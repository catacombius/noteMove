package com.spectraseq.core.dsp

import com.spectraseq.core.model.BEATS_PER_BAR
import com.spectraseq.core.model.Clip
import com.spectraseq.core.model.DRUM_BASE_NOTE
import com.spectraseq.core.model.DRUM_PAD_COUNT
import com.spectraseq.core.model.DrumKit
import com.spectraseq.core.model.DrumPad
import com.spectraseq.core.model.DrumSound
import com.spectraseq.core.model.Note
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Cuts a sample into slices for the drum pads (Move / Simpler-style slicing).
 *
 * Slice points are frame positions in ascending order; slice `i` plays from `points[i]` up to the next
 * point (or the end of the sample). At most [MAX_SLICES] slices, one per pad.
 */
object Slicer {
    const val MAX_SLICES = DRUM_PAD_COUNT

    enum class Mode(val label: String) { TRANSIENTS("Transients"), BEATS("Beats"), EQUAL("Equal"), MANUAL("Manual") }

    /**
     * Transient (onset) detection: spectral flux on a log-magnitude STFT with an adaptive median threshold.
     * [sensitivity] 0..1 — higher finds more, quieter onsets. Returns the strongest onsets (up to [maxSlices]),
     * each pinned to where its attack starts.
     */
    fun transients(data: FloatArray, sampleRate: Int, sensitivity: Float, maxSlices: Int = MAX_SLICES, minGapMs: Float = 45f): IntArray {
        if (data.size < 2048) return intArrayOf(0)
        val fftSize = 1024
        val hop = 256
        val fft = Fft(fftSize)
        val window = FloatArray(fftSize) { (0.5 - 0.5 * kotlin.math.cos(2 * Math.PI * it / fftSize)).toFloat() }
        val frames = (data.size - fftSize) / hop + 1
        val bins = fftSize / 2
        val re = FloatArray(fftSize)
        val im = FloatArray(fftSize)
        var prev = FloatArray(bins)
        var cur = FloatArray(bins)
        val flux = FloatArray(frames)
        for (f in 0 until frames) {
            val off = f * hop
            for (i in 0 until fftSize) { re[i] = data[off + i] * window[i]; im[i] = 0f }
            fft.transform(re, im)
            var sum = 0f
            for (b in 1 until bins) {
                val mag = ln(1f + 100f * sqrt(re[b] * re[b] + im[b] * im[b]))
                cur[b] = mag
                val d = mag - prev[b]
                if (d > 0f && f > 0) sum += d
            }
            flux[f] = sum
            val t = prev; prev = cur; cur = t
        }
        val peak = flux.maxOrNull() ?: 0f
        if (peak <= 1e-6f) return intArrayOf(0)
        val s = sensitivity.coerceIn(0f, 1f)
        val mult = 2.4f - 1.3f * s            // how far above the local median a peak must rise
        val floor = peak * (0.30f - 0.27f * s) // ignore tiny bumps relative to the strongest onset
        val half = 8
        val gapFrames = max(1, (minGapMs / 1000f * sampleRate / hop).roundToInt())
        val window2 = FloatArray(half * 2 + 1)
        val picked = ArrayList<Pair<Int, Float>>()
        var last = -gapFrames
        for (f in 1 until frames - 1) {
            val v = flux[f]
            if (v < floor || v < flux[f - 1] || v < flux[f + 1]) continue
            var n = 0
            for (k in max(0, f - half)..min(frames - 1, f + half)) window2[n++] = flux[k]
            java.util.Arrays.sort(window2, 0, n)
            val median = window2[n / 2]
            if (v <= median * mult + 1e-6f) continue
            if (f - last < gapFrames) {
                // Keep the stronger of two onsets that are too close together.
                if (picked.isNotEmpty() && v > picked.last().second) { picked[picked.lastIndex] = f to v; last = f }
                continue
            }
            picked.add(f to v); last = f
        }
        val strongest = picked.sortedByDescending { it.second }.take(maxSlices - 1).map { refine(data, it.first * hop + fftSize / 2 - hop, sampleRate) }
        return normalize(listOf(0) + strongest, data.size, sampleRate, maxSlices)
    }

    /** [count] slices of equal length. */
    fun equal(frames: Int, count: Int): IntArray {
        val n = count.coerceIn(1, MAX_SLICES)
        return IntArray(n) { (it.toLong() * frames / n).toInt() }
    }

    /** A slice every [divisionBeats] when the whole sample is a loop of [loopBeats] beats. */
    fun beats(frames: Int, loopBeats: Double, divisionBeats: Double): IntArray {
        val n = (loopBeats / divisionBeats).roundToInt().coerceIn(1, MAX_SLICES)
        val framesPerSlice = frames * divisionBeats / loopBeats
        return IntArray(n) { (it * framesPerSlice).roundToInt() }.filter { it < frames }.toIntArray()
    }

    /**
     * Best guess at a loop's length in beats at [tempo]: the duration rounded to a musical length
     * (¼, ½, 1, 2, 3, 4, 6, 8, 12 or 16 bars).
     */
    fun guessLoopBeats(frames: Int, sampleRate: Int, tempo: Double): Double {
        val beats = frames.toDouble() / sampleRate * tempo / 60.0
        val choices = doubleArrayOf(1.0, 2.0, 4.0, 8.0, 12.0, 16.0, 24.0, 32.0, 48.0, 64.0)
        return choices.minBy { abs(ln(it / max(beats, 0.25))) }
    }

    /** Sorts, de-duplicates and drops points that would make slices shorter than ~10 ms. */
    fun normalize(points: List<Int>, frames: Int, sampleRate: Int, maxSlices: Int = MAX_SLICES): IntArray {
        val minLen = max(1, sampleRate / 100)
        val out = ArrayList<Int>()
        for (p in points.map { it.coerceIn(0, max(0, frames - 1)) }.sorted()) {
            if (out.isEmpty() || p - out.last() >= minLen) out.add(p)
        }
        if (out.isEmpty()) out.add(0)
        return out.take(maxSlices).toIntArray()
    }

    /**
     * Pins a rough onset (±15 ms) to where the amplitude actually starts rising, backs off ~1 ms so the
     * attack stays intact, and lands on a zero crossing.
     */
    fun refine(data: FloatArray, pos: Int, sampleRate: Int): Int {
        val block = max(8, sampleRate / 1000)
        val a = (pos - sampleRate * 15 / 1000).coerceIn(0, data.size - 1)
        val b = (pos + sampleRate * 20 / 1000).coerceIn(a + block, data.size)
        val n = (b - a) / block
        if (n < 3) return snapToZero(data, pos.coerceIn(0, data.size - 1), sampleRate)
        val e = FloatArray(n) { k -> var sum = 0f; for (i in a + k * block until a + (k + 1) * block) sum += abs(data[i]); sum }
        var peakK = 0
        for (k in 1 until n) if (e[k] > e[peakK]) peakK = k
        var base = e[0]
        for (k in 0..peakK) base = min(base, e[k])
        val thr = base + (e[peakK] - base) * 0.15f
        var k = 0
        while (k < peakK && e[k] < thr) k++
        val onset = (a + k * block - block).coerceAtLeast(0)
        return snapToZero(data, onset, sampleRate)
    }

    /** Nearest zero crossing at or before [pos], within 1 ms. */
    fun snapToZero(data: FloatArray, pos: Int, sampleRate: Int): Int {
        var z = pos.coerceIn(0, data.size - 1)
        val lim = max(0, z - sampleRate / 1000)
        while (z > lim && (data[z] > 0f) == (data[z - 1] > 0f)) z--
        return if (z > lim) z else pos.coerceIn(0, data.size - 1)
    }

    /** (start, end) fractions of the sample for slice [index]. */
    fun region(points: IntArray, index: Int, frames: Int): Pair<Float, Float> {
        val a = points[index]
        val b = if (index + 1 < points.size) points[index + 1] else frames
        return a.toFloat() / frames to b.toFloat() / frames
    }

    /**
     * A drum kit with slice `i` on pad `i` (pads beyond the slices keep [base]'s sounds).
     * With [choke] every slice cuts the previous one off, like a mono Simpler in Slice mode.
     */
    fun toDrumKit(name: String, sampleId: String, points: IntArray, frames: Int, base: DrumKit, choke: Boolean): DrumKit {
        val pads = base.pads.mapIndexed { i, pad ->
            if (i >= points.size) pad else {
                val (s, e) = region(points, i, frames)
                DrumPad("Slice ${i + 1}", DrumSound.SAMPLE, sampleId = sampleId, start = s, end = e, chokeGroup = if (choke) 4 else 0, level = 0.85f)
            }
        }
        return DrumKit(name, pads)
    }

    /**
     * A clip that plays the slices in their original order and timing, so the loop plays back as recorded —
     * and follows the set's tempo, because each slice is triggered on its beat.
     */
    fun toClip(points: IntArray, frames: Int, loopBeats: Double, name: String = ""): Clip {
        val notes = points.indices.map { i ->
            val start = points[i].toDouble() / frames * loopBeats
            val end = (if (i + 1 < points.size) points[i + 1].toDouble() else frames.toDouble()) / frames * loopBeats
            Note(DRUM_BASE_NOTE + i, roundBeat(start), max(1.0 / 32, roundBeat(end) - roundBeat(start)), 100)
        }
        val length = max(BEATS_PER_BAR / 4, kotlin.math.ceil(loopBeats - 1e-6))
        return Clip(name = name, lengthBeats = length).withNotes(notes)
    }

    private fun roundBeat(b: Double) = Math.round(b * 960) / 960.0
}

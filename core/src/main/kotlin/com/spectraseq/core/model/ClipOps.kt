package com.spectraseq.core.model

import com.spectraseq.core.engine.PlayedNote
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.round

/** Pure clip editing operations shared by the UI and tests. */
object ClipOps {
    /** Grid options shown in the UI, in beats. */
    val GRIDS = listOf(1.0 to "1/4", 0.5 to "1/8", 0.25 to "1/16", 0.125 to "1/32", 1.0 / 3 to "1/4T", 1.0 / 6 to "1/8T", 1.0 / 12 to "1/16T")

    fun quantize(clip: Clip, grid: Double, strength: Double = 1.0): Clip = clip.withNotes(
        clip.notes.map { n ->
            val q = round(n.start / grid) * grid
            val start = n.start + (q - n.start) * strength
            n.copy(start = wrap(start, clip.lengthBeats))
        }.let(::dedupe),
    )

    fun snap(beat: Double, grid: Double): Double = round(beat / grid) * grid

    /** Doubles the loop and copies its content, like "Duplicate Loop" in Live / Move. */
    fun duplicateLoop(clip: Clip): Clip = clip.copy(
        lengthBeats = clip.lengthBeats * 2,
        notes = clip.notes + clip.notes.map { it.copy(start = it.start + clip.lengthBeats) },
    )

    fun setLength(clip: Clip, beats: Double): Clip {
        val len = max(0.25, beats)
        return clip.withNotes(clip.notes.filter { it.start < len }.map { if (it.end > len) it.copy(duration = len - it.start) else it })
    }

    fun transpose(clip: Clip, semitones: Int): Clip =
        clip.withNotes(clip.notes.map { it.copy(pitch = (it.pitch + semitones).coerceIn(0, 127)) })

    fun nudge(clip: Clip, beats: Double): Clip =
        clip.withNotes(clip.notes.map { it.copy(start = wrap(it.start + beats, clip.lengthBeats)) })

    /** Toggles a note on a step grid (step sequencer). */
    fun toggleStep(clip: Clip, pitch: Int, start: Double, stepLength: Double, velocity: Int = 100): Clip {
        val existing = clip.notes.firstOrNull { it.pitch == pitch && kotlin.math.abs(it.start - start) < stepLength / 2 }
        return if (existing != null) clip.withNotes(clip.notes - existing)
        else clip.withNotes(clip.notes + Note(pitch, start, stepLength * 0.95, velocity))
    }

    fun notesInStep(clip: Clip, start: Double, stepLength: Double): List<Note> =
        clip.notes.filter { it.start >= start - 1e-6 && it.start < start + stepLength - 1e-6 }

    /** Merges recorded notes into a clip, replacing near-identical duplicates. */
    fun merge(clip: Clip, recorded: List<Note>, quantizeGrid: Double?): Clip {
        val incoming = recorded.map { n ->
            val s = if (quantizeGrid != null) wrap(snap(n.start, quantizeGrid), clip.lengthBeats) else n.start
            n.copy(start = s, duration = max(0.03, minOf(n.duration, clip.lengthBeats)))
        }
        return clip.withNotes(dedupe(clip.notes + incoming))
    }

    private fun dedupe(notes: List<Note>): List<Note> {
        val out = ArrayList<Note>()
        for (n in notes.sortedBy { it.start }) {
            if (out.none { it.pitch == n.pitch && kotlin.math.abs(it.start - n.start) < 0.02 }) out.add(n)
        }
        return out
    }

    private fun wrap(beat: Double, len: Double): Double {
        var b = beat % len
        if (b < 0) b += len
        if (len - b < 1e-6) b = 0.0
        return b
    }

    /**
     * Move-style Capture: turns notes played without recording into a clip. While the transport
     * runs the clip is aligned to the bar grid; otherwise it starts at the first played note.
     */
    fun capture(played: List<PlayedNote>, maxBars: Int = 16): Clip? {
        if (played.isEmpty()) return null
        // Only keep the last phrase: drop notes separated from the rest by a long gap (> 2 bars).
        val sorted = played.sortedBy { it.startBeat }
        var firstIdx = 0
        for (i in 1 until sorted.size) {
            if (sorted[i].startBeat - (sorted[i - 1].startBeat + sorted[i - 1].duration) > BEATS_PER_BAR * 2) firstIdx = i
        }
        val phrase = sorted.subList(firstIdx, sorted.size)
        val useTransport = phrase.all { it.transportBeat != null }
        fun startOf(p: PlayedNote) = if (useTransport) p.transportBeat!! else p.startBeat
        val first = phrase.minOf { startOf(it) }
        val last = phrase.maxOf { startOf(it) + it.duration }
        val origin = if (useTransport) floor(first / BEATS_PER_BAR) * BEATS_PER_BAR else snap(first, 1.0)
        val spanBars = ceil((last - origin - 0.05) / BEATS_PER_BAR).toInt().coerceAtLeast(1)
        var bars = 1
        while (bars < spanBars && bars < maxBars) bars *= 2
        val length = bars * BEATS_PER_BAR
        val notes = phrase.map { p ->
            var s = startOf(p) - origin
            if (s < 0) s = 0.0
            Note(p.pitch, s % length, minOf(p.duration, length), p.velocity)
        }
        return Clip(lengthBeats = length).withNotes(notes)
    }
}

package com.notemove.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** In-place iterative radix-2 complex FFT. */
class Fft(val size: Int) {
    init { require(size > 1 && size and (size - 1) == 0) { "FFT size must be a power of two" } }
    private val bits = Integer.numberOfTrailingZeros(size)
    private val cosT = FloatArray(size / 2) { cos(2 * PI * it / size).toFloat() }
    private val sinT = FloatArray(size / 2) { sin(2 * PI * it / size).toFloat() }
    private val rev = IntArray(size) { Integer.reverse(it) ushr (32 - bits) }

    /** Forward transform when [inverse] is false; the inverse is scaled by 1/size. */
    fun transform(re: FloatArray, im: FloatArray, inverse: Boolean = false) {
        for (i in 0 until size) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= size) {
            val half = len / 2
            val step = size / len
            var i = 0
            while (i < size) {
                var k = 0
                for (j in i until i + half) {
                    val wr = cosT[k]
                    val wi = if (inverse) sinT[k] else -sinT[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr; im[j + half] = im[j] - xi
                    re[j] += xr; im[j] += xi
                    k += step
                }
                i += len
            }
            len *= 2
        }
        if (inverse) { val s = 1f / size; for (i in 0 until size) { re[i] *= s; im[i] *= s } }
    }
}

/** Magnitude spectrogram quantised to 0..255 (dB scale), for display. Frame f covers samples f*hop … f*hop+fftSize. */
class Spectrogram(val frames: Int, val bins: Int, val hop: Int, val fftSize: Int, val sampleRate: Int, val data: ByteArray) {
    fun level(frame: Int, bin: Int): Int = data[frame * bins + bin].toInt() and 0xFF
    fun binHz(bin: Int): Float = bin * sampleRate.toFloat() / fftSize
    fun frameSeconds(frame: Int): Float = frame * hop / sampleRate.toFloat()
}

/**
 * A region of the time–frequency plane, in seconds and Hz. Rectangles and brush strokes are both
 * expressed as [weight] (0 = untouched, 1 = fully selected) with soft edges, so edits don't click or ring.
 */
sealed interface SpectralSelection {
    fun weight(t: Float, hz: Float): Float

    data class Rect(val t0: Float, val t1: Float, val f0: Float, val f1: Float, val featherSec: Float = 0.01f, val featherOct: Float = 0.08f) : SpectralSelection {
        override fun weight(t: Float, hz: Float): Float {
            val wt = edge(t, min(t0, t1), max(t0, t1), featherSec)
            if (wt == 0f) return 0f
            val o = octaves(hz)
            val wf = edge(o, octaves(min(f0, f1)), octaves(max(f0, f1)), featherOct)
            return wt * wf
        }
    }

    /** A painted stroke: circles in (seconds, octaves) space, radius given in octaves (time radius is scaled by [secPerOct]). */
    data class Brush(val points: List<Pair<Float, Float>>, val radiusOct: Float, val secPerOct: Float) : SpectralSelection {
        override fun weight(t: Float, hz: Float): Float {
            val o = octaves(hz)
            var w = 0f
            for ((pt, phz) in points) {
                val dt = (t - pt) / secPerOct
                val dof = o - octaves(phz)
                val d = sqrt(dt * dt + dof * dof) / radiusOct
                if (d < 1f) { w = max(w, 1f - d * d * d); if (w >= 1f) break }
            }
            return w
        }
    }

    data class Union(val parts: List<SpectralSelection>) : SpectralSelection {
        override fun weight(t: Float, hz: Float): Float { var w = 0f; for (p in parts) w = max(w, p.weight(t, hz)); return w }
    }

    companion object {
        fun octaves(hz: Float) = (ln(max(hz, 10f) / 20f) / ln(2f))
        private fun edge(x: Float, lo: Float, hi: Float, feather: Float): Float {
            if (x < lo - feather || x > hi + feather) return 0f
            if (x in lo..hi) return 1f
            val d = if (x < lo) lo - x else x - hi
            return if (feather <= 0f) 0f else 0.5f + 0.5f * cos(PI.toFloat() * d / feather)
        }
    }
}

enum class SpectralOp(val label: String) { ATTENUATE("Attenuate"), ERASE("Erase"), BOOST("Boost"), KEEP("Keep only") }

/** Spectral analysis and editing (STFT → per-bin gain → overlap-add), as in a spectral sample editor. */
object Spectral {
    const val FFT_SIZE = 2048
    const val HOP = FFT_SIZE / 4

    private fun hann(n: Int) = FloatArray(n) { (0.5 - 0.5 * cos(2 * PI * it / n)).toFloat() }

    fun spectrogram(data: FloatArray, sampleRate: Int, fftSize: Int = FFT_SIZE, hop: Int = fftSize / 4, floorDb: Float = -90f): Spectrogram {
        val fft = Fft(fftSize)
        val win = hann(fftSize)
        val bins = fftSize / 2 + 1
        val frames = max(1, (data.size + hop - 1) / hop)
        val out = ByteArray(frames * bins)
        val re = FloatArray(fftSize); val im = FloatArray(fftSize)
        val norm = 2f / (fftSize * 0.5f)
        for (f in 0 until frames) {
            val start = f * hop - fftSize / 2
            for (i in 0 until fftSize) {
                val idx = start + i
                re[i] = if (idx in data.indices) data[idx] * win[i] else 0f
                im[i] = 0f
            }
            fft.transform(re, im)
            for (b in 0 until bins) {
                val mag = sqrt(re[b] * re[b] + im[b] * im[b]) * norm
                val db = 20f * log10(max(mag, 1e-9f))
                out[f * bins + b] = (((db - floorDb) / -floorDb).coerceIn(0f, 1f) * 255f).toInt().toByte()
            }
        }
        return Spectrogram(frames, bins, hop, fftSize, sampleRate, out)
    }

    /**
     * Applies [op] to the parts of [data] covered by [selection]. Unselected time–frequency cells pass
     * through untouched (perfect reconstruction with a Hann² window at 75% overlap).
     */
    fun apply(data: FloatArray, sampleRate: Int, selection: SpectralSelection, op: SpectralOp, amountDb: Float = -24f,
              onProgress: (Float) -> Unit = {}): FloatArray {
        val n = FFT_SIZE
        val hop = HOP
        val fft = Fft(n)
        val win = hann(n)
        val bins = n / 2 + 1
        val out = FloatArray(data.size)
        val norm = FloatArray(data.size)
        val re = FloatArray(n); val im = FloatArray(n)
        val gainSel = when (op) {
            SpectralOp.ATTENUATE -> dbToGain(-abs(amountDb))
            SpectralOp.ERASE -> 0f
            SpectralOp.BOOST -> dbToGain(abs(amountDb))
            SpectralOp.KEEP -> 1f
        }
        val binHz = FloatArray(bins) { it * sampleRate.toFloat() / n }
        val frames = (data.size + n) / hop + 1
        for (f in 0 until frames) {
            val start = f * hop - n
            val center = (start + n / 2).toFloat() / sampleRate
            for (i in 0 until n) {
                val idx = start + i
                re[i] = if (idx in data.indices) data[idx] * win[i] else 0f
                im[i] = 0f
            }
            fft.transform(re, im)
            for (b in 0 until bins) {
                val w = selection.weight(center, binHz[b])
                val g = if (op == SpectralOp.KEEP) w else 1f + (gainSel - 1f) * w
                re[b] *= g; im[b] *= g
                if (b in 1 until n / 2) { re[n - b] *= g; im[n - b] *= g }
            }
            fft.transform(re, im, inverse = true)
            for (i in 0 until n) {
                val idx = start + i
                if (idx in out.indices) { out[idx] += re[i] * win[i]; norm[idx] += win[i] * win[i] }
            }
            if (f % 64 == 0) onProgress(f.toFloat() / frames)
        }
        for (i in out.indices) out[i] = if (norm[i] > 1e-6f) out[i] / norm[i] else 0f
        onProgress(1f)
        return out
    }
}

/** Time-domain edits for the sample editor. Ranges are frame indices [from, to). */
object SampleOps {
    fun trim(d: FloatArray, from: Int, to: Int) = d.copyOfRange(from.coerceIn(0, d.size), to.coerceIn(from, d.size))

    fun delete(d: FloatArray, from: Int, to: Int): FloatArray {
        val a = from.coerceIn(0, d.size); val b = to.coerceIn(a, d.size)
        return d.copyOfRange(0, a) + d.copyOfRange(b, d.size)
    }

    fun reverse(d: FloatArray, from: Int = 0, to: Int = d.size): FloatArray {
        val out = d.copyOf(); val a = from.coerceIn(0, d.size); val b = to.coerceIn(a, d.size)
        for (i in a until b) out[i] = d[b - 1 - (i - a)]
        return out
    }

    fun gain(d: FloatArray, db: Float, from: Int = 0, to: Int = d.size): FloatArray {
        val g = dbToGain(db); val out = d.copyOf()
        for (i in from.coerceIn(0, d.size) until to.coerceIn(0, d.size)) out[i] = (out[i] * g).coerceIn(-1f, 1f)
        return out
    }

    fun normalize(d: FloatArray, peak: Float = 0.89f, from: Int = 0, to: Int = d.size): FloatArray {
        var m = 0f
        for (i in from.coerceIn(0, d.size) until to.coerceIn(0, d.size)) m = max(m, abs(d[i]))
        if (m < 1e-6f) return d
        val g = peak / m; val out = d.copyOf()
        for (i in from.coerceIn(0, d.size) until to.coerceIn(0, d.size)) out[i] *= g
        return out
    }

    fun fade(d: FloatArray, from: Int, to: Int, fadeIn: Boolean): FloatArray {
        val out = d.copyOf(); val a = from.coerceIn(0, d.size); val b = to.coerceIn(a, d.size)
        val len = max(1, b - a)
        for (i in a until b) {
            val x = (i - a).toFloat() / len
            out[i] *= if (fadeIn) x * x else (1 - x) * (1 - x)
        }
        return out
    }

    fun silence(d: FloatArray, from: Int, to: Int): FloatArray {
        val out = d.copyOf(); for (i in from.coerceIn(0, d.size) until to.coerceIn(0, d.size)) out[i] = 0f
        return out
    }

    /** Peak envelope for drawing a waveform with [columns] columns over [from, to). */
    fun peaks(d: FloatArray, columns: Int, from: Int = 0, to: Int = d.size): FloatArray {
        val out = FloatArray(columns)
        val a = from.coerceIn(0, d.size); val b = to.coerceIn(a, d.size)
        val span = (b - a).toFloat() / max(1, columns)
        for (c in 0 until columns) {
            val s = (a + c * span).toInt(); val e = min(b, (a + (c + 1) * span).toInt().coerceAtLeast(s + 1))
            var m = 0f
            for (i in s until e) if (i < d.size) m = max(m, abs(d[i]))
            out[c] = m
        }
        return out
    }

    /** Short fades at both ends so edited samples never click. */
    fun declick(d: FloatArray, sampleRate: Int): FloatArray {
        val n = min(d.size / 4, sampleRate / 500)
        if (n < 2) return d
        val out = d.copyOf()
        for (i in 0 until n) { val g = i.toFloat() / n; out[i] *= g; out[d.size - 1 - i] *= g }
        return out
    }

}

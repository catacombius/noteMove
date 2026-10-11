package com.spectraseq.core.export

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow

/** Small offline helpers for rendered audio: peak normalisation and sample-rate conversion. */
object AudioTools {
    fun peak(s: OfflineRenderer.Stereo): Float {
        var p = 0f
        for (i in s.left.indices) p = max(p, max(abs(s.left[i]), abs(s.right[i])))
        return p
    }

    /** Scales so the loudest sample sits at [peakDb] dBFS (silence is left alone). */
    fun normalize(s: OfflineRenderer.Stereo, peakDb: Float = -1f): OfflineRenderer.Stereo {
        val p = peak(s)
        if (p < 1e-6f) return s
        val g = 10f.pow(peakDb / 20f) / p
        return OfflineRenderer.Stereo(FloatArray(s.left.size) { s.left[it] * g }, FloatArray(s.right.size) { s.right[it] * g })
    }

    /** Converts between sample rates with 4-point Hermite interpolation (good enough for 44.1 ↔ 48 kHz). */
    fun resample(s: OfflineRenderer.Stereo, from: Int, to: Int): OfflineRenderer.Stereo {
        if (from == to) return s
        return OfflineRenderer.Stereo(resample(s.left, from, to), resample(s.right, from, to))
    }

    fun resample(x: FloatArray, from: Int, to: Int): FloatArray {
        if (from == to || x.isEmpty()) return x
        val ratio = from.toDouble() / to
        val n = (x.size / ratio).toInt()
        val out = FloatArray(n)
        fun at(i: Int) = x[i.coerceIn(0, x.size - 1)]
        for (k in 0 until n) {
            val pos = k * ratio
            val i = pos.toInt()
            val t = (pos - i).toFloat()
            val xm1 = at(i - 1); val x0 = at(i); val x1 = at(i + 1); val x2 = at(i + 2)
            val c1 = 0.5f * (x1 - xm1)
            val c2 = xm1 - 2.5f * x0 + 2f * x1 - 0.5f * x2
            val c3 = 0.5f * (x2 - xm1) + 1.5f * (x0 - x1)
            out[k] = ((c3 * t + c2) * t + c1) * t + x0
        }
        return out
    }
}

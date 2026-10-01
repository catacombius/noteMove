package com.notemove.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan
import kotlin.math.tanh

fun midiToHz(note: Float): Float = 440f * 2f.pow((note - 69f) / 12f)

fun dbToGain(db: Float): Float = 10f.pow(db / 20f)

fun clamp(v: Float, lo: Float, hi: Float) = if (v < lo) lo else if (v > hi) hi else v

/** Maps 0..1 to 20 Hz .. 20 kHz exponentially. */
fun normToCutoffHz(n: Float): Float = 20f * 1000f.pow(clamp(n, 0f, 1f))

/** Equal-power pan: returns left gain; right gain is [panRight]. */
fun panLeft(pan: Float): Float = kotlin.math.cos((clamp(pan, -1f, 1f) + 1f) * 0.25f * PI.toFloat())
fun panRight(pan: Float): Float = kotlin.math.sin((clamp(pan, -1f, 1f) + 1f) * 0.25f * PI.toFloat())

/** Linear-segment ADSR evaluated per sample. Times in seconds. */
class Adsr(private val sampleRate: Float) {
    enum class Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }

    var stage = Stage.IDLE
        private set
    var level = 0f
        private set
    private var attackInc = 0f
    private var decayCoef = 0f
    private var releaseCoef = 0f
    private var sustain = 1f

    fun set(attack: Float, decay: Float, sustain: Float, release: Float) {
        attackInc = 1f / max(1f, attack * sampleRate)
        decayCoef = coef(decay)
        releaseCoef = coef(release)
        this.sustain = clamp(sustain, 0f, 1f)
    }

    private fun coef(seconds: Float): Float = exp(-4.6f / max(1f, seconds * sampleRate))

    fun gate(on: Boolean) {
        stage = if (on) Stage.ATTACK else if (stage != Stage.IDLE) Stage.RELEASE else Stage.IDLE
    }

    fun reset() {
        stage = Stage.IDLE; level = 0f
    }

    val isActive get() = stage != Stage.IDLE

    fun next(): Float {
        when (stage) {
            Stage.ATTACK -> {
                level += attackInc
                if (level >= 1f) { level = 1f; stage = Stage.DECAY }
            }
            Stage.DECAY -> {
                level = sustain + (level - sustain) * decayCoef
                if (abs(level - sustain) < 1e-4f) { level = sustain; stage = Stage.SUSTAIN }
            }
            Stage.SUSTAIN -> {
                level = sustain
                if (sustain <= 0f) stage = Stage.IDLE
            }
            Stage.RELEASE -> {
                level *= releaseCoef
                if (level < 1e-4f) { level = 0f; stage = Stage.IDLE }
            }
            Stage.IDLE -> level = 0f
        }
        return level
    }
}

/** Simple exponential decay envelope used by drum voices. */
class Decay {
    var value = 0f
    private var coef = 0f
    fun trigger(start: Float, seconds: Float, sampleRate: Float) {
        value = start
        coef = exp(-6.9f / max(1f, seconds * sampleRate))
    }
    fun next(): Float { val v = value; value *= coef; return v }
}

/** Topology-preserving-transform state variable filter (Zavalishin / Simper). */
class Svf {
    private var ic1 = 0f
    private var ic2 = 0f
    private var g = 0f
    private var k = 1f
    private var a1 = 0f
    private var a2 = 0f
    private var a3 = 0f
    private var lastCut = -1f
    private var lastRes = -1f
    var lp = 0f; private set
    var bp = 0f; private set
    var hp = 0f; private set

    fun set(cutoffHz: Float, resonance: Float, sampleRate: Float) {
        if (cutoffHz == lastCut && resonance == lastRes) return
        lastCut = cutoffHz; lastRes = resonance
        val fc = clamp(cutoffHz, 10f, sampleRate * 0.49f)
        g = tan(PI.toFloat() * fc / sampleRate)
        k = 2f - 1.96f * clamp(resonance, 0f, 1f)
        a1 = 1f / (1f + g * (g + k))
        a2 = g * a1
        a3 = g * a2
    }

    fun process(x: Float) {
        val v3 = x - ic2
        val v1 = a1 * ic1 + a2 * v3
        val v2 = ic2 + a2 * ic1 + a3 * v3
        ic1 = 2f * v1 - ic1
        ic2 = 2f * v2 - ic2
        lp = v2; bp = v1; hp = x - k * v1 - v2
    }

    fun reset() { ic1 = 0f; ic2 = 0f }
}

/** PolyBLEP-band-limited oscillator. */
class Oscillator {
    var phase = 0f
    private var tri = 0f

    fun next(wave: com.notemove.core.model.Waveform, inc: Float, rnd: Noise): Float {
        val t = phase
        val out = when (wave) {
            com.notemove.core.model.Waveform.SAW -> 2f * t - 1f - polyBlep(t, inc)
            com.notemove.core.model.Waveform.SQUARE -> square(t, inc)
            com.notemove.core.model.Waveform.TRIANGLE -> {
                // Leaky integration of a band-limited square.
                tri = inc * 4f * square(t, inc) + (1f - inc * 0.5f) * tri
                tri
            }
            com.notemove.core.model.Waveform.SINE -> fastSin(t)
            com.notemove.core.model.Waveform.NOISE -> rnd.next()
        }
        phase += inc
        if (phase >= 1f) phase -= 1f
        return out
    }

    private fun square(t: Float, inc: Float): Float {
        var v = if (t < 0.5f) 1f else -1f
        v += polyBlep(t, inc)
        var t2 = t + 0.5f
        if (t2 >= 1f) t2 -= 1f
        v -= polyBlep(t2, inc)
        return v
    }

    private fun polyBlep(t: Float, dt: Float): Float {
        if (dt <= 0f) return 0f
        return when {
            t < dt -> { val x = t / dt; x + x - x * x - 1f }
            t > 1f - dt -> { val x = (t - 1f) / dt; x * x + x + x + 1f }
            else -> 0f
        }
    }
}

/** Sine of a phase in 0..1 using a parabolic approximation (good enough for audio-rate oscillators). */
fun fastSin(phase: Float): Float {
    val x = (phase - kotlin.math.floor(phase)) * 2f - 1f // -1..1 maps to -pi..pi
    val y = 4f * x * (1f - abs(x))
    return -(0.225f * (y * abs(y) - y) + y)
}

class Noise(seed: Int = 22222) {
    private var s = seed
    fun next(): Float {
        s = s * 196314165 + 907633515
        return (s shr 8) * (1f / 8388608f)
    }
}

fun softClip(x: Float): Float = if (x > 3f) 1f else if (x < -3f) -1f else x * (27f + x * x) / (27f + 9f * x * x)

fun drive(x: Float, amount: Float): Float {
    if (amount <= 0.001f) return x
    val g = 1f + amount * 12f
    return tanh(x * g) / tanh(g).coerceAtLeast(0.2f) * (1f - amount * 0.3f)
}

/** Stereo feedback delay with ping-pong option, length set in samples. */
class StereoDelay(maxSamples: Int) {
    private val bufL = FloatArray(maxSamples)
    private val bufR = FloatArray(maxSamples)
    private var write = 0
    private var delay = maxSamples / 2
    private val lpL = OnePole(); private val lpR = OnePole()

    fun setDelaySamples(samples: Int) { delay = samples.coerceIn(1, bufL.size - 1) }

    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, feedback: Float, level: Float) {
        val size = bufL.size
        val fb = clamp(feedback, 0f, 0.95f)
        for (i in 0 until n) {
            var r = write - delay
            if (r < 0) r += size
            val dl = bufL[r]; val dr = bufR[r]
            // Ping-pong: left input feeds right line and vice versa.
            bufL[write] = (inL[i] + inR[i]) * 0.5f + lpR.lp(dr, 0.35f) * fb
            bufR[write] = lpL.lp(dl, 0.35f) * fb
            outL[i] += dl * level
            outR[i] += dr * level
            write++
            if (write >= size) write = 0
        }
    }

    fun clear() { bufL.fill(0f); bufR.fill(0f) }
}

class OnePole {
    private var z = 0f
    fun lp(x: Float, coef: Float): Float { z += coef * (x - z); return z }
}

/** Freeverb-style reverb (8 combs + 4 allpasses per channel). */
class Reverb(sampleRate: Float) {
    private val scale = sampleRate / 44100f
    private val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
    private val allTunings = intArrayOf(556, 441, 341, 225)
    private val spread = 23
    private val combL = combTunings.map { Comb((it * scale).toInt()) }
    private val combR = combTunings.map { Comb(((it + spread) * scale).toInt()) }
    private val apL = allTunings.map { AllPass((it * scale).toInt()) }
    private val apR = allTunings.map { AllPass(((it + spread) * scale).toInt()) }

    fun process(inL: FloatArray, inR: FloatArray, outL: FloatArray, outR: FloatArray, n: Int, size: Float, damping: Float, level: Float) {
        val fb = 0.7f + clamp(size, 0f, 1f) * 0.28f
        val damp = clamp(damping, 0f, 1f) * 0.4f
        for (i in 0 until n) {
            val input = (inL[i] + inR[i]) * 0.015f
            var l = 0f; var r = 0f
            for (c in combL) l += c.process(input, fb, damp)
            for (c in combR) r += c.process(input, fb, damp)
            for (a in apL) l = a.process(l)
            for (a in apR) r = a.process(r)
            outL[i] += l * level
            outR[i] += r * level
        }
    }

    fun clear() { combL.forEach { it.clear() }; combR.forEach { it.clear() }; apL.forEach { it.clear() }; apR.forEach { it.clear() } }

    private class Comb(size: Int) {
        private val buf = FloatArray(max(1, size))
        private var idx = 0
        private var store = 0f
        fun process(x: Float, fb: Float, damp: Float): Float {
            val out = buf[idx]
            store = out * (1f - damp) + store * damp
            buf[idx] = x + store * fb
            if (++idx >= buf.size) idx = 0
            return out
        }
        fun clear() { buf.fill(0f); store = 0f }
    }

    private class AllPass(size: Int) {
        private val buf = FloatArray(max(1, size))
        private var idx = 0
        fun process(x: Float): Float {
            val b = buf[idx]
            val out = -x + b
            buf[idx] = x + b * 0.5f
            if (++idx >= buf.size) idx = 0
            return out
        }
        fun clear() = buf.fill(0f)
    }
}

/** Peak limiter with instant attack and smooth release, followed by a soft clipper. */
class Limiter(sampleRate: Float) {
    private var gain = 1f
    private val release = exp(-1f / (0.15f * sampleRate))
    var reduction = 0f
        private set

    fun process(l: FloatArray, r: FloatArray, n: Int, ceiling: Float = 0.95f) {
        var minGain = 1f
        for (i in 0 until n) {
            val peak = max(abs(l[i]), abs(r[i]))
            val target = if (peak * gain > ceiling) ceiling / peak else 1f
            gain = if (target < gain) target else min(1f, target - (target - gain) * release)
            l[i] = softClip(l[i] * gain)
            r[i] = softClip(r[i] * gain)
            if (gain < minGain) minGain = gain
        }
        reduction = 1f - minGain
    }
}

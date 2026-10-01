package com.notemove.core.dsp

import com.notemove.core.model.EffectSlot
import com.notemove.core.model.EffectType
import com.notemove.core.model.syncDivision
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** A stereo insert effect processing a block in place. Values are the slot's normalised knobs. */
interface AudioEffect {
    fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext)
    fun reset() {}
}

/** Per-block information effects may need (tempo for synced times, position for synced LFOs). */
class FxContext(val sampleRate: Float) {
    var tempo = 120.0
    /** Beat position at the start of the block (free-running when the transport is stopped). */
    var beat = 0.0
    val samplesPerBeat get() = sampleRate * 60.0 / tempo
}

fun createEffect(type: EffectType, sr: Float): AudioEffect = when (type) {
    EffectType.FILTER -> AutoFilterFx(sr)
    EffectType.SATURATOR -> SaturatorFx()
    EffectType.REDUX -> ReduxFx()
    EffectType.CHORUS -> ChorusFx(sr)
    EffectType.PHASER -> PhaserFx(sr)
    EffectType.COMPRESSOR -> CompressorFx(sr)
    EffectType.EQ -> EqFx(sr)
    EffectType.TREMOLO -> TremoloFx()
    EffectType.DELAY -> EchoFx(sr)
    EffectType.REVERB -> ReverbFx(sr)
}

/** A chain of effects that follows the slot list; processors are reused while slot ids and types match. */
class EffectChain(private val sr: Float) {
    private var ids: List<String> = emptyList()
    private val procs = HashMap<String, Pair<EffectType, AudioEffect>>()
    private var order: List<Pair<EffectSlot, AudioEffect>> = emptyList()
    private var lastSlots: List<EffectSlot>? = null

    fun process(slots: List<EffectSlot>, l: FloatArray, r: FloatArray, n: Int, ctx: FxContext) {
        if (slots.isEmpty()) return
        if (slots !== lastSlots) sync(slots)
        for ((slot, fx) in order) if (slot.enabled) fx.process(l, r, n, slot.values, ctx)
        // Guard the rest of the mix against blow-ups from extreme settings.
        for (i in 0 until n) {
            if (!l[i].isFinite()) l[i] = 0f
            if (!r[i].isFinite()) r[i] = 0f
        }
    }

    private fun sync(slots: List<EffectSlot>) {
        lastSlots = slots
        val newIds = slots.map { it.id }
        if (newIds != ids) procs.keys.retainAll(newIds.toSet())
        ids = newIds
        order = slots.map { s ->
            val existing = procs[s.id]
            val fx = if (existing != null && existing.first == s.type) existing.second
            else createEffect(s.type, sr).also { procs[s.id] = s.type to it }
            s to fx
        }
    }
}

private fun v(list: List<Float>, i: Int, def: Float = 0f) = list.getOrElse(i) { def }

// ---------------------------------------------------------------------------------------------

private class AutoFilterFx(private val sr: Float) : AudioEffect {
    private val fl = Svf(); private val fr = Svf()
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val type = v(v, 0); val cut = v(v, 1, 0.7f); val res = v(v, 2, 0.3f)
        val (div, _) = syncDivision(v(v, 3, 0.6f))
        val lfoAmt = v(v, 4); val mix = v(v, 5, 1f)
        // Tempo-synced LFO, phase locked to the beat so it stays in time after export.
        val phase = ((ctx.beat / div) % 1.0).toFloat()
        val lfo = fastSin(phase)
        val c = clamp(cut + lfo * lfoAmt * 0.35f, 0f, 1f)
        val hz = normToCutoffHz(c)
        fl.set(hz, res * 0.95f, sr); fr.set(hz, res * 0.95f, sr)
        for (i in 0 until n) {
            fl.process(l[i]); fr.process(r[i])
            val yl = when { type < 0.33f -> fl.lp; type < 0.66f -> fl.bp; else -> fl.hp }
            val yr = when { type < 0.33f -> fr.lp; type < 0.66f -> fr.bp; else -> fr.hp }
            l[i] += (yl - l[i]) * mix; r[i] += (yr - r[i]) * mix
        }
    }
    override fun reset() { fl.reset(); fr.reset() }
}

private class SaturatorFx : AudioEffect {
    private val tl = OnePole(); private val tr = OnePole()
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val g = dbToGain(v(v, 0, 0.4f) * 36f)
        val tone = 0.05f + v(v, 1, 0.6f) * 0.95f
        val out = dbToGain((v(v, 2, 0.5f) * 2 - 1) * 12f) / max(1f, sqrt(g))
        val mix = v(v, 3, 1f)
        for (i in 0 until n) {
            val sl = tl.lp(tanh(l[i] * g), tone) * out
            val sr = tr.lp(tanh(r[i] * g), tone) * out
            l[i] += (sl - l[i]) * mix; r[i] += (sr - r[i]) * mix
        }
    }
}

private class ReduxFx : AudioEffect {
    private var holdL = 0f; private var holdR = 0f; private var counter = 0
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val levels = 2f.pow(EffectType.bitsFor(v(v, 0, 0.5f)) - 1)
        val ds = EffectType.downsampleFor(v(v, 1, 0.3f))
        val mix = v(v, 2, 1f)
        for (i in 0 until n) {
            if (counter++ % ds == 0) {
                holdL = kotlin.math.round(l[i] * levels) / levels
                holdR = kotlin.math.round(r[i] * levels) / levels
            }
            l[i] += (holdL - l[i]) * mix; r[i] += (holdR - r[i]) * mix
        }
    }
}

/** Modulated delay line with linear interpolation. */
private class ModDelay(size: Int) {
    private val buf = FloatArray(size)
    private var w = 0
    fun write(x: Float) { buf[w] = x; w = (w + 1) % buf.size }
    fun read(delaySamples: Float): Float {
        var pos = w - 1 - delaySamples
        while (pos < 0) pos += buf.size
        val i = pos.toInt() % buf.size
        val f = pos - pos.toInt()
        val j = (i + 1) % buf.size
        return buf[i] + (buf[j] - buf[i]) * f
    }
    fun clear() = buf.fill(0f)
}

private class ChorusFx(private val sr: Float) : AudioEffect {
    private val dl = ModDelay((sr * 0.06f).toInt()); private val dr = ModDelay((sr * 0.06f).toInt())
    private var phase = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val rv = v(v, 0, 0.3f); val rate = 0.05f + rv * rv * 6f
        val depth = v(v, 1, 0.5f) * sr * 0.008f
        val width = v(v, 2, 0.8f)
        val mix = v(v, 3, 0.5f)
        val base = sr * 0.012f
        for (i in 0 until n) {
            phase += rate / sr; if (phase >= 1f) phase -= 1f
            val ml = base + depth * (1 + fastSin(phase)) * 0.5f
            val mr = base + depth * (1 + fastSin(phase + 0.25f * width)) * 0.5f
            dl.write(l[i]); dr.write(r[i])
            val wl = dl.read(ml); val wr = dr.read(mr)
            l[i] = l[i] * (1 - mix * 0.5f) + wl * mix
            r[i] = r[i] * (1 - mix * 0.5f) + wr * mix
        }
    }
    override fun reset() { dl.clear(); dr.clear() }
}

private class PhaserFx(private val sr: Float) : AudioEffect {
    private val stagesL = FloatArray(6); private val stagesR = FloatArray(6)
    private var fbL = 0f; private var fbR = 0f
    private var phase = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val rv = v(v, 0, 0.3f); val rate = 0.05f + rv * rv * 6f
        val depth = v(v, 1, 0.7f)
        val fb = v(v, 2, 0.5f) * 0.9f
        val mix = v(v, 3, 0.5f)
        for (i in 0 until n) {
            phase += rate / sr; if (phase >= 1f) phase -= 1f
            val sweep = 0.5f + 0.5f * fastSin(phase) * depth
            val fc = 200f * 20f.pow(sweep) // 200 Hz .. 4 kHz
            val t = kotlin.math.tan(PI.toFloat() * fc / sr)
            val a = (t - 1) / (t + 1)
            var xl = l[i] + fbL * fb
            var xr = r[i] + fbR * fb
            for (k in 0 until 6) {
                val yl = a * xl + stagesL[k]; stagesL[k] = xl - a * yl; xl = yl
                val yr = a * xr + stagesR[k]; stagesR[k] = xr - a * yr; xr = yr
            }
            fbL = xl; fbR = xr
            l[i] = l[i] * (1 - mix * 0.5f) + xl * mix
            r[i] = r[i] * (1 - mix * 0.5f) + xr * mix
        }
    }
    override fun reset() { stagesL.fill(0f); stagesR.fill(0f); fbL = 0f; fbR = 0f }
}

private class CompressorFx(private val sr: Float) : AudioEffect {
    private var env = 0f
    var gainReduction = 0f
        private set
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val thr = -40f + v(v, 0, 0.6f) * 40f
        val rv = v(v, 1, 0.3f); val ratio = 1f + rv * rv * 19f
        val av = v(v, 2, 0.2f); val atk = exp(-1f / ((0.1f + av * av * 100f) * 0.001f * sr))
        val relv = v(v, 3, 0.3f); val rel = exp(-1f / ((10f + relv * relv * 990f) * 0.001f * sr))
        val makeup = dbToGain((v(v, 4, 0.5f) * 2 - 1) * 12f)
        val mix = v(v, 5, 1f)
        for (i in 0 until n) {
            val x = max(abs(l[i]), abs(r[i]))
            env = if (x > env) atk * env + (1 - atk) * x else rel * env + (1 - rel) * x
            val levelDb = 20f * ln(max(env, 1e-6f)) / ln(10f)
            val over = levelDb - thr
            val grDb = if (over > 0) over - over / ratio else 0f
            val g = dbToGain(-grDb) * makeup
            gainReduction = grDb
            l[i] += (l[i] * g - l[i]) * mix
            r[i] += (r[i] * g - r[i]) * mix
        }
    }
}

/** RBJ biquad. */
private class Biquad {
    private var b0 = 1f; private var b1 = 0f; private var b2 = 0f; private var a1 = 0f; private var a2 = 0f
    private var x1 = 0f; private var x2 = 0f; private var y1 = 0f; private var y2 = 0f

    fun set(kind: Int, f: Float, q: Float, gainDb: Float, sr: Float) {
        val a = 10f.pow(gainDb / 40f)
        val w0 = 2f * PI.toFloat() * clamp(f, 10f, sr * 0.45f) / sr
        val cw = cos(w0); val sw = sin(w0)
        val alpha = sw / (2f * q)
        val nb0: Float; val nb1: Float; val nb2: Float; val na0: Float; val na1: Float; val na2: Float
        when (kind) {
            0 -> { // low shelf
                val s = 2f * sqrt(a) * alpha
                nb0 = a * ((a + 1) - (a - 1) * cw + s); nb1 = 2 * a * ((a - 1) - (a + 1) * cw); nb2 = a * ((a + 1) - (a - 1) * cw - s)
                na0 = (a + 1) + (a - 1) * cw + s; na1 = -2 * ((a - 1) + (a + 1) * cw); na2 = (a + 1) + (a - 1) * cw - s
            }
            1 -> { // peak
                nb0 = 1 + alpha * a; nb1 = -2 * cw; nb2 = 1 - alpha * a
                na0 = 1 + alpha / a; na1 = -2 * cw; na2 = 1 - alpha / a
            }
            else -> { // high shelf
                val s = 2f * sqrt(a) * alpha
                nb0 = a * ((a + 1) + (a - 1) * cw + s); nb1 = -2 * a * ((a - 1) + (a + 1) * cw); nb2 = a * ((a + 1) + (a - 1) * cw - s)
                na0 = (a + 1) - (a - 1) * cw + s; na1 = 2 * ((a - 1) - (a + 1) * cw); na2 = (a + 1) - (a - 1) * cw - s
            }
        }
        b0 = nb0 / na0; b1 = nb1 / na0; b2 = nb2 / na0; a1 = na1 / na0; a2 = na2 / na0
    }

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x; y2 = y1; y1 = y
        return y
    }
}

private class EqFx(private val sr: Float) : AudioEffect {
    private val bands = Array(6) { Biquad() }
    private var last = FloatArray(4) { -1f }
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val lo = (v(v, 0, 0.5f) * 2 - 1) * 15f
        val mid = (v(v, 1, 0.5f) * 2 - 1) * 15f
        val midF = normToCutoffHz(v(v, 2, 0.55f))
        val hi = (v(v, 3, 0.5f) * 2 - 1) * 15f
        val cur = floatArrayOf(lo, mid, midF, hi)
        if (!cur.contentEquals(last)) {
            last = cur
            for (c in 0 until 2) {
                bands[c * 3].set(0, 200f, 0.7f, lo, sr)
                bands[c * 3 + 1].set(1, midF, 0.9f, mid, sr)
                bands[c * 3 + 2].set(2, 4000f, 0.7f, hi, sr)
            }
        }
        for (i in 0 until n) {
            l[i] = bands[2].process(bands[1].process(bands[0].process(l[i])))
            r[i] = bands[5].process(bands[4].process(bands[3].process(r[i])))
        }
    }
}

private class TremoloFx : AudioEffect {
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val (div, _) = syncDivision(v(v, 0, 0.5f))
        val depth = v(v, 1, 0.6f)
        val mode = v(v, 2, 0f)
        val square = v(v, 3, 0f) >= 0.5f
        val spb = ctx.samplesPerBeat
        for (i in 0 until n) {
            val ph = (((ctx.beat + i / spb) / div) % 1.0).toFloat()
            val s = if (square) (if (ph < 0.5f) 1f else -1f) else fastSin(ph)
            // Blend between auto-pan (opposite gains) and tremolo (same gain).
            val panAmt = (1f - mode * 2).coerceIn(0f, 1f)
            val tremAmt = (mode * 2 - 1).coerceIn(0f, 1f)
            val gp = s * depth * panAmt
            val gt = 1f - depth * tremAmt * (0.5f + 0.5f * s)
            l[i] *= (1f - max(0f, gp)) * gt
            r[i] *= (1f - max(0f, -gp)) * gt
        }
    }
}

private class EchoFx(private val sr: Float) : AudioEffect {
    private val dl = ModDelay((sr * 4.2f).toInt()); private val dr = ModDelay((sr * 4.2f).toInt())
    private val tl = OnePole(); private val tr = OnePole()
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val (div, _) = syncDivision(v(v, 0, 0.5f))
        val time = (div * ctx.samplesPerBeat).toFloat().coerceIn(1f, sr * 4f)
        val fb = v(v, 1, 0.4f) * 0.95f
        val ping = v(v, 2, 1f) >= 0.5f
        val tone = 0.05f + v(v, 3, 0.6f) * 0.9f
        val mix = v(v, 4, 0.3f)
        for (i in 0 until n) {
            val el = tl.lp(dl.read(time), tone); val er = tr.lp(dr.read(time), tone)
            if (ping) { dl.write((l[i] + r[i]) * 0.5f + er * fb); dr.write(el * fb) }
            else { dl.write(l[i] + el * fb); dr.write(r[i] + er * fb) }
            l[i] += el * mix; r[i] += er * mix
        }
    }
    override fun reset() { dl.clear(); dr.clear() }
}

private class ReverbFx(sr: Float) : AudioEffect {
    private val rev = Reverb(sr)
    private var wl = FloatArray(0); private var wr = FloatArray(0)
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        if (wl.size < n) { wl = FloatArray(n); wr = FloatArray(n) }
        wl.fill(0f, 0, n); wr.fill(0f, 0, n)
        rev.process(l, r, wl, wr, n, v(v, 0, 0.6f), v(v, 1, 0.4f), 1f)
        val mix = v(v, 2, 0.3f)
        for (i in 0 until n) { l[i] = l[i] * (1 - mix * 0.5f) + wl[i] * mix; r[i] = r[i] * (1 - mix * 0.5f) + wr[i] * mix }
    }
    override fun reset() = rev.clear()
}

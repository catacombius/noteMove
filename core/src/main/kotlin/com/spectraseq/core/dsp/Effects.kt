package com.spectraseq.core.dsp

import com.spectraseq.core.model.EffectSlot
import com.spectraseq.core.model.EffectType
import com.spectraseq.core.model.syncDivision
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
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
    EffectType.FLANGER -> FlangerFx(sr)
    EffectType.DISTORTION -> DistortionFx()
    EffectType.RING_MOD -> RingModFx(sr)
    EffectType.UTILITY -> UtilityFx(sr)
    EffectType.GATE -> GateFx(sr)
    EffectType.TRANSIENT -> TransientFx(sr)
    EffectType.DUCKER -> DuckerFx()
    EffectType.TAPE -> TapeFx(sr)
    EffectType.STUTTER -> StutterFx(sr)
    EffectType.PITCH -> PitchShiftFx(sr)
    EffectType.LIMITER -> LimiterFx(sr)
    EffectType.RACK -> RackFx(sr)
    EffectType.PATCH -> PatchFx(sr)
}

/** Used until a rack / patch definition is attached. */
object PassThroughFx : AudioEffect {
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) = Unit
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
        for ((slot, fx) in order) {
            if (!slot.enabled) continue
            when (fx) {
                is RackFx -> fx.preset = slot.rack
                is PatchFx -> fx.graph = slot.patch
            }
            fx.process(l, r, n, slot.values, ctx)
        }
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
internal class ModDelay(size: Int) {
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

// --------------------------------------------------------------------------------------------- round 7 effects

private class FlangerFx(private val sr: Float) : AudioEffect {
    private val dl = ModDelay((sr * 0.02f).toInt()); private val dr = ModDelay((sr * 0.02f).toInt())
    private var phase = 0f
    private var fbl = 0f; private var fbr = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val rv = v(v, 0, 0.25f); val rate = 0.02f + rv * rv * 5f
        val depth = v(v, 1, 0.7f)
        val fb = (v(v, 2, 0.7f) * 2 - 1) * 0.95f
        val base = (0.3f + v(v, 3, 0.3f) * 7.7f) * 0.001f * sr
        val mix = v(v, 4, 0.5f)
        for (i in 0 until n) {
            phase += rate / sr; if (phase >= 1f) phase -= 1f
            val ml = base * (1f - depth * 0.95f * (0.5f + 0.5f * fastSin(phase)))
            val mr = base * (1f - depth * 0.95f * (0.5f + 0.5f * fastSin(phase + 0.25f)))
            dl.write(l[i] + fbl * fb); dr.write(r[i] + fbr * fb)
            val wl = dl.read(max(1f, ml)); val wr = dr.read(max(1f, mr))
            fbl = softClip(wl); fbr = softClip(wr)
            l[i] = l[i] * (1 - mix * 0.5f) + wl * mix
            r[i] = r[i] * (1 - mix * 0.5f) + wr * mix
        }
    }
    override fun reset() { dl.clear(); dr.clear(); fbl = 0f; fbr = 0f }
}

private class DistortionFx : AudioEffect {
    private val tl = OnePole(); private val tr = OnePole()
    private val dcl = OnePole(); private val dcr = OnePole()
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val g = dbToGain(v(v, 0, 0.4f) * 48f)
        val mode = (v(v, 1, 0f) * 4).toInt().coerceIn(0, 3)
        val tone = 0.03f + v(v, 2, 0.6f) * 0.97f
        val out = dbToGain((v(v, 3, 0.5f) * 2 - 1) * 12f) / max(1f, g.pow(0.35f))
        val mix = v(v, 4, 1f)
        fun shape(x: Float): Float = when (mode) {
            0 -> tanh(x)
            1 -> x.coerceIn(-1f, 1f)
            2 -> { // wavefolder
                var y = x
                var k = 0
                while ((y > 1f || y < -1f) && k++ < 8) y = if (y > 1f) 2f - y else -2f - y
                y
            }
            else -> { // asymmetric "tube"
                if (x >= 0) tanh(x) else tanh(x * 0.6f) * 0.85f
            }
        }
        for (i in 0 until n) {
            var yl = shape(l[i] * g); var yr = shape(r[i] * g)
            // The tube curve is asymmetric: remove its DC offset.
            yl -= dcl.lp(yl, 0.001f); yr -= dcr.lp(yr, 0.001f)
            yl = tl.lp(yl, tone) * out; yr = tr.lp(yr, tone) * out
            l[i] += (yl - l[i]) * mix; r[i] += (yr - r[i]) * mix
        }
    }
}

/**
 * 90° phase-difference network (two 4-stage allpass chains, Olli Niemitalo's design) for single-sideband
 * frequency shifting. Each stage is y(n) = a²·(x(n) + y(n−2)) − x(n−2).
 */
private class Hilbert {
    private val ca = floatArrayOf(0.6923878f, 0.9360654f, 0.9882295f, 0.9987488f).map { it * it }.toFloatArray()
    private val cb = floatArrayOf(0.4021921f, 0.8561711f, 0.9722910f, 0.9952885f).map { it * it }.toFloatArray()
    private val xa1 = FloatArray(4); private val xa2 = FloatArray(4); private val ya1 = FloatArray(4); private val ya2 = FloatArray(4)
    private val xb1 = FloatArray(4); private val xb2 = FloatArray(4); private val yb1 = FloatArray(4); private val yb2 = FloatArray(4)
    private var delayed = 0f
    var re = 0f; var im = 0f
    fun process(x: Float) {
        var a = x
        for (k in 0 until 4) {
            val y = ca[k] * (a + ya2[k]) - xa2[k]
            xa2[k] = xa1[k]; xa1[k] = a; ya2[k] = ya1[k]; ya1[k] = y; a = y
        }
        var b = x
        for (k in 0 until 4) {
            val y = cb[k] * (b + yb2[k]) - xb2[k]
            xb2[k] = xb1[k]; xb1[k] = b; yb2[k] = yb1[k]; yb1[k] = y; b = y
        }
        re = a; im = delayed; delayed = b
    }
}

private class RingModFx(private val sr: Float) : AudioEffect {
    private var phase = 0.0
    private val hl = Hilbert(); private val hr = Hilbert()
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val f = 5f * (5000f / 5f).pow(v(v, 0, 0.4f))
        val shift = v(v, 1, 0f) >= 0.5f
        val mix = v(v, 2, 0.5f)
        val inc = f / sr
        for (i in 0 until n) {
            phase += inc; if (phase >= 1.0) phase -= 1.0
            val c = cos(2 * PI * phase).toFloat(); val s = sin(2 * PI * phase).toFloat()
            val yl: Float; val yr: Float
            if (shift) {
                hl.process(l[i]); hr.process(r[i])
                yl = hl.re * c - hl.im * s; yr = hr.re * c - hr.im * s
            } else { yl = l[i] * c; yr = r[i] * c }
            l[i] += (yl - l[i]) * mix; r[i] += (yr - r[i]) * mix
        }
    }
}

private class UtilityFx(private val sr: Float) : AudioEffect {
    private val lowS = Svf()
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val width = v(v, 0, 0.5f) * 2f
        val bassV = v(v, 1, 0f)
        val gain = dbToGain((v(v, 2, 0.5f) * 2 - 1) * 24f)
        val pan = v(v, 3, 0.5f) * 2 - 1
        val flip = v(v, 4, 0f) >= 0.5f
        val monoBass = bassV >= 0.02f
        if (monoBass) { val f = 40f * 10f.pow(bassV); lowS.set(f, 0.1f, sr) }
        val gl = panLeft(pan) * 1.414f * gain; val gr = panRight(pan) * 1.414f * gain
        for (i in 0 until n) {
            val m = (l[i] + r[i]) * 0.5f
            var side = (l[i] - r[i]) * 0.5f * width
            if (monoBass) { lowS.process(side); side = lowS.hp }
            var yl = (m + side) * gl
            val yr = (m - side) * gr
            if (flip) yl = -yl
            l[i] = yl; r[i] = yr
        }
    }
}

private class GateFx(private val sr: Float) : AudioEffect {
    private var env = 0f
    private var gain = 0f
    private var holdLeft = 0
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val thr = dbToGain(-80f + v(v, 0, 0.5f) * 80f)
        val relMs = 5f + v(v, 1, 0.3f).let { it * it } * 495f
        val rel = exp(-1f / (relMs * 0.001f * sr))
        val atk = exp(-1f / (0.0005f * sr))
        val patV = v(v, 2, 0f)
        val (div, _) = syncDivision(v(v, 3, 0.45f))
        val floorV = v(v, 4, 0f)
        val floor = if (floorV <= 0.001f) 0f else dbToGain(-60f + floorV * 60f)
        val pattern = if (patV < 0.06f) null else EffectType.GATE_PATTERNS[((patV - 0.06f) / 0.94f * (EffectType.GATE_PATTERNS.size - 1)).toInt().coerceIn(0, EffectType.GATE_PATTERNS.lastIndex)]
        val spb = ctx.samplesPerBeat
        for (i in 0 until n) {
            val x = max(abs(l[i]), abs(r[i]))
            env = if (x > env) x else env * 0.9995f
            var open = env > thr
            if (pattern != null) {
                val step = (((ctx.beat + i / spb) / div).toLong() % 16).toInt()
                open = open && pattern[step] == '1'
            }
            if (open) holdLeft = (0.01f * sr).toInt() else if (holdLeft > 0) holdLeft--
            val target = if (open || holdLeft > 0) 1f else floor
            gain = if (target > gain) target + (gain - target) * atk else target + (gain - target) * rel
            l[i] *= gain; r[i] *= gain
        }
    }
}

private class TransientFx(private val sr: Float) : AudioEffect {
    private var fast = 0f; private var slow = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val atkAmt = (v(v, 0, 0.5f) * 2 - 1)
        val susAmt = (v(v, 1, 0.5f) * 2 - 1)
        val out = dbToGain((v(v, 2, 0.5f) * 2 - 1) * 12f)
        val fa = exp(-1f / (0.001f * sr)); val fr = exp(-1f / (0.02f * sr))
        val sa = exp(-1f / (0.015f * sr)); val srl = exp(-1f / (0.2f * sr))
        for (i in 0 until n) {
            val x = max(abs(l[i]), abs(r[i]))
            fast = if (x > fast) fa * fast + (1 - fa) * x else fr * fast + (1 - fr) * x
            slow = if (x > slow) sa * slow + (1 - sa) * x else srl * slow + (1 - srl) * x
            // Positive when the level rises quickly (attack), negative in the tail (sustain).
            val diff = ((fast - slow) / max(slow, 1e-4f)).coerceIn(-1f, 4f)
            val g = if (diff > 0) 1f + atkAmt * min(diff, 2f) * 0.8f else 1f + susAmt * min(-diff * 2f, 1f) * 0.8f
            val gg = g.coerceIn(0.05f, 4f) * out
            l[i] *= gg; r[i] *= gg
        }
    }
}

/** Tempo-synced volume pump, the classic side-chained-to-the-kick feel without any routing. */
private class DuckerFx : AudioEffect {
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val (div, _) = syncDivision(v(v, 0, 0.67f))
        val depth = v(v, 1, 0.7f)
        val release = 0.1f + v(v, 2, 0.6f) * 0.9f
        val curve = v(v, 3, 0.5f)
        val spb = ctx.samplesPerBeat
        for (i in 0 until n) {
            val ph = (((ctx.beat + i / spb) / div) % 1.0).toFloat()
            val t = (ph / release).coerceIn(0f, 1f)
            val shape = when {
                curve < 0.33f -> t
                curve < 0.66f -> t * t * (3 - 2 * t)
                else -> 1f - (1f - t) * (1f - t) * (1f - t)
            }
            val g = 1f - depth * (1f - shape)
            l[i] *= g; r[i] *= g
        }
    }
}

private class TapeFx(private val sr: Float) : AudioEffect {
    private val dl = ModDelay((sr * 0.05f).toInt()); private val dr = ModDelay((sr * 0.05f).toInt())
    private var wowPh = 0f; private var flutPh = 0f
    private val noise = Noise(4242)
    private val lpL = Svf(); private val lpR = Svf()
    private val hpL = Svf(); private val hpR = Svf()
    private var crackle = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val wow = v(v, 0, 0.2f); val flutter = v(v, 1, 0.2f)
        val drive = 1f + v(v, 2, 0.3f) * 6f
        val nz = v(v, 3, 0.1f)
        val tone = 1500f * (20000f / 1500f).pow(v(v, 4, 0.6f))
        val lowCut = 20f * 20f.pow(v(v, 5, 0.1f))
        lpL.set(min(tone, sr * 0.45f), 0.05f, sr); lpR.set(min(tone, sr * 0.45f), 0.05f, sr)
        hpL.set(lowCut, 0.05f, sr); hpR.set(lowCut, 0.05f, sr)
        val base = 0.006f * sr
        for (i in 0 until n) {
            wowPh += 0.6f / sr; if (wowPh >= 1f) wowPh -= 1f
            flutPh += 9f / sr; if (flutPh >= 1f) flutPh -= 1f
            val mod = base * (1f + wow * 0.5f * fastSin(wowPh) + flutter * 0.06f * fastSin(flutPh))
            dl.write(l[i]); dr.write(r[i])
            var yl = tanh(dl.read(mod) * drive) / tanh(drive)
            var yr = tanh(dr.read(mod * 1.002f) * drive) / tanh(drive)
            if (nz > 0f) {
                val hiss = noise.next() * nz * 0.02f
                crackle = if (noise.next() > 0.9995f - nz * 0.002f) noise.next() * nz * 0.5f else crackle * 0.6f
                yl += hiss + crackle; yr += hiss + crackle
            }
            lpL.process(yl); lpR.process(yr)
            hpL.process(lpL.lp); hpR.process(lpR.lp)
            l[i] = hpL.hp; r[i] = hpR.hp
        }
    }
    override fun reset() { dl.clear(); dr.clear() }
}

/** Beat-repeat: at each grid step, sometimes (Chance) loop that step for a few repeats, optionally pitching down. */
private class StutterFx(private val sr: Float) : AudioEffect {
    private val bufL = FloatArray((sr * 2.5f).toInt()); private val bufR = FloatArray((sr * 2.5f).toInt())
    private var w = 0
    private var repeating = false
    private var sliceStart = 0
    private var sliceLen = 1
    private var readPos = 0.0
    private var repeatsLeft = 0
    private var lastStep = Long.MIN_VALUE
    private var rate = 1.0
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val (div, _) = syncDivision(v(v, 0, 0.45f))
        val chance = v(v, 1, 0.3f)
        val repeats = 1 + (v(v, 2, 0.3f) * 7).toInt()
        val pitch = (v(v, 3, 0.5f) * 2 - 1) * 12f
        val mix = v(v, 4, 1f)
        val spb = ctx.samplesPerBeat
        val stepLen = (div * spb).toInt().coerceIn(32, bufL.size / 2)
        for (i in 0 until n) {
            bufL[w] = l[i]; bufR[w] = r[i]
            val beat = ctx.beat + i / spb
            val step = kotlin.math.floor(beat / div).toLong()
            if (step != lastStep) {
                lastStep = step
                if (repeating && repeatsLeft > 0) {
                    repeatsLeft--
                    readPos = 0.0
                    rate *= 2.0.pow(pitch / 12.0 / repeats)
                    if (repeatsLeft == 0) repeating = false
                } else {
                    // Deterministic "random" from the step index so renders match playback.
                    val h = ((step * 2654435761L) ushr 8) and 0xFFFF
                    if (h / 65535f < chance) {
                        repeating = true
                        sliceStart = w
                        sliceLen = stepLen
                        readPos = 0.0
                        rate = 1.0
                        repeatsLeft = repeats
                    }
                }
            }
            if (repeating && repeatsLeft < repeats) {
                // Repeat the captured step (it started at sliceStart and is fully recorded by now).
                val idx = ((sliceStart + (readPos % sliceLen).toInt()) % bufL.size)
                val fade = min(1f, min(readPos.toFloat(), (sliceLen - readPos % sliceLen).toFloat()) / 64f)
                val yl = bufL[idx] * fade; val yr = bufR[idx] * fade
                readPos += rate
                l[i] += (yl - l[i]) * mix; r[i] += (yr - r[i]) * mix
            }
            w = (w + 1) % bufL.size
        }
    }
}

/** Two-grain delay-line pitch shifter (crossfaded Hann windows). */
private class PitchShiftFx(private val sr: Float) : AudioEffect {
    private val dl = ModDelay((sr * 0.25f).toInt()); private val dr = ModDelay((sr * 0.25f).toInt())
    private var ph = 0f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val semis = kotlin.math.round((v(v, 0, 0.5f) * 2 - 1) * 12f) + (v(v, 1, 0.5f) * 2 - 1) * 0.5f
        val ratio = 2f.pow(semis / 12f)
        val win = (0.02f + v(v, 2, 0.4f) * 0.08f) * sr
        val mix = v(v, 3, 1f)
        val inc = (1f - ratio) / win
        for (i in 0 until n) {
            dl.write(l[i]); dr.write(r[i])
            ph += inc; if (ph >= 1f) ph -= 1f; if (ph < 0f) ph += 1f
            val ph2 = (ph + 0.5f) % 1f
            val g1 = 0.5f - 0.5f * cos(2f * PI.toFloat() * ph)
            val g2 = 0.5f - 0.5f * cos(2f * PI.toFloat() * ph2)
            val d1 = 1f + ph * win; val d2 = 1f + ph2 * win
            val yl = dl.read(d1) * g1 + dl.read(d2) * g2
            val yr = dr.read(d1) * g1 + dr.read(d2) * g2
            l[i] += (yl - l[i]) * mix; r[i] += (yr - r[i]) * mix
        }
    }
    override fun reset() { dl.clear(); dr.clear() }
}

private class LimiterFx(private val sr: Float) : AudioEffect {
    private var gain = 1f
    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val pre = dbToGain(v(v, 0, 0f) * 18f)
        val ceiling = dbToGain(-12f + v(v, 1, 0.92f) * 12f)
        val relv = v(v, 2, 0.3f)
        val rel = exp(-1f / ((10f + relv * relv * 990f) * 0.001f * sr))
        for (i in 0 until n) {
            val xl = l[i] * pre; val xr = r[i] * pre
            val peak = max(abs(xl), abs(xr))
            val need = if (peak * gain > ceiling) ceiling / peak else 1f
            gain = if (need < gain) need else need + (gain - need) * rel
            l[i] = (xl * gain).coerceIn(-ceiling, ceiling); r[i] = (xr * gain).coerceIn(-ceiling, ceiling)
        }
    }
}

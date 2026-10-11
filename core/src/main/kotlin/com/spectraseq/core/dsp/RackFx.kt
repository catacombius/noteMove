package com.spectraseq.core.dsp

import com.spectraseq.core.model.EffectSlot
import com.spectraseq.core.model.LfoShape
import com.spectraseq.core.model.ModKind
import com.spectraseq.core.model.NodeKind
import com.spectraseq.core.model.PatchGraph
import com.spectraseq.core.model.RackPreset
import com.spectraseq.core.model.syncDivision
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.tanh

/**
 * Runs a rack: macros set inner parameters, modulators move them each block, the inner chain processes,
 * then dry and wet are mixed.
 */
class RackFx(private val sr: Float) : AudioEffect {
    var preset: RackPreset? = null
    private val chain = EffectChain(sr)
    private var dryL = FloatArray(0); private var dryR = FloatArray(0)
    private val modState = FloatArray(4)
    private val envs = FloatArray(4)
    private var lastSlots: List<EffectSlot> = emptyList()
    private var cachedPreset: RackPreset? = null
    private var cachedMacros: List<Float> = emptyList()
    private var baseValues: List<List<Float>> = emptyList()

    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val p = preset ?: return
        if (p.chain.isEmpty()) return
        if (p !== cachedPreset || v != cachedMacros) {
            cachedPreset = p; cachedMacros = v.toList()
            baseValues = p.innerValues(v)
            lastSlots = p.chain.mapIndexed { i, s -> s.copy(values = baseValues[i]) }
        }
        var slots = lastSlots
        if (p.mods.isNotEmpty()) {
            val level = blockLevel(l, r, n)
            val modded = baseValues.map { it.toMutableList() }
            p.mods.take(4).forEachIndexed { mi, m ->
                val x = modValue(mi, m.kind, m.rate, m.sync, m.shape, level, ctx, n)
                for (t in m.targets) {
                    val list = modded.getOrNull(t.slot) ?: continue
                    if (t.param !in list.indices) continue
                    list[t.param] = (list[t.param] + x * m.depth * (t.max - t.min)).coerceIn(0f, 1f)
                }
            }
            slots = p.chain.mapIndexed { i, s -> s.copy(values = modded[i]) }
        }
        val wet = p.dryWet.coerceIn(0f, 1f)
        if (wet < 0.999f) {
            if (dryL.size < n) { dryL = FloatArray(n); dryR = FloatArray(n) }
            System.arraycopy(l, 0, dryL, 0, n); System.arraycopy(r, 0, dryR, 0, n)
        }
        chain.process(slots, l, r, n, ctx)
        if (wet < 0.999f) for (i in 0 until n) { l[i] = dryL[i] + (l[i] - dryL[i]) * wet; r[i] = dryR[i] + (r[i] - dryR[i]) * wet }
    }

    private fun blockLevel(l: FloatArray, r: FloatArray, n: Int): Float {
        var m = 0f
        for (i in 0 until n) m = max(m, max(abs(l[i]), abs(r[i])))
        return m
    }

    /** -1..1 for LFO / random, 0..1 for the envelope follower. */
    private fun modValue(i: Int, kind: ModKind, rate: Float, sync: Boolean, shape: LfoShape, level: Float, ctx: FxContext, n: Int): Float {
        return when (kind) {
            ModKind.LFO -> {
                val ph = if (sync) ((ctx.beat / syncDivision(rate).first) % 1.0).toFloat()
                else { modState[i] = (modState[i] + n * (0.05f * 400f.pow(rate)) / sr) % 1f; modState[i] }
                lfoShape(shape, ph)
            }
            ModKind.ENVELOPE -> {
                val rel = exp(-n / ((0.02f + rate * 1.5f) * sr))
                envs[i] = if (level > envs[i]) level else envs[i] * rel
                (envs[i] * 2f).coerceAtMost(1f)
            }
            ModKind.RANDOM -> {
                val div = syncDivision(rate).first
                val step = floor(ctx.beat / div).toLong()
                val h = ((step * 2654435761L + i * 40503L) ushr 9) and 0xFFFF
                val target = h / 32767.5f - 1f
                envs[i] += (target - envs[i]) * 0.2f
                envs[i]
            }
        }
    }
}

fun lfoShape(shape: LfoShape, ph: Float): Float = when (shape) {
    LfoShape.SINE -> fastSin(ph)
    LfoShape.TRIANGLE -> 1f - 4f * abs(ph - 0.5f)
    LfoShape.SQUARE -> if (ph < 0.5f) 1f else -1f
    LfoShape.SAW -> ph * 2f - 1f
}

/** Runs a modular patch per channel (dual mono), node by node in dependency order, sample by sample. */
class PatchFx(private val sr: Float) : AudioEffect {
    var graph: PatchGraph? = null
    private var built: PatchGraph? = null
    private var left: PatchRuntime? = null
    private var right: PatchRuntime? = null

    override fun process(l: FloatArray, r: FloatArray, n: Int, v: List<Float>, ctx: FxContext) {
        val g = graph ?: return
        if (g !== built) {
            built = g
            left = PatchRuntime.build(g, sr, 1)
            right = PatchRuntime.build(g, sr, 2)
        }
        left?.process(l, n, v, ctx)
        right?.process(r, n, v, ctx)
    }

    override fun reset() { built = null }
}

/** Compiled patch for one channel. */
class PatchRuntime private constructor(
    private val sr: Float,
    private val kinds: Array<NodeKind>,
    private val params: Array<FloatArray>,
    /** For each node and input port: indices (into the node arrays) of the sources. */
    private val sources: Array<Array<IntArray>>,
    private val order: IntArray,
    seed: Int,
) {
    private val out = FloatArray(kinds.size)
    private val svf = Array(kinds.size) { Svf() }
    private val delays = Array(kinds.size) { i -> if (kinds[i] == NodeKind.DELAY) ModDelay((sr * 4.2f).toInt()) else null }
    private val delayFb = FloatArray(kinds.size)
    private val env = FloatArray(kinds.size)
    private val noise = Noise(seed * 7919)
    private val outIndex = kinds.indexOf(NodeKind.OUT)

    private fun input(node: Int, port: Int): Float {
        val s = sources[node].getOrNull(port) ?: return 0f
        var sum = 0f
        for (j in s) sum += out[j]
        return sum
    }

    fun process(buf: FloatArray, n: Int, knobs: List<Float>, ctx: FxContext) {
        if (outIndex < 0) return
        val spb = ctx.samplesPerBeat
        for (i in 0 until n) {
            val x = buf[i]
            val beat = ctx.beat + i / spb
            for (k in order) {
                val p = params[k]
                out[k] = when (kinds[k]) {
                    NodeKind.IN -> x
                    NodeKind.OUT -> input(k, 0)
                    NodeKind.FILTER -> {
                        val cut = (p.getOrElse(1) { 0.6f } + input(k, 1) * 0.5f).coerceIn(0f, 1f)
                        svf[k].set(normToCutoffHz(cut), p.getOrElse(2) { 0.3f } * 0.95f, sr)
                        svf[k].process(input(k, 0))
                        val type = p.getOrElse(0) { 0f }
                        when { type < 0.33f -> svf[k].lp; type < 0.66f -> svf[k].bp; else -> svf[k].hp }
                    }
                    NodeKind.DELAY -> {
                        val d = delays[k]!!
                        val time = (syncDivision(p.getOrElse(0) { 0.55f }).first * spb).toFloat().coerceIn(1f, sr * 4f)
                        val y = d.read(time)
                        d.write(input(k, 0) + y * p.getOrElse(1) { 0.4f } * 0.95f)
                        y
                    }
                    NodeKind.GAIN -> {
                        val g = dbToGain((p.getOrElse(0) { 0.5f } * 2 - 1) * 24f)
                        input(k, 0) * g * (1f + input(k, 1)).coerceIn(0f, 4f)
                    }
                    NodeKind.MIX -> {
                        val m = (p.getOrElse(0) { 0.5f } + input(k, 2)).coerceIn(0f, 1f)
                        input(k, 0) * (1 - m) + input(k, 1) * m
                    }
                    NodeKind.SHAPER -> {
                        val g = dbToGain(p.getOrElse(0) { 0.3f } * 36f)
                        val y = input(k, 0) * g
                        val shaped = when ((p.getOrElse(1) { 0f } * 3).toInt()) {
                            0 -> tanh(y)
                            1 -> y.coerceIn(-1f, 1f)
                            else -> { var z = y; var c = 0; while ((z > 1f || z < -1f) && c++ < 8) z = if (z > 1f) 2f - z else -2f - z; z }
                        }
                        shaped / max(1f, g.pow(0.3f))
                    }
                    NodeKind.LFO -> {
                        val ph = ((beat / syncDivision(p.getOrElse(0) { 0.6f }).first) % 1.0).toFloat()
                        val shape = LfoShape.entries[(p.getOrElse(1) { 0f } * 4).toInt().coerceIn(0, 3)]
                        lfoShape(shape, ph) * p.getOrElse(2) { 0.5f }
                    }
                    NodeKind.ENV -> {
                        val a = exp(-1f / ((0.001f + p.getOrElse(0) { 0.1f }.let { it * it } * 0.5f) * sr))
                        val rl = exp(-1f / ((0.001f + p.getOrElse(1) { 0.4f }.let { it * it } * 0.5f) * sr))
                        val lv = abs(input(k, 0))
                        env[k] = if (lv > env[k]) a * env[k] + (1 - a) * lv else rl * env[k] + (1 - rl) * lv
                        (env[k] * 2f).coerceAtMost(1f)
                    }
                    NodeKind.MATH -> {
                        val a = input(k, 0); val b = input(k, 1)
                        val y = when ((p.getOrElse(0) { 0f } * 4).toInt().coerceIn(0, 3)) { 0 -> a + b; 1 -> a * b; 2 -> max(a, b); else -> kotlin.math.min(a, b) }
                        y * p.getOrElse(1) { 0.5f } * 2f
                    }
                    NodeKind.KNOB -> {
                        val kv = knobs.getOrElse(NodeKind.knobIndex(p.getOrElse(0) { 0f })) { 0.5f }
                        val lo = p.getOrElse(1) { 0f }; val hi = p.getOrElse(2) { 1f }
                        lo + (hi - lo) * kv
                    }
                    NodeKind.NOISE -> noise.next() * p.getOrElse(0) { 0.2f }
                }
            }
            val y = out[outIndex]
            buf[i] = if (y.isFinite()) y.coerceIn(-4f, 4f) else 0f
        }
    }

    companion object {
        fun build(g: PatchGraph, sr: Float, seed: Int): PatchRuntime? {
            val order = g.evaluationOrder() ?: return null
            val index = g.nodes.mapIndexed { i, n -> n.id to i }.toMap()
            val kinds = Array(g.nodes.size) { g.nodes[it].kind }
            val params = Array(g.nodes.size) { g.nodes[it].params.toFloatArray() }
            val sources = Array(g.nodes.size) { ni ->
                val node = g.nodes[ni]
                Array(node.kind.inputs.size) { port -> g.wires.filter { it.to == node.id && it.port == port }.mapNotNull { index[it.from] }.toIntArray() }
            }
            return PatchRuntime(sr, kinds, params, sources, order.map { index.getValue(it.id) }.toIntArray(), seed)
        }
    }
}

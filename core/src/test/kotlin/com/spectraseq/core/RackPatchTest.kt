package com.spectraseq.core

import com.spectraseq.core.dsp.EffectChain
import com.spectraseq.core.dsp.FxContext
import com.spectraseq.core.export.ProjectJson
import com.spectraseq.core.model.EffectSlot
import com.spectraseq.core.model.EffectType
import com.spectraseq.core.model.FactoryPatches
import com.spectraseq.core.model.FactoryRacks
import com.spectraseq.core.model.LfoShape
import com.spectraseq.core.model.MacroTarget
import com.spectraseq.core.model.ModKind
import com.spectraseq.core.model.Modulator
import com.spectraseq.core.model.NodeKind
import com.spectraseq.core.model.PatchGraph
import com.spectraseq.core.model.PatchNode
import com.spectraseq.core.model.PatchWire
import com.spectraseq.core.model.Project
import com.spectraseq.core.model.RackMacro
import com.spectraseq.core.model.RackPreset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class RackPatchTest {
    private val sr = 48000

    private fun energy(slots: List<EffectSlot>, freq: Double, blocks: Int = 10, n: Int = 4800, perBlock: MutableList<Double>? = null): Double {
        val chain = EffectChain(sr.toFloat())
        val ctx = FxContext(sr.toFloat())
        var e = 0.0
        for (b in 0 until blocks) {
            val l = FloatArray(n) { (sin(2 * PI * freq * (b * n + it) / sr) * 0.5).toFloat() }
            val r = l.copyOf()
            chain.process(slots, l, r, n, ctx)
            ctx.beat += n / ctx.samplesPerBeat
            assertTrue(l.all { it.isFinite() })
            val be = l.sumOf { it * it.toDouble() }
            perBlock?.add(be)
            if (b >= 2) e += be
        }
        return e
    }

    private val lowpassRack = RackPreset(
        chain = listOf(EffectSlot(type = EffectType.FILTER, values = listOf(0f, 0.5f, 0.1f, 0.6f, 0f, 1f))),
        macros = listOf(RackMacro("Cutoff", listOf(MacroTarget(0, 1, 0f, 1f)))) + List(7) { RackMacro("M$it") },
    )

    @Test
    fun macroDrivesInnerParameter() {
        val closed = energy(listOf(lowpassRack.toSlot().copy(values = List(8) { 0f })), 8000.0)
        val open = energy(listOf(lowpassRack.toSlot().copy(values = listOf(1f) + List(7) { 0f })), 8000.0)
        assertTrue("closed $closed vs open $open", open > closed * 20)
    }

    @Test
    fun lfoModulatorMovesTheParameterOverTime() {
        val rack = lowpassRack.copy(mods = listOf(Modulator(ModKind.LFO, rate = 0.85f, sync = true, shape = LfoShape.SINE, depth = 1f, targets = listOf(MacroTarget(0, 1)))))
        val blocks = mutableListOf<Double>()
        energy(listOf(rack.toSlot().copy(values = listOf(0.5f) + List(7) { 0f })), 3000.0, blocks = 40, n = 1200, perBlock = blocks)
        val tail = blocks.drop(4)
        assertTrue("energy should swing: ${tail.min()} .. ${tail.max()}", tail.max() > tail.min() * 3)
    }

    @Test
    fun patchFilterMatchesTheFilterEffect() {
        val g = PatchGraph(
            nodes = listOf(PatchNode(0, NodeKind.IN, 0f, 0f), PatchNode(1, NodeKind.FILTER, 0f, 0f, listOf(0f, 0.45f, 0.3f)), PatchNode(2, NodeKind.OUT, 0f, 0f)),
            wires = listOf(PatchWire(0, 1, 0), PatchWire(1, 2, 0)),
        )
        val viaPatch = energy(listOf(g.toSlot()), 2000.0)
        val viaFx = energy(listOf(EffectSlot(type = EffectType.FILTER, values = listOf(0f, 0.45f, 0.3f, 0.6f, 0f, 1f))), 2000.0)
        assertEquals(viaFx, viaPatch, viaFx * 0.01)
    }

    @Test
    fun loopsNeedADelay() {
        var g = PatchGraph(nodes = listOf(PatchNode(0, NodeKind.IN, 0f, 0f), PatchNode(1, NodeKind.GAIN, 0f, 0f), PatchNode(2, NodeKind.GAIN, 0f, 0f), PatchNode(3, NodeKind.OUT, 0f, 0f)), wires = emptyList())
        g = g.toggleWire(PatchWire(1, 2, 0)).toggleWire(PatchWire(2, 1, 0))
        assertEquals(1, g.wires.size) // the second wire would close a loop and is refused
        assertNull(g.copy(wires = listOf(PatchWire(1, 2, 0), PatchWire(2, 1, 0))).evaluationOrder())
        assertNotNull(FactoryPatches.DIRTY_ECHO.evaluationOrder())
    }

    @Test
    fun factoryRacksAndPatchesWorkAndSerialize() {
        for (r in FactoryRacks.ALL) assertTrue(r.name, energy(listOf(r.toSlot()), 440.0) > 1.0)
        for (p in FactoryPatches.ALL) assertTrue(p.name, energy(listOf(p.toSlot()), 440.0) > 1.0)
        val project = Project.createDefault("x").let { p ->
            p.copy(masterEffects = listOf(FactoryRacks.WOBBLE.toSlot(), FactoryPatches.AUTO_WAH.toSlot()))
        }
        assertEquals(project, ProjectJson.decode(ProjectJson.encode(project)))
        // Dry/wet 0 is the dry signal.
        val dry = energy(emptyList<EffectSlot>().ifEmpty { listOf(EffectSlot(type = EffectType.UTILITY)) }, 440.0)
        val bypass = energy(listOf(FactoryRacks.LOFI_RADIO.copy(dryWet = 0f).toSlot()), 440.0)
        assertTrue(abs(dry - bypass) < dry * 0.02)
    }
}

package com.spectraseq.core.model

import kotlinx.serialization.Serializable
import java.util.Locale

/**
 * Building blocks of the modular patcher. Every node has one output (except Out) and named inputs;
 * several wires into one input add up. Audio runs per channel (dual mono). Feedback loops are only
 * allowed through a Delay node.
 */
@Serializable
enum class NodeKind(val label: String, val inputs: List<String>, val params: List<String>, val hasOutput: Boolean = true) {
    IN("Input", emptyList(), emptyList()),
    OUT("Output", listOf("In"), emptyList(), hasOutput = false),
    FILTER("Filter", listOf("In", "Cutoff mod"), listOf("Type", "Cutoff", "Reso")),
    DELAY("Delay", listOf("In"), listOf("Time", "Feedback")),
    GAIN("Gain", listOf("In", "Gain mod"), listOf("Gain")),
    MIX("Mix", listOf("A", "B", "Mix mod"), listOf("Mix")),
    SHAPER("Shaper", listOf("In"), listOf("Drive", "Mode")),
    LFO("LFO", emptyList(), listOf("Rate", "Shape", "Depth")),
    ENV("Env follower", listOf("In"), listOf("Attack", "Release")),
    MATH("Math", listOf("A", "B"), listOf("Op", "Scale")),
    KNOB("Knob", emptyList(), listOf("Knob #", "Min", "Max")),
    NOISE("Noise", emptyList(), listOf("Level"));

    fun defaults(): List<Float> = when (this) {
        FILTER -> listOf(0f, 0.6f, 0.3f)
        DELAY -> listOf(0.55f, 0.4f)
        GAIN -> listOf(0.5f)
        MIX -> listOf(0.5f)
        SHAPER -> listOf(0.3f, 0f)
        LFO -> listOf(0.6f, 0f, 0.5f)
        ENV -> listOf(0.1f, 0.4f)
        MATH -> listOf(0f, 0.5f)
        KNOB -> listOf(0f, 0f, 1f)
        NOISE -> listOf(0.2f)
        IN, OUT -> emptyList()
    }

    fun paramLabel(i: Int, v: Float): String = when (this) {
        FILTER -> when (i) { 0 -> listOf("Low-pass", "Band-pass", "High-pass")[(v * 3).toInt().coerceAtMost(2)]; 1 -> hz(v); else -> pct(v) }
        DELAY -> if (i == 0) syncDivision(v).second else pct(v)
        GAIN -> String.format(Locale.ROOT, "%+.1f dB", (v * 2 - 1) * 24)
        SHAPER -> if (i == 1) listOf("Soft", "Hard", "Fold")[(v * 3).toInt().coerceAtMost(2)] else String.format(Locale.ROOT, "%.0f dB", v * 36)
        LFO -> when (i) { 0 -> syncDivision(v).second; 1 -> LfoShape.entries[(v * 4).toInt().coerceAtMost(3)].label; else -> pct(v) }
        ENV -> "${(1 + v * v * 499).toInt()} ms"
        MATH -> if (i == 0) listOf("Add", "Multiply", "Max", "Min")[(v * 4).toInt().coerceAtMost(3)] else String.format(Locale.ROOT, "×%.2f", v * 2)
        KNOB -> if (i == 0) "Knob ${knobIndex(v) + 1}" else pct(v)
        else -> pct(v)
    }

    companion object {
        fun knobIndex(v: Float) = (v * EffectType.PATCH_KNOBS).toInt().coerceIn(0, EffectType.PATCH_KNOBS - 1)
        private fun pct(v: Float) = "${(v * 100).toInt()}%"
        private fun hz(v: Float) = (20f * Math.pow(1000.0, v.toDouble()).toFloat()).let { if (it < 1000) "${it.toInt()} Hz" else String.format(Locale.ROOT, "%.1f kHz", it / 1000) }
    }
}

@Serializable
data class PatchNode(val id: Int, val kind: NodeKind, val x: Float, val y: Float, val params: List<Float> = kind.defaults())

/** A wire from [from]'s output to input [port] of [to]. */
@Serializable
data class PatchWire(val from: Int, val to: Int, val port: Int)

@Serializable
data class PatchGraph(
    val id: String = newId(),
    val name: String = "My Patch",
    val nodes: List<PatchNode> = listOf(PatchNode(0, NodeKind.IN, 40f, 120f), PatchNode(1, NodeKind.OUT, 520f, 120f)),
    val wires: List<PatchWire> = listOf(PatchWire(0, 1, 0)),
    val knobLabels: List<String> = List(EffectType.PATCH_KNOBS) { "Knob ${it + 1}" },
    val knobValues: List<Float> = List(EffectType.PATCH_KNOBS) { 0.5f },
) {
    fun node(id: Int) = nodes.firstOrNull { it.id == id }
    fun nextId() = (nodes.maxOfOrNull { it.id } ?: -1) + 1

    /** Labels of the knobs actually used by Knob nodes (unused knobs keep their default name). */
    fun knobNames(): List<String> = knobLabels

    fun toSlot(): EffectSlot = EffectSlot(type = EffectType.PATCH, values = knobValues.take(EffectType.PATCH_KNOBS), patch = this)

    fun addNode(kind: NodeKind, x: Float, y: Float): PatchGraph = copy(nodes = nodes + PatchNode(nextId(), kind, x, y))

    fun removeNode(id: Int): PatchGraph {
        val n = node(id) ?: return this
        if (n.kind == NodeKind.IN || n.kind == NodeKind.OUT) return this
        return copy(nodes = nodes.filter { it.id != id }, wires = wires.filter { it.from != id && it.to != id })
    }

    /** Connects (or, if the wire exists, disconnects). Refuses wires that would make a loop not passing a Delay. */
    fun toggleWire(w: PatchWire): PatchGraph {
        if (w in wires) return copy(wires = wires - w)
        val from = node(w.from) ?: return this
        val to = node(w.to) ?: return this
        if (!from.kind.hasOutput || w.port !in to.kind.inputs.indices || w.from == w.to) return this
        val next = copy(wires = wires + w)
        return if (next.evaluationOrder() == null) this else next
    }

    /**
     * Topological order of the nodes, treating wires into a Delay as breaking the loop (the delay
     * output only depends on past input). Null when there is a loop without a Delay.
     */
    fun evaluationOrder(): List<PatchNode>? {
        val deps = nodes.associate { n -> n.id to wires.filter { it.to == n.id && n.kind != NodeKind.DELAY }.map { it.from }.toSet() }
        val done = LinkedHashSet<Int>()
        val visiting = HashSet<Int>()
        fun visit(id: Int): Boolean {
            if (id in done) return true
            if (!visiting.add(id)) return false
            for (d in deps[id].orEmpty()) if (!visit(d)) return false
            visiting.remove(id); done.add(id)
            return true
        }
        for (n in nodes) if (!visit(n.id)) return null
        return done.mapNotNull { node(it) }
    }
}

object FactoryPatches {
    val AUTO_WAH = PatchGraph(
        id = "factory-auto-wah", name = "Auto-Wah",
        nodes = listOf(
            PatchNode(0, NodeKind.IN, 30f, 140f), PatchNode(1, NodeKind.ENV, 220f, 40f, listOf(0.05f, 0.35f)),
            PatchNode(2, NodeKind.FILTER, 400f, 140f, listOf(0.4f, 0.25f, 0.7f)), PatchNode(3, NodeKind.OUT, 600f, 140f),
            PatchNode(4, NodeKind.KNOB, 220f, 240f, listOf(0f, 0f, 1f)), PatchNode(5, NodeKind.MATH, 300f, 40f, listOf(0.3f, 0.5f)),
        ),
        wires = listOf(PatchWire(0, 2, 0), PatchWire(0, 1, 0), PatchWire(1, 5, 0), PatchWire(4, 5, 1), PatchWire(5, 2, 1), PatchWire(2, 3, 0)),
        knobLabels = listOf("Sensitivity") + List(EffectType.PATCH_KNOBS - 1) { "Knob ${it + 2}" },
        knobValues = listOf(0.7f) + List(EffectType.PATCH_KNOBS - 1) { 0.5f },
    )
    val DIRTY_ECHO = PatchGraph(
        id = "factory-dirty-echo", name = "Dirty Echo",
        nodes = listOf(
            PatchNode(0, NodeKind.IN, 30f, 140f), PatchNode(1, NodeKind.DELAY, 220f, 220f, listOf(0.6f, 0.3f)),
            PatchNode(2, NodeKind.SHAPER, 380f, 220f, listOf(0.35f, 0f)), PatchNode(3, NodeKind.FILTER, 520f, 220f, listOf(0f, 0.55f, 0.2f)),
            PatchNode(4, NodeKind.MIX, 600f, 120f, listOf(0.35f)), PatchNode(5, NodeKind.OUT, 760f, 120f),
        ),
        // The filtered echo feeds back into the delay: a loop through the Delay node.
        wires = listOf(PatchWire(0, 1, 0), PatchWire(1, 2, 0), PatchWire(2, 3, 0), PatchWire(3, 1, 0), PatchWire(0, 4, 0), PatchWire(3, 4, 1), PatchWire(4, 5, 0)),
    )
    val FOLD_LFO = PatchGraph(
        id = "factory-fold-lfo", name = "Wavefold Sweep",
        nodes = listOf(
            PatchNode(0, NodeKind.IN, 30f, 140f), PatchNode(1, NodeKind.GAIN, 200f, 140f, listOf(0.7f)),
            PatchNode(2, NodeKind.SHAPER, 360f, 140f, listOf(0.4f, 0.8f)), PatchNode(3, NodeKind.LFO, 200f, 30f, listOf(0.67f, 0.25f, 0.6f)),
            PatchNode(4, NodeKind.FILTER, 520f, 140f, listOf(0f, 0.7f, 0.3f)), PatchNode(5, NodeKind.OUT, 680f, 140f),
        ),
        wires = listOf(PatchWire(0, 1, 0), PatchWire(3, 1, 1), PatchWire(1, 2, 0), PatchWire(2, 4, 0), PatchWire(4, 5, 0)),
    )
    val ALL = listOf(AUTO_WAH, DIRTY_ECHO, FOLD_LFO)
}

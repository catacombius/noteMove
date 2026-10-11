package com.spectraseq.core.model

import kotlinx.serialization.Serializable

/** One parameter of an inner device that a macro or modulator drives, between [min] and [max] (normalised). */
@Serializable
data class MacroTarget(val slot: Int, val param: Int, val min: Float = 0f, val max: Float = 1f)

@Serializable
data class RackMacro(val name: String, val targets: List<MacroTarget> = emptyList())

@Serializable
enum class ModKind(val label: String) { LFO("LFO"), ENVELOPE("Envelope follower"), RANDOM("Random (S&H)") }

@Serializable
enum class LfoShape(val label: String) { SINE("Sine"), TRIANGLE("Triangle"), SQUARE("Square"), SAW("Saw") }

/**
 * A modulator moves its targets around their current value: ±[depth] of each target's range.
 * [rate] is a tempo division (see [SYNC_DIVISIONS]) when [sync], otherwise 0.05–20 Hz.
 * For the envelope follower, [rate] sets the release.
 */
@Serializable
data class Modulator(
    val kind: ModKind = ModKind.LFO,
    val rate: Float = 0.6f,
    val sync: Boolean = true,
    val shape: LfoShape = LfoShape.SINE,
    val depth: Float = 0.5f,
    val targets: List<MacroTarget> = emptyList(),
)

/** A user-built effect: a chain of devices, 8 macro knobs mapped onto their parameters, and up to 2 modulators. */
@Serializable
data class RackPreset(
    val id: String = newId(),
    val name: String = "My Rack",
    val color: Int = 0,
    val chain: List<EffectSlot> = emptyList(),
    val macros: List<RackMacro> = List(EffectType.RACK_MACROS) { RackMacro("Macro ${it + 1}") },
    val mods: List<Modulator> = emptyList(),
    /** 0 = dry only, 1 = fully processed. */
    val dryWet: Float = 1f,
    /** Macro positions saved with the preset. */
    val macroValues: List<Float> = List(EffectType.RACK_MACROS) { 0f },
) {
    fun toSlot(): EffectSlot = EffectSlot(type = EffectType.RACK, values = macroValues.take(EffectType.RACK_MACROS), rack = this)

    /** Effective normalised value of every inner parameter for the macro positions [macros] (modulators not included). */
    fun innerValues(macros: List<Float>): List<List<Float>> = chain.mapIndexed { si, slot ->
        val base = slot.values.toMutableList()
        this.macros.forEachIndexed { mi, m ->
            val mv = macros.getOrElse(mi) { 0f }
            for (t in m.targets) if (t.slot == si && t.param in base.indices) base[t.param] = (t.min + (t.max - t.min) * mv).coerceIn(0f, 1f)
        }
        base
    }
}

/** The user's library of racks and patches, stored once for all sets. */
@Serializable
data class UserDevices(val racks: List<RackPreset> = emptyList(), val patches: List<PatchGraph> = emptyList())

/** Starting points for the rack builder. */
object FactoryRacks {
    private fun slot(t: EffectType, vararg v: Pair<Int, Float>) = EffectSlot(type = t, values = t.defaults().toMutableList().also { l -> for ((i, x) in v) l[i] = x })
    private fun macro(name: String, vararg t: MacroTarget) = RackMacro(name, t.toList())
    private fun macros(vararg m: RackMacro) = m.toList() + List(EffectType.RACK_MACROS - m.size) { RackMacro("Macro ${m.size + it + 1}") }
    private fun values(vararg v: Float) = v.toList() + List(EffectType.RACK_MACROS - v.size) { 0f }

    val DUB_THROW = rack(
        id = "factory-dub-throw", name = "Dub Throw", color = 6,
        chain = listOf(slot(EffectType.DELAY, 0 to 0.73f, 1 to 0.55f, 4 to 0.3f), slot(EffectType.FILTER, 0 to 1f, 1 to 0.25f, 2 to 0.4f), slot(EffectType.REVERB, 2 to 0.25f)),
        macros = macros(
            macro("Throw", MacroTarget(0, 4, 0.05f, 0.85f), MacroTarget(0, 1, 0.3f, 0.85f)),
            macro("Tone", MacroTarget(1, 1, 0.05f, 0.7f)),
            macro("Space", MacroTarget(2, 2, 0f, 0.6f)),
        ),
        values = values(0.4f, 0.3f, 0.3f),
    )
    val LOFI_RADIO = rack(
        id = "factory-lofi-radio", name = "Lo-Fi Radio", color = 2,
        chain = listOf(slot(EffectType.REDUX, 0 to 0.4f, 1 to 0.3f), slot(EffectType.FILTER, 0 to 0.5f, 1 to 0.62f, 2 to 0.45f), slot(EffectType.TAPE, 0 to 0.4f, 1 to 0.3f, 3 to 0.25f)),
        macros = macros(
            macro("Crush", MacroTarget(0, 0, 0f, 0.85f), MacroTarget(0, 1, 0f, 0.6f)),
            macro("Radio", MacroTarget(1, 5, 0f, 1f)),
            macro("Wobble", MacroTarget(2, 0, 0f, 1f), MacroTarget(2, 1, 0f, 0.8f)),
        ),
        values = values(0.4f, 0.7f, 0.4f),
    )
    val PUMP = rack(
        id = "factory-pump", name = "Sidechain Pump", color = 9,
        chain = listOf(slot(EffectType.DUCKER, 1 to 0.75f), slot(EffectType.COMPRESSOR, 0 to 0.5f, 1 to 0.4f)),
        macros = macros(macro("Pump", MacroTarget(0, 1, 0f, 1f)), macro("Rate", MacroTarget(0, 0, 0.45f, 1f)), macro("Squash", MacroTarget(1, 0, 1f, 0.2f))),
        values = values(0.75f, 0.4f, 0.3f),
    )
    val SHIMMER = rack(
        id = "factory-shimmer", name = "Shimmer Space", color = 7,
        chain = listOf(slot(EffectType.PITCH, 0 to 1f, 3 to 0.35f), slot(EffectType.REVERB, 0 to 0.9f, 1 to 0.3f, 2 to 0.5f), slot(EffectType.CHORUS, 3 to 0.3f)),
        macros = macros(macro("Shimmer", MacroTarget(0, 3, 0f, 0.7f)), macro("Size", MacroTarget(1, 0, 0.4f, 1f)), macro("Mix", MacroTarget(1, 2, 0f, 0.8f))),
        values = values(0.4f, 0.8f, 0.5f),
    )
    val GLITCH = rack(
        id = "factory-glitch", name = "Glitch Box", color = 12,
        chain = listOf(slot(EffectType.STUTTER, 0 to 0.36f, 1 to 0.3f), slot(EffectType.REDUX, 0 to 0.3f, 2 to 0.6f), slot(EffectType.FILTER, 0 to 0f, 1 to 0.6f, 2 to 0.5f)),
        macros = macros(macro("Chance", MacroTarget(0, 1, 0f, 1f)), macro("Crush", MacroTarget(1, 0, 0f, 0.9f)), macro("Filter", MacroTarget(2, 1, 0.15f, 1f))),
        mods = listOf(Modulator(ModKind.RANDOM, rate = 0.45f, depth = 0.4f, targets = listOf(MacroTarget(2, 1)))),
        values = values(0.3f, 0.3f, 0.7f),
    )
    val WIDE = rack(
        id = "factory-wide", name = "Wide Chorus", color = 8,
        chain = listOf(slot(EffectType.CHORUS, 1 to 0.6f, 2 to 1f, 3 to 0.45f), slot(EffectType.UTILITY, 0 to 0.75f, 1 to 0.5f), slot(EffectType.EQ, 3 to 0.6f)),
        macros = macros(macro("Width", MacroTarget(1, 0, 0.5f, 1f)), macro("Chorus", MacroTarget(0, 3, 0f, 0.8f)), macro("Air", MacroTarget(2, 3, 0.5f, 0.8f))),
        values = values(0.5f, 0.5f, 0.3f),
    )
    val GATED_VERB = rack(
        id = "factory-gated-verb", name = "Gated Verb", color = 13,
        chain = listOf(slot(EffectType.REVERB, 0 to 0.85f, 2 to 0.6f), slot(EffectType.GATE, 0 to 0f, 2 to 0.35f, 3 to 0.45f)),
        macros = macros(macro("Verb", MacroTarget(0, 2, 0f, 0.9f)), macro("Pattern", MacroTarget(1, 2, 0.1f, 1f))),
        values = values(0.6f, 0.3f),
    )
    val WARM = rack(
        id = "factory-warm", name = "Warm Drive", color = 1,
        chain = listOf(slot(EffectType.SATURATOR, 0 to 0.3f), slot(EffectType.TAPE, 0 to 0.05f, 1 to 0.05f, 2 to 0.4f), slot(EffectType.EQ, 0 to 0.58f, 3 to 0.45f)),
        macros = macros(macro("Drive", MacroTarget(0, 0, 0f, 0.8f), MacroTarget(1, 2, 0.1f, 0.8f)), macro("Warmth", MacroTarget(2, 0, 0.5f, 0.7f), MacroTarget(2, 3, 0.5f, 0.35f))),
        values = values(0.4f, 0.5f),
    )
    val WOBBLE = rack(
        id = "factory-wobble", name = "Wobble Filter", color = 4,
        chain = listOf(slot(EffectType.FILTER, 1 to 0.5f, 2 to 0.6f), slot(EffectType.DISTORTION, 0 to 0.2f, 4 to 0.5f)),
        macros = macros(macro("Cutoff", MacroTarget(0, 1, 0.1f, 0.9f)), macro("Reso", MacroTarget(0, 2, 0f, 0.9f)), macro("Grit", MacroTarget(1, 4, 0f, 0.8f))),
        mods = listOf(Modulator(ModKind.LFO, rate = 0.45f, sync = true, shape = LfoShape.TRIANGLE, depth = 0.6f, targets = listOf(MacroTarget(0, 1)))),
        values = values(0.5f, 0.5f, 0.2f),
    )
    val TREM_SPACE = rack(
        id = "factory-trem-space", name = "Tremolo Space", color = 11,
        chain = listOf(slot(EffectType.TREMOLO, 1 to 0.5f, 2 to 0.8f), slot(EffectType.DELAY, 0 to 0.55f, 2 to 1f, 4 to 0.3f)),
        macros = macros(macro("Trem", MacroTarget(0, 1, 0f, 1f)), macro("Echo", MacroTarget(1, 4, 0f, 0.7f))),
        values = values(0.5f, 0.3f),
    )

    val ALL = listOf(DUB_THROW, LOFI_RADIO, PUMP, SHIMMER, GLITCH, WIDE, GATED_VERB, WARM, WOBBLE, TREM_SPACE)

    private fun rack(
        id: String, name: String, color: Int, chain: List<EffectSlot>, macros: List<RackMacro>,
        values: List<Float>, mods: List<Modulator> = emptyList(),
    ) = RackPreset(id, name, color, chain, macros, mods, 1f, values)
}

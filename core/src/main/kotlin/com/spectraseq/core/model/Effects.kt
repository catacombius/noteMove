package com.spectraseq.core.model

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.abs
import kotlin.math.pow

/** One knob of an effect. Values are stored normalised (0..1); [format] renders them for display. */
class ParamSpec(val name: String, val default: Float, val format: (Float) -> String = { "${(it * 100).toInt()}%" })

private fun pct(v: Float) = "${(v * 100).toInt()}%"
private fun db(v: Float, range: Float) = String.format(Locale.ROOT, "%+.1f dB", (v * 2 - 1) * range)
private fun hz(v: Float) = (20f * 1000f.pow(v)).let { if (it < 1000) "${it.toInt()} Hz" else String.format(Locale.ROOT, "%.1f kHz", it / 1000) }

/** Tempo-synced divisions shared by delay, tremolo and LFOs (in beats). */
val SYNC_DIVISIONS = listOf(
    1.0 / 8 to "1/32", 1.0 / 6 to "1/16T", 0.25 to "1/16", 1.0 / 3 to "1/8T", 0.375 to "1/16.",
    0.5 to "1/8", 2.0 / 3 to "1/4T", 0.75 to "1/8.", 1.0 to "1/4", 1.5 to "1/4.", 2.0 to "1/2", 4.0 to "1 bar",
)

fun syncDivision(v: Float): Pair<Double, String> = SYNC_DIVISIONS[(v * (SYNC_DIVISIONS.size - 1) + 0.5f).toInt().coerceIn(0, SYNC_DIVISIONS.lastIndex)]

/** Browser groups for the effects. */
enum class EffectCategory(val label: String) {
    DYNAMICS("Dynamics"), FILTER_EQ("Filter & EQ"), DRIVE("Drive"), MODULATION("Modulation"),
    TIME_SPACE("Time & Space"), GLITCH("Glitch"), UTILITY("Utility"), CUSTOM("Custom"),
}

private fun hzRange(v: Float, lo: Float, hi: Float) = lo * (hi / lo).pow(v)
private fun hzLabel(f: Float) = if (f < 1000) "${f.toInt()} Hz" else String.format(Locale.ROOT, "%.1f kHz", f / 1000)

@Serializable
enum class EffectType(val label: String, val category: EffectCategory) {
    // Enum names are stored in saved sets; only labels and categories may change.
    FILTER("Filter", EffectCategory.FILTER_EQ),
    SATURATOR("Saturator", EffectCategory.DRIVE),
    REDUX("Bitcrusher", EffectCategory.GLITCH),
    CHORUS("Chorus", EffectCategory.MODULATION),
    PHASER("Phaser", EffectCategory.MODULATION),
    COMPRESSOR("Compressor", EffectCategory.DYNAMICS),
    EQ("3-Band EQ", EffectCategory.FILTER_EQ),
    TREMOLO("Tremolo / Pan", EffectCategory.MODULATION),
    DELAY("Delay", EffectCategory.TIME_SPACE),
    REVERB("Reverb", EffectCategory.TIME_SPACE),
    FLANGER("Flanger", EffectCategory.MODULATION),
    DISTORTION("Distortion", EffectCategory.DRIVE),
    RING_MOD("Ring Mod / Shifter", EffectCategory.MODULATION),
    UTILITY("Width / Utility", EffectCategory.UTILITY),
    GATE("Gate", EffectCategory.DYNAMICS),
    TRANSIENT("Transient Shaper", EffectCategory.DYNAMICS),
    DUCKER("Ducker (Pump)", EffectCategory.DYNAMICS),
    TAPE("Tape / Lo-Fi", EffectCategory.DRIVE),
    STUTTER("Stutter", EffectCategory.GLITCH),
    PITCH("Pitch Shifter", EffectCategory.GLITCH),
    LIMITER("Limiter", EffectCategory.DYNAMICS),
    /** A rack built in the effect creator: an inner chain with macro knobs and modulators. */
    RACK("Rack", EffectCategory.CUSTOM),
    /** A modular patch built in the patcher. */
    PATCH("Patch", EffectCategory.CUSTOM);

    val params: List<ParamSpec>
        get() = when (this) {
            FILTER -> listOf(
                ParamSpec("Type", 0f) { when { it < 0.33f -> "Low-pass"; it < 0.66f -> "Band-pass"; else -> "High-pass" } },
                ParamSpec("Cutoff", 0.7f, ::hz),
                ParamSpec("Reso", 0.3f, ::pct),
                ParamSpec("LFO Rate", 0.6f) { syncDivision(it).second },
                ParamSpec("LFO Amt", 0f, ::pct),
                ParamSpec("Mix", 1f, ::pct),
            )
            SATURATOR -> listOf(
                ParamSpec("Drive", 0.4f) { String.format(Locale.ROOT, "%.1f dB", it * 36) },
                ParamSpec("Tone", 0.6f, ::pct),
                ParamSpec("Output", 0.5f) { db(it, 12f) },
                ParamSpec("Mix", 1f, ::pct),
            )
            REDUX -> listOf(
                ParamSpec("Bits", 0.5f) { "${bitsFor(it)} bit" },
                ParamSpec("Downsample", 0.3f) { "÷${downsampleFor(it)}" },
                ParamSpec("Mix", 1f, ::pct),
            )
            CHORUS -> listOf(
                ParamSpec("Rate", 0.3f) { String.format(Locale.ROOT, "%.2f Hz", 0.05f + it * it * 6f) },
                ParamSpec("Depth", 0.5f, ::pct),
                ParamSpec("Width", 0.8f, ::pct),
                ParamSpec("Mix", 0.5f, ::pct),
            )
            PHASER -> listOf(
                ParamSpec("Rate", 0.3f) { String.format(Locale.ROOT, "%.2f Hz", 0.05f + it * it * 6f) },
                ParamSpec("Depth", 0.7f, ::pct),
                ParamSpec("Feedback", 0.5f, ::pct),
                ParamSpec("Mix", 0.5f, ::pct),
            )
            COMPRESSOR -> listOf(
                ParamSpec("Threshold", 0.6f) { String.format(Locale.ROOT, "%.1f dB", -40f + it * 40f) },
                ParamSpec("Ratio", 0.3f) { String.format(Locale.ROOT, "%.1f:1", 1f + it * it * 19f) },
                ParamSpec("Attack", 0.2f) { String.format(Locale.ROOT, "%.1f ms", 0.1f + it * it * 100f) },
                ParamSpec("Release", 0.3f) { "${(10 + it * it * 990).toInt()} ms" },
                ParamSpec("Makeup", 0.5f) { db(it, 12f) },
                ParamSpec("Mix", 1f, ::pct),
            )
            EQ -> listOf(
                ParamSpec("Low", 0.5f) { db(it, 15f) },
                ParamSpec("Mid", 0.5f) { db(it, 15f) },
                ParamSpec("Mid Freq", 0.55f, ::hz),
                ParamSpec("High", 0.5f) { db(it, 15f) },
            )
            TREMOLO -> listOf(
                ParamSpec("Rate", 0.5f) { syncDivision(it).second },
                ParamSpec("Depth", 0.6f, ::pct),
                ParamSpec("Pan↔Trem", 0f) { if (it < 0.5f) "Pan ${pct(1 - it * 2)}" else "Trem ${pct(it * 2 - 1)}" },
                ParamSpec("Shape", 0f) { if (it < 0.5f) "Sine" else "Square" },
            )
            DELAY -> listOf(
                ParamSpec("Time", 0.5f) { syncDivision(it).second },
                ParamSpec("Feedback", 0.4f, ::pct),
                ParamSpec("Ping-pong", 1f) { if (it >= 0.5f) "On" else "Off" },
                ParamSpec("Tone", 0.6f, ::pct),
                ParamSpec("Mix", 0.3f, ::pct),
            )
            REVERB -> listOf(
                ParamSpec("Size", 0.6f, ::pct),
                ParamSpec("Damping", 0.4f, ::pct),
                ParamSpec("Mix", 0.3f, ::pct),
            )
            FLANGER -> listOf(
                ParamSpec("Rate", 0.25f) { String.format(Locale.ROOT, "%.2f Hz", 0.02f + it * it * 5f) },
                ParamSpec("Depth", 0.7f, ::pct),
                ParamSpec("Feedback", 0.7f) { "${((it * 2 - 1) * 95).toInt()}%" },
                ParamSpec("Delay", 0.3f) { String.format(Locale.ROOT, "%.1f ms", 0.3f + it * 7.7f) },
                ParamSpec("Mix", 0.5f, ::pct),
            )
            DISTORTION -> listOf(
                ParamSpec("Drive", 0.4f) { String.format(Locale.ROOT, "%.0f dB", it * 48) },
                ParamSpec("Mode", 0f) { DIST_MODES[(it * DIST_MODES.size).toInt().coerceAtMost(DIST_MODES.lastIndex)] },
                ParamSpec("Tone", 0.6f, ::pct),
                ParamSpec("Output", 0.5f) { db(it, 12f) },
                ParamSpec("Mix", 1f, ::pct),
            )
            RING_MOD -> listOf(
                ParamSpec("Freq", 0.4f) { hzLabel(hzRange(it, 5f, 5000f)) },
                ParamSpec("Mode", 0f) { if (it < 0.5f) "Ring" else "Shift" },
                ParamSpec("Mix", 0.5f, ::pct),
            )
            UTILITY -> listOf(
                ParamSpec("Width", 0.5f) { "${(it * 200).toInt()}%" },
                ParamSpec("Mono Bass", 0f) { if (it < 0.02f) "Off" else hzLabel(hzRange(it, 40f, 400f)) },
                ParamSpec("Gain", 0.5f) { db(it, 24f) },
                ParamSpec("Pan", 0.5f) { val p = it * 2 - 1; if (abs(p) < 0.02f) "C" else if (p < 0) "${(-p * 50).toInt()}L" else "${(p * 50).toInt()}R" },
                ParamSpec("Phase", 0f) { if (it < 0.5f) "Normal" else "Flip L" },
            )
            GATE -> listOf(
                ParamSpec("Threshold", 0.5f) { String.format(Locale.ROOT, "%.0f dB", -80f + it * 80f) },
                ParamSpec("Release", 0.3f) { "${(5 + it * it * 495).toInt()} ms" },
                ParamSpec("Pattern", 0f) { if (it < 0.06f) "Off" else "Pattern ${((it - 0.06f) / 0.94f * (GATE_PATTERNS.size - 1)).toInt() + 1}" },
                ParamSpec("Rate", 0.45f) { syncDivision(it).second },
                ParamSpec("Floor", 0f) { if (it <= 0.001f) "-∞ dB" else String.format(Locale.ROOT, "%.0f dB", -60f + it * 60f) },
            )
            TRANSIENT -> listOf(
                ParamSpec("Attack", 0.5f) { "${((it * 2 - 1) * 100).toInt()}%" },
                ParamSpec("Sustain", 0.5f) { "${((it * 2 - 1) * 100).toInt()}%" },
                ParamSpec("Output", 0.5f) { db(it, 12f) },
            )
            DUCKER -> listOf(
                ParamSpec("Rate", 0.67f) { syncDivision(it).second },
                ParamSpec("Depth", 0.7f, ::pct),
                ParamSpec("Release", 0.6f, ::pct),
                ParamSpec("Curve", 0.5f) { if (it < 0.33f) "Linear" else if (it < 0.66f) "Smooth" else "Punchy" },
            )
            TAPE -> listOf(
                ParamSpec("Wow", 0.2f, ::pct),
                ParamSpec("Flutter", 0.2f, ::pct),
                ParamSpec("Drive", 0.3f, ::pct),
                ParamSpec("Noise", 0.1f, ::pct),
                ParamSpec("Tone", 0.6f) { hzLabel(hzRange(it, 1500f, 20000f)) },
                ParamSpec("Low Cut", 0.1f) { hzLabel(hzRange(it, 20f, 400f)) },
            )
            STUTTER -> listOf(
                ParamSpec("Grid", 0.45f) { syncDivision(it).second },
                ParamSpec("Chance", 0.3f, ::pct),
                ParamSpec("Repeats", 0.3f) { "${1 + (it * 7).toInt()}" },
                ParamSpec("Pitch", 0.5f) { "${((it * 2 - 1) * 12).toInt()} st" },
                ParamSpec("Mix", 1f, ::pct),
            )
            PITCH -> listOf(
                ParamSpec("Shift", 0.5f) { "${((it * 2 - 1) * 12).roundToIntCompat()} st" },
                ParamSpec("Fine", 0.5f) { "${((it * 2 - 1) * 50).toInt()} ct" },
                ParamSpec("Window", 0.4f) { "${(20 + it * 80).toInt()} ms" },
                ParamSpec("Mix", 1f, ::pct),
            )
            LIMITER -> listOf(
                ParamSpec("Gain", 0f) { String.format(Locale.ROOT, "+%.1f dB", it * 18) },
                ParamSpec("Ceiling", 0.92f) { String.format(Locale.ROOT, "%.1f dB", -12f + it * 12f) },
                ParamSpec("Release", 0.3f) { "${(10 + it * it * 990).toInt()} ms" },
            )
            RACK -> (0 until RACK_MACROS).map { ParamSpec("Macro ${it + 1}", 0f, ::pct) }
            PATCH -> (0 until PATCH_KNOBS).map { ParamSpec("Knob ${it + 1}", 0.5f, ::pct) }
        }

    fun defaults(): List<Float> = params.map { it.default }

    companion object {
        const val RACK_MACROS = 8
        const val PATCH_KNOBS = 8
        val DIST_MODES = listOf("Soft", "Hard", "Fold", "Tube")
        /** Trance-gate patterns over 16 steps (1 = open). */
        val GATE_PATTERNS = listOf(
            "1111111111111111", "1010101010101010", "1110111011101110", "1101101101101101",
            "1001001001001000", "1111000011110000", "1011010110110101", "1100110011001100",
        )
        /** Effects offered in the browser (racks and patches come from the creator). */
        val BUILT_IN get() = entries.filter { it != RACK && it != PATCH }
        fun bitsFor(v: Float) = (16 - v * 14).toInt().coerceIn(2, 16)
        fun downsampleFor(v: Float) = (1 + v * v * 31).toInt().coerceIn(1, 32)
    }
}

@Serializable
data class EffectSlot(
    val id: String = newId(),
    val type: EffectType,
    val values: List<Float> = type.defaults(),
    val enabled: Boolean = true,
) {
    fun value(i: Int): Float = values.getOrElse(i) { type.params.getOrNull(i)?.default ?: 0f }
    fun with(i: Int, v: Float) = copy(values = type.params.indices.map { if (it == i) v.coerceIn(0f, 1f) else value(it) })
}

const val MAX_EFFECTS = 8

private fun Float.roundToIntCompat(): Int = kotlin.math.round(this).toInt()

@Serializable
enum class ArpMode(val label: String) { UP("Up"), DOWN("Down"), UP_DOWN("Up/Down"), AS_PLAYED("Played"), RANDOM("Random"), CHORD("Chord") }

/** Arpeggiator for live-played notes (pads, MIDI controllers). Its output is what gets recorded. */
@Serializable
data class ArpSettings(
    val enabled: Boolean = false,
    val mode: ArpMode = ArpMode.UP,
    /** Step length in beats. */
    val rate: Double = 0.25,
    val octaves: Int = 1,
    /** Note length as a fraction of a step. */
    val gate: Float = 0.6f,
    /** Keep playing after the pads are released, until new notes are pressed. */
    val latch: Boolean = false,
)

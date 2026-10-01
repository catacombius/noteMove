package com.notemove.core.model

import kotlinx.serialization.Serializable
import java.util.Locale
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

@Serializable
enum class EffectType(val label: String) {
    FILTER("Auto Filter"),
    SATURATOR("Saturator"),
    REDUX("Redux"),
    CHORUS("Chorus"),
    PHASER("Phaser"),
    COMPRESSOR("Compressor"),
    EQ("EQ Three"),
    TREMOLO("Auto Pan"),
    DELAY("Echo"),
    REVERB("Reverb");

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
        }

    fun defaults(): List<Float> = params.map { it.default }

    companion object {
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

const val MAX_EFFECTS = 6

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

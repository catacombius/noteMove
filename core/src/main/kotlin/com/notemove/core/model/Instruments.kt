package com.notemove.core.model

import kotlinx.serialization.Serializable

/** Drum pads sit on the same notes as an Ableton Drum Rack: pad 0 = C1 (MIDI 36). */
const val DRUM_BASE_NOTE = 36
const val DRUM_PAD_COUNT = 16

@Serializable
enum class DrumSound(val label: String) {
    KICK("Kick"), SNARE("Snare"), CLAP("Clap"), RIM("Rim"),
    CLOSED_HAT("Closed Hat"), OPEN_HAT("Open Hat"), PEDAL_HAT("Pedal Hat"),
    TOM_LOW("Low Tom"), TOM_MID("Mid Tom"), TOM_HIGH("High Tom"),
    CRASH("Crash"), RIDE("Ride"), COWBELL("Cowbell"), SHAKER("Shaker"),
    CONGA("Conga"), CLAVE("Clave"), ZAP("Zap"), SUB_KICK("Sub Kick"),
    SAMPLE("Sample"),
}

@Serializable
data class DrumPad(
    val name: String,
    val sound: DrumSound,
    /** Semitones. */
    val tune: Float = 0f,
    /** Multiplier on the sound's natural decay, 0.1..3. */
    val decay: Float = 1f,
    /** 0..1 brightness / noise balance. */
    val tone: Float = 0.5f,
    val level: Float = 0.8f,
    val pan: Float = 0f,
    val sampleId: String? = null,
    /** Pads sharing a non-zero choke group cut each other off (open / closed hats). */
    val chokeGroup: Int = 0,
)

@Serializable
data class DrumKit(val name: String, val pads: List<DrumPad>) {
    fun withPad(index: Int, pad: DrumPad) = copy(pads = pads.mapIndexed { i, p -> if (i == index) pad else p })
}

object DrumKits {
    private fun kit(name: String, t: Float, d: Float, tone: Float, kickDecay: Float = 1f) = DrumKit(
        name,
        listOf(
            DrumPad("Kick", DrumSound.KICK, tune = t, decay = d * kickDecay, tone = tone),
            DrumPad("Rim", DrumSound.RIM, tune = t, decay = d, tone = tone),
            DrumPad("Snare", DrumSound.SNARE, tune = t, decay = d, tone = tone),
            DrumPad("Clap", DrumSound.CLAP, tune = t, decay = d, tone = tone),
            DrumPad("Closed Hat", DrumSound.CLOSED_HAT, tune = t, decay = d, tone = tone, level = 0.6f, chokeGroup = 1),
            DrumPad("Pedal Hat", DrumSound.PEDAL_HAT, tune = t, decay = d, tone = tone, level = 0.55f, chokeGroup = 1),
            DrumPad("Open Hat", DrumSound.OPEN_HAT, tune = t, decay = d, tone = tone, level = 0.55f, chokeGroup = 1),
            DrumPad("Shaker", DrumSound.SHAKER, tune = t, decay = d, tone = tone, level = 0.5f),
            DrumPad("Low Tom", DrumSound.TOM_LOW, tune = t, decay = d, tone = tone, pan = -0.3f),
            DrumPad("Mid Tom", DrumSound.TOM_MID, tune = t, decay = d, tone = tone),
            DrumPad("High Tom", DrumSound.TOM_HIGH, tune = t, decay = d, tone = tone, pan = 0.3f),
            DrumPad("Cowbell", DrumSound.COWBELL, tune = t, decay = d, tone = tone, level = 0.5f),
            DrumPad("Conga", DrumSound.CONGA, tune = t, decay = d, tone = tone, pan = 0.2f),
            DrumPad("Clave", DrumSound.CLAVE, tune = t, decay = d, tone = tone, level = 0.6f),
            DrumPad("Crash", DrumSound.CRASH, tune = t, decay = d, tone = tone, level = 0.45f),
            DrumPad("Ride", DrumSound.RIDE, tune = t, decay = d, tone = tone, level = 0.45f),
        ),
    )

    val KIT_808 = kit("808 Kit", 0f, 1f, 0.45f, kickDecay = 1.6f)
    val KIT_909 = kit("909 Kit", 2f, 0.8f, 0.7f)
    val KIT_LOFI = kit("Lo-Fi Kit", -3f, 0.7f, 0.25f)
    val KIT_TIGHT = kit("Tight Kit", 4f, 0.45f, 0.6f)
    val KIT_BOOM = kit("Boom Kit", -5f, 1.4f, 0.35f, kickDecay = 1.8f).let { k ->
        k.withPad(1, DrumPad("Sub Kick", DrumSound.SUB_KICK, tune = -5f, decay = 1.5f))
            .withPad(13, DrumPad("Zap", DrumSound.ZAP, decay = 0.8f, level = 0.6f))
    }

    val ALL = listOf(KIT_808, KIT_909, KIT_LOFI, KIT_TIGHT, KIT_BOOM)
}

@Serializable
enum class Waveform(val label: String) { SAW("Saw"), SQUARE("Square"), TRIANGLE("Triangle"), SINE("Sine"), NOISE("Noise") }

@Serializable
enum class FilterMode(val label: String) { LOW_PASS("LP"), HIGH_PASS("HP"), BAND_PASS("BP") }

/** A two-oscillator subtractive synth in the spirit of Live's Drift. All 0..1 unless noted. */
@Serializable
data class SynthPatch(
    val name: String,
    val osc1: Waveform = Waveform.SAW,
    val osc2: Waveform = Waveform.SQUARE,
    /** Semitones, -24..24. */
    val osc2Semi: Int = 0,
    /** Cents, -50..50. */
    val osc2Detune: Float = 7f,
    val oscMix: Float = 0.5f,
    val subLevel: Float = 0f,
    val noiseLevel: Float = 0f,
    val filterMode: FilterMode = FilterMode.LOW_PASS,
    val cutoff: Float = 0.6f,
    val resonance: Float = 0.2f,
    /** -1..1 */
    val filterEnvAmount: Float = 0.3f,
    val keyTracking: Float = 0.5f,
    /** Seconds. */
    val attack: Float = 0.005f,
    val decay: Float = 0.3f,
    val sustain: Float = 0.7f,
    val release: Float = 0.25f,
    val filterAttack: Float = 0.005f,
    val filterDecay: Float = 0.35f,
    val filterSustain: Float = 0.2f,
    val filterRelease: Float = 0.3f,
    /** Hz. */
    val lfoRate: Float = 4f,
    val lfoToPitch: Float = 0f,
    val lfoToFilter: Float = 0f,
    /** Seconds; > 0 makes the synth monophonic with portamento. */
    val glide: Float = 0f,
    val mono: Boolean = false,
    val gain: Float = 0.7f,
    /** Semitones applied to every note. */
    val transpose: Int = 0,
)

object SynthPresets {
    val BASS = SynthPatch(
        "Sub Bass", osc1 = Waveform.SAW, osc2 = Waveform.SQUARE, osc2Semi = -12, osc2Detune = 0f, oscMix = 0.4f,
        subLevel = 0.6f, cutoff = 0.32f, resonance = 0.25f, filterEnvAmount = 0.35f, attack = 0.002f, decay = 0.4f,
        sustain = 0.8f, release = 0.12f, filterDecay = 0.25f, mono = true, glide = 0.03f, gain = 0.75f,
    )
    val KEYS = SynthPatch(
        "Soft Keys", osc1 = Waveform.TRIANGLE, osc2 = Waveform.SINE, osc2Semi = 12, osc2Detune = 3f, oscMix = 0.35f,
        cutoff = 0.55f, resonance = 0.1f, filterEnvAmount = 0.25f, attack = 0.004f, decay = 0.9f, sustain = 0.35f,
        release = 0.45f, filterDecay = 0.6f, gain = 0.6f,
    )
    val LEAD = SynthPatch(
        "Detuned Lead", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 14f, oscMix = 0.5f, cutoff = 0.62f,
        resonance = 0.3f, filterEnvAmount = 0.25f, attack = 0.01f, decay = 0.3f, sustain = 0.75f, release = 0.2f,
        lfoRate = 5.5f, lfoToPitch = 0.08f, gain = 0.5f,
    )
    val PAD = SynthPatch(
        "Warm Pad", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 18f, oscMix = 0.5f, cutoff = 0.42f,
        resonance = 0.15f, filterEnvAmount = 0.2f, attack = 0.6f, decay = 1.2f, sustain = 0.8f, release = 1.4f,
        filterAttack = 0.8f, filterDecay = 1.5f, filterSustain = 0.5f, lfoRate = 0.3f, lfoToFilter = 0.15f, gain = 0.45f,
    )
    val PLUCK = SynthPatch(
        "Pluck", osc1 = Waveform.SQUARE, osc2 = Waveform.SAW, osc2Semi = 12, osc2Detune = 5f, oscMix = 0.3f,
        cutoff = 0.3f, resonance = 0.35f, filterEnvAmount = 0.6f, attack = 0.001f, decay = 0.35f, sustain = 0f,
        release = 0.3f, filterDecay = 0.18f, filterSustain = 0f, gain = 0.6f,
    )
    val BELL = SynthPatch(
        "Glass Bell", osc1 = Waveform.SINE, osc2 = Waveform.TRIANGLE, osc2Semi = 19, osc2Detune = 0f, oscMix = 0.4f,
        cutoff = 0.8f, resonance = 0.05f, filterEnvAmount = 0f, attack = 0.001f, decay = 1.6f, sustain = 0f,
        release = 1.2f, gain = 0.55f,
    )
    val ACID = SynthPatch(
        "Acid", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 0f, oscMix = 0f, cutoff = 0.22f,
        resonance = 0.75f, filterEnvAmount = 0.55f, attack = 0.001f, decay = 0.2f, sustain = 0.6f, release = 0.08f,
        filterDecay = 0.16f, filterSustain = 0f, mono = true, glide = 0.06f, gain = 0.5f,
    )
    val STRINGS = SynthPatch(
        "Strings", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 10f, oscMix = 0.5f, cutoff = 0.5f,
        resonance = 0.05f, filterEnvAmount = 0.1f, attack = 0.25f, decay = 0.5f, sustain = 0.85f, release = 0.8f,
        lfoRate = 4.5f, lfoToPitch = 0.05f, gain = 0.45f,
    )

    val ALL = listOf(BASS, KEYS, LEAD, PAD, PLUCK, BELL, ACID, STRINGS)
}

/** Plays one sample chromatically, like Live's Simpler / Move's Melodic Sampler. */
@Serializable
data class SamplerPatch(
    val name: String = "Sampler",
    val sampleId: String? = null,
    val rootNote: Int = 60,
    /** 0..1 fractions of the sample. */
    val start: Float = 0f,
    val end: Float = 1f,
    val loop: Boolean = false,
    val reverse: Boolean = false,
    val attack: Float = 0.002f,
    val decay: Float = 0.5f,
    val sustain: Float = 1f,
    val release: Float = 0.2f,
    val cutoff: Float = 1f,
    val resonance: Float = 0.1f,
    val gain: Float = 0.8f,
    /** When false every note plays the sample at its original pitch (one-shot / slice style). */
    val pitched: Boolean = true,
)

/** A preset from a SoundFont (.sf2) file. */
@Serializable
data class SoundFontPatch(
    val fontId: String? = null,
    val fontName: String = "",
    val bank: Int = 0,
    val program: Int = 0,
    val presetName: String = "",
    val gain: Float = 0.8f,
)

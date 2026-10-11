package com.spectraseq.core.model

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
    SNAP("Snap"), TAMBOURINE("Tambourine"), WOODBLOCK("Woodblock"), BONGO("Bongo"), AGOGO("Agogo"),
    BELL("Bell"), NOISE("Noise Hit"), BOOM_808("808 Boom"),
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
    /** Region of the sample to play (fractions), e.g. one slice of a loop. */
    val start: Float = 0f,
    val end: Float = 1f,
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

    private fun p(sound: DrumSound, tune: Float = 0f, decay: Float = 1f, tone: Float = 0.5f, level: Float = 0.8f, pan: Float = 0f,
                  choke: Int = 0, name: String = sound.label) = DrumPad(name, sound, tune, decay, tone, level, pan, chokeGroup = choke)

    val KIT_TRAP = DrumKit("Trap Kit", listOf(
        p(DrumSound.BOOM_808, decay = 1.6f, tone = 0.4f, level = 0.85f, name = "808"), p(DrumSound.KICK, tune = 3f, decay = 0.5f, tone = 0.8f),
        p(DrumSound.SNARE, tune = 4f, decay = 0.7f, tone = 0.8f), p(DrumSound.CLAP, decay = 0.9f, tone = 0.7f),
        p(DrumSound.CLOSED_HAT, tune = 3f, decay = 0.5f, tone = 0.8f, level = 0.55f, choke = 1), p(DrumSound.PEDAL_HAT, tune = 3f, decay = 0.6f, level = 0.5f, choke = 1),
        p(DrumSound.OPEN_HAT, tune = 3f, decay = 0.8f, tone = 0.8f, level = 0.5f, choke = 1), p(DrumSound.SNAP, level = 0.7f),
        p(DrumSound.BOOM_808, tune = 5f, decay = 1.6f, name = "808 F"), p(DrumSound.BOOM_808, tune = 7f, decay = 1.6f, name = "808 G"),
        p(DrumSound.TOM_HIGH, tune = 2f, decay = 0.6f), p(DrumSound.RIM, tune = 2f, level = 0.6f),
        p(DrumSound.SHAKER, decay = 0.7f, level = 0.45f), p(DrumSound.BELL, tune = 5f, level = 0.45f),
        p(DrumSound.CRASH, decay = 0.8f, level = 0.4f), p(DrumSound.NOISE, decay = 2f, tone = 0.7f, level = 0.4f, name = "Riser"),
    ))
    val KIT_HOUSE = DrumKit("House Kit", listOf(
        p(DrumSound.KICK, tune = 1f, decay = 0.9f, tone = 0.65f), p(DrumSound.RIM, tune = 1f, level = 0.6f),
        p(DrumSound.SNARE, tune = 2f, decay = 0.8f, tone = 0.6f), p(DrumSound.CLAP, decay = 1.1f, tone = 0.6f),
        p(DrumSound.CLOSED_HAT, tune = 1f, decay = 0.9f, tone = 0.7f, level = 0.55f, choke = 1), p(DrumSound.SHAKER, decay = 0.9f, level = 0.5f),
        p(DrumSound.OPEN_HAT, tune = 1f, decay = 0.8f, tone = 0.7f, level = 0.5f, choke = 1), p(DrumSound.TAMBOURINE, level = 0.5f),
        p(DrumSound.CONGA, pan = -0.3f), p(DrumSound.CONGA, tune = 5f, pan = 0.3f, name = "Hi Conga"),
        p(DrumSound.BONGO, pan = 0.2f), p(DrumSound.COWBELL, level = 0.45f),
        p(DrumSound.SNAP, level = 0.6f), p(DrumSound.WOODBLOCK, level = 0.55f),
        p(DrumSound.RIDE, decay = 0.9f, level = 0.4f), p(DrumSound.CRASH, level = 0.4f),
    ))
    val KIT_TECHNO = DrumKit("Techno Kit", listOf(
        p(DrumSound.KICK, tune = -2f, decay = 1.2f, tone = 0.3f), p(DrumSound.SUB_KICK, tune = -3f, decay = 1.4f, name = "Rumble"),
        p(DrumSound.SNARE, tune = -2f, decay = 1.1f, tone = 0.35f), p(DrumSound.CLAP, decay = 1.4f, tone = 0.4f),
        p(DrumSound.CLOSED_HAT, decay = 0.6f, tone = 0.9f, level = 0.55f, choke = 1), p(DrumSound.NOISE, decay = 0.25f, tone = 0.9f, level = 0.45f, name = "Tick"),
        p(DrumSound.OPEN_HAT, decay = 0.7f, tone = 0.9f, level = 0.5f, choke = 1), p(DrumSound.RIDE, tune = 2f, decay = 0.8f, level = 0.4f),
        p(DrumSound.TOM_LOW, tune = -3f, decay = 1.3f), p(DrumSound.RIM, tune = -4f, level = 0.6f),
        p(DrumSound.ZAP, decay = 0.6f, level = 0.5f), p(DrumSound.BELL, tune = -5f, level = 0.4f),
        p(DrumSound.CLAVE, tune = -5f, level = 0.5f), p(DrumSound.COWBELL, tune = -3f, level = 0.4f),
        p(DrumSound.CRASH, tune = -3f, decay = 1.3f, level = 0.4f), p(DrumSound.NOISE, decay = 3f, tone = 0.3f, level = 0.35f, name = "Wash"),
    ))
    val KIT_ELECTRO = DrumKit("Electro Kit", listOf(
        p(DrumSound.KICK, tune = 2f, decay = 0.8f, tone = 0.9f), p(DrumSound.ZAP, decay = 0.8f, level = 0.6f),
        p(DrumSound.SNARE, tune = 5f, decay = 0.6f, tone = 0.9f), p(DrumSound.CLAP, decay = 0.7f, tone = 0.9f),
        p(DrumSound.CLOSED_HAT, tune = 4f, decay = 0.5f, tone = 1f, level = 0.5f, choke = 1), p(DrumSound.PEDAL_HAT, tune = 4f, level = 0.5f, choke = 1),
        p(DrumSound.OPEN_HAT, tune = 4f, decay = 0.6f, tone = 1f, level = 0.5f, choke = 1), p(DrumSound.CLAVE, tune = 2f, level = 0.55f),
        p(DrumSound.TOM_LOW, tune = 4f, decay = 0.7f, pan = -0.3f), p(DrumSound.TOM_MID, tune = 4f, decay = 0.7f), p(DrumSound.TOM_HIGH, tune = 4f, decay = 0.7f, pan = 0.3f),
        p(DrumSound.COWBELL, tune = 2f, level = 0.5f), p(DrumSound.BONGO, tune = 3f), p(DrumSound.ZAP, tune = 12f, decay = 0.4f, level = 0.5f, name = "Laser"),
        p(DrumSound.CRASH, tune = 3f, decay = 0.7f, level = 0.4f), p(DrumSound.BELL, tune = 7f, level = 0.4f),
    ))
    val KIT_PERC = DrumKit("Percussion Kit", listOf(
        p(DrumSound.CONGA, tune = -3f, pan = -0.3f, name = "Low Conga"), p(DrumSound.CONGA, pan = -0.1f), p(DrumSound.CONGA, tune = 5f, pan = 0.2f, name = "High Conga"),
        p(DrumSound.BONGO, tune = -2f, pan = -0.4f, name = "Low Bongo"), p(DrumSound.BONGO, tune = 4f, pan = 0.4f, name = "High Bongo"),
        p(DrumSound.SHAKER, level = 0.5f), p(DrumSound.TAMBOURINE, level = 0.5f), p(DrumSound.CLAVE, level = 0.6f),
        p(DrumSound.WOODBLOCK, pan = -0.2f), p(DrumSound.WOODBLOCK, tune = 5f, pan = 0.2f, name = "High Block"),
        p(DrumSound.AGOGO, pan = -0.3f), p(DrumSound.AGOGO, tune = 4f, pan = 0.3f, name = "High Agogo"),
        p(DrumSound.COWBELL, level = 0.5f), p(DrumSound.SNAP, level = 0.6f), p(DrumSound.BELL, level = 0.45f), p(DrumSound.RIDE, level = 0.4f),
    ))
    val KIT_MINIMAL = DrumKit("Minimal Kit", listOf(
        p(DrumSound.KICK, tune = 3f, decay = 0.45f, tone = 0.7f), p(DrumSound.RIM, tune = 4f, decay = 0.6f, level = 0.6f),
        p(DrumSound.SNARE, tune = 6f, decay = 0.4f, tone = 0.7f), p(DrumSound.SNAP, level = 0.7f),
        p(DrumSound.CLOSED_HAT, tune = 6f, decay = 0.35f, tone = 0.9f, level = 0.5f, choke = 1), p(DrumSound.NOISE, decay = 0.15f, tone = 1f, level = 0.4f, name = "Click"),
        p(DrumSound.OPEN_HAT, tune = 6f, decay = 0.4f, tone = 0.9f, level = 0.45f, choke = 1), p(DrumSound.WOODBLOCK, tune = 7f, level = 0.5f),
        p(DrumSound.CLAVE, tune = 4f, level = 0.5f), p(DrumSound.BONGO, tune = 7f, decay = 0.5f), p(DrumSound.CONGA, tune = 6f, decay = 0.5f),
        p(DrumSound.BELL, tune = 12f, decay = 0.5f, level = 0.4f), p(DrumSound.AGOGO, tune = 7f, level = 0.45f),
        p(DrumSound.SHAKER, decay = 0.5f, level = 0.45f), p(DrumSound.ZAP, tune = 7f, decay = 0.3f, level = 0.45f), p(DrumSound.RIDE, tune = 5f, decay = 0.5f, level = 0.35f),
    ))

    val ALL = listOf(KIT_808, KIT_909, KIT_TRAP, KIT_HOUSE, KIT_TECHNO, KIT_ELECTRO, KIT_MINIMAL, KIT_PERC, KIT_LOFI, KIT_TIGHT, KIT_BOOM)
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


    // Basses
    val REESE = SynthPatch(
        "Reese Bass", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 22f, oscMix = 0.5f, subLevel = 0.4f, cutoff = 0.38f,
        resonance = 0.2f, filterEnvAmount = 0.05f, attack = 0.005f, decay = 0.5f, sustain = 0.9f, release = 0.15f,
        lfoRate = 0.4f, lfoToFilter = 0.08f, mono = true, gain = 0.6f,
    )
    val WOBBLE = SynthPatch(
        "Wobble Bass", osc1 = Waveform.SAW, osc2 = Waveform.SQUARE, osc2Semi = -12, osc2Detune = 4f, oscMix = 0.45f, subLevel = 0.5f,
        cutoff = 0.3f, resonance = 0.55f, filterEnvAmount = 0f, attack = 0.003f, sustain = 1f, release = 0.12f,
        lfoRate = 3f, lfoToFilter = 0.45f, mono = true, gain = 0.6f,
    )
    val BOOM_BASS = SynthPatch(
        "808 Bass", osc1 = Waveform.SINE, osc2 = Waveform.TRIANGLE, osc2Detune = 0f, oscMix = 0.15f, subLevel = 0.3f, cutoff = 0.45f,
        resonance = 0f, filterEnvAmount = 0.15f, attack = 0.002f, decay = 1.8f, sustain = 0.25f, release = 0.4f, glide = 0.06f,
        mono = true, gain = 0.85f,
    )
    val SQUARE_BASS = SynthPatch(
        "Square Bass", osc1 = Waveform.SQUARE, osc2 = Waveform.SQUARE, osc2Semi = -12, osc2Detune = 0f, oscMix = 0.4f, cutoff = 0.35f,
        resonance = 0.3f, filterEnvAmount = 0.4f, attack = 0.002f, decay = 0.25f, sustain = 0.5f, release = 0.1f, filterDecay = 0.2f,
        filterSustain = 0.1f, mono = true, gain = 0.6f,
    )
    // Keys
    val ORGAN = SynthPatch(
        "Organ", osc1 = Waveform.SINE, osc2 = Waveform.SQUARE, osc2Semi = 12, osc2Detune = 0f, oscMix = 0.25f, subLevel = 0.35f,
        cutoff = 0.6f, resonance = 0f, filterEnvAmount = 0f, attack = 0.004f, decay = 0.1f, sustain = 1f, release = 0.06f,
        lfoRate = 6.5f, lfoToPitch = 0.03f, gain = 0.45f,
    )
    val EPIANO = SynthPatch(
        "E-Piano", osc1 = Waveform.SINE, osc2 = Waveform.TRIANGLE, osc2Semi = 12, osc2Detune = 2f, oscMix = 0.2f, cutoff = 0.5f,
        resonance = 0.05f, filterEnvAmount = 0.3f, keyTracking = 0.8f, attack = 0.002f, decay = 1.4f, sustain = 0.2f, release = 0.5f,
        filterDecay = 0.4f, filterSustain = 0.1f, lfoRate = 4.5f, lfoToFilter = 0.04f, gain = 0.65f,
    )
    val CLAV = SynthPatch(
        "Clav", osc1 = Waveform.SQUARE, osc2 = Waveform.SAW, osc2Detune = 3f, oscMix = 0.4f, filterMode = FilterMode.BAND_PASS,
        cutoff = 0.55f, resonance = 0.35f, filterEnvAmount = 0.3f, attack = 0.001f, decay = 0.4f, sustain = 0.15f, release = 0.12f,
        filterDecay = 0.2f, filterSustain = 0f, gain = 0.6f,
    )
    val CHORD_STAB = SynthPatch(
        "Chord Stab", osc1 = Waveform.SAW, osc2 = Waveform.SQUARE, osc2Semi = 7, osc2Detune = 4f, oscMix = 0.4f, cutoff = 0.4f,
        resonance = 0.3f, filterEnvAmount = 0.5f, attack = 0.002f, decay = 0.28f, sustain = 0f, release = 0.25f, filterDecay = 0.2f,
        filterSustain = 0f, gain = 0.5f,
    )
    // Leads
    val SUPERSAW = SynthPatch(
        "Supersaw", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 28f, oscMix = 0.5f, subLevel = 0.15f, cutoff = 0.7f,
        resonance = 0.1f, filterEnvAmount = 0.1f, attack = 0.01f, decay = 0.4f, sustain = 0.85f, release = 0.35f, gain = 0.45f,
    )
    val SQUARE_LEAD = SynthPatch(
        "Square Lead", osc1 = Waveform.SQUARE, osc2 = Waveform.SQUARE, osc2Semi = 12, osc2Detune = 6f, oscMix = 0.3f, cutoff = 0.6f,
        resonance = 0.2f, filterEnvAmount = 0.2f, attack = 0.005f, decay = 0.3f, sustain = 0.8f, release = 0.15f, glide = 0.04f,
        lfoRate = 5.8f, lfoToPitch = 0.06f, mono = true, gain = 0.45f,
    )
    val CHIP = SynthPatch(
        "Chiptune", osc1 = Waveform.SQUARE, osc2 = Waveform.TRIANGLE, osc2Semi = -12, osc2Detune = 0f, oscMix = 0.25f, cutoff = 1f,
        resonance = 0f, filterEnvAmount = 0f, keyTracking = 0f, attack = 0.001f, decay = 0.15f, sustain = 0.6f, release = 0.05f, gain = 0.4f,
    )
    val BRASS = SynthPatch(
        "Synth Brass", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Detune = 9f, oscMix = 0.5f, cutoff = 0.35f, resonance = 0.15f,
        filterEnvAmount = 0.45f, attack = 0.04f, decay = 0.5f, sustain = 0.75f, release = 0.25f, filterAttack = 0.08f, filterDecay = 0.5f,
        filterSustain = 0.4f, gain = 0.5f,
    )
    val SYNC_LEAD = SynthPatch(
        "Screamer", osc1 = Waveform.SAW, osc2 = Waveform.SQUARE, osc2Semi = 19, osc2Detune = 0f, oscMix = 0.5f, noiseLevel = 0.03f,
        cutoff = 0.5f, resonance = 0.6f, filterEnvAmount = 0.35f, attack = 0.003f, decay = 0.6f, sustain = 0.7f, release = 0.2f,
        lfoRate = 6f, lfoToPitch = 0.07f, mono = true, glide = 0.05f, gain = 0.4f,
    )
    // Pads
    val SWEEP_PAD = SynthPatch(
        "Sweep Pad", osc1 = Waveform.SAW, osc2 = Waveform.SQUARE, osc2Semi = 12, osc2Detune = 12f, oscMix = 0.4f, cutoff = 0.25f,
        resonance = 0.45f, filterEnvAmount = 0.4f, attack = 1.2f, decay = 2f, sustain = 0.8f, release = 2f, filterAttack = 2.5f,
        filterDecay = 3f, filterSustain = 0.6f, lfoRate = 0.15f, lfoToFilter = 0.25f, gain = 0.4f,
    )
    val CHOIR = SynthPatch(
        "Air Choir", osc1 = Waveform.TRIANGLE, osc2 = Waveform.SAW, osc2Semi = 12, osc2Detune = 8f, oscMix = 0.3f, noiseLevel = 0.04f,
        filterMode = FilterMode.BAND_PASS, cutoff = 0.55f, resonance = 0.3f, filterEnvAmount = 0.05f, attack = 0.45f, decay = 1f,
        sustain = 0.85f, release = 1.2f, lfoRate = 5f, lfoToPitch = 0.04f, gain = 0.55f,
    )
    val DARK_DRONE = SynthPatch(
        "Dark Drone", osc1 = Waveform.SAW, osc2 = Waveform.SAW, osc2Semi = -12, osc2Detune = 15f, oscMix = 0.5f, subLevel = 0.3f,
        noiseLevel = 0.05f, cutoff = 0.22f, resonance = 0.35f, filterEnvAmount = 0.1f, attack = 1.5f, decay = 2f, sustain = 1f,
        release = 2.5f, lfoRate = 0.08f, lfoToFilter = 0.3f, gain = 0.5f,
    )
    // Plucks, mallets & FX
    val MARIMBA = SynthPatch(
        "Marimba", osc1 = Waveform.SINE, osc2 = Waveform.SINE, osc2Semi = 24, osc2Detune = 0f, oscMix = 0.15f, cutoff = 0.7f,
        resonance = 0f, filterEnvAmount = 0f, attack = 0.001f, decay = 0.45f, sustain = 0f, release = 0.35f, gain = 0.75f,
    )
    val KALIMBA = SynthPatch(
        "Kalimba", osc1 = Waveform.TRIANGLE, osc2 = Waveform.SINE, osc2Semi = 12, osc2Detune = 0f, oscMix = 0.3f, cutoff = 0.6f,
        resonance = 0.1f, filterEnvAmount = 0.2f, attack = 0.001f, decay = 0.8f, sustain = 0f, release = 0.6f, filterDecay = 0.1f,
        filterSustain = 0f, gain = 0.7f,
    )
    val HARP = SynthPatch(
        "Harp Pluck", osc1 = Waveform.SAW, osc2 = Waveform.TRIANGLE, osc2Semi = 12, osc2Detune = 3f, oscMix = 0.5f, cutoff = 0.28f,
        resonance = 0.1f, filterEnvAmount = 0.45f, keyTracking = 0.9f, attack = 0.001f, decay = 1.2f, sustain = 0f, release = 0.9f,
        filterDecay = 0.25f, filterSustain = 0f, gain = 0.6f,
    )
    val RISER = SynthPatch(
        "Noise Riser", osc1 = Waveform.NOISE, osc2 = Waveform.SAW, osc2Detune = 20f, oscMix = 0.2f, filterMode = FilterMode.BAND_PASS,
        cutoff = 0.3f, resonance = 0.5f, filterEnvAmount = 0.6f, keyTracking = 0.2f, attack = 2f, decay = 0.5f, sustain = 1f,
        release = 0.8f, filterAttack = 3f, filterDecay = 1f, filterSustain = 1f, gain = 0.4f,
    )
    val SCI_FI = SynthPatch(
        "Sci-Fi FX", osc1 = Waveform.SQUARE, osc2 = Waveform.SAW, osc2Semi = 7, osc2Detune = 30f, oscMix = 0.5f, cutoff = 0.45f,
        resonance = 0.7f, filterEnvAmount = 0.2f, attack = 0.01f, decay = 0.6f, sustain = 0.7f, release = 0.6f,
        lfoRate = 8f, lfoToPitch = 0.4f, lfoToFilter = 0.3f, gain = 0.35f,
    )

    /** Presets grouped for the browser. */
    val CATEGORIES: List<Pair<String, List<SynthPatch>>> = listOf(
        "Bass" to listOf(BASS, REESE, WOBBLE, BOOM_BASS, SQUARE_BASS, ACID),
        "Keys" to listOf(KEYS, EPIANO, ORGAN, CLAV, CHORD_STAB),
        "Lead" to listOf(LEAD, SUPERSAW, SQUARE_LEAD, SYNC_LEAD, CHIP, BRASS),
        "Pad" to listOf(PAD, STRINGS, SWEEP_PAD, CHOIR, DARK_DRONE),
        "Pluck & Mallet" to listOf(PLUCK, BELL, MARIMBA, KALIMBA, HARP),
        "FX" to listOf(RISER, SCI_FI),
    )

    val ALL = CATEGORIES.flatMap { it.second }
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

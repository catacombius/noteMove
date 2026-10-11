package com.spectraseq.core.model

/** Starting points for a new set, offered on the start screen. */
enum class SetTemplate(val label: String, val description: String) {
    BAND("Band", "Drums, bass, keys and lead"),
    BEAT("Beat", "Drums and bass"),
    EMPTY("Empty", "One drum and one synth track"),
    SLICE("Slice a loop", "Load a loop and cut it across the pads"),
    GENERAL_MIDI("General MIDI", "Pianos, guitars, strings… from a SoundFont");

    fun create(name: String): Project = when (this) {
        BAND -> Project.createDefault(name)
        BEAT -> Project(
            name = name,
            tracks = listOf(
                Track(name = "Drums", kind = TrackKind.DRUMS, color = 1, drumKit = DrumKits.KIT_TRAP),
                Track(name = "808", kind = TrackKind.SYNTH, color = 9, synth = SynthPresets.BOOM_BASS),
            ),
            tempo = 140.0,
        )
        EMPTY -> Project(
            name = name,
            tracks = listOf(
                Track(name = "Drums", kind = TrackKind.DRUMS, color = 1, drumKit = DrumKits.KIT_808),
                Track(name = "Synth", kind = TrackKind.SYNTH, color = 5, synth = SynthPresets.KEYS),
            ),
        )
        SLICE -> Project(
            name = name,
            tracks = listOf(
                Track(name = "Loop", kind = TrackKind.SAMPLER, color = 12, sampler = SamplerPatch()),
                Track(name = "Bass", kind = TrackKind.SYNTH, color = 9, synth = SynthPresets.BASS),
            ),
        )
        GENERAL_MIDI -> Project(
            name = name,
            tracks = listOf(
                Track(name = "Drums", kind = TrackKind.DRUMS, color = 1, drumKit = DrumKits.KIT_808),
                Track(name = "Piano", kind = TrackKind.SOUNDFONT, color = 8, soundfont = SoundFontPatch()),
                Track(name = "Strings", kind = TrackKind.SOUNDFONT, color = 11, soundfont = SoundFontPatch(program = 48)),
            ),
        )
    }
}

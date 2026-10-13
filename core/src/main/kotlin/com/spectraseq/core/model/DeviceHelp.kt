package com.spectraseq.core.model

/** Explanation of a patcher node: what it does, what each input and knob means, and an idea to try. */
data class NodeHelp(val summary: String, val inputs: List<String>, val params: List<String>, val tip: String)

/** Plain-language help for patcher nodes, built-in effects and rack modulators (shown in the info pop-ups). */
object DeviceHelp {
    fun node(kind: NodeKind): NodeHelp = when (kind) {
        NodeKind.IN -> NodeHelp(
            "The audio coming into the device: the track (or the previous effect in the chain).",
            emptyList(), emptyList(),
            "Wire it to Output to pass the sound through untouched, or through other nodes to change it.",
        )
        NodeKind.OUT -> NodeHelp(
            "What the device sends on. Everything wired into its input is added together.",
            listOf("The final sound. Leave it unconnected and the device goes silent."),
            emptyList(),
            "Wire both the Input and an effect into Output to blend the dry and processed sound.",
        )
        NodeKind.FILTER -> NodeHelp(
            "A resonant filter that removes highs (low-pass), lows (high-pass) or keeps a band around the cutoff (band-pass).",
            listOf("Audio to filter.", "Moves the cutoff up and down. Wire an LFO here for a wobble or an Env follower for an auto-wah."),
            listOf("Low-pass, band-pass or high-pass.", "Where the filter starts cutting, 20 Hz to 20 kHz.", "Boost at the cutoff. High values ring and whistle."),
            "Input → Filter → Output, then an LFO into Cutoff mod: instant filter sweep.",
        )
        NodeKind.DELAY -> NodeHelp(
            "An echo synced to the tempo. It's also the only node that may sit inside a feedback loop.",
            listOf("Audio to echo. You can wire later nodes back into it to make feedback loops."),
            listOf("Echo time as a note length (1/16, 1/8, dotted, triplets…).", "How much of each echo is fed back: more = more repeats."),
            "Put a Filter or Shaper after the Delay and wire it back into the Delay's input: every repeat gets darker or dirtier.",
        )
        NodeKind.GAIN -> NodeHelp(
            "Turns a signal up or down (±24 dB). Its mod input makes it a VCA: tremolo, pumping, gating.",
            listOf("Audio (or a control signal) to scale.", "Adds to the gain: +1 doubles it, −1 mutes it. Wire an LFO for tremolo."),
            listOf("Level change in dB. The middle is 0 dB (no change)."),
            "LFO (square) → Gain mod gives a rhythmic chop; Env follower → Gain mod with a Math node set to negative scale gives ducking.",
        )
        NodeKind.MIX -> NodeHelp(
            "Crossfades between two signals.",
            listOf("Signal heard at Mix 0%.", "Signal heard at Mix 100%.", "Adds to the Mix knob, so an LFO or Knob node can move the blend."),
            listOf("Balance between A and B."),
            "Input into A, an effect chain into B, and a Knob node into Mix mod: a dry/wet knob for your patch.",
        )
        NodeKind.SHAPER -> NodeHelp(
            "A waveshaper that adds harmonics: gentle warmth to heavy fuzz. Output level is compensated as you drive it.",
            listOf("Audio to distort."),
            listOf("How hard the signal hits the curve (0–36 dB).", "Soft (round saturation), Hard (clipping) or Fold (wraps the wave back for metallic tones)."),
            "Shaper before a low-pass Filter tames the fizz; after a resonant Filter it screams.",
        )
        NodeKind.LFO -> NodeHelp(
            "A slow wave synced to the tempo, used to move other nodes' mod inputs. It makes no sound on its own.",
            emptyList(),
            listOf("One cycle per note length (from several bars down to 1/32).", "Sine, triangle, square or saw.", "How far it swings (the output goes from −depth to +depth)."),
            "Wire it into Filter Cutoff mod, Gain mod or Mix mod. Two LFOs at different rates into a Math node make complex movement.",
        )
        NodeKind.ENV -> NodeHelp(
            "Follows the loudness of its input and outputs it as a control signal (0 to 1).",
            listOf("Audio whose loudness you want to follow, usually the Input."),
            listOf("How fast it rises when the sound gets louder.", "How fast it falls when the sound gets quieter."),
            "Input → Env follower → Filter Cutoff mod: the louder you play, the brighter it gets (auto-wah).",
        )
        NodeKind.MATH -> NodeHelp(
            "Combines two signals (add, multiply, max or min) and scales the result. Works on audio and control signals.",
            listOf("First signal.", "Second signal."),
            listOf("Add, Multiply, Max or Min.", "Multiplies the result by 0 to 2 (the middle is ×1)."),
            "Multiply two LFOs for rhythmic patterns, or multiply audio by an LFO for ring-mod-like tremolo.",
        )
        NodeKind.KNOB -> NodeHelp(
            "Puts one of the device's 8 knobs into the patch, so you can control the patch from the effects panel (and automate it).",
            emptyList(),
            listOf("Which device knob (1–8) this node follows.", "Output when the knob is all the way down.", "Output when the knob is all the way up."),
            "Wire it into a mod input and rename the knob under Device knobs, e.g. \"Wobble\" or \"Dirt\".",
        )
        NodeKind.NOISE -> NodeHelp(
            "White noise. As audio it adds hiss and air; as a control signal it gives random jitter.",
            emptyList(),
            listOf("Noise level."),
            "Noise → band-pass Filter → Output (mixed with the Input) adds breath; a little noise into a Delay's input gives tape-like texture.",
        )
    }

    fun effect(type: EffectType): String = when (type) {
        EffectType.FILTER -> "Resonant low/band/high-pass filter with a tempo-synced LFO for sweeps and wobbles."
        EffectType.SATURATOR -> "Warm analog-style drive. Adds harmonics and makes things louder and denser; Tone darkens or brightens it."
        EffectType.REDUX -> "Lowers bit depth and sample rate for crunchy, aliased, 8-bit textures."
        EffectType.CHORUS -> "Thickens the sound with slightly detuned, moving copies. Great on pads, keys and basses."
        EffectType.PHASER -> "Sweeping notches that give a swirling, jet-like movement."
        EffectType.COMPRESSOR -> "Evens out the level: loud parts are turned down above the threshold, then Makeup brings it all back up."
        EffectType.EQ -> "Three-band tone control: boost or cut lows, mids (with a movable centre) and highs."
        EffectType.TREMOLO -> "Moves the volume (tremolo) or the stereo position (auto-pan) in time with the song."
        EffectType.DELAY -> "Tempo-synced echo with feedback, optional ping-pong between left and right, and a tone control on the repeats."
        EffectType.REVERB -> "Simulated room or hall. Size sets the length, Damping darkens the tail."
        EffectType.FLANGER -> "A very short modulated delay with feedback: the classic whooshing comb sweep."
        EffectType.DISTORTION -> "Heavier distortion with four curves (soft, hard clip, wave folding, tube) and a tone filter."
        EffectType.RING_MOD -> "Ring mode multiplies the sound by a sine for bell-like, metallic tones; Shift mode moves every frequency by the same amount."
        EffectType.UTILITY -> "Stereo width, mono bass below a frequency, gain, pan and phase flip. The mixing toolbox."
        EffectType.GATE -> "Silences the sound below a threshold, or chops it rhythmically with a tempo-synced pattern (trance gate)."
        EffectType.TRANSIENT -> "Shapes the hit of each note: more or less attack (snap), more or less sustain (body and room)."
        EffectType.DUCKER -> "Sidechain-style pumping without routing: ducks the volume on every beat (or other note length) and lets it swell back."
        EffectType.TAPE -> "Cassette character: pitch wobble (wow and flutter), saturation, hiss and a narrower frequency range."
        EffectType.STUTTER -> "Randomly repeats short slices in time with the song, optionally pitching the repeats up or down."
        EffectType.PITCH -> "Shifts the pitch up or down by up to an octave without changing the speed."
        EffectType.LIMITER -> "Pushes the level up while never letting peaks go past the ceiling. Usually last on the master."
        EffectType.RACK -> "A rack made in the effect creator: a chain of effects with 8 macro knobs and optional modulators."
        EffectType.PATCH -> "A modular patch made in the patcher. Its knobs are the Knob nodes inside the patch."
    }

    fun modulator(kind: ModKind): String = when (kind) {
        ModKind.LFO -> "Moves its targets back and forth in a repeating wave, synced to the tempo."
        ModKind.ENVELOPE -> "Follows how loud the sound coming into the rack is, so louder playing moves the targets further."
        ModKind.RANDOM -> "Jumps to a new random value on every step of its rate (sample & hold), for unpredictable movement."
    }
}

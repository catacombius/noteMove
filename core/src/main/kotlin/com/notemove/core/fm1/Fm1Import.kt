package com.notemove.core.fm1

import com.notemove.core.model.Clip
import com.notemove.core.model.DRUM_BASE_NOTE
import com.notemove.core.model.DrumKits
import com.notemove.core.model.Note
import com.notemove.core.model.Project
import com.notemove.core.model.Scale
import com.notemove.core.model.SynthPresets
import com.notemove.core.model.Track
import com.notemove.core.model.TrackKind
import com.notesorcery.fm1link.Fm1Song
import kotlin.math.sqrt

/**
 * An M-VAVE FM-1 running NoteSorcery -> a NoteMove project (fm1link reads the FM-1; docs/NSX_PROTOCOL.md in NoteSorcery).
 * Its eight tracks become eight tracks, its song sections A..D scenes, its steps notes as the FM-1 plays them
 * (swing, nudges, ties, slides, ratchets). From here the project exports to Ableton Live as any other.
 */
object Fm1Import {
    /** FM-1 drum lane (fm1link Nsp1.LANE_NAME order) -> NoteMove drum pad (DrumKits order), by sound */
    val LANE_TO_PAD = intArrayOf(
        0,  // KICK      -> Kick
        0,  // KICK 2    -> Kick
        2,  // SNARE     -> Snare
        3,  // CLAP      -> Clap
        4,  // CL HAT    -> Closed Hat
        6,  // OP HAT    -> Open Hat
        5,  // PEDAL HAT -> Pedal Hat
        1,  // RIM       -> Rim
        2,  // SNARE 2   -> Snare
        8,  // TOM LO    -> Low Tom
        10, // TOM HI    -> High Tom
        14, // CRASH     -> Crash
        15, // RIDE      -> Ride
        7,  // SHAKER    -> Shaker
        12, // CONGA     -> Conga
        11, // COWBELL   -> Cowbell
    )

    /** the FM-1's track colours (OP-1 blue, green, white, orange …) as NoteMove's palette indices */
    private val COLORS = intArrayOf(9, 5, 8, 1, 11, 7, 13, 3)

    private fun synthFor(engine: String) = when (engine) {
        "ACID" -> SynthPresets.ACID
        "FM6" -> SynthPresets.BELL
        "WAVE" -> SynthPresets.PAD
        "SAMPLE" -> SynthPresets.KEYS
        "TRIO" -> SynthPresets.LEAD
        else -> SynthPresets.BASS
    }

    /** linear gain (1.0 = 0 dB) -> NoteMove's fader (Live volume = fader² / 0.64) */
    private fun fader(gain: Double): Float = sqrt(gain * 0.64).toFloat().coerceIn(0f, 1f)

    fun toProject(song: Fm1Song, name: String = song.name): Project {
        val tracks = song.tracks.map { t ->
            val clips = t.clips.mapValues { (_, c) ->
                val notes = c.notes.map { n ->
                    val pitch = if (t.isDrum) DRUM_BASE_NOTE + LANE_TO_PAD[(n.pitch - DRUM_BASE_NOTE).coerceIn(0, 15)] else n.pitch
                    Note(pitch, n.start, n.duration, n.velocity)
                }
                Clip(name = c.name, lengthBeats = c.lengthBeats).withNotes(notes)
            }
            Track(
                name = t.name,
                kind = if (t.isDrum) TrackKind.DRUMS else TrackKind.SYNTH,
                color = COLORS[t.index % COLORS.size],
                drumKit = if (t.isDrum) (if (t.index % 2 == 0) DrumKits.KIT_808 else DrumKits.KIT_909) else null,
                synth = if (t.isDrum) null else synthFor(t.engine),
                volume = fader(t.volume),
                pan = t.pan.toFloat(),
                mute = t.mute,
                clips = clips,
            )
        }
        return Project(
            name = name,
            tempo = song.tempo,
            swing = 0f,                    // (the FM-1's swing is in the notes' timing already)
            rootNote = song.rootNote,
            scale = Scale.entries.firstOrNull { it.liveName.equals(song.scaleName, ignoreCase = true) } ?: Scale.MINOR,
            tracks = tracks,
            sceneCount = song.sceneCount.coerceIn(8, Project.MAX_SCENES),
            sceneNames = song.sceneNames.withIndex().associate { it.index to it.value },
        )
    }
}

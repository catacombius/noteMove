package com.notemove.core.model

import kotlinx.serialization.Serializable
import java.util.UUID

/** One MIDI note inside a clip. Times are in beats (quarter notes), relative to the clip start. */
@Serializable
data class Note(
    val pitch: Int,
    val start: Double,
    val duration: Double,
    val velocity: Int = 100,
) {
    val end: Double get() = start + duration
}

@Serializable
data class Clip(
    val id: String = newId(),
    val name: String = "",
    /** Loop length in beats. */
    val lengthBeats: Double = 4.0,
    val notes: List<Note> = emptyList(),
) {
    val bars: Double get() = lengthBeats / BEATS_PER_BAR

    fun withNotes(newNotes: List<Note>) = copy(notes = newNotes.sortedWith(compareBy({ it.start }, { it.pitch })))
}

@Serializable
enum class TrackKind(val label: String) { DRUMS("Drums"), SYNTH("Synth"), SAMPLER("Sampler") }

@Serializable
data class TrackFx(
    /** 0..1, 1 = fully open low-pass. */
    val filterCutoff: Float = 1f,
    val filterResonance: Float = 0.1f,
    /** 0..1 saturation amount. */
    val drive: Float = 0f,
    val delaySend: Float = 0f,
    val reverbSend: Float = 0f,
)

@Serializable
data class Track(
    val id: String = newId(),
    val name: String,
    val kind: TrackKind,
    val color: Int = 0,
    val drumKit: DrumKit? = null,
    val synth: SynthPatch? = null,
    val sampler: SamplerPatch? = null,
    val volume: Float = 0.8f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val fx: TrackFx = TrackFx(),
    /** Session clips keyed by scene index. */
    val clips: Map<Int, Clip> = emptyMap(),
) {
    fun clipAt(scene: Int): Clip? = clips[scene]

    fun withClip(scene: Int, clip: Clip?): Track =
        copy(clips = if (clip == null) clips - scene else clips + (scene to clip))
}

@Serializable
data class SampleRef(
    val id: String = newId(),
    val name: String,
    /** File name inside the project's samples folder. */
    val fileName: String,
    val sampleRate: Int,
    val frames: Int,
)

@Serializable
data class GlobalFx(
    /** Delay time in 16th notes. */
    val delaySixteenths: Int = 3,
    val delayFeedback: Float = 0.4f,
    val delayLevel: Float = 0.8f,
    val reverbSize: Float = 0.7f,
    val reverbDamping: Float = 0.4f,
    val reverbLevel: Float = 0.8f,
)

@Serializable
enum class LaunchQuantization(val label: String, val beats: Double) {
    NONE("None", 0.0), BEAT("1/4", 1.0), BAR("1 Bar", 4.0), TWO_BARS("2 Bars", 8.0)
}

@Serializable
data class Project(
    val id: String = newId(),
    val name: String,
    val tempo: Double = 120.0,
    /** 0..1, delays every second 16th note by up to a 32nd. */
    val swing: Float = 0f,
    val rootNote: Int = 0,
    val scale: Scale = Scale.MINOR,
    val tracks: List<Track>,
    val sceneCount: Int = 8,
    val sceneNames: Map<Int, String> = emptyMap(),
    val samples: List<SampleRef> = emptyList(),
    val masterVolume: Float = 0.85f,
    val globalFx: GlobalFx = GlobalFx(),
    val launchQuantization: LaunchQuantization = LaunchQuantization.BAR,
    val createdAt: Long = System.currentTimeMillis(),
    val modifiedAt: Long = System.currentTimeMillis(),
) {
    fun track(id: String): Track? = tracks.firstOrNull { it.id == id }

    fun updateTrack(id: String, f: (Track) -> Track): Project =
        copy(tracks = tracks.map { if (it.id == id) f(it) else it })

    fun sceneName(index: Int): String = sceneNames[index]?.takeIf { it.isNotBlank() } ?: "Scene ${index + 1}"

    /** Length of a scene: the longest clip in it (at least one bar). */
    fun sceneLength(scene: Int): Double =
        tracks.mapNotNull { it.clips[scene]?.lengthBeats }.maxOrNull() ?: BEATS_PER_BAR

    fun usedScenes(): List<Int> = (0 until sceneCount).filter { s -> tracks.any { it.clips.containsKey(s) } }

    fun sample(id: String?): SampleRef? = id?.let { sid -> samples.firstOrNull { it.id == sid } }

    companion object {
        const val MAX_TRACKS = 16
        const val MAX_SCENES = 32

        fun createDefault(name: String): Project = Project(
            name = name,
            tracks = listOf(
                Track(name = "Drums", kind = TrackKind.DRUMS, color = 1, drumKit = DrumKits.KIT_808),
                Track(name = "Bass", kind = TrackKind.SYNTH, color = 9, synth = SynthPresets.BASS),
                Track(name = "Keys", kind = TrackKind.SYNTH, color = 5, synth = SynthPresets.KEYS),
                Track(name = "Lead", kind = TrackKind.SYNTH, color = 11, synth = SynthPresets.LEAD),
            ),
        )
    }
}

const val BEATS_PER_BAR = 4.0

fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

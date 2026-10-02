package com.notemove.core.export

import com.notemove.core.dsp.SampleBank
import com.notemove.core.dsp.SoundFontBank
import com.notemove.core.model.Project
import kotlinx.serialization.json.Json
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ProjectJson {
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false; allowStructuredMapKeys = true }
    fun encode(p: Project): String = json.encodeToString(Project.serializer(), p)
    fun decode(s: String): Project = json.decodeFromString(Project.serializer(), s)
}

/**
 * Builds a zipped Ableton Live project folder:
 *
 * ```
 * <Name> Project/
 *   <Name>.als                       Live Set (MIDI tracks, session clips, arrangement)
 *   Samples/Imported/<sample>.wav     samples recorded or imported on the phone
 *   Samples/Rendered/<track>/<clip>.wav  each clip rendered as a seamless audio loop (optional)
 *   <Name> Mixdown.wav               the scenes played in order (optional)
 *   MIDI/<Name>.mid, MIDI/Clips/...  plain MIDI files for any DAW
 *   <Name>.notemove                  the phone project, to re-open it in NoteMove
 * ```
 */
class ProjectPackager(private val sampleRate: Int, private val samples: SampleBank, private val soundFonts: SoundFontBank = SoundFontBank()) {

    data class Options(
        val renderClips: Boolean = true,
        val renderMixdown: Boolean = true,
        val midi: Boolean = true,
        val arrangement: Boolean = true,
    )

    fun write(
        project: Project,
        sampleFile: (String) -> File?,
        out: OutputStream,
        options: Options = Options(),
        onProgress: (String, Float) -> Unit = { _, _ -> },
        soundFontFile: (com.notemove.core.model.SoundFontRef) -> File? = { null },
    ) {
        val name = safeName(project.name)
        val root = "$name Project/"
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(root + path)); zip.write(bytes); zip.closeEntry()
            }
            onProgress("Live Set", 0.05f)
            put("$name.als", LiveSetExporter().exportBytes(project, LiveSetExporter.Options(arrangement = options.arrangement)))
            put("$name.notemove", ProjectJson.encode(project).toByteArray())
            put("README.txt", README.toByteArray())

            for (ref in project.samples) {
                val f = sampleFile(ref.fileName) ?: continue
                if (f.exists()) put("Samples/Imported/${safeName(ref.name)}.wav", f.readBytes())
            }
            // SoundFonts used by the set (skipped when huge, the rendered loops carry their sound anyway).
            for (ref in project.soundFonts) {
                val f = soundFontFile(ref) ?: continue
                if (f.exists() && f.length() < 96L * 1024 * 1024) put("Samples/SoundFonts/${safeName(ref.name)}.sf2", f.readBytes())
            }
            if (options.midi) {
                onProgress("MIDI", 0.1f)
                put("MIDI/$name.mid", MidiFileWriter.song(project))
                for (t in project.tracks) for ((scene, clip) in t.clips.toSortedMap()) {
                    put("MIDI/Clips/${safeName(t.name)} - ${safeName(project.sceneName(scene))}.mid", MidiFileWriter.clip(project, clip, t.name, t.kind))
                }
            }
            val renderer = OfflineRenderer(sampleRate, samples, soundFonts)
            if (options.renderClips) {
                val jobs = project.tracks.flatMap { t -> t.clips.keys.sorted().map { t to it } }
                jobs.forEachIndexed { i, (t, scene) ->
                    onProgress("Rendering ${t.name}", 0.15f + 0.6f * i / jobs.size.coerceAtLeast(1))
                    val audio = renderer.renderClipLoop(project, t.id, scene) ?: return@forEachIndexed
                    put("Samples/Rendered/${safeName(t.name)}/${safeName(t.name)} - ${safeName(project.sceneName(scene))}.wav",
                        Wav.bytes(sampleRate, audio.left, audio.right))
                }
            }
            if (options.renderMixdown && project.usedScenes().isNotEmpty()) {
                onProgress("Mixdown", 0.8f)
                val mix = renderer.renderSong(project)
                put("$name Mixdown.wav", Wav.bytes(sampleRate, mix.left, mix.right))
            }
            onProgress("Done", 1f)
        }
    }

    companion object {
        fun safeName(s: String): String = s.replace(Regex("[\\\\/:*?\"<>|\\x00-\\x1f]"), "_").trim().ifBlank { "Untitled" }.take(80)

        private const val README = """Made with NoteMove.

Open the .als file in Ableton Live 11 or 12.
- Every track is a MIDI track with its clips in Session View; scenes are also laid out in Arrangement View.
- Drum tracks use Drum Rack pad notes (pad 1 = C1): drop any Drum Rack / kit on the track.
- Synth and sampler tracks: drop an instrument (Drift, Wavetable, Simpler...) on the track.
- Samples/Rendered contains every clip rendered with the phone's sounds as seamless loops,
  so you can drag the exact sound you sketched onto an audio track.
- Samples/Imported contains samples you recorded or imported on the phone.
"""
    }
}

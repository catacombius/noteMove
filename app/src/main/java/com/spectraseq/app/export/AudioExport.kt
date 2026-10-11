package com.spectraseq.app.export

import com.spectraseq.core.dsp.SampleBank
import com.spectraseq.core.dsp.SoundFontBank
import com.spectraseq.core.export.AudioTools
import com.spectraseq.core.export.OfflineRenderer
import com.spectraseq.core.export.ProjectPackager
import com.spectraseq.core.export.Wav
import com.spectraseq.core.model.Project
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** What to render and how to write it. */
data class AudioExportOptions(
    val source: Source = Source.SONG,
    val stems: Boolean = false,
    val format: Format = Format.WAV24,
    val normalize: Boolean = true,
    val sampleRate: Int = 48000,
    val tailSeconds: Double = 2.0,
) {
    enum class Source(val label: String) { SONG("Song"), LOOP("Loop region"), SCENES("Scenes in order") }
    enum class Format(val label: String, val ext: String, val mime: String) {
        WAV24("WAV 24-bit", "wav", "audio/wav"), WAV16("WAV 16-bit", "wav", "audio/wav"), AAC("AAC (.m4a)", "m4a", "audio/mp4")
    }
}

/** Renders the set to audio files (a mixdown, or one file per track zipped together). */
class AudioExporter(private val engineRate: Int, private val samples: SampleBank, private val soundFonts: SoundFontBank) {

    /** Returns the file and its MIME type. Throws when there's nothing to render. */
    fun export(project: Project, o: AudioExportOptions, dir: File, onProgress: (String, Float) -> Unit): Pair<File, String> {
        val r = OfflineRenderer(engineRate, samples, soundFonts)
        val useSong = o.source != AudioExportOptions.Source.SCENES
        if (useSong && project.arrangement.isEmpty) error("The song is empty — record or arrange it first, or export the scenes")
        if (!useSong && project.usedScenes().isEmpty()) error("There are no clips to render")
        val base = ProjectPackager.safeName(project.name)
        if (!o.stems) {
            onProgress("Rendering", 0f)
            val mix = render(r, project, o) { onProgress("Rendering", it * 0.8f) } ?: error("Nothing to render")
            val f = File(dir, "$base.${o.format.ext}")
            write(finish(mix, o), o, f) { onProgress("Encoding", 0.8f + it * 0.2f) }
            onProgress("Done", 1f)
            return f to o.format.mime
        }
        val zip = File(dir, "$base Stems.zip")
        ZipOutputStream(zip.outputStream().buffered()).use { z ->
            val tracks = project.tracks
            // Stems share one gain so they still sum to the mix when normalising.
            val renders = ArrayList<Pair<String, OfflineRenderer.Stereo>>()
            tracks.forEachIndexed { i, t ->
                onProgress("Rendering ${t.name}", i.toFloat() / (tracks.size + 1) * 0.8f)
                val solo = project.copy(tracks = tracks.map { it.copy(solo = it.id == t.id, mute = false) })
                render(r, solo, o) { f -> onProgress("Rendering ${t.name}", (i + f) / (tracks.size + 1) * 0.8f) }?.let { renders.add(t.name to it) }
            }
            onProgress("Rendering mix", tracks.size.toFloat() / (tracks.size + 1) * 0.8f)
            render(r, project, o) {}?.let { renders.add("Mix" to it) }
            val gain = if (o.normalize) {
                val peak = renders.maxOfOrNull { AudioTools.peak(it.second) } ?: 0f
                if (peak > 1e-6f) 0.891f / peak else 1f
            } else 1f
            renders.forEachIndexed { i, (name, st) ->
                val scaled = OfflineRenderer.Stereo(FloatArray(st.left.size) { st.left[it] * gain }, FloatArray(st.right.size) { st.right[it] * gain })
                val tmp = File(dir, "stem.tmp.${o.format.ext}")
                write(AudioTools.resample(scaled, engineRate, o.sampleRate), o, tmp) {}
                z.putNextEntry(ZipEntry("$base Stems/${String.format("%02d", i + 1)} ${ProjectPackager.safeName(name)}.${o.format.ext}"))
                tmp.inputStream().use { it.copyTo(z) }
                z.closeEntry()
                tmp.delete()
                onProgress("Writing", 0.8f + 0.2f * (i + 1) / renders.size)
            }
        }
        onProgress("Done", 1f)
        return zip to "application/zip"
    }

    private fun render(r: OfflineRenderer, project: Project, o: AudioExportOptions, onProgress: (Float) -> Unit): OfflineRenderer.Stereo? = when (o.source) {
        AudioExportOptions.Source.SONG -> r.renderArrangement(project, loopOnly = false, tailSeconds = o.tailSeconds, onProgress = onProgress)
        AudioExportOptions.Source.LOOP -> r.renderArrangement(project, loopOnly = true, tailSeconds = o.tailSeconds, onProgress = onProgress)
        AudioExportOptions.Source.SCENES -> r.renderSong(project, tailSeconds = o.tailSeconds, onProgress = onProgress)
    }

    private fun finish(s: OfflineRenderer.Stereo, o: AudioExportOptions): OfflineRenderer.Stereo {
        val n = if (o.normalize) AudioTools.normalize(s, -1f) else s
        return AudioTools.resample(n, engineRate, o.sampleRate)
    }

    private fun write(s: OfflineRenderer.Stereo, o: AudioExportOptions, f: File, onProgress: (Float) -> Unit) {
        when (o.format) {
            AudioExportOptions.Format.WAV24 -> f.outputStream().buffered().use { Wav.write(it, o.sampleRate, s.left, s.right, 24) }
            AudioExportOptions.Format.WAV16 -> f.outputStream().buffered().use { Wav.write(it, o.sampleRate, s.left, s.right, 16) }
            AudioExportOptions.Format.AAC -> AacEncoder.encode(s.left, s.right, o.sampleRate, f, onProgress = onProgress)
        }
    }
}

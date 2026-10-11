package com.spectraseq.app.data

import android.content.Context
import com.spectraseq.core.dsp.SampleBank
import com.spectraseq.core.dsp.SampleData
import com.spectraseq.core.dsp.SoundFont
import com.spectraseq.core.dsp.SoundFontBank
import com.spectraseq.core.export.ProjectJson
import com.spectraseq.core.export.Wav
import com.spectraseq.core.model.Project
import com.spectraseq.core.model.SampleRef
import com.spectraseq.core.model.SoundFontRef
import kotlinx.serialization.builtins.ListSerializer
import com.spectraseq.core.model.newId
import java.io.File

data class ProjectSummary(val id: String, val name: String, val modifiedAt: Long, val tempo: Double, val tracks: Int, val clips: Int)

/** Stores each project as `files/projects/<id>/project.json` plus its samples as WAV files. */
class ProjectRepository(context: Context) {
    private val root = File(context.filesDir, "projects").apply { mkdirs() }
    /** SoundFonts are stored once for all sets. */
    val soundFontDir = File(context.filesDir, "soundfonts").apply { mkdirs() }

    fun dir(id: String) = File(root, id)
    fun samplesDir(id: String) = File(dir(id), "samples").apply { mkdirs() }

    fun list(): List<ProjectSummary> = root.listFiles().orEmpty().mapNotNull { d ->
        val f = File(d, "project.json")
        if (!f.exists()) return@mapNotNull null
        runCatching {
            val p = ProjectJson.decode(f.readText())
            ProjectSummary(p.id, p.name, p.modifiedAt, p.tempo, p.tracks.size, p.tracks.sumOf { it.clips.size })
        }.getOrNull()
    }.sortedByDescending { it.modifiedAt }

    fun load(id: String): Project? = runCatching { ProjectJson.decode(File(dir(id), "project.json").readText()) }.getOrNull()

    @Synchronized
    fun save(project: Project) {
        val d = dir(project.id).apply { mkdirs() }
        val tmp = File(d, "project.json.tmp")
        tmp.writeText(ProjectJson.encode(project))
        tmp.renameTo(File(d, "project.json"))
    }

    fun delete(id: String) { dir(id).deleteRecursively() }

    fun duplicate(id: String, newName: String): Project? {
        val p = load(id) ?: return null
        val copy = p.copy(id = newId(), name = newName, createdAt = System.currentTimeMillis(), modifiedAt = System.currentTimeMillis())
        dir(id).resolve("samples").copyRecursively(samplesDir(copy.id), overwrite = true)
        save(copy)
        return copy
    }

    fun sampleFile(projectId: String, fileName: String) = File(samplesDir(projectId), fileName)

    /** Writes mono audio as a new sample of [projectId] and returns its reference. */
    fun addSample(projectId: String, name: String, data: FloatArray, sampleRate: Int): SampleRef {
        val id = newId()
        val fileName = "$id.wav"
        sampleFile(projectId, fileName).outputStream().use { Wav.write(it, sampleRate, data, null, 24) }
        return SampleRef(id = id, name = name, fileName = fileName, sampleRate = sampleRate, frames = data.size)
    }

    /** Loads every sample of [project] into [bank] (replacing what was there). */
    fun loadSamples(project: Project, bank: SampleBank) {
        bank.clear()
        for (ref in project.samples) {
            val f = sampleFile(project.id, ref.fileName)
            if (!f.exists()) continue
            runCatching { f.inputStream().use { Wav.readMono(it) } }.getOrNull()?.let { bank.put(ref.id, SampleData(it.mono, it.sampleRate)) }
        }
    }

    // ------------------------------------------------------------------ SoundFonts

    fun soundFontFile(ref: SoundFontRef) = File(soundFontDir, ref.fileName)

    /** All .sf2 files in the library, with their display names. */
    fun soundFontLibrary(): List<SoundFontRef> {
        val index = File(soundFontDir, "index.json")
        val known: List<SoundFontRef> = runCatching { ProjectJson.json.decodeFromString(ListSerializer(SoundFontRef.serializer()), index.readText()) }.getOrDefault(emptyList())
        return known.filter { File(soundFontDir, it.fileName).exists() }
    }

    /** Copies an .sf2 into the library (deduplicated by name and size). */
    fun addSoundFont(name: String, input: java.io.InputStream): SoundFontRef {
        val tmp = File(soundFontDir, "import.tmp")
        tmp.outputStream().use { input.copyTo(it) }
        val existing = soundFontLibrary().firstOrNull { it.name == name && it.bytes == tmp.length() }
        if (existing != null) { tmp.delete(); return existing }
        val id = newId()
        val ref = SoundFontRef(id, name, "$id.sf2", tmp.length())
        tmp.renameTo(File(soundFontDir, ref.fileName))
        val all = soundFontLibrary() + ref
        File(soundFontDir, "index.json").writeText(ProjectJson.json.encodeToString(ListSerializer(SoundFontRef.serializer()), all))
        return ref
    }

    /** Parses and registers every SoundFont the project uses that isn't loaded yet. */
    fun loadSoundFonts(project: Project, bank: SoundFontBank) {
        for (ref in project.soundFonts) {
            if (bank.contains(ref.id)) continue
            val f = soundFontFile(ref)
            if (!f.exists()) continue
            runCatching { SoundFont.parse(f.readBytes(), ref.name) }.getOrNull()?.let { bank.put(ref.id, it) }
        }
    }
}

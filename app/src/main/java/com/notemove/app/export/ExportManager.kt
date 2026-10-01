package com.notemove.app.export

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.notemove.app.data.ProjectRepository
import com.notemove.core.dsp.SampleBank
import com.notemove.core.export.LiveSetExporter
import com.notemove.core.export.LiveSetImporter
import com.notemove.core.export.ProjectJson
import com.notemove.core.export.ProjectPackager
import com.notemove.core.model.Project
import com.notemove.core.model.newId
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Builds export files into the cache and hands them to the share sheet or a user-chosen location. */
class ExportManager(private val context: Context, private val repo: ProjectRepository, private val sampleRate: Int, private val samples: SampleBank) {
    private val dir get() = File(context.cacheDir, "exports").apply { mkdirs() }

    /** Full Live project folder (.als + rendered loops + samples + MIDI), zipped. */
    fun buildProjectZip(project: Project, options: ProjectPackager.Options, onProgress: (String, Float) -> Unit): File {
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "${ProjectPackager.safeName(project.name)} Project.zip")
        f.outputStream().buffered().use { out ->
            ProjectPackager(sampleRate, samples).write(project, { repo.sampleFile(project.id, it) }, out, options, onProgress)
        }
        return f
    }

    /** Just the Live Set. */
    fun buildAls(project: Project, arrangement: Boolean): File {
        dir.listFiles()?.forEach { it.delete() }
        val f = File(dir, "${ProjectPackager.safeName(project.name)}.als")
        f.outputStream().buffered().use { LiveSetExporter().export(project, it, LiveSetExporter.Options(arrangement = arrangement)) }
        return f
    }

    fun shareIntent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        return Intent(Intent.ACTION_SEND).apply {
            type = mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.nameWithoutExtension)
            clipData = ClipData.newRawUri(file.name, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }.let { Intent.createChooser(it, "Send ${file.name}") }
    }

    fun copyTo(file: File, target: Uri) {
        context.contentResolver.openOutputStream(target)?.use { out -> file.inputStream().use { it.copyTo(out) } }
            ?: error("Could not write to the chosen location")
    }

    /**
     * Imports a Live Set (.als), a NoteMove project (.notemove) or a zipped project exported by
     * NoteMove. Returns the stored project.
     */
    fun import(uri: Uri, displayName: String): Project {
        val name = displayName.substringBeforeLast('.').ifBlank { "Imported" }
        val bytes = context.contentResolver.openInputStream(uri)?.use(InputStream::readBytes) ?: error("Could not open file")
        val project = when {
            bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte() -> importZip(bytes, name)
            bytes.isNotEmpty() && bytes[0] == '{'.code.toByte() -> ProjectJson.decode(String(bytes)).copy(id = newId())
            else -> LiveSetImporter.import(bytes.inputStream(), name)
        }
        val stamped = project.copy(modifiedAt = System.currentTimeMillis())
        repo.save(stamped)
        return stamped
    }

    private fun importZip(bytes: ByteArray, name: String): Project {
        var json: String? = null
        var als: ByteArray? = null
        val wavs = HashMap<String, ByteArray>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val n = e.name
                when {
                    n.endsWith(".notemove") -> json = String(zip.readBytes())
                    n.endsWith(".als") && als == null -> als = zip.readBytes()
                    n.contains("Samples/Imported/") && n.endsWith(".wav") -> wavs[n.substringAfterLast('/').removeSuffix(".wav")] = zip.readBytes()
                }
            }
        }
        json?.let { j ->
            val p = ProjectJson.decode(j).copy(id = newId())
            // Restore samples (exported under their display names).
            for (ref in p.samples) {
                val data = wavs[ProjectPackager.safeName(ref.name)] ?: continue
                repo.sampleFile(p.id, ref.fileName).writeBytes(data)
            }
            return p
        }
        als?.let { return LiveSetImporter.import(it.inputStream(), name) }
        error("The zip contains no Live Set or NoteMove project")
    }
}

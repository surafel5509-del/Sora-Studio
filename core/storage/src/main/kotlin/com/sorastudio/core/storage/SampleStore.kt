package com.sorastudio.core.storage

import android.content.Context
import android.net.Uri
import com.sorastudio.core.dsp.WavCodec
import java.io.File
import java.io.IOException
import java.io.InputStream

/** A sound in the local library (bundled-generated or user-imported file). */
data class SoundItem(
    val id: String,
    val name: String,
    val category: String,
    /** File path for imported samples; null for generated/synth sounds. */
    val filePath: String?,
    val durationMs: Long = 0,
    val sizeBytes: Long = 0,
    /** "drum" | "preset" | "sample" | "effect" */
    val kind: String = "sample"
)

/**
 * User sample library stored under files/samples/<category>/.
 * Imports copy content-URI streams (Storage Access Framework) into app storage
 * so projects never depend on revocable URI permissions.
 */
class SampleStore(private val context: Context) {
    private val root = File(context.filesDir, "samples").apply { mkdirs() }

    fun categoryDir(category: String): File =
        File(root, category.replace(Regex("[^A-Za-z0-9_-]"), "_")).apply { mkdirs() }

    @Throws(IOException::class)
    fun importSample(input: InputStream, displayName: String, category: String = "Imported"): SoundItem {
        val dir = categoryDir(category)
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "sample.wav" }
        var target = File(dir, safeName)
        var counter = 1
        while (target.exists()) {
            val base = safeName.substringBeforeLast('.')
            val ext = safeName.substringAfterLast('.', "")
            target = File(dir, if (ext.isEmpty()) "${base}_$counter" else "${base}_$counter.$ext")
            counter++
        }
        input.use { stream ->
            target.outputStream().use { out -> stream.copyTo(out) }
        }
        return describe(target, category)
    }

    @Throws(IOException::class)
    fun importFromUri(uri: Uri, displayName: String, category: String = "Imported"): SoundItem {
        val stream = context.contentResolver.openInputStream(uri)
            ?: throw IOException("Could not open selected file")
        return importSample(stream, displayName, category)
    }

    fun listSamples(): List<SoundItem> {
        val result = mutableListOf<SoundItem>()
        val categories = root.listFiles { f -> f.isDirectory } ?: return result
        for (dir in categories.sortedBy { it.name }) {
            val files = dir.listFiles { f -> f.isFile && isAudioFile(f) } ?: continue
            for (file in files.sortedBy { it.name }) {
                result.add(describe(file, dir.name))
            }
        }
        return result
    }

    fun delete(item: SoundItem): Boolean {
        val path = item.filePath ?: return false
        return File(path).delete()
    }

    /** Copy a library sample into a project's audio folder; returns project-relative path. */
    @Throws(IOException::class)
    fun copyIntoProject(storage: ProjectStorage, projectId: String, source: File): String {
        val audioDir = storage.audioDir(projectId)
        var target = File(audioDir, source.name)
        var counter = 1
        while (target.exists()) {
            target = File(audioDir, "${source.nameWithoutExtension}_$counter.${source.extension}")
            counter++
        }
        source.copyTo(target)
        return "audio/${target.name}"
    }

    private fun describe(file: File, category: String): SoundItem {
        var durationMs = 0L
        if (file.extension.equals("wav", ignoreCase = true)) {
            durationMs = runCatching {
                val decoded = WavCodec.read(file)
                (decoded.durationSeconds * 1000).toLong()
            }.getOrDefault(0L)
        }
        return SoundItem(
            id = "file:${file.absolutePath}",
            name = file.nameWithoutExtension.replace('_', ' '),
            category = category,
            filePath = file.absolutePath,
            durationMs = durationMs,
            sizeBytes = file.length(),
            kind = "sample"
        )
    }

    private fun isAudioFile(file: File): Boolean =
        file.extension.lowercase() in setOf("wav", "mp3", "m4a", "aac", "ogg", "flac", "aif", "aiff")
}

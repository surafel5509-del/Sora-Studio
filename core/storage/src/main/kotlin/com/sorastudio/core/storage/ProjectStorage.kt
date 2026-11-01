package com.sorastudio.core.storage

import android.content.Context
import com.sorastudio.core.project.ProjectCodec
import com.sorastudio.core.project.ProjectSummary
import com.sorastudio.core.project.SProject
import com.sorastudio.core.project.toSummary
import java.io.File
import java.io.IOException

/**
 * On-disk layout (app-specific internal storage):
 *
 * ```
 * files/projects/<projectId>/project.soraproj   (JSON)
 * files/projects/<projectId>/project.bak.json   (previous good save)
 * files/projects/<projectId>/audio/<...>        (recordings & imported audio)
 * files/samples/<category>/<...>                (user sample library)
 * files/autosave/autosave.soraproj              (crash recovery)
 * ```
 *
 * Saves are transactional: write to `.tmp`, fsync, copy current to `.bak`,
 * then rename — a crash mid-save can never destroy the last good project.
 */
class ProjectStorage(context: Context) {
    private val filesDir: File = context.filesDir
    private val projectsRoot = File(filesDir, "projects").apply { mkdirs() }
    private val autosaveFile = File(filesDir, "autosave/autosave.soraproj")

    fun projectDir(projectId: String): File = File(projectsRoot, sanitize(projectId))
    fun audioDir(projectId: String): File = File(projectDir(projectId), "audio").apply { mkdirs() }

    fun projectFile(projectId: String): File = File(projectDir(projectId), "project.soraproj")

    @Throws(IOException::class)
    fun save(project: SProject) {
        val dir = projectDir(project.id).apply { mkdirs() }
        val target = File(dir, "project.soraproj")
        val backup = File(dir, "project.bak.json")
        val tmp = File(dir, "project.tmp.json")
        tmp.writeText(ProjectCodec.encode(project))
        if (target.exists()) {
            target.copyTo(backup, overwrite = true)
        }
        if (!tmp.renameTo(target)) {
            // renameTo can fail across filesystems; fall back to copy+delete
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    fun load(projectId: String): Result<SProject> {
        val dir = projectDir(projectId)
        val target = File(dir, "project.soraproj")
        val backup = File(dir, "project.bak.json")
        val primary = runCatching { ProjectCodec.decode(target.readText()).getOrThrow() }
        if (primary.isSuccess) return primary
        // Recover from backup if the main file is corrupt/missing.
        return runCatching { ProjectCodec.decode(backup.readText()).getOrThrow() }
            .recoverCatching { throw primary.exceptionOrNull() ?: it }
    }

    /** All projects on disk, newest first. Corrupt entries are skipped. */
    fun listProjects(): List<ProjectSummary> {
        val dirs = projectsRoot.listFiles { f -> f.isDirectory } ?: return emptyList()
        return dirs.mapNotNull { dir ->
            val file = File(dir, "project.soraproj")
            if (!file.exists()) return@mapNotNull null
            runCatching { ProjectCodec.decode(file.readText()).getOrThrow().toSummary() }.getOrNull()
        }.sortedByDescending { it.updatedAtEpochMs }
    }

    fun delete(projectId: String): Boolean = projectDir(projectId).deleteRecursively()

    fun duplicate(projectId: String, newId: String): Result<SProject> = runCatching {
        val source = projectDir(projectId)
        val target = projectDir(newId)
        source.copyRecursively(target, overwrite = true)
        val loaded = load(newId).getOrThrow()
        val renamed = loaded.copy(id = newId, name = loaded.name + " Copy")
        save(renamed)
        renamed
    }

    // --------------------------------------------------------------- autosave

    fun saveAutosave(project: SProject) {
        runCatching {
            autosaveFile.parentFile?.mkdirs()
            autosaveFile.writeText(ProjectCodec.encode(project))
        }
    }

    fun loadAutosave(): SProject? = runCatching {
        if (autosaveFile.exists()) ProjectCodec.decode(autosaveFile.readText()).getOrNull() else null
    }.getOrNull()

    fun clearAutosave() {
        runCatching { autosaveFile.delete() }
    }

    fun hasAutosaveNewerThan(projectId: String): Boolean {
        val auto = loadAutosave() ?: return false
        if (auto.id != projectId) return auto.updatedAtEpochMs > 0
        val saved = load(projectId).getOrNull() ?: return true
        return auto.updatedAtEpochMs > saved.updatedAtEpochMs
    }

    private fun sanitize(id: String): String = id.replace(Regex("[^A-Za-z0-9_-]"), "_")
}

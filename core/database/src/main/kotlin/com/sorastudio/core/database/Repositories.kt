package com.sorastudio.core.database

import com.sorastudio.core.common.newId
import com.sorastudio.core.project.ProjectCodec
import com.sorastudio.core.project.ProjectSummary
import com.sorastudio.core.project.SDrumPattern
import com.sorastudio.core.project.SEffect
import com.sorastudio.core.project.SProject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Keeps the Room project index in sync with project files. */
class ProjectIndexRepository(private val dao: ProjectDao) {
    fun observeProjects(): Flow<List<ProjectSummary>> = dao.observeAll().map { list ->
        list.map { it.toSummary() }
    }

    fun observeFavorites(): Flow<List<ProjectSummary>> = dao.observeFavorites().map { list ->
        list.map { it.toSummary() }
    }

    suspend fun index(project: SProject) {
        dao.upsert(
            ProjectEntity(
                id = project.id,
                name = project.name,
                bpm = project.bpm,
                createdAt = project.createdAtEpochMs,
                updatedAt = project.updatedAtEpochMs,
                trackCount = project.tracks.size,
                favorite = project.favorite
            )
        )
    }

    suspend fun remove(projectId: String) = dao.delete(projectId)

    private fun ProjectEntity.toSummary(): ProjectSummary = ProjectSummary(
        id = id, name = name, bpm = bpm, updatedAtEpochMs = updatedAt,
        createdAtEpochMs = createdAt, trackCount = trackCount, favorite = favorite,
        lengthBeats = 16.0
    )
}

class PresetRepository(private val dao: PresetDao) {
    private val json = Json { ignoreUnknownKeys = true }

    fun observeEffects(): Flow<List<PresetEntity>> = dao.observeByKind(KIND_EFFECT)
    fun observeVocalChains(): Flow<List<PresetEntity>> = dao.observeByKind(KIND_VOCAL)

    suspend fun saveEffectPreset(name: String, effect: SEffect) {
        dao.upsert(
            PresetEntity(
                id = newId("pre"), name = name, kind = KIND_EFFECT,
                effectType = effect.type,
                payloadJson = json.encodeToString(listOf(effect)),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun saveChainPreset(name: String, kind: String, chain: List<SEffect>) {
        dao.upsert(
            PresetEntity(
                id = newId("pre"), name = name, kind = kind, effectType = null,
                payloadJson = json.encodeToString(chain),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun decodeChain(entity: PresetEntity): List<SEffect> =
        runCatching { json.decodeFromString<List<SEffect>>(entity.payloadJson) }.getOrDefault(emptyList())

    suspend fun delete(entity: PresetEntity) = dao.delete(entity)

    companion object {
        const val KIND_EFFECT = "EFFECT"
        const val KIND_VOCAL = "VOCAL_CHAIN"
        const val KIND_SYNTH = "SYNTH"
    }
}

class PatternLibraryRepository(private val dao: PatternDao) {
    fun observePatterns(): Flow<List<SDrumPattern>> = dao.observeAll().map { list ->
        list.mapNotNull { runCatching { ProjectCodec.json.decodeFromString<SDrumPattern>(it.payloadJson) }.getOrNull() }
    }

    fun observeEntities(): Flow<List<PatternEntity>> = dao.observeAll()

    suspend fun save(pattern: SDrumPattern) {
        dao.upsert(
            PatternEntity(
                id = pattern.id, name = pattern.name, kitId = pattern.kitId,
                payloadJson = ProjectCodec.json.encodeToString(pattern),
                createdAt = System.currentTimeMillis()
            )
        )
    }

    fun decode(entity: PatternEntity): SDrumPattern? =
        runCatching { ProjectCodec.json.decodeFromString<SDrumPattern>(entity.payloadJson) }.getOrNull()

    suspend fun delete(entity: PatternEntity) = dao.delete(entity)
}

class RecordingRepository(private val dao: RecordingDao) {
    fun observeForProject(projectId: String): Flow<List<RecordingEntity>> = dao.observeForProject(projectId)
    fun observeAll(): Flow<List<RecordingEntity>> = dao.observeAll()

    suspend fun add(entity: RecordingEntity) = dao.upsert(entity)
    suspend fun delete(entity: RecordingEntity) = dao.delete(entity)
    suspend fun deleteForProject(projectId: String) = dao.deleteForProject(projectId)
}

class SoundLibraryRepository(private val dao: SoundLibraryDao) {
    fun observeFavorites(): Flow<List<FavoriteSoundEntity>> = dao.observeFavorites()
    fun observeRecents(): Flow<List<RecentSoundEntity>> = dao.observeRecents()

    suspend fun toggleFavorite(soundId: String, name: String, kind: String): Boolean {
        return if (dao.isFavorite(soundId)) {
            dao.removeFavorite(soundId); false
        } else {
            dao.addFavorite(FavoriteSoundEntity(soundId, name, kind, System.currentTimeMillis())); true
        }
    }

    suspend fun touchRecent(soundId: String, name: String, kind: String) {
        dao.touchRecent(RecentSoundEntity(soundId, name, kind, System.currentTimeMillis()))
    }
}

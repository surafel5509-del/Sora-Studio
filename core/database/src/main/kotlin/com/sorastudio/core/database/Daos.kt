package com.sorastudio.core.database

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ProjectDao {
    @Query("SELECT * FROM projects ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE favorite = 1 ORDER BY updatedAt DESC")
    fun observeFavorites(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM projects WHERE id = :id")
    suspend fun byId(id: String): ProjectEntity?

    @Upsert
    suspend fun upsert(entity: ProjectEntity)

    @Query("DELETE FROM projects WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface PresetDao {
    @Query("SELECT * FROM presets ORDER BY name ASC")
    fun observeAll(): Flow<List<PresetEntity>>

    @Query("SELECT * FROM presets WHERE kind = :kind ORDER BY name ASC")
    fun observeByKind(kind: String): Flow<List<PresetEntity>>

    @Upsert
    suspend fun upsert(entity: PresetEntity)

    @Delete
    suspend fun delete(entity: PresetEntity)
}

@Dao
interface PatternDao {
    @Query("SELECT * FROM drum_patterns ORDER BY name ASC")
    fun observeAll(): Flow<List<PatternEntity>>

    @Upsert
    suspend fun upsert(entity: PatternEntity)

    @Delete
    suspend fun delete(entity: PatternEntity)
}

@Dao
interface RecordingDao {
    @Query("SELECT * FROM recordings WHERE projectId = :projectId ORDER BY createdAt DESC")
    fun observeForProject(projectId: String): Flow<List<RecordingEntity>>

    @Query("SELECT * FROM recordings ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RecordingEntity>>

    @Upsert
    suspend fun upsert(entity: RecordingEntity)

    @Delete
    suspend fun delete(entity: RecordingEntity)

    @Query("DELETE FROM recordings WHERE projectId = :projectId")
    suspend fun deleteForProject(projectId: String)
}

@Dao
interface SoundLibraryDao {
    @Query("SELECT * FROM favorite_sounds ORDER BY addedAt DESC")
    fun observeFavorites(): Flow<List<FavoriteSoundEntity>>

    @Upsert
    suspend fun addFavorite(entity: FavoriteSoundEntity)

    @Query("DELETE FROM favorite_sounds WHERE soundId = :soundId")
    suspend fun removeFavorite(soundId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_sounds WHERE soundId = :soundId)")
    suspend fun isFavorite(soundId: String): Boolean

    @Query("SELECT * FROM recent_sounds ORDER BY lastUsedAt DESC LIMIT 24")
    fun observeRecents(): Flow<List<RecentSoundEntity>>

    @Upsert
    suspend fun touchRecent(entity: RecentSoundEntity)
}

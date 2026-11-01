package com.sorastudio.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/** Searchable index of on-disk projects (full data lives in .soraproj files). */
@Entity(tableName = "projects")
data class ProjectEntity(
    @PrimaryKey val id: String,
    val name: String,
    val bpm: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val trackCount: Int,
    val favorite: Boolean
)

/** User-saved presets. [kind]: EFFECT, VOCAL_CHAIN, SYNTH. [payloadJson] = SEffect list or param map. */
@Entity(tableName = "presets")
data class PresetEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kind: String,
    val effectType: String?,
    val payloadJson: String,
    val createdAt: Long
)

/** User drum patterns (library, independent of any project). */
@Entity(tableName = "drum_patterns")
data class PatternEntity(
    @PrimaryKey val id: String,
    val name: String,
    val kitId: String,
    val payloadJson: String,
    val createdAt: Long
)

/** Recording history (takes), across all projects. */
@Entity(tableName = "recordings")
data class RecordingEntity(
    @PrimaryKey val id: String,
    val projectId: String,
    val name: String,
    val relativePath: String,
    val durationMs: Long,
    val sampleRate: Int,
    val createdAt: Long
)

/** Favourite sounds from the library. */
@Entity(tableName = "favorite_sounds")
data class FavoriteSoundEntity(
    @PrimaryKey val soundId: String,
    val name: String,
    val kind: String,
    val addedAt: Long
)

/** Recently previewed/used sounds. */
@Entity(tableName = "recent_sounds")
data class RecentSoundEntity(
    @PrimaryKey val soundId: String,
    val name: String,
    val kind: String,
    val lastUsedAt: Long
)

package com.sorastudio.core.project

/** Lightweight project metadata for lists (no full deserialization of clips needed by UI). */
data class ProjectSummary(
    val id: String,
    val name: String,
    val bpm: Double,
    val updatedAtEpochMs: Long,
    val createdAtEpochMs: Long,
    val trackCount: Int,
    val favorite: Boolean,
    val lengthBeats: Double
)

fun SProject.toSummary(): ProjectSummary = ProjectSummary(
    id = id,
    name = name,
    bpm = bpm,
    updatedAtEpochMs = updatedAtEpochMs,
    createdAtEpochMs = createdAtEpochMs,
    trackCount = tracks.size,
    favorite = favorite,
    lengthBeats = computedLengthBeats()
)

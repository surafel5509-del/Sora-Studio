package com.sorastudio.core.project

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Versioned JSON project format (.soraproj). Human-diffable, forward tolerant:
 * unknown keys are ignored, missing fields fall back to model defaults.
 */
object ProjectCodec {

    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(project: SProject): String = json.encodeToString(project)

    fun decode(text: String): Result<SProject> = runCatching {
        val project = json.decodeFromString<SProject>(text)
        migrate(project)
    }

    private fun migrate(project: SProject): SProject {
        // Future format migrations go here (v1 is current).
        return if (project.formatVersion == PROJECT_FORMAT_VERSION) project
        else project.copy(formatVersion = PROJECT_FORMAT_VERSION)
    }
}

package com.sorastudio.core.common

import java.util.UUID
import kotlin.random.Random

/** Short, collision-resistant ids for tracks/clips/notes. */
fun newId(prefix: String = ""): String {
    val raw = UUID.randomUUID().toString().replace("-", "").take(12)
    return if (prefix.isEmpty()) raw else "$prefix-$raw"
}

/** Deterministic-ish random that can be seeded in tests. */
fun seededRandom(seed: Long): Random = Random(seed)

const val SORA_SAMPLE_RATE_DEFAULT: Int = 44100
const val SORA_BPM_DEFAULT: Double = 120.0

package com.sorastudio.core.project

import com.sorastudio.core.common.newId
import kotlinx.serialization.Serializable

/** Current on-disk project format version. Bump + migrate in [ProjectCodec]. */
const val PROJECT_FORMAT_VERSION: Int = 1

enum class TrackType { AUDIO, VOCAL, INSTRUMENT, DRUM, SAMPLE }

enum class ClipType { AUDIO, MIDI, PATTERN }

@Serializable
data class SEffect(
    val id: String = newId("fx"),
    /** [com.sorastudio.core.dsp.EffectType] name */
    val type: String,
    val enabled: Boolean = true,
    val wet: Float = 1f,
    val params: Map<String, Float> = emptyMap()
)

@Serializable
data class SNote(
    val id: String = newId("note"),
    val startBeats: Double,
    val lengthBeats: Double,
    val pitch: Int,
    val velocity: Int = 100
) {
    val endBeats: Double get() = startBeats + lengthBeats
}

@Serializable
data class SClip(
    val id: String = newId("clip"),
    val trackId: String,
    val name: String = "Clip",
    val type: ClipType,
    val startBeats: Double,
    val lengthBeats: Double,
    /** Offset into the clip's own content (for trimming from the left). */
    val contentOffsetBeats: Double = 0.0,
    /** App-relative audio file path for AUDIO clips (inside the project folder). */
    val audioPath: String? = null,
    /** Content length of the underlying audio file in beats at recording time (for waveform/trim limits). */
    val audioLengthBeats: Double = 0.0,
    val notes: List<SNote> = emptyList(),
    val patternId: String? = null,
    val gainDb: Float = 0f,
    val fadeInBeats: Double = 0.0,
    val fadeOutBeats: Double = 0.0,
    val pitchSemitones: Float = 0f,
    val muted: Boolean = false
) {
    val endBeats: Double get() = startBeats + lengthBeats
}

@Serializable
data class SDrumStep(
    val active: Boolean = false,
    val velocity: Float = 0.9f,
    /** 0..1 chance the step actually fires on each pass. */
    val probability: Float = 1f,
    /** Playback pitch offset for this step (semitones). */
    val pitchSemitones: Float = 0f
)

@Serializable
data class SDrumLane(
    /** [com.sorastudio.core.dsp.DrumType] name, or CUSTOM with [customSamplePath]. */
    val drum: String,
    val customSamplePath: String? = null,
    val volumeDb: Float = 0f,
    val pan: Float = 0f,
    val steps: List<SDrumStep> = emptyList()
)

@Serializable
data class SDrumPattern(
    val id: String = newId("pat"),
    val name: String = "Pattern",
    val stepsPerBar: Int = 16,
    val bars: Int = 1,
    /** 0..0.6 — delays off-16ths. */
    val swing: Float = 0f,
    val kitId: String = "kit-studio",
    val lanes: List<SDrumLane> = emptyList()
) {
    val totalSteps: Int get() = stepsPerBar * bars
    val lengthBeats: Double get() = totalSteps / (stepsPerBar / 4.0)

    companion object {
        fun emptyPattern(name: String = "Pattern", steps: Int = 16, kitId: String = "kit-studio"): SDrumPattern {
            val drums = listOf("KICK", "SNARE", "CLAP", "RIM", "HAT_CLOSED", "HAT_OPEN", "TOM", "PERC", "BASS_808")
            return SDrumPattern(
                name = name, stepsPerBar = steps, bars = 1, kitId = kitId,
                lanes = drums.map { d -> SDrumLane(drum = d, steps = List(steps) { SDrumStep() }) }
            )
        }
    }
}

@Serializable
data class STrack(
    val id: String = newId("trk"),
    val name: String,
    val type: TrackType,
    val colorArgb: Long = 0xFFC5A7FF,
    val volumeDb: Float = -3f,
    val pan: Float = 0f,
    val mute: Boolean = false,
    val solo: Boolean = false,
    val locked: Boolean = false,
    val groupId: String? = null,
    /** Instrument preset id for INSTRUMENT tracks. */
    val presetId: String = "piano-grand",
    /** Synth parameter overrides (cutoff/resonance/attack/... keys from the Instruments screen). */
    val synthParams: Map<String, Float> = emptyMap(),
    val drumKitId: String = "kit-studio",
    /** Sample file (app-relative) for SAMPLE tracks. */
    val samplePath: String? = null,
    val sampleRootNote: Int = 60,
    val mono: Boolean = false,
    val inputGainDb: Float = 0f,
    val fxChain: List<SEffect> = emptyList(),
    /** Sends to the two global send effects (0..1). */
    val sendReverb: Float = 0f,
    val sendDelay: Float = 0f,
    /** Vocal processing settings for VOCAL tracks (applied through fxChain too; see Vocal screen). */
    val autoTuneRoot: Int = 0,
    val autoTuneScale: String = "MAJOR",
    val autoTuneStrength: Float = 0.7f,
    val autoTuneSpeed: Float = 0.35f
)

@Serializable
data class SAutomationPoint(val beat: Double, val value: Float)

enum class AutomationCurve { LINEAR, SMOOTH, STEP }

@Serializable
data class SAutomationLane(
    val id: String = newId("auto"),
    val trackId: String,
    /**
     * Target path: "volume" | "pan" | "sendReverb" | "sendDelay" |
     * "effect:<effectId>:<paramKey>" | "synth:<paramKey>".
     */
    val target: String,
    val displayName: String = target,
    val minValue: Float = 0f,
    val maxValue: Float = 1f,
    val curve: AutomationCurve = AutomationCurve.SMOOTH,
    val points: List<SAutomationPoint> = emptyList()
) {
    /** Interpolated value at a beat position; null before the first point (leave param untouched). */
    fun valueAt(beat: Double): Float? {
        if (points.isEmpty()) return null
        val sorted = points.sortedBy { it.beat }
        if (beat <= sorted.first().beat) return sorted.first().value
        if (beat >= sorted.last().beat) return sorted.last().value
        for (i in 0 until sorted.size - 1) {
            val a = sorted[i]; val b = sorted[i + 1]
            if (beat in a.beat..b.beat) {
                val span = (b.beat - a.beat).coerceAtLeast(0.000001)
                val t = ((beat - a.beat) / span).toFloat()
                return when (curve) {
                    AutomationCurve.STEP -> a.value
                    AutomationCurve.LINEAR -> a.value + (b.value - a.value) * t
                    AutomationCurve.SMOOTH -> {
                        val smooth = t * t * (3f - 2f * t)
                        a.value + (b.value - a.value) * smooth
                    }
                }
            }
        }
        return sorted.last().value
    }
}

@Serializable
data class SMarker(val id: String = newId("mrk"), val beat: Double, val name: String)

@Serializable
data class SMaster(
    val volumeDb: Float = 0f,
    val fxChain: List<SEffect> = emptyList(),
    val limiterEnabled: Boolean = true
)

@Serializable
data class SProject(
    val formatVersion: Int = PROJECT_FORMAT_VERSION,
    val id: String = newId("prj"),
    val name: String = "Untitled",
    val bpm: Double = 120.0,
    val beatsPerBar: Int = 4,
    val keyRoot: Int = 0, // 0 = C
    val keyScale: String = "MAJOR",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis(),
    val tracks: List<STrack> = emptyList(),
    val clips: List<SClip> = emptyList(),
    val patterns: List<SDrumPattern> = emptyList(),
    val automation: List<SAutomationLane> = emptyList(),
    val markers: List<SMarker> = emptyList(),
    val master: SMaster = SMaster(),
    val loopEnabled: Boolean = false,
    val loopStartBeats: Double = 0.0,
    val loopEndBeats: Double = 16.0,
    val metronomeEnabled: Boolean = false,
    val favorite: Boolean = false,
    val templateName: String? = null
) {
    /** Timeline length: last clip end, at least 4 bars, rounded up to whole bars. */
    fun computedLengthBeats(): Double {
        val lastEnd = clips.maxOfOrNull { it.endBeats } ?: 0.0
        val minBeats = beatsPerBar * 4.0
        val raw = maxOf(lastEnd, minBeats)
        val bars = kotlin.math.ceil(raw / beatsPerBar).toInt().coerceAtLeast(4)
        return bars * beatsPerBar.toDouble()
    }

    fun trackById(id: String?): STrack? = tracks.firstOrNull { it.id == id }
    fun clipById(id: String?): SClip? = clips.firstOrNull { it.id == id }
    fun patternById(id: String?): SDrumPattern? = patterns.firstOrNull { it.id == id }
    fun clipsForTrack(trackId: String): List<SClip> = clips.filter { it.trackId == trackId }
}

/** Professional, muted track colour palette matching the SORA design system. */
object TrackColors {
    val PALETTE: List<Long> = listOf(
        0xFFC5A7FF, // lavender (brand accent)
        0xFF8ECAE6, // sky
        0xFF72C99A, // mint
        0xFFE9B96E, // amber
        0xFFF2A65A, // orange
        0xFFEF8FB5, // rose
        0xFF9B9BDE, // periwinkle
        0xFF7FD1C0, // teal
        0xFFD9C5B0, // sand
        0xFFB8C4D9, // steel (secondary accent)
    )
    fun forIndex(index: Int): Long = PALETTE[((index % PALETTE.size) + PALETTE.size) % PALETTE.size]
}

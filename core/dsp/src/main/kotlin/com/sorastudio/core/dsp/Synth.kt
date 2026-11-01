package com.sorastudio.core.dsp

import com.sorastudio.core.common.NoteUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class Waveform { SINE, TRIANGLE, SQUARE, SAWTOOTH, NOISE }

enum class InstrumentType(val displayName: String, val category: String) {
    PIANO("Grand Piano", "Keys"),
    ELECTRIC_PIANO("Electric Piano", "Keys"),
    ORGAN("Organ", "Keys"),
    SYNTH_BASS("Synth Bass", "Bass"),
    BASS_808("808 Bass", "Bass"),
    LEAD("Lead Synth", "Synth"),
    PAD("Pad Synth", "Synth"),
    PLUCK("Pluck", "Synth"),
    BELL("Bell", "Synth"),
    STRINGS("Strings", "Orchestral"),
    GUITAR("Guitar", "Guitar"),
    SAMPLER("Sampler", "Sampler"),
    DRUMS("Drum Kit", "Drums"),
}

/** Fast deterministic noise (xorshift32) for synthesis and drum generation. */
class NoiseGenerator(seed: Int = 22222) {
    private var state: Int = if (seed == 0) 22222 else seed
    fun next(): Float {
        var x = state
        x = x xor (x shl 13)
        x = x xor (x ushr 17)
        x = x xor (x shl 5)
        state = x
        return (x / 2147483648.0).toFloat()
    }
}

/** Band-limited-ish oscillator (PolyBLEP for saw/square). */
class Oscillator {
    var waveform: Waveform = Waveform.SAWTOOTH
    private var phase = 0.0 // 0..1
    private val noise = NoiseGenerator()

    fun reset(phase: Double = 0.0) { this.phase = phase }

    fun process(freqHz: Double, sampleRate: Int): Float {
        val dt = freqHz / sampleRate
        val t = phase
        val value = when (waveform) {
            Waveform.SINE -> sin(2.0 * PI * t).toFloat()
            Waveform.TRIANGLE -> (2.0 * abs(2.0 * (t - kotlin.math.floor(t + 0.5))) - 1.0).toFloat() * -1f
            Waveform.SQUARE -> {
                var v = if (t < 0.5) 1f else -1f
                v += polyBlep(t, dt)
                v -= polyBlep((t + 0.5) % 1.0, dt)
                v * 0.7f
            }
            Waveform.SAWTOOTH -> {
                var v = (2.0 * t - 1.0).toFloat()
                v -= polyBlep(t, dt)
                v * 0.8f
            }
            Waveform.NOISE -> noise.next()
        }
        phase += dt
        if (phase >= 1.0) phase -= kotlin.math.floor(phase)
        return value
    }

    private fun polyBlep(t: Double, dt: Double): Float {
        return when {
            t < dt -> {
                val x = t / dt
                (2.0 * x - x * x - 1.0).toFloat()
            }
            t > 1.0 - dt -> {
                val x = (t - 1.0) / dt
                (x * x + 2.0 * x + 1.0).toFloat()
            }
            else -> 0f
        }
    }
}

class AdsrEnvelope {
    var attackSec = 0.005f
    var decaySec = 0.15f
    var sustainLevel = 0.7f
    var releaseSec = 0.25f

    private enum class Stage { IDLE, ATTACK, DECAY, SUSTAIN, RELEASE }
    private var stage = Stage.IDLE
    private var level = 0f

    val isActive: Boolean get() = stage != Stage.IDLE

    fun noteOn() { stage = Stage.ATTACK }
    fun noteOff() { if (stage != Stage.IDLE) stage = Stage.RELEASE }
    fun reset() { stage = Stage.IDLE; level = 0f }

    fun process(sampleRate: Int): Float {
        when (stage) {
            Stage.IDLE -> level = 0f
            Stage.ATTACK -> {
                level += 1f / max(1f, attackSec * sampleRate)
                if (level >= 1f) { level = 1f; stage = Stage.DECAY }
            }
            Stage.DECAY -> {
                val coef = exp(-1.0 / max(1.0, decaySec.toDouble() * sampleRate)).toFloat()
                level = sustainLevel + coef * (level - sustainLevel)
                if (abs(level - sustainLevel) < 0.001f) stage = Stage.SUSTAIN
            }
            Stage.SUSTAIN -> level = sustainLevel
            Stage.RELEASE -> {
                val coef = exp(-1.0 / max(1.0, releaseSec.toDouble() * sampleRate)).toFloat()
                level *= coef
                if (level < 0.0004f) { level = 0f; stage = Stage.IDLE }
            }
        }
        return level
    }
}

/** Full description of a synth sound. All built-in instruments are patches. */
data class SynthPatch(
    val osc1: Waveform = Waveform.SAWTOOTH,
    val osc2: Waveform = Waveform.SAWTOOTH,
    val osc2Mix: Float = 0.0f,
    val osc2DetuneCents: Float = 8f,
    val osc2Semitones: Float = 0f,
    val subOsc: Boolean = false,
    val cutoffHz: Float = 6000f,
    val resonance: Float = 0.7f,
    val filterEnvAmount: Float = 0f, // 0..1 sweep toward cutoff * (1+amount*8)
    val attack: Float = 0.005f,
    val decay: Float = 0.2f,
    val sustain: Float = 0.7f,
    val release: Float = 0.3f,
    val fmRatio: Float = 0f,     // 0 = FM off
    val fmIndex: Float = 0f,     // modulation depth
    val fmDecay: Float = 0.4f,
    val is808: Boolean = false,
    val glideSec: Float = 0f,    // portamento for 808/lead
    val harmonics: FloatArray? = null, // additive organ/EP: harmonic gains
    val noiseMix: Float = 0f
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is SynthPatch) return false
        return osc1 == other.osc1 && osc2 == other.osc2 && osc2Mix == other.osc2Mix &&
            osc2DetuneCents == other.osc2DetuneCents && osc2Semitones == other.osc2Semitones &&
            subOsc == other.subOsc && cutoffHz == other.cutoffHz && resonance == other.resonance &&
            filterEnvAmount == other.filterEnvAmount && attack == other.attack && decay == other.decay &&
            sustain == other.sustain && release == other.release && fmRatio == other.fmRatio &&
            fmIndex == other.fmIndex && fmDecay == other.fmDecay && is808 == other.is808 &&
            glideSec == other.glideSec && harmonics.contentEquals(other.harmonics) && noiseMix == other.noiseMix
    }

    override fun hashCode(): Int {
        var r = osc1.hashCode()
        r = 31 * r + (cutoffHz * 10).toInt()
        r = 31 * r + harmonics.contentHashCode()
        return r
    }
}

data class InstrumentPreset(val id: String, val name: String, val type: InstrumentType, val patch: SynthPatch)

object InstrumentLibrary {
    val PRESETS: List<InstrumentPreset> = listOf(
        InstrumentPreset("piano-grand", "Grand Piano", InstrumentType.PIANO, SynthPatch(
            osc1 = Waveform.TRIANGLE, harmonics = floatArrayOf(1f, 0.55f, 0.28f, 0.12f, 0.05f),
            cutoffHz = 5200f, resonance = 0.4f, attack = 0.002f, decay = 1.4f, sustain = 0.25f, release = 0.35f,
            filterEnvAmount = 0.25f)),
        InstrumentPreset("piano-soft", "Soft Felt Piano", InstrumentType.PIANO, SynthPatch(
            osc1 = Waveform.SINE, harmonics = floatArrayOf(1f, 0.35f, 0.1f, 0.03f),
            cutoffHz = 2400f, resonance = 0.3f, attack = 0.006f, decay = 1.8f, sustain = 0.2f, release = 0.5f)),
        InstrumentPreset("ep-rhodes", "Rhodes EP", InstrumentType.ELECTRIC_PIANO, SynthPatch(
            osc1 = Waveform.SINE, fmRatio = 2f, fmIndex = 1.6f, fmDecay = 0.25f,
            cutoffHz = 4200f, attack = 0.003f, decay = 1.6f, sustain = 0.3f, release = 0.4f)),
        InstrumentPreset("ep-wurli", "Wurli EP", InstrumentType.ELECTRIC_PIANO, SynthPatch(
            osc1 = Waveform.TRIANGLE, harmonics = floatArrayOf(1f, 0.7f, 0.2f, 0.35f),
            cutoffHz = 3200f, attack = 0.004f, decay = 1.1f, sustain = 0.35f, release = 0.3f)),
        InstrumentPreset("organ-drawbar", "Drawbar Organ", InstrumentType.ORGAN, SynthPatch(
            osc1 = Waveform.SINE, harmonics = floatArrayOf(1f, 0.8f, 0.6f, 0.4f, 0.25f, 0.18f, 0.12f, 0.08f),
            cutoffHz = 9000f, attack = 0.01f, decay = 0.1f, sustain = 0.9f, release = 0.12f)),
        InstrumentPreset("bass-sub", "Sub Bass", InstrumentType.SYNTH_BASS, SynthPatch(
            osc1 = Waveform.SINE, subOsc = true, cutoffHz = 500f, attack = 0.004f, decay = 0.25f, sustain = 0.85f, release = 0.18f)),
        InstrumentPreset("bass-reese", "Reese Bass", InstrumentType.SYNTH_BASS, SynthPatch(
            osc1 = Waveform.SAWTOOTH, osc2 = Waveform.SAWTOOTH, osc2Mix = 0.9f, osc2DetuneCents = 18f,
            cutoffHz = 900f, resonance = 1.2f, attack = 0.006f, decay = 0.3f, sustain = 0.8f, release = 0.2f)),
        InstrumentPreset("bass-acid", "Acid Bass", InstrumentType.SYNTH_BASS, SynthPatch(
            osc1 = Waveform.SQUARE, cutoffHz = 700f, resonance = 9f, filterEnvAmount = 0.9f,
            attack = 0.003f, decay = 0.22f, sustain = 0.25f, release = 0.15f)),
        InstrumentPreset("808-classic", "808 Classic", InstrumentType.BASS_808, SynthPatch(
            osc1 = Waveform.SINE, is808 = true, cutoffHz = 2000f, attack = 0.002f, decay = 1.2f, sustain = 0.35f, release = 0.25f, glideSec = 0.05f)),
        InstrumentPreset("808-dist", "808 Saturated", InstrumentType.BASS_808, SynthPatch(
            osc1 = Waveform.SINE, is808 = true, harmonics = floatArrayOf(1f, 0.22f, 0.08f), cutoffHz = 2600f,
            attack = 0.002f, decay = 0.9f, sustain = 0.4f, release = 0.2f, glideSec = 0.04f)),
        InstrumentPreset("lead-saw", "Saw Lead", InstrumentType.LEAD, SynthPatch(
            osc1 = Waveform.SAWTOOTH, osc2 = Waveform.SQUARE, osc2Mix = 0.4f, osc2Semitones = 12f,
            cutoffHz = 7000f, resonance = 1.6f, attack = 0.004f, decay = 0.2f, sustain = 0.75f, release = 0.25f, glideSec = 0.03f)),
        InstrumentPreset("lead-square", "Square Lead", InstrumentType.LEAD, SynthPatch(
            osc1 = Waveform.SQUARE, cutoffHz = 5000f, resonance = 1.0f, attack = 0.005f, decay = 0.25f, sustain = 0.65f, release = 0.2f)),
        InstrumentPreset("pad-warm", "Warm Pad", InstrumentType.PAD, SynthPatch(
            osc1 = Waveform.SAWTOOTH, osc2 = Waveform.SAWTOOTH, osc2Mix = 1f, osc2DetuneCents = 14f,
            cutoffHz = 2200f, resonance = 0.6f, attack = 0.6f, decay = 0.8f, sustain = 0.8f, release = 1.2f)),
        InstrumentPreset("pad-air", "Airy Pad", InstrumentType.PAD, SynthPatch(
            osc1 = Waveform.TRIANGLE, osc2 = Waveform.SINE, osc2Mix = 0.7f, osc2Semitones = 12f, noiseMix = 0.03f,
            cutoffHz = 3800f, attack = 0.9f, decay = 1f, sustain = 0.85f, release = 1.6f)),
        InstrumentPreset("pluck-soft", "Soft Pluck", InstrumentType.PLUCK, SynthPatch(
            osc1 = Waveform.SAWTOOTH, cutoffHz = 1800f, resonance = 2.2f, filterEnvAmount = 0.85f,
            attack = 0.002f, decay = 0.35f, sustain = 0f, release = 0.2f)),
        InstrumentPreset("pluck-nylon", "Nylon Pluck", InstrumentType.PLUCK, SynthPatch(
            osc1 = Waveform.TRIANGLE, harmonics = floatArrayOf(1f, 0.5f, 0.18f, 0.06f),
            cutoffHz = 2800f, filterEnvAmount = 0.4f, attack = 0.002f, decay = 0.55f, sustain = 0f, release = 0.25f)),
        InstrumentPreset("bell-fm", "FM Bell", InstrumentType.BELL, SynthPatch(
            osc1 = Waveform.SINE, fmRatio = 3.53f, fmIndex = 3.2f, fmDecay = 1.4f,
            cutoffHz = 10000f, attack = 0.002f, decay = 2.2f, sustain = 0f, release = 0.8f)),
        InstrumentPreset("bell-musicbox", "Music Box", InstrumentType.BELL, SynthPatch(
            osc1 = Waveform.SINE, fmRatio = 5.07f, fmIndex = 1.8f, fmDecay = 0.6f, harmonics = floatArrayOf(1f, 0.3f),
            cutoffHz = 12000f, attack = 0.001f, decay = 1.2f, sustain = 0f, release = 0.4f)),
        InstrumentPreset("strings-ensemble", "String Ensemble", InstrumentType.STRINGS, SynthPatch(
            osc1 = Waveform.SAWTOOTH, osc2 = Waveform.SAWTOOTH, osc2Mix = 0.9f, osc2DetuneCents = 9f,
            cutoffHz = 4200f, resonance = 0.5f, attack = 0.25f, decay = 0.5f, sustain = 0.85f, release = 0.7f)),
        InstrumentPreset("guitar-nylon", "Nylon Guitar", InstrumentType.GUITAR, SynthPatch(
            osc1 = Waveform.TRIANGLE, harmonics = floatArrayOf(1f, 0.6f, 0.25f, 0.08f, 0.03f),
            cutoffHz = 3400f, filterEnvAmount = 0.35f, attack = 0.003f, decay = 0.9f, sustain = 0.08f, release = 0.3f)),
        InstrumentPreset("guitar-muted", "Muted Guitar", InstrumentType.GUITAR, SynthPatch(
            osc1 = Waveform.SAWTOOTH, cutoffHz = 1600f, resonance = 1.4f, filterEnvAmount = 0.6f,
            attack = 0.002f, decay = 0.16f, sustain = 0f, release = 0.08f)),
    )

    fun byId(id: String): InstrumentPreset? = PRESETS.firstOrNull { it.id == id }
    fun defaultFor(type: InstrumentType): InstrumentPreset =
        PRESETS.firstOrNull { it.type == type } ?: PRESETS.first()
    fun byType(type: InstrumentType): List<InstrumentPreset> = PRESETS.filter { it.type == type }
}

private class SynthVoice {
    var active = false
    var midiNote = -1
    var velocity = 0f
    private val osc1 = Oscillator()
    private val osc2 = Oscillator()
    private val env = AdsrEnvelope()
    private val filter = Biquad()
    private val noise = NoiseGenerator(991)
    private var currentFreq = 440.0
    private var targetFreq = 440.0
    private var ageSamples = 0
    private var filterDirtyCounter = 0

    fun start(note: Int, vel: Float, patch: SynthPatch, sampleRate: Int, glideFromFreq: Double) {
        active = true
        midiNote = note
        velocity = vel
        targetFreq = NoteUtils.midiToFreq(note)
        currentFreq = if (patch.glideSec > 0f && glideFromFreq > 0) glideFromFreq else targetFreq
        osc1.waveform = patch.osc1; osc2.waveform = patch.osc2
        osc1.reset(); osc2.reset(0.25)
        env.attackSec = patch.attack; env.decaySec = patch.decay
        env.sustainLevel = patch.sustain; env.releaseSec = patch.release
        env.reset(); env.noteOn()
        filter.set(BiquadType.LOW_PASS, patch.cutoffHz.toDouble(), patch.resonance.toDouble(), 0.0, sampleRate)
        filter.reset()
        ageSamples = 0
    }

    fun stop() = env.noteOff()
    fun kill() { active = false; env.reset() }

    fun renderAdd(buffer: FloatArray, frames: Int, patch: SynthPatch, sampleRate: Int, gainL: Float, gainR: Float) {
        if (!active) return
        val n = min(frames, buffer.size / 2)
        val glideCoef = if (patch.glideSec > 0f) exp(-1.0 / (patch.glideSec * sampleRate)).toFloat() else 0f
        for (f in 0 until n) {
            if (patch.glideSec > 0f) {
                currentFreq = targetFreq + glideCoef * (currentFreq - targetFreq)
            } else currentFreq = targetFreq

            var freq = currentFreq
            if (patch.is808) {
                // pitch drop: +1 octave -> target over ~45 ms
                val drop = exp(-ageSamples.toDouble() / (0.045 * sampleRate))
                freq = targetFreq * (1.0 + drop)
            }

            val envVal = env.process(sampleRate)
            if (!env.isActive) { active = false; break }

            var sample: Float
            val harmonics = patch.harmonics
            if (harmonics != null && patch.fmRatio == 0f) {
                // Additive: osc1 waveform re-triggered per harmonic via phase multiply trick —
                // implemented as sine partials for clean organ/piano tones.
                var sum = 0f
                var norm = 0f
                for (h in harmonics.indices) {
                    val g = harmonics[h]
                    if (g <= 0f) continue
                    sum += sin(2.0 * PI * freq * (h + 1) * ageSamples / sampleRate).toFloat() * g
                    norm += g
                }
                sample = if (norm > 0f) sum / norm else 0f
                // keep osc phase advancing for waveform blend continuity
                osc1.process(freq, sampleRate)
            } else if (patch.fmRatio > 0f) {
                val fmEnv = exp(-ageSamples.toDouble() / max(1.0, patch.fmDecay * sampleRate)).toFloat()
                val modFreq = freq * patch.fmRatio
                val mod = sin(2.0 * PI * modFreq * ageSamples / sampleRate).toFloat()
                val deviation = mod * patch.fmIndex * fmEnv * freq
                sample = osc1.process(freq + deviation, sampleRate)
            } else {
                sample = osc1.process(freq, sampleRate)
                if (patch.osc2Mix > 0f) {
                    val cents = patch.osc2DetuneCents + patch.osc2Semitones * 100f
                    val f2 = freq * Math.pow(2.0, cents / 1200.0)
                    sample = sample * (1f - patch.osc2Mix * 0.5f) + osc2.process(f2, sampleRate) * patch.osc2Mix * 0.7f
                }
                if (patch.subOsc) sample += sin(2.0 * PI * (freq * 0.5) * ageSamples / sampleRate).toFloat() * 0.5f
                if (patch.noiseMix > 0f) sample += noise.next() * patch.noiseMix
            }

            // Filter envelope: recompute coefficients a few times/sec while env moves.
            if (patch.filterEnvAmount > 0f) {
                filterDirtyCounter++
                if (filterDirtyCounter >= 32) {
                    filterDirtyCounter = 0
                    val sweep = 1.0 + patch.filterEnvAmount * 8.0 * envVal
                    filter.set(BiquadType.LOW_PASS, (patch.cutoffHz * sweep), patch.resonance.toDouble(), 0.0, sampleRate)
                }
            }
            sample = filter.processSample(sample)
            val outLevel = sample * envVal * velocity * 0.55f
            val i = f * 2
            buffer[i] += outLevel * gainL
            buffer[i + 1] += outLevel * gainR
            ageSamples++
        }
    }
}

/** Polyphonic synth (16 voices, oldest-voice stealing). Audio-thread safe. */
class PolySynth(private val maxVoices: Int = 16) {
    var patch: SynthPatch = InstrumentLibrary.PRESETS.first().patch
    var sampleRate: Int = 44100
    private val voices = Array(maxVoices) { SynthVoice() }
    private var lastFreq = 0.0

    fun noteOn(midi: Int, velocity: Float) {
        // Re-trigger same note if already sounding.
        var target = voices.firstOrNull { it.active && it.midiNote == midi }
        if (target == null) target = voices.firstOrNull { !it.active }
        if (target == null) target = voices[0] // steal oldest slot
        target.start(midi, velocity.coerceIn(0f, 1f), patch, sampleRate, lastFreq)
        lastFreq = NoteUtils.midiToFreq(midi)
    }

    fun noteOff(midi: Int) {
        for (v in voices) if (v.active && v.midiNote == midi) v.stop()
    }

    fun allNotesOff() {
        for (v in voices) v.kill()
    }

    val hasActiveVoices: Boolean get() = voices.any { it.active }

    fun renderAdd(buffer: FloatArray, frames: Int, gainL: Float, gainR: Float) {
        for (v in voices) v.renderAdd(buffer, frames, patch, sampleRate, gainL, gainR)
    }
}

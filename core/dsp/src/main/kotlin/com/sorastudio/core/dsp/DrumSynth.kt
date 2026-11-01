package com.sorastudio.core.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

enum class DrumType(val displayName: String, val shortName: String) {
    KICK("Kick", "KIK"),
    SNARE("Snare", "SNR"),
    CLAP("Clap", "CLP"),
    RIM("Rimshot", "RIM"),
    HAT_CLOSED("Closed Hat", "CH"),
    HAT_OPEN("Open Hat", "OH"),
    TOM("Tom", "TOM"),
    PERC("Percussion", "PRC"),
    BASS_808("808", "808"),
}

data class DrumKitPreset(val id: String, val name: String, val description: String)

/**
 * Physically-inspired drum synthesis. Every bundled kit is generated in code —
 * no third-party samples — so the app ships with a clean license story.
 * Samples are mono; the renderer pans them per track.
 */
object DrumSynth {

    val KITS: List<DrumKitPreset> = listOf(
        DrumKitPreset("kit-studio", "Studio Kit", "Clean acoustic-style kit"),
        DrumKitPreset("kit-808", "808 Kit", "Classic analog drum machine character"),
        DrumKitPreset("kit-lofi", "Lo-Fi Kit", "Dusty, low-passed, soft transients"),
        DrumKitPreset("kit-electro", "Electro Kit", "Tight electronic kit"),
    )

    /** Build all one-shots for a kit at [sampleRate]. Cached by the caller. */
    fun buildKit(kitId: String, sampleRate: Int): Map<DrumType, FloatArray> {
        val lofi = kitId == "kit-lofi"
        val electro = kitId == "kit-electro"
        val classic808 = kitId == "kit-808"
        val result = mutableMapOf<DrumType, FloatArray>()
        for (type in DrumType.values()) {
            var sample = render(type, sampleRate, when {
                classic808 -> 1
                electro -> 2
                lofi -> 3
                else -> 0
            })
            if (lofi) sample = lofiProcess(sample, sampleRate)
            result[type] = sample
        }
        return result
    }

    fun render(type: DrumType, sampleRate: Int, variant: Int = 0): FloatArray = when (type) {
        DrumType.KICK -> kick(sampleRate, variant)
        DrumType.SNARE -> snare(sampleRate, variant)
        DrumType.CLAP -> clap(sampleRate, variant)
        DrumType.RIM -> rim(sampleRate)
        DrumType.HAT_CLOSED -> hat(sampleRate, open = false, variant = variant)
        DrumType.HAT_OPEN -> hat(sampleRate, open = true, variant = variant)
        DrumType.TOM -> tom(sampleRate, variant)
        DrumType.PERC -> perc(sampleRate, variant)
        DrumType.BASS_808 -> bass808(sampleRate, variant)
    }

    private fun kick(sr: Int, variant: Int): FloatArray {
        val duration = when (variant) { 1 -> 0.55; 2 -> 0.3; else -> 0.42 }
        val frames = (sr * duration).toInt()
        val out = FloatArray(frames)
        val startFreq = if (variant == 2) 220.0 else 160.0
        val endFreq = if (variant == 1) 42.0 else 48.0
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val pitchEnv = exp(-t / 0.028)
            val freq = endFreq + (startFreq - endFreq) * pitchEnv
            phase += 2.0 * PI * freq / sr
            val amp = exp(-t / (duration * 0.42))
            var v = sin(phase).toFloat() * amp.toFloat()
            // beater click
            if (t < 0.004) v += (1.0 - t / 0.004).toFloat() * 0.35f * sin(2.0 * PI * 3200.0 * t).toFloat()
            out[i] = softSaturate(v * 1.05f)
        }
        return out
    }

    private fun snare(sr: Int, variant: Int): FloatArray {
        val duration = if (variant == 2) 0.16 else 0.22
        val frames = (sr * duration).toInt()
        val out = FloatArray(frames)
        val noise = NoiseGenerator(1234)
        val bp = Biquad().apply { set(BiquadType.BAND_PASS, if (variant == 1) 1900.0 else 2400.0, 0.6, 0.0, sr) }
        val noiseBuf = FloatArray(frames) { noise.next() }
        bp.processMono(noiseBuf, frames)
        var phase = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val toneEnv = exp(-t / 0.055)
            val noiseEnv = exp(-t / 0.085)
            phase += 2.0 * PI * 192.0 / sr
            val tone = sin(phase).toFloat() * toneEnv.toFloat() * 0.55f
            out[i] = softSaturate(tone + noiseBuf[i] * noiseEnv.toFloat() * 1.15f)
        }
        return out
    }

    private fun clap(sr: Int, variant: Int): FloatArray {
        val duration = 0.3
        val frames = (sr * duration).toInt()
        val noise = NoiseGenerator(777)
        val bp = Biquad().apply { set(BiquadType.BAND_PASS, 1300.0, 1.4, 0.0, sr) }
        val noiseBuf = FloatArray(frames) { noise.next() }
        bp.processMono(noiseBuf, frames)
        val out = FloatArray(frames)
        val burstTimes = doubleArrayOf(0.0, 0.011, 0.023)
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            var env = exp(-t / 0.13)
            if (t < 0.032) {
                env = 0.25
                for (b in burstTimes) {
                    val dt = t - b
                    if (dt >= 0) env = max(env, exp(-dt / 0.006))
                }
            }
            out[i] = softSaturate(noiseBuf[i] * env.toFloat() * 1.5f)
        }
        return out
    }

    private fun rim(sr: Int): FloatArray {
        val frames = (sr * 0.045).toInt()
        val out = FloatArray(frames)
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val env = exp(-t / 0.012)
            val v = (sin(2.0 * PI * 1720.0 * t) * 0.7 + sin(2.0 * PI * 480.0 * t) * 0.3) * env
            out[i] = v.toFloat() * 0.8f
        }
        return out
    }

    private fun hat(sr: Int, open: Boolean, variant: Int): FloatArray {
        val duration = if (open) 0.45 else 0.055
        val frames = (sr * duration).toInt()
        val noise = NoiseGenerator(31337)
        val hp = Biquad().apply { set(BiquadType.HIGH_PASS, if (variant == 3) 6000.0 else 8200.0, 0.7, 0.0, sr) }
        val buf = FloatArray(frames) { noise.next() }
        hp.processMono(buf, frames)
        val out = FloatArray(frames)
        val decay = if (open) 0.16 else 0.016
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            out[i] = buf[i] * exp(-t / decay).toFloat() * 0.62f
        }
        return out
    }

    private fun tom(sr: Int, variant: Int): FloatArray {
        val frames = (sr * 0.35).toInt()
        val out = FloatArray(frames)
        var phase = 0.0
        val base = if (variant == 1) 120.0 else 140.0
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val freq = base * (1.0 + 0.7 * exp(-t / 0.03))
            phase += 2.0 * PI * freq / sr
            out[i] = (sin(phase) * exp(-t / 0.14)).toFloat() * 0.85f
        }
        return out
    }

    private fun perc(sr: Int, variant: Int): FloatArray {
        val frames = (sr * 0.12).toInt()
        val out = FloatArray(frames)
        val noise = NoiseGenerator(555)
        val freq = if (variant == 2) 1240.0 else 880.0
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val env = exp(-t / 0.03)
            val tone = sin(2.0 * PI * freq * t) * 0.5
            out[i] = (tone + noise.next() * 0.25).toFloat() * env.toFloat() * 0.8f
        }
        return out
    }

    private fun bass808(sr: Int, variant: Int): FloatArray {
        val frames = (sr * 0.9).toInt()
        val out = FloatArray(frames)
        var phase = 0.0
        val base = 55.0 // A1
        for (i in 0 until frames) {
            val t = i.toDouble() / sr
            val freq = base * (1.0 + 1.1 * exp(-t / 0.035))
            phase += 2.0 * PI * freq / sr
            val env = exp(-t / 0.34)
            var v = sin(phase).toFloat() * env.toFloat()
            if (variant == 2) v = softSaturate(v * 1.6f)
            out[i] = v * 0.95f
        }
        return out
    }

    private fun lofiProcess(sample: FloatArray, sr: Int): FloatArray {
        val out = sample.copyOf()
        val lp = Biquad().apply { set(BiquadType.LOW_PASS, 5200.0, 0.5, 0.0, sr) }
        lp.processMono(out, out.size)
        // gentle bit reduction for dust
        val levels = 1800f
        for (i in out.indices) {
            out[i] = kotlin.math.round(out[i] * levels) / levels * 0.92f
        }
        return out
    }

    private fun softSaturate(x: Float): Float {
        val ax = abs(x)
        val sign = if (x < 0) -1f else 1f
        return sign * (ax / (1f + ax * 0.35f))
    }
}

/**
 * Polyphonic one-shot sample player (drums, previews, sampler instrument).
 * Voices play mono or stereo buffers with linear-interpolation resampling.
 */
class SamplePlayer(private val maxVoices: Int = 24) {
    private class Voice {
        var active = false
        var data: FloatArray = FloatArray(0)
        var stereo = false
        var position = 0.0
        var rate = 1.0
        var gainL = 1f
        var gainR = 1f
        var release = false
        var releaseGain = 1f
        var noteId = -1
    }

    private val voices = Array(maxVoices) { Voice() }

    fun trigger(data: FloatArray, stereo: Boolean, rate: Double = 1.0, gainL: Float = 0.9f, gainR: Float = 0.9f, noteId: Int = -1) {
        val v = voices.firstOrNull { !it.active } ?: voices[0]
        v.active = true
        v.data = data
        v.stereo = stereo
        v.position = 0.0
        v.rate = rate.coerceIn(0.05, 8.0)
        v.gainL = gainL; v.gainR = gainR
        v.release = false; v.releaseGain = 1f
        v.noteId = noteId
    }

    fun release(noteId: Int) {
        for (v in voices) if (v.active && v.noteId == noteId) v.release = true
    }

    fun stopAll() = voices.forEach { it.active = false }
    val hasActiveVoices: Boolean get() = voices.any { it.active }

    fun renderAdd(buffer: FloatArray, frames: Int, gainL: Float = 1f, gainR: Float = 1f) {
        val n = min(frames, buffer.size / 2)
        for (v in voices) {
            if (!v.active) continue
            val totalFrames = if (v.stereo) v.data.size / 2 else v.data.size
            var f = 0
            while (f < n) {
                val pos = v.position
                if (pos >= totalFrames - 1) { v.active = false; break }
                if (v.release) {
                    v.releaseGain -= 0.004f
                    if (v.releaseGain <= 0f) { v.active = false; break }
                }
                val i0 = pos.toInt()
                val frac = (pos - i0).toFloat()
                val i = f * 2
                if (v.stereo) {
                    val l0 = v.data[i0 * 2]; val l1 = v.data[min(i0 + 1, totalFrames - 1) * 2]
                    val r0 = v.data[i0 * 2 + 1]; val r1 = v.data[min(i0 + 1, totalFrames - 1) * 2 + 1]
                    buffer[i] += (l0 + (l1 - l0) * frac) * v.gainL * v.releaseGain * gainL
                    buffer[i + 1] += (r0 + (r1 - r0) * frac) * v.gainR * v.releaseGain * gainR
                } else {
                    val s0 = v.data[i0]; val s1 = v.data[min(i0 + 1, totalFrames - 1)]
                    val s = s0 + (s1 - s0) * frac
                    buffer[i] += s * v.gainL * v.releaseGain * gainL
                    buffer[i + 1] += s * v.gainR * v.releaseGain * gainR
                }
                v.position += v.rate
                f++
            }
        }
    }
}

package com.sorastudio.core.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Interleaved stereo float buffer helpers.
 *
 * Convention everywhere in SORA Studio: audio is interleaved stereo
 * `FloatArray` [L0, R0, L1, R1, ...] with samples in -1..1 (float headroom allowed
 * internally; the master limiter/output stage guarantees the final range).
 * These functions never allocate — they are safe on the audio thread.
 */
object AudioBufferOps {

    fun framesOf(stereo: FloatArray): Int = stereo.size / 2

    fun clear(buffer: FloatArray, frames: Int) {
        val n = minOf(buffer.size, frames * 2)
        for (i in 0 until n) buffer[i] = 0f
    }

    /** buffer += source * gain (stereo -> stereo) */
    fun addWithGain(buffer: FloatArray, source: FloatArray, frames: Int, gain: Float) {
        val n = minOf(frames * 2, minOf(buffer.size, source.size))
        for (i in 0 until n) buffer[i] += source[i] * gain
    }

    /** buffer += mono source with constant-power pan gains already computed. */
    fun addMonoWithPan(buffer: FloatArray, mono: FloatArray, frameOffset: Int, frames: Int, gainL: Float, gainR: Float) {
        var bi = frameOffset * 2
        for (f in 0 until frames) {
            if (f >= mono.size || bi + 1 >= buffer.size) break
            val s = mono[f]
            buffer[bi] += s * gainL
            buffer[bi + 1] += s * gainR
            bi += 2
        }
    }

    fun applyGain(buffer: FloatArray, frames: Int, gain: Float) {
        val n = minOf(buffer.size, frames * 2)
        for (i in 0 until n) buffer[i] *= gain
    }

    fun applyStereoGainPan(buffer: FloatArray, frames: Int, gainL: Float, gainR: Float) {
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            buffer[f * 2] *= gainL
            buffer[f * 2 + 1] *= gainR
        }
    }

    fun peak(buffer: FloatArray, frames: Int): Float {
        val n = minOf(buffer.size, frames * 2)
        var p = 0f
        for (i in 0 until n) {
            val a = abs(buffer[i])
            if (a > p) p = a
        }
        return p
    }

    fun peakLR(buffer: FloatArray, frames: Int): Pair<Float, Float> {
        val n = minOf(frames, buffer.size / 2)
        var pl = 0f
        var pr = 0f
        for (f in 0 until n) {
            val al = abs(buffer[f * 2]); if (al > pl) pl = al
            val ar = abs(buffer[f * 2 + 1]); if (ar > pr) pr = ar
        }
        return pl to pr
    }

    fun rms(buffer: FloatArray, frames: Int): Float {
        val n = minOf(buffer.size, frames * 2)
        if (n == 0) return 0f
        var sum = 0.0
        for (i in 0 until n) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return sqrt(sum / n).toFloat()
    }

    fun softClipSample(x: Float): Float = when {
        x > 1f -> 1f - 1f / (1f + (x - 1f))
        x < -1f -> -1f + 1f / (1f + (-x - 1f))
        else -> x
    }

    /** Hard clamp to ±1 for final output safety. */
    fun clamp(buffer: FloatArray, frames: Int) {
        val n = minOf(buffer.size, frames * 2)
        for (i in 0 until n) {
            val v = buffer[i]
            buffer[i] = when {
                v > 1f -> 1f
                v < -1f -> -1f
                else -> v
            }
        }
    }

    /** Copy a region of [source] (stereo) into [dest] at frame offsets, with gain. */
    fun copyRegion(
        dest: FloatArray, destFrame: Int,
        source: FloatArray, sourceFrame: Int,
        frames: Int, gain: Float = 1f
    ) {
        var d = destFrame * 2
        var s = sourceFrame * 2
        for (f in 0 until frames) {
            if (d + 1 >= dest.size || s + 1 >= source.size) break
            dest[d] += source[s] * gain
            dest[d + 1] += source[s + 1] * gain
            d += 2; s += 2
        }
    }

    /** Mono -> stereo deinterleave average: writes mono mix of stereo buffer into [out]. */
    fun toMono(stereo: FloatArray, out: FloatArray, frames: Int) {
        val n = minOf(frames, minOf(out.size, stereo.size / 2))
        for (f in 0 until n) out[f] = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5f
    }

    fun peakOfAll(data: FloatArray): Float {
        var p = 0f
        for (v in data) {
            val a = abs(v)
            if (a > p) p = a
        }
        return max(p, 0f)
    }
}

/** A decoded audio asset in memory: interleaved samples at [channels] count. */
class DecodedAudio(
    val sampleRate: Int,
    val channels: Int,
    val samples: FloatArray
) {
    val frames: Int get() = if (channels <= 0) 0 else samples.size / channels
    val durationSeconds: Double get() = if (sampleRate <= 0) 0.0 else frames.toDouble() / sampleRate

    /** Returns a NEW stereo-interleaved copy resampled (linear) to [targetRate]. Allocates. */
    fun toStereoAt(targetRate: Int): FloatArray {
    // fast path
        if (channels == 2 && sampleRate == targetRate) return samples.copyOf()
        val ratio = sampleRate.toDouble() / targetRate.toDouble()
        val outFrames = (frames / ratio).toInt()
        val out = FloatArray(outFrames * 2)
        for (f in 0 until outFrames) {
            val srcPos = f * ratio
            val i0 = srcPos.toInt()
            val i1 = minOf(i0 + 1, frames - 1)
            val frac = (srcPos - i0).toFloat()
            val l0 = sampleAt(i0, 0); val l1 = sampleAt(i1, 0)
            val r0 = sampleAt(i0, if (channels > 1) 1 else 0); val r1 = sampleAt(i1, if (channels > 1) 1 else 0)
            out[f * 2] = l0 + (l1 - l0) * frac
            out[f * 2 + 1] = r0 + (r1 - r0) * frac
        }
        return out
    }

    private fun sampleAt(frame: Int, ch: Int): Float {
        val idx = frame * channels + ch
        return if (idx in samples.indices) samples[idx] else 0f
    }

    fun toMonoAt(targetRate: Int): FloatArray {
        val stereo = toStereoAt(targetRate)
        val out = FloatArray(stereo.size / 2)
        AudioBufferOps.toMono(stereo, out, out.size)
        return out
    }
}

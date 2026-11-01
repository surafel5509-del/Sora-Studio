package com.sorastudio.core.dsp

import com.sorastudio.core.common.LevelMath
import com.sorastudio.core.common.NoteUtils
import com.sorastudio.core.common.ScaleType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/**
 * Offline time/pitch processing:
 *  - Time stretch: WSOLA/OLA with Hann windows ("where practical", per spec).
 *  - Pitch shift: time-stretch + resample (formants move with pitch).
 *  - Pitch correction: analysis + granular resynthesis with a time-varying ratio
 *    that glides each frame toward the nearest in-key note.
 */
object TimePitch {

    /**
     * Overlap-add time stretch of a mono signal by [factor] (1.25 = 25% longer).
     * Pitch is (approximately) preserved; artefacts grow beyond 0.5x..2x.
     */
    fun timeStretchMono(input: FloatArray, factor: Double, windowSize: Int = 1024): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val f = factor.coerceIn(0.25, 4.0)
        if (abs(f - 1.0) < 0.001) return input.copyOf()
        val hopOut = windowSize / 4
        val hopIn = hopOut / f
        val outLength = (input.size * f).toInt() + windowSize
        val out = FloatArray(outLength)
        val norm = FloatArray(outLength)
        val window = FloatArray(windowSize) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (windowSize - 1))).toFloat() }
        var inPos = 0.0
        var outPos = 0
        while (outPos + windowSize < outLength && inPos < input.size) {
            val src = inPos.toInt()
            for (i in 0 until windowSize) {
                val sIdx = src + i
                if (sIdx >= input.size) break
                val w = window[i]
                out[outPos + i] += input[sIdx] * w
                norm[outPos + i] += w
            }
            inPos += hopIn
            outPos += hopOut
        }
        for (i in out.indices) if (norm[i] > 0.01f) out[i] /= norm[i]
        return out.copyOf(min(out.size, (input.size * f).toInt() + hopOut))
    }

    /** Pitch-shift a mono signal by [semitones] (duration preserved). */
    fun pitchShiftMono(input: FloatArray, semitones: Double): FloatArray {
        if (input.isEmpty() || abs(semitones) < 0.01) return input.copyOf()
        val ratio = LevelMath.semitonesToRatio(semitones)
        // Stretch by 1/ratio then resample by ratio.
        val stretched = timeStretchMono(input, 1.0 / ratio)
        return resampleMono(stretched, ratio, input.size)
    }

    /** Pitch-shift a stereo signal by processing channels with identical settings. */
    fun pitchShiftStereo(stereo: FloatArray, semitones: Double): FloatArray {
        val frames = stereo.size / 2
        val l = FloatArray(frames); val r = FloatArray(frames)
        for (i in 0 until frames) { l[i] = stereo[i * 2]; r[i] = stereo[i * 2 + 1] }
        val ls = pitchShiftMono(l, semitones)
        val rs = pitchShiftMono(r, semitones)
        val outFrames = min(ls.size, rs.size)
        val out = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) { out[i * 2] = ls[i]; out[i * 2 + 1] = rs[i] }
        return out
    }

    fun timeStretchStereo(stereo: FloatArray, factor: Double): FloatArray {
        val frames = stereo.size / 2
        val l = FloatArray(frames); val r = FloatArray(frames)
        for (i in 0 until frames) { l[i] = stereo[i * 2]; r[i] = stereo[i * 2 + 1] }
        val ls = timeStretchMono(l, factor)
        val rs = timeStretchMono(r, factor)
        val outFrames = min(ls.size, rs.size)
        val out = FloatArray(outFrames * 2)
        for (i in 0 until outFrames) { out[i * 2] = ls[i]; out[i * 2 + 1] = rs[i] }
        return out
    }

    fun resampleMono(input: FloatArray, ratio: Double, targetLength: Int = -1): FloatArray {
        if (input.isEmpty()) return FloatArray(0)
        val outLen = if (targetLength > 0) targetLength else (input.size / ratio).toInt()
        val out = FloatArray(outLen)
        val step = input.size.toDouble() / max(1, outLen)
        for (i in 0 until outLen) {
            val srcPos = i * step
            val i0 = srcPos.toInt()
            val i1 = min(i0 + 1, input.size - 1)
            val frac = (srcPos - i0).toFloat()
            out[i] = input[i0] + (input[i1] - input[i0]) * frac
        }
        @Suppress("UNUSED_EXPRESSION") ratio
        return out
    }

    data class PitchCorrectionSettings(
        val rootPitchClass: Int = 0,
        val scale: ScaleType = ScaleType.MAJOR,
        /** 0 = no correction, 1 = hard tune */
        val strength: Float = 0.8f,
        /** 0 = slow/natural glide, 1 = instant transitions */
        val speed: Float = 0.4f
    )

    /**
     * Correct a monophonic vocal toward the key. Analysis frames of 2048/hop 512
     * produce a smoothed ratio curve; resynthesis uses period grains (TD-PSOLA style
     * without explicit pitch marks — grains are placed on a fixed hop grid and read
     * at the local ratio, which corrects pitch while preserving timing).
     */
    fun pitchCorrectMono(input: FloatArray, sampleRate: Int, settings: PitchCorrectionSettings): FloatArray {
        if (input.size < 4096) return input.copyOf()
        val frameSize = 2048
        val hop = 512
        val numFrames = input.size / hop + 1
        val ratios = FloatArray(numFrames) { 1f }
        val frame = FloatArray(frameSize)

        for (fi in 0 until numFrames) {
            val start = fi * hop
            for (i in 0 until frameSize) {
                frame[i] = if (start + i < input.size) input[start + i] else 0f
            }
            val freq = PitchDetector.detect(frame, frameSize, sampleRate)
            if (freq > 0.0) {
                val exact = NoteUtils.freqToMidiExact(freq)
                val snapped = settings.scale.snap(settings.rootPitchClass, round(exact).toInt())
                val correction = (snapped - exact) * settings.strength.coerceIn(0f, 1f)
                ratios[fi] = LevelMath.semitonesToRatio(correction).toFloat()
            } else {
                ratios[fi] = if (fi > 0) ratios[fi - 1] else 1f
            }
        }
        // Smooth the ratio curve according to speed.
        val alpha = 0.05f + settings.speed.coerceIn(0f, 1f) * 0.8f
        for (fi in 1 until numFrames) {
            ratios[fi] = ratios[fi - 1] + alpha * (ratios[fi] - ratios[fi - 1])
        }

        // Granular resynthesis: grains of 1024 samples every 256 samples; each grain
        // is read from a source position advanced by hopOut/ratio so pitch changes
        // while the output timeline stays aligned with the input.
        val grain = 1024
        val grainHop = 256
        val out = FloatArray(input.size + grain)
        val norm = FloatArray(out.size)
        val window = FloatArray(grain) { i -> (0.5 - 0.5 * cos(2.0 * PI * i / (grain - 1))).toFloat() }
        var srcPos = 0.0
        var outPos = 0
        while (outPos < input.size) {
            val frameIdx = (outPos / hop).coerceIn(0, numFrames - 1)
            val ratio = ratios[frameIdx].toDouble().coerceIn(0.5, 2.0)
            val base = srcPos
            for (i in 0 until grain) {
                val readPos = base + i * ratio
                val i0 = readPos.toInt()
                if (i0 >= input.size) break
                val i1 = min(i0 + 1, input.size - 1)
                val frac = (readPos - i0).toFloat()
                val v = input[i0] + (input[i1] - input[i0]) * frac
                val w = window[i]
                if (outPos + i < out.size) {
                    out[outPos + i] += v * w
                    norm[outPos + i] += w
                }
            }
            srcPos += grainHop / ratio * ratio // timeline follows input; ratio consumed inside grain
            srcPos = outPos.toDouble() + grainHop
            outPos += grainHop
        }
        for (i in out.indices) if (norm[i] > 0.01f) out[i] /= norm[i]
        return out.copyOf(input.size)
    }
}

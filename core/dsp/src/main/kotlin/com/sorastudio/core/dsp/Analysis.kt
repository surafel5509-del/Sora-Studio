package com.sorastudio.core.dsp

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Autocorrelation pitch detector with parabolic interpolation.
 * Reliable for monophonic material (vocals, bass) in 50..1200 Hz.
 */
object PitchDetector {

    fun detect(mono: FloatArray, frames: Int, sampleRate: Int, minFreq: Double = 50.0, maxFreq: Double = 1200.0): Double {
        val n = min(frames, mono.size)
        if (n < 256) return -1.0
        // Energy check — don't tune silence/noise floor
        var energy = 0.0
        for (i in 0 until n) energy += mono[i] * mono[i]
        if (energy / n < 0.000005) return -1.0

        val minPeriod = max(2, (sampleRate / maxFreq).toInt())
        val maxPeriod = min(n / 2, (sampleRate / minFreq).toInt())
        if (maxPeriod <= minPeriod) return -1.0

        // Direct autocorrelation on <=8192 samples is fine for block-rate analysis.
        val len = n - maxPeriod
        val correlations = DoubleArray(maxPeriod + 1)
        var bestCorr = 0.0
        for (period in minPeriod..maxPeriod) {
            var corr = 0.0
            for (i in 0 until len) corr += mono[i] * mono[i + period]
            corr /= len
            correlations[period] = corr
            if (corr > bestCorr) bestCorr = corr
        }
        // Confidence: normalised by zero-lag energy
        var zeroLag = 0.0
        for (i in 0 until len) zeroLag += mono[i] * mono[i]
        zeroLag /= len
        if (zeroLag <= 0 || bestCorr / zeroLag < 0.45) return -1.0

        // Octave guard: for periodic signals EVERY multiple of the true period
        // correlates strongly. Pick the FIRST local peak that reaches 90% of the
        // global maximum instead of the raw argmax (which causes octave-down errors).
        val threshold = bestCorr * 0.9
        var bestPeriod = -1
        var period = minPeriod
        while (period <= maxPeriod) {
            if (correlations[period] > threshold) {
                // Refine to the local maximum of this lobe.
                var localBest = period
                var q = period
                while (q + 1 <= maxPeriod && correlations[q + 1] > correlations[localBest]) {
                    q++
                    localBest = q
                }
                bestPeriod = localBest
                break
            }
            period++
        }
        if (bestPeriod <= 0) return -1.0
        bestCorr = correlations[bestPeriod]

        // Parabolic interpolation around the peak
        fun corrAt(p: Int): Double = if (p in correlations.indices) correlations[p] else 0.0
        val cPrev = corrAt(bestPeriod - 1)
        val cNext = corrAt(bestPeriod + 1)
        val denom = cPrev - 2 * bestCorr + cNext
        val shift = if (denom != 0.0) 0.5 * (cPrev - cNext) / denom else 0.0
        val refinedPeriod = bestPeriod + shift.coerceIn(-1.0, 1.0)
        return sampleRate / refinedPeriod
    }
}

/** Peak computation for waveform display. Result: [min0,max0,min1,max1,...] per column. */
object WaveformPeaks {
    fun computeStereoPeaks(stereo: FloatArray, columns: Int): FloatArray {
        val frames = stereo.size / 2
        if (frames == 0 || columns <= 0) return FloatArray(0)
        val out = FloatArray(columns * 2)
        val perColumn = max(1, frames / columns)
        for (c in 0 until columns) {
            val start = c * perColumn
            val end = min(frames, start + perColumn)
            var mn = 0f; var mx = 0f
            for (f in start until end) {
                val v = (stereo[f * 2] + stereo[f * 2 + 1]) * 0.5f
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            out[c * 2] = mn; out[c * 2 + 1] = mx
        }
        return out
    }

    fun computeMonoPeaks(mono: FloatArray, columns: Int): FloatArray {
        if (mono.isEmpty() || columns <= 0) return FloatArray(0)
        val out = FloatArray(columns * 2)
        val perColumn = max(1, mono.size / columns)
        for (c in 0 until columns) {
            val start = c * perColumn
            val end = min(mono.size, start + perColumn)
            var mn = 0f; var mx = 0f
            for (f in start until end) {
                val v = mono[f]
                if (v < mn) mn = v
                if (v > mx) mx = v
            }
            out[c * 2] = mn; out[c * 2 + 1] = mx
        }
        return out
    }
}

/** Offline (destructive-on-copy) audio editing operations used by the Audio Editor. */
object AudioEditOps {

    /** Returns a new buffer containing [startFrame, startFrame+frames) of a stereo buffer. */
    fun trimStereo(stereo: FloatArray, startFrame: Int, frames: Int): FloatArray {
        val total = stereo.size / 2
        val s = startFrame.coerceIn(0, total)
        val count = frames.coerceIn(0, total - s)
        val out = FloatArray(count * 2)
        System.arraycopy(stereo, s * 2, out, 0, count * 2)
        return out
    }

    fun reverseStereo(stereo: FloatArray): FloatArray {
        val frames = stereo.size / 2
        val out = FloatArray(stereo.size)
        for (f in 0 until frames) {
            val src = (frames - 1 - f) * 2
            out[f * 2] = stereo[src]
            out[f * 2 + 1] = stereo[src + 1]
        }
        return out
    }

    fun reverseMono(mono: FloatArray): FloatArray = mono.reversedArray()

    fun fadeInStereo(stereo: FloatArray, fadeFrames: Int): FloatArray {
        val out = stereo.copyOf()
        val n = min(fadeFrames, out.size / 2)
        for (f in 0 until n) {
            val g = f.toFloat() / max(1, n)
            out[f * 2] *= g; out[f * 2 + 1] *= g
        }
        return out
    }

    fun fadeOutStereo(stereo: FloatArray, fadeFrames: Int): FloatArray {
        val out = stereo.copyOf()
        val frames = out.size / 2
        val n = min(fadeFrames, frames)
        for (f in 0 until n) {
            val idx = frames - 1 - f
            val g = f.toFloat() / max(1, n)
            out[idx * 2] *= g; out[idx * 2 + 1] *= g
        }
        return out
    }

    /** Peak-normalise a copy to [targetPeak] (default -1 dB). */
    fun normalize(data: FloatArray, targetPeak: Float = 0.891f): FloatArray {
        val peak = AudioBufferOps.peakOfAll(data)
        if (peak <= 0.000001f) return data.copyOf()
        val g = targetPeak / peak
        val out = FloatArray(data.size)
        for (i in data.indices) out[i] = data[i] * g
        return out
    }

    fun applyGainCopy(data: FloatArray, gainDb: Float): FloatArray {
        val g = com.sorastudio.core.common.LevelMath.dbToGain(gainDb)
        val out = FloatArray(data.size)
        for (i in data.indices) out[i] = data[i] * g
        return out
    }

    data class Region(val startFrame: Int, val endFrame: Int)

    /** Silent regions (below [thresholdDb]) longer than [minFrames] in a stereo buffer. */
    fun detectSilence(stereo: FloatArray, sampleRate: Int, thresholdDb: Float = -50f, minMs: Int = 250): List<Region> {
        val threshold = com.sorastudio.core.common.LevelMath.dbToGain(thresholdDb)
        val minFrames = sampleRate * minMs / 1000
        val frames = stereo.size / 2
        val regions = mutableListOf<Region>()
        var silenceStart = -1
        for (f in 0 until frames) {
            val v = max(abs(stereo[f * 2]), abs(stereo[f * 2 + 1]))
            if (v < threshold) {
                if (silenceStart < 0) silenceStart = f
            } else {
                if (silenceStart >= 0 && f - silenceStart >= minFrames) regions.add(Region(silenceStart, f))
                silenceStart = -1
            }
        }
        if (silenceStart >= 0 && frames - silenceStart >= minFrames) regions.add(Region(silenceStart, frames))
        return regions
    }

    /** Trim leading/trailing silence from a stereo buffer. */
    fun trimSilence(stereo: FloatArray, thresholdDb: Float = -50f): FloatArray {
        val threshold = com.sorastudio.core.common.LevelMath.dbToGain(thresholdDb)
        val frames = stereo.size / 2
        var first = 0
        while (first < frames && max(abs(stereo[first * 2]), abs(stereo[first * 2 + 1])) < threshold) first++
        var last = frames - 1
        while (last > first && max(abs(stereo[last * 2]), abs(stereo[last * 2 + 1])) < threshold) last--
        if (first >= frames) return FloatArray(0)
        return trimStereo(stereo, first, last - first + 1)
    }

    /** Join two stereo buffers with an equal-power crossfade of [crossfadeFrames]. */
    fun joinStereo(a: FloatArray, b: FloatArray, crossfadeFrames: Int = 512): FloatArray {
        val aFrames = a.size / 2; val bFrames = b.size / 2
        val x = min(crossfadeFrames, min(aFrames, bFrames))
        val outFrames = aFrames + bFrames - x
        val out = FloatArray(outFrames * 2)
        System.arraycopy(a, 0, out, 0, a.size)
        for (f in 0 until bFrames) {
            val outIdx = (aFrames - x + f) * 2
            if (outIdx < 0 || outIdx + 1 >= out.size) continue
            if (f < x) {
                val t = f.toFloat() / max(1, x)
                val gA = kotlin.math.cos(t * Math.PI / 2).toFloat()
                val gB = kotlin.math.sin(t * Math.PI / 2).toFloat()
                out[outIdx] = out[outIdx] * gA + b[f * 2] * gB
                out[outIdx + 1] = out[outIdx + 1] * gA + b[f * 2 + 1] * gB
            } else {
                out[outIdx] = b[f * 2]
                out[outIdx + 1] = b[f * 2 + 1]
            }
        }
        return out
    }
}

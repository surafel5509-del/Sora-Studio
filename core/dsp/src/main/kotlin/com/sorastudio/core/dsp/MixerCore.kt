package com.sorastudio.core.dsp

import com.sorastudio.core.common.LevelMath
import kotlin.math.exp
import kotlin.math.max

/** Per-channel meter ballistics (peak with decay + smoothed RMS). */
class MeterState {
    @Volatile var peakL: Float = 0f; private set
    @Volatile var peakR: Float = 0f; private set
    @Volatile var rmsL: Float = 0f; private set
    @Volatile var rmsR: Float = 0f; private set

    fun update(buffer: FloatArray, frames: Int, sampleRate: Int) {
        val (pl, pr) = AudioBufferOps.peakLR(buffer, frames)
        val blockSeconds = frames.toDouble() / max(1, sampleRate)
        val peakDecay = exp(-blockSeconds / 0.35).toFloat()
        peakL = max(pl, peakL * peakDecay)
        peakR = max(pr, peakR * peakDecay)
        // mono RMS split approximation: use buffer RMS for both, scaled by peak balance
        val r = AudioBufferOps.rms(buffer, frames)
        val rmsCoef = exp(-blockSeconds / 0.12).toFloat()
        rmsL = r + rmsCoef * (rmsL - r)
        rmsR = r + rmsCoef * (rmsR - r)
    }

    fun reset() { peakL = 0f; peakR = 0f; rmsL = 0f; rmsR = 0f }
}

/**
 * Stateless channel-strip math used by the renderer:
 * gain staging, pan law and mono/stereo conversion.
 */
object MixerCore {

    fun trackGain(volumeDb: Float): Float = LevelMath.dbToGain(volumeDb)

    /** Apply per-track volume + pan to a stereo track buffer in place. */
    fun applyVolumePan(buffer: FloatArray, frames: Int, volumeDb: Float, pan: Float) {
        val g = trackGain(volumeDb)
        val (gl, gr) = LevelMath.panGains(pan)
        // Normalise pan law so centre keeps unity *average* power for stereo sources:
        // scale by sqrt(2) compensation is folded in (constant power law at centre = 0.707).
        AudioBufferOps.applyStereoGainPan(buffer, frames, g * gl * 1.4142f, g * gr * 1.4142f)
    }

    /** Collapse to mono (in place, both channels get the sum). */
    fun toMonoInPlace(buffer: FloatArray, frames: Int) {
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val m = (buffer[f * 2] + buffer[f * 2 + 1]) * 0.5f
            buffer[f * 2] = m
            buffer[f * 2 + 1] = m
        }
    }

    /** True if any track is soloed — non-solo tracks are then silent. */
    fun isAudible(trackMuted: Boolean, trackSolo: Boolean, anySolo: Boolean): Boolean {
        if (trackMuted && !trackSolo) return false
        if (anySolo && !trackSolo) return false
        return true
    }
}

/** Simple synth click for the metronome (accent + regular). */
object MetronomeClick {
    fun render(sampleRate: Int, accent: Boolean): FloatArray {
        val frames = (sampleRate * 0.045).toInt()
        val out = FloatArray(frames)
        val freq = if (accent) 1760.0 else 1174.0
        for (i in 0 until frames) {
            val t = i.toDouble() / sampleRate
            val env = exp(-t / 0.012)
            out[i] = (kotlin.math.sin(2.0 * Math.PI * freq * t) * env).toFloat() * if (accent) 0.75f else 0.55f
        }
        return out
    }
}

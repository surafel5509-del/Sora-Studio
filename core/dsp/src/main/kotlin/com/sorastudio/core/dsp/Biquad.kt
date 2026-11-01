package com.sorastudio.core.dsp

import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class BiquadType { LOW_PASS, HIGH_PASS, BAND_PASS, NOTCH, PEAKING, LOW_SHELF, HIGH_SHELF }

/**
 * RBJ Audio EQ Cookbook biquad, Direct Form I, stereo (independent state per channel).
 * Coefficients are computed in Double; the hot loop runs in Float without allocation.
 */
class Biquad {
    private var b0 = 1.0; private var b1 = 0.0; private var b2 = 0.0
    private var a1 = 0.0; private var a2 = 0.0

    // State per channel: x1, x2, y1, y2
    private val xl = DoubleArray(2); private val yl = DoubleArray(2)
    private val xr = DoubleArray(2); private val yr = DoubleArray(2)

    var type: BiquadType = BiquadType.LOW_PASS
        private set

    fun set(type: BiquadType, freqHz: Double, q: Double, gainDb: Double, sampleRate: Int) {
        this.type = type
        val nyquist = sampleRate / 2.0
        val f = freqHz.coerceIn(10.0, nyquist * 0.98)
        val qq = q.coerceIn(0.1, 24.0)
        val w0 = 2.0 * Math.PI * f / sampleRate
        val cosW = cos(w0)
        val sinW = sin(w0)
        val alpha = sinW / (2.0 * qq)
        val a = 10.0.pow(gainDb / 40.0)

        var bb0 = 1.0; var bb1 = 0.0; var bb2 = 0.0
        var aa0 = 1.0; var aa1 = 0.0; var aa2 = 0.0
        when (type) {
            BiquadType.LOW_PASS -> {
                bb0 = (1 - cosW) / 2; bb1 = 1 - cosW; bb2 = (1 - cosW) / 2
                aa0 = 1 + alpha; aa1 = -2 * cosW; aa2 = 1 - alpha
            }
            BiquadType.HIGH_PASS -> {
                bb0 = (1 + cosW) / 2; bb1 = -(1 + cosW); bb2 = (1 + cosW) / 2
                aa0 = 1 + alpha; aa1 = -2 * cosW; aa2 = 1 - alpha
            }
            BiquadType.BAND_PASS -> {
                bb0 = alpha; bb1 = 0.0; bb2 = -alpha
                aa0 = 1 + alpha; aa1 = -2 * cosW; aa2 = 1 - alpha
            }
            BiquadType.NOTCH -> {
                bb0 = 1.0; bb1 = -2 * cosW; bb2 = 1.0
                aa0 = 1 + alpha; aa1 = -2 * cosW; aa2 = 1 - alpha
            }
            BiquadType.PEAKING -> {
                bb0 = 1 + alpha * a; bb1 = -2 * cosW; bb2 = 1 - alpha * a
                aa0 = 1 + alpha / a; aa1 = -2 * cosW; aa2 = 1 - alpha / a
            }
            BiquadType.LOW_SHELF -> {
                val t = 2 * sqrt(a) * alpha
                bb0 = a * ((a + 1) - (a - 1) * cosW + t)
                bb1 = 2 * a * ((a - 1) - (a + 1) * cosW)
                bb2 = a * ((a + 1) - (a - 1) * cosW - t)
                aa0 = (a + 1) + (a - 1) * cosW + t
                aa1 = -2 * ((a - 1) + (a + 1) * cosW)
                aa2 = (a + 1) + (a - 1) * cosW - t
            }
            BiquadType.HIGH_SHELF -> {
                val t = 2 * sqrt(a) * alpha
                bb0 = a * ((a + 1) + (a - 1) * cosW + t)
                bb1 = -2 * a * ((a - 1) + (a + 1) * cosW)
                bb2 = a * ((a + 1) + (a - 1) * cosW - t)
                aa0 = (a + 1) - (a - 1) * cosW + t
                aa1 = 2 * ((a - 1) - (a + 1) * cosW)
                aa2 = (a + 1) - (a - 1) * cosW - t
            }
        }
        b0 = bb0 / aa0; b1 = bb1 / aa0; b2 = bb2 / aa0
        a1 = aa1 / aa0; a2 = aa2 / aa0
    }

    fun reset() {
        xl.fill(0.0); yl.fill(0.0); xr.fill(0.0); yr.fill(0.0)
    }

    /** Process interleaved stereo in place. */
    fun processStereo(buffer: FloatArray, frames: Int) {
        val n = minOf(frames, buffer.size / 2)
        var x1l = xl[0]; var x2l = xl[1]; var y1l = yl[0]; var y2l = yl[1]
        var x1r = xr[0]; var x2r = xr[1]; var y1r = yr[0]; var y2r = yr[1]
        val cb0 = b0; val cb1 = b1; val cb2 = b2; val ca1 = a1; val ca2 = a2
        for (f in 0 until n) {
            val i = f * 2
            val inL = buffer[i].toDouble()
            val outL = cb0 * inL + cb1 * x1l + cb2 * x2l - ca1 * y1l - ca2 * y2l
            x2l = x1l; x1l = inL; y2l = y1l; y1l = outL
            buffer[i] = outL.toFloat()

            val inR = buffer[i + 1].toDouble()
            val outR = cb0 * inR + cb1 * x1r + cb2 * x2r - ca1 * y1r - ca2 * y2r
            x2r = x1r; x1r = inR; y2r = y1r; y1r = outR
            buffer[i + 1] = outR.toFloat()
        }
        xl[0] = x1l; xl[1] = x2l; yl[0] = y1l; yl[1] = y2l
        xr[0] = x1r; xr[1] = x2r; yr[0] = y1r; yr[1] = y2r
    }

    fun processMono(buffer: FloatArray, frames: Int) {
        var x1 = xl[0]; var x2 = xl[1]; var y1 = yl[0]; var y2 = yl[1]
        val n = minOf(frames, buffer.size)
        for (f in 0 until n) {
            val input = buffer[f].toDouble()
            val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = input; y2 = y1; y1 = out
            buffer[f] = out.toFloat()
        }
        xl[0] = x1; xl[1] = x2; yl[0] = y1; yl[1] = y2
    }

    /** Single-sample mono processing for synth voices. */
    fun processSample(input: Float): Float {
        val x = input.toDouble()
        val y = b0 * x + b1 * xl[0] + b2 * xl[1] - a1 * yl[0] - a2 * yl[1]
        xl[1] = xl[0]; xl[0] = x
        yl[1] = yl[0]; yl[0] = y
        return y.toFloat()
    }
}

/** Simple one-pole low-pass, useful for parameter smoothing & tone control. */
class OnePoleLowPass(private var coefficient: Float = 0.2f) {
    private var state = 0f
    fun setCutoff(freqHz: Double, sampleRate: Int) {
        val x = (2.0 * Math.PI * freqHz / sampleRate).coerceIn(0.0, 1.0)
        coefficient = x.toFloat()
    }
    fun process(input: Float): Float {
        state += coefficient * (input - state)
        return state
    }
    fun reset(value: Float = 0f) { state = value }
    val current: Float get() = state
}

/** Circular delay line with fractional (linear-interpolated) read. Preallocated. */
class DelayLine(maxSamples: Int) {
    private val buffer = FloatArray(maxOf(2, maxSamples))
    private var writePos = 0
    val capacity: Int get() = buffer.size

    fun reset() {
        buffer.fill(0f)
        writePos = 0
    }

    fun push(sample: Float) {
        buffer[writePos] = sample
        writePos = (writePos + 1) % buffer.size
    }

    fun read(delaySamples: Float): Float {
        val d = delaySamples.coerceIn(0f, (buffer.size - 2).toFloat())
        val readPos = writePos - 1 - d
        val i0 = floorMod(readPos.toInt(), buffer.size)
        val i1 = (i0 + 1) % buffer.size
        val frac = d - kotlin.math.floor(d)
        return buffer[i0] * (1f - frac) + buffer[i1] * frac
    }

    fun readInt(delaySamples: Int): Float {
        val d = delaySamples.coerceIn(0, buffer.size - 1)
        return buffer[floorMod(writePos - 1 - d, buffer.size)]
    }

    private fun floorMod(a: Int, m: Int): Int = ((a % m) + m) % m
}

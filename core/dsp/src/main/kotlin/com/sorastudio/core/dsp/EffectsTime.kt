package com.sorastudio.core.dsp

import com.sorastudio.core.common.LevelMath
import com.sorastudio.core.common.NoteUtils
import com.sorastudio.core.common.ScaleType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

// ---------------------------------------------------------------- reverb (Freeverb)

private class CombFilter(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0
    private var store = 0f
    var feedback = 0.84f
    var damp1 = 0.2f
    var damp2 = 0.8f
    fun reset() { buffer.fill(0f); store = 0f; index = 0 }
    fun process(input: Float): Float {
        val out = buffer[index]
        store = out * damp2 + store * damp1
        buffer[index] = input + store * feedback
        index = (index + 1) % buffer.size
        return out
    }
}

private class AllpassFilter(size: Int) {
    private val buffer = FloatArray(size)
    private var index = 0
    var feedback = 0.5f
    fun reset() { buffer.fill(0f); index = 0 }
    fun process(input: Float): Float {
        val buffered = buffer[index]
        val out = -input + buffered
        buffer[index] = input + buffered * feedback
        index = (index + 1) % buffer.size
        return out
    }
}

/**
 * Schroeder/Freeverb stereo reverb: 8 parallel feedback comb filters into
 * 4 series allpasses per channel, with stereo spread. Pre-delay via delay lines.
 */
class Reverb : AudioEffect(EffectType.REVERB) {
    private var combsL: Array<CombFilter> = emptyArray()
    private var combsR: Array<CombFilter> = emptyArray()
    private var allpassL: Array<AllpassFilter> = emptyArray()
    private var allpassR: Array<AllpassFilter> = emptyArray()
    private var preL: DelayLine? = null
    private var preR: DelayLine? = null

    override fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        val scale = sampleRate / 44100.0
        val combTunings = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        val allpassTunings = intArrayOf(556, 441, 341, 225)
        val spread = (23 * scale).toInt()
        combsL = Array(8) { CombFilter(max(8, (combTunings[it] * scale).toInt())) }
        combsR = Array(8) { CombFilter(max(8, (combTunings[it] * scale).toInt() + spread)) }
        allpassL = Array(4) { AllpassFilter(max(8, (allpassTunings[it] * scale).toInt())) }
        allpassR = Array(4) { AllpassFilter(max(8, (allpassTunings[it] * scale).toInt() + spread)) }
        preL = DelayLine(sampleRate / 4)
        preR = DelayLine(sampleRate / 4)
        onParamsChanged()
    }

    override fun onParamsChanged() {
        if (combsL.isEmpty()) return
        val size = param("size", 60f) / 100f
        val damping = param("damping", 50f) / 100f
        val feedback = 0.70f + size * 0.28f
        val damp = damping * 0.9f
        for (c in combsL + combsR) {
            c.feedback = feedback
            c.damp1 = damp
            c.damp2 = 1f - damp
        }
    }

    override fun process(buffer: FloatArray, frames: Int) {
        val width = param("width", 80f) / 100f
        val predelaySamples = param("predelay", 10f) / 1000f * sampleRate
        val pl = preL ?: return
        val pr = preR ?: return
        val n = minOf(frames, buffer.size / 2)
        val wet1 = (width / 2f + 0.5f) * 0.6f
        val wet2 = ((1f - width) / 2f) * 0.6f
        for (f in 0 until n) {
            val i = f * 2
            pl.push(buffer[i]); pr.push(buffer[i + 1])
            val inL = pl.read(predelaySamples) * 0.5f + pr.read(predelaySamples) * 0.0f
            val inR = pr.read(predelaySamples) * 0.5f + pl.read(predelaySamples) * 0.0f
            var accL = 0f; var accR = 0f
            for (c in combsL) accL += c.process(inL)
            for (c in combsR) accR += c.process(inR)
            accL *= 0.25f; accR *= 0.25f
            for (a in allpassL) accL = a.process(accL)
            for (a in allpassR) accR = a.process(accR)
            buffer[i] = accL * wet1 + accR * wet2
            buffer[i + 1] = accR * wet1 + accL * wet2
        }
    }

    override fun reset() {
        combsL.forEach { it.reset() }; combsR.forEach { it.reset() }
        allpassL.forEach { it.reset() }; allpassR.forEach { it.reset() }
        preL?.reset(); preR?.reset()
    }
}

// ---------------------------------------------------------------- delays

class StereoDelay(private val pingPong: Boolean) :
    AudioEffect(if (pingPong) EffectType.PING_PONG_DELAY else EffectType.DELAY) {
    private var lineL: DelayLine? = null
    private var lineR: DelayLine? = null
    private val toneL = Biquad(); private val toneR = Biquad()

    override fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        lineL = DelayLine(sampleRate * 2)
        lineR = DelayLine(sampleRate * 2)
        onParamsChanged()
    }

    override fun onParamsChanged() {
        toneL.set(BiquadType.LOW_PASS, param("tone", 6000f).toDouble(), 0.5, 0.0, sampleRate)
        toneR.set(BiquadType.LOW_PASS, param("tone", 6000f).toDouble(), 0.5, 0.0, sampleRate)
    }

    override fun process(buffer: FloatArray, frames: Int) {
        val ll = lineL ?: return; val lr = lineR ?: return
        val delaySamples = param("time", 375f) / 1000f * sampleRate
        val feedback = (param("feedback", 35f) / 100f).coerceIn(0f, 0.92f)
        val n = minOf(frames, buffer.size / 2)
        val one = FloatArray(1)
        for (f in 0 until n) {
            val i = f * 2
            val wetL = ll.read(delaySamples)
            val wetR = lr.read(delaySamples)
            val fbL = if (pingPong) wetR else wetL
            val fbR = if (pingPong) wetL else wetR
            one[0] = fbL; toneL.processMono(one, 1); val tL = one[0]
            one[0] = fbR; toneR.processMono(one, 1); val tR = one[0]
            ll.push(buffer[i] + tL * feedback)
            lr.push(buffer[i + 1] + tR * feedback)
            buffer[i] = wetL
            buffer[i + 1] = wetR
        }
    }

    override fun reset() { lineL?.reset(); lineR?.reset(); toneL.reset(); toneR.reset() }
}

// ---------------------------------------------------------------- modulation

class Chorus : AudioEffect(EffectType.CHORUS) {
    private var lineL: DelayLine? = null
    private var lineR: DelayLine? = null
    private var phase = 0.0
    override fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        lineL = DelayLine((sampleRate * 0.06).toInt() + 8)
        lineR = DelayLine((sampleRate * 0.06).toInt() + 8)
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val ll = lineL ?: return; val lr = lineR ?: return
        val rate = param("rate", 1.2f).toDouble()
        val depth = param("depth", 45f) / 100f
        val baseDelay = sampleRate * 0.018
        val depthSamples = sampleRate * 0.006 * depth
        val inc = 2.0 * Math.PI * rate / sampleRate
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            ll.push(buffer[i]); lr.push(buffer[i + 1])
            val modL = (baseDelay + depthSamples * sin(phase)).toFloat()
            val modR = (baseDelay + depthSamples * sin(phase + Math.PI / 2)).toFloat()
            buffer[i] = ll.read(modL)
            buffer[i + 1] = lr.read(modR)
            phase += inc
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI
        }
    }
    override fun reset() { lineL?.reset(); lineR?.reset(); phase = 0.0 }
}

class Flanger : AudioEffect(EffectType.FLANGER) {
    private var lineL: DelayLine? = null
    private var lineR: DelayLine? = null
    private var phase = 0.0
    private var fbL = 0f; private var fbR = 0f
    override fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        lineL = DelayLine((sampleRate * 0.02).toInt() + 8)
        lineR = DelayLine((sampleRate * 0.02).toInt() + 8)
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val ll = lineL ?: return; val lr = lineR ?: return
        val rate = param("rate", 0.4f).toDouble()
        val depth = param("depth", 60f) / 100f
        val feedback = (param("feedback", 40f) / 100f).coerceIn(0f, 0.9f)
        val baseDelay = sampleRate * 0.003
        val depthSamples = sampleRate * 0.0025 * depth
        val inc = 2.0 * Math.PI * rate / sampleRate
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            ll.push(buffer[i] + fbL * feedback)
            lr.push(buffer[i + 1] + fbR * feedback)
            val mod = (baseDelay + depthSamples * sin(phase)).toFloat()
            val wetL = ll.read(mod); val wetR = lr.read(mod)
            fbL = wetL; fbR = wetR
            buffer[i] = buffer[i] * 0.6f + wetL * 0.6f
            buffer[i + 1] = buffer[i + 1] * 0.6f + wetR * 0.6f
            phase += inc
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI
        }
    }
    override fun reset() { lineL?.reset(); lineR?.reset(); phase = 0.0; fbL = 0f; fbR = 0f }
}

private class FirstOrderAllpass {
    var a = 0f
    private var x1 = 0f; private var y1 = 0f
    fun process(x: Float): Float {
        val y = -a * x + x1 + a * y1
        x1 = x; y1 = y
        return y
    }
    fun reset() { x1 = 0f; y1 = 0f }
}

class Phaser : AudioEffect(EffectType.PHASER) {
    private val stagesL = Array(4) { FirstOrderAllpass() }
    private val stagesR = Array(4) { FirstOrderAllpass() }
    private var phase = 0.0
    private var fbL = 0f; private var fbR = 0f
    override fun process(buffer: FloatArray, frames: Int) {
        val rate = param("rate", 0.5f).toDouble()
        val depth = param("depth", 60f) / 100f
        val feedback = (param("feedback", 30f) / 100f).coerceIn(0f, 0.85f)
        val inc = 2.0 * Math.PI * rate / sampleRate
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            val lfo = 0.5 + 0.5 * sin(phase)
            // allpass coefficient sweep, centre ~800Hz equivalent
            val a = (0.1 + 0.8 * depth * lfo).toFloat()
            var vL = buffer[i] + fbL * feedback
            var vR = buffer[i + 1] + fbR * feedback
            for (s in stagesL) { s.a = a; vL = s.process(vL) }
            for (s in stagesR) { s.a = a; vR = s.process(vR) }
            fbL = vL; fbR = vR
            buffer[i] = buffer[i] * 0.6f + vL * 0.6f
            buffer[i + 1] = buffer[i + 1] * 0.6f + vR * 0.6f
            phase += inc
            if (phase > 2 * Math.PI) phase -= 2 * Math.PI
        }
    }
    override fun reset() {
        stagesL.forEach { it.reset() }; stagesR.forEach { it.reset() }
        phase = 0.0; fbL = 0f; fbR = 0f
    }
}

// ---------------------------------------------------------------- pitch

/**
 * Real-time pitch shifter using the classic dual delay-line crossfade technique.
 * One shared read pointer keeps the stereo image coherent. Window = 1024 samples.
 */
open class DelayPitchShifter : AudioEffect(EffectType.PITCH_SHIFT) {
    protected var lineL: DelayLine? = null
    protected var lineR: DelayLine? = null
    protected var phase = 0.0
    protected val windowSize = 1024
    var ratioOverride: Float? = null // used by AutoTuneLive

    override fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        lineL = DelayLine(windowSize * 3)
        lineR = DelayLine(windowSize * 3)
        onParamsChanged()
    }

    protected fun currentRatio(): Float =
        ratioOverride ?: LevelMath.semitonesToRatio(param("semitones", 0f).toDouble()).toFloat()

    override fun process(buffer: FloatArray, frames: Int) {
        val ll = lineL ?: return; val lr = lineR ?: return
        val ratio = currentRatio().coerceIn(0.25f, 4f)
        val increment = 1.0 - ratio.toDouble()
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            ll.push(buffer[i]); lr.push(buffer[i + 1])
            phase += increment
            while (phase < 0) phase += windowSize
            while (phase >= windowSize) phase -= windowSize
            val p1 = phase.toFloat()
            val p2 = (if (p1 >= windowSize / 2) p1 - windowSize / 2 else p1 + windowSize / 2)
            val xfade = abs(p1 / windowSize - 0.5f) * 2f // 0 at centre -> 1 at edges
            val g2 = xfade; val g1 = 1f - xfade
            buffer[i] = ll.read(p1) * g1 + ll.read(p2) * g2
            buffer[i + 1] = lr.read(p1) * g1 + lr.read(p2) * g2
        }
    }

    override fun reset() { lineL?.reset(); lineR?.reset(); phase = 0.0 }
}

/**
 * Light-weight live auto-tune: each block, estimate the dominant pitch of the
 * (mono-summed) input by autocorrelation, snap it to the chosen scale and drive
 * the delay-line pitch shifter with a smoothed correction ratio.
 *
 * Honest scope: this gives audible pitch correction on monophonic vocals; it is
 * not a replacement for offline studio tuning (see the Vocal screen's render).
 */
class AutoTuneLive : DelayPitchShifter() {
    private val monoScratch = FloatArray(8192)
    private var smoothedRatio = 1f

    override fun process(buffer: FloatArray, frames: Int) {
        val n = minOf(frames, minOf(buffer.size / 2, monoScratch.size))
        val strength = param("strength", 70f) / 100f
        val speed = (param("speed", 30f) / 100f).coerceIn(0.01f, 1f)
        if (n >= 512) {
            AudioBufferOps.toMono(buffer, monoScratch, n)
            val freq = PitchDetector.detect(monoScratch, n, sampleRate)
            if (freq > 0.0) {
                val root = param("root", 0f).toInt().coerceIn(0, 11)
                val exactMidi = NoteUtils.freqToMidiExact(freq)
                val nearest = kotlin.math.round(exactMidi).toInt()
                val snapped = ScaleType.NATURAL_MINOR.snap(root, nearest)
                // If natural minor snap moves >1 semitone, the note may be major — accept nearest instead
                val target = if (abs(snapped - nearest) > 1) nearest else snapped
                val correctionSemitones = (target - exactMidi) * strength
                val targetRatio = LevelMath.semitonesToRatio(correctionSemitones).toFloat()
                smoothedRatio += speed * 0.25f * (targetRatio - smoothedRatio)
            }
        }
        ratioOverride = smoothedRatio.coerceIn(0.5f, 2f)
        super.process(buffer, frames)
    }
}

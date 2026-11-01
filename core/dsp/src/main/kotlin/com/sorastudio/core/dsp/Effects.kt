package com.sorastudio.core.dsp

import com.sorastudio.core.common.LevelMath
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.tanh

/** Every real-time effect type available in SORA Studio. */
enum class EffectType(val displayName: String, val category: String) {
    THREE_BAND_EQ("3-Band EQ", "EQ"),
    PARAMETRIC_EQ("Parametric EQ", "EQ"),
    EIGHT_BAND_EQ("8-Band EQ", "EQ"),
    HIGH_PASS("High-Pass Filter", "Filter"),
    LOW_PASS("Low-Pass Filter", "Filter"),
    COMPRESSOR("Compressor", "Dynamics"),
    LIMITER("Limiter", "Dynamics"),
    NOISE_GATE("Noise Gate", "Dynamics"),
    DE_ESSER("De-Esser", "Dynamics"),
    REVERB("Reverb", "Space"),
    DELAY("Delay", "Space"),
    PING_PONG_DELAY("Ping-Pong Delay", "Space"),
    CHORUS("Chorus", "Modulation"),
    FLANGER("Flanger", "Modulation"),
    PHASER("Phaser", "Modulation"),
    TREMOLO("Tremolo", "Modulation"),
    AUTO_PAN("Auto-Pan", "Modulation"),
    DISTORTION("Distortion", "Drive"),
    SATURATION("Saturation", "Drive"),
    BIT_CRUSHER("Bit Crusher", "Drive"),
    STEREO_WIDTH("Stereo Width", "Stereo"),
    PITCH_SHIFT("Pitch Shift", "Pitch"),
    AUTO_TUNE("Auto Tune (Live)", "Pitch"),
}

data class ParamDef(val key: String, val label: String, val min: Float, val max: Float, val default: Float, val unit: String = "")

/** Parameter definitions + factory + factory presets for every effect. */
object EffectCatalog {

    private fun p(key: String, label: String, min: Float, max: Float, default: Float, unit: String = "") =
        ParamDef(key, label, min, max, default, unit)

    val PARAMS: Map<EffectType, List<ParamDef>> = mapOf(
        EffectType.THREE_BAND_EQ to listOf(
            p("low", "Low", -18f, 18f, 0f, "dB"), p("mid", "Mid", -18f, 18f, 0f, "dB"),
            p("high", "High", -18f, 18f, 0f, "dB"), p("lowFreq", "Low Freq", 40f, 600f, 120f, "Hz"),
            p("highFreq", "High Freq", 2000f, 16000f, 8000f, "Hz")),
        EffectType.PARAMETRIC_EQ to (1..4).flatMap { b ->
            listOf(p("b${b}freq", "Band $b Freq", 30f, 18000f, listOf(100f, 500f, 2000f, 6000f)[b - 1], "Hz"),
                p("b${b}gain", "Band $b Gain", -18f, 18f, 0f, "dB"),
                p("b${b}q", "Band $b Q", 0.2f, 10f, 1f))
        },
        EffectType.EIGHT_BAND_EQ to listOf(60, 150, 400, 1000, 2400, 6000, 10000, 15000).mapIndexed { i, f ->
            p("band$i", "${f}Hz", -15f, 15f, 0f, "dB")
        },
        EffectType.HIGH_PASS to listOf(p("freq", "Frequency", 20f, 2000f, 80f, "Hz"), p("res", "Resonance", 0.3f, 4f, 0.71f)),
        EffectType.LOW_PASS to listOf(p("freq", "Frequency", 200f, 18000f, 12000f, "Hz"), p("res", "Resonance", 0.3f, 8f, 0.71f)),
        EffectType.COMPRESSOR to listOf(
            p("threshold", "Threshold", -48f, 0f, -18f, "dB"), p("ratio", "Ratio", 1f, 20f, 4f, ":1"),
            p("attack", "Attack", 0.1f, 100f, 10f, "ms"), p("release", "Release", 10f, 1000f, 150f, "ms"),
            p("makeup", "Makeup", 0f, 24f, 0f, "dB"), p("knee", "Knee", 0f, 24f, 6f, "dB")),
        EffectType.LIMITER to listOf(p("ceiling", "Ceiling", -12f, 0f, -0.3f, "dB"), p("release", "Release", 10f, 500f, 100f, "ms"), p("input", "Input Gain", 0f, 24f, 0f, "dB")),
        EffectType.NOISE_GATE to listOf(
            p("threshold", "Threshold", -80f, -10f, -50f, "dB"), p("attack", "Attack", 0.1f, 50f, 2f, "ms"),
            p("release", "Release", 10f, 1000f, 200f, "ms"), p("range", "Range", 0f, 60f, 40f, "dB")),
        EffectType.DE_ESSER to listOf(p("freq", "Frequency", 3000f, 12000f, 6500f, "Hz"), p("amount", "Amount", 0f, 100f, 40f, "%"), p("threshold", "Threshold", -40f, 0f, -20f, "dB")),
        EffectType.REVERB to listOf(
            p("size", "Room Size", 0f, 100f, 60f, "%"), p("damping", "Damping", 0f, 100f, 50f, "%"),
            p("width", "Width", 0f, 100f, 80f, "%"), p("predelay", "Pre-Delay", 0f, 200f, 10f, "ms")),
        EffectType.DELAY to listOf(
            p("time", "Time", 20f, 2000f, 375f, "ms"), p("feedback", "Feedback", 0f, 90f, 35f, "%"),
            p("tone", "Tone", 500f, 16000f, 6000f, "Hz")),
        EffectType.PING_PONG_DELAY to listOf(
            p("time", "Time", 20f, 2000f, 375f, "ms"), p("feedback", "Feedback", 0f, 90f, 40f, "%"),
            p("tone", "Tone", 500f, 16000f, 6000f, "Hz")),
        EffectType.CHORUS to listOf(p("rate", "Rate", 0.05f, 8f, 1.2f, "Hz"), p("depth", "Depth", 0f, 100f, 45f, "%")),
        EffectType.FLANGER to listOf(p("rate", "Rate", 0.05f, 10f, 0.4f, "Hz"), p("depth", "Depth", 0f, 100f, 60f, "%"), p("feedback", "Feedback", 0f, 90f, 40f, "%")),
        EffectType.PHASER to listOf(p("rate", "Rate", 0.05f, 8f, 0.5f, "Hz"), p("depth", "Depth", 0f, 100f, 60f, "%"), p("feedback", "Feedback", 0f, 90f, 30f, "%")),
        EffectType.TREMOLO to listOf(p("rate", "Rate", 0.1f, 20f, 5f, "Hz"), p("depth", "Depth", 0f, 100f, 50f, "%")),
        EffectType.AUTO_PAN to listOf(p("rate", "Rate", 0.05f, 10f, 1f, "Hz"), p("depth", "Depth", 0f, 100f, 60f, "%")),
        EffectType.DISTORTION to listOf(p("drive", "Drive", 0f, 100f, 40f, "%"), p("tone", "Tone", 500f, 16000f, 5000f, "Hz"), p("output", "Output", -24f, 6f, -6f, "dB")),
        EffectType.SATURATION to listOf(p("drive", "Drive", 0f, 100f, 30f, "%"), p("output", "Output", -12f, 6f, 0f, "dB")),
        EffectType.BIT_CRUSHER to listOf(p("bits", "Bits", 2f, 16f, 12f), p("rate", "Downsample", 1f, 40f, 4f, "x")),
        EffectType.STEREO_WIDTH to listOf(p("width", "Width", 0f, 200f, 100f, "%"), p("bassMono", "Bass Mono", 40f, 400f, 120f, "Hz")),
        EffectType.PITCH_SHIFT to listOf(p("semitones", "Semitones", -12f, 12f, 0f, "st")),
        EffectType.AUTO_TUNE to listOf(
            p("root", "Key Root (0=C)", 0f, 11f, 0f), p("strength", "Correction", 0f, 100f, 70f, "%"),
            p("speed", "Speed", 1f, 100f, 30f, "%")),
    )

    fun defaultParams(type: EffectType): Map<String, Float> =
        (PARAMS[type] ?: emptyList()).associate { it.key to it.default }

    fun create(type: EffectType, sampleRate: Int): AudioEffect {
        val fx: AudioEffect = when (type) {
            EffectType.THREE_BAND_EQ -> ThreeBandEq()
            EffectType.PARAMETRIC_EQ -> ParametricEq()
            EffectType.EIGHT_BAND_EQ -> EightBandEq()
            EffectType.HIGH_PASS -> SingleFilter(BiquadType.HIGH_PASS)
            EffectType.LOW_PASS -> SingleFilter(BiquadType.LOW_PASS)
            EffectType.COMPRESSOR -> Compressor()
            EffectType.LIMITER -> Limiter()
            EffectType.NOISE_GATE -> NoiseGate()
            EffectType.DE_ESSER -> DeEsser()
            EffectType.REVERB -> Reverb()
            EffectType.DELAY -> StereoDelay(pingPong = false)
            EffectType.PING_PONG_DELAY -> StereoDelay(pingPong = true)
            EffectType.CHORUS -> Chorus()
            EffectType.FLANGER -> Flanger()
            EffectType.PHASER -> Phaser()
            EffectType.TREMOLO -> Tremolo()
            EffectType.AUTO_PAN -> AutoPan()
            EffectType.DISTORTION -> Distortion()
            EffectType.SATURATION -> Saturation()
            EffectType.BIT_CRUSHER -> BitCrusher()
            EffectType.STEREO_WIDTH -> StereoWidth()
            EffectType.PITCH_SHIFT -> DelayPitchShifter()
            EffectType.AUTO_TUNE -> AutoTuneLive()
        }
        fx.prepare(sampleRate)
        fx.setParams(defaultParams(type))
        return fx
    }

    data class EffectPreset(val name: String, val params: Map<String, Float>)

    val PRESETS: Map<EffectType, List<EffectPreset>> = mapOf(
        EffectType.COMPRESSOR to listOf(
            EffectPreset("Vocal Control", mapOf("threshold" to -20f, "ratio" to 3f, "attack" to 15f, "release" to 180f, "makeup" to 4f, "knee" to 8f)),
            EffectPreset("Drum Punch", mapOf("threshold" to -16f, "ratio" to 6f, "attack" to 30f, "release" to 120f, "makeup" to 3f, "knee" to 2f)),
            EffectPreset("Gentle Glue", mapOf("threshold" to -14f, "ratio" to 1.8f, "attack" to 40f, "release" to 250f, "makeup" to 1.5f, "knee" to 12f)),
        ),
        EffectType.REVERB to listOf(
            EffectPreset("Small Room", mapOf("size" to 25f, "damping" to 60f, "width" to 60f, "predelay" to 5f)),
            EffectPreset("Studio Plate", mapOf("size" to 55f, "damping" to 40f, "width" to 85f, "predelay" to 15f)),
            EffectPreset("Concert Hall", mapOf("size" to 90f, "damping" to 35f, "width" to 95f, "predelay" to 40f)),
        ),
        EffectType.DELAY to listOf(
            EffectPreset("Slapback", mapOf("time" to 110f, "feedback" to 12f, "tone" to 4000f)),
            EffectPreset("Quarter @120", mapOf("time" to 500f, "feedback" to 35f, "tone" to 6000f)),
            EffectPreset("Dotted 8th @120", mapOf("time" to 375f, "feedback" to 45f, "tone" to 5500f)),
        ),
        EffectType.THREE_BAND_EQ to listOf(
            EffectPreset("Vocal Presence", mapOf("low" to -3f, "mid" to 2.5f, "high" to 4f, "lowFreq" to 120f, "highFreq" to 9000f)),
            EffectPreset("Bass Boost", mapOf("low" to 5f, "mid" to -1f, "high" to 1f, "lowFreq" to 100f, "highFreq" to 8000f)),
            EffectPreset("Telephone", mapOf("low" to -18f, "mid" to 6f, "high" to -14f, "lowFreq" to 400f, "highFreq" to 3000f)),
        ),
        EffectType.SATURATION to listOf(
            EffectPreset("Warm Tape", mapOf("drive" to 35f, "output" to -2f)),
            EffectPreset("Hot Console", mapOf("drive" to 65f, "output" to -5f)),
        ),
        EffectType.CHORUS to listOf(
            EffectPreset("Subtle Shimmer", mapOf("rate" to 0.8f, "depth" to 30f)),
            EffectPreset("80s Wide", mapOf("rate" to 1.8f, "depth" to 70f)),
        ),
    )
}

/** Base class: params map, sample rate, no-allocation processing. */
abstract class AudioEffect(val type: EffectType) {
    protected var sampleRate: Int = 44100
    protected val params = HashMap<String, Float>()

    open fun prepare(sampleRate: Int) {
        this.sampleRate = sampleRate
        onParamsChanged()
    }

    fun setParams(newParams: Map<String, Float>) {
        params.putAll(newParams)
        onParamsChanged()
    }

    fun setParam(key: String, value: Float) {
        params[key] = value
        onParamsChanged()
    }

    protected fun param(key: String, fallback: Float): Float = params[key] ?: fallback

    protected open fun onParamsChanged() {}
    abstract fun process(buffer: FloatArray, frames: Int)
    open fun reset() {}
}

/** Ordered chain with per-effect enable/wet. Scratch is preallocated. */
class EffectChain(maxFrames: Int = 4096) {
    class Slot(val effect: AudioEffect, var enabled: Boolean = true, var wet: Float = 1f)
    val slots = mutableListOf<Slot>()
    private var scratch = FloatArray(maxFrames * 2)

    fun ensureCapacity(frames: Int) {
        if (scratch.size < frames * 2) scratch = FloatArray(frames * 2)
    }

    fun process(buffer: FloatArray, frames: Int) {
        if (slots.isEmpty()) return
        ensureCapacity(frames)
        val n = minOf(frames * 2, minOf(buffer.size, scratch.size))
        for (slot in slots) {
            if (!slot.enabled) continue
            if (slot.wet >= 0.999f) {
                slot.effect.process(buffer, frames)
            } else if (slot.wet > 0.001f) {
                System.arraycopy(buffer, 0, scratch, 0, n)
                slot.effect.process(scratch, frames)
                val w = slot.wet; val d = 1f - w
                for (i in 0 until n) buffer[i] = buffer[i] * d + scratch[i] * w
            }
        }
    }

    fun reset() = slots.forEach { it.effect.reset() }
}

// ---------------------------------------------------------------- EQ / filters

class ThreeBandEq : AudioEffect(EffectType.THREE_BAND_EQ) {
    private val low = Biquad(); private val mid = Biquad(); private val high = Biquad()
    override fun onParamsChanged() {
        low.set(BiquadType.LOW_SHELF, param("lowFreq", 120f).toDouble(), 0.71, param("low", 0f).toDouble(), sampleRate)
        mid.set(BiquadType.PEAKING, 1000.0, 0.6, param("mid", 0f).toDouble(), sampleRate)
        high.set(BiquadType.HIGH_SHELF, param("highFreq", 8000f).toDouble(), 0.71, param("high", 0f).toDouble(), sampleRate)
    }
    override fun process(buffer: FloatArray, frames: Int) {
        low.processStereo(buffer, frames); mid.processStereo(buffer, frames); high.processStereo(buffer, frames)
    }
    override fun reset() { low.reset(); mid.reset(); high.reset() }
}

class ParametricEq : AudioEffect(EffectType.PARAMETRIC_EQ) {
    private val bands = Array(4) { Biquad() }
    override fun onParamsChanged() {
        val defFreq = floatArrayOf(100f, 500f, 2000f, 6000f)
        for (i in 0..3) {
            val b = i + 1
            bands[i].set(BiquadType.PEAKING, param("b${b}freq", defFreq[i]).toDouble(),
                param("b${b}q", 1f).toDouble(), param("b${b}gain", 0f).toDouble(), sampleRate)
        }
    }
    override fun process(buffer: FloatArray, frames: Int) = bands.forEach { it.processStereo(buffer, frames) }
    override fun reset() = bands.forEach { it.reset() }
}

class EightBandEq : AudioEffect(EffectType.EIGHT_BAND_EQ) {
    private val freqs = doubleArrayOf(60.0, 150.0, 400.0, 1000.0, 2400.0, 6000.0, 10000.0, 15000.0)
    private val bands = Array(8) { Biquad() }
    override fun onParamsChanged() {
        for (i in 0..7) bands[i].set(BiquadType.PEAKING, freqs[i], 1.1, param("band$i", 0f).toDouble(), sampleRate)
    }
    override fun process(buffer: FloatArray, frames: Int) = bands.forEach { it.processStereo(buffer, frames) }
    override fun reset() = bands.forEach { it.reset() }
}

class SingleFilter(private val biquadType: BiquadType) :
    AudioEffect(if (biquadType == BiquadType.HIGH_PASS) EffectType.HIGH_PASS else EffectType.LOW_PASS) {
    private val filter = Biquad()
    override fun onParamsChanged() {
        filter.set(biquadType, param("freq", if (biquadType == BiquadType.HIGH_PASS) 80f else 12000f).toDouble(),
            param("res", 0.71f).toDouble(), 0.0, sampleRate)
    }
    override fun process(buffer: FloatArray, frames: Int) = filter.processStereo(buffer, frames)
    override fun reset() = filter.reset()
}

// ---------------------------------------------------------------- dynamics

class Compressor : AudioEffect(EffectType.COMPRESSOR) {
    private var envelope = 0f
    private var attackCoef = 0f
    private var releaseCoef = 0f
    var lastGainReductionDb: Float = 0f
        private set

    override fun onParamsChanged() {
        attackCoef = exp(-1.0 / (sampleRate * param("attack", 10f) / 1000.0)).toFloat()
        releaseCoef = exp(-1.0 / (sampleRate * param("release", 150f) / 1000.0)).toFloat()
    }

    override fun process(buffer: FloatArray, frames: Int) {
        val thresholdDb = param("threshold", -18f)
        val ratio = max(1f, param("ratio", 4f))
        val kneeDb = param("knee", 6f)
        val makeup = LevelMath.dbToGain(param("makeup", 0f))
        val n = minOf(frames, buffer.size / 2)
        var gr = 0f
        for (f in 0 until n) {
            val i = f * 2
            val peak = max(abs(buffer[i]), abs(buffer[i + 1]))
            val coef = if (peak > envelope) attackCoef else releaseCoef
            envelope = peak + coef * (envelope - peak)
            val envDb = LevelMath.gainToDb(envelope)
            val over = envDb - thresholdDb
            val reductionDb = when {
                over <= -kneeDb / 2f -> 0f
                over >= kneeDb / 2f -> over * (1f - 1f / ratio)
                else -> {
                    val x = over + kneeDb / 2f
                    (1f - 1f / ratio) * x * x / (2f * max(kneeDb, 0.0001f))
                }
            }
            gr = reductionDb
            val g = LevelMath.dbToGain(-reductionDb) * makeup
            buffer[i] *= g; buffer[i + 1] *= g
        }
        lastGainReductionDb = gr
    }
    override fun reset() { envelope = 0f }
}

class Limiter : AudioEffect(EffectType.LIMITER) {
    private var gain = 1f
    private var releaseCoef = 0f
    override fun onParamsChanged() {
        releaseCoef = exp(-1.0 / (sampleRate * param("release", 100f) / 1000.0)).toFloat()
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val ceiling = LevelMath.dbToGain(param("ceiling", -0.3f))
        val input = LevelMath.dbToGain(param("input", 0f))
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            var l = buffer[i] * input
            var r = buffer[i + 1] * input
            val peak = max(abs(l), abs(r))
            val target = if (peak > ceiling && peak > 0f) ceiling / peak else 1f
            gain = if (target < gain) target else 1f + releaseCoef * (gain - 1f)
            l *= gain; r *= gain
            // absolute safety against inter-sample overs of the slew
            buffer[i] = l.coerceIn(-ceiling * 1.02f, ceiling * 1.02f)
            buffer[i + 1] = r.coerceIn(-ceiling * 1.02f, ceiling * 1.02f)
        }
    }
    override fun reset() { gain = 1f }
}

class NoiseGate : AudioEffect(EffectType.NOISE_GATE) {
    private var envelope = 0f
    private var gateGain = 0f
    private var attackCoef = 0f
    private var releaseCoef = 0f
    override fun onParamsChanged() {
        attackCoef = exp(-1.0 / (sampleRate * param("attack", 2f) / 1000.0)).toFloat()
        releaseCoef = exp(-1.0 / (sampleRate * param("release", 200f) / 1000.0)).toFloat()
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val threshold = LevelMath.dbToGain(param("threshold", -50f))
        val floor = LevelMath.dbToGain(-param("range", 40f))
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            val peak = max(abs(buffer[i]), abs(buffer[i + 1]))
            val coef = if (peak > envelope) attackCoef else releaseCoef
            envelope = peak + coef * (envelope - peak)
            val target = if (envelope >= threshold) 1f else floor
            gateGain += 0.002f * (target - gateGain) + (if (target > gateGain) 0.01f else 0f) * (target - gateGain)
            buffer[i] *= gateGain; buffer[i + 1] *= gateGain
        }
    }
    override fun reset() { envelope = 0f; gateGain = 0f }
}

class DeEsser : AudioEffect(EffectType.DE_ESSER) {
    private val band = Biquad()
    private var envelope = 0f
    private var coef = 0f
    // separate state copy for sidechain: reuse filter on copy of sample via small buffers
    private val sideL = FloatArray(1); private val sideR = FloatArray(1)
    override fun onParamsChanged() {
        band.set(BiquadType.BAND_PASS, param("freq", 6500f).toDouble(), 0.9, 0.0, sampleRate)
        coef = exp(-1.0 / (sampleRate * 0.004)).toFloat()
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val amount = param("amount", 40f) / 100f
        val threshold = LevelMath.dbToGain(param("threshold", -20f))
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            sideL[0] = buffer[i]; sideR[0] = buffer[i + 1]
            val sibL = band.processSample(sideL[0])
            // approximate stereo sidechain with left channel detection
            val peak = abs(sibL)
            envelope = if (peak > envelope) peak else peak + coef * (envelope - peak)
            val over = (envelope - threshold).coerceAtLeast(0f)
            val reduction = (over * 4f).coerceIn(0f, 0.85f) * amount
            buffer[i] -= sibL * reduction
            buffer[i + 1] -= sibL * reduction * 0.0f // right keeps its own content; detection is mono-summed below
            // apply matched broadband dip on right for image stability
            buffer[i + 1] *= (1f - reduction * 0.5f)
            buffer[i] *= (1f - reduction * 0.5f)
            sideR[0] = buffer[i + 1]
        }
    }
    override fun reset() { band.reset(); envelope = 0f }
}

// ---------------------------------------------------------------- drive / stereo / mod-lite

class Distortion : AudioEffect(EffectType.DISTORTION) {
    private val tone = Biquad()
    override fun onParamsChanged() {
        tone.set(BiquadType.LOW_PASS, param("tone", 5000f).toDouble(), 0.5, 0.0, sampleRate)
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val drive = 1f + param("drive", 40f) / 100f * 24f
        val out = LevelMath.dbToGain(param("output", -6f))
        val n = minOf(frames * 2, buffer.size)
        for (i in 0 until n) {
            val x = buffer[i] * drive
            buffer[i] = (x / (1f + abs(x))) * 1.4f * out // soft-clip waveshaper
        }
        tone.processStereo(buffer, frames)
    }
    override fun reset() = tone.reset()
}

class Saturation : AudioEffect(EffectType.SATURATION) {
    override fun process(buffer: FloatArray, frames: Int) {
        val drive = 1f + param("drive", 30f) / 100f * 3f
        val out = LevelMath.dbToGain(param("output", 0f))
        val norm = tanh(drive.toDouble()).toFloat()
        val n = minOf(frames * 2, buffer.size)
        for (i in 0 until n) buffer[i] = tanh((buffer[i] * drive).toDouble()).toFloat() / max(norm, 0.01f) * out
    }
}

class BitCrusher : AudioEffect(EffectType.BIT_CRUSHER) {
    private var holdL = 0f; private var holdR = 0f; private var counter = 0
    override fun process(buffer: FloatArray, frames: Int) {
        val bits = param("bits", 12f).toInt().coerceIn(1, 16)
        val levels = (1 shl bits).toFloat()
        val step = 2f / levels
        val down = param("rate", 4f).toInt().coerceAtLeast(1)
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            if (counter <= 0) {
                holdL = (kotlin.math.floor(buffer[i] / step) * step).toFloat()
                holdR = (kotlin.math.floor(buffer[i + 1] / step) * step).toFloat()
                counter = down
            }
            counter--
            buffer[i] = holdL; buffer[i + 1] = holdR
        }
    }
    override fun reset() { counter = 0; holdL = 0f; holdR = 0f }
}

class StereoWidth : AudioEffect(EffectType.STEREO_WIDTH) {
    private val bassLowL = OnePoleLowPass(); private val bassLowR = OnePoleLowPass()
    override fun onParamsChanged() {
        bassLowL.setCutoff(param("bassMono", 120f).toDouble(), sampleRate)
        bassLowR.setCutoff(param("bassMono", 120f).toDouble(), sampleRate)
    }
    override fun process(buffer: FloatArray, frames: Int) {
        val width = param("width", 100f) / 100f
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val i = f * 2
            val l = buffer[i]; val r = buffer[i + 1]
            val lowL = bassLowL.process(l); val lowR = bassLowR.process(r)
            val lowMono = (lowL + lowR) * 0.5f
            val hiL = l - lowL; val hiR = r - lowR
            val mid = (hiL + hiR) * 0.5f
            val side = (hiL - hiR) * 0.5f * width
            buffer[i] = lowMono + mid + side
            buffer[i + 1] = lowMono + mid - side
        }
    }
    override fun reset() { bassLowL.reset(); bassLowR.reset() }
}

class Tremolo : AudioEffect(EffectType.TREMOLO) {
    private var phase = 0.0
    override fun process(buffer: FloatArray, frames: Int) {
        val rate = param("rate", 5f).toDouble()
        val depth = param("depth", 50f) / 100f
        val inc = 2.0 * Math.PI * rate / sampleRate
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val g = 1f - depth * (0.5f - 0.5f * kotlin.math.cos(phase).toFloat())
            buffer[f * 2] *= g; buffer[f * 2 + 1] *= g
            phase += inc
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
        }
    }
    override fun reset() { phase = 0.0 }
}

class AutoPan : AudioEffect(EffectType.AUTO_PAN) {
    private var phase = 0.0
    override fun process(buffer: FloatArray, frames: Int) {
        val rate = param("rate", 1f).toDouble()
        val depth = param("depth", 60f) / 100f
        val inc = 2.0 * Math.PI * rate / sampleRate
        val n = minOf(frames, buffer.size / 2)
        for (f in 0 until n) {
            val pan = (kotlin.math.sin(phase) * depth).toFloat()
            val (gl, gr) = LevelMath.panGains(pan)
            val mono = (buffer[f * 2] + buffer[f * 2 + 1]) * 0.5f
            // pan the stereo image by cross-blending toward the panned mono position
            buffer[f * 2] = buffer[f * 2] * (1f - depth * 0.5f) + mono * gl * depth
            buffer[f * 2 + 1] = buffer[f * 2 + 1] * (1f - depth * 0.5f) + mono * gr * depth
            phase += inc
            if (phase > 2.0 * Math.PI) phase -= 2.0 * Math.PI
        }
    }
    override fun reset() { phase = 0.0 }
}

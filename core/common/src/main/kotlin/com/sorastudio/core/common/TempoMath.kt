package com.sorastudio.core.common

import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Beat/sample/second conversions and level math.
 *
 * The whole DAW timeline is measured in *beats* (quarter notes). Audio positions
 * are derived from beats + BPM + sample rate, never the other way around, so tempo
 * edits never move musical content.
 */
object TempoMath {

    fun secondsPerBeat(bpm: Double): Double = 60.0 / bpm.coerceIn(20.0, 400.0)

    fun beatsToSeconds(beats: Double, bpm: Double): Double = beats * secondsPerBeat(bpm)

    fun secondsToBeats(seconds: Double, bpm: Double): Double = seconds / secondsPerBeat(bpm)

    fun beatsToSamples(beats: Double, bpm: Double, sampleRate: Int): Long =
        (beatsToSeconds(beats, bpm) * sampleRate).toLong()

    fun samplesToBeats(samples: Long, bpm: Double, sampleRate: Int): Double =
        secondsToBeats(samples.toDouble() / sampleRate, bpm)

    /** Number of samples in one beat. */
    fun samplesPerBeat(bpm: Double, sampleRate: Int): Double = secondsPerBeat(bpm) * sampleRate

    /** Beat position of a step index in a step sequencer (16 steps = 4 beats by default). */
    fun stepToBeat(step: Int, stepsPerBeat: Int = 4): Double = step.toDouble() / stepsPerBeat

    fun barLengthBeats(beatsPerBar: Int): Double = beatsPerBar.toDouble()

    fun formatBarsBeats(positionBeats: Double, beatsPerBar: Int): String {
        val safeBeatsPerBar = beatsPerBar.coerceAtLeast(1)
        val bar = (positionBeats / safeBeatsPerBar).toInt() + 1
        val beat = (positionBeats % safeBeatsPerBar).toInt() + 1
        val ticks = ((positionBeats % 1.0) * 100).toInt()
        return "%d.%d.%02d".format(bar, beat, ticks)
    }

    fun formatTime(positionBeats: Double, bpm: Double): String {
        val totalSeconds = beatsToSeconds(positionBeats, bpm)
        val m = (totalSeconds / 60).toInt()
        val s = (totalSeconds % 60).toInt()
        val cs = ((totalSeconds % 1.0) * 100).toInt()
        return "%d:%02d.%02d".format(m, s, cs)
    }

    /** Snap a beat position to a grid (e.g. gridBeats = 0.25 = 16th notes). */
    fun snap(beats: Double, gridBeats: Double, enabled: Boolean = true): Double {
        if (!enabled || gridBeats <= 0.0) return beats
        return kotlin.math.round(beats / gridBeats) * gridBeats
    }
}

/** Decibel / gain / pan conversions used by the mixer and meters. */
object LevelMath {
    const val SILENCE_DB: Float = -90f

    fun dbToGain(db: Float): Float = if (db <= SILENCE_DB) 0f else 10.0.pow(db / 20.0).toFloat()

    fun gainToDb(gain: Float): Float {
        val g = abs(gain)
        return if (g <= 0.0000316f) SILENCE_DB else (20.0 * log10(g.toDouble())).toFloat()
    }

    /** Convert a 0..1 fader position to dB using a musically useful taper (-60..+6 dB). */
    fun faderToDb(position: Float): Float {
        val p = position.coerceIn(0f, 1f)
        if (p <= 0.001f) return SILENCE_DB
        // Quadratic taper: 0.75 position == unity (0 dB)
        val db = (p * p) * 66f - 60f
        return db.coerceIn(-60f, 6f)
    }

    fun dbToFader(db: Float): Float {
        if (db <= -60f) return 0f
        return sqrt(((db.coerceIn(-60f, 6f) + 60f) / 66f).toDouble()).toFloat().coerceIn(0f, 1f)
    }

    /** Constant-power pan law. [pan] is -1 (left) .. +1 (right). Returns (left, right) gains. */
    fun panGains(pan: Float): Pair<Float, Float> {
        val p = pan.coerceIn(-1f, 1f)
        val angle = (p + 1f) * (Math.PI / 4.0) // 0..PI/2
        return kotlin.math.cos(angle).toFloat() to kotlin.math.sin(angle).toFloat()
    }

    /** Semitones -> playback rate ratio (for pitch shifting / sampler transposition). */
    fun semitonesToRatio(semitones: Double): Double = 2.0.pow(semitones / 12.0)

    fun ratioToSemitones(ratio: Double): Double = if (ratio <= 0.0) 0.0 else 12.0 * kotlin.math.log2(ratio)

    fun centsBetween(freqA: Double, freqB: Double): Double {
        if (freqA <= 0.0 || freqB <= 0.0) return 0.0
        return 1200.0 * kotlin.math.log2(freqB / freqA)
    }
}

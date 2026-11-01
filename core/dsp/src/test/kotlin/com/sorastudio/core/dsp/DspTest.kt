package com.sorastudio.core.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DspTest {
    private val sr = 44100

    private fun sine(freq: Double, seconds: Double): FloatArray {
        val frames = (sr * seconds).toInt()
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val v = sin(2.0 * PI * freq * i / sr).toFloat() * 0.8f
            out[i * 2] = v; out[i * 2 + 1] = v
        }
        return out
    }

    @Test
    fun `lowpass attenuates high frequencies more than low`() {
        val lpLow = Biquad().apply { set(BiquadType.LOW_PASS, 1000.0, 0.71, 0.0, sr) }
        val bufLow = sine(100.0, 0.2)
        lpLow.processStereo(bufLow, bufLow.size / 2)
        val rmsLow = AudioBufferOps.rms(bufLow, bufLow.size / 2)

        val lpHigh = Biquad().apply { set(BiquadType.LOW_PASS, 1000.0, 0.71, 0.0, sr) }
        val bufHigh = sine(10000.0, 0.2)
        lpHigh.processStereo(bufHigh, bufHigh.size / 2)
        val rmsHigh = AudioBufferOps.rms(bufHigh, bufHigh.size / 2)

        assertTrue("100Hz should pass ($rmsLow) much better than 10kHz ($rmsHigh)", rmsLow > rmsHigh * 4)
    }

    @Test
    fun `compressor reduces loud signal`() {
        val comp = EffectCatalog.create(EffectType.COMPRESSOR, sr)
        comp.setParams(mapOf("threshold" to -12f, "ratio" to 8f, "attack" to 1f, "release" to 100f, "makeup" to 0f, "knee" to 0f))
        val buf = sine(440.0, 0.5)
        val before = AudioBufferOps.rms(buf, buf.size / 2)
        // process in blocks to let envelope settle
        val block = 1024
        var f = 0
        val work = buf.copyOf()
        val chunk = FloatArray(block * 2)
        while (f + block <= work.size / 2) {
            System.arraycopy(work, f * 2, chunk, 0, block * 2)
            comp.process(chunk, block)
            System.arraycopy(chunk, 0, work, f * 2, block * 2)
            f += block
        }
        // measure second half only (envelope settled)
        val tail = work.copyOfRange(work.size / 2, work.size)
        val after = AudioBufferOps.rms(tail, tail.size / 2)
        assertTrue("compressed RMS ($after) should be below original ($before)", after < before * 0.75f)
    }

    @Test
    fun `limiter never exceeds ceiling by much`() {
        val limiter = EffectCatalog.create(EffectType.LIMITER, sr)
        limiter.setParams(mapOf("ceiling" to -6f, "release" to 100f, "input" to 12f))
        val buf = sine(220.0, 0.3)
        limiter.process(buf, buf.size / 2)
        val peak = AudioBufferOps.peak(buf, buf.size / 2)
        assertTrue("peak $peak should stay near -6dB (0.5)", peak <= 0.53f)
    }

    @Test
    fun `wav write-read round trip preserves audio`() {
        val file = File.createTempFile("sora-test", ".wav")
        try {
            val original = sine(440.0, 0.25)
            WavCodec.write(file, original, sr, 2, WavBitDepth.PCM16)
            val decoded = WavCodec.read(file)
            assertEquals(sr, decoded.sampleRate)
            assertEquals(2, decoded.channels)
            assertEquals(original.size, decoded.samples.size)
            var maxErr = 0f
            for (i in original.indices step 37) {
                maxErr = maxOf(maxErr, abs(original[i] - decoded.samples[i]))
            }
            assertTrue("16-bit round trip error $maxErr too large", maxErr < 0.001f)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `drum synth produces non silent finite samples`() {
        for (type in DrumType.values()) {
            val sample = DrumSynth.render(type, sr)
            assertTrue("$type empty", sample.isNotEmpty())
            val peak = AudioBufferOps.peakOfAll(sample)
            assertTrue("$type peak $peak out of range", peak in 0.1f..1.05f)
            assertTrue("$type has NaN", sample.none { it.isNaN() || it.isInfinite() })
        }
    }

    @Test
    fun `synth note renders audio at expected pitch region`() {
        val synth = PolySynth()
        synth.sampleRate = sr
        synth.patch = SynthPatch(osc1 = Waveform.SINE, cutoffHz = 18000f, sustain = 0.9f)
        synth.noteOn(69, 0.9f) // A4 440Hz
        val frames = 8192
        val buf = FloatArray(frames * 2)
        synth.renderAdd(buf, frames, 1f, 1f)
        val peak = AudioBufferOps.peak(buf, frames)
        assertTrue("synth peak $peak too low", peak > 0.15f)
        val mono = FloatArray(frames)
        AudioBufferOps.toMono(buf, mono, frames)
        val freq = PitchDetector.detect(mono.copyOfRange(2048, 6144), 4096, sr)
        assertTrue("detected $freq Hz, expected ~440", abs(freq - 440.0) < 15.0)
    }

    @Test
    fun `pitch shift up an octave doubles detected frequency`() {
        val frames = sr / 2
        val mono = FloatArray(frames) { i -> sin(2.0 * PI * 220.0 * i / sr).toFloat() * 0.8f }
        val shifted = TimePitch.pitchShiftMono(mono, 12.0)
        val start = minOf(4096, shifted.size / 4)
        val window = shifted.copyOfRange(start, minOf(shifted.size, start + 4096))
        val freq = PitchDetector.detect(window, window.size, sr)
        assertTrue("detected $freq Hz, expected ~440", freq > 0 && abs(freq - 440.0) < 40.0)
    }

    @Test
    fun `time stretch changes duration`() {
        val mono = FloatArray(sr / 2) { i -> sin(2.0 * PI * 330.0 * i / sr).toFloat() * 0.5f }
        val stretched = TimePitch.timeStretchMono(mono, 1.5)
        assertTrue("expected ~1.5x length, got ${stretched.size} vs ${mono.size}",
            stretched.size > (mono.size * 1.35).toInt() && stretched.size < (mono.size * 1.65).toInt())
    }

    @Test
    fun `noise gate silences quiet signal`() {
        val gate = EffectCatalog.create(EffectType.NOISE_GATE, sr)
        gate.setParams(mapOf("threshold" to -30f))
        val frames = 8192
        val buf = FloatArray(frames * 2) { 0.001f } // -60dB constant
        repeat(4) { gate.process(buf, frames) }
        val rms = AudioBufferOps.rms(buf, frames)
        assertTrue("gate should push quiet signal down, rms=$rms", rms < 0.0006f)
    }

    @Test
    fun `normalize reaches target peak`() {
        val data = FloatArray(1000) { 0.05f }
        val normalized = AudioEditOps.normalize(data, 0.9f)
        assertEquals(0.9f, AudioBufferOps.peakOfAll(normalized), 0.001f)
        assertEquals(0.05f, AudioBufferOps.peakOfAll(data), 0.0001f) // original untouched
    }

    @Test
    fun `reverb produces a decay tail after input stops`() {
        val reverb = EffectCatalog.create(EffectType.REVERB, sr)
        reverb.setParams(mapOf("size" to 80f, "damping" to 30f))
        val frames = sr / 2
        val buf = FloatArray(frames * 2)
        buf[0] = 0.9f; buf[1] = 0.9f // impulse
        reverb.process(buf, frames)
        val tailStart = frames / 4
        val tailPeak = AudioBufferOps.peak(buf.copyOfRange(tailStart * 2, frames * 2), frames - tailStart)
        assertTrue("reverb tail should still be audible, peak=$tailPeak", tailPeak > 0.001f)
        assertTrue("no NaN in reverb", buf.none { it.isNaN() })
    }
}

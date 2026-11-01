package com.sorastudio.core.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MusicTest {

    @Test
    fun `A4 is 440 Hz and octaves double`() {
        assertEquals(440.0, NoteUtils.midiToFreq(69), 0.001)
        assertEquals(880.0, NoteUtils.midiToFreq(81), 0.001)
        assertEquals(220.0, NoteUtils.midiToFreq(57), 0.001)
        assertEquals(69, NoteUtils.freqToMidi(440.0))
        assertEquals(60, NoteUtils.freqToMidi(261.63))
    }

    @Test
    fun `note names are correct`() {
        assertEquals("C4", NoteUtils.noteName(60))
        assertEquals("A4", NoteUtils.noteName(69))
        assertEquals("C#5", NoteUtils.noteName(73))
        assertTrue(NoteUtils.isBlackKey(61))
        assertFalse(NoteUtils.isBlackKey(60))
    }

    @Test
    fun `scale membership and snapping`() {
        val cMajor = ScaleType.MAJOR
        assertTrue(cMajor.contains(0, 60))
        assertFalse(cMajor.contains(0, 61))
        assertEquals(62, cMajor.snap(0, 61)) // C# -> D (ties upward)
        assertEquals(60, cMajor.snap(0, 60))
        // A natural minor: root 9
        assertTrue(ScaleType.NATURAL_MINOR.contains(9, 69))
        assertFalse(ScaleType.NATURAL_MINOR.contains(9, 70))
    }

    @Test
    fun `tempo conversions round trip`() {
        val samples = TempoMath.beatsToSamples(4.0, 120.0, 44100)
        assertEquals(88200L, samples) // 2 seconds at 120bpm
        assertEquals(4.0, TempoMath.samplesToBeats(samples, 120.0, 44100), 0.0001)
        assertEquals(0.5, TempoMath.secondsPerBeat(120.0), 0.0001)
        assertEquals(1.0, TempoMath.secondsPerBeat(60.0), 0.0001)
    }

    @Test
    fun `snap aligns to grid`() {
        assertEquals(1.0, TempoMath.snap(0.97, 0.25), 0.0001)
        assertEquals(0.97, TempoMath.snap(0.97, 0.25, enabled = false), 0.0001)
    }

    @Test
    fun `db and fader conversions`() {
        assertEquals(1f, LevelMath.dbToGain(0f), 0.0001f)
        assertEquals(0f, LevelMath.gainToDb(1f), 0.01f)
        assertEquals(-6.02f, LevelMath.gainToDb(0.5f), 0.05f)
        val db = LevelMath.faderToDb(0.75f)
        assertEquals(0.75f, LevelMath.dbToFader(db), 0.02f)
    }

    @Test
    fun `constant power pan preserves power`() {
        val (l, r) = LevelMath.panGains(0f)
        assertEquals(1.0, (l * l + r * r).toDouble(), 0.001)
        val (lFull, rFull) = LevelMath.panGains(1f)
        assertEquals(0f, lFull, 0.001f)
        assertEquals(1f, rFull, 0.001f)
    }

    @Test
    fun `chord notes`() {
        assertEquals(listOf(60, 64, 67), ChordQuality.MAJOR.notes(60))
        assertEquals(listOf(57, 60, 64), ChordQuality.MINOR.notes(57))
        assertEquals("Am", ChordQuality.MINOR.label(57))
    }
}

package com.sorastudio.core.midi

import com.sorastudio.core.common.Progressions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MidiTest {

    @Test
    fun `scheduler emits note on and off inside window with looping`() {
        val notes = listOf(MidiNote(startBeats = 0.0, lengthBeats = 1.0, pitch = 60))
        val first = MidiScheduler.eventsBetween(notes, 4.0, 0.0, 0.5)
        assertEquals(1, first.size)
        assertTrue(first[0] is ScheduledEvent.NoteOn)
        // second loop iteration
        val secondLoop = MidiScheduler.eventsBetween(notes, 4.0, 4.0, 4.5)
        assertEquals(1, secondLoop.size)
        assertEquals(4.0, secondLoop[0].atBeats, 0.0001)
        val offs = MidiScheduler.eventsBetween(notes, 4.0, 0.9, 1.1)
        assertEquals(1, offs.size)
        assertTrue(offs[0] is ScheduledEvent.NoteOff)
    }

    @Test
    fun `quantize snaps starts to grid`() {
        val notes = listOf(MidiNote(startBeats = 0.13, lengthBeats = 0.4, pitch = 62))
        val quantized = MidiTransform.quantize(notes, 0.25)
        assertEquals(0.25, quantized[0].startBeats, 0.0001)
        val half = MidiTransform.quantize(notes, 0.25, strength = 0.5f)
        assertEquals(0.19, half[0].startBeats, 0.001)
    }

    @Test
    fun `humanize keeps notes in valid ranges`() {
        val notes = (0..20).map { MidiNote(startBeats = it * 0.25, lengthBeats = 0.2, pitch = 60, velocity = 100) }
        val humanized = MidiTransform.humanize(notes, 0.05, 0.3f, Random(42))
        assertTrue(humanized.all { it.velocity in 1..127 && it.startBeats >= 0.0 })
        assertTrue(humanized.zip(notes).any { (a, b) -> a.startBeats != b.startBeats })
    }

    @Test
    fun `arpeggiator generates expected count and pattern`() {
        val arp = Arpeggiator.generate(listOf(60, 64, 67), ArpPattern.UP, rateBeats = 0.25, totalBeats = 4.0)
        assertEquals(16, arp.size)
        assertEquals(60, arp[0].pitch)
        assertEquals(64, arp[1].pitch)
        assertEquals(67, arp[2].pitch)
        assertEquals(60, arp[3].pitch)
    }

    @Test
    fun `chord progression produces chords of right size`() {
        val notes = ChordGenerator.progressionNotes(60, Progressions.ALL.first(), beatsPerChord = 4.0)
        assertEquals(12, notes.size) // 4 chords x 3 notes
        assertTrue(notes.all { it.pitch in 0..127 })
    }

    @Test
    fun `midi file round trip`() {
        val notes = listOf(
            MidiNote(startBeats = 0.0, lengthBeats = 1.0, pitch = 60, velocity = 90),
            MidiNote(startBeats = 1.0, lengthBeats = 0.5, pitch = 64, velocity = 80),
            MidiNote(startBeats = 2.5, lengthBeats = 1.5, pitch = 67, velocity = 100),
        )
        val bytes = MidiFileIO.toBytes(128.0, listOf("Lead" to notes))
        val imported = MidiFileIO.read(bytes)
        assertEquals(128.0, imported.bpm, 0.6)
        assertEquals(1, imported.tracks.size)
        assertEquals("Lead", imported.tracks[0].name)
        assertEquals(3, imported.tracks[0].notes.size)
        assertEquals(60, imported.tracks[0].notes[0].pitch)
        assertEquals(2.5, imported.tracks[0].notes[2].startBeats, 0.01)
        assertEquals(1.5, imported.tracks[0].notes[2].lengthBeats, 0.01)
    }

    @Test
    fun `transpose clamps to midi range`() {
        val notes = listOf(MidiNote(startBeats = 0.0, lengthBeats = 1.0, pitch = 120))
        assertEquals(127, MidiTransform.transpose(notes, 12)[0].pitch)
        assertEquals(0, MidiTransform.transpose(notes, -200)[0].pitch)
    }
}

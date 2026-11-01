package com.sorastudio.core.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectTest {

    @Test
    fun `project json round trip preserves everything`() {
        val original = ProjectFactory.create("beat-starter", "Test Beat")
        val json = ProjectCodec.encode(original)
        val decoded = ProjectCodec.decode(json).getOrThrow()
        assertEquals(original.name, decoded.name)
        assertEquals(original.bpm, decoded.bpm, 0.0)
        assertEquals(original.tracks.size, decoded.tracks.size)
        assertEquals(original.clips.size, decoded.clips.size)
        assertEquals(original.patterns.size, decoded.patterns.size)
        assertEquals(original.clips.sumOf { it.notes.size }, decoded.clips.sumOf { it.notes.size })
        assertEquals(original, decoded)
    }

    @Test
    fun `templates produce playable content`() {
        for (template in ProjectFactory.TEMPLATES) {
            val project = ProjectFactory.create(template.id, "X")
            assertTrue("${template.id} has tracks", project.tracks.isNotEmpty())
            assertTrue("${template.id} bpm sane", project.bpm in 40.0..240.0)
        }
        val beat = ProjectFactory.create("beat-starter", "X")
        assertTrue(beat.clips.any { it.type == ClipType.PATTERN })
        assertTrue(beat.clips.any { it.notes.isNotEmpty() })
    }

    @Test
    fun `split clip partitions notes`() {
        var project = ProjectFactory.create("empty", "X")
        val trackId = project.tracks.first { it.type == TrackType.INSTRUMENT }.id
        val clip = SClip(
            trackId = trackId, type = ClipType.MIDI, startBeats = 0.0, lengthBeats = 4.0,
            notes = listOf(
                SNote(startBeats = 0.0, lengthBeats = 1.0, pitch = 60),
                SNote(startBeats = 2.0, lengthBeats = 1.0, pitch = 62),
                SNote(startBeats = 3.0, lengthBeats = 0.5, pitch = 64),
            )
        )
        project = ProjectOps.addClip(project, clip)
        project = ProjectOps.splitClip(project, clip.id, 2.0)
        assertEquals(2, project.clips.size)
        val left = project.clips.first { it.startBeats == 0.0 }
        val right = project.clips.first { it.startBeats == 2.0 }
        assertEquals(2.0, left.lengthBeats, 0.0001)
        assertEquals(2.0, right.lengthBeats, 0.0001)
        assertEquals(2.0, right.contentOffsetBeats, 0.0001)
        assertEquals(1, left.notes.size)
        assertEquals(2, right.notes.size)
    }

    @Test
    fun `undo and redo restore edits`() {
        val session = ProjectSession(ProjectFactory.create("empty", "Undo Test"))
        val initialTracks = session.current.tracks.size
        assertFalse(session.canUndo.value)
        session.update { ProjectOps.addTrack(it, TrackType.AUDIO).first }
        assertEquals(initialTracks + 1, session.current.tracks.size)
        assertTrue(session.canUndo.value)
        session.undo()
        assertEquals(initialTracks, session.current.tracks.size)
        assertTrue(session.canRedo.value)
        session.redo()
        assertEquals(initialTracks + 1, session.current.tracks.size)
    }

    @Test
    fun `duplicate track copies clips with new ids`() {
        var project = ProjectFactory.create("song-starter", "X")
        val piano = project.tracks.first { it.name == "Piano" }
        val before = project.clips.size
        project = ProjectOps.duplicateTrack(project, piano.id)
        assertTrue(project.clips.size > before)
        val ids = project.clips.map { it.id }
        assertEquals("clip ids must be unique", ids.size, ids.distinct().size)
    }

    @Test
    fun `automation interpolation`() {
        val lane = SAutomationLane(
            trackId = "t1", target = "volume", minValue = -60f, maxValue = 6f,
            curve = AutomationCurve.LINEAR,
            points = listOf(SAutomationPoint(0.0, -12f), SAutomationPoint(4.0, 0f))
        )
        assertEquals(-12f, lane.valueAt(0.0)!!, 0.001f)
        assertEquals(-6f, lane.valueAt(2.0)!!, 0.001f)
        assertEquals(0f, lane.valueAt(4.0)!!, 0.001f)
        assertEquals(0f, lane.valueAt(9.0)!!, 0.001f)
    }

    @Test
    fun `drum pattern length math`() {
        val p16 = SDrumPattern.emptyPattern(steps = 16)
        assertEquals(4.0, p16.lengthBeats, 0.0001)
        val p32 = SDrumPattern.emptyPattern(steps = 32)
        assertEquals(4.0, p32.lengthBeats, 0.0001) // 32 steps at 8 steps per beat
    }

    @Test
    fun `delete track also deletes its clips`() {
        var project = ProjectFactory.create("beat-starter", "X")
        val drums = project.tracks.first { it.type == TrackType.DRUM }
        project = ProjectOps.deleteTrack(project, drums.id)
        assertTrue(project.clips.none { it.trackId == drums.id })
        assertTrue(project.tracks.none { it.id == drums.id })
    }
}

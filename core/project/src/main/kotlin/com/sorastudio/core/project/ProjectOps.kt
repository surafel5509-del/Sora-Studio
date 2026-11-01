package com.sorastudio.core.project

import com.sorastudio.core.common.newId
import com.sorastudio.core.midi.MidiNote

/** Conversions between the wire model (S*) and the MIDI domain model. */
fun SNote.toMidi(): MidiNote = MidiNote(id, startBeats, lengthBeats, pitch, velocity)
fun MidiNote.toS(): SNote = SNote(id, startBeats, lengthBeats, pitch, velocity)

/**
 * Pure project editing operations. Every function returns a new [SProject];
 * the session wraps them with undo bookkeeping. Kept side-effect free so the
 * arrangement editor, piano roll and tests all share one implementation.
 */
object ProjectOps {

    fun touch(project: SProject): SProject = project.copy(updatedAtEpochMs = System.currentTimeMillis())

    // ------------------------------------------------------------- tracks

    fun addTrack(project: SProject, type: TrackType, name: String? = null): Pair<SProject, STrack> {
        val index = project.tracks.size
        val trackName = name ?: defaultTrackName(project, type)
        val track = STrack(
            name = trackName,
            type = type,
            colorArgb = TrackColors.forIndex(index),
            presetId = when (type) {
                TrackType.INSTRUMENT -> "piano-grand"
                else -> "piano-grand"
            }
        )
        return touch(project.copy(tracks = project.tracks + track)) to track
    }

    fun defaultTrackName(project: SProject, type: TrackType): String {
        val base = when (type) {
            TrackType.AUDIO -> "Audio"
            TrackType.VOCAL -> "Vocal"
            TrackType.INSTRUMENT -> "Instrument"
            TrackType.DRUM -> "Drums"
            TrackType.SAMPLE -> "Sampler"
        }
        val count = project.tracks.count { it.type == type }
        return if (count == 0) base else "$base ${count + 1}"
    }

    fun deleteTrack(project: SProject, trackId: String): SProject = touch(
        project.copy(
            tracks = project.tracks.filterNot { it.id == trackId },
            clips = project.clips.filterNot { it.trackId == trackId },
            automation = project.automation.filterNot { it.trackId == trackId }
        )
    )

    fun duplicateTrack(project: SProject, trackId: String): SProject {
        val original = project.trackById(trackId) ?: return project
        val newTrackId = newId("trk")
        val copy = original.copy(
            id = newTrackId,
            name = original.name + " Copy",
            fxChain = original.fxChain.map { it.copy(id = newId("fx")) }
        )
        val newClips = project.clipsForTrack(trackId).map { clip ->
            clip.copy(
                id = newId("clip"),
                trackId = newTrackId,
                notes = clip.notes.map { it.copy(id = newId("note")) }
            )
        }
        val tracks = project.tracks.toMutableList()
        val idx = tracks.indexOfFirst { it.id == trackId }
        tracks.add(idx + 1, copy)
        return touch(project.copy(tracks = tracks, clips = project.clips + newClips))
    }

    fun updateTrack(project: SProject, trackId: String, transform: (STrack) -> STrack): SProject =
        touch(project.copy(tracks = project.tracks.map { if (it.id == trackId) transform(it) else it }))

    fun moveTrack(project: SProject, trackId: String, newIndex: Int): SProject {
        val tracks = project.tracks.toMutableList()
        val from = tracks.indexOfFirst { it.id == trackId }
        if (from < 0) return project
        val track = tracks.removeAt(from)
        tracks.add(newIndex.coerceIn(0, tracks.size), track)
        return touch(project.copy(tracks = tracks))
    }

    // ------------------------------------------------------------- clips

    fun addClip(project: SProject, clip: SClip): SProject =
        touch(project.copy(clips = project.clips + clip))

    fun deleteClip(project: SProject, clipId: String): SProject =
        touch(project.copy(clips = project.clips.filterNot { it.id == clipId }))

    fun duplicateClip(project: SProject, clipId: String, atEnd: Boolean = true): SProject {
        val clip = project.clipById(clipId) ?: return project
        val copy = clip.copy(
            id = newId("clip"),
            startBeats = if (atEnd) clip.endBeats else clip.startBeats,
            notes = clip.notes.map { it.copy(id = newId("note")) }
        )
        return addClip(project, copy)
    }

    fun updateClip(project: SProject, clipId: String, transform: (SClip) -> SClip): SProject =
        touch(project.copy(clips = project.clips.map { if (it.id == clipId) transform(it) else it }))

    fun moveClip(project: SProject, clipId: String, newStartBeats: Double, newTrackId: String? = null): SProject =
        updateClip(project, clipId) {
            it.copy(startBeats = newStartBeats.coerceAtLeast(0.0), trackId = newTrackId ?: it.trackId)
        }

    /**
     * Split a clip at [atBeats] (absolute timeline position). MIDI notes are
     * partitioned; notes crossing the split are shortened on the left and
     * continued (from content offset) on the right.
     */
    fun splitClip(project: SProject, clipId: String, atBeats: Double): SProject {
        val clip = project.clipById(clipId) ?: return project
        if (atBeats <= clip.startBeats || atBeats >= clip.endBeats) return project
        val splitLocal = atBeats - clip.startBeats // in clip-view beats
        val leftLength = splitLocal
        val rightLength = clip.lengthBeats - splitLocal

        val leftNotes = mutableListOf<SNote>()
        val rightNotes = mutableListOf<SNote>()
        for (note in clip.notes) {
            val noteViewStart = note.startBeats - clip.contentOffsetBeats
            val noteViewEnd = note.endBeats - clip.contentOffsetBeats
            if (noteViewEnd <= splitLocal) {
                leftNotes.add(note)
            } else if (noteViewStart >= splitLocal) {
                rightNotes.add(note)
            } else {
                // crossing note
                leftNotes.add(note.copy(lengthBeats = (clip.contentOffsetBeats + splitLocal) - note.startBeats))
                rightNotes.add(note.copy(id = newId("note")))
            }
        }
        val left = clip.copy(lengthBeats = leftLength, notes = leftNotes)
        val right = clip.copy(
            id = newId("clip"),
            startBeats = atBeats,
            lengthBeats = rightLength,
            contentOffsetBeats = clip.contentOffsetBeats + splitLocal,
            notes = rightNotes
        )
        return touch(project.copy(clips = project.clips.map { if (it.id == clipId) left else it } + right))
    }

    /** Trim/resize from the left edge: moves start and adjusts content offset. */
    fun trimClipLeft(project: SProject, clipId: String, newStartBeats: Double): SProject {
        val clip = project.clipById(clipId) ?: return project
        val delta = newStartBeats - clip.startBeats
        val newOffset = (clip.contentOffsetBeats + delta).coerceAtLeast(0.0)
        val appliedDelta = newOffset - clip.contentOffsetBeats
        val newLength = (clip.lengthBeats - appliedDelta).coerceAtLeast(0.25)
        return updateClip(project, clipId) {
            it.copy(startBeats = clip.startBeats + appliedDelta, lengthBeats = newLength, contentOffsetBeats = newOffset)
        }
    }

    fun trimClipRight(project: SProject, clipId: String, newEndBeats: Double): SProject {
        val clip = project.clipById(clipId) ?: return project
        val newLength = (newEndBeats - clip.startBeats).coerceAtLeast(0.25)
        return updateClip(project, clipId) { it.copy(lengthBeats = newLength) }
    }

    // ------------------------------------------------------------- notes

    fun addNote(project: SProject, clipId: String, note: SNote): SProject =
        updateClip(project, clipId) { it.copy(notes = (it.notes + note).sortedBy { n -> n.startBeats }) }

    fun deleteNote(project: SProject, clipId: String, noteId: String): SProject =
        updateClip(project, clipId) { it.copy(notes = it.notes.filterNot { n -> n.id == noteId }) }

    fun updateNote(project: SProject, clipId: String, noteId: String, transform: (SNote) -> SNote): SProject =
        updateClip(project, clipId) { clip ->
            clip.copy(notes = clip.notes.map { if (it.id == noteId) transform(it) else it })
        }

    fun setNotes(project: SProject, clipId: String, notes: List<SNote>): SProject =
        updateClip(project, clipId) { it.copy(notes = notes) }

    // ------------------------------------------------------------- patterns

    fun addPattern(project: SProject, pattern: SDrumPattern): SProject =
        touch(project.copy(patterns = project.patterns + pattern))

    fun updatePattern(project: SProject, patternId: String, transform: (SDrumPattern) -> SDrumPattern): SProject =
        touch(project.copy(patterns = project.patterns.map { if (it.id == patternId) transform(it) else it }))

    fun duplicatePattern(project: SProject, patternId: String): SProject {
        val pattern = project.patternById(patternId) ?: return project
        return addPattern(project, pattern.copy(id = newId("pat"), name = pattern.name + " Copy"))
    }

    fun deletePattern(project: SProject, patternId: String): SProject {
        // Clips referencing the pattern keep their id but render silence; remove them too for cleanliness.
        return touch(project.copy(
            patterns = project.patterns.filterNot { it.id == patternId },
            clips = project.clips.filterNot { it.patternId == patternId }
        ))
    }

    fun setStep(project: SProject, patternId: String, laneIndex: Int, stepIndex: Int, step: SDrumStep): SProject =
        updatePattern(project, patternId) { pattern ->
            val lanes = pattern.lanes.mapIndexed { li, lane ->
                if (li != laneIndex) lane
                else lane.copy(steps = lane.steps.mapIndexed { si, s -> if (si == stepIndex) step else s })
            }
            pattern.copy(lanes = lanes)
        }

    /** Resize a pattern to a new step count (preserving existing steps). */
    fun resizePattern(project: SProject, patternId: String, newStepsPerBar: Int): SProject =
        updatePattern(project, patternId) { pattern ->
            val total = newStepsPerBar * pattern.bars
            val lanes = pattern.lanes.map { lane ->
                val steps = MutableList(total) { i -> if (i < lane.steps.size) lane.steps[i] else SDrumStep() }
                lane.copy(steps = steps)
            }
            pattern.copy(stepsPerBar = newStepsPerBar, lanes = lanes)
        }

    // ------------------------------------------------------------- automation / markers / misc

    fun upsertAutomation(project: SProject, lane: SAutomationLane): SProject {
        val existing = project.automation.any { it.id == lane.id }
        return touch(project.copy(automation = if (existing)
            project.automation.map { if (it.id == lane.id) lane else it }
        else project.automation + lane))
    }

    fun deleteAutomation(project: SProject, laneId: String): SProject =
        touch(project.copy(automation = project.automation.filterNot { it.id == laneId }))

    fun addMarker(project: SProject, beat: Double, name: String): SProject =
        touch(project.copy(markers = (project.markers + SMarker(beat = beat, name = name)).sortedBy { it.beat }))

    fun deleteMarker(project: SProject, markerId: String): SProject =
        touch(project.copy(markers = project.markers.filterNot { it.id == markerId }))

    fun updateMaster(project: SProject, transform: (SMaster) -> SMaster): SProject =
        touch(project.copy(master = transform(project.master)))

    // ------------------------------------------------------------- effects

    fun addEffect(project: SProject, trackId: String?, effect: SEffect): SProject {
        return if (trackId == null) {
            updateMaster(project) { it.copy(fxChain = it.fxChain + effect) }
        } else {
            updateTrack(project, trackId) { it.copy(fxChain = it.fxChain + effect) }
        }
    }

    fun updateEffect(project: SProject, trackId: String?, effectId: String, transform: (SEffect) -> SEffect): SProject {
        return if (trackId == null) {
            updateMaster(project) { m -> m.copy(fxChain = m.fxChain.map { if (it.id == effectId) transform(it) else it }) }
        } else {
            updateTrack(project, trackId) { t -> t.copy(fxChain = t.fxChain.map { if (it.id == effectId) transform(it) else it }) }
        }
    }

    fun removeEffect(project: SProject, trackId: String?, effectId: String): SProject {
        return if (trackId == null) {
            updateMaster(project) { it.copy(fxChain = it.fxChain.filterNot { fx -> fx.id == effectId }) }
        } else {
            updateTrack(project, trackId) { it.copy(fxChain = it.fxChain.filterNot { fx -> fx.id == effectId }) }
        }
    }

    fun moveEffect(project: SProject, trackId: String?, effectId: String, newIndex: Int): SProject {
        fun reorder(chain: List<SEffect>): List<SEffect> {
            val list = chain.toMutableList()
            val from = list.indexOfFirst { it.id == effectId }
            if (from < 0) return chain
            val fx = list.removeAt(from)
            list.add(newIndex.coerceIn(0, list.size), fx)
            return list
        }
        return if (trackId == null) {
            updateMaster(project) { it.copy(fxChain = reorder(it.fxChain)) }
        } else {
            updateTrack(project, trackId) { it.copy(fxChain = reorder(it.fxChain)) }
        }
    }
}

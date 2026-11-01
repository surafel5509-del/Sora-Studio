package com.sorastudio.core.project

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Holds the live project for the whole UI + engine. Pure state, no I/O: the app
 * layer observes [project] and persists it (autosave), keeping this module
 * Android-free and unit-testable.
 *
 * Undo/redo store serialized snapshots (deep copies by construction).
 */
class ProjectSession(initial: SProject = ProjectFactory.create("empty", "Untitled")) {

    private val _project = MutableStateFlow(initial)
    val project: StateFlow<SProject> = _project.asStateFlow()

    private val _selectedTrackId = MutableStateFlow<String?>(initial.tracks.firstOrNull()?.id)
    val selectedTrackId: StateFlow<String?> = _selectedTrackId.asStateFlow()

    private val _selectedClipId = MutableStateFlow<String?>(null)
    val selectedClipId: StateFlow<String?> = _selectedClipId.asStateFlow()

    private val _dirty = MutableStateFlow(false)
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    private val undoStack = ArrayDeque<String>()
    private val redoStack = ArrayDeque<String>()
    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()
    private val _canRedo = MutableStateFlow(false)
    val canRedo: StateFlow<Boolean> = _canRedo.asStateFlow()

    val current: SProject get() = _project.value

    /** Open a project (clears undo history). */
    fun load(project: SProject) {
        _project.value = project
        _selectedTrackId.value = project.tracks.firstOrNull()?.id
        _selectedClipId.value = null
        undoStack.clear(); redoStack.clear()
        _canUndo.value = false; _canRedo.value = false
        _dirty.value = false
    }

    fun selectTrack(trackId: String?) {
        _selectedTrackId.value = trackId
    }

    fun selectClip(clipId: String?) {
        _selectedClipId.value = clipId
        if (clipId != null) {
            _selectedTrackId.value = current.clipById(clipId)?.trackId
        }
    }

    fun markSaved() { _dirty.value = false }

    /**
     * Apply an edit. [recordUndo] = false for continuous controls (faders, knobs)
     * so a drag doesn't flood history; callers commit once on release.
     */
    fun update(recordUndo: Boolean = true, transform: (SProject) -> SProject) {
        val before = _project.value
        val after = transform(before)
        if (after == before) return
        if (recordUndo) {
            undoStack.addLast(ProjectCodec.encode(before))
            while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
            redoStack.clear()
        }
        _project.value = after
        _dirty.value = true
        refreshHistoryFlags()
    }

    /** Snapshot the current state into undo history (e.g. before a fader drag). */
    fun pushUndoCheckpoint() {
        undoStack.addLast(ProjectCodec.encode(_project.value))
        while (undoStack.size > MAX_HISTORY) undoStack.removeFirst()
        redoStack.clear()
        refreshHistoryFlags()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.addLast(ProjectCodec.encode(_project.value))
        val previous = undoStack.removeLast()
        ProjectCodec.decode(previous).getOrNull()?.let { restored ->
            _project.value = restored
            _dirty.value = true
            fixSelection(restored)
        }
        refreshHistoryFlags()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.addLast(ProjectCodec.encode(_project.value))
        val next = redoStack.removeLast()
        ProjectCodec.decode(next).getOrNull()?.let { restored ->
            _project.value = restored
            _dirty.value = true
            fixSelection(restored)
        }
        refreshHistoryFlags()
    }

    private fun fixSelection(project: SProject) {
        if (project.trackById(_selectedTrackId.value) == null) {
            _selectedTrackId.value = project.tracks.firstOrNull()?.id
        }
        if (project.clipById(_selectedClipId.value) == null) {
            _selectedClipId.value = null
        }
    }

    private fun refreshHistoryFlags() {
        _canUndo.value = undoStack.isNotEmpty()
        _canRedo.value = redoStack.isNotEmpty()
    }

    companion object {
        const val MAX_HISTORY = 60
    }
}

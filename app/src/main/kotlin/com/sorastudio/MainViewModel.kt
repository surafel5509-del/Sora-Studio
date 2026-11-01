package com.sorastudio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sorastudio.core.audio.AudioRecorder
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.newId
import com.sorastudio.core.database.PatternLibraryRepository
import com.sorastudio.core.database.PresetEntity
import com.sorastudio.core.database.PresetRepository
import com.sorastudio.core.database.ProjectIndexRepository
import com.sorastudio.core.database.RecordingRepository
import com.sorastudio.core.database.SoundLibraryRepository
import com.sorastudio.core.project.ProjectFactory
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.ProjectSummary
import com.sorastudio.core.project.SEffect
import com.sorastudio.core.project.SProject
import com.sorastudio.core.storage.AppSettings
import com.sorastudio.core.storage.ProjectStorage
import com.sorastudio.core.storage.SampleStore
import com.sorastudio.core.storage.SettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * App-level orchestration: project lifecycle, autosave + crash recovery,
 * and glue between the session (document) and persistence (files + Room index).
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    val session: ProjectSession,
    val controller: PlaybackController,
    val recorder: AudioRecorder,
    val storage: ProjectStorage,
    val sampleStore: SampleStore,
    val settingsRepository: SettingsRepository,
    val projectIndex: ProjectIndexRepository,
    val presetRepository: PresetRepository,
    val patternLibrary: PatternLibraryRepository,
    val recordingRepository: RecordingRepository,
    val soundLibrary: SoundLibraryRepository
) : ViewModel() {

    val settings: Flow<AppSettings> = settingsRepository.settings

    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects.asStateFlow()

    private val _recovered = MutableStateFlow(false)
    val recovered: StateFlow<Boolean> = _recovered.asStateFlow()

    val userEffectPresets: Flow<List<PresetEntity>> = presetRepository.observeEffects()

    init {
        viewModelScope.launch {
            initialiseProject()
        }
        observeSettings()
        observeAutosave()
        observeProjectIndex()
    }

    private suspend fun initialiseProject() {
        val prefs = settingsRepository.settings.first()
        val autosave = withContext(Dispatchers.IO) { storage.loadAutosave() }
        when {
            autosave != null && storage.hasAutosaveNewerThan(autosave.id) -> {
                session.load(autosave)
                _recovered.value = true
                persist(autosave)
                withContext(Dispatchers.IO) { storage.clearAutosave() }
            }
            prefs.lastProjectId != null -> {
                val loaded = withContext(Dispatchers.IO) { storage.load(prefs.lastProjectId!!) }
                loaded.getOrNull()?.let { session.load(it) } ?: loadMostRecentOrCreate(prefs)
            }
            else -> loadMostRecentOrCreate(prefs)
        }
        refreshProjects()
    }

    private suspend fun loadMostRecentOrCreate(prefs: AppSettings) {
        val existing = withContext(Dispatchers.IO) { storage.listProjects() }
        if (existing.isNotEmpty()) {
            val loaded = withContext(Dispatchers.IO) { storage.load(existing.first().id) }
            loaded.getOrNull()?.let { session.load(it) }
            settingsRepository.setLastProject(existing.first().id)
        } else {
            // First launch: a playable welcome beat so the app sings immediately.
            val welcome = ProjectFactory.create("beat-starter", "Welcome Beat")
            persist(welcome)
            session.load(welcome)
            settingsRepository.setLastProject(welcome.id)
        }
        @Suppress("UNUSED_EXPRESSION") prefs
    }

    private fun observeSettings() {
        viewModelScope.launch {
            settingsRepository.settings.collect { prefs ->
                controller.setMasterVolumePercent(prefs.masterVolumePercent)
            }
        }
    }

    @OptIn(FlowPreview::class)
    private fun observeAutosave() {
        viewModelScope.launch {
            session.project
                .debounce(900)
                .distinctUntilChanged()
                .collect { project ->
                    if (session.dirty.value) {
                        persist(project)
                        session.markSaved()
                        refreshProjects()
                    }
                }
        }
        viewModelScope.launch {
            session.project
                .debounce(2500)
                .collect { project ->
                    withContext(Dispatchers.IO) { storage.saveAutosave(project) }
                }
        }
    }

    private fun observeProjectIndex() {
        viewModelScope.launch {
            projectIndex.observeProjects().collect { indexed ->
                if (indexed.isNotEmpty() || _projects.value.isNotEmpty()) {
                    // Files are the source of truth; index drives cheap updates.
                    if (_projects.value.isEmpty()) _projects.value = indexed
                }
            }
        }
    }

    private suspend fun persist(project: SProject) {
        withContext(Dispatchers.IO) {
            runCatching { storage.save(project) }
        }
        runCatching { projectIndex.index(project) }
        runCatching { settingsRepository.setLastProject(project.id) }
    }

    fun refreshProjects() {
        viewModelScope.launch {
            _projects.value = withContext(Dispatchers.IO) { storage.listProjects() }
        }
    }

    // ------------------------------------------------------------- actions

    fun createProject(templateId: String, name: String) {
        viewModelScope.launch {
            val prefs = settingsRepository.settings.first()
            var project = ProjectFactory.create(templateId, name)
            if (templateId == "empty") {
                project = project.copy(
                    bpm = prefs.defaultBpm.toDouble(),
                    metronomeEnabled = prefs.metronomeDefault
                )
            }
            persist(project)
            session.load(project)
            controller.stop()
            controller.seekBeats(0.0)
            refreshProjects()
        }
    }

    fun openProject(summary: ProjectSummary) {
        viewModelScope.launch {
            // Save whatever is open first.
            persist(session.current)
            controller.stop()
            val loaded = withContext(Dispatchers.IO) { storage.load(summary.id) }
            loaded.getOrNull()?.let { project ->
                controller.assetCache.clear()
                session.load(project)
                controller.seekBeats(0.0)
                settingsRepository.setLastProject(project.id)
            }
            refreshProjects()
        }
    }

    fun deleteProject(summary: ProjectSummary) {
        viewModelScope.launch {
            val wasCurrent = session.current.id == summary.id
            withContext(Dispatchers.IO) { storage.delete(summary.id) }
            projectIndex.remove(summary.id)
            recordingRepository.deleteForProject(summary.id)
            if (wasCurrent) {
                controller.stop()
                val remaining = withContext(Dispatchers.IO) { storage.listProjects() }
                if (remaining.isNotEmpty()) {
                    storage.load(remaining.first().id).getOrNull()?.let { session.load(it) }
                } else {
                    val fresh = ProjectFactory.create("empty", "Untitled Project")
                    persist(fresh)
                    session.load(fresh)
                }
            }
            refreshProjects()
        }
    }

    fun duplicateProject(summary: ProjectSummary) {
        viewModelScope.launch {
            val newProjectId = newId("prj")
            val result = withContext(Dispatchers.IO) { storage.duplicate(summary.id, newProjectId) }
            result.getOrNull()?.let { project ->
                projectIndex.index(project)
            }
            refreshProjects()
        }
    }

    fun toggleFavorite(summary: ProjectSummary) {
        viewModelScope.launch {
            if (session.current.id == summary.id) {
                session.update { it.copy(favorite = !it.favorite) }
            } else {
                val loaded = withContext(Dispatchers.IO) { storage.load(summary.id).getOrNull() }
                if (loaded != null) {
                    val updated = loaded.copy(favorite = !loaded.favorite)
                    persist(updated)
                }
            }
            refreshProjects()
        }
    }

    fun saveEffectPreset(name: String, effect: SEffect) {
        viewModelScope.launch { presetRepository.saveEffectPreset(name, effect) }
    }

    fun saveChainPreset(name: String, chain: List<SEffect>) {
        viewModelScope.launch { presetRepository.saveChainPreset(name, PresetRepository.KIND_VOCAL, chain) }
    }

    fun deleteEffectPreset(entity: PresetEntity) {
        viewModelScope.launch { presetRepository.delete(entity) }
    }

    fun savePatternToLibrary(pattern: com.sorastudio.core.project.SDrumPattern) {
        viewModelScope.launch { patternLibrary.save(pattern.copy(id = newId("pat"))) }
    }

    fun insertExistingProjectAudioClip(clipId: String, newRelativePath: String) {
        session.update { p -> ProjectOps.updateClip(p, clipId) { it.copy(audioPath = newRelativePath) } }
    }

    fun dismissRecovery() {
        _recovered.value = false
    }

    fun clearAutosave() {
        viewModelScope.launch(Dispatchers.IO) { storage.clearAutosave() }
    }

    fun onExit() {
        viewModelScope.launch {
            persist(session.current)
            withContext(Dispatchers.IO) { storage.clearAutosave() }
        }
    }

    override fun onCleared() {
        controller.release()
        super.onCleared()
    }
}

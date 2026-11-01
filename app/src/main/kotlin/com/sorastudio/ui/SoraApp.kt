package com.sorastudio.ui

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.sorastudio.MainViewModel
import com.sorastudio.core.common.newId
import com.sorastudio.core.database.RecordingEntity
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.TrackType
import com.sorastudio.feature.arrangement.ArrangementScreen
import com.sorastudio.feature.automation.AutomationScreen
import com.sorastudio.feature.drums.DrumsScreen
import com.sorastudio.feature.effects.EffectsScreen
import com.sorastudio.feature.export.ExportScreen
import com.sorastudio.feature.home.HomeScreen
import com.sorastudio.feature.home.NewProjectDialog
import com.sorastudio.feature.home.ProjectsScreen
import com.sorastudio.feature.instruments.InstrumentsScreen
import com.sorastudio.feature.mixer.MixerScreen
import com.sorastudio.feature.pianoroll.PianoRollScreen
import com.sorastudio.feature.recording.AudioEditorScreen
import com.sorastudio.feature.recording.RecordingScreen
import com.sorastudio.feature.settings.SettingsScreen
import com.sorastudio.feature.sounds.SoundsScreen
import com.sorastudio.feature.studio.StudioScreen
import com.sorastudio.feature.studio.StudioTab
import com.sorastudio.feature.vocal.VocalScreen
import kotlinx.coroutines.launch
import java.io.File

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val TOP_DESTINATIONS = listOf(
    Destination("home", "Home", Icons.Filled.Home),
    Destination("studio", "Studio", Icons.Filled.Tune),
    Destination("record", "Record", Icons.Filled.Mic),
    Destination("sounds", "Sounds", Icons.Filled.LibraryMusic),
    Destination("projects", "Projects", Icons.Filled.Folder),
    Destination("settings", "Settings", Icons.Filled.Settings),
)

private sealed class EditorRequest {
    data class ForClip(val clip: SClip, val file: File) : EditorRequest()
    data class ForTake(val file: File, val relativePath: String) : EditorRequest()
}

@Composable
fun SoraApp(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val session = viewModel.session
    val controller = viewModel.controller
    val scope = rememberCoroutineScope()

    val projects by viewModel.projects.collectAsState()
    val recovered by viewModel.recovered.collectAsState()
    val project by session.project.collectAsState()
    val userPresets by viewModel.userEffectPresets.collectAsState(initial = emptyList())
    val libraryPatterns by viewModel.patternLibrary.observePatterns().collectAsState(initial = emptyList())

    var studioTab by remember { mutableStateOf(StudioTab.ARRANGEMENT) }
    var effectsTarget by remember { mutableStateOf<String?>(null) }
    var effectsUseMaster by remember { mutableStateOf(false) }
    var drumsFocusPatternId by remember { mutableStateOf<String?>(null) }
    var showNewProject by remember { mutableStateOf(false) }
    var editorRequest by remember { mutableStateOf<EditorRequest?>(null) }
    var recordTabVocal by remember { mutableStateOf(false) }

    val configuration = LocalConfiguration.current
    val useRail = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE &&
        configuration.screenWidthDp >= 600

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    fun navigateTop(route: String) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    Scaffold(
        bottomBar = {
            if (!useRail) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    for (destination in TOP_DESTINATIONS) {
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = { navigateTop(destination.route) },
                            icon = { Icon(destination.icon, destination.label, modifier = Modifier.size(21.dp)) },
                            label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
        }
    ) { padding ->
        Row(
            Modifier
                .fillMaxSize()
                .padding(if (useRail) androidx.compose.foundation.layout.PaddingValues(0.dp) else padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            if (useRail) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.padding(top = 8.dp))
                    for (destination in TOP_DESTINATIONS) {
                        val selected = currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = { navigateTop(destination.route) },
                            icon = { Icon(destination.icon, destination.label, modifier = Modifier.size(21.dp)) },
                            label = { Text(destination.label, style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
            }
            Column(Modifier.weight(1f)) {
                if (recovered) {
                    Surface(color = SoraPalette.Warning.copy(alpha = 0.16f)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "Recovered unsaved work from the last session.",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = { viewModel.dismissRecovery() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Filled.Close, "Dismiss", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                NavHost(
                    navController = navController,
                    startDestination = "home",
                    modifier = Modifier.weight(1f)
                ) {
                    composable("home") {
                        HomeScreen(
                            projects = projects,
                            onNewProject = { showNewProject = true },
                            onOpenProject = { summary ->
                                viewModel.openProject(summary)
                                navigateTop("studio")
                            },
                            onRecordVocal = {
                                recordTabVocal = false
                                navigateTop("record")
                            },
                            onCreateBeat = {
                                val drumTrack = project.tracks.firstOrNull { it.type == TrackType.DRUM }
                                if (drumTrack != null) session.selectTrack(drumTrack.id)
                                studioTab = StudioTab.DRUMS
                                navigateTop("studio")
                            },
                            onOpenStudio = { navigateTop("studio") },
                            onOpenSounds = { navigateTop("sounds") },
                            onOpenAllProjects = { navigateTop("projects") },
                            onCreateFromTemplate = { templateId ->
                                viewModel.createProject(templateId, defaultNameFor(templateId))
                                navigateTop("studio")
                            },
                            onDeleteProject = { viewModel.deleteProject(it) },
                            onDuplicateProject = { viewModel.duplicateProject(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) }
                        )
                    }

                    composable("studio") {
                        Box(Modifier.fillMaxSize()) {
                            StudioScreen(
                                session = session,
                                controller = controller,
                                selectedTab = studioTab,
                                onTabSelected = { studioTab = it }
                            ) { tab ->
                                when (tab) {
                                    StudioTab.ARRANGEMENT -> ArrangementScreen(
                                        session = session,
                                        controller = controller,
                                        onOpenPianoRoll = { clipId ->
                                            session.selectClip(clipId)
                                            studioTab = StudioTab.PIANO_ROLL
                                        },
                                        onOpenDrums = { patternId ->
                                            drumsFocusPatternId = patternId
                                            studioTab = StudioTab.DRUMS
                                        },
                                        onEditAudioClip = { clip ->
                                            val path = clip.audioPath
                                            if (path != null) {
                                                controller.resolvePath(path)?.let { file ->
                                                    editorRequest = EditorRequest.ForClip(clip, file)
                                                }
                                            }
                                        }
                                    )
                                    StudioTab.PIANO_ROLL -> PianoRollScreen(session, controller)
                                    StudioTab.DRUMS -> DrumsScreen(
                                        session = session,
                                        controller = controller,
                                        sampleStore = viewModel.sampleStore,
                                        libraryPatterns = libraryPatterns,
                                        onSavePatternToLibrary = { viewModel.savePatternToLibrary(it) },
                                        focusPatternId = drumsFocusPatternId
                                    )
                                    StudioTab.INSTRUMENTS -> InstrumentsScreen(session, controller, viewModel.sampleStore)
                                    StudioTab.MIXER -> MixerScreen(
                                        session = session,
                                        controller = controller,
                                        onOpenEffects = { trackId ->
                                            if (trackId == null) {
                                                effectsUseMaster = true
                                            } else {
                                                effectsUseMaster = false
                                                effectsTarget = trackId
                                                session.selectTrack(trackId)
                                            }
                                            studioTab = StudioTab.EFFECTS
                                        }
                                    )
                                    StudioTab.AUTOMATION -> AutomationScreen(session, controller)
                                    StudioTab.EFFECTS -> EffectsScreen(
                                        session = session,
                                        controller = controller,
                                        targetTrackId = if (effectsUseMaster) null else effectsTarget,
                                        onTargetChange = { target ->
                                            if (target == null) effectsUseMaster = true
                                            else {
                                                effectsUseMaster = false
                                                effectsTarget = target
                                            }
                                        },
                                        userPresets = userPresets,
                                        onSaveUserPreset = { name, fx -> viewModel.saveEffectPreset(name, fx) },
                                        onDeleteUserPreset = { viewModel.deleteEffectPreset(it) }
                                    )
                                }
                            }
                            FloatingActionButton(
                                onClick = { navController.navigate("export") },
                                containerColor = SoraPalette.Accent,
                                contentColor = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(16.dp)
                                    .size(48.dp)
                            ) {
                                Icon(Icons.Filled.FileDownload, "Export")
                            }
                        }
                    }

                    composable("record") {
                        Column(Modifier.fillMaxSize()) {
                            Surface(color = MaterialTheme.colorScheme.surface) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    SmallToggleButton("Record", !recordTabVocal, { recordTabVocal = false })
                                    Spacer(Modifier.width(6.dp))
                                    SmallToggleButton("Vocal Studio", recordTabVocal, { recordTabVocal = true })
                                }
                            }
                            if (recordTabVocal) {
                                VocalScreen(
                                    session = session,
                                    controller = controller,
                                    onSaveChainPreset = { name, chain -> viewModel.saveChainPreset(name, chain) },
                                    modifier = Modifier.weight(1f)
                                )
                            } else {
                                RecordingScreen(
                                    session = session,
                                    controller = controller,
                                    storage = viewModel.storage,
                                    recordingRepository = viewModel.recordingRepository,
                                    recorder = viewModel.recorder,
                                    onOpenAudioEditor = { relativePath ->
                                        val file = File(viewModel.storage.projectDir(project.id), relativePath)
                                        editorRequest = EditorRequest.ForTake(file, relativePath)
                                    },
                                    onOpenStudio = { navigateTop("studio") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    composable("sounds") {
                        SoundsScreen(
                            session = session,
                            controller = controller,
                            sampleStore = viewModel.sampleStore,
                            soundLibrary = viewModel.soundLibrary,
                            patternLibrary = viewModel.patternLibrary,
                            presetRepository = viewModel.presetRepository
                        )
                    }

                    composable("projects") {
                        ProjectsScreen(
                            projects = projects,
                            onNewProject = { showNewProject = true },
                            onOpenProject = { summary ->
                                viewModel.openProject(summary)
                                navigateTop("studio")
                            },
                            onDeleteProject = { viewModel.deleteProject(it) },
                            onDuplicateProject = { viewModel.duplicateProject(it) },
                            onToggleFavorite = { viewModel.toggleFavorite(it) }
                        )
                    }

                    composable("settings") {
                        SettingsScreen(
                            settingsRepository = viewModel.settingsRepository,
                            deviceInfoText = controller.deviceInfo.describe(),
                            appVersionText = "Version 1.0.0",
                            onClearAutosave = { viewModel.clearAutosave() }
                        )
                    }

                    composable("export") {
                        ExportScreen(
                            session = session,
                            controller = controller,
                            onClose = { navController.popBackStack() }
                        )
                    }
                }
            }
        }
    }

    if (showNewProject) {
        NewProjectDialog(
            onCreate = { templateId, name ->
                showNewProject = false
                viewModel.createProject(templateId, name)
                navigateTop("studio")
            },
            onDismiss = { showNewProject = false }
        )
    }

    editorRequest?.let { request ->
        val file = when (request) {
            is EditorRequest.ForClip -> request.file
            is EditorRequest.ForTake -> request.file
        }
        val title = when (request) {
            is EditorRequest.ForClip -> request.clip.name
            is EditorRequest.ForTake -> request.file.name
        }
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            AudioEditorScreen(
                file = file,
                title = title,
                controller = controller,
                defaultKeyRoot = project.keyRoot,
                defaultScale = runCatching { com.sorastudio.core.common.ScaleType.valueOf(project.keyScale) }
                    .getOrDefault(com.sorastudio.core.common.ScaleType.MAJOR),
                onClose = { editorRequest = null },
                onSaved = { savedFile ->
                    when (request) {
                        is EditorRequest.ForClip -> {
                            val projectDir = viewModel.storage.projectDir(project.id).absolutePath
                            val relative = if (savedFile.absolutePath.startsWith(projectDir)) {
                                "audio/${savedFile.name}"
                            } else savedFile.absolutePath
                            viewModel.insertExistingProjectAudioClip(request.clip.id, relative)
                            controller.preloadFiles(listOf(savedFile))
                        }
                        is EditorRequest.ForTake -> {
                            scope.launch {
                                viewModel.recordingRepository.add(
                                    RecordingEntity(
                                        id = newId("rec"),
                                        projectId = project.id,
                                        name = savedFile.nameWithoutExtension,
                                        relativePath = "audio/${savedFile.name}",
                                        durationMs = 0,
                                        sampleRate = controller.sampleRate,
                                        createdAt = System.currentTimeMillis()
                                    )
                                )
                            }
                        }
                    }
                    editorRequest = null
                }
            )
        }
    }
}

private fun defaultNameFor(templateId: String): String = when (templateId) {
    "beat-starter" -> "My Beat"
    "song-starter" -> "My Song"
    "lofi-sketch" -> "Lo-Fi Sketch"
    "dance-starter" -> "Dance Track"
    else -> "Untitled Project"
}

private fun SClip.isAudio(): Boolean = type == ClipType.AUDIO

package com.sorastudio.feature.drums

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.newId
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.dsp.DrumSynth
import com.sorastudio.core.dsp.DrumType
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectFactory
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.SDrumPattern
import com.sorastudio.core.project.SDrumStep
import com.sorastudio.core.project.TrackType
import com.sorastudio.core.storage.SampleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Step sequencer: pattern editing with velocity/probability/pitch per step,
 * swing, 16/32 steps, kit switching, custom lane samples and groove presets.
 */
@Composable
fun DrumsScreen(
    session: ProjectSession,
    controller: PlaybackController,
    sampleStore: SampleStore,
    libraryPatterns: List<SDrumPattern> = emptyList(),
    onSavePatternToLibrary: (SDrumPattern) -> Unit = {},
    focusPatternId: String? = null,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val transport by controller.transport.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val drumTrack = project.trackById(selectedTrackId)?.takeIf { it.type == TrackType.DRUM }
        ?: project.tracks.firstOrNull { it.type == TrackType.DRUM }

    var activePatternId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(focusPatternId) { if (focusPatternId != null) activePatternId = focusPatternId }
    val pattern = project.patternById(activePatternId) ?: project.patterns.firstOrNull()

    var selectedStep by remember { mutableStateOf<Pair<Int, Int>?>(null) } // lane, step
    var currentStep by remember { mutableIntStateOf(-1) }

    LaunchedEffect(transport.playing, pattern?.id) {
        while (transport.playing) {
            val p = pattern
            if (p != null) {
                val pos = controller.positionBeats()
                val stepsPerBeat = p.stepsPerBar / 4.0
                currentStep = ((pos * stepsPerBeat).toInt() % p.totalSteps)
            }
            delay(40)
        }
        currentStep = -1
    }

    var pendingCustomLane by remember { mutableStateOf<Int?>(null) }
    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val laneIndex = pendingCustomLane
        val currentPattern = pattern
        if (uri != null && laneIndex != null && currentPattern != null) {
            scope.launch {
                val item = withContext(Dispatchers.IO) {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "drum-sample.wav"
                    runCatching { sampleStore.importFromUri(uri, name, "Drum Samples") }.getOrNull()
                }
                if (item?.filePath != null) {
                    session.update { p ->
                        ProjectOps.updatePattern(p, currentPattern.id) { pat ->
                            pat.copy(lanes = pat.lanes.mapIndexed { li, lane ->
                                if (li == laneIndex) lane.copy(customSamplePath = item.filePath) else lane
                            })
                        }
                    }
                    controller.preloadFiles(listOf(java.io.File(item.filePath)))
                }
            }
        }
        pendingCustomLane = null
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Drum Machine", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val newPattern = SDrumPattern.emptyPattern(name = "Pattern ${project.patterns.size + 1}", kitId = drumTrack?.drumKitId ?: "kit-studio")
                        session.update { ProjectOps.addPattern(it, newPattern) }
                        activePatternId = newPattern.id
                    }) { Icon(Icons.Filled.Add, "New pattern") }
                    if (pattern != null) {
                        IconButton(onClick = {
                            session.update { ProjectOps.duplicatePattern(it, pattern.id) }
                        }) { Icon(Icons.Filled.ContentCopy, "Duplicate pattern") }
                        IconButton(onClick = { onSavePatternToLibrary(pattern) }) { Icon(Icons.Filled.Save, "Save to library") }
                        IconButton(onClick = {
                            session.update { ProjectOps.deletePattern(it, pattern.id) }
                            activePatternId = null
                        }) { Icon(Icons.Filled.Delete, "Delete pattern", tint = MaterialTheme.colorScheme.error) }
                    }
                }
                // Pattern chips
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (p in project.patterns) {
                        SmallToggleButton(p.name, p.id == pattern?.id, { activePatternId = p.id })
                        Spacer(Modifier.width(6.dp))
                    }
                }
                Spacer(Modifier.height(6.dp))
                // Kit chips
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    Text("Kit", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 6.dp))
                    for (kit in DrumSynth.KITS) {
                        val isActive = (drumTrack?.drumKitId ?: pattern?.kitId) == kit.id
                        SmallToggleButton(kit.name, isActive, {
                            if (drumTrack != null) {
                                session.update { p -> ProjectOps.updateTrack(p, drumTrack.id) { it.copy(drumKitId = kit.id) } }
                            }
                            if (pattern != null) {
                                session.update { p -> ProjectOps.updatePattern(p, pattern.id) { it.copy(kitId = kit.id) } }
                            }
                        })
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        }

        if (pattern == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No pattern yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val newPattern = SDrumPattern.emptyPattern()
                        session.update { ProjectOps.addPattern(it, newPattern) }
                        activePatternId = newPattern.id
                    }) { Text("Create Pattern") }
                }
            }
            return@Column
        }

        val kitId = drumTrack?.drumKitId ?: pattern.kitId
        val padSize = if (pattern.stepsPerBar > 16) 26.dp else 34.dp

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 10.dp)
        ) {
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Column {
                    for ((laneIndex, lane) in pattern.lanes.withIndex()) {
                        val drum = runCatching { DrumType.valueOf(lane.drum) }.getOrNull() ?: DrumType.PERC
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                            Box(
                                Modifier
                                    .width(86.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable {
                                        controller.drumHit(kitId, drum, 0.95f, lane.customSamplePath)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 7.dp)
                            ) {
                                Column {
                                    Text(
                                        if (lane.customSamplePath != null) "♪ " + java.io.File(lane.customSamplePath).nameWithoutExtension.take(8) else drum.shortName,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            IconButton(onClick = {
                                pendingCustomLane = laneIndex
                                samplePicker.launch(arrayOf("audio/*"))
                            }, modifier = Modifier.size(26.dp)) {
                                Icon(Icons.Filled.FileUpload, "Custom sample", modifier = Modifier.size(15.dp))
                            }
                            Spacer(Modifier.width(4.dp))
                            for ((stepIndex, step) in lane.steps.withIndex()) {
                                val isBeat = stepIndex % (pattern.stepsPerBar / 4) == 0
                                val isCurrent = stepIndex == currentStep
                                val isSelected = selectedStep == (laneIndex to stepIndex)
                                val shape = RoundedCornerShape(6.dp)
                                Box(
                                    Modifier
                                        .size(padSize)
                                        .padding(1.5.dp)
                                        .clip(shape)
                                        .background(
                                            when {
                                                step.active -> SoraPalette.Accent.copy(alpha = 0.35f + step.velocity * 0.6f)
                                                isCurrent -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
                                                isBeat -> MaterialTheme.colorScheme.surfaceVariant
                                                else -> MaterialTheme.colorScheme.surface
                                            }
                                        )
                                        .border(
                                            when {
                                                isSelected -> 1.5.dp
                                                isCurrent -> 1.dp
                                                else -> 0.5.dp
                                            },
                                            when {
                                                isSelected -> SoraPalette.Accent
                                                isCurrent -> SoraPalette.Accent
                                                else -> MaterialTheme.colorScheme.outline
                                            },
                                            shape
                                        )
                                        .clickable {
                                            selectedStep = laneIndex to stepIndex
                                            val newStep = step.copy(active = !step.active)
                                            session.update(recordUndo = false) { p ->
                                                ProjectOps.setStep(p, pattern.id, laneIndex, stepIndex, newStep)
                                            }
                                            if (newStep.active) {
                                                controller.drumHit(kitId, drum, step.velocity, lane.customSamplePath)
                                            }
                                        }
                                )
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            // Step editor
            val sel = selectedStep
            if (sel != null && sel.first < pattern.lanes.size && sel.second < pattern.lanes[sel.first].steps.size) {
                val step = pattern.lanes[sel.first].steps[sel.second]
                SectionTitle("Step ${sel.second + 1} — ${pattern.lanes[sel.first].drum}")
                LabeledSlider("Velocity", "${(step.velocity * 127).toInt()}", step.velocity, { v ->
                    session.update(recordUndo = false) { ProjectOps.setStep(it, pattern.id, sel.first, sel.second, step.copy(velocity = v)) }
                }, valueRange = 0.05f..1f)
                LabeledSlider("Probability", "${(step.probability * 100).toInt()}%", step.probability, { v ->
                    session.update(recordUndo = false) { ProjectOps.setStep(it, pattern.id, sel.first, sel.second, step.copy(probability = v)) }
                }, valueRange = 0.05f..1f)
                LabeledSlider("Pitch", "${"%+.0f".format(step.pitchSemitones)} st", step.pitchSemitones, { v ->
                    session.update(recordUndo = false) { ProjectOps.setStep(it, pattern.id, sel.first, sel.second, step.copy(pitchSemitones = v)) }
                }, valueRange = -12f..12f)
            } else {
                Text("Tap a pad to toggle it; select a pad to edit velocity, probability and pitch.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Groove")
            LabeledSlider("Swing", "${(pattern.swing * 100).toInt()}%", pattern.swing, { v ->
                session.update(recordUndo = false) { ProjectOps.updatePattern(it, pattern.id) { p -> p.copy(swing = v) } }
            }, valueRange = 0f..0.6f)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Steps", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                SmallToggleButton("16", pattern.stepsPerBar == 16, {
                    session.update { ProjectOps.resizePattern(it, pattern.id, 16) }
                })
                Spacer(Modifier.width(6.dp))
                SmallToggleButton("32", pattern.stepsPerBar == 32, {
                    session.update { ProjectOps.resizePattern(it, pattern.id, 32) }
                })
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    session.update { p ->
                        ProjectOps.updatePattern(p, pattern.id) { pat ->
                            pat.copy(lanes = pat.lanes.map { it.copy(steps = it.steps.map { s -> s.copy(active = false) }) })
                        }
                    }
                }) { Text("Clear") }
                TextButton(onClick = {
                    session.update { p ->
                        ProjectOps.updatePattern(p, pattern.id) { pat ->
                            pat.copy(lanes = pat.lanes.map { lane ->
                                lane.copy(steps = lane.steps + lane.steps)
                            }, bars = pat.bars * 2)
                        }
                    }
                }) { Text("Double length") }
            }

            Spacer(Modifier.height(10.dp))
            SectionTitle("Groove presets")
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                val grooves = listOf(
                    "Hip-Hop" to { ProjectFactory.Grooves.hipHop(kitId) },
                    "Trap" to { ProjectFactory.Grooves.trap(kitId) },
                    "House" to { ProjectFactory.Grooves.fourFloor(kitId) },
                    "Lo-Fi" to { ProjectFactory.Grooves.lofi(kitId) },
                    "Pop" to { ProjectFactory.Grooves.pop(kitId) }
                )
                for ((label, make) in grooves) {
                    SmallToggleButton(label, false, {
                        val groove = make()
                        session.update { p ->
                            ProjectOps.updatePattern(p, pattern.id) { pat ->
                                pat.copy(lanes = groove.lanes, swing = groove.swing)
                            }
                        }
                    })
                    Spacer(Modifier.width(6.dp))
                }
            }
            if (libraryPatterns.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Library patterns")
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (lib in libraryPatterns) {
                        SmallToggleButton(lib.name, false, {
                            val copy = lib.copy(id = newId("pat"))
                            session.update { ProjectOps.addPattern(it, copy) }
                            activePatternId = copy.id
                        })
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Row {
                Button(onClick = {
                    val trackId = drumTrack?.id ?: run {
                        val (updated, created) = ProjectOps.addTrack(session.current, TrackType.DRUM, "Drums")
                        session.update { updated }
                        session.selectTrack(created.id)
                        created.id
                    }
                    val end = session.current.clipsForTrack(trackId).maxOfOrNull { it.endBeats } ?: 0.0
                    val clip = SClip(
                        trackId = trackId, name = pattern.name, type = ClipType.PATTERN,
                        startBeats = end, lengthBeats = pattern.lengthBeats * 4, patternId = pattern.id
                    )
                    session.update { ProjectOps.addClip(it, clip) }
                    session.selectClip(clip.id)
                    controller.seekBeats(end)
                    controller.play()
                }) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add to Arrangement & Play")
                }
            }
            Text(
                "Patterns loop inside their arrangement clip. Use the Arrangement tab to chain patterns into a song.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp)
            )
            @Suppress("UNUSED_EXPRESSION") context
            Spacer(Modifier.height(16.dp))
        }
    }
}


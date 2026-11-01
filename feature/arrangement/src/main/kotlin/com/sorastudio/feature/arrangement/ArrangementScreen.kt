package com.sorastudio.feature.arrangement

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.designsystem.ColorDot
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.designsystem.WaveformCanvas
import com.sorastudio.core.designsystem.toComposeColor
import com.sorastudio.core.designsystem.waveformPeaksFor
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.SDrumPattern
import com.sorastudio.core.project.STrack
import com.sorastudio.core.project.TrackColors
import com.sorastudio.core.project.TrackType
import kotlinx.coroutines.delay
import kotlin.math.round

private val TRACK_HEADER_WIDTH = 148.dp
private val TRACK_HEIGHT = 58.dp

/**
 * Multitrack arrangement editor: track headers + horizontally scrolling lanes,
 * draggable clips, ruler seek, snap-to-grid, split/trim/duplicate, markers,
 * loop region display and per-clip inspector.
 */
@Composable
fun ArrangementScreen(
    session: ProjectSession,
    controller: PlaybackController,
    onOpenPianoRoll: (clipId: String) -> Unit,
    onOpenDrums: (patternId: String) -> Unit,
    onEditAudioClip: (clip: SClip) -> Unit,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val selectedClipId by session.selectedClipId.collectAsState()
    val transport by controller.transport.collectAsState()

    var pxPerBeat by remember { mutableFloatStateOf(46f) }
    var snapEnabled by remember { mutableStateOf(true) }
    var gridBeats by remember { mutableDoubleStateOf(1.0) }
    var showAddTrack by remember { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<STrack?>(null) }

    var playheadBeats by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        while (true) {
            playheadBeats = controller.positionBeats()
            delay(50)
        }
    }

    val lengthBeats = project.computedLengthBeats()
    val verticalScroll = rememberScrollState()
    val horizontalScroll = rememberScrollState()
    val density = LocalDensity.current

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = { showAddTrack = true }, modifier = Modifier.height(34.dp)) {
                    Icon(Icons.Filled.Add, null, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Track", style = MaterialTheme.typography.labelMedium)
                }
                Spacer(Modifier.width(10.dp))
                SmallToggleButton("Snap ${gridLabel(gridBeats)}", snapEnabled, { snapEnabled = !snapEnabled })
                Spacer(Modifier.width(6.dp))
                SmallToggleButton("1", gridBeats == 1.0, { gridBeats = 1.0 })
                SmallToggleButton("1/2", gridBeats == 0.5, { gridBeats = 0.5 })
                SmallToggleButton("1/4", gridBeats == 0.25, { gridBeats = 0.25 })
                Spacer(Modifier.weight(1f))
                Text("Zoom", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Slider(
                    value = pxPerBeat,
                    onValueChange = { pxPerBeat = it },
                    valueRange = 18f..130f,
                    modifier = Modifier.width(110.dp)
                )
            }
        }

        // Ruler
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.width(TRACK_HEADER_WIDTH).height(26.dp).background(MaterialTheme.colorScheme.surface)) {
                Text(
                    "Bars",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterStart).padding(start = 10.dp)
                )
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(26.dp)
                    .horizontalScroll(horizontalScroll, enabled = false)
            ) {
                val rulerBg = MaterialTheme.colorScheme.surface
                val lineColor = MaterialTheme.colorScheme.outline
                val textColor = MaterialTheme.colorScheme.onSurfaceVariant
                Canvas(
                    Modifier
                        .width((lengthBeats * pxPerBeat).dp)
                        .fillMaxHeight()
                        .background(rulerBg)
                        .pointerInput(pxPerBeat) {
                            detectTapGestures { offset ->
                                val beat = (offset.x / pxPerBeat).toDouble()
                                val snapped = if (snapEnabled) round(beat / gridBeats) * gridBeats else beat
                                controller.seekBeats(snapped.coerceAtLeast(0.0))
                            }
                        }
                ) {
                    val bars = (lengthBeats / project.beatsPerBar).toInt()
                    for (bar in 0..bars) {
                        val x = bar * project.beatsPerBar * pxPerBeat.dp.toPx()
                        drawLine(lineColor, Offset(x, size.height * 0.4f), Offset(x, size.height), 1.dp.toPx())
                    }
                    // loop region
                    if (project.loopEnabled) {
                        val x1 = (project.loopStartBeats * pxPerBeat).dp.toPx()
                        val x2 = (project.loopEndBeats * pxPerBeat).dp.toPx()
                        drawRect(SoraPalette.Accent.copy(alpha = 0.25f), topLeft = Offset(x1, 0f), size = androidx.compose.ui.geometry.Size(x2 - x1, size.height))
                    }
                    // markers
                    for (marker in project.markers) {
                        val x = (marker.beat * pxPerBeat).dp.toPx()
                        drawLine(SoraPalette.Warning, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                    }
                    @Suppress("UNUSED_EXPRESSION") textColor
                }
            }
        }

        // Tracks
        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Headers
            Column(
                Modifier
                    .width(TRACK_HEADER_WIDTH)
                    .fillMaxHeight()
                    .verticalScroll(verticalScroll, enabled = false)
                    .background(MaterialTheme.colorScheme.surface)
            ) {
                for (track in project.tracks) {
                    TrackHeader(
                        track = track,
                        selected = track.id == selectedTrackId,
                        onSelect = { session.selectTrack(track.id) },
                        onMute = { session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(mute = !it.mute) } } },
                        onSolo = { session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(solo = !it.solo) } } },
                        onRename = { renameTarget = track },
                        onDuplicate = { session.update { ProjectOps.duplicateTrack(it, track.id) } },
                        onDelete = { session.update { ProjectOps.deleteTrack(it, track.id) } },
                        onToggleLock = { session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(locked = !it.locked) } } },
                        onNextColor = {
                            val idx = TrackColors.PALETTE.indexOf(track.colorArgb)
                            val next = TrackColors.forIndex(idx + 1)
                            session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(colorArgb = next) } }
                        }
                    )
                }
                if (project.tracks.isEmpty()) {
                    Text(
                        "Add a track to get started",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            // Lanes
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .horizontalScroll(horizontalScroll)
            ) {
                Column(
                    Modifier
                        .width((lengthBeats * pxPerBeat).dp)
                        .verticalScroll(verticalScroll)
                ) {
                    for (track in project.tracks) {
                        TrackLane(
                            track = track,
                            clips = project.clipsForTrack(track.id),
                            pxPerBeat = pxPerBeat,
                            beatsPerBar = project.beatsPerBar,
                            lengthBeats = lengthBeats,
                            selectedClipId = selectedClipId,
                            controller = controller,
                            onSelectClip = { session.selectClip(it) },
                            onMoveClip = { clip, newStart ->
                                session.update { p -> ProjectOps.moveClip(p, clip.id, newStart) }
                            },
                            onUndoCheckpoint = { session.pushUndoCheckpoint() },
                            snapEnabled = snapEnabled,
                            gridBeats = gridBeats,
                            onLaneTap = { beat ->
                                session.selectTrack(track.id)
                                session.selectClip(null)
                                controller.seekBeats(beat)
                            }
                        )
                    }
                }
                // Playhead
                val playheadX = (playheadBeats * pxPerBeat).dp
                Box(
                    Modifier
                        .offset(x = playheadX)
                        .width(1.5.dp)
                        .fillMaxHeight()
                        .background(SoraPalette.Playhead.copy(alpha = if (transport.playing) 1f else 0.55f))
                )
            }
        }

        // Inspector / actions
        val selectedClip = project.clipById(selectedClipId)
        val selectedTrack = project.trackById(selectedTrackId)
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 1.dp) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (selectedClip != null) {
                    ClipInspector(
                        clip = selectedClip,
                        session = session,
                        playheadBeats = playheadBeats,
                        onOpenPianoRoll = onOpenPianoRoll,
                        onOpenDrums = onOpenDrums,
                        onEditAudioClip = onEditAudioClip
                    )
                } else if (selectedTrack != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ColorDot(selectedTrack.colorArgb.toComposeColor())
                        Spacer(Modifier.width(8.dp))
                        Text(selectedTrack.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when (selectedTrack.type) {
                            TrackType.INSTRUMENT, TrackType.SAMPLE -> TextButton(onClick = {
                                val clip = SClip(
                                    trackId = selectedTrack.id, name = "New Melody", type = ClipType.MIDI,
                                    startBeats = snapBeat(playheadBeats, snapEnabled, gridBeats), lengthBeats = project.beatsPerBar * 1.0
                                )
                                session.update { ProjectOps.addClip(it, clip) }
                                session.selectClip(clip.id)
                                onOpenPianoRoll(clip.id)
                            }) { Text("+ MIDI Clip") }
                            TrackType.DRUM -> TextButton(onClick = {
                                val pattern = SDrumPattern.emptyPattern(name = "Pattern ${project.patterns.size + 1}", kitId = selectedTrack.drumKitId)
                                val clip = SClip(
                                    trackId = selectedTrack.id, name = pattern.name, type = ClipType.PATTERN,
                                    startBeats = snapBeat(playheadBeats, snapEnabled, gridBeats), lengthBeats = 4.0, patternId = pattern.id
                                )
                                session.update { ProjectOps.addClip(ProjectOps.addPattern(it, pattern), clip) }
                                session.selectClip(clip.id)
                                onOpenDrums(pattern.id)
                            }) { Text("+ Drum Pattern") }
                            else -> Text(
                                "Record or import audio to add clips",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            val beat = snapBeat(playheadBeats, true, project.beatsPerBar.toDouble())
                            session.update { ProjectOps.addMarker(it, beat, "Marker") }
                        }) { Text("+ Marker") }
                        TextButton(onClick = {
                            val start = snapBeat(playheadBeats, true, project.beatsPerBar.toDouble())
                            session.update { it.copy(loopEnabled = true, loopStartBeats = start, loopEndBeats = start + project.beatsPerBar * 2) }
                        }) { Text("Loop 2 bars here") }
                    }
                    project.markers.takeIf { it.isNotEmpty() }?.let { markers ->
                        Row(Modifier.padding(top = 2.dp)) {
                            for (marker in markers) {
                                SmallToggleButton("${marker.name} ✕", false, {
                                    session.update { ProjectOps.deleteMarker(it, marker.id) }
                                })
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                    }
                } else {
                    Text(
                        "Tap a track or clip. Drag clips to move them; tap the ruler to move the playhead.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    if (showAddTrack) {
        AlertDialog(
            onDismissRequest = { showAddTrack = false },
            title = { Text("Add Track") },
            text = {
                Column {
                    for (type in TrackType.values()) {
                        TextButton(onClick = {
                            showAddTrack = false
                            val (_, newTrack) = ProjectOps.addTrack(session.current, type)
                            session.update { ProjectOps.addTrack(it, type).first }
                            // select the newly added track of this type (last one)
                            session.current.tracks.lastOrNull { it.type == type }?.let { session.selectTrack(it.id) }
                            @Suppress("UNUSED_EXPRESSION") newTrack
                        }, modifier = Modifier.fillMaxWidth()) {
                            Text(trackTypeLabel(type), modifier = Modifier.weight(1f))
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddTrack = false }) { Text("Cancel") } }
        )
    }

    renameTarget?.let { track ->
        var text by remember(track.id) { mutableStateOf(track.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename Track") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true) },
            confirmButton = {
                Button(onClick = {
                    session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(name = text.ifBlank { it.name }) } }
                    renameTarget = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("Cancel") } }
        )
    }
    @Suppress("UNUSED_EXPRESSION") density
}

private fun gridLabel(gridBeats: Double): String = when (gridBeats) {
    1.0 -> "1"
    0.5 -> "1/2"
    0.25 -> "1/4"
    else -> gridBeats.toString()
}

private fun snapBeat(beat: Double, snap: Boolean, grid: Double): Double =
    if (snap && grid > 0) (round(beat / grid) * grid).coerceAtLeast(0.0) else beat.coerceAtLeast(0.0)

fun trackTypeLabel(type: TrackType): String = when (type) {
    TrackType.AUDIO -> "Audio Track"
    TrackType.VOCAL -> "Vocal Track"
    TrackType.INSTRUMENT -> "Instrument (MIDI)"
    TrackType.DRUM -> "Drum Track"
    TrackType.SAMPLE -> "Sampler Track"
}

@Composable
private fun TrackHeader(
    track: STrack,
    selected: Boolean,
    onSelect: () -> Unit,
    onMute: () -> Unit,
    onSolo: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onToggleLock: () -> Unit,
    onNextColor: () -> Unit
) {
    var menuOpen by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(0.dp)
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .border(0.5.dp, MaterialTheme.colorScheme.outline)
            .clickable { onSelect() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.clickable { onNextColor() }) { ColorDot(track.colorArgb.toComposeColor(), 11.dp) }
                Spacer(Modifier.width(6.dp))
                Text(
                    track.name,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Filled.MoreVert, "Track menu", modifier = Modifier.size(16.dp))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menuOpen = false; onRename() })
                        DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(if (track.locked) "Unlock" else "Lock") }, onClick = { menuOpen = false; onToggleLock() })
                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Spacer(Modifier.height(3.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallToggleButton("M", track.mute, onMute, activeColor = SoraPalette.Warning)
                Spacer(Modifier.width(4.dp))
                SmallToggleButton("S", track.solo, onSolo, activeColor = SoraPalette.Success)
                Spacer(Modifier.width(6.dp))
                Icon(
                    if (track.locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    "Lock",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(12.dp).clickable { onToggleLock() }
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    trackTypeLabel(track.type).substringBefore(" "),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun TrackLane(
    track: STrack,
    clips: List<SClip>,
    pxPerBeat: Float,
    beatsPerBar: Int,
    lengthBeats: Double,
    selectedClipId: String?,
    controller: PlaybackController,
    onSelectClip: (String) -> Unit,
    onMoveClip: (SClip, Double) -> Unit,
    onUndoCheckpoint: () -> Unit,
    snapEnabled: Boolean,
    gridBeats: Double,
    onLaneTap: (Double) -> Unit
) {
    val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.45f)
    val barColor = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .border(0.5.dp, MaterialTheme.colorScheme.outline)
            .background(MaterialTheme.colorScheme.background)
            .pointerInput(track.id, pxPerBeat) {
                detectTapGestures { offset ->
                    onLaneTap(snapBeat((offset.x / pxPerBeat).toDouble(), snapEnabled, gridBeats))
                }
            }
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val totalBeats = lengthBeats.toInt()
            for (beat in 0..totalBeats) {
                val x = beat * pxPerBeat.dp.toPx()
                val isBar = beat % beatsPerBar == 0
                drawLine(if (isBar) barColor else gridColor, Offset(x, 0f), Offset(x, size.height), 0.7.dp.toPx())
            }
        }
        for (clip in clips) {
            ClipBox(
                clip = clip,
                track = track,
                pxPerBeat = pxPerBeat,
                selected = clip.id == selectedClipId,
                controller = controller,
                onSelect = { onSelectClip(clip.id) },
                onMove = { newStart -> onMoveClip(clip, newStart) },
                onUndoCheckpoint = onUndoCheckpoint,
                snapEnabled = snapEnabled,
                gridBeats = gridBeats
            )
        }
    }
}

@Composable
private fun ClipBox(
    clip: SClip,
    track: STrack,
    pxPerBeat: Float,
    selected: Boolean,
    controller: PlaybackController,
    onSelect: () -> Unit,
    onMove: (Double) -> Unit,
    onUndoCheckpoint: () -> Unit,
    snapEnabled: Boolean,
    gridBeats: Double
) {
    val densityPxPerDp = LocalDensity.current.density
    var dragOffsetPx by remember(clip.id) { mutableFloatStateOf(0f) }
    val color = track.colorArgb.toComposeColor()
    val xDp = ((clip.startBeats * pxPerBeat).toFloat() + dragOffsetPx).dp
    val widthDp = (clip.lengthBeats * pxPerBeat).dp
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .offset(x = xDp, y = 4.dp)
            .width(widthDp)
            .height(TRACK_HEIGHT - 8.dp)
            .clip(shape)
            .background(color.copy(alpha = if (selected) 0.42f else 0.26f))
            .border(if (selected) 1.5.dp else 0.75.dp, if (selected) color else color.copy(alpha = 0.7f), shape)
            .pointerInput(clip.id, track.locked) {
                if (!track.locked) {
                    detectDragGestures(
                        onDragStart = { onSelect(); onUndoCheckpoint() },
                        onDragEnd = {
                            val deltaBeats = (dragOffsetPx / pxPerBeat).toDouble()
                            val target = snapBeat(clip.startBeats + deltaBeats, snapEnabled, gridBeats)
                            dragOffsetPx = 0f
                            onMove(target)
                        },
                        onDragCancel = { dragOffsetPx = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetPx += dragAmount.x / densityPxPerDp
                        }
                    )
                }
            }
            .clickable { onSelect() }
    ) {
        Column(Modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 3.dp)) {
            Text(
                clip.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            when (clip.type) {
                ClipType.AUDIO -> {
                    val peaks = rememberClipPeaks(clip, controller)
                    if (peaks != null) {
                        WaveformCanvas(peaks = peaks, modifier = Modifier.fillMaxWidth().weight(1f), color = color.copy(alpha = 0.9f))
                    } else {
                        Text("audio", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                ClipType.MIDI -> {
                    MiniNotesPreview(clip, color, Modifier.fillMaxWidth().weight(1f))
                }
                ClipType.PATTERN -> {
                    Text("pattern", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun rememberClipPeaks(clip: SClip, controller: PlaybackController): FloatArray? {
    var peaks by remember(clip.id, clip.audioPath) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(clip.id, clip.audioPath) {
        val path = clip.audioPath ?: return@LaunchedEffect
        // Try now; if not decoded yet, retry briefly after preload.
        repeat(6) {
            val file = controller.resolvePath(path)
            val data = file?.let { controller.assetCache.stereoAt(it, controller.sampleRate) }
            if (data != null) {
                peaks = waveformPeaksFor(data, 180)
                return@LaunchedEffect
            }
            delay(400)
        }
    }
    return peaks
}

@Composable
private fun MiniNotesPreview(clip: SClip, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (clip.notes.isEmpty() || clip.lengthBeats <= 0) return@Canvas
        val minPitch = clip.notes.minOf { it.pitch }
        val maxPitch = clip.notes.maxOf { it.pitch }
        val range = (maxPitch - minPitch).coerceAtLeast(7)
        val contentEnd = clip.contentOffsetBeats + clip.lengthBeats
        for (note in clip.notes) {
            if (note.endBeats < clip.contentOffsetBeats || note.startBeats > contentEnd) continue
            val x = ((note.startBeats - clip.contentOffsetBeats) / clip.lengthBeats * size.width).toFloat()
            val w = ((note.lengthBeats / clip.lengthBeats) * size.width).toFloat().coerceAtLeast(2f)
            val yNorm = (note.pitch - minPitch).toFloat() / range
            val y = size.height - yNorm * size.height - 3.dp.toPx()
            drawRect(color.copy(alpha = 0.9f), topLeft = Offset(x, y), size = androidx.compose.ui.geometry.Size(w, 2.5.dp.toPx()))
        }
    }
}

@Composable
private fun ClipInspector(
    clip: SClip,
    session: ProjectSession,
    playheadBeats: Double,
    onOpenPianoRoll: (String) -> Unit,
    onOpenDrums: (String) -> Unit,
    onEditAudioClip: (SClip) -> Unit
) {
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(clip.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "start ${"%.2f".format(clip.startBeats)} · len ${"%.2f".format(clip.lengthBeats)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = {
                session.update { ProjectOps.splitClip(it, clip.id, playheadBeats) }
            }) { Icon(Icons.Filled.ContentCut, "Split at playhead") }
            IconButton(onClick = {
                session.update { ProjectOps.duplicateClip(it, clip.id) }
            }) { Icon(Icons.Filled.ContentCopy, "Duplicate") }
            IconButton(onClick = {
                session.update { ProjectOps.deleteClip(it, clip.id) }
                session.selectClip(null)
            }) { Icon(Icons.Filled.Delete, "Delete clip", tint = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.width(4.dp))
            when (clip.type) {
                ClipType.MIDI -> TextButton(onClick = { onOpenPianoRoll(clip.id) }) { Text("Piano Roll") }
                ClipType.PATTERN -> TextButton(onClick = { clip.patternId?.let(onOpenDrums) }) { Text("Open Drums") }
                ClipType.AUDIO -> TextButton(onClick = { onEditAudioClip(clip) }) { Text("Edit Audio") }
            }
            SmallToggleButton("Mute", clip.muted, {
                session.update { p -> ProjectOps.updateClip(p, clip.id) { it.copy(muted = !it.muted) } }
            }, activeColor = SoraPalette.Warning)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Gain", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
            Slider(
                value = clip.gainDb,
                onValueChange = { v -> session.update(recordUndo = false) { p -> ProjectOps.updateClip(p, clip.id) { it.copy(gainDb = v) } } },
                valueRange = -24f..12f,
                modifier = Modifier.weight(1f)
            )
            Text("${"%+.0f".format(clip.gainDb)} dB", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Fade in", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(52.dp))
            Slider(
                value = clip.fadeInBeats.toFloat(),
                onValueChange = { v -> session.update(recordUndo = false) { p -> ProjectOps.updateClip(p, clip.id) { it.copy(fadeInBeats = v.toDouble()) } } },
                valueRange = 0f..2f,
                modifier = Modifier.weight(1f)
            )
            Text("Fade out", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(56.dp))
            Slider(
                value = clip.fadeOutBeats.toFloat(),
                onValueChange = { v -> session.update(recordUndo = false) { p -> ProjectOps.updateClip(p, clip.id) { it.copy(fadeOutBeats = v.toDouble()) } } },
                valueRange = 0f..2f,
                modifier = Modifier.weight(1f)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Trim ◀", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { session.update { ProjectOps.trimClipLeft(it, clip.id, clip.startBeats - 0.25) } }) { Text("-¼") }
            TextButton(onClick = { session.update { ProjectOps.trimClipLeft(it, clip.id, clip.startBeats + 0.25) } }) { Text("+¼") }
            Spacer(Modifier.width(8.dp))
            Text("Trim ▶", style = MaterialTheme.typography.labelSmall)
            TextButton(onClick = { session.update { ProjectOps.trimClipRight(it, clip.id, clip.endBeats - 0.25) } }) { Text("-¼") }
            TextButton(onClick = { session.update { ProjectOps.trimClipRight(it, clip.id, clip.endBeats + 0.25) } }) { Text("+¼") }
            if (clip.type == ClipType.AUDIO) {
                Spacer(Modifier.width(8.dp))
                Text("Pitch", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    session.update { p -> ProjectOps.updateClip(p, clip.id) { it.copy(pitchSemitones = (it.pitchSemitones - 1).coerceIn(-12f, 12f)) } }
                }) { Text("−") }
                Text("${"%+.0f".format(clip.pitchSemitones)} st", style = MaterialTheme.typography.labelSmall)
                TextButton(onClick = {
                    session.update { p -> ProjectOps.updateClip(p, clip.id) { it.copy(pitchSemitones = (it.pitchSemitones + 1).coerceIn(-12f, 12f)) } }
                }) { Text("+") }
            }
        }
    }
}

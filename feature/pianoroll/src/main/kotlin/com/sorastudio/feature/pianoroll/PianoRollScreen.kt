package com.sorastudio.feature.pianoroll

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.ChordQuality
import com.sorastudio.core.common.NoteUtils
import com.sorastudio.core.common.Progressions
import com.sorastudio.core.common.ScaleType
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.designsystem.toComposeColor
import com.sorastudio.core.midi.ArpPattern
import com.sorastudio.core.midi.Arpeggiator
import com.sorastudio.core.midi.ChordGenerator
import com.sorastudio.core.midi.MidiTransform
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.SNote
import com.sorastudio.core.project.toMidi
import com.sorastudio.core.project.toS
import kotlinx.coroutines.delay
import kotlin.math.round

private const val ROW_COUNT = 24
private const val AUDITION_KEY = "pianoroll"

/**
 * Piano roll editor over the selected MIDI clip.
 * Tap empty cell = draw note · tap note = select · long-press note = delete ·
 * drag note = move · drag ruler = scroll · drag key strip = scroll pitches.
 */
@Composable
fun PianoRollScreen(
    session: ProjectSession,
    controller: PlaybackController,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val selectedClipId by session.selectedClipId.collectAsState()

    val track = project.trackById(selectedTrackId)
    val clip: SClip? = remember(project, selectedClipId, selectedTrackId) {
        val byId = project.clipById(selectedClipId)
        when {
            byId != null && byId.type == ClipType.MIDI -> byId
            else -> selectedTrackId?.let { tid -> project.clipsForTrack(tid).firstOrNull { it.type == ClipType.MIDI } }
        }
    }

    var gridBeats by remember { mutableDoubleStateOf(0.25) }
    var pxPerBeat by remember { mutableFloatStateOf(64f) }
    var topPitch by remember { mutableIntStateOf(84) } // C6 at top
    var scrollBeats by remember { mutableDoubleStateOf(0.0) }
    var drawVelocity by remember { mutableIntStateOf(100) }
    var selectedNoteId by remember { mutableStateOf<String?>(null) }
    var chordStamp by remember { mutableStateOf<ChordQuality?>(null) }
    var showScale by remember { mutableStateOf(true) }
    var showGenerateMenu by remember { mutableStateOf(false) }
    var showChordMenu by remember { mutableStateOf(false) }

    var playheadBeats by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        while (true) {
            playheadBeats = controller.positionBeats()
            delay(50)
        }
    }

    val scale = remember(project.keyScale) {
        runCatching { ScaleType.valueOf(project.keyScale) }.getOrDefault(ScaleType.MAJOR)
    }
    val density = LocalDensity.current.density

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Toolbar
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    clip?.name ?: "No MIDI clip",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(end = 10.dp)
                )
                SmallToggleButton("1/4", gridBeats == 1.0, { gridBeats = 1.0 })
                SmallToggleButton("1/8", gridBeats == 0.5, { gridBeats = 0.5 })
                SmallToggleButton("1/16", gridBeats == 0.25, { gridBeats = 0.25 })
                SmallToggleButton("1/32", gridBeats == 0.125, { gridBeats = 0.125 })
                Spacer(Modifier.width(8.dp))
                SmallToggleButton("Scale", showScale, { showScale = !showScale })
                Spacer(Modifier.width(8.dp))
                Box {
                    SmallToggleButton(if (chordStamp == null) "Chord ▾" else "Chord: ${chordStamp!!.suffix.ifEmpty { "maj" }}", chordStamp != null, { showChordMenu = true })
                    DropdownMenu(expanded = showChordMenu, onDismissRequest = { showChordMenu = false }) {
                        DropdownMenuItem(text = { Text("Off (single notes)") }, onClick = { chordStamp = null; showChordMenu = false })
                        for (q in ChordQuality.values()) {
                            DropdownMenuItem(text = { Text(q.displayName) }, onClick = { chordStamp = q; showChordMenu = false })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                Box {
                    SmallToggleButton("Generate ▾", false, { showGenerateMenu = true })
                    DropdownMenu(expanded = showGenerateMenu, onDismissRequest = { showGenerateMenu = false }) {
                        DropdownMenuItem(text = { Text("Quantize") }, onClick = {
                            showGenerateMenu = false
                            if (clip != null) {
                                val transformed = MidiTransform.quantize(clip.notes.map { it.toMidi() }, gridBeats).map { it.toS() }
                                session.update { ProjectOps.setNotes(it, clip.id, transformed) }
                            }
                        })
                        DropdownMenuItem(text = { Text("Humanize") }, onClick = {
                            showGenerateMenu = false
                            if (clip != null) {
                                val transformed = MidiTransform.humanize(clip.notes.map { it.toMidi() }).map { it.toS() }
                                session.update { ProjectOps.setNotes(it, clip.id, transformed) }
                            }
                        })
                        DropdownMenuItem(text = { Text("Transpose +1") }, onClick = {
                            showGenerateMenu = false
                            if (clip != null) {
                                val transformed = MidiTransform.transpose(clip.notes.map { it.toMidi() }, 1).map { it.toS() }
                                session.update { ProjectOps.setNotes(it, clip.id, transformed) }
                            }
                        })
                        DropdownMenuItem(text = { Text("Transpose −1") }, onClick = {
                            showGenerateMenu = false
                            if (clip != null) {
                                val transformed = MidiTransform.transpose(clip.notes.map { it.toMidi() }, -1).map { it.toS() }
                                session.update { ProjectOps.setNotes(it, clip.id, transformed) }
                            }
                        })
                        DropdownMenuItem(text = { Text("Arpeggio (from notes)") }, onClick = {
                            showGenerateMenu = false
                            if (clip != null && clip.notes.isNotEmpty()) {
                                val pitches = clip.notes.map { it.pitch }.distinct().sorted()
                                val start = clip.notes.minOf { it.startBeats }
                                val arp = Arpeggiator.generate(pitches, ArpPattern.UP_DOWN, rateBeats = gridBeats, totalBeats = clip.lengthBeats + clip.contentOffsetBeats - start, startBeats = start).map { it.toS() }
                                session.update { ProjectOps.setNotes(it, clip.id, arp) }
                            }
                        })
                        for (progression in Progressions.ALL) {
                            DropdownMenuItem(text = { Text("Add ${progression.name}") }, onClick = {
                                showGenerateMenu = false
                                if (clip != null) {
                                    val root = 60 + project.keyRoot
                                    val notes = ChordGenerator.progressionNotes(root, progression, beatsPerChord = project.beatsPerBar.toDouble()).map { it.toS() }
                                    val needed = notes.maxOfOrNull { it.endBeats } ?: 0.0
                                    session.update { p ->
                                        var updated = ProjectOps.setNotes(p, clip.id, clip.notes + notes)
                                        if (clip.lengthBeats < needed) {
                                            updated = ProjectOps.updateClip(updated, clip.id) { it.copy(lengthBeats = needed) }
                                        }
                                        updated
                                    }
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("Delete all notes") }, onClick = {
                            showGenerateMenu = false
                            if (clip != null) session.update { ProjectOps.setNotes(it, clip.id, emptyList()) }
                        })
                    }
                }
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.KeyboardArrowUp, "Octave up",
                    modifier = Modifier.size(22.dp).clickable { topPitch = (topPitch + 12).coerceAtMost(118) }
                )
                Icon(
                    Icons.Filled.KeyboardArrowDown, "Octave down",
                    modifier = Modifier.size(22.dp).clickable { topPitch = (topPitch - 12).coerceAtLeast(24) }
                )
                Spacer(Modifier.width(6.dp))
                Text("Vel $drawVelocity", style = MaterialTheme.typography.labelSmall)
                Slider(
                    value = drawVelocity.toFloat(),
                    onValueChange = { drawVelocity = it.toInt() },
                    valueRange = 1f..127f,
                    modifier = Modifier.width(90.dp)
                )
            }
        }

        if (clip == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No MIDI clip selected", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Select an instrument or sampler track, then create a clip to start writing notes.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (track != null && (track.type.name == "INSTRUMENT" || track.type.name == "SAMPLE")) {
                        Spacer(Modifier.height(14.dp))
                        Button(onClick = {
                            val newClip = SClip(
                                trackId = track.id, name = "Melody", type = ClipType.MIDI,
                                startBeats = 0.0, lengthBeats = project.beatsPerBar * 2.0
                            )
                            session.update { ProjectOps.addClip(it, newClip) }
                            session.selectClip(newClip.id)
                        }) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Create MIDI Clip")
                        }
                    }
                }
            }
            return@Column
        }

        val trackColor = track?.colorArgb?.toComposeColor() ?: SoraPalette.Accent
        val presetId = track?.presetId ?: "piano-grand"
        val rowHeightDp = 16.dp
        val keyStripWidth = 52.dp

        fun audition(pitch: Int) {
            controller.noteOn(AUDITION_KEY, presetId, pitch, drawVelocity / 127f, track?.synthParams ?: emptyMap())
            controller.noteOff(AUDITION_KEY, pitch)
        }

        /** Offsets arrive in dp units; returns (snapped view beat, pitch). */
        fun cellAt(offsetXDp: Float, offsetYDp: Float): Pair<Double, Int> {
            val viewBeat = scrollBeats + (offsetXDp / pxPerBeat).toDouble()
            val pitch = topPitch - (offsetYDp / 16f).toInt()
            val snappedBeat = round(viewBeat / gridBeats) * gridBeats
            return snappedBeat to pitch.coerceIn(0, 127)
        }

        fun noteAt(viewBeat: Double, pitch: Int): SNote? {
            val contentBeat = viewBeat + clip.contentOffsetBeats
            return clip.notes.firstOrNull { it.pitch == pitch && contentBeat >= it.startBeats && contentBeat < it.endBeats }
        }

        // Ruler (drag = scroll horizontally, tap = seek)
        Row(Modifier.fillMaxWidth().height(22.dp)) {
            Box(Modifier.width(keyStripWidth).fillMaxHeight().background(MaterialTheme.colorScheme.surface))
            val rulerLine = MaterialTheme.colorScheme.outline
            Canvas(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(pxPerBeat) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                scrollBeats = (scrollBeats - drag.x / density / pxPerBeat).coerceAtLeast(0.0)
                            }
                        )
                    }
                    .pointerInput(pxPerBeat) {
                        detectTapGestures { offset ->
                            val viewBeat = scrollBeats + offset.x / density / pxPerBeat
                            controller.seekBeats(clip.startBeats + viewBeat)
                        }
                    }
            ) {
                var beat = kotlin.math.floor(scrollBeats).toInt()
                while ((beat - scrollBeats) * pxPerBeat.dp.toPx() < size.width) {
                    val x = ((beat - scrollBeats) * pxPerBeat).dp.toPx()
                    val isBar = beat % project.beatsPerBar == 0
                    drawLine(rulerLine, Offset(x, if (isBar) 0f else size.height / 2), Offset(x, size.height), 1.dp.toPx())
                    beat++
                }
            }
        }

        Row(Modifier.weight(1f).fillMaxWidth()) {
            // Key strip (drag = scroll pitch, tap = audition)
            val blackKeyColor = Color(0xFF17181D)
            val whiteKeyColor = Color(0xFFEDEDF0)
            Canvas(
                Modifier
                    .width(keyStripWidth)
                    .fillMaxHeight()
                    .pointerInput(Unit) {
                        detectDragGestures { change, drag ->
                            change.consume()
                            val rows = (drag.y / density / 16f).toInt()
                            if (rows != 0) topPitch = (topPitch + rows).coerceIn(ROW_COUNT - 1, 127)
                        }
                    }
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val pitch = topPitch - (offset.y / density / 16f).toInt()
                            audition(pitch)
                        }
                    }
            ) {
                val rowH = rowHeightDp.toPx()
                for (r in 0 until ROW_COUNT) {
                    val pitch = topPitch - r
                    if (pitch < 0) break
                    val y = r * rowH
                    val isBlack = NoteUtils.isBlackKey(pitch)
                    drawRect(
                        if (isBlack) blackKeyColor else whiteKeyColor,
                        topLeft = Offset(if (isBlack) size.width * 0.3f else 0f, y),
                        size = Size(if (isBlack) size.width * 0.7f else size.width, rowH - 1f)
                    )
                    if (pitch % 12 == 0) {
                        drawCircle(SoraPalette.Accent, radius = 2.dp.toPx(), center = Offset(7.dp.toPx(), y + rowH / 2))
                    }
                }
            }

            // Grid
            val gridBeatColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
            val gridBarColor = MaterialTheme.colorScheme.outline
            val rowAlt = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            val outOfScaleColor = MaterialTheme.colorScheme.background.copy(alpha = 0.45f)
            var dragState by remember { mutableStateOf<Triple<String, Double, Int>?>(null) } // noteId, grabOffsetContent, origPitch
            var dragPos by remember { mutableStateOf<Offset?>(null) }

            Box(Modifier.weight(1f).fillMaxHeight()) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .pointerInput(clip.id, gridBeats, pxPerBeat, topPitch, scrollBeats) {
                            detectTapGestures(
                                onTap = { offset ->
                                    val (viewBeat, pitch) = cellAt(offset.x / density, offset.y / density)
                                    val existing = noteAt(viewBeat, pitch)
                                    if (existing != null) {
                                        selectedNoteId = existing.id
                                        audition(pitch)
                                    } else {
                                        val contentBeat = viewBeat + clip.contentOffsetBeats
                                        val stamp = chordStamp
                                        if (stamp != null) {
                                            val notes = ChordGenerator.chordNotes(pitch, stamp, contentBeat, project.beatsPerBar.toDouble(), drawVelocity).map { it.toS() }
                                            session.update { p ->
                                                var updated = p
                                                for (n in notes) updated = ProjectOps.addNote(updated, clip.id, n)
                                                updated
                                            }
                                        } else {
                                            val note = SNote(startBeats = contentBeat, lengthBeats = gridBeats, pitch = pitch, velocity = drawVelocity)
                                            session.update { ProjectOps.addNote(it, clip.id, note) }
                                            selectedNoteId = note.id
                                        }
                                        audition(pitch)
                                    }
                                },
                                onLongPress = { offset ->
                                    val (viewBeat, pitch) = cellAt(offset.x / density, offset.y / density)
                                    val existing = noteAt(viewBeat, pitch)
                                    if (existing != null) {
                                        session.update { ProjectOps.deleteNote(it, clip.id, existing.id) }
                                        if (selectedNoteId == existing.id) selectedNoteId = null
                                    }
                                }
                            )
                        }
                        .pointerInput(clip.id, gridBeats, pxPerBeat, topPitch, scrollBeats) {
                            detectDragGestures(
                                onDragStart = { offset ->
                                    val (viewBeat, pitch) = cellAt(offset.x / density, offset.y / density)
                                    val existing = noteAt(viewBeat, pitch)
                                    if (existing != null) {
                                        session.pushUndoCheckpoint()
                                        dragState = Triple(existing.id, (viewBeat + clip.contentOffsetBeats) - existing.startBeats, existing.pitch)
                                        dragPos = offset
                                        selectedNoteId = existing.id
                                    }
                                },
                                onDragEnd = {
                                    val state = dragState
                                    val pos = dragPos
                                    if (state != null && pos != null) {
                                        val (viewBeat, pitch) = cellAt(pos.x / density, pos.y / density)
                                        val newContent = (viewBeat + clip.contentOffsetBeats - state.second).coerceAtLeast(0.0)
                                        val snapped = round(newContent / gridBeats) * gridBeats
                                        session.update(recordUndo = false) { p ->
                                            ProjectOps.updateNote(p, clip.id, state.first) {
                                                it.copy(startBeats = snapped, pitch = pitch)
                                            }
                                        }
                                        audition(pitch)
                                    }
                                    dragState = null
                                    dragPos = null
                                },
                                onDragCancel = { dragState = null; dragPos = null },
                                onDrag = { change, _ ->
                                    change.consume()
                                    if (dragState != null) dragPos = change.position
                                }
                            )
                        }
                ) {
                    val rowH = rowHeightDp.toPx()
                    // rows
                    for (r in 0 until ROW_COUNT) {
                        val pitch = topPitch - r
                        if (pitch < 0) break
                        val y = r * rowH
                        if (NoteUtils.isBlackKey(pitch)) {
                            drawRect(rowAlt, topLeft = Offset(0f, y), size = Size(size.width, rowH))
                        }
                        if (showScale && !scale.contains(project.keyRoot, pitch)) {
                            drawRect(outOfScaleColor, topLeft = Offset(0f, y), size = Size(size.width, rowH))
                        }
                        drawLine(gridBeatColor, Offset(0f, y + rowH), Offset(size.width, y + rowH), 0.5.dp.toPx())
                    }
                    // beat lines
                    var beat = kotlin.math.floor(scrollBeats / gridBeats).toInt() * gridBeats
                    while ((beat - scrollBeats) * pxPerBeat.dp.toPx() < size.width + 4) {
                        val x = ((beat - scrollBeats) * pxPerBeat).dp.toPx()
                        val isBar = kotlin.math.abs(beat % project.beatsPerBar) < 0.001
                        val isBeat = kotlin.math.abs(beat % 1.0) < 0.001
                        drawLine(
                            if (isBar) gridBarColor else gridBeatColor,
                            Offset(x, 0f), Offset(x, size.height),
                            if (isBar) 1.dp.toPx() else if (isBeat) 0.7.dp.toPx() else 0.4.dp.toPx()
                        )
                        beat += gridBeats
                    }
                    // notes
                    for (note in clip.notes) {
                        if (note.pitch > topPitch || note.pitch < topPitch - ROW_COUNT) continue
                        val viewStart = note.startBeats - clip.contentOffsetBeats - scrollBeats
                        val x = (viewStart * pxPerBeat).dp.toPx()
                        val w = (note.lengthBeats * pxPerBeat).dp.toPx().coerceAtLeast(4f)
                        if (x + w < 0 || x > size.width) continue
                        val y = (topPitch - note.pitch) * rowH + 1.5f
                        val alpha = 0.45f + (note.velocity / 127f) * 0.55f
                        val isSelected = note.id == selectedNoteId
                        val isDragging = dragState?.first == note.id
                        drawRoundRect(
                            color = trackColor.copy(alpha = if (isDragging) alpha * 0.5f else alpha),
                            topLeft = Offset(x, y),
                            size = Size(w, rowH - 3f),
                            cornerRadius = CornerRadius(3.dp.toPx())
                        )
                        if (isSelected) {
                            drawRoundRect(
                                color = Color.White,
                                topLeft = Offset(x, y),
                                size = Size(w, rowH - 3f),
                                cornerRadius = CornerRadius(3.dp.toPx()),
                                style = androidx.compose.ui.graphics.drawscope.Stroke(1.2.dp.toPx())
                            )
                        }
                    }
                    // playhead
                    val clipLocalPlayhead = playheadBeats - clip.startBeats - scrollBeats
                    if (clipLocalPlayhead >= 0) {
                        val x = (clipLocalPlayhead * pxPerBeat).dp.toPx()
                        drawLine(SoraPalette.Playhead, Offset(x, 0f), Offset(x, size.height), 1.5.dp.toPx())
                    }
                }
            }
        }

        // Velocity lane + selected note tools
        val selectedNote = clip.notes.firstOrNull { it.id == selectedNoteId }
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
                Canvas(
                    Modifier
                        .fillMaxWidth()
                        .height(38.dp)
                        .pointerInput(clip.id, scrollBeats, pxPerBeat) {
                            detectTapGestures { offset ->
                                val viewBeat = scrollBeats + offset.x / density / pxPerBeat
                                val contentBeat = viewBeat + clip.contentOffsetBeats
                                val hit = clip.notes.firstOrNull { contentBeat >= it.startBeats && contentBeat < it.endBeats }
                                if (hit != null) selectedNoteId = hit.id
                            }
                        }
                ) {
                    for (note in clip.notes) {
                        val viewStart = note.startBeats - clip.contentOffsetBeats - scrollBeats
                        val x = (viewStart * pxPerBeat).dp.toPx()
                        if (x < 0 || x > size.width) continue
                        val h = size.height * (note.velocity / 127f)
                        drawRect(
                            if (note.id == selectedNoteId) SoraPalette.Accent else trackColor.copy(alpha = 0.6f),
                            topLeft = Offset(x, size.height - h),
                            size = Size(3.dp.toPx(), h)
                        )
                    }
                }
                if (selectedNote != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${NoteUtils.noteName(selectedNote.pitch)} · beat ${"%.2f".format(selectedNote.startBeats)} · len ${"%.2f".format(selectedNote.lengthBeats)}",
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.weight(1f)
                        )
                        Text("Vel", style = MaterialTheme.typography.labelSmall)
                        Slider(
                            value = selectedNote.velocity.toFloat(),
                            onValueChange = { v ->
                                session.update(recordUndo = false) { p ->
                                    ProjectOps.updateNote(p, clip.id, selectedNote.id) { it.copy(velocity = v.toInt().coerceIn(1, 127)) }
                                }
                            },
                            valueRange = 1f..127f,
                            modifier = Modifier.width(110.dp)
                        )
                        TextButton(onClick = {
                            session.update { p -> ProjectOps.updateNote(p, clip.id, selectedNote.id) { it.copy(lengthBeats = (it.lengthBeats + gridBeats).coerceAtMost(64.0)) } }
                        }) { Text("Len +") }
                        TextButton(onClick = {
                            session.update { p -> ProjectOps.updateNote(p, clip.id, selectedNote.id) { it.copy(lengthBeats = (it.lengthBeats - gridBeats).coerceAtLeast(gridBeats / 2)) } }
                        }) { Text("Len −") }
                        TextButton(onClick = {
                            session.update { ProjectOps.deleteNote(it, clip.id, selectedNote.id) }
                            selectedNoteId = null
                        }) { Text("Delete") }
                    }
                } else {
                    Text(
                        "Tap to draw · long-press a note to delete · drag to move. ${clip.notes.size} notes.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Scroll", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = scrollBeats.toFloat(),
                        onValueChange = { scrollBeats = it.toDouble() },
                        valueRange = 0f..(clip.lengthBeats.toFloat().coerceAtLeast(4f)),
                        modifier = Modifier.weight(1f)
                    )
                    Text("Zoom", style = MaterialTheme.typography.labelSmall)
                    Slider(
                        value = pxPerBeat,
                        onValueChange = { pxPerBeat = it },
                        valueRange = 24f..160f,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

package com.sorastudio.feature.automation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.designsystem.toComposeColor
import com.sorastudio.core.project.AutomationCurve
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SAutomationLane
import com.sorastudio.core.project.SAutomationPoint
import kotlinx.coroutines.delay

private data class LaneTarget(val target: String, val label: String, val min: Float, val max: Float, val default: Float)

/**
 * Automation editor: tap to add points, drag to move, long-press to delete.
 * "Record" captures the live playhead value by tapping/writing points while playing
 * (touch-write: drag on the canvas while transport plays writes values at the playhead).
 */
@Composable
fun AutomationScreen(
    session: ProjectSession,
    controller: PlaybackController,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val transport by controller.transport.collectAsState()
    val track = project.trackById(selectedTrackId)

    var activeLaneId by remember { mutableStateOf<String?>(null) }
    var showAddMenu by remember { mutableStateOf(false) }
    var recordArmed by remember { mutableStateOf(false) }
    var playheadBeats by remember { mutableDoubleStateOf(0.0) }
    val density = LocalDensity.current.density

    LaunchedEffect(Unit) {
        while (true) {
            playheadBeats = controller.positionBeats()
            delay(50)
        }
    }

    val trackLanes = if (track != null) project.automation.filter { it.trackId == track.id } else emptyList()
    val activeLane = trackLanes.firstOrNull { it.id == activeLaneId } ?: trackLanes.firstOrNull()
    val lengthBeats = project.computedLengthBeats()
    val pxPerBeat = 28f

    val availableTargets: List<LaneTarget> = buildList {
        add(LaneTarget("volume", "Volume", -60f, 6f, -3f))
        add(LaneTarget("pan", "Pan", -1f, 1f, 0f))
        add(LaneTarget("sendReverb", "Reverb Send", 0f, 1f, 0f))
        add(LaneTarget("sendDelay", "Delay Send", 0f, 1f, 0f))
        add(LaneTarget("synth:cutoff", "Synth Cutoff", 100f, 16000f, 4000f))
        add(LaneTarget("synth:resonance", "Synth Resonance", 0.2f, 12f, 0.7f))
        if (track != null) {
            for (fx in track.fxChain) {
                val type = runCatching { com.sorastudio.core.dsp.EffectType.valueOf(fx.type) }.getOrNull() ?: continue
                val params = com.sorastudio.core.dsp.EffectCatalog.PARAMS[type].orEmpty()
                for (param in params) {
                    add(LaneTarget("effect:${fx.id}:${param.key}", "${type.displayName} · ${param.label}", param.min, param.max, param.default))
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Automation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (track != null) "Track: ${track.name}" else "Select a track in the Arrangement",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    SmallToggleButton("● REC", recordArmed, { recordArmed = !recordArmed }, activeColor = SoraPalette.Record)
                    Spacer(Modifier.width(6.dp))
                    Box {
                        Button(onClick = { showAddMenu = true }, enabled = track != null) {
                            Icon(Icons.Filled.Add, null, modifier = Modifier.size(15.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Lane")
                        }
                        DropdownMenu(expanded = showAddMenu, onDismissRequest = { showAddMenu = false }) {
                            for (target in availableTargets) {
                                DropdownMenuItem(text = { Text(target.label) }, onClick = {
                                    showAddMenu = false
                                    if (track != null) {
                                        val lane = SAutomationLane(
                                            trackId = track.id, target = target.target,
                                            displayName = target.label, minValue = target.min, maxValue = target.max,
                                            points = listOf(SAutomationPoint(0.0, target.default))
                                        )
                                        session.update { ProjectOps.upsertAutomation(it, lane) }
                                        activeLaneId = lane.id
                                    }
                                })
                            }
                        }
                    }
                }
                if (trackLanes.isNotEmpty()) {
                    Spacer(Modifier.height(6.dp))
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        for (lane in trackLanes) {
                            SmallToggleButton(lane.displayName, lane.id == activeLane?.id, { activeLaneId = lane.id })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }
        }

        if (track == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Select a track to edit its automation.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }
        if (activeLane == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No automation lanes yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Add a lane to automate volume, pan, sends, filter or any effect parameter.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Column
        }

        val lane = activeLane
        val trackColor = track.colorArgb.toComposeColor()
        var dragPointIndex by remember { mutableStateOf<Int?>(null) }
        var dragPos by remember { mutableStateOf<Offset?>(null) }

        fun valueToY(value: Float, height: Float): Float {
            val t = (value - lane.minValue) / (lane.maxValue - lane.minValue)
            return height - t * height
        }
        fun yToValue(yDp: Float, heightDp: Float): Float {
            val t = 1f - (yDp / heightDp).coerceIn(0f, 1f)
            return lane.minValue + t * (lane.maxValue - lane.minValue)
        }

        SectionTitle(lane.displayName + "  ·  ${lane.minValue} … ${lane.maxValue}", Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row {
                for (curve in AutomationCurve.values()) {
                    SmallToggleButton(curve.name.lowercase().replaceFirstChar { it.uppercase() }, lane.curve == curve, {
                        session.update { ProjectOps.upsertAutomation(it, lane.copy(curve = curve)) }
                    })
                    Spacer(Modifier.width(4.dp))
                }
                IconButton(onClick = {
                    session.update { ProjectOps.deleteAutomation(it, lane.id) }
                    activeLaneId = null
                }, modifier = Modifier.size(30.dp)) {
                    Icon(Icons.Filled.Delete, "Delete lane", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(17.dp))
                }
            }
        }

        val gridColor = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
        ) {
            Canvas(
                Modifier
                    .width((lengthBeats * pxPerBeat).dp)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.surface)
                    .pointerInput(lane.id, pxPerBeat) {
                        detectTapGestures(
                            onTap = { offset ->
                                val beat = (offset.x / density / pxPerBeat).toDouble().coerceIn(0.0, lengthBeats)
                                val value = yToValue(offset.y / density, size.height / density)
                                val point = SAutomationPoint(
                                    beat = kotlin.math.round(beat * 4) / 4.0,
                                    value = value
                                )
                                session.update { p ->
                                    ProjectOps.upsertAutomation(p, lane.copy(points = (lane.points + point).sortedBy { it.beat }))
                                }
                            },
                            onLongPress = { offset ->
                                val beat = (offset.x / density / pxPerBeat).toDouble()
                                val nearest = lane.points.minByOrNull { kotlin.math.abs(it.beat - beat) }
                                if (nearest != null && kotlin.math.abs(nearest.beat - beat) < 0.6) {
                                    session.update { p ->
                                        ProjectOps.upsertAutomation(p, lane.copy(points = lane.points - nearest))
                                    }
                                }
                            }
                        )
                    }
                    .pointerInput(lane.id, pxPerBeat, recordArmed) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                if (recordArmed && transport.playing) {
                                    session.pushUndoCheckpoint()
                                } else {
                                    val beat = (offset.x / density / pxPerBeat).toDouble()
                                    val index = lane.points.indices.minByOrNull { kotlin.math.abs(lane.points[it].beat - beat) }
                                    if (index != null && kotlin.math.abs(lane.points[index].beat - beat) < 0.6) {
                                        session.pushUndoCheckpoint()
                                        dragPointIndex = index
                                        dragPos = offset
                                    }
                                }
                            },
                            onDragEnd = {
                                val index = dragPointIndex
                                val pos = dragPos
                                if (index != null && pos != null && index < lane.points.size) {
                                    val beat = (pos.x / density / pxPerBeat).toDouble().coerceIn(0.0, lengthBeats)
                                    val value = yToValue(pos.y / density, size.height / density)
                                    val moved = lane.points[index].copy(
                                        beat = kotlin.math.round(beat * 4) / 4.0, value = value
                                    )
                                    val newPoints = lane.points.mapIndexed { i, pt -> if (i == index) moved else pt }.sortedBy { it.beat }
                                    session.update(recordUndo = false) { p ->
                                        ProjectOps.upsertAutomation(p, lane.copy(points = newPoints))
                                    }
                                }
                                dragPointIndex = null; dragPos = null
                            },
                            onDragCancel = { dragPointIndex = null; dragPos = null },
                            onDrag = { change, _ ->
                                change.consume()
                                if (recordArmed && transport.playing) {
                                    // Touch-write at playhead
                                    val value = yToValue(change.position.y / density, size.height / density)
                                    val beat = (kotlin.math.round(playheadBeats * 4) / 4.0)
                                    val without = lane.points.filter { kotlin.math.abs(it.beat - beat) > 0.24 }
                                    val newPoints = (without + SAutomationPoint(beat, value)).sortedBy { it.beat }
                                    session.update(recordUndo = false) { p ->
                                        ProjectOps.upsertAutomation(p, lane.copy(points = newPoints))
                                    }
                                } else if (dragPointIndex != null) {
                                    dragPos = change.position
                                }
                            }
                        )
                    }
            ) {
                // grid: bars and value quartiles
                var beat = 0.0
                while (beat <= lengthBeats) {
                    val x = (beat * pxPerBeat).dp.toPx()
                    val isBar = kotlin.math.abs(beat % project.beatsPerBar) < 0.001
                    drawLine(gridColor, Offset(x, 0f), Offset(x, size.height), if (isBar) 1.dp.toPx() else 0.5.dp.toPx())
                    beat += 1.0
                }
                for (q in 1..3) {
                    val y = size.height * q / 4f
                    drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 0.5.dp.toPx())
                }
                // curve
                if (lane.points.isNotEmpty()) {
                    val path = Path()
                    val steps = 240
                    for (s in 0..steps) {
                        val b = lengthBeats * s / steps
                        val v = lane.valueAt(b) ?: lane.minValue
                        val x = (b * pxPerBeat).dp.toPx()
                        val y = valueToY(v, size.height)
                        if (s == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, trackColor, style = Stroke(2.dp.toPx()))
                    // fill under curve
                    val fill = Path()
                    fill.addPath(path)
                    fill.lineTo(size.width, size.height)
                    fill.lineTo(0f, size.height)
                    fill.close()
                    drawPath(fill, trackColor.copy(alpha = 0.12f))
                }
                // points
                lane.points.forEachIndexed { index, point ->
                    val x = (point.beat * pxPerBeat).dp.toPx()
                    val y = valueToY(point.value, size.height)
                    val dragging = index == dragPointIndex && dragPos != null
                    drawCircle(
                        if (dragging) SoraPalette.Accent else trackColor,
                        radius = if (dragging) 6.dp.toPx() else 4.5.dp.toPx(),
                        center = if (dragging) dragPos!! else Offset(x, y)
                    )
                    drawCircle(
                        androidx.compose.ui.graphics.Color.White, radius = 1.6.dp.toPx(),
                        center = if (dragging) dragPos!! else Offset(x, y)
                    )
                }
                // playhead
                val px = (playheadBeats * pxPerBeat).dp.toPx()
                drawLine(SoraPalette.Playhead, Offset(px, 0f), Offset(px, size.height), 1.5.dp.toPx())
                // current value at playhead
                lane.valueAt(playheadBeats)?.let { v ->
                    drawCircle(SoraPalette.Playhead, radius = 3.dp.toPx(), center = Offset(px, valueToY(v, size.height)))
                }
            }
        }

        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FiberManualRecord, null, tint = if (recordArmed) SoraPalette.Record else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    when {
                        recordArmed -> "REC armed: press Play and drag on the lane to write automation at the playhead."
                        else -> "Tap to add a point · drag to move · long-press to delete. ${lane.points.size} point(s)."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    session.update { ProjectOps.upsertAutomation(it, lane.copy(points = emptyList())) }
                }) { Text("Clear points") }
            }
        }
    }
}

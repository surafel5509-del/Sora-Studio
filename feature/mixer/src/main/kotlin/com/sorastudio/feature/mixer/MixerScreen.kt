package com.sorastudio.feature.mixer

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.LevelMath
import com.sorastudio.core.designsystem.ColorDot
import com.sorastudio.core.designsystem.RotaryKnob
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.designsystem.StereoMeter
import com.sorastudio.core.designsystem.VerticalFader
import com.sorastudio.core.designsystem.toComposeColor
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.STrack
import kotlinx.coroutines.delay

/**
 * Multichannel mixer. Every control writes to the project model and is applied
 * by the renderer in real time (faders commit one undo step per drag).
 */
@Composable
fun MixerScreen(
    session: ProjectSession,
    controller: PlaybackController,
    onOpenEffects: (trackId: String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()

    // Meter polling
    var masterPeakL by remember { mutableFloatStateOf(0f) }
    var masterPeakR by remember { mutableFloatStateOf(0f) }
    var masterRmsL by remember { mutableFloatStateOf(0f) }
    var masterRmsR by remember { mutableFloatStateOf(0f) }
    var meterSnapshot by remember { mutableStateOf(mapOf<String, FloatArray>()) }
    LaunchedEffect(Unit) {
        while (true) {
            val mm = controller.masterMeter()
            masterPeakL = mm.peakL; masterPeakR = mm.peakR; masterRmsL = mm.rmsL; masterRmsR = mm.rmsR
            val snapshot = HashMap<String, FloatArray>()
            for (track in session.current.tracks) {
                controller.trackMeter(track.id)?.let { m ->
                    snapshot[track.id] = floatArrayOf(m.peakL, m.peakR, m.rmsL, m.rmsR)
                }
            }
            meterSnapshot = snapshot
            delay(50)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Mixer", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Master", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp))
                    StereoMeter(masterPeakL, masterPeakR, masterRmsL, masterRmsR, modifier = Modifier.width(120.dp).height(16.dp), vertical = false)
                }
            }
        }

        if (project.tracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("Add tracks in the Arrangement to see mixer channels.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            return@Column
        }

        Row(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 12.dp)
        ) {
            for (track in project.tracks) {
                ChannelStrip(
                    track = track,
                    selected = track.id == selectedTrackId,
                    meter = meterSnapshot[track.id],
                    session = session,
                    onSelect = { session.selectTrack(track.id) },
                    onOpenEffects = { onOpenEffects(track.id) }
                )
                Spacer(Modifier.width(8.dp))
            }
            // Master strip
            MasterStrip(
                volumeDb = project.master.volumeDb,
                limiterEnabled = project.master.limiterEnabled,
                peakL = masterPeakL, peakR = masterPeakR, rmsL = masterRmsL, rmsR = masterRmsR,
                fxCount = project.master.fxChain.size,
                session = session,
                onOpenEffects = { onOpenEffects(null) }
            )
        }
    }
}

@Composable
private fun ChannelStrip(
    track: STrack,
    selected: Boolean,
    meter: FloatArray?,
    session: ProjectSession,
    onSelect: () -> Unit,
    onOpenEffects: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    var sendRevUi by remember(track.id, track.sendReverb) { mutableFloatStateOf(track.sendReverb) }
    var sendDelUi by remember(track.id, track.sendDelay) { mutableFloatStateOf(track.sendDelay) }
    var panUi by remember(track.id, track.pan) { mutableFloatStateOf((track.pan + 1f) / 2f) }
    Box(
        Modifier
            .width(108.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            ColorDot(track.colorArgb.toComposeColor(), 12.dp)
            Spacer(Modifier.height(4.dp))
            Text(
                track.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(track.type.name.lowercase().replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            RotaryKnob(
                value = panUi,
                onValueChange = { v ->
                    panUi = v
                    session.update(recordUndo = false) { p ->
                        ProjectOps.updateTrack(p, track.id) { it.copy(pan = v * 2f - 1f) }
                    }
                },
                onDragStart = { session.pushUndoCheckpoint() },
                label = "Pan",
                valueText = panText(panUi * 2f - 1f),
                size = 44.dp,
                bipolar = true
            )
            Spacer(Modifier.height(4.dp))
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                VerticalFader(
                    value = LevelMath.dbToFader(track.volumeDb),
                    onValueChange = { v ->
                        session.update(recordUndo = false) { p ->
                            ProjectOps.updateTrack(p, track.id) { it.copy(volumeDb = LevelMath.faderToDb(v)) }
                        }
                    },
                    onDragStart = { session.pushUndoCheckpoint(); onSelect() },
                    height = 168.dp,
                    accent = track.colorArgb.toComposeColor()
                )
                Spacer(Modifier.width(4.dp))
                StereoMeter(
                    peakL = meter?.getOrNull(0) ?: 0f,
                    peakR = meter?.getOrNull(1) ?: 0f,
                    rmsL = meter?.getOrNull(2) ?: 0f,
                    rmsR = meter?.getOrNull(3) ?: 0f,
                    modifier = Modifier.width(14.dp).height(168.dp)
                )
            }
            Text(
                "%+.1f dB".format(track.volumeDb),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(4.dp))
            Row {
                SmallToggleButton("M", track.mute, {
                    session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(mute = !it.mute) } }
                }, activeColor = SoraPalette.Warning)
                Spacer(Modifier.width(4.dp))
                SmallToggleButton("S", track.solo, {
                    session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(solo = !it.solo) } }
                }, activeColor = SoraPalette.Success)
                Spacer(Modifier.width(4.dp))
                SmallToggleButton("M", track.mono, {
                    session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(mono = !it.mono) } }
                })
            }
            Text("mono", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(2.dp))
            Row {
                RotaryKnob(
                    value = sendRevUi,
                    onValueChange = { v ->
                        sendRevUi = v
                        session.update(recordUndo = false) { p ->
                            ProjectOps.updateTrack(p, track.id) { it.copy(sendReverb = v) }
                        }
                    },
                    onDragStart = { session.pushUndoCheckpoint() },
                    label = "Rvb",
                    size = 36.dp
                )
                Spacer(Modifier.width(6.dp))
                RotaryKnob(
                    value = sendDelUi,
                    onValueChange = { v ->
                        sendDelUi = v
                        session.update(recordUndo = false) { p ->
                            ProjectOps.updateTrack(p, track.id) { it.copy(sendDelay = v) }
                        }
                    },
                    onDragStart = { session.pushUndoCheckpoint() },
                    label = "Dly",
                    size = 36.dp
                )
            }
            TextButton(onClick = onOpenEffects) {
                Text("FX (${track.fxChain.size})", style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun MasterStrip(
    volumeDb: Float,
    limiterEnabled: Boolean,
    peakL: Float, peakR: Float, rmsL: Float, rmsR: Float,
    fxCount: Int,
    session: ProjectSession,
    onOpenEffects: () -> Unit
) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier
            .width(112.dp)
            .fillMaxHeight()
            .clip(shape)
            .background(SoraPalette.Accent.copy(alpha = 0.08f))
            .padding(8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxSize()) {
            ColorDot(SoraPalette.Accent, 12.dp)
            Spacer(Modifier.height(4.dp))
            Text("Master", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
            Text("stereo out", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(48.dp))
            Row(Modifier.weight(1f)) {
                VerticalFader(
                    value = LevelMath.dbToFader(volumeDb),
                    onValueChange = { v ->
                        session.update(recordUndo = false) { p ->
                            ProjectOps.updateMaster(p) { it.copy(volumeDb = LevelMath.faderToDb(v)) }
                        }
                    },
                    onDragStart = { session.pushUndoCheckpoint() },
                    height = 168.dp
                )
                Spacer(Modifier.width(4.dp))
                StereoMeter(peakL, peakR, rmsL, rmsR, modifier = Modifier.width(14.dp).height(168.dp))
            }
            Text("%+.1f dB".format(volumeDb), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            SmallToggleButton("Limiter", limiterEnabled, {
                session.update { p -> ProjectOps.updateMaster(p) { it.copy(limiterEnabled = !it.limiterEnabled) } }
            }, activeColor = SoraPalette.Success)
            TextButton(onClick = onOpenEffects) {
                Text("Master FX ($fxCount)", style = MaterialTheme.typography.labelMedium)
            }
            Text(
                "Peak ${"%.1f".format(LevelMath.gainToDb(maxOf(peakL, peakR)))} dB",
                style = MaterialTheme.typography.labelSmall,
                color = if (maxOf(peakL, peakR) > 0.99f) SoraPalette.Error else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun FloatArray.getOrNull(index: Int): Float? = if (index in indices) this[index] else null

private fun panText(pan: Float): String = when {
    pan < -0.02f -> "L${(-pan * 100).toInt()}"
    pan > 0.02f -> "R${(pan * 100).toInt()}"
    else -> "C"
}

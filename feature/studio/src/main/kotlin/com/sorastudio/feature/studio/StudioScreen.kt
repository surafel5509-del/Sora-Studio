package com.sorastudio.feature.studio

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Redo
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Piano
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.ViewWeek
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.TempoMath
import com.sorastudio.core.designsystem.RoundIconButton
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.designsystem.TransportBar
import com.sorastudio.core.project.ProjectSession
import kotlinx.coroutines.delay

enum class StudioTab(val label: String, val icon: ImageVector) {
    ARRANGEMENT("Arrangement", Icons.Filled.ViewWeek),
    PIANO_ROLL("Piano Roll", Icons.Filled.Piano),
    DRUMS("Drums", Icons.Filled.GridView),
    INSTRUMENTS("Instruments", Icons.Filled.MusicNote),
    MIXER("Mixer", Icons.Filled.Tune),
    AUTOMATION("Automation", Icons.Filled.ShowChart),
    EFFECTS("Effects", Icons.Filled.GraphicEq),
}

/**
 * Studio workspace shell: transport + tempo/loop/metronome controls, section tabs,
 * and the active editor provided by the app module.
 */
@Composable
fun StudioScreen(
    session: ProjectSession,
    controller: PlaybackController,
    selectedTab: StudioTab,
    onTabSelected: (StudioTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (StudioTab) -> Unit
) {
    val project by session.project.collectAsState()
    val transport by controller.transport.collectAsState()
    val canUndo by session.canUndo.collectAsState()
    val canRedo by session.canRedo.collectAsState()

    var positionBeats by remember { mutableDoubleStateOf(0.0) }
    LaunchedEffect(Unit) {
        while (true) {
            positionBeats = controller.positionBeats()
            delay(50)
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Project header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    project.name,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                )
                Text(
                    "${project.tracks.size} tracks · ${project.clips.size} clips",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            RoundIconButton(
                icon = Icons.Filled.Undo,
                contentDescription = "Undo",
                onClick = { session.undo() },
                active = canUndo,
                size = 36.dp
            )
            Spacer(Modifier.width(4.dp))
            RoundIconButton(
                icon = Icons.Filled.Redo,
                contentDescription = "Redo",
                onClick = { session.redo() },
                active = canRedo,
                size = 36.dp
            )
        }

        TransportBar(
            isPlaying = transport.playing,
            isRecording = transport.recording,
            positionText = "${TempoMath.formatBarsBeats(positionBeats, project.beatsPerBar)}  ·  ${TempoMath.formatTime(positionBeats, project.bpm)}",
            tempoText = "${project.bpm.toInt()} BPM · ${project.beatsPerBar}/4",
            onPlayPause = { controller.toggle() },
            onStop = { controller.stop() },
            onSkipStart = { controller.seekBeats(0.0) }
        ) {
            // Tempo stepper
            Row(verticalAlignment = Alignment.CenterVertically) {
                RoundIconButton(Icons.Filled.Remove, "Tempo down", {
                    session.update(recordUndo = false) { it.copy(bpm = (it.bpm - 1).coerceIn(40.0, 240.0)) }
                }, size = 30.dp)
                Text(
                    "${project.bpm.toInt()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 4.dp)
                )
                RoundIconButton(Icons.Filled.Add, "Tempo up", {
                    session.update(recordUndo = false) { it.copy(bpm = (it.bpm + 1).coerceIn(40.0, 240.0)) }
                }, size = 30.dp)
                Spacer(Modifier.width(8.dp))
                RoundIconButton(
                    icon = Icons.Filled.Timer,
                    contentDescription = "Metronome",
                    onClick = { session.update { it.copy(metronomeEnabled = !it.metronomeEnabled) } },
                    active = project.metronomeEnabled,
                    size = 36.dp
                )
                Spacer(Modifier.width(4.dp))
                RoundIconButton(
                    icon = Icons.Filled.Repeat,
                    contentDescription = "Loop",
                    onClick = { session.update { it.copy(loopEnabled = !it.loopEnabled) } },
                    active = project.loopEnabled,
                    size = 36.dp
                )
            }
        }

        // Tabs
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (tab in StudioTab.values()) {
                    val selected = tab == selectedTab
                    val shape = RoundedCornerShape(10.dp)
                    Box(
                        modifier = Modifier
                            .clip(shape)
                            .background(if (selected) SoraPalette.Accent.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surface)
                            .clickable { onTabSelected(tab) }
                            .padding(horizontal = 10.dp, vertical = 7.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                tab.icon, tab.label,
                                tint = if (selected) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                            )
                        }
                    }
                    Spacer(Modifier.width(2.dp))
                }
            }
        }
        Box(Modifier.height(0.5.dp).fillMaxWidth().background(MaterialTheme.colorScheme.outline))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content(selectedTab)
        }
    }
}

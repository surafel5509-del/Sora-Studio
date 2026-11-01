package com.sorastudio.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.storage.AppSettings
import com.sorastudio.core.storage.SettingsRepository
import com.sorastudio.core.storage.ThemeMode
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    deviceInfoText: String,
    appVersionText: String,
    onClearAutosave: () -> Unit,
    modifier: Modifier = Modifier
) {
    val settings by settingsRepository.settings.collectAsState(initial = AppSettings())
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("SORA Studio works fully offline — everything is stored on this device.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            SectionTitle("Appearance")
            Spacer(Modifier.height(6.dp))
            Row {
                for (mode in ThemeMode.values()) {
                    SmallToggleButton(mode.name.lowercase().replaceFirstChar { it.uppercase() }, settings.themeMode == mode, {
                        scope.launch { settingsRepository.setThemeMode(mode) }
                    })
                    Spacer(Modifier.width(6.dp))
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Audio")
            Spacer(Modifier.height(6.dp))
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("Device", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(deviceInfoText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Text("Engine buffer (restart app to apply)", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.padding(top = 4.dp)) {
                        for (frames in listOf(256, 512, 1024, 2048)) {
                            SmallToggleButton("$frames", settings.bufferSizeFrames == frames, {
                                scope.launch { settingsRepository.setBufferSize(frames) }
                            })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                    Text(
                        "Smaller = lower latency, more CPU. Larger = safer on slower phones.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                    Spacer(Modifier.height(10.dp))
                    LabeledSlider(
                        "Master output volume",
                        "${settings.masterVolumePercent}%",
                        settings.masterVolumePercent.toFloat(),
                        { v -> scope.launch { settingsRepository.setMasterVolume(v.toInt()) } },
                        valueRange = 0f..100f
                    )
                    Text("Export sample rate (default)", style = MaterialTheme.typography.labelMedium)
                    Row(Modifier.padding(top = 4.dp)) {
                        for (rate in listOf(44100, 48000)) {
                            SmallToggleButton("${rate / 1000} kHz", settings.exportSampleRate == rate, {
                                scope.launch { settingsRepository.setExportSampleRate(rate) }
                            })
                            Spacer(Modifier.width(6.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Recording & Defaults")
            Spacer(Modifier.height(6.dp))
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    LabeledSlider(
                        "Default tempo for new projects",
                        "${settings.defaultBpm.toInt()} BPM",
                        settings.defaultBpm,
                        { v -> scope.launch { settingsRepository.setDefaultBpm(v) } },
                        valueRange = 60f..180f
                    )
                    SwitchRow("Metronome on by default", settings.metronomeDefault) {
                        scope.launch { settingsRepository.setMetronomeDefault(it) }
                    }
                    SwitchRow("Monitor while recording (default)", settings.monitorWhileRecording) {
                        scope.launch { settingsRepository.setMonitor(it) }
                    }
                    SwitchRow("Snap to grid by default", settings.snapDefault) {
                        scope.launch { settingsRepository.setSnapDefault(it) }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Count-in", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        for (bars in listOf(0, 1, 2)) {
                            SmallToggleButton(if (bars == 0) "Off" else "$bars bar", settings.countInBars == bars, {
                                scope.launch { settingsRepository.setCountInBars(bars) }
                            })
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("Data")
            Spacer(Modifier.height(6.dp))
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Projects autosave continuously and keep a backup of the previous save. A crash-recovery copy lets you restore unsaved work on next launch.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onClearAutosave) { Text("Clear Crash-Recovery Copy") }
                }
            }

            Spacer(Modifier.height(16.dp))
            SectionTitle("About")
            Spacer(Modifier.height(6.dp))
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    Text("SORA Studio", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(appVersionText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "A professional offline DAW written in 100% Kotlin — custom audio engine on AudioTrack/AudioRecord, " +
                            "pure-Kotlin DSP, generated drum kits and synth presets. No account, no cloud, no subscription.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Known limits: monitoring latency depends on your phone's audio path; MP3/FLAC export needs a device encoder (WAV/M4A always work); " +
                            "live auto tune is a light correction — use Render Tuned Take in the Vocal Studio for best results.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

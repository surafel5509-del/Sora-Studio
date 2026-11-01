package com.sorastudio.feature.instruments

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.MidiDeviceManager
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.NoteUtils
import com.sorastudio.core.common.ScaleType
import com.sorastudio.core.designsystem.PianoKeyboard
import com.sorastudio.core.designsystem.RotaryKnob
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.dsp.InstrumentLibrary
import com.sorastudio.core.dsp.InstrumentType
import com.sorastudio.core.dsp.Waveform
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.SNote
import com.sorastudio.core.project.TrackType
import com.sorastudio.core.storage.SampleStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ln

private const val LIVE_KEY = "instruments"

/**
 * Instrument browser + synth editor + playable keyboard.
 * Notes played while "MIDI Capture" is armed and the transport runs are
 * written into the selected track's clip (created if needed).
 */
@Composable
fun InstrumentsScreen(
    session: ProjectSession,
    controller: PlaybackController,
    sampleStore: SampleStore,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val transport by controller.transport.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val track = project.trackById(selectedTrackId)
    var category by remember { mutableStateOf(InstrumentType.PIANO) }
    var auditionPresetId by remember { mutableStateOf("piano-grand") }
    var octave by remember { mutableIntStateOf(4) }
    var midiCapture by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val activeNotes = remember { mutableStateMapOf<Int, Double>() } // pitch -> startBeat (capture)

    val activePresetId = if (track != null && (track.type == TrackType.INSTRUMENT)) track.presetId else auditionPresetId

    fun synthParam(key: String, fallback: Float): Float = track?.synthParams?.get(key) ?: fallback

    fun noteOn(pitch: Int, velocity: Float = 0.85f) {
        if (track?.type == TrackType.SAMPLE) {
            val file = track.samplePath?.let { controller.resolvePath(it) }
            if (file != null) {
                val rate = Math.pow(2.0, (pitch - track.sampleRootNote) / 12.0)
                controller.previewFile(file, rate)
            } else {
                status = "Choose a sample for this sampler track first."
            }
        } else {
            controller.noteOn(LIVE_KEY, activePresetId, pitch, velocity, track?.synthParams ?: emptyMap())
        }
        if (midiCapture && transport.playing && track != null && (track.type == TrackType.INSTRUMENT)) {
            activeNotes[pitch] = controller.positionBeats()
        }
    }

    fun noteOff(pitch: Int) {
        controller.noteOff(LIVE_KEY, pitch)
        val startBeat = activeNotes.remove(pitch) ?: return
        val endBeat = controller.positionBeats()
        val currentTrack = track ?: return
        if (!midiCapture || currentTrack.type != TrackType.INSTRUMENT) return
        val length = (endBeat - startBeat).coerceAtLeast(0.1)
        // Find or create a clip covering this region
        val p = session.current
        val covering = p.clipsForTrack(currentTrack.id).firstOrNull {
            it.type == ClipType.MIDI && startBeat >= it.startBeats && startBeat < it.endBeats + p.beatsPerBar * 4
        }
        if (covering != null) {
            val contentStart = (startBeat - covering.startBeats) + covering.contentOffsetBeats
            val note = SNote(startBeats = contentStart, lengthBeats = length, pitch = pitch, velocity = 100)
            session.update { proj ->
                var updated = ProjectOps.addNote(proj, covering.id, note)
                if (startBeat + length > covering.endBeats) {
                    updated = ProjectOps.updateClip(updated, covering.id) { it.copy(lengthBeats = (startBeat + length - it.startBeats)) }
                }
                updated
            }
        } else {
            val clip = SClip(
                trackId = currentTrack.id, name = "Recorded MIDI", type = ClipType.MIDI,
                startBeats = startBeat, lengthBeats = length.coerceAtLeast(p.beatsPerBar.toDouble()),
                notes = listOf(SNote(startBeats = 0.0, lengthBeats = length, pitch = pitch, velocity = 100))
            )
            session.update { ProjectOps.addClip(it, clip) }
        }
        status = "Captured ${NoteUtils.noteName(pitch)}"
    }

    // External MIDI
    val midiManager = remember(context) { MidiDeviceManager(context) }
    var midiConnected by remember { mutableStateOf(false) }
    var midiDevices by remember { mutableStateOf<List<String>>(emptyList()) }
    val latestNoteOn by rememberUpdatedState { pitch: Int, vel: Float -> noteOn(pitch, vel) }
    val latestNoteOff by rememberUpdatedState { pitch: Int -> noteOff(pitch) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        midiDevices = midiManager.availableDeviceNames()
        midiManager.onNoteOn = { pitch, vel -> latestNoteOn(pitch, vel / 127f) }
        midiManager.onNoteOff = { pitch -> latestNoteOff(pitch) }
    }

    val samplePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        val currentTrack = track
        if (uri != null && currentTrack != null) {
            scope.launch {
                val item = withContext(Dispatchers.IO) {
                    val name = uri.lastPathSegment?.substringAfterLast('/') ?: "sample.wav"
                    runCatching { sampleStore.importFromUri(uri, name, "Instruments") }.getOrNull()
                }
                if (item?.filePath != null) {
                    controller.preloadFiles(listOf(java.io.File(item.filePath)))
                    session.update { p ->
                        ProjectOps.updateTrack(p, currentTrack.id) { it.copy(type = TrackType.SAMPLE, samplePath = item.filePath) }
                    }
                    status = "Sample loaded: ${item.name}"
                } else {
                    status = "Could not import that file."
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // Category chips
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            for (type in InstrumentType.values().filter { it != InstrumentType.DRUMS && it != InstrumentType.SAMPLER }) {
                SmallToggleButton(type.displayName, category == type, { category = type })
                Spacer(Modifier.width(6.dp))
            }
            SmallToggleButton("Sampler", category == InstrumentType.SAMPLER, { category = InstrumentType.SAMPLER })
        }

        LazyColumn(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)) {
            if (category == InstrumentType.SAMPLER) {
                item {
                    SoraCard(Modifier.fillMaxWidth()) {
                        Column {
                            Text("Sampler Track", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Load any audio file and play it across the keyboard (pitched by resampling). Root note = C4.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { samplePicker.launch(arrayOf("audio/*")) }, enabled = track != null) {
                                Icon(Icons.Filled.FileUpload, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Load Sample")
                            }
                            if (track?.samplePath != null) {
                                Spacer(Modifier.height(6.dp))
                                Text("Current: ${java.io.File(track.samplePath!!).name}", style = MaterialTheme.typography.bodySmall)
                            }
                            if (track == null) {
                                Text("Select a track first.", style = MaterialTheme.typography.bodySmall, color = SoraPalette.Warning)
                            }
                        }
                    }
                }
            } else {
                items(InstrumentLibrary.byType(category), key = { it.id }) { preset ->
                    val isActive = preset.id == activePresetId
                    val shape = RoundedCornerShape(10.dp)
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 3.dp)
                            .clip(shape)
                            .background(if (isActive) SoraPalette.Accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
                            .clickable {
                                auditionPresetId = preset.id
                                if (track != null && track.type == TrackType.INSTRUMENT) {
                                    session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(presetId = preset.id) } }
                                }
                                noteOn(60)
                            }
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(preset.name, style = MaterialTheme.typography.titleSmall, fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal)
                            Text(preset.type.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (isActive) {
                            Text("Active", style = MaterialTheme.typography.labelSmall, color = SoraPalette.Accent, fontWeight = FontWeight.Bold)
                        }
                        TextButton(onClick = { noteOn(60); noteOff(60); noteOn(64); noteOff(64); noteOn(67) }) { Text("Chord") }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                SectionTitle("Synth Controls")
                Spacer(Modifier.height(8.dp))
                if (track == null || track.type != TrackType.INSTRUMENT) {
                    Text(
                        "Select an instrument track to edit synth parameters. Controls always audition the selected preset.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    val preset = InstrumentLibrary.byId(track.presetId) ?: InstrumentLibrary.PRESETS.first()
                    var cutoffUi by remember(track.id, track.presetId) {
                        mutableFloatStateOf(freqToUi(synthParam("cutoff", preset.patch.cutoffHz)))
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        RotaryKnob(
                            value = cutoffUi,
                            onValueChange = { v ->
                                cutoffUi = v
                                val hz = uiToFreq(v)
                                session.update(recordUndo = false) { p ->
                                    ProjectOps.updateTrack(p, track.id) { it.copy(synthParams = it.synthParams + ("cutoff" to hz)) }
                                }
                            },
                            label = "Cutoff",
                            valueText = "${uiToFreq(cutoffUi).toInt()} Hz"
                        )
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "resonance", "Resonance", synthParam("resonance", preset.patch.resonance), 0.2f, 12f)
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "attack", "Attack", synthParam("attack", preset.patch.attack), 0.001f, 2f, format = { "%.2fs".format(it) })
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "decay", "Decay", synthParam("decay", preset.patch.decay), 0.01f, 3f, format = { "%.2fs".format(it) })
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "sustain", "Sustain", synthParam("sustain", preset.patch.sustain), 0f, 1f, format = { "${(it * 100).toInt()}%" })
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "release", "Release", synthParam("release", preset.patch.release), 0.01f, 4f, format = { "%.2fs".format(it) })
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "detune", "Detune", synthParam("detune", preset.patch.osc2DetuneCents), 0f, 50f, format = { "${it.toInt()}¢" })
                        Spacer(Modifier.width(10.dp))
                        SynthKnob(session, track.id, "glide", "Glide", synthParam("glide", preset.patch.glideSec), 0f, 0.5f, format = { "%.2fs".format(it) })
                    }
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Oscillator", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        val currentWave = track.synthParams["waveform"]?.toInt()
                        for ((index, wave) in Waveform.values().withIndex()) {
                            SmallToggleButton(wave.name.lowercase().replaceFirstChar { it.uppercase() }, currentWave == index, {
                                session.update { p ->
                                    ProjectOps.updateTrack(p, track.id) { it.copy(synthParams = it.synthParams + ("waveform" to index.toFloat())) }
                                }
                            })
                            Spacer(Modifier.width(4.dp))
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(12.dp))
                SectionTitle("External MIDI Keyboard")
                Spacer(Modifier.height(6.dp))
                if (midiDevices.isEmpty()) {
                    Text(
                        "No USB/Bluetooth MIDI devices detected. Connect a keyboard, then reopen this tab.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text("Found: ${midiDevices.joinToString()}", style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Button(onClick = {
                        midiConnected = if (midiConnected) {
                            midiManager.disconnect(); false
                        } else midiManager.connectFirst()
                    }) { Text(if (midiConnected) "Disconnect MIDI" else "Connect MIDI") }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        // Keyboard
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { octave = (octave - 1).coerceIn(1, 7) }) { Icon(Icons.Filled.ChevronLeft, "Octave down") }
                    Text("C$octave", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    IconButton(onClick = { octave = (octave + 1).coerceIn(1, 7) }) { Icon(Icons.Filled.ChevronRight, "Octave up") }
                    Spacer(Modifier.width(8.dp))
                    SmallToggleButton("MIDI Capture", midiCapture, { midiCapture = !midiCapture }, activeColor = SoraPalette.Record)
                    Spacer(Modifier.width(8.dp))
                    status?.let {
                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                val scale = remember(project.keyScale) { runCatching { ScaleType.valueOf(project.keyScale) }.getOrDefault(ScaleType.MAJOR) }
                PianoKeyboard(
                    baseMidi = 12 * (octave + 1),
                    whiteKeyCount = 15,
                    onNoteOn = { pitch -> noteOn(pitch) },
                    onNoteOff = { pitch -> noteOff(pitch) },
                    inScale = { pitch -> scale.contains(project.keyRoot, pitch) },
                    modifier = Modifier.fillMaxWidth().height(128.dp)
                )
                if (midiCapture) {
                    Text(
                        "Capture armed: press Play, then perform — notes are written to the selected instrument track.",
                        style = MaterialTheme.typography.labelSmall,
                        color = SoraPalette.Record,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SynthKnob(
    session: ProjectSession,
    trackId: String,
    key: String,
    label: String,
    currentValue: Float,
    min: Float,
    max: Float,
    format: (Float) -> String = { "%.2f".format(it) }
) {
    var ui by remember(trackId, key) { mutableFloatStateOf(((currentValue - min) / (max - min)).coerceIn(0f, 1f)) }
    RotaryKnob(
        value = ui,
        onValueChange = { v ->
            ui = v
            val real = min + v * (max - min)
            session.update(recordUndo = false) { p ->
                ProjectOps.updateTrack(p, trackId) { it.copy(synthParams = it.synthParams + (key to real)) }
            }
        },
        label = label,
        valueText = format(min + ui * (max - min))
    )
}

private fun freqToUi(hz: Float): Float {
    val min = ln(80.0); val max = ln(16000.0)
    return ((ln(hz.toDouble().coerceIn(80.0, 16000.0)) - min) / (max - min)).toFloat()
}

private fun uiToFreq(ui: Float): Float {
    val min = ln(80.0); val max = ln(16000.0)
    return kotlin.math.exp(min + ui * (max - min)).toFloat()
}

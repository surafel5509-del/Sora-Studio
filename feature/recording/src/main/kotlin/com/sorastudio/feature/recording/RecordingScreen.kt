package com.sorastudio.feature.recording

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sorastudio.core.audio.AudioRecorder
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.TempoMath
import com.sorastudio.core.common.newId
import com.sorastudio.core.database.RecordingEntity
import com.sorastudio.core.database.RecordingRepository
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.designsystem.WaveformCanvas
import com.sorastudio.core.dsp.DecodedAudio
import com.sorastudio.core.dsp.WavCodec
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.TrackType
import com.sorastudio.core.storage.ProjectStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Vocal/instrument recording studio: permission flow, count-in, punch-in at the
 * playhead, loop takes, live metering/waveform and take history.
 */
@Composable
fun RecordingScreen(
    session: ProjectSession,
    controller: PlaybackController,
    storage: ProjectStorage,
    recordingRepository: RecordingRepository,
    recorder: AudioRecorder,
    onOpenAudioEditor: (relativePath: String) -> Unit,
    onOpenStudio: () -> Unit,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val recorderState by recorder.state.collectAsState()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasPermission = granted
    }

    var targetTrackId by remember { mutableStateOf<String?>(null) }
    var inputGainDb by remember { mutableStateOf(0f) }
    var monitor by remember { mutableStateOf(false) }
    var countInBars by remember { mutableIntStateOf(1) }
    var punchIn by remember { mutableStateOf(true) }
    var loopRecord by remember { mutableStateOf(false) }
    var countingIn by remember { mutableStateOf(false) }
    var countInBeat by remember { mutableIntStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }
    var recordStartBeat by remember { mutableStateOf(0.0) }

    val takes by recordingRepository.observeForProject(project.id).collectAsState(initial = emptyList())
    val recordableTracks = project.tracks.filter { it.type == TrackType.VOCAL || it.type == TrackType.AUDIO }
    val targetTrack = project.trackById(targetTrackId) ?: recordableTracks.firstOrNull()

    fun startRecording() {
        if (!hasPermission) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        val track = targetTrack ?: run {
            val (updated, created) = ProjectOps.addTrack(session.current, TrackType.VOCAL, "Vocal")
            session.update { updated }
            session.selectTrack(created.id)
            targetTrackId = created.id
            created
        }
        val audioDir = storage.audioDir(project.id)
        val fileName = "take-${SimpleDateFormat("HHmmss", Locale.US).format(Date())}.wav"
        val file = File(audioDir, fileName)
        recorder.inputGainDb = inputGainDb
        recorder.monitorEnabled = monitor

        scope.launch {
            recordStartBeat = controller.positionBeats()
            controller.setRecording(true)
            controller.play()
            if (countInBars > 0) {
                countingIn = true
                val beatsToWait = countInBars * project.beatsPerBar
                val target = recordStartBeat + beatsToWait
                var lastBeat = -1
                while (controller.positionBeats() < target) {
                    val remaining = (target - controller.positionBeats()).toInt()
                    if (remaining != lastBeat) {
                        lastBeat = remaining
                        countInBeat = remaining + 1
                    }
                    delay(30)
                }
                countingIn = false
                recordStartBeat = controller.positionBeats()
            }
            val ok = recorder.start(file, controller.sampleRate)
            if (!ok) {
                controller.setRecording(false)
                status = recorder.state.value.error ?: "Could not start recording."
            } else {
                status = "Recording take…"
            }
        }
    }

    fun stopRecording() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { recorder.stop() }
            controller.setRecording(false)
            controller.pause()
            if (result == null || result.frames == 0L) {
                status = "Take was empty — nothing saved."
                return@launch
            }
            val track = targetTrack ?: return@launch
            val relativePath = "audio/${result.file.name}"
            controller.preloadFiles(listOf(result.file))

            if (loopRecord && project.loopEnabled) {
                // Slice the continuous take into per-loop takes
                val loopBeats = project.loopEndBeats - project.loopStartBeats
                val loopMs = (TempoMath.beatsToSeconds(loopBeats, project.bpm) * 1000).toLong()
                if (loopMs > 200 && result.durationMs > loopMs) {
                    val created = withContext(Dispatchers.IO) {
                        sliceLoopTakes(result.file, loopMs)
                    }
                    created.forEachIndexed { index, takeFile ->
                        recordingRepository.add(
                            RecordingEntity(
                                id = newId("rec"), projectId = project.id,
                                name = "${track.name} Loop ${index + 1}",
                                relativePath = "audio/${takeFile.name}",
                                durationMs = loopMs, sampleRate = result.sampleRate,
                                createdAt = System.currentTimeMillis()
                            )
                        )
                        controller.preloadFiles(listOf(takeFile))
                    }
                    status = "Saved ${created.size} loop take(s). Add the best one to the arrangement."
                    return@launch
                }
            }

            recordingRepository.add(
                RecordingEntity(
                    id = newId("rec"), projectId = project.id,
                    name = "${track.name} ${SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())}",
                    relativePath = relativePath, durationMs = result.durationMs,
                    sampleRate = result.sampleRate, createdAt = System.currentTimeMillis()
                )
            )
            if (punchIn) {
                val lengthBeats = TempoMath.secondsToBeats(result.durationMs / 1000.0, project.bpm)
                val clip = SClip(
                    trackId = track.id, name = "${track.name} Take", type = ClipType.AUDIO,
                    startBeats = recordStartBeat, lengthBeats = lengthBeats,
                    audioPath = relativePath, audioLengthBeats = lengthBeats
                )
                session.update { ProjectOps.addClip(it, clip) }
                session.selectClip(clip.id)
                controller.seekBeats(recordStartBeat)
                status = "Take added to the arrangement at bar ${(recordStartBeat / project.beatsPerBar).toInt() + 1}."
            } else {
                status = "Take saved to history."
            }
        }
    }

    if (!hasPermission) {
        LaunchedEffect(Unit) { /* prompt on record press */ }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Mic, null, tint = SoraPalette.Accent)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Record", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(project.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        TempoMath.formatTime(controller.positionBeats(), project.bpm),
                        style = MaterialTheme.typography.titleMedium
                    )
                }
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(14.dp)
        ) {
            item {
                SectionTitle("Target track")
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    if (recordableTracks.isEmpty()) {
                        SmallToggleButton("+ New Vocal Track", false, {
                            val (updated, created) = ProjectOps.addTrack(session.current, TrackType.VOCAL, "Vocal")
                            session.update { updated }
                            targetTrackId = created.id
                            session.selectTrack(created.id)
                        })
                    }
                    for (track in recordableTracks) {
                        SmallToggleButton(track.name, targetTrack?.id == track.id, {
                            targetTrackId = track.id
                            session.selectTrack(track.id)
                        })
                        Spacer(Modifier.width(6.dp))
                    }
                    if (recordableTracks.isNotEmpty()) {
                        SmallToggleButton("+ New", false, {
                            val (updated, created) = ProjectOps.addTrack(session.current, TrackType.VOCAL)
                            session.update { updated }
                            targetTrackId = created.id
                            session.selectTrack(created.id)
                        })
                    }
                }
            }

            item {
                SoraCard(Modifier.fillMaxWidth()) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (countingIn) {
                            Text("$countInBeat", style = MaterialTheme.typography.displaySmall, color = SoraPalette.Warning, fontWeight = FontWeight.Bold)
                            Text("Get ready…", style = MaterialTheme.typography.bodyMedium)
                        } else if (recorderState.recording) {
                            Text(
                                formatElapsed(recorderState.elapsedMs),
                                style = MaterialTheme.typography.displaySmall,
                                color = SoraPalette.Record
                            )
                        } else {
                            Text("Ready to record", style = MaterialTheme.typography.titleMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        // Level meter
                        WaveformCanvas(
                            peaks = recorderState.peaks,
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            color = SoraPalette.AccentSecondary,
                            playedColor = SoraPalette.Accent
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            val fraction = recorderState.peakLevel.coerceIn(0f, 1f)
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction)
                                    .height(10.dp)
                                    .background(if (fraction > 0.9f) SoraPalette.Error else SoraPalette.Success)
                            )
                        }
                        Spacer(Modifier.height(14.dp))
                        Box(
                            Modifier
                                .size(76.dp)
                                .clip(CircleShape)
                                .background(if (recorderState.recording) SoraPalette.Record else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable {
                                    if (recorderState.recording) stopRecording() else startRecording()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (recorderState.recording) Icons.Filled.Stop else Icons.Filled.FiberManualRecord,
                                if (recorderState.recording) "Stop recording" else "Start recording",
                                tint = if (recorderState.recording) androidx.compose.ui.graphics.Color.White else SoraPalette.Record,
                                modifier = Modifier.size(34.dp)
                            )
                        }
                        if (!hasPermission) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                "Microphone permission is needed. Tap record and allow access to continue.",
                                style = MaterialTheme.typography.bodySmall,
                                color = SoraPalette.Warning
                            )
                        }
                        recorderState.error?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                        status?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            item {
                SectionTitle("Recording setup")
                Spacer(Modifier.height(6.dp))
                SoraCard(Modifier.fillMaxWidth()) {
                    Column {
                        LabeledSlider("Input gain", "%+.0f dB".format(inputGainDb), inputGainDb, { v ->
                            inputGainDb = v
                            recorder.inputGainDb = v
                        }, valueRange = -12f..24f)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Monitor input", style = MaterialTheme.typography.labelLarge)
                                Text(
                                    "Hear yourself while recording. Use wired headphones — phone monitoring adds noticeable latency.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(checked = monitor, onCheckedChange = { monitor = it; recorder.monitorEnabled = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Punch-in at playhead", style = MaterialTheme.typography.labelLarge)
                                Text("Place the take on the timeline where recording started.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = punchIn, onCheckedChange = { punchIn = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Loop recording", style = MaterialTheme.typography.labelLarge)
                                Text("Split into one take per loop pass (uses the project loop region).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = loopRecord, onCheckedChange = { loopRecord = it })
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Count-in", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                            for (bars in listOf(0, 1, 2)) {
                                SmallToggleButton(if (bars == 0) "Off" else "$bars bar", countInBars == bars, { countInBars = bars })
                                Spacer(Modifier.width(4.dp))
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("Metronome", style = MaterialTheme.typography.labelLarge)
                                Text("Click track while recording.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = project.metronomeEnabled, onCheckedChange = { v ->
                                session.update(recordUndo = false) { it.copy(metronomeEnabled = v) }
                            })
                        }
                        Text(
                            "Capture quality: ${AudioRecorder.probeSampleRate(controller.sampleRate)} Hz · 16-bit PCM · ${controller.deviceInfo.describe()}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { SectionTitle("Takes & recording history (${takes.size})") }
            if (takes.isEmpty()) {
                item {
                    Text(
                        "Recorded takes appear here. Every take is saved in the project folder.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            items(takes, key = { it.id }) { take ->
                SoraCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Mic, null, tint = SoraPalette.Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(take.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            Text(
                                "${formatElapsed(take.durationMs)} · ${take.sampleRate} Hz",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        TextButton(onClick = {
                            val file = File(storage.projectDir(project.id), take.relativePath)
                            controller.previewFile(file)
                        }) { Text("Play") }
                        IconButton(onClick = {
                            val track = targetTrack ?: return@IconButton
                            val lengthBeats = TempoMath.secondsToBeats(take.durationMs / 1000.0, project.bpm)
                            val clip = SClip(
                                trackId = track.id, name = take.name, type = ClipType.AUDIO,
                                startBeats = controller.positionBeats(), lengthBeats = lengthBeats,
                                audioPath = take.relativePath, audioLengthBeats = lengthBeats
                            )
                            session.update { ProjectOps.addClip(it, clip) }
                            session.selectClip(clip.id)
                            controller.preloadFiles(listOf(File(storage.projectDir(project.id), take.relativePath)))
                            onOpenStudio()
                        }) { Icon(Icons.Filled.PlaylistAdd, "Add to arrangement") }
                        IconButton(onClick = { onOpenAudioEditor(take.relativePath) }) {
                            Icon(Icons.Filled.Edit, "Edit take")
                        }
                        IconButton(onClick = {
                            scope.launch {
                                withContext(Dispatchers.IO) {
                                    File(storage.projectDir(project.id), take.relativePath).delete()
                                }
                                recordingRepository.delete(take)
                            }
                        }) { Icon(Icons.Filled.Delete, "Delete take", tint = MaterialTheme.colorScheme.error) }
                    }
                }
            }
            item { Spacer(Modifier.height(12.dp)) }
        }
    }
}

private fun formatElapsed(ms: Long): String {
    val totalSeconds = ms / 1000
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    val cs = (ms % 1000) / 10
    return "%d:%02d.%02d".format(m, s, cs)
}

/** Slice a continuous loop-mode recording into per-pass WAV files next to the original. */
private fun sliceLoopTakes(file: File, loopMs: Long): List<File> {
    val decoded: DecodedAudio = WavCodec.read(file)
    val framesPerLoop = (loopMs * decoded.sampleRate / 1000).toInt()
    if (framesPerLoop <= 0) return emptyList()
    val channels = decoded.channels
    val result = mutableListOf<File>()
    var startFrame = 0
    var index = 1
    while (startFrame + framesPerLoop <= decoded.frames) {
        val slice = FloatArray(framesPerLoop * channels)
        System.arraycopy(decoded.samples, startFrame * channels, slice, 0, slice.size)
        val out = File(file.parentFile, "${file.nameWithoutExtension}-loop$index.wav")
        WavCodec.write(out, slice, decoded.sampleRate, channels)
        result.add(out)
        startFrame += framesPerLoop
        index++
    }
    return result
}

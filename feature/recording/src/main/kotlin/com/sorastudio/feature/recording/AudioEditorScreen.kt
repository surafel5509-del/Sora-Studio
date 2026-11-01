package com.sorastudio.feature.recording

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.LiveEvent
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.ScaleType
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.WaveformCanvas
import com.sorastudio.core.designsystem.waveformPeaksFor
import com.sorastudio.core.dsp.AudioEditOps
import com.sorastudio.core.dsp.TimePitch
import com.sorastudio.core.dsp.WavBitDepth
import com.sorastudio.core.dsp.WavCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Destructive-edit-on-copy audio editor over a single file.
 * All operations transform an in-memory stereo copy; "Save as new file" writes
 * alongside the original (the original is never touched). One level of undo.
 */
@Composable
fun AudioEditorScreen(
    file: File,
    title: String,
    controller: PlaybackController,
    defaultKeyRoot: Int = 0,
    defaultScale: ScaleType = ScaleType.MAJOR,
    onClose: () -> Unit,
    onSaved: (File) -> Unit,
    modifier: Modifier = Modifier
) {
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(true) }
    var status by remember { mutableStateOf<String?>(null) }
    var sampleRate by remember { mutableStateOf(44100) }
    var work by remember { mutableStateOf<FloatArray?>(null) } // stereo at file rate
    var undoBuffer by remember { mutableStateOf<FloatArray?>(null) }
    var peaks by remember { mutableStateOf(FloatArray(0)) }

    var selStart by remember { mutableFloatStateOf(0f) }
    var selEnd by remember { mutableFloatStateOf(1f) }
    var gainDb by remember { mutableFloatStateOf(0f) }
    var pitchSemitones by remember { mutableFloatStateOf(0f) }
    var stretchFactor by remember { mutableFloatStateOf(1f) }
    var tuneRoot by remember { mutableStateOf(defaultKeyRoot) }
    var tuneScale by remember { mutableStateOf(defaultScale) }
    var tuneStrength by remember { mutableFloatStateOf(0.8f) }
    var tuneSpeed by remember { mutableFloatStateOf(0.4f) }
    var busy by remember { mutableStateOf(false) }

    fun refreshPeaks(data: FloatArray) {
        peaks = waveformPeaksFor(data, 300)
    }

    fun applyOp(label: String, op: (FloatArray) -> FloatArray) {
        val current = work ?: return
        busy = true
        scope.launch {
            val result = withContext(Dispatchers.Default) { op(current) }
            undoBuffer = current
            work = result
            refreshPeaks(result)
            status = "$label applied. Save to keep it as a new file."
            busy = false
        }
    }

    LaunchedEffect(file) {
        loading = true
        val decoded = withContext(Dispatchers.IO) { controller.assetCache.decodeNow(file) }
        if (decoded != null) {
            sampleRate = decoded.sampleRate
            val stereo = decoded.toStereoAt(decoded.sampleRate)
            work = stereo
            refreshPeaks(stereo)
            status = "${file.name} · ${decoded.sampleRate} Hz · ${"%.1f".format(decoded.durationSeconds)}s"
        } else {
            status = "Could not decode this file."
        }
        loading = false
    }

    val frames = (work?.size ?: 0) / 2

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    controller.stopPreviews()
                    onClose()
                }) { Icon(Icons.Filled.Close, "Close") }
                Column(Modifier.weight(1f)) {
                    Text("Audio Editor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(title, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                IconButton(onClick = {
                    val current = work
                    val undo = undoBuffer
                    if (current != null && undo != null) {
                        work = undo
                        undoBuffer = current
                        refreshPeaks(undo)
                        status = "Undone."
                    }
                }, enabled = undoBuffer != null) { Icon(Icons.AutoMirrored.Filled.Undo, "Undo edit") }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            if (loading) {
                Text("Loading audio…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            WaveformCanvas(
                peaks = peaks,
                modifier = Modifier.fillMaxWidth().height(110.dp),
                progress = selStart
            )
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row {
                Button(onClick = {
                    val data = work ?: return@Button
                    controller.stopPreviews()
                    val rate = sampleRate.toDouble() / controller.sampleRate
                    controller.renderer.postLive(
                        LiveEvent.SampleHit(resampleForPreview(data, sampleRate, controller.sampleRate, rate), stereo = true, rate = 1.0, gain = 0.9f)
                    )
                }, enabled = work != null && !busy) {
                    Icon(Icons.Filled.PlayArrow, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Preview")
                }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = { controller.stopPreviews() }) {
                    Icon(Icons.Filled.Stop, null, modifier = Modifier.size(16.dp))
                    Text("Stop")
                }
                Spacer(Modifier.weight(1f))
                Text("${"%.2f".format(frames.toDouble() / sampleRate)} s", style = MaterialTheme.typography.labelSmall)
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Selection (for Trim)")
            LabeledSlider("Start", "${(selStart * 100).toInt()}%", selStart, { selStart = it.coerceAtMost(selEnd - 0.01f) }, valueRange = 0f..1f)
            LabeledSlider("End", "${(selEnd * 100).toInt()}%", selEnd, { selEnd = it.coerceAtLeast(selStart + 0.01f) }, valueRange = 0f..1f)
            Row {
                TextButton(onClick = {
                    applyOp("Trim") { data ->
                        val startFrame = (selStart * data.size / 2).toInt()
                        val endFrame = (selEnd * data.size / 2).toInt()
                        AudioEditOps.trimStereo(data, startFrame, (endFrame - startFrame).coerceAtLeast(1)).also {
                            selStart = 0f; selEnd = 1f
                        }
                    }
                }, enabled = !busy) { Text("Trim to Selection") }
                TextButton(onClick = { applyOp("Trim silence") { AudioEditOps.trimSilence(it) } }, enabled = !busy) { Text("Trim Silence") }
                TextButton(onClick = { applyOp("Reverse") { AudioEditOps.reverseStereo(it) } }, enabled = !busy) { Text("Reverse") }
            }

            Spacer(Modifier.height(10.dp))
            SectionTitle("Level & Fades")
            LabeledSlider("Gain", "%+.0f dB".format(gainDb), gainDb, { gainDb = it }, valueRange = -24f..24f)
            Row {
                TextButton(onClick = { applyOp("Gain") { AudioEditOps.applyGainCopy(it, gainDb) } }, enabled = !busy) { Text("Apply Gain") }
                TextButton(onClick = { applyOp("Normalize") { AudioEditOps.normalize(it) } }, enabled = !busy) { Text("Normalize −1 dB") }
                TextButton(onClick = {
                    applyOp("Fade in") { AudioEditOps.fadeInStereo(it, (0.25 * sampleRate).toInt()) }
                }, enabled = !busy) { Text("Fade In") }
                TextButton(onClick = {
                    applyOp("Fade out") { AudioEditOps.fadeOutStereo(it, (0.35 * sampleRate).toInt()) }
                }, enabled = !busy) { Text("Fade Out") }
            }

            Spacer(Modifier.height(10.dp))
            SectionTitle("Pitch & Time (offline render)")
            LabeledSlider("Pitch shift", "%+.0f st".format(pitchSemitones), pitchSemitones, { pitchSemitones = it }, valueRange = -12f..12f)
            TextButton(onClick = {
                applyOp("Pitch shift") { TimePitch.pitchShiftStereo(it, pitchSemitones.toDouble()) }
            }, enabled = !busy && pitchSemitones != 0f) { Text("Apply Pitch Shift") }
            LabeledSlider("Time stretch", "×%.2f".format(stretchFactor), stretchFactor, { stretchFactor = it }, valueRange = 0.5f..2f)
            TextButton(onClick = {
                applyOp("Time stretch") { TimePitch.timeStretchStereo(it, stretchFactor.toDouble()) }
            }, enabled = !busy && stretchFactor != 1f) { Text("Apply Stretch") }

            Spacer(Modifier.height(10.dp))
            SectionTitle("Pitch Correction (Auto Tune render)")
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Key", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        for (root in listOf(0, 2, 4, 5, 7, 9, 11)) {
                            SmallToggleButton(
                                listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")[root],
                                tuneRoot == root, { tuneRoot = root }
                            )
                            Spacer(Modifier.width(3.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Scale", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        for (scale in listOf(ScaleType.MAJOR, ScaleType.NATURAL_MINOR, ScaleType.MINOR_PENTATONIC, ScaleType.CHROMATIC)) {
                            SmallToggleButton(scale.displayName.substringBefore(" "), tuneScale == scale, { tuneScale = scale })
                            Spacer(Modifier.width(3.dp))
                        }
                    }
                    LabeledSlider("Correction strength", "${(tuneStrength * 100).toInt()}%", tuneStrength, { tuneStrength = it }, valueRange = 0f..1f)
                    LabeledSlider("Transition speed", "${(tuneSpeed * 100).toInt()}%", tuneSpeed, { tuneSpeed = it }, valueRange = 0f..1f)
                    Button(onClick = {
                        applyOp("Pitch correction") { data ->
                            val framesD = data.size / 2
                            val mono = FloatArray(framesD) { f -> (data[f * 2] + data[f * 2 + 1]) * 0.5f }
                            val corrected = TimePitch.pitchCorrectMono(
                                mono, sampleRate,
                                TimePitch.PitchCorrectionSettings(tuneRoot, tuneScale, tuneStrength, tuneSpeed)
                            )
                            val out = FloatArray(corrected.size * 2)
                            for (f in corrected.indices) {
                                out[f * 2] = corrected[f]
                                out[f * 2 + 1] = corrected[f]
                            }
                            out
                        }
                    }, enabled = !busy) { Text("Render Tuned (mono→stereo)") }
                    Text(
                        "Frame-based correction for monophonic vocals/leads. Results vary with the performance; check with Preview before saving.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val data = work ?: return@Button
                    busy = true
                    scope.launch {
                        val outFile = withContext(Dispatchers.IO) {
                            val base = file.nameWithoutExtension
                            var target = File(file.parentFile, "$base-edited.wav")
                            var i = 2
                            while (target.exists()) {
                                target = File(file.parentFile, "$base-edited$i.wav")
                                i++
                            }
                            WavCodec.write(target, data, sampleRate, 2, WavBitDepth.PCM16)
                            target
                        }
                        controller.preloadFiles(listOf(outFile))
                        busy = false
                        onSaved(outFile)
                    }
                },
                enabled = work != null && !busy,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Save, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (busy) "Processing…" else "Save as New File (original kept)")
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun resampleForPreview(stereo: FloatArray, fromRate: Int, toRate: Int, @Suppress("UNUSED_PARAMETER") unused: Double): FloatArray {
    if (fromRate == toRate) return stereo
    val inputFrames = stereo.size / 2
    val ratio = fromRate.toDouble() / toRate.toDouble()
    val outFrames = (inputFrames / ratio).toInt()
    val out = FloatArray(outFrames * 2)
    for (f in 0 until outFrames) {
        val src = f * ratio
        val i0 = src.toInt().coerceIn(0, inputFrames - 1)
        val i1 = (i0 + 1).coerceAtMost(inputFrames - 1)
        val frac = (src - i0).toFloat()
        out[f * 2] = stereo[i0 * 2] + (stereo[i1 * 2] - stereo[i0 * 2]) * frac
        out[f * 2 + 1] = stereo[i0 * 2 + 1] + (stereo[i1 * 2 + 1] - stereo[i0 * 2 + 1]) * frac
    }
    return out
}

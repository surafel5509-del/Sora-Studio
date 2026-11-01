package com.sorastudio.feature.vocal

import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.NoteUtils
import com.sorastudio.core.common.ScaleType
import com.sorastudio.core.common.newId
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.RotaryKnob
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.WaveformCanvas
import com.sorastudio.core.designsystem.waveformPeaksFor
import com.sorastudio.core.dsp.PitchDetector
import com.sorastudio.core.dsp.TimePitch
import com.sorastudio.core.dsp.WavBitDepth
import com.sorastudio.core.dsp.WavCodec
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SEffect
import com.sorastudio.core.project.STrack
import com.sorastudio.core.project.TrackType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

/** Named vocal chains. Each entry is an ordered insert list applied to the track. */
object VocalPresets {
    fun chain(name: String): List<SEffect> = when (name) {
        "Warm & Close" -> listOf(
            fx("HIGH_PASS", mapOf("freq" to 80f, "res" to 0.71f)),
            fx("NOISE_GATE", mapOf("threshold" to -52f, "attack" to 2f, "release" to 180f, "range" to 30f)),
            fx("THREE_BAND_EQ", mapOf("low" to 2f, "mid" to 1f, "high" to 2.5f, "lowFreq" to 140f, "highFreq" to 9500f)),
            fx("COMPRESSOR", mapOf("threshold" to -20f, "ratio" to 3f, "attack" to 18f, "release" to 200f, "makeup" to 3f, "knee" to 8f)),
            fx("SATURATION", mapOf("drive" to 22f, "output" to -1f), wet = 0.5f),
        )
        "Bright Pop" -> listOf(
            fx("HIGH_PASS", mapOf("freq" to 100f, "res" to 0.71f)),
            fx("THREE_BAND_EQ", mapOf("low" to -3f, "mid" to 2.5f, "high" to 5f, "lowFreq" to 120f, "highFreq" to 10000f)),
            fx("COMPRESSOR", mapOf("threshold" to -18f, "ratio" to 4f, "attack" to 8f, "release" to 120f, "makeup" to 4f, "knee" to 6f)),
            fx("DE_ESSER", mapOf("freq" to 6800f, "amount" to 45f, "threshold" to -22f)),
            fx("AUTO_TUNE", mapOf("root" to 0f, "strength" to 55f, "speed" to 35f), wet = 1f),
        )
        "Hard Tune" -> listOf(
            fx("HIGH_PASS", mapOf("freq" to 90f, "res" to 0.71f)),
            fx("AUTO_TUNE", mapOf("root" to 0f, "strength" to 100f, "speed" to 90f), wet = 1f),
            fx("COMPRESSOR", mapOf("threshold" to -16f, "ratio" to 5f, "attack" to 6f, "release" to 100f, "makeup" to 4f, "knee" to 2f)),
            fx("THREE_BAND_EQ", mapOf("low" to -2f, "mid" to 2f, "high" to 4f, "lowFreq" to 120f, "highFreq" to 10000f)),
        )
        "Dry & Honest" -> listOf(
            fx("HIGH_PASS", mapOf("freq" to 70f, "res" to 0.71f)),
            fx("COMPRESSOR", mapOf("threshold" to -22f, "ratio" to 2f, "attack" to 25f, "release" to 250f, "makeup" to 2f, "knee" to 12f)),
        )
        "Telephone" -> listOf(
            fx("HIGH_PASS", mapOf("freq" to 500f, "res" to 1f)),
            fx("LOW_PASS", mapOf("freq" to 2600f, "res" to 1.2f)),
            fx("DISTORTION", mapOf("drive" to 35f, "tone" to 2600f, "output" to -6f), wet = 0.7f),
            fx("COMPRESSOR", mapOf("threshold" to -14f, "ratio" to 6f, "attack" to 4f, "release" to 90f, "makeup" to 5f, "knee" to 2f)),
        )
        else -> listOf(
            fx("HIGH_PASS", mapOf("freq" to 85f, "res" to 0.71f)),
            fx("COMPRESSOR", mapOf("threshold" to -19f, "ratio" to 3.5f, "attack" to 12f, "release" to 160f, "makeup" to 3f, "knee" to 6f)),
            fx("THREE_BAND_EQ", mapOf("low" to -1f, "mid" to 2f, "high" to 3f, "lowFreq" to 120f, "highFreq" to 9000f)),
        )
    }

    val NAMES = listOf("Clean Studio", "Warm & Close", "Bright Pop", "Hard Tune", "Dry & Honest", "Telephone")

    private fun fx(type: String, params: Map<String, Float>, wet: Float = 1f) =
        SEffect(id = newId("fx"), type = type, params = params, wet = wet)
}

/**
 * Vocal processing studio: a guided chain (cleanup EQ → gate → de-ess →
 * compression → saturation → auto tune → sends) written into the track's real
 * effect chain, plus offline tuned-take rendering and a live pitch readout.
 */
@Composable
fun VocalScreen(
    session: ProjectSession,
    controller: PlaybackController,
    onSaveChainPreset: (name: String, chain: List<SEffect>) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val scope = rememberCoroutineScope()

    val track = project.trackById(selectedTrackId)?.takeIf { it.type == TrackType.VOCAL || it.type == TrackType.AUDIO }
        ?: project.tracks.firstOrNull { it.type == TrackType.VOCAL }

    var status by remember { mutableStateOf<String?>(null) }
    var detectedNote by remember { mutableStateOf<String?>(null) }
    var analysing by remember { mutableStateOf(false) }
    var tunedPeaks by remember { mutableStateOf<FloatArray?>(null) }
    var noiseAmount by remember { mutableFloatStateOf(0.5f) }
    var busy by remember { mutableStateOf(false) }

    fun fxOf(track: STrack, type: String): SEffect? = track.fxChain.firstOrNull { it.type == type }

    fun ensureFx(track: STrack, type: String, defaults: Map<String, Float>): SEffect {
        val existing = fxOf(track, type)
        if (existing != null) return existing
        val created = SEffect(type = type, params = defaults)
        session.update { ProjectOps.addEffect(it, track.id, created) }
        return created
    }

    fun setParam(track: STrack, fxId: String, key: String, value: Float) {
        session.update(recordUndo = false) { p ->
            ProjectOps.updateEffect(p, track.id, fxId) { it.copy(params = it.params + (key to value)) }
        }
    }

    fun setFxEnabled(track: STrack, fxId: String, enabled: Boolean) {
        session.update { p -> ProjectOps.updateEffect(p, track.id, fxId) { it.copy(enabled = enabled) } }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Mic, null, tint = com.sorastudio.core.designsystem.SoraPalette.Accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Vocal Studio", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (track != null) "Processing: ${track.name}" else "Select a vocal track to process",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (track != null) {
                    IconButton(onClick = { onSaveChainPreset("${track.name} Chain", track.fxChain) }) {
                        Icon(Icons.Filled.Save, "Save chain preset", modifier = Modifier.size(19.dp))
                    }
                }
            }
        }

        if (track == null) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No vocal track", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Add a Vocal track in the Arrangement, record a take, then polish it here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        val (updated, created) = ProjectOps.addTrack(session.current, TrackType.VOCAL, "Lead Vocal")
                        session.update { updated }
                        session.selectTrack(created.id)
                    }) { Text("Create Vocal Track") }
                }
            }
            return@Column
        }


        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            SectionTitle("Presets")
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                for (name in VocalPresets.NAMES) {
                    SmallToggleButton(name, false, {
                        session.update { p ->
                            ProjectOps.updateTrack(p, track.id) { it.copy(fxChain = VocalPresets.chain(name)) }
                        }
                        status = "Applied “$name”. Press Play to hear it."
                    })
                    Spacer(Modifier.width(6.dp))
                }
            }
            status?.let {
                Spacer(Modifier.height(6.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Vocal Chain")

            VocalEqModule(session, track, ::ensureFx, ::fxOf, ::setParam, ::setFxEnabled)
            GateModule(session, track, ::ensureFx, ::fxOf, ::setParam, ::setFxEnabled)
            DeEsserModule(session, track, ::ensureFx, ::fxOf, ::setParam, ::setFxEnabled)
            CompressorModule(session, track, ::ensureFx, ::fxOf, ::setParam, ::setFxEnabled)
            SaturationModule(session, track, ::ensureFx, ::fxOf, ::setParam, ::setFxEnabled)

            Spacer(Modifier.height(8.dp))
            SectionTitle("Auto Tune (live)")
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    val tuneFx = fxOf(track, "AUTO_TUNE")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Live pitch correction", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Light real-time tuning while you listen/monitor. For best quality use “Render Tuned Take” below.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(checked = tuneFx?.enabled == true, onCheckedChange = { enabled ->
                            val fx = ensureFx(track, "AUTO_TUNE", mapOf("root" to track.autoTuneRoot.toFloat(), "strength" to 70f, "speed" to 35f))
                            setFxEnabled(track, fx.id, enabled)
                        })
                    }
                    if (tuneFx != null) {
                        LabeledSlider("Correction", "${(tuneFx.params["strength"] ?: 70f).toInt()}%", tuneFx.params["strength"] ?: 70f, { v -> setParam(track, tuneFx.id, "strength", v) }, valueRange = 0f..100f)
                        LabeledSlider("Speed", "${(tuneFx.params["speed"] ?: 35f).toInt()}%", tuneFx.params["speed"] ?: 35f, { v -> setParam(track, tuneFx.id, "speed", v) }, valueRange = 1f..100f)
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Render Tuned Take (offline)")
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Key", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            for (root in 0..11) {
                                SmallToggleButton(NoteUtils.pitchClassName(root), track.autoTuneRoot == root, {
                                    session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(autoTuneRoot = root) } }
                                })
                                Spacer(Modifier.width(3.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Scale", style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(end = 8.dp))
                        for (scale in listOf(ScaleType.MAJOR, ScaleType.NATURAL_MINOR, ScaleType.HARMONIC_MINOR, ScaleType.MINOR_PENTATONIC)) {
                            SmallToggleButton(scale.displayName.substringBefore(" "), track.autoTuneScale == scale.name, {
                                session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(autoTuneScale = scale.name) } }
                            })
                            Spacer(Modifier.width(3.dp))
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    Row {
                        RotaryKnob(
                            value = track.autoTuneStrength,
                            onValueChange = { v ->
                                session.update(recordUndo = false) { p -> ProjectOps.updateTrack(p, track.id) { it.copy(autoTuneStrength = v) } }
                            },
                            label = "Strength",
                            valueText = "${(track.autoTuneStrength * 100).toInt()}%",
                            size = 56.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        RotaryKnob(
                            value = track.autoTuneSpeed,
                            onValueChange = { v ->
                                session.update(recordUndo = false) { p -> ProjectOps.updateTrack(p, track.id) { it.copy(autoTuneSpeed = v) } }
                            },
                            label = "Speed",
                            valueText = "${(track.autoTuneSpeed * 100).toInt()}%",
                            size = 56.dp
                        )
                        Spacer(Modifier.width(14.dp))
                        Column {
                            Button(onClick = {
                                // Analyse first audio clip's pitch for feedback
                                val audioClip = project.clipsForTrack(track.id).firstOrNull { it.type == ClipType.AUDIO && it.audioPath != null }
                                if (audioClip == null) {
                                    status = "This track has no audio clip to analyse yet."
                                    return@Button
                                }
                                analysing = true
                                scope.launch {
                                    val file = controller.resolvePath(audioClip.audioPath!!)
                                    val freq = withContext(Dispatchers.Default) {
                                        if (file == null) -1.0 else {
                                            val decoded = controller.assetCache.decodeNow(file)
                                            if (decoded == null) -1.0 else {
                                                val mono = decoded.toMonoAt(decoded.sampleRate)
                                                val window = mono.copyOfRange(
                                                    (mono.size * 0.3).toInt(),
                                                    minOf(mono.size, (mono.size * 0.3).toInt() + 4096)
                                                )
                                                PitchDetector.detect(window, window.size, decoded.sampleRate)
                                            }
                                        }
                                    }
                                    detectedNote = if (freq > 0) {
                                        "Detected around ${NoteUtils.noteName(NoteUtils.freqToMidi(freq))} (${"%.0f".format(freq)} Hz) in the middle of the take."
                                    } else "No clear pitch found (silence or unpitched section)."
                                    analysing = false
                                }
                            }, enabled = !analysing) { Text("Detect Pitch") }
                            detectedNote?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = {
                        val audioClips = project.clipsForTrack(track.id).filter { it.type == ClipType.AUDIO && it.audioPath != null }
                        if (audioClips.isEmpty()) {
                            status = "Record or add an audio clip on this track first."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            var rendered = 0
                            var lastPeaks: FloatArray? = null
                            for (clip in audioClips) {
                                val file = controller.resolvePath(clip.audioPath!!) ?: continue
                                val outFile = withContext(Dispatchers.Default) {
                                    val decoded = controller.assetCache.decodeNow(file) ?: return@withContext null
                                    val mono = decoded.toMonoAt(decoded.sampleRate)
                                    val scale = runCatching { ScaleType.valueOf(track.autoTuneScale) }.getOrDefault(ScaleType.MAJOR)
                                    val corrected = TimePitch.pitchCorrectMono(
                                        mono, decoded.sampleRate,
                                        TimePitch.PitchCorrectionSettings(track.autoTuneRoot, scale, track.autoTuneStrength, track.autoTuneSpeed)
                                    )
                                    val target = File(file.parentFile, "${file.nameWithoutExtension}-tuned.wav")
                                    val stereoOut = FloatArray(corrected.size * 2)
                                    for (i in corrected.indices) {
                                        stereoOut[i * 2] = corrected[i]
                                        stereoOut[i * 2 + 1] = corrected[i]
                                    }
                                    WavCodec.write(target, stereoOut, decoded.sampleRate, 2, WavBitDepth.PCM16)
                                    lastPeaks = waveformPeaksFor(stereoOut, 220)
                                    target
                                }
                                if (outFile != null) {
                                    controller.preloadFiles(listOf(outFile))
                                    val newRelative = "audio/${outFile.name}"
                                    session.update { p ->
                                        ProjectOps.updateClip(p, clip.id) { it.copy(audioPath = newRelative, name = it.name.removeSuffix(" (tuned)") + " (tuned)") }
                                    }
                                    rendered++
                                }
                            }
                            tunedPeaks = lastPeaks
                            busy = false
                            status = if (rendered > 0) "Rendered $rendered tuned take(s). Originals are kept in the project folder." else "Nothing could be rendered (decode failed)."
                        }
                    }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Text(if (busy) "Rendering…" else "Render Tuned Take")
                    }
                    tunedPeaks?.let {
                        Spacer(Modifier.height(8.dp))
                        WaveformCanvas(peaks = it, modifier = Modifier.fillMaxWidth().height(48.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Noise Reduction (render)")
            SoraCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Downward expansion that pushes quiet room noise down between phrases. Renders new files; originals are kept.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    LabeledSlider("Amount", "${(noiseAmount * 100).toInt()}%", noiseAmount, { noiseAmount = it }, valueRange = 0f..1f)
                    Button(onClick = {
                        val audioClips = project.clipsForTrack(track.id).filter { it.type == ClipType.AUDIO && it.audioPath != null }
                        if (audioClips.isEmpty()) {
                            status = "No audio clips on this track yet."
                            return@Button
                        }
                        busy = true
                        scope.launch {
                            var done = 0
                            for (clip in audioClips) {
                                val file = controller.resolvePath(clip.audioPath!!) ?: continue
                                val outFile = withContext(Dispatchers.Default) {
                                    val decoded = controller.assetCache.decodeNow(file) ?: return@withContext null
                                    val stereo = decoded.toStereoAt(decoded.sampleRate)
                                    reduceNoise(stereo, decoded.sampleRate, noiseAmount)
                                    val target = File(file.parentFile, "${file.nameWithoutExtension}-clean.wav")
                                    WavCodec.write(target, stereo, decoded.sampleRate, 2, WavBitDepth.PCM16)
                                    target
                                }
                                if (outFile != null) {
                                    controller.preloadFiles(listOf(outFile))
                                    session.update { p ->
                                        ProjectOps.updateClip(p, clip.id) { it.copy(audioPath = "audio/${outFile.name}") }
                                    }
                                    done++
                                }
                            }
                            busy = false
                            status = "Noise reduction rendered for $done clip(s)."
                        }
                    }, enabled = !busy) { Text("Render Noise Reduction") }
                }
            }

            Spacer(Modifier.height(14.dp))
            SectionTitle("Space (sends)")
            Row {
                RotaryKnob(
                    value = track.sendReverb,
                    onValueChange = { v ->
                        session.update(recordUndo = false) { p -> ProjectOps.updateTrack(p, track.id) { it.copy(sendReverb = v) } }
                    },
                    label = "Reverb",
                    valueText = "${(track.sendReverb * 100).toInt()}%",
                    size = 60.dp
                )
                Spacer(Modifier.width(16.dp))
                RotaryKnob(
                    value = track.sendDelay,
                    onValueChange = { v ->
                        session.update(recordUndo = false) { p -> ProjectOps.updateTrack(p, track.id) { it.copy(sendDelay = v) } }
                    },
                    label = "Delay",
                    valueText = "${(track.sendDelay * 100).toInt()}%",
                    size = 60.dp
                )
                Spacer(Modifier.width(16.dp))
                RotaryKnob(
                    value = (track.pan + 1f) / 2f,
                    onValueChange = { v ->
                        session.update(recordUndo = false) { p -> ProjectOps.updateTrack(p, track.id) { it.copy(pan = v * 2f - 1f) } }
                    },
                    label = "Pan",
                    size = 60.dp,
                    bipolar = true
                )
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private typealias EnsureFx = (STrack, String, Map<String, Float>) -> SEffect
private typealias FxOf = (STrack, String) -> SEffect?
private typealias SetParam = (STrack, String, String, Float) -> Unit
private typealias SetEnabled = (STrack, String, Boolean) -> Unit

@Composable
private fun ModuleCard(
    title: String,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    content: @Composable () -> Unit
) {
    SoraCard(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = onEnabledChange)
            }
            if (enabled) content()
        }
    }
}

@Composable
private fun VocalEqModule(session: ProjectSession, track: STrack, ensureFx: EnsureFx, fxOf: FxOf, setParam: SetParam, setEnabled: SetEnabled) {
    val eq = fxOf(track, "THREE_BAND_EQ")
    ModuleCard("Vocal EQ", "Shape body, presence and air", eq?.enabled == true, { enabled ->
        val fx = ensureFx(track, "THREE_BAND_EQ", mapOf("low" to 0f, "mid" to 1.5f, "high" to 3f, "lowFreq" to 120f, "highFreq" to 9000f))
        setEnabled(track, fx.id, enabled)
    }) {
        val current = fxOf(track, "THREE_BAND_EQ") ?: return@ModuleCard
        Row {
            EqKnob("Low", current.params["low"] ?: 0f) { setParam(track, current.id, "low", it) }
            Spacer(Modifier.width(12.dp))
            EqKnob("Presence", current.params["mid"] ?: 0f) { setParam(track, current.id, "mid", it) }
            Spacer(Modifier.width(12.dp))
            EqKnob("Air", current.params["high"] ?: 0f) { setParam(track, current.id, "high", it) }
        }
        @Suppress("UNUSED_EXPRESSION") session
    }
}

@Composable
private fun EqKnob(label: String, db: Float, onChange: (Float) -> Unit) {
    var ui by remember(db) { mutableFloatStateOf((db + 18f) / 36f) }
    RotaryKnob(
        value = ui,
        onValueChange = { v ->
            ui = v
            onChange(v * 36f - 18f)
        },
        label = label,
        valueText = "%+.1f dB".format(ui * 36f - 18f),
        size = 56.dp,
        bipolar = true
    )
}

@Composable
private fun GateModule(session: ProjectSession, track: STrack, ensureFx: EnsureFx, fxOf: FxOf, setParam: SetParam, setEnabled: SetEnabled) {
    val gate = fxOf(track, "NOISE_GATE")
    ModuleCard("Noise Gate", "Silence breaths and room noise between phrases", gate?.enabled == true, { enabled ->
        val fx = ensureFx(track, "NOISE_GATE", mapOf("threshold" to -52f, "attack" to 2f, "release" to 180f, "range" to 32f))
        setEnabled(track, fx.id, enabled)
    }) {
        val current = fxOf(track, "NOISE_GATE") ?: return@ModuleCard
        LabeledSlider("Threshold", "%.0f dB".format(current.params["threshold"] ?: -52f), current.params["threshold"] ?: -52f, { setParam(track, current.id, "threshold", it) }, valueRange = -80f..-10f)
        LabeledSlider("Range", "%.0f dB".format(current.params["range"] ?: 32f), current.params["range"] ?: 32f, { setParam(track, current.id, "range", it) }, valueRange = 0f..60f)
        @Suppress("UNUSED_EXPRESSION") session
    }
}

@Composable
private fun DeEsserModule(session: ProjectSession, track: STrack, ensureFx: EnsureFx, fxOf: FxOf, setParam: SetParam, setEnabled: SetEnabled) {
    val fx = fxOf(track, "DE_ESSER")
    ModuleCard("De-Esser", "Tame harsh S and T sounds", fx?.enabled == true, { enabled ->
        val created = ensureFx(track, "DE_ESSER", mapOf("freq" to 6500f, "amount" to 40f, "threshold" to -20f))
        setEnabled(track, created.id, enabled)
    }) {
        val current = fxOf(track, "DE_ESSER") ?: return@ModuleCard
        LabeledSlider("Amount", "${(current.params["amount"] ?: 40f).toInt()}%", current.params["amount"] ?: 40f, { setParam(track, current.id, "amount", it) }, valueRange = 0f..100f)
        @Suppress("UNUSED_EXPRESSION") session
    }
}

@Composable
private fun CompressorModule(session: ProjectSession, track: STrack, ensureFx: EnsureFx, fxOf: FxOf, setParam: SetParam, setEnabled: SetEnabled) {
    val fx = fxOf(track, "COMPRESSOR")
    ModuleCard("Compressor", "Even out the performance dynamics", fx?.enabled == true, { enabled ->
        val created = ensureFx(track, "COMPRESSOR", mapOf("threshold" to -19f, "ratio" to 3.5f, "attack" to 12f, "release" to 160f, "makeup" to 3f, "knee" to 6f))
        setEnabled(track, created.id, enabled)
    }) {
        val current = fxOf(track, "COMPRESSOR") ?: return@ModuleCard
        LabeledSlider("Threshold", "%.0f dB".format(current.params["threshold"] ?: -19f), current.params["threshold"] ?: -19f, { setParam(track, current.id, "threshold", it) }, valueRange = -48f..0f)
        LabeledSlider("Ratio", "%.1f:1".format(current.params["ratio"] ?: 3.5f), current.params["ratio"] ?: 3.5f, { setParam(track, current.id, "ratio", it) }, valueRange = 1f..10f)
        LabeledSlider("Makeup", "%.0f dB".format(current.params["makeup"] ?: 3f), current.params["makeup"] ?: 3f, { setParam(track, current.id, "makeup", it) }, valueRange = 0f..18f)
        @Suppress("UNUSED_EXPRESSION") session
    }
}

@Composable
private fun SaturationModule(session: ProjectSession, track: STrack, ensureFx: EnsureFx, fxOf: FxOf, setParam: SetParam, setEnabled: SetEnabled) {
    val fx = fxOf(track, "SATURATION")
    ModuleCard("Saturation", "Add warmth and presence", fx?.enabled == true, { enabled ->
        val created = ensureFx(track, "SATURATION", mapOf("drive" to 25f, "output" to -1f))
        setEnabled(track, created.id, enabled)
    }) {
        val current = fxOf(track, "SATURATION") ?: return@ModuleCard
        LabeledSlider("Drive", "${(current.params["drive"] ?: 25f).toInt()}%", current.params["drive"] ?: 25f, { setParam(track, current.id, "drive", it) }, valueRange = 0f..100f)
        @Suppress("UNUSED_EXPRESSION") session
    }
}

/** Broadband downward expander tuned for room-noise reduction between phrases. */
private fun reduceNoise(stereo: FloatArray, sampleRate: Int, amount: Float) {
    val frames = stereo.size / 2
    var env = 0f
    val attack = kotlin.math.exp(-1.0 / (sampleRate * 0.003)).toFloat()
    val release = kotlin.math.exp(-1.0 / (sampleRate * 0.08)).toFloat()
    val threshold = 0.012f + amount * 0.05f
    val floorGain = 1f - amount * 0.92f
    var gate = 1f
    for (f in 0 until frames) {
        val peak = maxOf(abs(stereo[f * 2]), abs(stereo[f * 2 + 1]))
        val coef = if (peak > env) attack else release
        env = peak + coef * (env - peak)
        val target = if (env >= threshold) 1f else floorGain + (1f - floorGain) * (env / threshold)
        gate += 0.003f * (target - gate)
        stereo[f * 2] *= gate
        stereo[f * 2 + 1] *= gate
    }
}

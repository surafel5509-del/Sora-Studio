package com.sorastudio.feature.export

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.sorastudio.core.audio.ExportFormat
import com.sorastudio.core.audio.ExportOptions
import com.sorastudio.core.audio.OfflineExporter
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.dsp.WavBitDepth
import com.sorastudio.core.midi.MidiFileIO
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.toMidi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class Scope { FULL, STEMS, TRACK }

/**
 * Export & mastering: offline render to WAV (16/24-bit/float), M4A (AAC),
 * MP3/FLAC where the device provides an encoder, plus MIDI file export.
 * Renders use the identical audio path as playback.
 */
@Composable
fun ExportScreen(
    session: ProjectSession,
    controller: PlaybackController,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var format by remember { mutableStateOf(ExportFormat.WAV) }
    var bitDepth by remember { mutableStateOf(WavBitDepth.PCM16) }
    var sampleRate by remember { mutableStateOf(44100) }
    var stereo by remember { mutableStateOf(true) }
    var normalize by remember { mutableStateOf(true) }
    var exportScope by remember { mutableStateOf(Scope.FULL) }
    var bitrate by remember { mutableFloatStateOf(256f) }

    var busy by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var progressLabel by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<File>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    val exportsDir = remember(context) {
        File(context.getExternalFilesDir(null) ?: context.filesDir, "exports").apply { mkdirs() }
    }
    val tempDir = remember(context) { File(context.cacheDir, "export-temp").apply { mkdirs() } }

    var pendingSaveFile by remember { mutableStateOf<File?>(null) }
    val saveDocumentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("*/*")
    ) { uri: Uri? ->
        val file = pendingSaveFile
        if (uri != null && file != null) {
            scope.launch {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        file.inputStream().use { input -> input.copyTo(out) }
                    }
                }
            }
        }
        pendingSaveFile = null
    }

    fun cleanName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]+"), "_").trim('_').ifBlank { "SORA_Export" }

    fun runExport() {
        if (busy) return
        busy = true
        error = null
        results = emptyList()
        progress = 0f
        scope.launch {
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    val assetProviderForRate: (Int) -> (String) -> FloatArray? = { rate ->
                        { path ->
                            val file = controller.resolvePath(path)
                            if (file == null) null else controller.assetCache.stereoAt(file, rate)
                        }
                    }
                    // Make sure every asset is decoded before rendering.
                    val files = controller.referencedFiles(project)
                    kotlinx.coroutines.runBlocking { controller.assetCache.ensureLoaded(files) }

                    val outFiles = mutableListOf<File>()
                    when (exportScope) {
                        Scope.FULL -> {
                            val options = ExportOptions(
                                format = format, sampleRate = sampleRate, bitDepth = bitDepth,
                                stereo = stereo, normalize = normalize, bitrateKbps = bitrate.toInt()
                            )
                            val out = File(exportsDir, "${cleanName(project.name)}.${format.extension}")
                            OfflineExporter.export(project, assetProviderForRate, options, out, tempDir) { p ->
                                progress = p
                                progressLabel = "Rendering mix…"
                            }.getOrThrow()
                            outFiles.add(out)
                        }
                        Scope.TRACK -> {
                            val trackId = selectedTrackId ?: throw IllegalStateException("Select a track first.")
                            val trackName = project.trackById(trackId)?.name ?: "Track"
                            val options = ExportOptions(
                                format = format, sampleRate = sampleRate, bitDepth = bitDepth,
                                stereo = stereo, normalize = normalize, bitrateKbps = bitrate.toInt(),
                                onlyTrackId = trackId
                            )
                            val out = File(exportsDir, "${cleanName(project.name)}_${cleanName(trackName)}.${format.extension}")
                            OfflineExporter.export(project, assetProviderForRate, options, out, tempDir) { p ->
                                progress = p
                                progressLabel = "Rendering $trackName…"
                            }.getOrThrow()
                            outFiles.add(out)
                        }
                        Scope.STEMS -> {
                            val tracks = project.tracks
                            tracks.forEachIndexed { index, track ->
                                val options = ExportOptions(
                                    format = format, sampleRate = sampleRate, bitDepth = bitDepth,
                                    stereo = stereo, normalize = normalize, bitrateKbps = bitrate.toInt(),
                                    onlyTrackId = track.id
                                )
                                val out = File(exportsDir, "${cleanName(project.name)}_stem-${cleanName(track.name)}.${format.extension}")
                                OfflineExporter.export(project, assetProviderForRate, options, out, tempDir) { p ->
                                    progress = (index + p) / tracks.size
                                    progressLabel = "Stem ${index + 1}/${tracks.size}: ${track.name}"
                                }.getOrThrow()
                                outFiles.add(out)
                            }
                        }
                    }
                    outFiles
                }
            }
            busy = false
            result.onSuccess { files ->
                results = files
                progress = 1f
                progressLabel = "Done"
            }.onFailure { throwable ->
                error = throwable.message ?: "Export failed."
                progressLabel = ""
            }
        }
    }

    fun exportMidi() {
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                val tracks = project.tracks.map { track ->
                    val notes = project.clipsForTrack(track.id)
                        .filter { it.type == com.sorastudio.core.project.ClipType.MIDI }
                        .flatMap { clip ->
                            clip.notes.map { note ->
                                note.copy(startBeats = clip.startBeats + (note.startBeats - clip.contentOffsetBeats)).toMidi()
                            }
                        }
                    track.name to notes
                }.filter { it.second.isNotEmpty() }
                val out = File(exportsDir, "${cleanName(project.name)}.mid")
                MidiFileIO.write(out, project.bpm, tracks)
                out
            }
            results = results + file
        }
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.FileDownload, null, tint = SoraPalette.Accent)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("Export & Master", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(project.name, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (onClose != null) TextButton(onClick = onClose) { Text("Close") }
            }
        }

        Column(
            Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(14.dp)
        ) {
            SectionTitle("What to export")
            Row(Modifier.padding(vertical = 6.dp)) {
                SmallToggleButton("Full Song", exportScope == Scope.FULL, { exportScope = Scope.FULL })
                Spacer(Modifier.width(6.dp))
                SmallToggleButton("Stems (per track)", exportScope == Scope.STEMS, { exportScope = Scope.STEMS })
                Spacer(Modifier.width(6.dp))
                SmallToggleButton("Selected Track", exportScope == Scope.TRACK, { exportScope = Scope.TRACK })
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Format")
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
                for (fmt in ExportFormat.values()) {
                    val available = OfflineExporter.formatAvailable(fmt)
                    SmallToggleButton(
                        if (available) fmt.displayName else "${fmt.displayName} —",
                        format == fmt,
                        { format = fmt }
                    )
                    Spacer(Modifier.width(6.dp))
                }
            }
            if (!OfflineExporter.formatAvailable(format)) {
                Text(
                    OfflineExporter.unavailableReason(format),
                    style = MaterialTheme.typography.bodySmall,
                    color = SoraPalette.Warning
                )
            }

            if (format == ExportFormat.WAV) {
                Spacer(Modifier.height(8.dp))
                SectionTitle("Bit depth")
                Row(Modifier.padding(vertical = 6.dp)) {
                    SmallToggleButton("16-bit", bitDepth == WavBitDepth.PCM16, { bitDepth = WavBitDepth.PCM16 })
                    Spacer(Modifier.width(6.dp))
                    SmallToggleButton("24-bit", bitDepth == WavBitDepth.PCM24, { bitDepth = WavBitDepth.PCM24 })
                    Spacer(Modifier.width(6.dp))
                    SmallToggleButton("32-bit float", bitDepth == WavBitDepth.FLOAT32, { bitDepth = WavBitDepth.FLOAT32 })
                }
            } else if (format == ExportFormat.M4A || format == ExportFormat.MP3) {
                LabeledSlider("Bitrate", "${bitrate.toInt()} kbps", bitrate, { bitrate = it }, valueRange = 96f..320f)
            }

            Spacer(Modifier.height(8.dp))
            SectionTitle("Options")
            Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Sample rate", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                for (rate in listOf(44100, 48000, 96000)) {
                    SmallToggleButton("${rate / 1000}k", sampleRate == rate, { sampleRate = rate })
                    Spacer(Modifier.width(4.dp))
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Stereo", style = MaterialTheme.typography.labelLarge)
                    Text("Off = mono sum", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = stereo, onCheckedChange = { stereo = it })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Normalize", style = MaterialTheme.typography.labelLarge)
                    Text("Peak-normalize the render to −1 dB (after master chain).", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = normalize, onCheckedChange = { normalize = it })
            }
            Text(
                "Master effects and the safety limiter (if enabled) are printed into the export, exactly as heard.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(14.dp))
            if (busy) {
                LinearProgressIndicator(progress = progress.coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text("$progressLabel ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(6.dp))
            }
            Row {
                Button(onClick = { runExport() }, enabled = !busy, modifier = Modifier.weight(1f)) {
                    Text(if (busy) "Rendering…" else "Export Audio")
                }
                Spacer(Modifier.width(10.dp))
                Button(onClick = { exportMidi() }, enabled = !busy) {
                    Text("Export MIDI")
                }
            }

            if (results.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                SectionTitle("Exported files")
                for (file in results) {
                    Spacer(Modifier.height(6.dp))
                    SoraCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.CheckCircle, null, tint = SoraPalette.Success, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text("${file.length() / 1024} KB · ${file.parentFile?.name}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                pendingSaveFile = file
                                saveDocumentLauncher.launch(file.name)
                            }) { Text("Save As…") }
                            IconButton(onClick = { shareFile(context, file) }) {
                                Icon(Icons.Filled.Share, "Share")
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun shareFile(context: Context, file: File) {
    runCatching {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = when (file.extension.lowercase()) {
                "mid" -> "audio/midi"
                "m4a" -> "audio/mp4"
                "mp3" -> "audio/mpeg"
                "flac" -> "audio/flac"
                else -> "audio/wav"
            }
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, file.name)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
    }
}

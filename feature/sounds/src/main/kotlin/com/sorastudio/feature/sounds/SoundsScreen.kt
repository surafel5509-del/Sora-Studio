package com.sorastudio.feature.sounds

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
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.common.newId
import com.sorastudio.core.database.PatternLibraryRepository
import com.sorastudio.core.database.PresetRepository
import com.sorastudio.core.database.SoundLibraryRepository
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.dsp.DrumSynth
import com.sorastudio.core.dsp.DrumType
import com.sorastudio.core.dsp.EffectCatalog
import com.sorastudio.core.dsp.InstrumentLibrary
import com.sorastudio.core.project.ClipType
import com.sorastudio.core.project.ProjectFactory
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SClip
import com.sorastudio.core.project.SEffect
import com.sorastudio.core.project.TrackType
import com.sorastudio.core.storage.SampleStore
import com.sorastudio.core.storage.SoundItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private enum class SoundsTab(val label: String) {
    DRUMS("Drum Kits"), PRESETS("Instruments"), SAMPLES("Samples"), FX("FX Presets"), PATTERNS("Patterns")
}

/**
 * Offline sound library: generated drum kits & synth presets (bundled, original),
 * user-imported samples via the Storage Access Framework, effect presets and
 * saved drum patterns. Everything previews through the engine — no network.
 */
@Composable
fun SoundsScreen(
    session: ProjectSession,
    controller: PlaybackController,
    sampleStore: SampleStore,
    soundLibrary: SoundLibraryRepository,
    patternLibrary: PatternLibraryRepository,
    presetRepository: PresetRepository,
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val scope = rememberCoroutineScope()

    var tab by remember { mutableStateOf(SoundsTab.DRUMS) }
    var query by remember { mutableStateOf("") }
    var favoritesOnly by remember { mutableStateOf(false) }
    var samples by remember { mutableStateOf<List<SoundItem>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    val favorites by soundLibrary.observeFavorites().collectAsState(initial = emptyList())
    val recents by soundLibrary.observeRecents().collectAsState(initial = emptyList())
    val favoriteIds = favorites.map { it.soundId }.toSet()
    val libraryPatterns by patternLibrary.observePatterns().collectAsState(initial = emptyList())
    val userEffectPresets by presetRepository.observeEffects().collectAsState(initial = emptyList())

    fun refreshSamples() {
        scope.launch {
            samples = withContext(Dispatchers.IO) { sampleStore.listSamples() }
        }
    }
    LaunchedEffect(Unit) { refreshSamples() }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            scope.launch {
                var ok = 0
                for (uri in uris) {
                    val item = withContext(Dispatchers.IO) {
                        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "sample.wav"
                        runCatching { sampleStore.importFromUri(uri, name, "Imported") }.getOrNull()
                    }
                    if (item != null) {
                        ok++
                        item.filePath?.let { controller.preloadFiles(listOf(File(it))) }
                    }
                }
                refreshSamples()
                status = "Imported $ok sample(s)."
            }
        }
    }

    val track = project.trackById(selectedTrackId)

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Sounds", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Bundled kits & presets are generated in-app — fully offline.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Button(onClick = { importLauncher.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Filled.FileUpload, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Import")
                    }
                    Spacer(Modifier.width(4.dp))
                    IconButton(onClick = { controller.stopPreviews() }) {
                        Icon(Icons.Filled.Stop, "Stop preview")
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    label = { Text("Search sounds") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                Row(Modifier.horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
                    for (t in SoundsTab.values()) {
                        SmallToggleButton(t.label, tab == t, { tab = t })
                        Spacer(Modifier.width(6.dp))
                    }
                    SmallToggleButton("♥ Favorites", favoritesOnly, { favoritesOnly = !favoritesOnly }, activeColor = SoraPalette.Accent)
                }
                status?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        when (tab) {
            SoundsTab.DRUMS -> DrumKitsTab(
                session = session,
                controller = controller,
                query = query,
                favoriteIds = favoriteIds,
                favoritesOnly = favoritesOnly,
                onToggleFavorite = { id, name ->
                    scope.launch { soundLibrary.toggleFavorite(id, name, "drum") }
                },
                onKitUsed = { kitId, name ->
                    scope.launch { soundLibrary.touchRecent(kitId, name, "drum") }
                }
            )
            SoundsTab.PRESETS -> PresetsTab(
                session = session,
                controller = controller,
                query = query,
                favoriteIds = favoriteIds,
                favoritesOnly = favoritesOnly,
                onToggleFavorite = { id, name -> scope.launch { soundLibrary.toggleFavorite(id, name, "preset") } },
                onUsed = { id, name -> scope.launch { soundLibrary.touchRecent(id, name, "preset") } },
                hasInstrumentTrack = track?.type == TrackType.INSTRUMENT
            )
            SoundsTab.SAMPLES -> SamplesTab(
                session = session,
                controller = controller,
                samples = samples,
                query = query,
                favoriteIds = favoriteIds,
                favoritesOnly = favoritesOnly,
                recents = recents,
                onToggleFavorite = { item -> scope.launch { soundLibrary.toggleFavorite(item.id, item.name, "sample") } },
                onDelete = { item ->
                    scope.launch {
                        withContext(Dispatchers.IO) { sampleStore.delete(item) }
                        refreshSamples()
                    }
                },
                onImport = { importLauncher.launch(arrayOf("audio/*")) }
            )
            SoundsTab.FX -> FxPresetsTab(
                session = session,
                query = query,
                userPresets = userEffectPresets.mapNotNull { entity ->
                    presetRepository.decodeChain(entity).firstOrNull()?.let { entity to it }
                },
                onDeleteUserPreset = { entity -> scope.launch { presetRepository.delete(entity) } }
            )
            SoundsTab.PATTERNS -> PatternsTab(
                session = session,
                query = query,
                libraryPatterns = libraryPatterns
            )
        }
    }
}

@Composable
private fun DrumKitsTab(
    session: ProjectSession,
    controller: PlaybackController,
    query: String,
    favoriteIds: Set<String>,
    favoritesOnly: Boolean,
    onToggleFavorite: (String, String) -> Unit,
    onKitUsed: (String, String) -> Unit
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val drumTrack = project.trackById(selectedTrackId)?.takeIf { it.type == TrackType.DRUM }
        ?: project.tracks.firstOrNull { it.type == TrackType.DRUM }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp)
    ) {
        for (kit in DrumSynth.KITS) {
            if (favoritesOnly && kit.id !in favoriteIds) continue
            if (query.isNotBlank() && !kit.name.contains(query, ignoreCase = true)) continue
            item(key = kit.id) {
                SoraCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(kit.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                                Text(kit.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { onToggleFavorite(kit.id, kit.name) }) {
                                Icon(
                                    if (kit.id in favoriteIds) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                                    "Favorite",
                                    tint = if (kit.id in favoriteIds) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                onKitUsed(kit.id, kit.name)
                                if (drumTrack != null) {
                                    session.update { p -> ProjectOps.updateTrack(p, drumTrack.id) { it.copy(drumKitId = kit.id) } }
                                } else {
                                    val (updated, created) = ProjectOps.addTrack(session.current, TrackType.DRUM, "Drums")
                                    session.update { ProjectOps.updateTrack(updated, created.id) { t -> t.copy(drumKitId = kit.id) } }
                                    session.selectTrack(created.id)
                                }
                            }) {
                                Text(if (drumTrack?.drumKitId == kit.id) "In Use" else "Use Kit")
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.horizontalScroll(rememberScrollState())) {
                            for (drum in DrumType.values()) {
                                val shape = RoundedCornerShape(8.dp)
                                Box(
                                    Modifier
                                        .clip(shape)
                                        .background(MaterialTheme.colorScheme.surfaceVariant)
                                        .clickable { controller.drumHit(kit.id, drum, 0.95f) }
                                        .padding(horizontal = 10.dp, vertical = 8.dp)
                                ) {
                                    Text(drum.shortName, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                                }
                                Spacer(Modifier.width(5.dp))
                            }
                        }
                    }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun PresetsTab(
    session: ProjectSession,
    controller: PlaybackController,
    query: String,
    favoriteIds: Set<String>,
    favoritesOnly: Boolean,
    onToggleFavorite: (String, String) -> Unit,
    onUsed: (String, String) -> Unit,
    hasInstrumentTrack: Boolean
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val presets = InstrumentLibrary.PRESETS.filter {
        (!favoritesOnly || it.id in favoriteIds) &&
            (query.isBlank() || it.name.contains(query, true) || it.type.displayName.contains(query, true))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)
    ) {
        var lastCategory: String? = null
        for (preset in presets) {
            if (preset.type.displayName != lastCategory) {
                lastCategory = preset.type.displayName
                item(key = "cat-${preset.type.name}") {
                    SectionTitle(preset.type.displayName, Modifier.padding(top = 8.dp, bottom = 2.dp))
                }
            }
            item(key = preset.id) {
                val shape = RoundedCornerShape(10.dp)
                val track = project.trackById(selectedTrackId)
                val isActive = track?.presetId == preset.id
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(shape)
                        .background(if (isActive) SoraPalette.Accent.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface)
                        .clickable {
                            onUsed(preset.id, preset.name)
                            if (track != null && track.type == TrackType.INSTRUMENT) {
                                session.update { p -> ProjectOps.updateTrack(p, track.id) { it.copy(presetId = preset.id) } }
                            }
                            controller.noteOn("sounds", preset.id, 60, 0.85f)
                            controller.noteOn("sounds", preset.id, 64, 0.8f)
                            controller.noteOn("sounds", preset.id, 67, 0.8f)
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.PlayArrow, "Preview", tint = SoraPalette.Accent, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(preset.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (isActive) Text("Active", style = MaterialTheme.typography.labelSmall, color = SoraPalette.Accent)
                    IconButton(onClick = { onToggleFavorite(preset.id, preset.name) }) {
                        Icon(
                            if (preset.id in favoriteIds) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Favorite",
                            tint = if (preset.id in favoriteIds) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        item {
            Text(
                if (hasInstrumentTrack) "Tapping assigns the preset to the selected instrument track."
                else "Tip: select an instrument track in the Studio to assign presets.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 10.dp)
            )
        }
    }
}

@Composable
private fun SamplesTab(
    session: ProjectSession,
    controller: PlaybackController,
    samples: List<SoundItem>,
    query: String,
    favoriteIds: Set<String>,
    favoritesOnly: Boolean,
    recents: List<com.sorastudio.core.database.RecentSoundEntity>,
    onToggleFavorite: (SoundItem) -> Unit,
    onDelete: (SoundItem) -> Unit,
    onImport: () -> Unit
) {
    val project by session.project.collectAsState()
    val filtered = samples.filter {
        (!favoritesOnly || it.id in favoriteIds) &&
            (query.isBlank() || it.name.contains(query, true) || it.category.contains(query, true))
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        if (recents.isNotEmpty() && query.isBlank() && !favoritesOnly) {
            item {
                SectionTitle("Recent")
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                    for (recent in recents.take(10)) {
                        SmallToggleButton(recent.name, false, {})
                        Spacer(Modifier.width(6.dp))
                    }
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                SoraCard(Modifier.fillMaxWidth()) {
                    Column {
                        Text("No imported samples yet", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "Import WAV, MP3, M4A, OGG or FLAC files from your device. They are copied into the app so projects always keep working offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onImport) { Text("Import Samples") }
                    }
                }
            }
        }
        items(filtered, key = { it.id }) { item ->
            SoraCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${item.category} · ${if (item.durationMs > 0) "${item.durationMs / 1000.0}s · " else ""}${item.sizeBytes / 1024} KB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = {
                        item.filePath?.let { controller.previewFile(File(it)) }
                    }) { Icon(Icons.Filled.PlayArrow, "Preview") }
                    IconButton(onClick = {
                        // Add as audio clip on a new/existing audio track at the playhead
                        val path = item.filePath ?: return@IconButton
                        val p = session.current
                        val audioTrack = p.tracks.firstOrNull { it.type == TrackType.AUDIO }
                        val trackId: String
                        val baseProject = if (audioTrack == null) {
                            val (updated, created) = ProjectOps.addTrack(p, TrackType.AUDIO, "Samples")
                            trackId = created.id
                            updated
                        } else {
                            trackId = audioTrack.id
                            p
                        }
                        val lengthBeats = if (item.durationMs > 0)
                            com.sorastudio.core.common.TempoMath.secondsToBeats(item.durationMs / 1000.0, p.bpm)
                        else 4.0
                        val clip = SClip(
                            trackId = trackId, name = item.name, type = ClipType.AUDIO,
                            startBeats = controller.positionBeats(), lengthBeats = lengthBeats,
                            audioPath = path, audioLengthBeats = lengthBeats
                        )
                        session.update { ProjectOps.addClip(baseProject, clip) }
                        controller.preloadFiles(listOf(File(path)))
                        session.selectTrack(trackId)
                        session.selectClip(clip.id)
                    }) { Icon(Icons.Filled.PlaylistAdd, "Add to project") }
                    IconButton(onClick = { onToggleFavorite(item) }) {
                        Icon(
                            if (item.id in favoriteIds) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            "Favorite",
                            tint = if (item.id in favoriteIds) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { onDelete(item) }) { Text("Delete") }
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun FxPresetsTab(
    session: ProjectSession,
    query: String,
    userPresets: List<Pair<com.sorastudio.core.database.PresetEntity, SEffect>>,
    onDeleteUserPreset: (com.sorastudio.core.database.PresetEntity) -> Unit
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val track = project.trackById(selectedTrackId)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        item {
            Text(
                if (track != null) "Applying a preset adds it to “${track.name}”."
                else "Select a track in the Studio first, then apply presets here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        for ((type, presets) in EffectCatalog.PRESETS) {
            item(key = "fxcat-${type.name}") { SectionTitle(type.displayName, Modifier.padding(top = 8.dp)) }
            items(presets.filter { query.isBlank() || it.name.contains(query, true) }, key = { type.name + it.name }) { preset ->
                SoraCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(preset.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (track != null) {
                                val fx = SEffect(type = type.name, params = EffectCatalog.defaultParams(type) + preset.params)
                                session.update { ProjectOps.addEffect(it, track.id, fx) }
                            }
                        }, enabled = track != null) { Text("Apply") }
                    }
                }
            }
        }
        if (userPresets.isNotEmpty()) {
            item { SectionTitle("Your Presets", Modifier.padding(top = 8.dp)) }
            items(userPresets.filter { query.isBlank() || it.first.name.contains(query, true) }, key = { it.first.id }) { (entity, fx) ->
                SoraCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(entity.name, style = MaterialTheme.typography.titleSmall)
                            Text(entity.effectType ?: "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            if (track != null) {
                                session.update { ProjectOps.addEffect(it, track.id, fx.copy(id = newId("fx"))) }
                            }
                        }, enabled = track != null) { Text("Apply") }
                        TextButton(onClick = { onDeleteUserPreset(entity) }) { Text("Delete") }
                    }
                }
            }
        }
    }
}

@Composable
private fun PatternsTab(
    session: ProjectSession,
    query: String,
    libraryPatterns: List<com.sorastudio.core.project.SDrumPattern>
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(14.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)
    ) {
        item { SectionTitle("Built-in grooves") }
        val grooves = listOf(
            "Hip-Hop Groove" to { ProjectFactory.Grooves.hipHop() },
            "Trap Skeleton" to { ProjectFactory.Grooves.trap() },
            "Four on the Floor" to { ProjectFactory.Grooves.fourFloor() },
            "Lo-Fi Pocket" to { ProjectFactory.Grooves.lofi() },
            "Pop Backbeat" to { ProjectFactory.Grooves.pop() }
        )
        items(grooves.filter { query.isBlank() || it.first.contains(query, true) }, key = { it.first }) { (name, make) ->
            SoraCard(Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = {
                        session.update { ProjectOps.addPattern(it, make()) }
                    }) { Text("Add to Project") }
                }
            }
        }
        if (libraryPatterns.isNotEmpty()) {
            item { SectionTitle("Your patterns", Modifier.padding(top = 10.dp)) }
            items(libraryPatterns.filter { query.isBlank() || it.name.contains(query, true) }, key = { it.id }) { pattern ->
                SoraCard(Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(pattern.name, style = MaterialTheme.typography.titleSmall)
                            Text("${pattern.stepsPerBar} steps · kit ${pattern.kitId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = {
                            session.update { ProjectOps.addPattern(it, pattern.copy(id = newId("pat"))) }
                        }) { Text("Add to Project") }
                    }
                }
            }
        }
        item {
            Text(
                "Patterns are added to the project pattern pool — place them from the Drums tab or Arrangement.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

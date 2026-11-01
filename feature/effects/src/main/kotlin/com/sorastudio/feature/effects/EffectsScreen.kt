package com.sorastudio.feature.effects

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sorastudio.core.audio.PlaybackController
import com.sorastudio.core.database.PresetEntity
import com.sorastudio.core.designsystem.LabeledSlider
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SmallToggleButton
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.dsp.EffectCatalog
import com.sorastudio.core.dsp.EffectType
import com.sorastudio.core.project.ProjectOps
import com.sorastudio.core.project.ProjectSession
import com.sorastudio.core.project.SEffect
import kotlinx.serialization.decodeFromString
import kotlin.math.ln

/**
 * Insert-effects editor for the selected track (or Master).
 * Parameters map 1:1 to the real DSP processors in :core:dsp.
 */
@Composable
fun EffectsScreen(
    session: ProjectSession,
    controller: PlaybackController,
    targetTrackId: String?,
    onTargetChange: (String?) -> Unit,
    userPresets: List<PresetEntity> = emptyList(),
    onSaveUserPreset: (name: String, effect: SEffect) -> Unit = { _, _ -> },
    onDeleteUserPreset: (PresetEntity) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val project by session.project.collectAsState()
    val selectedTrackId by session.selectedTrackId.collectAsState()
    val effectiveTarget = targetTrackId ?: selectedTrackId
    var showAddDialog by remember { mutableStateOf(false) }

    val chain: List<SEffect> = if (effectiveTarget == null) {
        project.master.fxChain
    } else {
        project.trackById(effectiveTarget)?.fxChain ?: emptyList()
    }
    val targetName = if (effectiveTarget == null) "Master" else project.trackById(effectiveTarget)?.name ?: "Track"

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Surface(color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Effects", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text("Insert chain for $targetName", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Add Effect")
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(Modifier.horizontalScroll(rememberScrollState())) {
                    for (track in project.tracks) {
                        SmallToggleButton(track.name, effectiveTarget == track.id, { onTargetChange(track.id); session.selectTrack(track.id) })
                        Spacer(Modifier.width(6.dp))
                    }
                    SmallToggleButton("Master", effectiveTarget == null, { onTargetChange(null) })
                }
            }
        }

        if (chain.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("No effects on $targetName", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Add EQ, compression, space and modulation — everything processes in real time.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { showAddDialog = true }) { Text("Browse Effects") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp)
            ) {
                items(chain, key = { it.id }) { fx ->
                    EffectCard(
                        fx = fx,
                        index = chain.indexOf(fx),
                        chainSize = chain.size,
                        session = session,
                        targetTrackId = effectiveTarget,
                        onSaveUserPreset = onSaveUserPreset,
                        userPresets = userPresets.filter { it.effectType == fx.type },
                        onDeleteUserPreset = onDeleteUserPreset
                    )
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        }
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Add Effect") },
            text = {
                LazyColumn(Modifier.height(420.dp)) {
                    val grouped = EffectType.values().groupBy { it.category }
                    for ((category, types) in grouped) {
                        item { SectionTitle(category, Modifier.padding(top = 10.dp, bottom = 4.dp)) }
                        items(types) { type ->
                            TextButton(onClick = {
                                showAddDialog = false
                                val effect = SEffect(
                                    type = type.name,
                                    params = EffectCatalog.defaultParams(type),
                                    wet = if (type in setOf(EffectType.REVERB, EffectType.DELAY, EffectType.PING_PONG_DELAY, EffectType.CHORUS, EffectType.FLANGER)) 0.35f else 1f
                                )
                                session.update { ProjectOps.addEffect(it, effectiveTarget, effect) }
                                @Suppress("UNUSED_EXPRESSION") controller
                            }, modifier = Modifier.fillMaxWidth()) {
                                Text(type.displayName, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { showAddDialog = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun EffectCard(
    fx: SEffect,
    index: Int,
    chainSize: Int,
    session: ProjectSession,
    targetTrackId: String?,
    onSaveUserPreset: (String, SEffect) -> Unit,
    userPresets: List<PresetEntity>,
    onDeleteUserPreset: (PresetEntity) -> Unit
) {
    val type = runCatching { EffectType.valueOf(fx.type) }.getOrNull()
    var presetMenu by remember { mutableStateOf(false) }
    var saveNameDialog by remember { mutableStateOf(false) }

    SoraCard(Modifier.fillMaxWidth()) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = fx.enabled, onCheckedChange = { enabled ->
                    session.update { p -> ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(enabled = enabled) } }
                })
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(type?.displayName ?: fx.type, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(type?.category ?: "", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box {
                    TextButton(onClick = { presetMenu = true }) { Text("Presets ▾") }
                    DropdownMenu(expanded = presetMenu, onDismissRequest = { presetMenu = false }) {
                        val factory = if (type != null) EffectCatalog.PRESETS[type].orEmpty() else emptyList()
                        for (preset in factory) {
                            DropdownMenuItem(text = { Text(preset.name) }, onClick = {
                                presetMenu = false
                                session.update { p ->
                                    ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(params = it.params + preset.params) }
                                }
                            })
                        }
                        for (userPreset in userPresets) {
                            DropdownMenuItem(text = { Text("★ ${userPreset.name}") }, onClick = {
                                presetMenu = false
                                val decoded = runCatching {
                                    com.sorastudio.core.project.ProjectCodec.json.decodeFromString<List<SEffect>>(userPreset.payloadJson)
                                }.getOrNull()?.firstOrNull()
                                if (decoded != null) {
                                    session.update { p ->
                                        ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(params = decoded.params, wet = decoded.wet) }
                                    }
                                }
                            }, trailingIcon = {
                                IconButton(onClick = { onDeleteUserPreset(userPreset) }) {
                                    Icon(Icons.Filled.Delete, "Delete preset", modifier = Modifier.size(16.dp))
                                }
                            })
                        }
                        DropdownMenuItem(text = { Text("Reset to defaults") }, onClick = {
                            presetMenu = false
                            if (type != null) {
                                session.update { p ->
                                    ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(params = EffectCatalog.defaultParams(type)) }
                                }
                            }
                        })
                    }
                }
                IconButton(onClick = { saveNameDialog = true }) { Icon(Icons.Filled.Save, "Save preset", modifier = Modifier.size(18.dp)) }
                if (index > 0) {
                    IconButton(onClick = {
                        session.update { ProjectOps.moveEffect(it, targetTrackId, fx.id, index - 1) }
                    }) { Icon(Icons.Filled.ArrowUpward, "Move up", modifier = Modifier.size(18.dp)) }
                }
                if (index < chainSize - 1) {
                    IconButton(onClick = {
                        session.update { ProjectOps.moveEffect(it, targetTrackId, fx.id, index + 1) }
                    }) { Icon(Icons.Filled.ArrowDownward, "Move down", modifier = Modifier.size(18.dp)) }
                }
                IconButton(onClick = {
                    session.update { ProjectOps.removeEffect(it, targetTrackId, fx.id) }
                }) { Icon(Icons.Filled.Delete, "Remove effect", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp)) }
            }

            LabeledSlider("Wet / Dry", "${(fx.wet * 100).toInt()}%", fx.wet, { v ->
                session.update(recordUndo = false) { p ->
                    ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(wet = v) }
                }
            }, valueRange = 0f..1f)

            if (type != null) {
                val defs = EffectCatalog.PARAMS[type].orEmpty()
                for (def in defs) {
                    val current = fx.params[def.key] ?: def.default
                    val isFreq = def.unit == "Hz" && def.max >= 1000f
                    if (isFreq) {
                        val ui = freqParamToUi(current, def.min, def.max)
                        LabeledSlider(def.label, "${current.toInt()} Hz", ui, { v ->
                            val hz = uiToFreqParam(v, def.min, def.max)
                            session.update(recordUndo = false) { p ->
                                ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(params = it.params + (def.key to hz)) }
                            }
                        }, valueRange = 0f..1f)
                    } else {
                        LabeledSlider(def.label, formatParam(current, def.unit), current, { v ->
                            session.update(recordUndo = false) { p ->
                                ProjectOps.updateEffect(p, targetTrackId, fx.id) { it.copy(params = it.params + (def.key to v)) }
                            }
                        }, valueRange = def.min..def.max)
                    }
                }
            }
        }
    }

    if (saveNameDialog) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { saveNameDialog = false },
            title = { Text("Save Preset") },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("Preset name") }, singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    onSaveUserPreset(name.ifBlank { "My ${type?.displayName ?: "Effect"}" }, fx)
                    saveNameDialog = false
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { saveNameDialog = false }) { Text("Cancel") } }
        )
    }
}

private fun formatParam(v: Float, unit: String): String = when {
    unit == "dB" -> "%+.1f dB".format(v)
    unit == "%" -> "${v.toInt()}%"
    unit == "ms" -> "%.0f ms".format(v)
    unit == "Hz" -> "%.1f Hz".format(v)
    unit == ":1" -> "%.1f:1".format(v)
    unit == "st" -> "%+.1f st".format(v)
    unit == "x" -> "×%.0f".format(v)
    else -> "%.2f".format(v)
}

private fun freqParamToUi(hz: Float, min: Float, max: Float): Float {
    val lo = ln(min.toDouble()); val hi = ln(max.toDouble())
    return ((ln(hz.toDouble().coerceIn(min.toDouble(), max.toDouble())) - lo) / (hi - lo)).toFloat()
}

private fun uiToFreqParam(ui: Float, min: Float, max: Float): Float {
    val lo = ln(min.toDouble()); val hi = ln(max.toDouble())
    return kotlin.math.exp(lo + ui * (hi - lo)).toFloat()
}

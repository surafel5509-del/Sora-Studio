package com.sorastudio.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.sorastudio.core.designsystem.SectionTitle
import com.sorastudio.core.designsystem.SoraCard
import com.sorastudio.core.designsystem.SoraPalette
import com.sorastudio.core.project.ProjectFactory
import com.sorastudio.core.project.ProjectSummary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatProjectDate(epochMs: Long): String =
    SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(epochMs))

@Composable
fun HomeScreen(
    projects: List<ProjectSummary>,
    userName: String = "Creator",
    onNewProject: () -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onRecordVocal: () -> Unit,
    onCreateBeat: () -> Unit,
    onOpenStudio: () -> Unit,
    onOpenSounds: () -> Unit,
    onOpenAllProjects: () -> Unit,
    onCreateFromTemplate: (templateId: String) -> Unit,
    onDeleteProject: (ProjectSummary) -> Unit,
    onDuplicateProject: (ProjectSummary) -> Unit,
    onToggleFavorite: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    LazyColumn(
        modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        item {
            Column {
                Text("SORA STUDIO", style = MaterialTheme.typography.labelMedium, color = SoraPalette.Accent, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("Welcome back, $userName", style = MaterialTheme.typography.headlineMedium)
                Text("What will you create today?", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Filled.Add, "New Project", Modifier.weight(1f), onNewProject, primary = true)
                QuickAction(Icons.Filled.Mic, "Record Vocal", Modifier.weight(1f), onRecordVocal)
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                QuickAction(Icons.Filled.MusicNote, "Create Beat", Modifier.weight(1f), onCreateBeat)
                QuickAction(Icons.Filled.Tune, "Open Studio", Modifier.weight(1f), onOpenStudio)
                QuickAction(Icons.Filled.LibraryMusic, "Sounds", Modifier.weight(1f), onOpenSounds)
            }
        }

        item { SectionTitle("Templates") }
        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                for (template in ProjectFactory.TEMPLATES.filter { it.id != "empty" }) {
                    SoraCard(modifier = Modifier.width(190.dp), onClick = { onCreateFromTemplate(template.id) }) {
                        Column {
                            Icon(Icons.Filled.AudioFile, null, tint = SoraPalette.Accent, modifier = Modifier.size(22.dp))
                            Spacer(Modifier.height(8.dp))
                            Text(template.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("Recent Projects") {
                if (projects.isNotEmpty()) {
                    TextButton(onClick = onOpenAllProjects) { Text("View all") }
                }
            }
        }
        if (projects.isEmpty()) {
            item {
                SoraCard {
                    Column {
                        Text("No projects yet", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Create your first project to start making music — everything works fully offline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onNewProject) { Text("Create Project") }
                    }
                }
            }
        } else {
            items(projects.take(6), key = { it.id }) { project ->
                ProjectRow(project, onOpenProject, onDeleteProject, onDuplicateProject, onToggleFavorite)
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun QuickAction(icon: ImageVector, label: String, modifier: Modifier = Modifier, onClick: () -> Unit, primary: Boolean = false) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (primary) SoraPalette.Accent else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(14.dp)
    ) {
        Column {
            Icon(icon, label, tint = if (primary) Color(0xFF1D1430) else SoraPalette.Accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(8.dp))
            Text(
                label,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (primary) Color(0xFF1D1430) else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
fun ProjectRow(
    project: ProjectSummary,
    onOpen: (ProjectSummary) -> Unit,
    onDelete: (ProjectSummary) -> Unit,
    onDuplicate: (ProjectSummary) -> Unit,
    onToggleFavorite: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    SoraCard(modifier = modifier.fillMaxWidth(), onClick = { onOpen(project) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(10.dp)).background(SoraPalette.Accent.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Filled.PlayArrow, "Open", tint = SoraPalette.Accent)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(project.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${project.bpm.toInt()} BPM · ${project.trackCount} tracks · ${formatProjectDate(project.updatedAtEpochMs)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { onToggleFavorite(project) }) {
                Icon(
                    if (project.favorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                    "Favorite",
                    tint = if (project.favorite) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Filled.MoreVert, "More")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text("Open") }, onClick = { menuOpen = false; onOpen(project) })
                    DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menuOpen = false; onDuplicate(project) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menuOpen = false; confirmDelete = true })
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete project?") },
            text = { Text("“${project.name}” and its audio files will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete(project) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun ProjectsScreen(
    projects: List<ProjectSummary>,
    onNewProject: () -> Unit,
    onOpenProject: (ProjectSummary) -> Unit,
    onDeleteProject: (ProjectSummary) -> Unit,
    onDuplicateProject: (ProjectSummary) -> Unit,
    onToggleFavorite: (ProjectSummary) -> Unit,
    modifier: Modifier = Modifier
) {
    var showFavoritesOnly by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val filtered = projects.filter {
        (!showFavoritesOnly || it.favorite) && (query.isBlank() || it.name.contains(query, ignoreCase = true))
    }
    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text("Projects", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Text("${projects.size} project(s) stored on this device", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Button(onClick = onNewProject) {
                Icon(Icons.Filled.Add, null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            label = { Text("Search projects") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.padding(horizontal = 16.dp)) {
            FilterToggle("All", !showFavoritesOnly) { showFavoritesOnly = false }
            Spacer(Modifier.width(8.dp))
            FilterToggle("Favorites", showFavoritesOnly) { showFavoritesOnly = true }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (filtered.isEmpty()) {
                item {
                    SoraCard {
                        Column {
                            Icon(Icons.Filled.FolderOpen, null, tint = SoraPalette.Accent)
                            Spacer(Modifier.height(8.dp))
                            Text("Nothing here yet", style = MaterialTheme.typography.titleSmall)
                            Text("Projects you create will appear here.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            items(filtered, key = { it.id }) { project ->
                ProjectRow(project, onOpenProject, onDeleteProject, onDuplicateProject, onToggleFavorite)
            }
        }
    }
}

@Composable
private fun FilterToggle(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = Modifier
            .clip(shape)
            .background(if (selected) SoraPalette.Accent.copy(alpha = 0.2f) else MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) SoraPalette.Accent else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun NewProjectDialog(
    onCreate: (templateId: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    var selectedTemplate by remember { mutableStateOf("empty") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Project name") },
                    placeholder = { Text("My Song") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text("Template", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(6.dp))
                for (template in ProjectFactory.TEMPLATES) {
                    val selected = template.id == selectedTemplate
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (selected) SoraPalette.Accent.copy(alpha = 0.15f) else Color.Transparent)
                            .clickable { selectedTemplate = template.id }
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier.size(14.dp).clip(CircleShape)
                                .background(if (selected) SoraPalette.Accent else MaterialTheme.colorScheme.surfaceVariant)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column {
                            Text(template.name, style = MaterialTheme.typography.titleSmall)
                            Text(template.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onCreate(selectedTemplate, name.ifBlank { defaultProjectName(selectedTemplate) })
            }) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun defaultProjectName(templateId: String): String = when (templateId) {
    "beat-starter" -> "My Beat"
    "song-starter" -> "My Song"
    "lofi-sketch" -> "Lo-Fi Sketch"
    "dance-starter" -> "Dance Track"
    else -> "Untitled Project"
}

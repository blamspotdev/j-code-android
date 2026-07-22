package dev.jcode.workbench

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceNodeType

/**
 * Workspace-scoped panel pieces (empty state, project roster, welcome card) peeled out of
 * JCodeShell. Stateless / param-driven; the chrome buttons they use live in WorkbenchChrome.kt
 * (same package).
 */

@Composable
internal fun WorkspaceEmptyState(
    workspace: Workspace?,
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(16.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.Files),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = workspace?.name ?: "JCode",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Start with a new folder or open an existing one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchActionButton(text = "New Folder", onClick = onCreateProject, active = true)
            WorkbenchActionButton(text = "Open Folder", onClick = onOpenExternalFolder)
        }
    }
}

@Composable
internal fun ProjectRoster(
    projects: List<Project>,
    selectedProjectId: Long,
    onOpenProject: (Project) -> Unit,
    onRenameProject: (Long, String) -> Unit,
    onRemoveProject: (Long) -> Unit,
    onOpenProjectSettings: (Long) -> Unit,
    onExportProject: (Project) -> Unit,
) {
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var openMenuId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(
            text = "PROJECTS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            projects.forEach { project ->
                val selected = project.id == selectedProjectId
                val isWorkspace = project.nodeType == WorkspaceNodeType.Workspace
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onOpenProject(project) },
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 8.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isWorkspace) jcIcon(JCodeIcon.Files) else jcIcon(JCodeIcon.Code),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(
                            text = project.name,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.weight(1f),
                        )
                        Box {
                            JcTooltip("Project actions") {
                                IconButton(onClick = { openMenuId = project.id }, modifier = Modifier.size(32.dp)) {
                                    Icon(
                                        imageVector = jcIcon(JCodeIcon.MoreVert),
                                        contentDescription = "Project actions",
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            CompactContextMenu(
                                expanded = openMenuId == project.id,
                                onDismissRequest = { openMenuId = null },
                                quickActions = listOf(
                                    ContextAction(JCodeIcon.Rename, "Rename") { renameTarget = project },
                                    ContextAction(JCodeIcon.Delete, "Remove", destructive = true) { onRemoveProject(project.id) },
                                ),
                                listActions = listOf(
                                    ContextAction(JCodeIcon.Open, if (isWorkspace) "Open workspace" else "Open") { onOpenProject(project) },
                                    ContextAction(JCodeIcon.Settings, "Project settings") { onOpenProjectSettings(project.id) },
                                    ContextAction(JCodeIcon.Save, "Export to storage") { onExportProject(project) },
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var newName by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename") },
            text = {
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { onRenameProject(target.id, newName); renameTarget = null },
                    enabled = newName.isNotBlank() && newName.trim() != target.name,
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }
}

@Composable
internal fun WelcomeCard(
    title: String,
    icon: ImageVector,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, fontWeight = FontWeight.SemiBold)
        }
        lines.forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

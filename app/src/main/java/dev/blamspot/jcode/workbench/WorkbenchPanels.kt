package dev.blamspot.jcode.workbench

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
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.design.FileTypeIcon
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JcTooltip
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.fs.Project
import dev.blamspot.jcode.fs.Workspace
import dev.blamspot.jcode.fs.WorkspaceNodeType

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
            .padding(Space.xl),
        verticalArrangement = Arrangement.spacedBy(Space.md, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(Radius.xxxl),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = jcIcon(JCodeIcon.Files),
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
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
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
            .padding(horizontal = Space.ms, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Text(
            text = "PROJECTS",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            projects.forEach { project ->
                val selected = project.id == selectedProjectId
                val isWorkspace = project.nodeType == WorkspaceNodeType.Workspace
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
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
                            .padding(start = Space.sm, top = Space.xs, bottom = Space.xs),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Surface(
                            modifier = Modifier.size(24.dp),
                            shape = RoundedCornerShape(Radius.lg),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                FileTypeIcon(
                                    name = project.name,
                                    isDirectory = true,
                                    size = IconSize.sm,
                                    // A workspace root and a project inside one are both folders, so
                                    // a pack that names neither still tells them apart by glyph.
                                    fallback = if (isWorkspace) JCodeIcon.Files else JCodeIcon.Code,
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
                                        painter = jcIcon(JCodeIcon.MoreVert),
                                        contentDescription = "Project actions",
                                        modifier = Modifier.size(IconSize.md),
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
                CompactFilledButton(
                    text = "Rename",
                    onClick = { onRenameProject(target.id, newName); renameTarget = null },
                    enabled = newName.isNotBlank() && newName.trim() != target.name,
                )
            },
            dismissButton = {
                CompactOutlinedButton(text = "Cancel", onClick = { renameTarget = null })
            },
        )
    }
}

package dev.jcode
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.LocalWebPreviewBrowsers
import dev.jcode.design.WebPreviewBrowsers
import dev.jcode.fs.Project
import dev.jcode.run.ProjectRunner

/**
 * The "Build & Run" side-panel. One compact row per project — all projects inside a User Workspace,
 * or just the open project in the Default Workspace. Each row shows the run kind + port and a status
 * chip, with an inline Run/Stop control and a Configure (gear) action; a running row also reveals
 * Open-in-browser. The actual run + browser launch is orchestrated by the workbench shell
 * (`handleRun`).
 */
@Composable
internal fun RunDebugPanel(
    projects: List<Project>,
    runningProjectId: Long?,
    runUrl: String?,
    runInProgress: Boolean,
    runConfigVersion: Int,
    onRun: (Project) -> Unit,
    onConfigure: (Project) -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(jcIcon(JCodeIcon.Run), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("Build & Run", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }

        if (projects.isEmpty()) {
            Text(
                "Open a project to build & run.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            projects.forEach { project ->
                ProjectRunRow(
                    project = project,
                    isRunning = runningProjectId == project.id,
                    runUrl = runUrl,
                    runInProgress = runInProgress,
                    runConfigVersion = runConfigVersion,
                    onRun = onRun,
                    onConfigure = onConfigure,
                    onStop = onStop,
                    onOpenInBrowser = onOpenInBrowser,
                )
            }
        }
    }
}

@Composable
private fun ProjectRunRow(
    project: Project,
    isRunning: Boolean,
    runUrl: String?,
    runInProgress: Boolean,
    runConfigVersion: Int,
    onRun: (Project) -> Unit,
    onConfigure: (Project) -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
) {
    // Re-resolve when the project changes or a config is saved (runConfigVersion bumps).
    val plan = remember(project.id, runConfigVersion) { ProjectRunner.effectivePlan(project) }
    val configured = plan != null
    val subline = when {
        plan == null -> "Not configured"
        plan.readyPort > 0 -> "${plan.kindLabel} · :${plan.readyPort}"
        else -> plan.kindLabel
    }
    val statusText = when {
        isRunning && runInProgress -> "Building…"
        isRunning -> "Running"
        configured -> "Idle"
        else -> "Not set up"
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)) {
      Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = project.name,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    RunStatusChip(text = statusText, active = isRunning)
                }
                Text(
                    text = subline,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (isRunning && runUrl != null) {
                JcTooltip("Open in browser") {
                    IconButton(onClick = onOpenInBrowser) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                            contentDescription = "Open in browser",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            if (isRunning) {
                JcTooltip("Stop") {
                    IconButton(onClick = onStop) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.Stop),
                            contentDescription = "Stop",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            } else {
                JcTooltip("Build & Run") {
                    IconButton(onClick = { onRun(project) }, enabled = configured) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.Run),
                            contentDescription = "Build & Run",
                            tint = if (configured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
            }
            JcTooltip("Configure") {
                IconButton(onClick = { onConfigure(project) }) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Settings),
                        contentDescription = "Configure",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        }
        // Web-serving projects (dev servers with a ready port) get a per-project browser override.
        if ((plan?.readyPort ?: 0) > 0) {
            WebPreviewSelector(projectKey = project.id.toString())
        }
      }
    }
}

/** Per-project "Open web previews in" override; INHERIT falls back to the Settings global default. */
@Composable
private fun WebPreviewSelector(projectKey: String) {
    val webPreview = LocalWebPreviewBrowsers.current
    var open by remember { mutableStateOf(false) }
    val raw = webPreview.projectChoice(projectKey)
    val shown = if (raw == WebPreviewBrowsers.INHERIT) {
        "Default (${webPreview.label(webPreview.globalChoice)})"
    } else {
        webPreview.label(raw)
    }
    val options = buildList {
        add(WebPreviewBrowsers.INHERIT)
        add(WebPreviewBrowsers.SYSTEM)
        add(WebPreviewBrowsers.ASK)
        webPreview.available.forEach { add(it.packageName) }
    }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { open = true }
                .padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.OpenInNew,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = "Preview in: $shown",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(webPreview.label(choice)) },
                    leadingIcon = {
                        if (choice == raw) {
                            Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                        }
                    },
                    onClick = { webPreview.onSetProject(projectKey, choice); open = false },
                )
            }
        }
    }
}

@Composable
private fun RunStatusChip(text: String, active: Boolean) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.6f)
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

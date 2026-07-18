package dev.jcode
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.core.config.BuildConfig
import dev.jcode.core.config.RunConfig
import dev.jcode.core.debug.DebugState
import dev.jcode.design.LocalWebPreviewBrowsers
import dev.jcode.design.WebPreviewBrowsers
import dev.jcode.fs.Project
import dev.jcode.run.ProjectRunner
import dev.jcode.workbench.DebugSessionUi

/**
 * The "Run" side-panel. In a User Workspace it first lists projects; tapping one opens a Build | Run
 * segmented detail. In the Default Workspace it goes straight to the open project's detail. The Run
 * segment lists run configs (each with Run ▷ / Debug 🐞 / Configure) plus the live debug session; the
 * Build segment lists build tasks (each with Build ▷ / Configure). Multiple configs of each kind are
 * supported. Execution is orchestrated by the workbench shell via the callbacks.
 */
@Composable
internal fun RunPanel(
    projects: List<Project>,
    inUserWorkspace: Boolean,
    runningProjectId: Long?,
    runningRunName: String?,
    runUrl: String?,
    runInProgress: Boolean,
    runConfigVersion: Int,
    debugUi: DebugSessionUi,
    onRun: (Project, RunConfig) -> Unit,
    onDebug: (Project, RunConfig) -> Unit,
    onBuild: (Project, BuildConfig) -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onConfigureRun: (Project, Int?) -> Unit,
    onConfigureBuild: (Project, Int?) -> Unit,
    onDeleteRun: (Project, Int) -> Unit,
    onDeleteBuild: (Project, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // In a User Workspace, remember which project's detail is open; Default Workspace uses its one project.
    var pickedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeProject = if (inUserWorkspace) projects.firstOrNull { it.id == pickedId } else projects.firstOrNull()

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
            if (inUserWorkspace && activeProject != null) {
                IconButton(onClick = { pickedId = null }, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back to projects", modifier = Modifier.size(20.dp))
                }
            }
            Icon(jcIcon(JCodeIcon.Run), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(
                text = activeProject?.name ?: "Run",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        when {
            projects.isEmpty() -> HintText("Open a project to build & run.")
            inUserWorkspace && activeProject == null -> projects.forEach { project ->
                ProjectPickRow(project, running = runningProjectId == project.id, onClick = { pickedId = project.id })
            }
            activeProject != null -> ProjectRunBuildDetail(
                project = activeProject,
                isRunning = runningProjectId == activeProject.id,
                runningRunName = runningRunName,
                runUrl = runUrl,
                runInProgress = runInProgress,
                runConfigVersion = runConfigVersion,
                debugUi = debugUi,
                onRun = onRun,
                onDebug = onDebug,
                onBuild = onBuild,
                onStop = onStop,
                onOpenInBrowser = onOpenInBrowser,
                onConfigureRun = onConfigureRun,
                onConfigureBuild = onConfigureBuild,
                onDeleteRun = onDeleteRun,
                onDeleteBuild = onDeleteBuild,
            )
        }
    }
}

private enum class Segment { Run, Build }

@Composable
private fun ProjectRunBuildDetail(
    project: Project,
    isRunning: Boolean,
    runningRunName: String?,
    runUrl: String?,
    runInProgress: Boolean,
    runConfigVersion: Int,
    debugUi: DebugSessionUi,
    onRun: (Project, RunConfig) -> Unit,
    onDebug: (Project, RunConfig) -> Unit,
    onBuild: (Project, BuildConfig) -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onConfigureRun: (Project, Int?) -> Unit,
    onConfigureBuild: (Project, Int?) -> Unit,
    onDeleteRun: (Project, Int) -> Unit,
    onDeleteBuild: (Project, Int) -> Unit,
) {
    var segment by rememberSaveable(project.id) { mutableStateOf(Segment.Run) }
    val saved = remember(project.id, runConfigVersion) { ProjectRunner.loadProjectConfigs(project) }
    val runs = remember(project.id, runConfigVersion) { ProjectRunner.effectiveRuns(project) }
    val builds = remember(project.id, runConfigVersion) { ProjectRunner.effectiveBuilds(project) }
    // Detected (unsaved) configs have nothing to delete — only saved lists show a Delete action.
    val runsDeletable = saved.runs.isNotEmpty()
    val buildsDeletable = saved.builds.isNotEmpty()
    val debugActive = debugUi.state != DebugState.DISCONNECTED && debugUi.state != DebugState.TERMINATED

    SegmentedToggle(segment, onSelect = { segment = it })

    when (segment) {
        Segment.Run -> {
            if (runs.isEmpty()) HintText("No run config yet — add one.")
            runs.forEachIndexed { index, config ->
                val running = isRunning && (runningRunName == null || runningRunName == config.name)
                RunConfigRow(
                    config = config,
                    running = running,
                    runInProgress = runInProgress && running,
                    runUrl = if (running) runUrl else null,
                    deletable = runsDeletable,
                    onRun = { onRun(project, config) },
                    onDebug = { onDebug(project, config) },
                    onStop = onStop,
                    onOpenInBrowser = onOpenInBrowser,
                    onConfigure = { onConfigureRun(project, index) },
                    onDelete = { onDeleteRun(project, index) },
                    projectKey = project.id.toString(),
                )
            }
            AddRow("Add run config", onClick = { onConfigureRun(project, null) })
            if (debugActive) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f), shape = RoundedCornerShape(8.dp)) {
                    DebugSessionPanel(ui = debugUi, modifier = Modifier.padding(8.dp))
                }
            }
        }
        Segment.Build -> {
            if (builds.isEmpty()) HintText("No build task yet — add one (e.g. dotnet publish).")
            builds.forEachIndexed { index, config ->
                BuildConfigRow(
                    config = config,
                    deletable = buildsDeletable,
                    onBuild = { onBuild(project, config) },
                    onConfigure = { onConfigureBuild(project, index) },
                    onDelete = { onDeleteBuild(project, index) },
                )
            }
            AddRow("Add build task", onClick = { onConfigureBuild(project, null) })
        }
    }
}

@Composable
private fun SegmentedToggle(selected: Segment, onSelect: (Segment) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.15f))
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Segment.entries.forEach { seg ->
            val active = seg == selected
            Text(
                text = seg.name,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.20f) else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(seg) }
                    .padding(vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun ProjectPickRow(project: Project, running: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick)
                .padding(start = 12.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = project.name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (running) RunStatusChip("Running", active = true)
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun RunConfigRow(
    config: RunConfig,
    running: Boolean,
    runInProgress: Boolean,
    runUrl: String?,
    deletable: Boolean,
    onRun: () -> Unit,
    onDebug: () -> Unit,
    onStop: () -> Unit,
    onOpenInBrowser: () -> Unit,
    onConfigure: () -> Unit,
    onDelete: () -> Unit,
    projectKey: String,
) {
    val subline = if (config.readyPort > 0) ":${config.readyPort}" else "${config.terminals.size} terminal(s)"
    val status = when {
        running && runInProgress -> "Building…"
        running -> "Running"
        else -> "Idle"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Column {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(config.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        RunStatusChip(status, active = running)
                    }
                    Text(subline, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                if (running && runUrl != null) {
                    IconAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", MaterialTheme.colorScheme.onSurfaceVariant, onOpenInBrowser)
                }
                if (running) {
                    IconAction(jcIcon(JCodeIcon.Stop), "Stop", MaterialTheme.colorScheme.error, onStop)
                } else {
                    IconAction(jcIcon(JCodeIcon.Run), "Run", MaterialTheme.colorScheme.primary, onRun, enabled = config.terminals.isNotEmpty())
                    IconAction(jcIcon(JCodeIcon.Debug), "Debug", MaterialTheme.colorScheme.primary, onDebug, enabled = config.debugEntry.isNotBlank())
                }
                IconAction(jcIcon(JCodeIcon.Settings), "Configure", MaterialTheme.colorScheme.onSurfaceVariant, onConfigure, size = 18)
                if (!running && deletable) IconAction(Icons.Rounded.DeleteOutline, "Delete", MaterialTheme.colorScheme.onSurfaceVariant, onDelete, size = 18)
            }
            if (config.readyPort > 0) WebPreviewSelector(projectKey = projectKey)
        }
    }
}

@Composable
private fun BuildConfigRow(config: BuildConfig, deletable: Boolean, onBuild: () -> Unit, onConfigure: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, bottom = 4.dp, end = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(config.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconAction(jcIcon(JCodeIcon.Run), "Build", MaterialTheme.colorScheme.primary, onBuild, enabled = config.command.isNotBlank())
            IconAction(jcIcon(JCodeIcon.Settings), "Configure", MaterialTheme.colorScheme.onSurfaceVariant, onConfigure, size = 18)
            if (deletable) IconAction(Icons.Rounded.DeleteOutline, "Delete", MaterialTheme.colorScheme.onSurfaceVariant, onDelete, size = 18)
        }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 8.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun IconAction(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: androidx.compose.ui.graphics.Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Int = 22,
) {
    JcTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = if (enabled) tint else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(size.dp),
            )
        }
    }
}

@Composable
private fun HintText(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Per-project "Open web previews in" override; INHERIT falls back to the Settings global default. */
@Composable
private fun WebPreviewSelector(projectKey: String) {
    val webPreview = LocalWebPreviewBrowsers.current
    var open by remember { mutableStateOf(false) }
    val raw = webPreview.projectChoice(projectKey)
    val shown = if (raw == WebPreviewBrowsers.INHERIT) "Default (${webPreview.label(webPreview.globalChoice)})" else webPreview.label(raw)
    val options = buildList {
        add(WebPreviewBrowsers.INHERIT); add(WebPreviewBrowsers.SYSTEM); add(WebPreviewBrowsers.ASK); add(WebPreviewBrowsers.BUILTIN)
        webPreview.available.forEach { add(it.packageName) }
    }
    Box {
        Row(
            modifier = Modifier.fillMaxWidth().clickable { open = true }.padding(start = 10.dp, end = 8.dp, top = 2.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text("Preview in: $shown", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { choice ->
                DropdownMenuItem(
                    text = { Text(webPreview.label(choice)) },
                    leadingIcon = { if (choice == raw) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = { webPreview.onSetProject(projectKey, choice); open = false },
                )
            }
        }
    }
}

@Composable
private fun RunStatusChip(text: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(6.dp),
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

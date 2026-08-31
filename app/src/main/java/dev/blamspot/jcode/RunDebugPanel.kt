package dev.blamspot.jcode
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JcTooltip
import dev.blamspot.jcode.design.ManagerFilterChip
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.PanelHeader
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.blamspot.jcode.core.config.BuildConfig
import dev.blamspot.jcode.core.config.RunConfig
import dev.blamspot.jcode.design.AndroidRunTarget
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.JCodeDialogDefaults
import dev.blamspot.jcode.design.LocalAndroidDevice
import dev.blamspot.jcode.design.LocalAndroidRunTargets
import dev.blamspot.jcode.fs.Project
import dev.blamspot.jcode.run.ProjectRunner
import dev.blamspot.jcode.vdevice.VirtualDeviceBridge
import dev.blamspot.jcode.workbench.DebugSessionUi
import dev.blamspot.jcode.workbench.LocalRunConfigPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The "Run" side-panel. In a User Workspace it first lists projects; tapping one opens a Build | Run
 * segmented detail. In the Default Workspace it goes straight to the open project's detail. The Run
 * segment lists run configs (each with Run ▷ / Debug 🐞 / Configure), the device those launch on, and
 * the live debug session; the Build segment lists build tasks (each with Build ▷ / Configure).
 * Multiple configs of each kind are supported. Execution is orchestrated by the workbench shell via
 * the callbacks.
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
    onAddRunPresets: (Project, List<RunConfig>) -> Unit,
    onAddBuildPreset: (Project, BuildConfig) -> Unit,
    onDeleteRun: (Project, Int) -> Unit,
    onDeleteBuild: (Project, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // In a User Workspace, remember which project's detail is open; Default Workspace uses its one project.
    var pickedId by rememberSaveable { mutableStateOf<Long?>(null) }
    val activeProject = if (inUserWorkspace) projects.firstOrNull { it.id == pickedId } else projects.firstOrNull()
    val targets = LocalAndroidRunTargets.current
    // Devices come and go while the panel is closed (a phone unpaired, the virtual device toggled), so
    // re-read `adb devices` whenever it opens rather than trusting whatever the last look found.
    LaunchedEffect(Unit) { targets.onRefresh() }

    Column(modifier = modifier.fillMaxSize()) {
        // Header outside the scroll and padded on its own, with the rule spanning the drawer — the
        // shape every panel here uses, so moving between them does not move the title.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = PanelHeader.minHeight)
                .padding(
                    horizontal = PanelHeader.horizontalPadding,
                    vertical = PanelHeader.verticalPadding,
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            if (inUserWorkspace && activeProject != null) {
                IconButton(onClick = { pickedId = null }, modifier = Modifier.size(PanelHeader.iconButton)) {
                    Icon(
                        Icons.AutoMirrored.Rounded.ArrowBack,
                        contentDescription = "Back to projects",
                        modifier = Modifier.size(PanelHeader.icon),
                    )
                }
            }
            // No leading icon: the drawer's own "Run" tab chip already carries one directly above.
            Text(
                text = activeProject?.name ?: "Run",
                style = PanelHeader.titleStyle,
                fontWeight = PanelHeader.titleWeight,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(
            thickness = PanelHeader.rule,
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(Space.ms),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
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
                onAddRunPresets = onAddRunPresets,
                onAddBuildPreset = onAddBuildPreset,
                onDeleteRun = onDeleteRun,
                onDeleteBuild = onDeleteBuild,
            )
        }
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
    onAddRunPresets: (Project, List<RunConfig>) -> Unit,
    onAddBuildPreset: (Project, BuildConfig) -> Unit,
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
    var showAddRun by remember { mutableStateOf(false) }
    var showAddBuild by remember { mutableStateOf(false) }
    val runPresets = LocalRunConfigPresets.current

    SegmentedToggle(segment, onSelect = { segment = it })

    when (segment) {
        Segment.Run -> {
            // Two different ways to get an app onto a device, so a row each, and a project can have
            // both: the container recipe only builds and is handed to JCode's own sandbox afterwards,
            // while every other Android recipe shells out to adb and takes its device from the target
            // row. Each row appears only when a config of its kind is present.
            // ...and only when a pack that provides the device is actually installed: the recipe is
            // in the project either way, but there is nothing to open it in without one.
            if (VirtualDeviceBridge.isAvailable &&
                runs.any { run -> run.terminals.any { it.command.contains(ProjectRunner.VDEVICE_MARKER) } }
            ) {
                VirtualDeviceRow()
            }
            if (runs.any { run -> run.terminals.any { it.command.contains("adb ") } }) {
                AndroidTargetRow(project)
            }
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
                )
            }
            AddRow("Add run config", onClick = { showAddRun = true })
            // While a session runs this is the debugger — steps, stack, variables — and nothing else
            // shows it. Idle it is a launch row for the active file, which every run config's own
            // Debug button already falls through to when its command names no source: two buttons,
            // one target, both saying "open a source file" when there is none. So idle it appears
            // only where nothing else can start a session. Not gated on the session alone: that was
            // tried, and it left you needing a session to reach the button that starts one.
            if (debugUi.active || runs.isEmpty()) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.10f), shape = RoundedCornerShape(Radius.lg)) {
                    DebugSessionPanel(ui = debugUi, modifier = Modifier.padding(Space.sm))
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
            AddRow("Add build task", onClick = { showAddBuild = true })
        }
    }

    if (showAddRun) {
        AddConfigDialog(
            title = "Add run config",
            groupHint = "Pick a framework, then a project file.",
            entryHint = "Pick a project file — every run config it offers is added.",
            emptyHint = "No run trigger detected — start from a blank config.",
            load = {
                withContext(Dispatchers.IO) { ProjectRunner.suggestRunTriggers(project, runPresets) }
                    .groupBy { it.kind }
                    .map { (kind, triggers) ->
                        PickerGroup(
                            name = kind,
                            entries = triggers.map { trigger ->
                                PickerEntry(trigger.label, trigger.detail) {
                                    onAddRunPresets(project, trigger.configs)
                                    showAddRun = false
                                }
                            },
                        )
                    }
            },
            onCustom = { showAddRun = false; onConfigureRun(project, null) },
            onDismiss = { showAddRun = false },
        )
    }
    if (showAddBuild) {
        AddConfigDialog(
            title = "Add build task",
            groupHint = "Pick where the task comes from, then the task.",
            entryHint = "Pick a task to add it.",
            emptyHint = "No build trigger detected — start from a blank task.",
            load = {
                withContext(Dispatchers.IO) { ProjectRunner.suggestBuildChoices(project, runPresets) }
                    .groupBy { it.source }
                    .map { (source, choices) ->
                        PickerGroup(
                            name = source,
                            entries = choices.map { choice ->
                                PickerEntry(choice.config.name, ProjectRunner.commandPreview(choice.config.command, max = 48)) {
                                    onAddBuildPreset(project, choice.config)
                                    showAddBuild = false
                                }
                            },
                        )
                    }
            },
            onCustom = { showAddBuild = false; onConfigureBuild(project, null) },
            onDismiss = { showAddBuild = false },
        )
    }
}

/** One thing the [AddConfigDialog] can add: what it is, where it came from, and the tap that adds it. */
private class PickerEntry(val label: String, val detail: String, val onPick: () -> Unit)

/** [PickerEntry]s that share an origin — a framework ("Android", "Node") for run configs, or the
 *  extension that contributed them for build tasks. */
private class PickerGroup(val name: String, val entries: List<PickerEntry>)

/**
 * The Add picker for both segments: [load] scans the project on IO and returns its offerings grouped
 * by origin. Two levels — groups, then that group's entries — collapsing to one when everything came
 * from the same place, since a list of one group is a tap that tells the user nothing. "Custom
 * (blank)" opens the editor on an empty config instead.
 */
@Composable
private fun AddConfigDialog(
    title: String,
    groupHint: String,
    entryHint: String,
    emptyHint: String,
    load: suspend () -> List<PickerGroup>,
    onCustom: () -> Unit,
    onDismiss: () -> Unit,
) {
    var groups by remember { mutableStateOf<List<PickerGroup>?>(null) }
    var openGroup by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { groups = load() }
    // Cap the scrollable list to ~half the viewport so the header + buttons stay on-screen.
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).coerceIn(160f, 360f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(JCodeDialogDefaults.width()),
            shape = RoundedCornerShape(Radius.xxxl),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                val list = groups
                val single = list?.singleOrNull()
                val shown = single ?: list?.firstOrNull { it.name == openGroup }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                    // A collapsed single group has nowhere to go back to, so it keeps the plain title.
                    if (shown != null && single == null) {
                        IconButton(onClick = { openGroup = null }, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(IconSize.lg))
                        }
                    }
                    Text(
                        text = if (single == null) shown?.name ?: title else title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                when {
                    list == null -> HintText("Scanning project…")
                    list.isEmpty() -> {
                        HintText(emptyHint)
                        ChoiceRow("Custom (blank)", "Start from an empty config", onClick = onCustom)
                    }
                    shown != null -> {
                        HintText(entryHint)
                        PickerList(listMaxHeight) {
                            shown.entries.forEach { ChoiceRow(it.label, it.detail, onClick = it.onPick) }
                            if (single != null) ChoiceRow("Custom (blank)", "Start from an empty config", onClick = onCustom)
                        }
                    }
                    else -> {
                        HintText(groupHint)
                        PickerList(listMaxHeight) {
                            list.forEach { group ->
                                ChoiceRow(
                                    label = group.name,
                                    subtitle = "${group.entries.size} available",
                                    onClick = { openGroup = group.name },
                                    trailing = Icons.AutoMirrored.Rounded.KeyboardArrowRight,
                                )
                            }
                            ChoiceRow("Custom (blank)", "Start from an empty config", onClick = onCustom)
                        }
                    }
                }
                CompactOutlinedButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun PickerList(maxHeight: androidx.compose.ui.unit.Dp, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier.heightIn(max = maxHeight).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(Space.s),
    ) { content() }
}

@Composable
private fun ChoiceRow(
    label: String,
    subtitle: String,
    onClick: () -> Unit,
    trailing: ImageVector = Icons.Rounded.Add,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(Radius.xl)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).clickable(onClick = onClick)
                .padding(horizontal = Space.ms, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hairline)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (subtitle.isNotBlank()) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Icon(trailing, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.md))
        }
    }
}

@Composable
private fun SegmentedToggle(selected: Segment, onSelect: (Segment) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
        Segment.entries.forEach { seg ->
            ManagerFilterChip(selected = seg == selected, label = seg.name) { onSelect(seg) }
        }
    }
}

@Composable
private fun ProjectPickRow(project: Project, running: Boolean, onClick: () -> Unit) {
    PanelRow(onClick = onClick) {
        Text(
            text = project.name,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (running) RunStatusChip("Running", active = true)
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.lg))
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
) {
    val subline = if (config.readyPort > 0) {
        ":${config.readyPort}"
    } else {
        config.terminals.firstOrNull()?.command?.let { ProjectRunner.commandPreview(it, max = 32) }.orEmpty()
    }
    val status = when {
        running && runInProgress -> "Building…"
        running -> "Running"
        else -> "Idle"
    }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(Radius.lg)) {
        Column {
            // Row 1: full-width name + compact action icons (the status chip moves to row 2 so the
            // name gets the whole width and stops truncating).
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Space.ms, top = Space.xxs, end = Space.xxs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.hairline),
            ) {
                Text(config.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (running && runUrl != null) {
                    IconAction(Icons.AutoMirrored.Rounded.OpenInNew, "Open in browser", MaterialTheme.colorScheme.onSurfaceVariant, onOpenInBrowser)
                }
                if (running) {
                    IconAction(jcIcon(JCodeIcon.Stop), "Stop", MaterialTheme.colorScheme.error, onStop)
                } else {
                    IconAction(jcIcon(JCodeIcon.Run), "Run", MaterialTheme.colorScheme.primary, onRun, enabled = config.terminals.any { it.command.isNotBlank() })
                    // Launch under the debugger (VS-style): set gutter breakpoints, tap Debug, pause on hit.
                    // The entry is auto-derived from the command / active file — no manual field to fill in.
                    IconAction(jcIcon(JCodeIcon.Debug), "Debug", MaterialTheme.colorScheme.tertiary, onDebug)
                }
                IconAction(jcIcon(JCodeIcon.Settings), "Configure", MaterialTheme.colorScheme.onSurfaceVariant, onConfigure, size = 17)
                if (!running && deletable) IconAction(Icons.Rounded.DeleteOutline, "Delete", MaterialTheme.colorScheme.onSurfaceVariant, onDelete, size = 17)
            }
            // Row 2: thin status + port line.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Space.ms, end = Space.ms, bottom = Space.s),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
                RunStatusChip(status, active = running)
                Text(subline, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
        }
    }
}

@Composable
private fun BuildConfigRow(config: BuildConfig, deletable: Boolean, onBuild: () -> Unit, onConfigure: () -> Unit, onDelete: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(Radius.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = Space.ms, top = Space.xxs, bottom = Space.xxs, end = Space.xxs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.hairline),
        ) {
            Text(config.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconAction(jcIcon(JCodeIcon.Run), "Build", MaterialTheme.colorScheme.primary, onBuild, enabled = config.command.isNotBlank())
            IconAction(jcIcon(JCodeIcon.Settings), "Configure", MaterialTheme.colorScheme.onSurfaceVariant, onConfigure, size = 17)
            if (deletable) IconAction(Icons.Rounded.DeleteOutline, "Delete", MaterialTheme.colorScheme.onSurfaceVariant, onDelete, size = 17)
        }
    }
}

/**
 * Which device this project's adb-driven runs launch on, and the picker that changes it.
 *
 * The runtime's adb server is the only source of devices: JCode's own virtual device and this phone
 * both reach a run by being connected to it, so anything adb does not list cannot be launched on. With
 * nothing listed the row becomes the way into the pairing page instead.
 */
@Composable
private fun AndroidTargetRow(project: Project) {
    val targets = LocalAndroidRunTargets.current
    val device = LocalAndroidDevice.current
    val key = project.id.toString()
    val current = targets.effective(key)
    var showPicker by remember { mutableStateOf(false) }

    PanelRow(onClick = { if (current == null) device.onOpenPage() else showPicker = true }) {
        Icon(
            imageVector = if (current?.isVirtual == true) Icons.Rounded.Smartphone else Icons.Rounded.PhoneAndroid,
            contentDescription = null,
            tint = if (current?.isOnline == true) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(IconSize.md),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hairline)) {
            Text(
                text = current?.label ?: "No device",
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = when {
                    current == null && targets.loading -> "Looking for devices…"
                    current == null -> device.status
                    else -> current.serial
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        when {
            current == null -> Text("Set up", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            // Only say how many when there is in fact a choice to make.
            targets.available.size > 1 -> RunStatusChip("${targets.available.size} devices", active = current.isOnline)
            else -> RunStatusChip(current.state, active = current.isOnline)
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(IconSize.md))
    }

    if (showPicker) {
        AndroidTargetDialog(
            targets = targets.available,
            selected = current,
            loading = targets.loading,
            onRefresh = targets.onRefresh,
            onPick = { serial -> targets.onSetProject(key, serial); showPicker = false },
            onDismiss = { showPicker = false },
        )
    }
}

@Composable
private fun AndroidTargetDialog(
    targets: List<AndroidRunTarget>,
    selected: AndroidRunTarget?,
    loading: Boolean,
    onRefresh: () -> Unit,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).coerceIn(160f, 360f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier.width(JCodeDialogDefaults.width()),
            shape = RoundedCornerShape(Radius.xxxl),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(Space.lg), verticalArrangement = Arrangement.spacedBy(Space.ms)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Run on",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f),
                    )
                    IconAction(Icons.Rounded.Refresh, "Refresh devices", MaterialTheme.colorScheme.onSurfaceVariant, onRefresh, enabled = !loading, size = 17)
                }
                if (targets.isEmpty()) {
                    HintText(if (loading) "Looking for devices…" else "The runtime's adb server lists no device.")
                } else {
                    HintText("This project's runs and debugs go to the device picked here.")
                    PickerList(listMaxHeight) {
                        targets.forEach { target ->
                            TargetChoiceRow(target, chosen = target.serial == selected?.serial, onClick = { onPick(target.serial) })
                        }
                    }
                }
                CompactOutlinedButton(text = "Cancel", onClick = onDismiss, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TargetChoiceRow(target: AndroidRunTarget, chosen: Boolean, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(Radius.xl)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.xl)).clickable(onClick = onClick)
                .padding(horizontal = Space.ms, vertical = Space.sm),
            horizontalArrangement = Arrangement.spacedBy(Space.ms),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (target.isVirtual) Icons.Rounded.Smartphone else Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = if (target.isOnline) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.md),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hairline)) {
                Text(target.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    // adb's own word for the state, so an offline or unauthorized device says so in the
                    // vocabulary the terminal would have used.
                    text = if (target.isOnline) target.serial else "${target.state} · ${target.serial}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (chosen) {
                Icon(Icons.Rounded.Check, contentDescription = "Selected", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.md))
            }
        }
    }
}

/** Opens the device sandbox tab, which is otherwise only reached when a virtual-device build finishes.
 *  Named after the tab rather than the device, since the target row above it can be showing the very
 *  same virtual device as an adb target and two rows reading "Virtual device" say nothing apart. */
@Composable
private fun VirtualDeviceRow() {
    PanelRow(onClick = { VirtualDeviceBridge.requestOpen(null) }) {
        Icon(
            Icons.Rounded.Smartphone,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.hairline)) {
            Text("Device sandbox", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1)
            Text(
                text = "Run a built APK in a tab — no install, no ADB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text("Open", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
    }
}

/** The panel's standard tappable row: same surface, radius and density as the manager list rows. */
@Composable
private fun PanelRow(onClick: () -> Unit, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f), shape = RoundedCornerShape(Radius.lg)) {
        Row(
            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).clickable(onClick = onClick)
                .padding(horizontal = Space.ms, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) { content() }
    }
}

@Composable
private fun AddRow(label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.lg)).clickable(onClick = onClick).padding(vertical = Space.s, horizontal = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(Icons.Rounded.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(IconSize.md))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun IconAction(
    icon: ImageVector,
    label: String,
    tint: Color,
    onClick: () -> Unit,
    enabled: Boolean = true,
    size: Int = 19,
) {
    JcTooltip(label) {
        IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size((size + 13).dp)) {
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

@Composable
private fun RunStatusChip(text: String, active: Boolean) {
    Surface(
        color = if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surface.copy(alpha = 0.6f),
        shape = RoundedCornerShape(Radius.md),
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = Space.sm, vertical = Space.xs),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}

package dev.blamspot.jcode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import dev.blamspot.jcode.MainViewModel
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.core.debug.DebugState
import dev.blamspot.jcode.design.HeaderActionButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.LocalCommandPaletteLauncher
import dev.blamspot.jcode.design.LocalEditorSaveActions
import dev.blamspot.jcode.design.LocalHeaderActionSetting
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.feature.editor.pane.EditorTab
import dev.blamspot.jcode.fs.Project
import dev.blamspot.jcode.fs.Workspace

/**
 * The workbench header bars peeled out of JCodeShell: the left-panel [WorkspaceHeader] (workspace
 * title + tool row) and the editor-area [WorkbenchTopBar] (nav + title + Save/Run/Terminal quick
 * actions). Stateless / param-driven; the buttons they use live in WorkbenchChrome.kt.
 */

private fun contributedActionIcon(id: String): JCodeIcon = when (id) {
    "clone" -> JCodeIcon.Scm
    "remoteRepo" -> JCodeIcon.Browser
    else -> JCodeIcon.Code
}

@Composable
internal fun WorkspaceHeader(
    selectedTool: WorkbenchTool,
    workspace: Workspace?,
    selectedProject: Project?,
    inUserWorkspace: Boolean,
    dbManagerAvailable: Boolean,
    scmAvailable: Boolean,
    vmManagerAvailable: Boolean,
    onSelectTool: (WorkbenchTool) -> Unit,
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
    contributedDrawerActions: List<MainViewModel.ShellContribution>,
    onDrawerAction: (MainViewModel.ShellContribution) -> Unit,
    onCloseWorkspace: () -> Unit,
    onCloseProject: () -> Unit,
    onCollapseSidebar: (() -> Unit)? = null,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Space.ms, vertical = Space.xs),
        verticalArrangement = Arrangement.spacedBy(Space.xs),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // In the persistent (landscape) layout, the editor top-bar's sidebar toggle sits under
            // this panel, so surface a collapse control in the panel header itself.
            onCollapseSidebar?.let { collapse ->
                WorkbenchIconActionButton(
                    icon = jcIcon(JCodeIcon.MenuToggle),
                    contentDescription = "Hide left sidebar",
                    onClick = collapse,
                    active = true,
                )
            }
            // The title doubles as a menu anchor: a User Workspace can be closed (Close workspace),
            // and the Default Workspace can close its open project (Close project). With nothing open
            // in the Default Workspace there is nothing to close, so it stays a plain label.
            val canCloseProject = !inUserWorkspace && selectedProject != null
            val hasCloseAction = inUserWorkspace || canCloseProject
            Box(modifier = Modifier.weight(1f)) {
                var menuExpanded by remember { mutableStateOf(false) }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.lg))
                        .then(
                            if (hasCloseAction) Modifier.clickable { menuExpanded = true } else Modifier,
                        )
                        .padding(vertical = Space.xxs),
                    verticalArrangement = Arrangement.spacedBy(Space.hairline),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xxs),
                    ) {
                        Text(
                            text = workspace?.name ?: "Default Workspace",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (hasCloseAction) {
                            Icon(
                                imageVector = jcIcon(JCodeIcon.DropDown),
                                contentDescription = null,
                                modifier = Modifier.size(IconSize.sm),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(
                        text = selectedProject?.distroBindTarget ?: "No bind target yet",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                CompactContextMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                    listActions = listOf(
                        ContextAction(
                            JCodeIcon.Close,
                            if (inUserWorkspace) "Close workspace" else "Close project",
                        ) { if (inUserWorkspace) onCloseWorkspace() else onCloseProject() },
                    ),
                )
            }

            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Add),
                contentDescription = "Add project",
                onClick = onCreateProject,
            )
            Box {
                var openFolderMenu by remember { mutableStateOf(false) }
                WorkbenchIconActionButton(
                    icon = jcIcon(JCodeIcon.OpenFolder),
                    contentDescription = "Open folder",
                    onClick = { if (contributedDrawerActions.isEmpty()) onOpenExternalFolder() else openFolderMenu = true },
                )
                if (contributedDrawerActions.isNotEmpty()) {
                    CompactContextMenu(
                        expanded = openFolderMenu,
                        onDismissRequest = { openFolderMenu = false },
                        listActions = buildList {
                            add(ContextAction(JCodeIcon.OpenFolder, "Open Folder") { onOpenExternalFolder() })
                            contributedDrawerActions.forEach { a ->
                                add(ContextAction(contributedActionIcon(a.id), a.label) { onDrawerAction(a) })
                            }
                        },
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            // "DB Managers", "SCM" and "VM" only show once a matching client extension is installed.
            WorkbenchTool.entries
                .filter {
                    it.available &&
                        (it != WorkbenchTool.DbManager || dbManagerAvailable) &&
                        (it != WorkbenchTool.Scm || scmAvailable) &&
                        (it != WorkbenchTool.VmManager || vmManagerAvailable)
                }
                .forEach { tool ->
                    SidebarToolButton(
                        tool = tool,
                        selected = selectedTool == tool,
                        onClick = { onSelectTool(tool) },
                    )
                }
        }
    }
}

@Composable
internal fun WorkbenchTopBar(
    workspace: Workspace?,
    selectedProject: Project?,
    activeTab: EditorTab?,
    leftSidebarExpanded: Boolean,
    canShowRightSidebar: Boolean,
    rightSidebarVisible: Boolean,
    rightSidebarDocked: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onShowTerminal: () -> Unit,
    onRun: () -> Unit,
    onStop: () -> Unit,
    onRerun: () -> Unit,
    runConfigNames: List<String>,
    onRunConfig: (Int) -> Unit,
    isRunning: Boolean,
    terminalBusy: Boolean,
    terminalHasUnseen: Boolean,
    terminalSessions: List<TerminalInstance>,
    onOpenTerminalSession: (String) -> Unit,
    onSave: () -> Unit,
) {
    // Single row: navigation + title + quick actions. Per-file metrics (cursor, language, distro)
    // live in the bottom status bar, so this header no longer carries a redundant second chip row.
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.ms, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.MenuToggle),
                contentDescription = if (leftSidebarExpanded) "Hide left sidebar" else "Show left sidebar",
                onClick = onToggleLeftSidebar,
                active = leftSidebarExpanded,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.hairline),
            ) {
                val heading = activeTab?.title ?: selectedProject?.name ?: "JCode"
                Text(
                    text = heading,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    // Where the thing above *is*, which means everything up to it and not it again.
                    // The trail ended with the same name the heading was already showing — "Device
                    // sandbox" over "Default Workspace / waverepo / Device sandbox" — so the line
                    // with the least room on it spent its last third repeating the line above.
                    //
                    // Only the last one is dropped, and only when it matches. A project that happens
                    // to share its name with the tab open in it is still a real step on the way
                    // there, and taking it out would leave a path that goes somewhere else.
                    text = listOfNotNull(
                        workspace?.name,
                        selectedProject?.name,
                        activeTab?.title,
                    ).let { if (it.lastOrNull() == heading) it.dropLast(1) else it }
                        .joinToString(" / ")
                        .ifBlank { "Editor workspace" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            if (activeTab?.editorState != null) {
                val saveActions = LocalEditorSaveActions.current
                var saveMenuOpen by remember { mutableStateOf(false) }
                Box {
                    WorkbenchIconActionButton(
                        icon = jcIcon(JCodeIcon.Save),
                        contentDescription = if (activeTab.isDirty) "Save (unsaved changes)" else "Save",
                        onClick = onSave,
                        active = activeTab.isDirty,
                        onLongClick = { saveMenuOpen = true },
                    )
                    CompactContextMenu(
                        expanded = saveMenuOpen,
                        onDismissRequest = { saveMenuOpen = false },
                        quickActions = listOf(
                            ContextAction(JCodeIcon.Undo, "Undo") { saveActions.onUndo() },
                            ContextAction(JCodeIcon.Redo, "Redo") { saveActions.onRedo() },
                            ContextAction(JCodeIcon.Discard, "Discard", destructive = true) { saveActions.onDiscard() },
                            ContextAction(JCodeIcon.Save, "Save all") { saveActions.onSaveAll() },
                        ),
                    )
                }
            }
            // Run toggles to Stop while a run is active. A tap runs the single/first config; with more
            // than one config it opens a picker instead. Long-press always opens the menu — the list of
            // run configs to launch, plus the debug/session controls.
            // Only shown when a project is open and focused — there's nothing to run otherwise.
            if (selectedProject != null) {
                var runMenuOpen by remember { mutableStateOf(false) }
                val hasMultipleRuns = runConfigNames.size > 1
                // A debug session owns this button while it lasts, but it stays a three-state control:
                // Run to start, Continue at a breakpoint, Stop while anything is live. Pause is
                // deliberately not here — it belongs with the stepping controls in this button's
                // long-press menu and the Run panel, and putting it in the header meant the one
                // obvious control could not end a session. Without the Continue state the header kept
                // offering "Run" at a breakpoint, the one thing you cannot do from there.
                val debug = LocalDebugSession.current
                val paused = debug.state == DebugState.STOPPED
                val debugging = paused || debug.state == DebugState.RUNNING
                Box {
                    WorkbenchIconActionButton(
                        icon = when {
                            paused -> jcIcon(JCodeIcon.Continue)
                            debugging || isRunning -> jcIcon(JCodeIcon.Stop)
                            else -> jcIcon(JCodeIcon.Run)
                        },
                        contentDescription = when {
                            paused -> "Continue"
                            debugging -> "Stop debugging"
                            isRunning -> "Stop"
                            else -> "Run"
                        },
                        onClick = {
                            when {
                                paused -> debug.onContinue()
                                debugging -> debug.onStop()
                                isRunning -> onStop()
                                hasMultipleRuns -> runMenuOpen = true
                                else -> onRun()
                            }
                        },
                        active = isRunning || debugging,
                        onLongClick = { runMenuOpen = true },
                    )
                    CompactContextMenu(
                        expanded = runMenuOpen,
                        onDismissRequest = { runMenuOpen = false },
                        quickActions = listOf(
                            // The session controls, live only while it is paused — stepping a running
                            // debuggee is not a thing, and these were dead placeholders before the
                            // debugger existed.
                            ContextAction(JCodeIcon.Continue, "Continue", enabled = paused) { debug.onContinue() },
                            ContextAction(JCodeIcon.Pause, "Pause", enabled = debug.state == DebugState.RUNNING) {
                                debug.onPause()
                            },
                            ContextAction(JCodeIcon.StepOver, "Step Over", enabled = paused) { debug.onStepOver() },
                            ContextAction(JCodeIcon.StepInto, "Step Into", enabled = paused) { debug.onStepInto() },
                            ContextAction(JCodeIcon.StepOut, "Step Out", enabled = paused) { debug.onStepOut() },
                            if (debugging) {
                                ContextAction(JCodeIcon.Stop, "Stop debugging") { debug.onStop() }
                            } else {
                                ContextAction(JCodeIcon.Rerun, "Rerun") { onRerun() }
                            },
                        ),
                        // Each run config as its own row — tap to launch that one.
                        listActions = runConfigNames.mapIndexed { index, name ->
                            ContextAction(JCodeIcon.Run, name) { onRunConfig(index) }
                        },
                    )
                }
            }
            // What this slot does is a setting (Settings → Appearance → Header): the terminal it has
            // always been, the Command Palette for anyone who reaches for that instead, or nothing
            // at all. The terminal stays reachable from the right drawer either way.
            when (LocalHeaderActionSetting.current.button) {
                HeaderActionButton.Terminal -> {
                    // Shimmers while any session is busy; a dot badge flags new background instances;
                    // long-press lists the live instances and opens the right drawer on the chosen one.
                    var terminalMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        WorkbenchIconActionButton(
                            icon = jcIcon(JCodeIcon.Terminal),
                            contentDescription = "Terminal",
                            onClick = onShowTerminal,
                            shimmer = terminalBusy,
                            badge = terminalHasUnseen,
                            onLongClick = { terminalMenuOpen = true },
                        )
                        CompactContextMenu(
                            expanded = terminalMenuOpen && terminalSessions.isNotEmpty(),
                            onDismissRequest = { terminalMenuOpen = false },
                            listActions = terminalSessions.map { session ->
                                ContextAction(JCodeIcon.Terminal, session.label) { onOpenTerminalSession(session.id) }
                            },
                        )
                    }
                }

                HeaderActionButton.CommandPalette -> {
                    val launcher = LocalCommandPaletteLauncher.current
                    WorkbenchIconActionButton(
                        icon = jcIcon(JCodeIcon.CommandPalette),
                        contentDescription = "Command Palette",
                        onClick = launcher.onOpen,
                    )
                }

                HeaderActionButton.Hidden -> Unit
            }
            // A docked panel is already on screen beside the editor with its own Close button, so
            // this toggle would only duplicate it. It returns as soon as the panel is hidden, and
            // stays put when the panel overlays the workspace instead of splitting it.
            if (!(rightSidebarDocked && canShowRightSidebar && rightSidebarVisible)) {
                WorkbenchIconActionButton(
                    icon = jcIcon(JCodeIcon.Logs),
                    contentDescription = "Toggle right sidebar",
                    onClick = onToggleRightSidebar,
                    active = canShowRightSidebar && rightSidebarVisible,
                )
            }
        }
    }
}

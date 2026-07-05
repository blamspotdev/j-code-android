package dev.jcode.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.config.ProjectConfig
import dev.jcode.core.config.WorkspaceConfig
import dev.jcode.design.ExtensionSettingSpec
import dev.jcode.design.ExtensionSettingsUi
import dev.jcode.design.IconBundle
import dev.jcode.design.IconBundleRegistry
import dev.jcode.design.JCodeIcon
import dev.jcode.design.LocalEditorDragMovesCursor
import dev.jcode.design.LocalExtensionSettingsUi
import dev.jcode.design.LocalPerformanceSettings
import dev.jcode.design.LocalRestoreSession
import dev.jcode.design.LocalSourceControlSettings
import dev.jcode.design.WebPreviewBrowsers
import dev.jcode.design.LocalWebPreviewBrowsers
import dev.jcode.design.LocalTabCloseButtonSetting
import dev.jcode.design.ThemeBundleRegistry
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.design.ThemeMode

object SettingsFeature {

    @Composable
    fun Content(
        effectiveConfig: EffectiveConfig,
        workspaceConfig: WorkspaceConfig?,
        projectConfig: ProjectConfig?,
        workspaceError: String?,
        projectError: String?,
        projectOverridesAvailable: Boolean,
        environmentState: DistroEnvironmentState,
        onOpenWorkspaceConfig: () -> Unit,
        onOpenProjectConfig: () -> Unit,
        onOpenEnvironmentWizard: () -> Unit,
        onRefreshEnvironment: () -> Unit,
        onUpdateFontSize: (ConfigScope, Float) -> Unit,
        onUpdateTabSize: (ConfigScope, Int) -> Unit,
        onUpdateWordWrap: (ConfigScope, Boolean) -> Unit,
        onUpdateMinimap: (ConfigScope, Boolean) -> Unit,
        onUpdateLigatures: (ConfigScope, Boolean) -> Unit,
        onUpdateExplorerViewMode: (ConfigScope, String) -> Unit,
        themeMode: ThemeMode,
        onUpdateThemeMode: (ThemeMode) -> Unit,
        themeBundleId: String,
        onUpdateThemeBundle: (String) -> Unit,
        iconBundleId: String,
        onUpdateIconBundle: (String) -> Unit,
        formatterId: String,
        formatterOptions: List<Pair<String, String>>,
        onSelectFormatter: (String) -> Unit,
        terminalDoubleTapToFocus: Boolean,
        onUpdateTerminalDoubleTapToFocus: (Boolean) -> Unit,
        hideStatusBarWithKeyboard: Boolean,
        onUpdateHideStatusBarWithKeyboard: (Boolean) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val tabCloseSetting = LocalTabCloseButtonSetting.current
        val editorDragSetting = LocalEditorDragMovesCursor.current
        val restoreSessionSetting = LocalRestoreSession.current
        val perf = LocalPerformanceSettings.current
        val webPreview = LocalWebPreviewBrowsers.current
        var selectedScope by rememberSaveable(projectOverridesAvailable) {
            mutableStateOf(if (projectOverridesAvailable) ConfigScope.Project else ConfigScope.Workspace)
        }
        LaunchedEffect(projectOverridesAvailable) {
            if (!projectOverridesAvailable) {
                selectedScope = ConfigScope.Workspace
            }
        }

        val scopedEditor = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.editor
            ConfigScope.Project -> projectConfig?.editor
        }

        val fontSize = scopedEditor?.fontSize ?: effectiveConfig.editor.fontSize
        val tabSize = scopedEditor?.tabSize ?: effectiveConfig.editor.tabSize
        val wordWrap = scopedEditor?.wordWrap ?: effectiveConfig.editor.wordWrap
        val minimap = scopedEditor?.minimap ?: effectiveConfig.editor.minimap
        val ligatures = scopedEditor?.ligatures ?: effectiveConfig.editor.ligatures

        val scopedExplorer = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.explorer
            ConfigScope.Project -> projectConfig?.explorer
        }
        val explorerViewMode = scopedExplorer?.viewMode ?: effectiveConfig.explorer.viewMode

        var query by rememberSaveable { mutableStateOf("") }
        CompositionLocalProvider(LocalSettingsQuery provides query) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SettingsSearchField(query = query, onQueryChange = { query = it })
            SettingsSectionHeader("Appearance")
            SettingsCard(
                title = "Appearance",
                description = "System follows your device's light/dark setting.",
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ThemeMode.entries.forEachIndexed { index, mode ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ThemeMode.entries.size),
                            onClick = { onUpdateThemeMode(mode) },
                            selected = themeMode == mode,
                            label = { Text(mode.name) },
                        )
                    }
                }
            }

            SettingsCard(
                title = "Theme bundle",
                description = "Color palette applied across the app.",
            ) {
                val activeBundle = themeBundleId.ifEmpty { ThemeBundleRegistry.default.id }
                ThemeBundleRegistry.builtIns.forEach { bundle ->
                    BundleRow(
                        name = bundle.name,
                        description = bundle.description,
                        selected = activeBundle == bundle.id,
                        swatch = listOf(
                            bundle.dark.primary,
                            bundle.dark.secondary,
                            bundle.dark.tertiary,
                            bundle.dark.surface,
                        ),
                        onClick = { onUpdateThemeBundle(bundle.id) },
                    )
                }
            }

            SettingsCard(
                title = "Icon bundle",
                description = "Icon set used across the app.",
            ) {
                val activeIcons = iconBundleId.ifEmpty { IconBundleRegistry.default.id }
                IconBundleRegistry.builtIns.forEach { bundle ->
                    IconBundleRow(
                        bundle = bundle,
                        selected = activeIcons == bundle.id,
                        onClick = { onUpdateIconBundle(bundle.id) },
                    )
                }
            }

            SettingsCard(
                title = "Immersive keyboard",
                description = "Reclaim screen space while typing.",
                keywords = "status bar immersive fullscreen keyboard editor terminal",
            ) {
                ToggleRow(
                    label = "Hide status bar with keyboard",
                    supporting = "Hide the system status bar while the on-screen keyboard is open, for more room in the editor and terminal. Swipe down from the top to reveal it.",
                    checked = hideStatusBarWithKeyboard,
                    onCheckedChange = onUpdateHideStatusBarWithKeyboard,
                )
            }

            SettingsSectionHeader("Startup")
            SettingsCard(
                title = "Restore last session",
                description = "Pick up where you left off after closing the app.",
                keywords = "restore session reopen tabs workspace project unsaved recover startup launch",
            ) {
                ToggleRow(
                    label = "Restore last session on launch",
                    supporting = "Reopen the last workspace, project, and editor tabs — including unsaved changes — when J Code starts. Missing files are skipped.",
                    checked = restoreSessionSetting.enabled,
                    onCheckedChange = restoreSessionSetting.onChange,
                )
            }

            SettingsSectionHeader("Source Control")
            SettingsCard(
                title = "Git identity",
                description = "The author name and email recorded on your commits. Applies to all git in the " +
                    "runtime, and is also editable from the Source Control sign-in page.",
                keywords = "git source control scm identity name email commit author github sign in credentials",
            ) {
                val scm = LocalSourceControlSettings.current
                var name by rememberSaveable { mutableStateOf("") }
                var email by rememberSaveable { mutableStateOf("") }
                var loaded by rememberSaveable { mutableStateOf(false) }
                var saved by rememberSaveable { mutableStateOf(false) }
                LaunchedEffect(scm) {
                    if (!loaded) {
                        val (n, e) = scm.onLoad()
                        name = n; email = e; loaded = true
                    }
                }
                IdentityField(label = "Name", value = name, placeholder = "Your name") { name = it; saved = false }
                IdentityField(label = "Email", value = email, placeholder = "you@example.com") { email = it; saved = false }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    FilledTonalButton(
                        onClick = { scm.onSave(name.trim(), email.trim()); saved = true },
                        enabled = name.isNotBlank() && email.isNotBlank(),
                    ) { Text("Save identity") }
                    if (saved) {
                        Text("Saved", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            val extensionSettings = LocalExtensionSettingsUi.current
            if (extensionSettings.groups.isNotEmpty()) {
                SettingsSectionHeader("Extensions")
                extensionSettings.groups.forEach { group ->
                    SettingsCard(
                        title = group.extensionName,
                        description = "Preferences for the ${group.extensionName} extension.",
                        keywords = "extension settings " + group.extensionName + " " +
                            group.specs.joinToString(" ") { "${it.key} ${it.label}" },
                    ) {
                        group.specs.forEachIndexed { index, spec ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                            ExtensionSettingControl(group.extensionId, spec, extensionSettings)
                        }
                    }
                }
            }

            SettingsSectionHeader("Performance")
            SettingsCard(
                title = "Resource management",
                description = "Keep the Linux runtime lean by stopping work you're done with. Each terminal, " +
                    "run, and debug session holds a proot process tree in memory.",
                keywords = "performance memory cpu battery proot process terminal kill close idle background resource optimize",
            ) {
                ToggleRow(
                    label = "Warn before closing running processes",
                    supporting = "When closing a project or workspace with a running terminal command, an active " +
                        "Build & Run, or a live debug session, ask first before stopping them.",
                    checked = perf.confirmCloseRunning,
                    onCheckedChange = perf.onSetConfirmCloseRunning,
                )
                ToggleRow(
                    label = "Auto-close idle terminals",
                    supporting = "Automatically close terminals left idle at the prompt (no running program) to " +
                        "free their process tree and memory. Terminals running a command are never auto-closed.",
                    checked = perf.autoCloseIdleTerminals,
                    onCheckedChange = perf.onSetAutoCloseIdleTerminals,
                )
                if (perf.autoCloseIdleTerminals) {
                    StepperRow(
                        label = "Idle timeout",
                        value = "${perf.idleTimeoutMinutes} min",
                        onDecrease = { perf.onSetIdleTimeoutMinutes(perf.idleTimeoutMinutes - 5) },
                        onIncrease = { perf.onSetIdleTimeoutMinutes(perf.idleTimeoutMinutes + 5) },
                    )
                }
                StepperRow(
                    label = "Max terminal instances",
                    value = "${perf.maxTerminalSessions}",
                    onDecrease = { perf.onSetMaxTerminalSessions((perf.maxTerminalSessions - 1).coerceAtLeast(1)) },
                    onIncrease = { perf.onSetMaxTerminalSessions((perf.maxTerminalSessions + 1).coerceAtMost(24)) },
                )
            }

            SettingsSectionHeader("Web preview")
            SettingsCard(
                title = "Open web previews in",
                description = "The browser used when you open a running dev server (Build & Run) or tap a URL " +
                    "in the terminal. A project can override this in its Build & Run panel.",
                keywords = "browser web preview open url chrome firefox default run dev server",
            ) {
                val globalOptions = buildList {
                    add(WebPreviewBrowsers.SYSTEM)
                    add(WebPreviewBrowsers.ASK)
                    add(WebPreviewBrowsers.BUILTIN)
                    webPreview.available.forEach { add(it.packageName) }
                }
                globalOptions.forEach { choice ->
                    BundleRow(
                        name = webPreview.label(choice),
                        description = when (choice) {
                            WebPreviewBrowsers.SYSTEM -> "The device's default browser app"
                            WebPreviewBrowsers.ASK -> "Show the Android app chooser each time"
                            WebPreviewBrowsers.BUILTIN -> "J Code's own in-editor browser, with DevTools"
                            else -> choice
                        },
                        selected = webPreview.globalChoice == choice,
                        swatch = emptyList(),
                        onClick = { webPreview.onSetGlobal(choice) },
                    )
                }
            }

            SettingsSectionHeader("Environment")
            SettingsCard(
                title = "Environment",
                description = "Environment setup: proot, distro bootstrap, and the final smoke test. " +
                    "Install, switch between, or remove environments from the setup page.",
            ) {
                SummaryRow(
                    label = "proot",
                    value = if (environmentState.prootInstalled) "Ready" else "Not installed",
                )
                SummaryRow(
                    label = "Distro",
                    value = when (environmentState.distroInstalled) {
                        true -> environmentState.runtime.selectedDistro.label
                        false -> "Not installed"
                        null -> "Unknown"
                    },
                )
                SummaryRow(
                    label = "Toolchain",
                    value = when (environmentState.toolchainReady) {
                        true -> "Ready"
                        false -> "Not ready"
                        null -> "Unknown"
                    },
                )
                SummaryRow(
                    label = "Smoke test",
                    value = when (environmentState.smokeTestPassed) {
                        true -> "Passed"
                        false -> "Failed"
                        null -> "Not run"
                    },
                )
                SummaryRow(
                    "Primary bind",
                    environmentState.runtime.binds.firstOrNull()?.target ?: "/workspace",
                )
                environmentState.runningStep?.let { runningStep ->
                    Text(
                        text = "Running: ${runningStep.key}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                environmentState.activityLog.takeLast(3).forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onOpenEnvironmentWizard, modifier = Modifier.weight(1f)) {
                        Text("Manage environments")
                    }
                    OutlinedButton(onClick = onRefreshEnvironment, modifier = Modifier.weight(1f)) {
                        Text("Refresh checks")
                    }
                }
            }

            SettingsSectionHeader("Editor")
            SettingsCard(
                title = "Edit scope",
                description = if (projectOverridesAvailable) {
                    "Change workspace defaults or set project-specific overrides."
                } else {
                    "Only workspace-level editing is available until a local project is selected."
                },
            ) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    ConfigScope.entries.forEachIndexed { index, scope ->
                        val enabled = scope == ConfigScope.Workspace || projectOverridesAvailable
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ConfigScope.entries.size),
                            onClick = {
                                if (enabled) {
                                    selectedScope = scope
                                }
                            },
                            selected = selectedScope == scope,
                            enabled = enabled,
                            label = { Text(scope.name) },
                        )
                    }
                }
                Text(
                    text = when (selectedScope) {
                        ConfigScope.Workspace -> "Workspace changes apply across projects unless a project override exists."
                        ConfigScope.Project -> "Project changes only affect the selected local project."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            workspaceError?.let { message ->
                WarningCard(title = "Workspace YAML warning", message = message)
            }

            if (projectOverridesAvailable) {
                projectError?.let { message ->
                    WarningCard(title = "Project YAML warning", message = message)
                }
            }

            environmentState.errorMessage?.let { message ->
                WarningCard(title = "Environment warning", message = message)
            }

            SettingsCard(
                title = "Editor behavior",
                description = "These controls write back to YAML and update the open editor immediately.",
            ) {
                StepperRow(
                    label = "Font size",
                    value = "${fontSize.toInt()} sp",
                    onDecrease = { onUpdateFontSize(selectedScope, (fontSize - 1f).coerceAtLeast(8f)) },
                    onIncrease = { onUpdateFontSize(selectedScope, (fontSize + 1f).coerceAtMost(72f)) },
                )
                OptionRow(
                    label = "Tab size",
                    supporting = "Good defaults are 2, 4, or 8 spaces depending on the project.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(2, 4, 8).forEach { option ->
                            val selected = tabSize == option
                            if (selected) {
                                FilledTonalButton(
                                    onClick = { onUpdateTabSize(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("$option")
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onUpdateTabSize(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text("$option")
                                }
                            }
                        }
                    }
                }
                ToggleRow(
                    label = "Word wrap",
                    supporting = "Helpful on portrait phones; many codebases still prefer it off by default.",
                    checked = wordWrap,
                    onCheckedChange = { onUpdateWordWrap(selectedScope, it) },
                )
                ToggleRow(
                    label = "Minimap",
                    supporting = "Useful on tablets and desktop windows. Keep it optional on smaller widths.",
                    checked = minimap,
                    onCheckedChange = { onUpdateMinimap(selectedScope, it) },
                )
                ToggleRow(
                    label = "Ligatures",
                    supporting = "Keep enabled for the editor surface, but let users disable it for long coding sessions.",
                    checked = ligatures,
                    onCheckedChange = { onUpdateLigatures(selectedScope, it) },
                )
                ToggleRow(
                    label = "Drag to move cursor",
                    supporting = "Drag a finger on the editor to move the text cursor (the view scrolls to follow) instead of scrolling. Long-press still selects text. Applies app-wide.",
                    checked = editorDragSetting.enabled,
                    onCheckedChange = editorDragSetting.onChange,
                )
                if (editorDragSetting.enabled) {
                    StepperRow(
                        label = "Cursor drag speed — vertical",
                        value = "${editorDragSetting.verticalLevel} / 5",
                        onDecrease = { editorDragSetting.onVerticalLevelChange((editorDragSetting.verticalLevel - 1).coerceAtLeast(1)) },
                        onIncrease = { editorDragSetting.onVerticalLevelChange((editorDragSetting.verticalLevel + 1).coerceAtMost(5)) },
                    )
                    StepperRow(
                        label = "Cursor drag speed — horizontal",
                        value = "${editorDragSetting.horizontalLevel} / 5",
                        onDecrease = { editorDragSetting.onHorizontalLevelChange((editorDragSetting.horizontalLevel - 1).coerceAtLeast(1)) },
                        onIncrease = { editorDragSetting.onHorizontalLevelChange((editorDragSetting.horizontalLevel + 1).coerceAtMost(5)) },
                    )
                }
            }

            SettingsCard(
                title = "Tabs",
                description = "How editor and terminal tabs behave. Applies app-wide.",
            ) {
                ToggleRow(
                    label = "Hide tab close button",
                    supporting = "Removes the × on editor and terminal tabs to avoid accidental closes. Close a tab from its long-press menu instead.",
                    checked = tabCloseSetting.hidden,
                    onCheckedChange = tabCloseSetting.onChange,
                )
            }

            SettingsCard(
                title = "Explorer",
                description = "Choose how the file explorer is laid out. Applies to the current edit scope.",
            ) {
                OptionRow(
                    label = "View mode",
                    supporting = "Tree shows the whole project hierarchy; List is a one-folder file manager with breadcrumbs.",
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Tree", "List").forEach { option ->
                            val selected = explorerViewMode == option
                            if (selected) {
                                FilledTonalButton(
                                    onClick = { onUpdateExplorerViewMode(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(option)
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { onUpdateExplorerViewMode(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(option)
                                }
                            }
                        }
                    }
                }
            }

            SettingsCard(
                title = "Formatter",
                description = "Which formatter the editor uses. Built-in is rule-based; formatter extensions appear here once installed.",
                keywords = "format prettier indent on-save whitespace",
            ) {
                formatterOptions.forEach { (id, label) ->
                    BundleRow(
                        name = label,
                        description = if (id == "builtin") "Built-in rule-based formatter" else "Formatter extension",
                        selected = formatterId == id,
                        swatch = emptyList(),
                        onClick = { onSelectFormatter(id) },
                    )
                }
            }

            SettingsSectionHeader("Terminal")
            SettingsCard(
                title = "Terminal",
                description = "How tapping the terminal behaves. Applies app-wide.",
            ) {
                ToggleRow(
                    label = "Double-tap to type",
                    supporting = "Double-tap focuses the terminal and shows the keyboard; a single tap opens URLs and file paths. Turn off to focus with a single tap (links disabled).",
                    checked = terminalDoubleTapToFocus,
                    onCheckedChange = onUpdateTerminalDoubleTapToFocus,
                )
            }

            SettingsSectionHeader("Files")
            SettingsCard(
                title = "YAML files",
                description = "Open the backing config files directly when you want full control.",
            ) {
                FilledTonalButton(onClick = onOpenWorkspaceConfig, modifier = Modifier.fillMaxWidth()) {
                    Text("Open workspace YAML")
                }
                OutlinedButton(
                    onClick = onOpenProjectConfig,
                    enabled = projectOverridesAvailable,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open project YAML")
                }
            }
        }
        }
    }
}

/** Current Settings search query; cards/headers self-filter on it. */
val LocalSettingsQuery = compositionLocalOf { "" }

/** Compact, single-line search field (smaller than a default OutlinedTextField). */
@Composable
private fun SettingsSearchField(query: String, onQueryChange: (String) -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        text = "Search settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    imageVector = Icons.Rounded.Close,
                    contentDescription = "Clear search",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .size(16.dp)
                        .clickable { onQueryChange("") },
                )
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    // Hidden while searching, so results read as a flat filtered list.
    if (LocalSettingsQuery.current.isNotBlank()) return
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
    )
}

@Composable
private fun IdentityField(
    label: String,
    value: String,
    placeholder: String,
    onCommit: (() -> Unit)? = null,
    onValueChange: (String) -> Unit,
) {
    var wasFocused by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        ) {
            Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp)) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (onCommit == null) {
                                Modifier
                            } else {
                                Modifier.onFocusChanged { st ->
                                    if (wasFocused && !st.isFocused) onCommit()
                                    wasFocused = st.isFocused
                                }
                            },
                        ),
                )
            }
        }
    }
}

/** One control for a generic extension setting; shape depends on the declared [ExtensionSettingSpec.type]. */
@Composable
private fun ExtensionSettingControl(
    extensionId: String,
    spec: ExtensionSettingSpec,
    ui: ExtensionSettingsUi,
) {
    val current = ui.valueOf(extensionId, spec.key)
    when (spec.type) {
        "bool" -> ToggleRow(
            label = spec.label,
            supporting = spec.description.orEmpty(),
            checked = current == "true" || current == "1",
            onCheckedChange = { ui.onChange(extensionId, spec.key, it.toString()) },
        )

        "enum" -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(spec.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            spec.description?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            spec.options.forEach { option ->
                BundleRow(
                    name = option.replaceFirstChar { c -> c.uppercaseChar() },
                    description = "",
                    selected = current == option,
                    swatch = emptyList(),
                    onClick = { ui.onChange(extensionId, spec.key, option) },
                )
            }
        }

        else -> {
            // Buffer edits locally so fast typing isn't clobbered by the async DataStore round-trip,
            // and persist once on focus loss instead of on every keystroke (avoids a write/reload storm).
            var text by remember(extensionId, spec.key) { mutableStateOf(current) }
            LaunchedEffect(current) { if (current != text) text = current }
            IdentityField(
                label = spec.label,
                value = text,
                placeholder = spec.default,
                onCommit = { if (text != current) ui.onChange(extensionId, spec.key, text) },
            ) { text = it }
        }
    }
}

@Composable
private fun SettingsCard(
    title: String,
    description: String,
    keywords: String = "",
    content: @Composable () -> Unit,
) {
    val query = LocalSettingsQuery.current.trim()
    if (query.isNotEmpty() &&
        !title.contains(query, ignoreCase = true) &&
        !description.contains(query, ignoreCase = true) &&
        !keywords.contains(query, ignoreCase = true)
    ) {
        return
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}

@Composable
private fun WarningCard(
    title: String,
    message: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

@Composable
private fun BundleRow(
    name: String,
    description: String,
    selected: Boolean,
    swatch: List<Color>,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            swatch.take(4).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(color),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (description.isNotBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun IconBundleRow(
    bundle: IconBundle,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val sample = listOf(JCodeIcon.Files, JCodeIcon.Run, JCodeIcon.Terminal, JCodeIcon.Search, JCodeIcon.Settings)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            sample.forEach { slot ->
                Icon(
                    imageVector = bundle[slot],
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(bundle.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = bundle.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Rounded.Check,
                contentDescription = "Selected",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedButton(onClick = onDecrease) { Text("-") }
        FilledTonalButton(onClick = onIncrease) { Text("+") }
    }
}

@Composable
private fun OptionRow(
    label: String,
    supporting: String,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        Text(
            text = supporting,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

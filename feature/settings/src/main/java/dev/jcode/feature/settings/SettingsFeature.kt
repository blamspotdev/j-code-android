package dev.jcode.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
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
import dev.jcode.design.IconBundle
import dev.jcode.design.IconBundleRegistry
import dev.jcode.design.JCodeIcon
import dev.jcode.design.BottomBarVisibility
import dev.jcode.design.ExtraKeysVisibility
import dev.jcode.design.LocalBottomBarSetting
import dev.jcode.design.LocalFontSettings
import dev.jcode.design.LocalEditorDragMovesCursor
import dev.jcode.design.LocalExtraKeysSetting
import dev.jcode.design.LocalPerformanceSettings
import dev.jcode.design.ExplorerHiddenMode
import dev.jcode.design.LocalExplorerHiddenSetting
import dev.jcode.design.LocalRestoreSession
import dev.jcode.design.WebPreviewBrowsers
import dev.jcode.design.LocalWebPreviewBrowsers
import dev.jcode.design.LocalTabCloseButtonSetting
import dev.jcode.design.SettingsDefaults
import dev.jcode.design.SettingsDropdownRow
import dev.jcode.design.SettingsResettableRow
import dev.jcode.design.SettingsTextFieldRow
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
        // A null value clears the override from the scope's .jcode (see MainViewModel).
        onUpdateFontSize: (ConfigScope, Float?) -> Unit,
        onUpdateTabSize: (ConfigScope, Int?) -> Unit,
        onUpdateMinimap: (ConfigScope, Boolean?) -> Unit,
        onUpdateLigatures: (ConfigScope, Boolean?) -> Unit,
        onUpdateExplorerViewMode: (ConfigScope, String?) -> Unit,
        themeMode: ThemeMode,
        onUpdateThemeMode: (ThemeMode?) -> Unit,
        themeBundleId: String,
        onUpdateThemeBundle: (String) -> Unit,
        iconBundleId: String,
        onUpdateIconBundle: (String) -> Unit,
        formatterId: String,
        formatterOptions: List<Pair<String, String>>,
        onSelectFormatter: (String) -> Unit,
        hideStatusBarWithKeyboard: Boolean,
        onUpdateHideStatusBarWithKeyboard: (Boolean) -> Unit,
        isUserWorkspace: Boolean = false,
        modifier: Modifier = Modifier,
    ) {
        val tabCloseSetting = LocalTabCloseButtonSetting.current
        val editorDragSetting = LocalEditorDragMovesCursor.current
        val restoreSessionSetting = LocalRestoreSession.current
        val explorerHiddenSetting = LocalExplorerHiddenSetting.current
        val extraKeysSetting = LocalExtraKeysSetting.current
        val bottomBarSetting = LocalBottomBarSetting.current
        val fontSettings = LocalFontSettings.current
        val perf = LocalPerformanceSettings.current
        val webPreview = LocalWebPreviewBrowsers.current
        // The tab IS the scope — no separate "Edit scope" selector. Index 0 = Global (app-level);
        // each further tab edits one .jcode scope: WORKSPACE appears when a User Workspace is open,
        // PROJECT when a local project is selected, and the Default Workspace's own scope is offered
        // only when there is no project to scope to.
        var selectedTab by rememberSaveable { mutableStateOf(0) }
        val tabScopes: List<ConfigScope?> = buildList {
            add(null)
            if (isUserWorkspace) add(ConfigScope.Workspace)
            if (projectOverridesAvailable) add(ConfigScope.Project)
            if (size == 1) add(ConfigScope.Workspace)
        }
        val safeTab = selectedTab.coerceIn(0, tabScopes.lastIndex)
        // Scoped cards also render while a search is active (from any tab); they then edit the most
        // specific scope available.
        val selectedScope = tabScopes[safeTab]
            ?: if (projectOverridesAvailable) ConfigScope.Project else ConfigScope.Workspace

        val scopedEditor = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.editor
            ConfigScope.Project -> projectConfig?.editor
        }

        val fontSize = scopedEditor?.fontSize ?: effectiveConfig.editor.fontSize
        val tabSize = scopedEditor?.tabSize ?: effectiveConfig.editor.tabSize
        val minimap = scopedEditor?.minimap ?: effectiveConfig.editor.minimap
        val ligatures = scopedEditor?.ligatures ?: effectiveConfig.editor.ligatures

        val scopedExplorer = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.explorer
            ConfigScope.Project -> projectConfig?.explorer
        }
        val explorerViewMode = scopedExplorer?.viewMode ?: effectiveConfig.explorer.viewMode

        var query by rememberSaveable { mutableStateOf("") }
        // Fresh each composition; cards increment it when they pass the filter, and the trailing
        // empty-state reads it after all cards have composed.
        val matchSink = SettingsMatchSink()
        CompositionLocalProvider(
            LocalSettingsQuery provides query,
            LocalSettingsMatchSink provides matchSink,
        ) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Material underline tabs, left-packed. ScrollableTabRow's own divider only spans the
            // tab content, so it is suppressed and a full-width one is drawn behind the row.
            Box(modifier = Modifier.fillMaxWidth()) {
                HorizontalDivider(modifier = Modifier.align(Alignment.BottomStart))
                ScrollableTabRow(
                    selectedTabIndex = safeTab,
                    edgePadding = 0.dp,
                    containerColor = Color.Transparent,
                    divider = {},
                ) {
                    tabScopes.forEachIndexed { index, scope ->
                        Tab(
                            selected = safeTab == index,
                            onClick = { selectedTab = index },
                            modifier = Modifier.height(40.dp),
                            selectedContentColor = MaterialTheme.colorScheme.primary,
                            unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            text = {
                                Text(
                                    text = when (scope) {
                                        null -> "GLOBAL"
                                        ConfigScope.Workspace -> "WORKSPACE"
                                        ConfigScope.Project -> "PROJECT"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            },
                        )
                    }
                }
            }
            SettingsSearchField(query = query, onQueryChange = { query = it })
            // A search reaches across ALL tabs, so a match can never hide behind the tab selection;
            // outside a search, only the selected tab's cards render.
            val showGlobalTab = query.isNotBlank() || safeTab == 0
            val showScopedTab = query.isNotBlank() || safeTab >= 1
            if (showGlobalTab) {
            SettingsSectionHeader("Appearance")
            SettingsCard(
                title = "Appearance",
                description = "System follows your device's light/dark setting.",
                keywords = "appearance theme dark light system color mode scheme",
            ) {
                SettingsDropdownRow(
                    label = "Mode",
                    options = ThemeMode.entries.map { it.name },
                    selected = themeMode.name,
                    onSelect = { onUpdateThemeMode(ThemeMode.valueOf(it)) },
                    modified = workspaceConfig?.theme?.id != null || projectConfig?.theme?.id != null,
                    onReset = { onUpdateThemeMode(null) },
                )
            }

            SettingsCard(
                title = "Theme bundle",
                description = "Color palette applied across the app.",
                keywords = "theme bundle color palette catppuccin dracula nord scheme appearance",
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
                keywords = "icon bundle icons set material rounded j code line appearance",
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
                title = "Fonts",
                description = "Monospace typefaces for the code editor and the terminal. More fonts " +
                    "can be added by extensions.",
                keywords = "font fonts family typeface monospace editor terminal jetbrains mono system code appearance " +
                    fontSettings.options.joinToString(" ") { it.name },
            ) {
                val fontOptionIds = fontSettings.options.map { it.id }
                val fontLabel: (String) -> String =
                    { id -> fontSettings.options.firstOrNull { it.id == id }?.name ?: id }
                SettingsDropdownRow(
                    label = "Editor font",
                    options = fontOptionIds,
                    selected = fontSettings.editorFontId,
                    onSelect = fontSettings.onSelectEditorFont,
                    optionLabel = fontLabel,
                    modified = fontSettings.editorFontId != fontSettings.editorDefaultId,
                    onReset = { fontSettings.onSelectEditorFont(fontSettings.editorDefaultId) },
                )
                SettingsDropdownRow(
                    label = "Terminal font",
                    options = fontOptionIds,
                    selected = fontSettings.terminalFontId,
                    onSelect = fontSettings.onSelectTerminalFont,
                    optionLabel = fontLabel,
                    modified = fontSettings.terminalFontId != fontSettings.terminalDefaultId,
                    onReset = { fontSettings.onSelectTerminalFont(fontSettings.terminalDefaultId) },
                )
            }

            SettingsCard(
                title = "Immersive keyboard",
                description = "Reclaim screen space while typing.",
                keywords = "status bar immersive fullscreen keyboard editor terminal hide reveal screen space",
            ) {
                ToggleRow(
                    label = "Hide status bar with keyboard",
                    supporting = "Hide the system status bar while the on-screen keyboard is open, for more room in the editor and terminal. Swipe down from the top to reveal it.",
                    checked = hideStatusBarWithKeyboard,
                    onCheckedChange = onUpdateHideStatusBarWithKeyboard,
                    modified = hideStatusBarWithKeyboard != SettingsDefaults.HIDE_STATUS_BAR_WITH_KEYBOARD,
                    onReset = { onUpdateHideStatusBarWithKeyboard(SettingsDefaults.HIDE_STATUS_BAR_WITH_KEYBOARD) },
                )
            }

            SettingsCard(
                title = "Bottom status bar",
                description = "The bar at the bottom of the workbench showing branch, distro, and " +
                    "cursor position.",
                keywords = "bottom status bar branch distro cursor position hide always show soft keyboard chrome space",
            ) {
                SettingsDropdownRow(
                    label = "Show",
                    options = BottomBarVisibility.entries.map { it.name },
                    selected = bottomBarSetting.visibility.name,
                    onSelect = { bottomBarSetting.onChange(BottomBarVisibility.valueOf(it)) },
                    optionLabel = { bottomBarVisibilityLabel(BottomBarVisibility.valueOf(it)) },
                    modified = bottomBarSetting.visibility != SettingsDefaults.BOTTOM_STATUS_BAR,
                    onReset = { bottomBarSetting.onChange(SettingsDefaults.BOTTOM_STATUS_BAR) },
                )
            }

            SettingsCard(
                title = "Extra keys row",
                description = "A Termux-style key row (Esc, Tab, Ctrl, arrows and more) shown above " +
                    "the keyboard while typing in the terminal or editor. Choose when it appears in " +
                    "each orientation.",
                keywords = "extra keys row esc ctrl alt tab arrows home end pgup pgdn page terminal editor keyboard termux orientation portrait landscape hidden always with soft keyboard",
            ) {
                SettingsDropdownRow(
                    label = "Portrait",
                    options = ExtraKeysVisibility.entries.map { it.name },
                    selected = extraKeysSetting.portrait.name,
                    onSelect = { extraKeysSetting.onChangePortrait(ExtraKeysVisibility.valueOf(it)) },
                    optionLabel = { extraKeysVisibilityLabel(ExtraKeysVisibility.valueOf(it)) },
                    modified = extraKeysSetting.portrait != SettingsDefaults.EXTRA_KEYS_PORTRAIT,
                    onReset = { extraKeysSetting.onChangePortrait(SettingsDefaults.EXTRA_KEYS_PORTRAIT) },
                )
                SettingsDropdownRow(
                    label = "Landscape",
                    options = ExtraKeysVisibility.entries.map { it.name },
                    selected = extraKeysSetting.landscape.name,
                    onSelect = { extraKeysSetting.onChangeLandscape(ExtraKeysVisibility.valueOf(it)) },
                    optionLabel = { extraKeysVisibilityLabel(ExtraKeysVisibility.valueOf(it)) },
                    modified = extraKeysSetting.landscape != SettingsDefaults.EXTRA_KEYS_LANDSCAPE,
                    onReset = { extraKeysSetting.onChangeLandscape(SettingsDefaults.EXTRA_KEYS_LANDSCAPE) },
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
                    modified = restoreSessionSetting.enabled != SettingsDefaults.RESTORE_LAST_SESSION,
                    onReset = { restoreSessionSetting.onChange(SettingsDefaults.RESTORE_LAST_SESSION) },
                )
            }

            // Per-extension settings now live on the Extension Settings screen (Extensions list → gear),
            // alongside each extension's permissions — not here in App Settings.

            SettingsSectionHeader("Performance")
            SettingsCard(
                title = "Rendering",
                description = "How J Code draws the UI, editor, and terminal.",
                keywords = "performance rendering hardware acceleration gpu software draw graphics lag smooth",
            ) {
                ToggleRow(
                    label = "Hardware acceleration",
                    supporting = "Render the UI, editor, and terminal on the GPU. Turn off only to " +
                        "troubleshoot rendering glitches on this device — software rendering is much " +
                        "slower. Takes effect the next time the app starts.",
                    checked = perf.hardwareAcceleration,
                    onCheckedChange = perf.onSetHardwareAcceleration,
                    modified = perf.hardwareAcceleration != SettingsDefaults.HARDWARE_ACCELERATION,
                    onReset = { perf.onSetHardwareAcceleration(SettingsDefaults.HARDWARE_ACCELERATION) },
                )
            }
            SettingsCard(
                title = "Resource management",
                description = "Keep the Linux runtime lean by stopping work you're done with. Each terminal, " +
                    "run, and debug session holds a proot process tree in memory.",
                keywords = "performance memory cpu battery proot process terminal kill close idle background resource optimize swipe away warn running max instances timeout auto-close",
            ) {
                ToggleRow(
                    label = "Warn before closing running processes",
                    supporting = "When closing a project or workspace with a running terminal command, an active " +
                        "Build & Run, or a live debug session, ask first before stopping them.",
                    checked = perf.confirmCloseRunning,
                    onCheckedChange = perf.onSetConfirmCloseRunning,
                    modified = perf.confirmCloseRunning != SettingsDefaults.CONFIRM_CLOSE_RUNNING,
                    onReset = { perf.onSetConfirmCloseRunning(SettingsDefaults.CONFIRM_CLOSE_RUNNING) },
                )
                ToggleRow(
                    label = "Close app fully on swipe-away",
                    supporting = "When you swipe JCode off the Android recents screen, stop the Linux runtime " +
                        "(terminals, runs, VMs) and exit completely instead of leaving it running in the background.",
                    checked = perf.exitOnSwipeAway,
                    onCheckedChange = perf.onSetExitOnSwipeAway,
                    modified = perf.exitOnSwipeAway != SettingsDefaults.EXIT_ON_SWIPE_AWAY,
                    onReset = { perf.onSetExitOnSwipeAway(SettingsDefaults.EXIT_ON_SWIPE_AWAY) },
                )
                ToggleRow(
                    label = "Auto-close idle terminals",
                    supporting = "Automatically close terminals left idle at the prompt (no running program) to " +
                        "free their process tree and memory. Terminals running a command are never auto-closed.",
                    checked = perf.autoCloseIdleTerminals,
                    onCheckedChange = perf.onSetAutoCloseIdleTerminals,
                    modified = perf.autoCloseIdleTerminals != SettingsDefaults.AUTO_CLOSE_IDLE_TERMINALS,
                    onReset = { perf.onSetAutoCloseIdleTerminals(SettingsDefaults.AUTO_CLOSE_IDLE_TERMINALS) },
                )
                if (perf.autoCloseIdleTerminals) {
                    StepperRow(
                        label = "Idle timeout",
                        value = "${perf.idleTimeoutMinutes} min",
                        onDecrease = { perf.onSetIdleTimeoutMinutes(perf.idleTimeoutMinutes - 5) },
                        onIncrease = { perf.onSetIdleTimeoutMinutes(perf.idleTimeoutMinutes + 5) },
                        modified = perf.idleTimeoutMinutes != SettingsDefaults.IDLE_TIMEOUT_MINUTES,
                        onReset = { perf.onSetIdleTimeoutMinutes(SettingsDefaults.IDLE_TIMEOUT_MINUTES) },
                    )
                }
                StepperRow(
                    label = "Max terminal instances",
                    value = "${perf.maxTerminalSessions}",
                    onDecrease = { perf.onSetMaxTerminalSessions((perf.maxTerminalSessions - 1).coerceAtLeast(1)) },
                    onIncrease = { perf.onSetMaxTerminalSessions((perf.maxTerminalSessions + 1).coerceAtMost(24)) },
                    modified = perf.maxTerminalSessions != SettingsDefaults.MAX_TERMINAL_SESSIONS,
                    onReset = { perf.onSetMaxTerminalSessions(SettingsDefaults.MAX_TERMINAL_SESSIONS) },
                )
            }

            SettingsSectionHeader("Web preview")
            SettingsCard(
                title = "Open web previews in",
                description = "The browser used when you open a running dev server (Build & Run) or tap a URL " +
                    "in the terminal. A project can override this in its Build & Run panel.",
                keywords = "browser web preview open url chrome firefox default run dev server " +
                    "system always ask chooser built-in builtin " +
                    webPreview.available.joinToString(" ") { it.label },
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
                keywords = "environment proot distro toolchain smoke test bind runtime setup manage refresh install " +
                    "ready passed failed not installed not run unknown " +
                    environmentState.runtime.selectedDistro.label,
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
                title = "Editor gestures",
                description = "How touch input behaves in the editor. Applies app-wide.",
                keywords = "editor gestures drag move cursor speed vertical horizontal touch scroll",
            ) {
                ToggleRow(
                    label = "Drag to move cursor",
                    supporting = "Drag a finger on the editor to move the text cursor (the view scrolls to follow) instead of scrolling. Long-press still selects text. Applies app-wide.",
                    checked = editorDragSetting.enabled,
                    onCheckedChange = editorDragSetting.onChange,
                    modified = editorDragSetting.enabled != SettingsDefaults.EDITOR_DRAG_MOVES_CURSOR,
                    onReset = { editorDragSetting.onChange(SettingsDefaults.EDITOR_DRAG_MOVES_CURSOR) },
                )
                if (editorDragSetting.enabled) {
                    StepperRow(
                        label = "Cursor drag speed — vertical",
                        value = "${editorDragSetting.verticalLevel} / 5",
                        onDecrease = { editorDragSetting.onVerticalLevelChange((editorDragSetting.verticalLevel - 1).coerceAtLeast(1)) },
                        onIncrease = { editorDragSetting.onVerticalLevelChange((editorDragSetting.verticalLevel + 1).coerceAtMost(5)) },
                        modified = editorDragSetting.verticalLevel != SettingsDefaults.CURSOR_DRAG_LEVEL,
                        onReset = { editorDragSetting.onVerticalLevelChange(SettingsDefaults.CURSOR_DRAG_LEVEL) },
                    )
                    StepperRow(
                        label = "Cursor drag speed — horizontal",
                        value = "${editorDragSetting.horizontalLevel} / 5",
                        onDecrease = { editorDragSetting.onHorizontalLevelChange((editorDragSetting.horizontalLevel - 1).coerceAtLeast(1)) },
                        onIncrease = { editorDragSetting.onHorizontalLevelChange((editorDragSetting.horizontalLevel + 1).coerceAtMost(5)) },
                        modified = editorDragSetting.horizontalLevel != SettingsDefaults.CURSOR_DRAG_LEVEL,
                        onReset = { editorDragSetting.onHorizontalLevelChange(SettingsDefaults.CURSOR_DRAG_LEVEL) },
                    )
                }
            }

            SettingsCard(
                title = "Tabs",
                description = "How editor and terminal tabs behave. Applies app-wide.",
                keywords = "tabs tab close button hide editor terminal accidental",
            ) {
                ToggleRow(
                    label = "Hide tab close button",
                    supporting = "Removes the × on editor and terminal tabs to avoid accidental closes. Close a tab from its long-press menu instead.",
                    checked = tabCloseSetting.hidden,
                    onCheckedChange = tabCloseSetting.onChange,
                    modified = tabCloseSetting.hidden != SettingsDefaults.HIDE_TAB_CLOSE_BUTTON,
                    onReset = { tabCloseSetting.onChange(SettingsDefaults.HIDE_TAB_CLOSE_BUTTON) },
                )
            }

            SettingsCard(
                title = "Formatter",
                description = "Which formatter the editor uses. Built-in is rule-based; formatter extensions appear here once installed.",
                keywords = "formatter format prettier indent on-save whitespace built-in " +
                    formatterOptions.joinToString(" ") { it.second },
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

            SettingsSectionHeader("Explorer")
            SettingsCard(
                title = "Hidden files (project root)",
                description = "Hide files and folders at the project root in the Explorer. \"By-injected\" " +
                    "comes from each project's .gitignore, kept in sync by the Source Control extension.",
                keywords = "explorer files folder hide hidden project root gitignore jcode ignore injected specified show reveal by-line",
            ) {
                SettingsDropdownRow(
                    label = "Mode",
                    options = ExplorerHiddenMode.entries.map { it.name },
                    selected = explorerHiddenSetting.mode.name,
                    onSelect = { explorerHiddenSetting.onSetMode(ExplorerHiddenMode.valueOf(it)) },
                    optionLabel = { explorerHiddenModeLabel(ExplorerHiddenMode.valueOf(it)) },
                    modified = explorerHiddenSetting.mode != SettingsDefaults.HIDDEN_ROOT_MODE,
                    onReset = { explorerHiddenSetting.onSetMode(SettingsDefaults.HIDDEN_ROOT_MODE) },
                )
                var hidePatterns by remember(explorerHiddenSetting.specifiedRaw) {
                    mutableStateOf(explorerHiddenSetting.specifiedRaw)
                }
                SettingsTextFieldRow(
                    label = "Specified — one pattern per line",
                    value = hidePatterns,
                    onValueChange = { hidePatterns = it },
                    onCommit = { explorerHiddenSetting.onSetSpecifiedRaw(hidePatterns) },
                    placeholder = ".jcode",
                    singleLine = false,
                    minLines = 3,
                )
            }

            } // end Global tab

            if (showScopedTab) {
            SettingsSectionHeader("Editor")
            // The active tab already names the scope; this caption just states its reach.
            if (query.isBlank()) {
                Text(
                    text = when (selectedScope) {
                        ConfigScope.Workspace -> "These settings save to the workspace .jcode and apply across its projects unless a project override exists."
                        ConfigScope.Project -> "These settings save to the project .jcode and only affect the selected local project."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp),
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
                keywords = "editor behavior font size tab size minimap ligatures indent",
            ) {
                StepperRow(
                    label = "Font size",
                    value = "${fontSize.toInt()} sp",
                    onDecrease = { onUpdateFontSize(selectedScope, (fontSize - 1f).coerceAtLeast(8f)) },
                    onIncrease = { onUpdateFontSize(selectedScope, (fontSize + 1f).coerceAtMost(72f)) },
                    modified = scopedEditor?.fontSize != null,
                    onReset = { onUpdateFontSize(selectedScope, null) },
                )
                SettingsDropdownRow(
                    label = "Tab size",
                    supporting = "Good defaults are 2, 4, or 8 spaces depending on the project.",
                    options = listOf("2", "4", "8"),
                    selected = tabSize.toString(),
                    onSelect = { onUpdateTabSize(selectedScope, it.toInt()) },
                    optionLabel = { "$it spaces" },
                    modified = scopedEditor?.tabSize != null,
                    onReset = { onUpdateTabSize(selectedScope, null) },
                )
                ToggleRow(
                    label = "Minimap",
                    supporting = "Useful on tablets and desktop windows. Keep it optional on smaller widths.",
                    checked = minimap,
                    onCheckedChange = { onUpdateMinimap(selectedScope, it) },
                    modified = scopedEditor?.minimap != null,
                    onReset = { onUpdateMinimap(selectedScope, null) },
                )
                ToggleRow(
                    label = "Ligatures",
                    supporting = "Keep enabled for the editor surface, but let users disable it for long coding sessions.",
                    checked = ligatures,
                    onCheckedChange = { onUpdateLigatures(selectedScope, it) },
                    modified = scopedEditor?.ligatures != null,
                    onReset = { onUpdateLigatures(selectedScope, null) },
                )
            }

            SettingsCard(
                title = "Explorer",
                description = "Choose how the file explorer is laid out. Applies to the current edit scope.",
                keywords = "explorer view mode tree list file manager layout breadcrumbs",
            ) {
                OptionRow(
                    label = "View mode",
                    supporting = "Tree shows the whole project hierarchy; List is a one-folder file manager with breadcrumbs.",
                    modified = scopedExplorer?.viewMode != null,
                    onReset = { onUpdateExplorerViewMode(selectedScope, null) },
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

            SettingsSectionHeader("Files")
            SettingsCard(
                title = "YAML files",
                description = "Open the backing config files directly when you want full control.",
                keywords = "yaml files config workspace project open backing edit",
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
            } // end Project/Workspace tab

            // Composed after every card, so matchSink.count reflects the whole page.
            if (query.isNotBlank() && matchSink.count == 0) {
                SettingsNoResults(query)
            }
        }
        }
    }
}

/** Human-readable labels for the [ExplorerHiddenMode] dropdown (match the settings wording). */
private fun explorerHiddenModeLabel(mode: ExplorerHiddenMode): String = when (mode) {
    ExplorerHiddenMode.HideSpecifiedAndInjected -> "Hide Specified + By-Injected"
    ExplorerHiddenMode.HideInjected -> "Hide By-Injected"
    ExplorerHiddenMode.None -> "No Hidden File"
}

/** Human-readable label for an [ExtraKeysVisibility] dropdown option. */
private fun extraKeysVisibilityLabel(mode: ExtraKeysVisibility): String = when (mode) {
    ExtraKeysVisibility.Hidden -> "Hidden"
    ExtraKeysVisibility.WithKeyboard -> "With keyboard"
    ExtraKeysVisibility.Always -> "Always"
}

/** Human-readable label for a [BottomBarVisibility] dropdown option. */
private fun bottomBarVisibilityLabel(mode: BottomBarVisibility): String = when (mode) {
    BottomBarVisibility.Hidden -> "Hidden"
    BottomBarVisibility.HideOnKeyboard -> "Hide on Soft Keyboard"
    BottomBarVisibility.AlwaysShow -> "Always Show"
}

/** Current Settings search query; cards/headers self-filter on it. */
val LocalSettingsQuery = compositionLocalOf { "" }

/** Counts how many cards passed the search filter this composition, so a no-match query can show an
 *  empty state. A fresh instance is provided each composition (see Content), so every card recomposes
 *  and re-counts on any page change — the count read by the trailing empty-state is always accurate. */
private class SettingsMatchSink { var count = 0 }
private val LocalSettingsMatchSink = compositionLocalOf { SettingsMatchSink() }

/** True when EVERY whitespace-separated term in [query] appears (case-insensitive) somewhere in the
 *  card's searchable text ([haystacks] = title + description + keywords). Term-wise AND matching lets
 *  "tab close" find the Tabs card, where a single-substring match would not. */
private fun matchesSettingsQuery(query: String, vararg haystacks: String): Boolean {
    val terms = query.split(' ', '\t', '\n', '-').filter { it.isNotBlank() }
    if (terms.isEmpty()) return true
    val hay = haystacks.joinToString(" ").lowercase()
    return terms.all { hay.contains(it.lowercase()) }
}

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

/** Shown when a search query matches no card, so an empty page reads as "no results" not "broken". */
@Composable
private fun SettingsNoResults(query: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = "No settings match “$query”",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = "Try a shorter or different term, like “font” or “theme”.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun SettingsCard(
    title: String,
    description: String,
    keywords: String = "",
    content: @Composable () -> Unit,
) {
    val query = LocalSettingsQuery.current.trim()
    if (query.isNotEmpty() && !matchesSettingsQuery(query, title, description, keywords)) return
    LocalSettingsMatchSink.current.count++
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
    // Participate in the search filter/count like SettingsCard, so a warning neither leaks into
    // unrelated results nor sits above a "No results" empty state.
    val query = LocalSettingsQuery.current.trim()
    if (query.isNotEmpty() && !matchesSettingsQuery(query, title, message, "warning error yaml")) return
    LocalSettingsMatchSink.current.count++
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
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    SettingsResettableRow(modified = modified, onReset = onReset) {
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
}

@Composable
private fun OptionRow(
    label: String,
    supporting: String,
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    SettingsResettableRow(modified = modified, onReset = onReset) {
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
}

@Composable
private fun ToggleRow(
    label: String,
    supporting: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
) {
    SettingsResettableRow(modified = modified, onReset = onReset) {
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
}

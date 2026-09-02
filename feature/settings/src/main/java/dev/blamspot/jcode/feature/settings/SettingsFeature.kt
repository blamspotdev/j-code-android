package dev.blamspot.jcode.feature.settings

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.core.config.ConfigScope
import dev.blamspot.jcode.core.config.EffectiveConfig
import dev.blamspot.jcode.core.config.ProjectConfig
import dev.blamspot.jcode.core.config.WorkspaceConfig
import dev.blamspot.jcode.design.FileIconSet
import dev.blamspot.jcode.design.painter
import dev.blamspot.jcode.design.LocalFileIconSet
import dev.blamspot.jcode.design.FileTypeIcon
import dev.blamspot.jcode.design.FileIconSetRegistry
import dev.blamspot.jcode.design.LocalIconSetSettings
import dev.blamspot.jcode.design.UiIconSet
import dev.blamspot.jcode.design.UiIconSetRegistry
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.SettingsActionRow
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.design.BottomBarVisibility
import dev.blamspot.jcode.design.ExtraKeysVisibility
import dev.blamspot.jcode.design.LocalBottomBarSetting
import dev.blamspot.jcode.design.LocalFontSettings
import dev.blamspot.jcode.design.LocalEditorDragMovesCursor
import dev.blamspot.jcode.design.LocalDiagnosticsSetting
import dev.blamspot.jcode.design.LocalEditorFontSizeSetting
import dev.blamspot.jcode.design.LocalExtensionFontSizeSetting
import dev.blamspot.jcode.design.LocalTerminalFontSizeSetting
import dev.blamspot.jcode.design.LocalEditorWordWrapSetting
import dev.blamspot.jcode.design.LocalExtraKeysSetting
import dev.blamspot.jcode.design.LocalPerformanceSettings
import dev.blamspot.jcode.design.LocalRightDrawerSetting
import dev.blamspot.jcode.design.ExplorerExcludeEffect
import dev.blamspot.jcode.design.ExplorerHiddenMode
import dev.blamspot.jcode.design.LocalAndroidDevice
import dev.blamspot.jcode.design.LocalAppUpdate
import dev.blamspot.jcode.design.LocalSettingsBackup
import dev.blamspot.jcode.design.EnvVarSettings
import dev.blamspot.jcode.design.LocalEnvVarSettings
import dev.blamspot.jcode.design.LocalEnvironmentBackup
import dev.blamspot.jcode.design.LocalCutoutSetting
import dev.blamspot.jcode.design.LocalExplorerHiddenSetting
import dev.blamspot.jcode.design.LocalTrashSettings
import dev.blamspot.jcode.design.TRASH_RETENTION_CHOICES
import dev.blamspot.jcode.design.trashRetentionLabel
import dev.blamspot.jcode.design.LocalTabColoringSetting
import dev.blamspot.jcode.design.LocalTabMaxSize
import dev.blamspot.jcode.design.TabColoring
import dev.blamspot.jcode.design.TabMaxSize
import dev.blamspot.jcode.design.HeaderActionButton
import dev.blamspot.jcode.design.LocalCommandPaletteSetting
import dev.blamspot.jcode.design.LocalHeaderActionSetting
import dev.blamspot.jcode.design.LocalDeveloperSetting
import dev.blamspot.jcode.design.LocalMarkdownPreviewSetting
import dev.blamspot.jcode.design.LocalVolumeKeysSetting
import dev.blamspot.jcode.design.PaletteCommandCatalog
import dev.blamspot.jcode.design.VolumeKeyAction
import dev.blamspot.jcode.design.LocalRestoreSession
import dev.blamspot.jcode.design.WebPreviewBrowsers
import dev.blamspot.jcode.design.LocalWebPreviewBrowsers
import dev.blamspot.jcode.design.LocalTabCloseButtonSetting
import dev.blamspot.jcode.design.SettingsDefaults
import dev.blamspot.jcode.design.SettingsDropdownRow
import dev.blamspot.jcode.design.SettingsResettableRow
import dev.blamspot.jcode.design.SettingsTextFieldRow
import dev.blamspot.jcode.design.ThemeBundleRegistry
import dev.blamspot.jcode.core.diag.DiagLevel
import dev.blamspot.jcode.core.distro.AppProcesses
import dev.blamspot.jcode.core.distro.DistroEnvironmentState
import dev.blamspot.jcode.design.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.roundToInt

object SettingsFeature {

    /** Group the next composition should jump to, or null. Set by [revealGroup]. */
    private val pendingReveal = mutableStateOf<String?>(null)

    /**
     * Ask the Settings screen to reveal a group by title — switch to the GLOBAL tab, clear any
     * search, expand it, and scroll it into view. Used by the "update available" toast so its Update
     * action lands the user on the progress it starts. Safe to call before Settings is composed: the
     * request is consumed by whichever composition runs next.
     */
    fun revealGroup(title: String) {
        pendingReveal.value = title
    }

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
        onUpdateTabColoring: (ConfigScope, String?) -> Unit,
        onUpdateLigatures: (ConfigScope, Boolean?) -> Unit,
        onUpdateExplorerViewMode: (ConfigScope, String?) -> Unit,
        themeMode: ThemeMode,
        onUpdateThemeMode: (ThemeMode?) -> Unit,
        themeBundleId: String,
        onUpdateThemeBundle: (String) -> Unit,
        formatterId: String,
        formatterOptions: List<Pair<String, String>>,
        onSelectFormatter: (String) -> Unit,
        isUserWorkspace: Boolean = false,
        modifier: Modifier = Modifier,
    ) {
        val iconSettings = LocalIconSetSettings.current
        val tabCloseSetting = LocalTabCloseButtonSetting.current
        val editorDragSetting = LocalEditorDragMovesCursor.current
        val restoreSessionSetting = LocalRestoreSession.current
        val explorerHiddenSetting = LocalExplorerHiddenSetting.current
        val trashSettings = LocalTrashSettings.current
        val cutoutSetting = LocalCutoutSetting.current
        val volumeKeysSetting = LocalVolumeKeysSetting.current
        val tabColoringSetting = LocalTabColoringSetting.current
        val tabMaxSizeSetting = LocalTabMaxSize.current
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
        // The trailing "ENV VAR" tab lives at index tabScopes.size (it is not a ConfigScope).
        val safeTab = selectedTab.coerceIn(0, tabScopes.size)
        val isEnvVarTab = safeTab == tabScopes.size
        // Scoped cards also render while a search is active (from any tab); they then edit the most
        // specific scope available. getOrNull guards the ENV VAR tab index (out of tabScopes range).
        val selectedScope = tabScopes.getOrNull(safeTab)
            ?: if (projectOverridesAvailable) ConfigScope.Project else ConfigScope.Workspace

        val scopedEditor = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.editor
            ConfigScope.Project -> projectConfig?.editor
        }

        val fontSize = scopedEditor?.fontSize ?: effectiveConfig.editor.fontSize
        val tabSize = scopedEditor?.tabSize ?: effectiveConfig.editor.tabSize
        val ligatures = scopedEditor?.ligatures ?: effectiveConfig.editor.ligatures

        val scopedExplorer = when (selectedScope) {
            ConfigScope.Workspace -> workspaceConfig?.explorer
            ConfigScope.Project -> projectConfig?.explorer
        }
        val explorerViewMode = scopedExplorer?.viewMode ?: effectiveConfig.explorer.viewMode

        var query by rememberSaveable { mutableStateOf("") }
        val scrollState = rememberScrollState()
        // A reveal request (e.g. the update toast's Update action) puts the named group on screen:
        // the GLOBAL tab because that is where they live, no search because a query bypasses grouping
        // entirely, then expand and scroll. The group's offset is only known once it has been laid
        // out, and Settings may have opened on this very frame, so wait for it rather than guessing.
        val reveal = pendingReveal.value
        LaunchedEffect(reveal) {
            val title = reveal ?: return@LaunchedEffect
            selectedTab = 0
            query = ""
            settingsGroupExpanded.getOrPut(title) { mutableStateOf(false) }.value = true
            var frames = 0
            while (settingsGroupOffsets[title] == null && frames++ < 30) withFrameNanos { }
            settingsGroupOffsets[title]?.let { scrollState.animateScrollTo(it.roundToInt()) }
            pendingReveal.value = null
        }
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
                .verticalScroll(scrollState)
                // Less room above the scope tabs than around everything else: they are a header, and
                // the editor's own tab strip is already a horizontal edge directly above them, so a
                // full margin there read as a band of nothing between two rows of tabs.
                .padding(start = Space.md, end = Space.md, top = Space.xs, bottom = Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
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
                    // Trailing content tab (not a scope): the environment-variable editor.
                    Tab(
                        selected = isEnvVarTab,
                        onClick = { selectedTab = tabScopes.size },
                        modifier = Modifier.height(40.dp),
                        selectedContentColor = MaterialTheme.colorScheme.primary,
                        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        text = {
                            Text(
                                text = "ENV VAR",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        },
                    )
                }
            }
            if (isEnvVarTab) {
                EnvVarEditor(LocalEnvVarSettings.current)
            } else {
            SettingsSearchField(query = query, onQueryChange = { query = it })
            }
            // Search is scoped to the SELECTED tab (like VS Code's User/Workspace split): the GLOBAL
            // tab shows only app-level settings, WORKSPACE/PROJECT only the .jcode-scoped ones — so a
            // search on the Project tab never surfaces global settings that aren't project-overridable.
            val showGlobalTab = safeTab == 0
            val showScopedTab = safeTab in 1 until tabScopes.size
            if (showGlobalTab) {
            SettingsGroup("Appearance") {
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
                keywords = "theme bundle color palette catppuccin dracula midnight oled black scheme appearance",
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
                title = "UI icons",
                description = "Icon set used for the app's own toolbars, tabs and menus.",
                keywords = "icon bundle icons set ui material rounded jcode line appearance " +
                    iconSettings.uiSets.joinToString(" ") { it.name },
            ) {
                val activeUi = iconSettings.activeUiSetId
                iconSettings.uiSets.forEach { set ->
                    UiIconSetRow(
                        set = set,
                        selected = activeUi == set.id,
                        onClick = { iconSettings.onSelectUiSet(set.id) },
                    )
                }
            }

            // Hidden until something can fill it: JCode ships no file icon set, so with no icon-pack
            // extension installed this card would offer exactly one choice — the one already in use.
            if (iconSettings.fileSets.isNotEmpty()) {
                SettingsCard(
                    title = "File icons",
                    description = "Icon set used for files and folders in the Explorer, tabs and search results.",
                    keywords = "icon bundle icons set file files folder explorer appearance " +
                        iconSettings.fileSets.joinToString(" ") { it.name },
                ) {
                    val activeFiles = iconSettings.activeFileSetId
                    FileIconSetRow(
                        name = "None",
                        description = "JCode's own folder and file glyphs, from the UI icon set.",
                        detail = null,
                        selected = activeFiles == FileIconSetRegistry.NONE_ID,
                        onClick = { iconSettings.onSelectFileSet(FileIconSetRegistry.NONE_ID) },
                    )
                    iconSettings.fileSets.forEach { set ->
                        FileIconSetRow(
                            name = set.name,
                            description = set.description,
                            detail = set,
                            selected = activeFiles == set.id,
                            onClick = { iconSettings.onSelectFileSet(set.id) },
                        )
                    }
                }
            }

            SettingsCard(
                title = "Fonts",
                description = "Monospace typefaces for the code editor and the terminal. More fonts " +
                    "can be added by extensions.",
                keywords = "font fonts family typeface monospace editor terminal jetbrains mono system code appearance " +
                    fontSettings.options.joinToString(" ") { it.name },
            ) {
                // Re-scan the environment's installed fonts each time this card is shown, so fonts the
                // user apt-installed since launch appear without a restart.
                LaunchedEffect(Unit) { fontSettings.onScanFonts() }
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
                title = "Terminal",
                description = "Text size for terminal sessions. Applies to every open terminal.",
                keywords = "terminal font size text scale sp bigger smaller zoom console shell tty readable",
            ) {
                val terminalFontSizeSetting = LocalTerminalFontSizeSetting.current
                StepperRow(
                    label = "Font size",
                    value = "${terminalFontSizeSetting.value.toInt()} sp",
                    onDecrease = { terminalFontSizeSetting.onChange((terminalFontSizeSetting.value - 1f).coerceAtLeast(6f)) },
                    onIncrease = { terminalFontSizeSetting.onChange((terminalFontSizeSetting.value + 1f).coerceAtMost(40f)) },
                    modified = terminalFontSizeSetting.value != SettingsDefaults.TERMINAL_FONT_SIZE,
                    onReset = { terminalFontSizeSetting.onChange(SettingsDefaults.TERMINAL_FONT_SIZE) },
                )
            }

            SettingsCard(
                title = "Extensions",
                description = "Text size inside imported .vsix extensions. A scale rather than a " +
                    "size, because each extension styles its own page.",
                keywords = "extension extensions vsix font size text scale zoom bigger smaller " +
                    "readable webview marketplace imported",
            ) {
                val extensionFontSize = LocalExtensionFontSizeSetting.current
                StepperRow(
                    label = "Font size",
                    value = "${extensionFontSize.percent}%",
                    onDecrease = { extensionFontSize.onChange((extensionFontSize.percent - 10).coerceAtLeast(50)) },
                    onIncrease = { extensionFontSize.onChange((extensionFontSize.percent + 10).coerceAtMost(300)) },
                    modified = extensionFontSize.percent != SettingsDefaults.EXTENSION_FONT_SCALE,
                    onReset = { extensionFontSize.onChange(SettingsDefaults.EXTENSION_FONT_SCALE) },
                )
            }

            // Hidden on displays without a cutout (desktop mode, external display, notchless devices).
            if (cutoutSetting.hasCutout) {
                SettingsCard(
                    title = "Display cutout",
                    description = "Keep the app clear of the camera notch or punch-hole. When off, the " +
                        "app draws into the cutout area for a full-screen layout.",
                    keywords = "cutout notch punch hole camera display safe area letterbox fullscreen screen edge insets",
                ) {
                    ToggleRow(
                        label = "Respect device cutout",
                        supporting = "Lay out the app inside the cutout's safe area instead of drawing behind it.",
                        checked = cutoutSetting.respect,
                        onCheckedChange = cutoutSetting.onChange,
                        modified = cutoutSetting.respect != SettingsDefaults.RESPECT_DEVICE_CUTOUT,
                        onReset = { cutoutSetting.onChange(SettingsDefaults.RESPECT_DEVICE_CUTOUT) },
                    )
                }
            }

            SettingsCard(
                title = "Right drawer",
                description = "The panel holding the terminal, output, issues and extension views.",
                keywords = "right drawer panel sidebar persistent dock split half width landscape terminal inspector",
            ) {
                val rightDrawerSetting = LocalRightDrawerSetting.current
                ToggleRow(
                    label = "Dock in landscape",
                    supporting = "In landscape, split the screen with the right drawer instead of " +
                        "sliding it over the editor, so both stay usable. Portrait is unaffected.",
                    checked = rightDrawerSetting.enabled,
                    onCheckedChange = rightDrawerSetting.onSetEnabled,
                    modified = rightDrawerSetting.enabled != SettingsDefaults.RIGHT_DRAWER_PERSISTENT,
                    onReset = { rightDrawerSetting.onSetEnabled(SettingsDefaults.RIGHT_DRAWER_PERSISTENT) },
                )
            }

            SettingsCard(
                title = "Header",
                description = "The bar across the top of the workbench, with the project name and " +
                    "quick actions.",
                keywords = "header top bar app bar terminal command palette button action hide disable remove",
            ) {
                val headerActionSetting = LocalHeaderActionSetting.current
                SettingsDropdownRow(
                    label = "Action button",
                    supporting = "The button beside Run. Hiding it leaves the terminal reachable " +
                        "from the right drawer.",
                    options = HeaderActionButton.entries.map { it.name },
                    selected = headerActionSetting.button.name,
                    onSelect = { headerActionSetting.onChange(HeaderActionButton.valueOf(it)) },
                    optionLabel = { headerActionButtonLabel(HeaderActionButton.valueOf(it)) },
                    modified = headerActionSetting.button != SettingsDefaults.HEADER_ACTION_BUTTON,
                    onReset = { headerActionSetting.onChange(SettingsDefaults.HEADER_ACTION_BUTTON) },
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
                keywords = "extra keys row esc ctrl alt tab arrows home end pgup pgdn page terminal editor keyboard termux orientation portrait landscape hidden always with soft keyboard function keys f1 f2 f3 f4 f5 f6 f7 f8 f9 f10 f11 f12 fn htop midnight commander",
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
                ToggleRow(
                    label = "Function keys",
                    supporting = "Append F1–F12 chips to the row while a terminal is focused (htop, " +
                        "midnight commander, and other TUIs use them).",
                    checked = extraKeysSetting.functionKeys,
                    onCheckedChange = { extraKeysSetting.onChangeFunctionKeys(it) },
                    modified = extraKeysSetting.functionKeys != SettingsDefaults.EXTRA_KEYS_FUNCTION_KEYS,
                    onReset = { extraKeysSetting.onChangeFunctionKeys(SettingsDefaults.EXTRA_KEYS_FUNCTION_KEYS) },
                )
            }

            } // end Appearance

            SettingsGroup("Input") {
            SettingsCard(
                title = "Volume keys",
                description = "Remap the hardware volume buttons to editor/terminal actions. " +
                    "\"System Default\" keeps normal volume control. Pane actions (arrows, scroll) act on " +
                    "whichever editor or terminal is focused; hold to repeat arrows and scrolling.",
                keywords = "volume keys button hardware remap bind binding shortcut undo redo arrow scroll " +
                    "command palette input up down page rocker media",
            ) {
                SettingsDropdownRow(
                    label = "Volume up",
                    options = VolumeKeyAction.entries.map { it.name },
                    selected = volumeKeysSetting.up.name,
                    onSelect = { volumeKeysSetting.onChangeUp(VolumeKeyAction.valueOf(it)) },
                    optionLabel = { volumeKeyActionLabel(VolumeKeyAction.valueOf(it), "Vol Up") },
                    modified = volumeKeysSetting.up != SettingsDefaults.VOLUME_UP_ACTION,
                    onReset = { volumeKeysSetting.onChangeUp(SettingsDefaults.VOLUME_UP_ACTION) },
                )
                SettingsDropdownRow(
                    label = "Volume down",
                    options = VolumeKeyAction.entries.map { it.name },
                    selected = volumeKeysSetting.down.name,
                    onSelect = { volumeKeysSetting.onChangeDown(VolumeKeyAction.valueOf(it)) },
                    optionLabel = { volumeKeyActionLabel(VolumeKeyAction.valueOf(it), "Vol Down") },
                    modified = volumeKeysSetting.down != SettingsDefaults.VOLUME_DOWN_ACTION,
                    onReset = { volumeKeysSetting.onChangeDown(SettingsDefaults.VOLUME_DOWN_ACTION) },
                )
            }

            SettingsCard(
                title = "Command Palette",
                description = "Choose which built-in commands the palette offers. Context-dependent " +
                    "commands only appear when their view is focused (e.g. Go to Line needs an open editor).",
                keywords = "command palette commands orientation lock fullscreen keep awake screen on " +
                    "hide header tabs zen go to line color search picker eyedropper format document",
            ) {
                val paletteSetting = LocalCommandPaletteSetting.current
                PaletteCommandCatalog.forEach { command ->
                    val enabled = command.id !in paletteSetting.disabledIds
                    ToggleRow(
                        label = command.label,
                        supporting = command.description,
                        checked = enabled,
                        onCheckedChange = { paletteSetting.onSetEnabled(command.id, it) },
                        modified = !enabled,
                        onReset = { paletteSetting.onSetEnabled(command.id, true) },
                    )
                }
            }

            } // end Input

            SettingsGroup("Startup") {
            SettingsCard(
                title = "Restore last session",
                description = "Pick up where you left off after closing the app.",
                keywords = "restore session reopen tabs workspace project unsaved recover startup launch",
            ) {
                ToggleRow(
                    label = "Restore last session on launch",
                    supporting = "Reopen the last workspace, project, and editor tabs — including unsaved changes — when JCode starts. Missing files are skipped.",
                    checked = restoreSessionSetting.enabled,
                    onCheckedChange = restoreSessionSetting.onChange,
                    modified = restoreSessionSetting.enabled != SettingsDefaults.RESTORE_LAST_SESSION,
                    onReset = { restoreSessionSetting.onChange(SettingsDefaults.RESTORE_LAST_SESSION) },
                )
            }

            } // end Startup

            // Per-extension settings now live on the Extension Settings screen (Extensions list → gear),
            // alongside each extension's permissions — not here in App Settings.

            SettingsGroup("Performance") {
            SettingsCard(
                title = "Rendering",
                description = "How JCode draws the UI, editor, and terminal.",
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
                keywords = "performance memory cpu battery proot process terminal kill close idle background resource optimize swipe away warn running max instances timeout auto-close nested sub-shell subshell relocate tab bash zsh install toolchain sdk download timeout minutes android",
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
                StepperRow(
                    label = "Toolchain install timeout",
                    supporting = "How long a toolchain install (SDK, language server, debugger) may run before " +
                        "it's cancelled. Increase it for large SDKs like the Android SDK on a slow connection.",
                    value = "${perf.installTimeoutMinutes} min",
                    onDecrease = { perf.onSetInstallTimeoutMinutes((perf.installTimeoutMinutes - 5).coerceAtLeast(5)) },
                    onIncrease = { perf.onSetInstallTimeoutMinutes((perf.installTimeoutMinutes + 5).coerceAtMost(180)) },
                    modified = perf.installTimeoutMinutes != SettingsDefaults.INSTALL_TIMEOUT_MINUTES,
                    onReset = { perf.onSetInstallTimeoutMinutes(SettingsDefaults.INSTALL_TIMEOUT_MINUTES) },
                )
                ToggleRow(
                    label = "Sub-shells open in their own tab",
                    supporting = "When you start an interactive shell (bash, zsh, …) inside a terminal, open it in a " +
                        "temporary tab that closes when the sub-shell exits, returning to the parent — like a new " +
                        "console window. Scripts and piped shells stay in the current tab.",
                    checked = perf.nestedShellTabs,
                    onCheckedChange = perf.onSetNestedShellTabs,
                    modified = perf.nestedShellTabs != SettingsDefaults.NESTED_SHELL_TABS,
                    onReset = { perf.onSetNestedShellTabs(SettingsDefaults.NESTED_SHELL_TABS) },
                )
            }
            } // end Performance

            } // end Global-only cards; the Web preview card below renders on every scope tab.

            // "Open web previews in" edits the app-wide default on the GLOBAL tab and a per-project
            // override on the PROJECT tab (INHERIT = fall back to that default). It renders on every
            // tab; the raw selected tab (not [selectedScope], which coalesces GLOBAL into Project when
            // a project is open) decides which it edits, so the GLOBAL tab always edits the default.
            // Web preview renders on every scope tab, but not on the ENV VAR content tab.
            if (!isEnvVarTab) {
            val projectBrowserScope =
                tabScopes.getOrNull(safeTab) == ConfigScope.Project && webPreview.currentProjectKey.isNotBlank()
            SettingsGroup("Web preview") {
            SettingsCard(
                title = "Open web previews in",
                description = if (projectBrowserScope) {
                    "The browser this project uses when you open a running dev server (Build & Run) or " +
                        "tap a URL in the terminal. \"Use global default\" defers to the app-wide setting."
                } else {
                    "The browser used when you open a running dev server (Build & Run) or tap a URL in " +
                        "the terminal. A local project can override this on its Project settings tab."
                },
                keywords = "browser web preview open url chrome firefox default run dev server " +
                    "system always ask chooser built-in builtin inherit global project override " +
                    webPreview.available.joinToString(" ") { it.label },
            ) {
                val options = buildList {
                    if (projectBrowserScope) add(WebPreviewBrowsers.INHERIT)
                    add(WebPreviewBrowsers.SYSTEM)
                    add(WebPreviewBrowsers.ASK)
                    add(WebPreviewBrowsers.BUILTIN)
                    webPreview.available.forEach { add(it.packageName) }
                }
                val selectedChoice = if (projectBrowserScope) {
                    webPreview.projectChoice(webPreview.currentProjectKey)
                } else {
                    webPreview.globalChoice
                }
                options.forEach { choice ->
                    BundleRow(
                        name = webPreview.label(choice),
                        description = when (choice) {
                            WebPreviewBrowsers.INHERIT -> "Fall back to the app-wide default"
                            WebPreviewBrowsers.SYSTEM -> "The device's default browser app"
                            WebPreviewBrowsers.ASK -> "Show the Android app chooser each time"
                            WebPreviewBrowsers.BUILTIN -> "JCode's own in-editor browser, with DevTools"
                            else -> choice
                        },
                        selected = selectedChoice == choice,
                        swatch = emptyList(),
                        onClick = {
                            if (projectBrowserScope) {
                                webPreview.onSetProject(webPreview.currentProjectKey, choice)
                            } else {
                                webPreview.onSetGlobal(choice)
                            }
                        },
                    )
                }
            }

            SettingsCard(
                title = "Web engine",
                description = "The Chromium engine behind JCode's built-in browser and web previews. " +
                    "It is the device's WebView provider — a system component JCode can read but not " +
                    "choose; when it can't be updated, JCode falls back to the ROM's engine.",
                keywords = "web engine webview chromium version provider outdated update play store " +
                    "browser render blank dvh modern developer options implementation",
            ) {
                val ctx = LocalContext.current
                // Re-read on each composition of the card: the user may return from Play or the
                // provider picker with the engine changed, and a stale number here would claim the
                // trip changed nothing.
                val enginePackage = remember { runCatching { WebView.getCurrentWebViewPackage() }.getOrNull() }
                val engineVersion = enginePackage?.versionName ?: "unknown"
                val engineMajor = engineVersion.substringBefore('.').toIntOrNull() ?: 0
                // Chromium 108 shipped dynamic viewport units (dvh) — the line below which modern
                // sites visibly break. A margin above it counts as "current enough".
                val outdated = engineMajor in 1 until WEBVIEW_MODERN_MAJOR
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Chromium $engineVersion",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (outdated) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "Outdated",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = Space.ms, vertical = Space.xs),
                            )
                        }
                    } else if (engineMajor > 0) {
                        Text(
                            text = "Current",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                enginePackage?.packageName?.let { SummaryRow(label = "Provider", value = it) }
                if (outdated) {
                    Text(
                        text = "Modern sites can render blank or broken on this engine. Install the " +
                            "latest Android System WebView, then select it under Developer options → " +
                            "WebView implementation. Some devices lock the provider; JCode then keeps " +
                            "using the ROM's engine.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                CompactFilledButton(
                    text = "Get latest WebView",
                    onClick = {
                        val play = Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse("market://details?id=$GOOGLE_WEBVIEW_PACKAGE"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        runCatching { ctx.startActivity(play) }.onFailure {
                            runCatching {
                                ctx.startActivity(
                                    Intent(
                                        Intent.ACTION_VIEW,
                                        Uri.parse("https://play.google.com/store/apps/details?id=$GOOGLE_WEBVIEW_PACKAGE"),
                                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                                )
                            }
                        }
                    },
                )
                CompactOutlinedButton(
                    text = "Choose provider…",
                    onClick = {
                        runCatching {
                            ctx.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        }
                    },
                )
                }
            }
            } // end Web preview

            } // end web-preview (hidden on the ENV VAR tab)

            if (showGlobalTab) {
            SettingsGroup("Environment") {
            SettingsCard(
                title = "Environment",
                description = "Environment setup: proot, distro bootstrap, and the final smoke test. " +
                    "Install, switch between, or remove environments from the setup page.",
                keywords = "environment proot distro toolchain smoke test bind runtime setup manage refresh install " +
                    "ready passed failed not installed not run unknown update upgrade packages apt system " +
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
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    CompactFilledButton(text = "Manage environments", onClick = onOpenEnvironmentWizard)
                    CompactOutlinedButton(text = "Refresh checks", onClick = onRefreshEnvironment)
                }
                LocalEnvironmentBackup.current.migrationSummary?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactFilledButton(
                        text = "Import from previous install",
                        onClick = LocalEnvironmentBackup.current.onImportMigration,
                    )
                }
                if (environmentState.distroInstalled == true) {
                    val envBackup = LocalEnvironmentBackup.current
                    Text(
                        text = "Back up the whole Linux environment (~2.5 GB) to a .tar.gz you can " +
                            "restore here or on another device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        CompactFilledButton(text = "Back up (.tar.gz)", onClick = envBackup.onBackup)
                        CompactOutlinedButton(text = "Restore…", onClick = envBackup.onRestore)
                    }
                    // Moving to an install with a different package name. Android gives that install
                    // its own data directory and no way to read this one's, so everything has to go
                    // out through shared storage first — see MigrationBundle.
                    Text(
                        text = "Moving to a differently-named build? Write the environment, projects, " +
                            "extensions and settings to the shared JCode folder, then import them " +
                            "from the new install.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactOutlinedButton(
                        text = "Export for migration",
                        onClick = envBackup.onExportMigration,
                    )
                    Text(
                        text = "Refresh package lists and upgrade installed packages " +
                            "(apt-get update && upgrade). Runs in the Setup terminal — can be slow and use data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    CompactOutlinedButton(
                        text = if (envBackup.updatingPackages) "Updating packages…" else "Update system packages",
                        onClick = envBackup.onUpdatePackages,
                        enabled = !envBackup.updatingPackages,
                    )
                }
            }

            SettingsCard(
                title = "Background process limit",
                description = "Android caps how many processes an app may fork and kills the rest — " +
                    "which takes the whole Linux environment down mid-command. Raising the cap needs " +
                    "one adb command; JCode cannot set it itself.",
                keywords = "phantom process limit killed died crashed dies terminal closes distro proot stopped " +
                    "background max_phantom_processes device_config adb activity manager trimming long session " +
                    "claude agent build gradle npm disappears exits by itself",
            ) {
                val clipboard = LocalClipboardManager.current
                var processCount by remember { mutableStateOf<Int?>(null) }
                // Only polls while this card is actually on screen (the group is collapsed by default).
                LaunchedEffect(Unit) {
                    while (true) {
                        processCount = withContext(Dispatchers.IO) { AppProcesses.count() }
                        delay(3_000L)
                    }
                }
                SummaryRow(
                    label = "Linux processes",
                    value = processCount?.let { "$it of ${AppProcesses.DEFAULT_PHANTOM_LIMIT} (default cap)" }
                        ?: "Unknown",
                )
                Text(
                    text = "Android 12+ kills an app's forked processes once they pass the cap — 32 by " +
                        "default. proot, the shell and everything under it count, so a long build or " +
                        "coding-agent session goes over it and the terminal dies while JCode keeps " +
                        "running. Run these from a computer with the device connected (or from this " +
                        "device's own adb) to lift it; it survives reboots but has to be redone after a " +
                        "factory reset.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = AppProcesses.RAISE_LIMIT_COMMANDS,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                CompactOutlinedButton(
                    text = "Copy commands",
                    onClick = { clipboard.setText(AnnotatedString(AppProcesses.RAISE_LIMIT_COMMANDS)) },
                )
            }

            SettingsCard(
                title = "Android device",
                description = "Pair JCode with this phone's own adb so builds install and launch on it.",
                keywords = "android device adb bridge wireless debugging pair pairing code relay serial apk " +
                    "install launch logcat gradle installdebug flutter run",
            ) {
                val androidDevice = LocalAndroidDevice.current
                SummaryRow(label = "ADB bridge", value = androidDevice.status)
                androidDevice.serial?.let { SummaryRow(label = "Serial", value = it) }
                CompactFilledButton(
                    text = if (androidDevice.ready) "Manage device" else "Set up ADB",
                    onClick = androidDevice.onOpenPage,
                )
            }


            } // end Environment

            SettingsGroup("About") {
            SettingsCard(
                title = "JCode",
                description = "App version and updates from GitHub releases.",
                keywords = "about version update check release github changelog build app",
            ) {
                val appUpdate = LocalAppUpdate.current
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Version ${appUpdate.currentVersion}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (appUpdate.updateAvailable) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(50),
                        ) {
                            Text(
                                text = "Update: v${appUpdate.latestVersion}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimary,
                                modifier = Modifier.padding(horizontal = Space.ms, vertical = Space.xs),
                            )
                        }
                    } else if (appUpdate.latestVersion != null) {
                        Text(
                            text = "Up to date",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                // A single button: "Install Update" when a newer release is available (its label shows
                // download/install progress while running), otherwise "Check for updates".
                if (appUpdate.updateAvailable) {
                    CompactFilledButton(
                        text = when {
                            !appUpdate.installing -> "Install Update"
                            appUpdate.installProgress in 1..99 -> "Downloading… ${appUpdate.installProgress}%"
                            else -> "Installing…"
                        },
                        onClick = appUpdate.onInstallUpdate,
                        enabled = !appUpdate.installing,
                    )
                } else {
                    CompactOutlinedButton(
                        text = if (appUpdate.checking) "Checking…" else "Check for updates",
                        onClick = appUpdate.onCheck,
                        enabled = !appUpdate.checking,
                    )
                }
            }

            SettingsCard(
                title = "Backup & restore",
                description = "Save your app preferences to a file, then restore them here or on " +
                    "another device. (Theme and editor settings live in the workspace config.)",
                keywords = "backup restore export import settings preferences file save load transfer migrate json device",
            ) {
                val backup = LocalSettingsBackup.current
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    CompactFilledButton(text = "Export settings…", onClick = backup.onExport)
                    CompactOutlinedButton(text = "Import settings…", onClick = backup.onImport)
                }
            }

            } // end About

            SettingsGroup("Diagnostics") {
            SettingsCard(
                title = "Diagnostic logging",
                description = "Off unless you turn it on. When something misbehaves, record what the " +
                    "app is doing to a file you can attach to a bug report, then switch it back off.",
                keywords = "diagnostic diagnostics log logging logcat debug trace record capture crash report " +
                    "bug issue troubleshoot export share verbose file",
            ) {
                val diagnostics = LocalDiagnosticsSetting.current
                var showLog by remember { mutableStateOf(false) }
                // Size/location only move while recording, and only matter while this card is open.
                LaunchedEffect(diagnostics.enabled) {
                    while (diagnostics.enabled) {
                        diagnostics.onRefresh()
                        delay(2_000L)
                    }
                }
                ToggleRow(
                    label = "Record diagnostics",
                    supporting = "Writes app events to a log file on this device. Nothing is sent anywhere " +
                        "— you choose when to export it. File paths are replaced with placeholders so the " +
                        "log is safe to share.",
                    checked = diagnostics.enabled,
                    onCheckedChange = diagnostics.onSetEnabled,
                    modified = diagnostics.enabled != SettingsDefaults.DIAGNOSTIC_LOGGING,
                    onReset = { diagnostics.onSetEnabled(SettingsDefaults.DIAGNOSTIC_LOGGING) },
                )
                if (diagnostics.enabled) {
                    SettingsDropdownRow(
                        label = "Detail",
                        options = DiagLevel.entries.map { it.name },
                        selected = diagnostics.level.name,
                        onSelect = { diagnostics.onSetLevel(DiagLevel.valueOf(it)) },
                        optionLabel = { DiagLevel.valueOf(it).label },
                        modified = diagnostics.level != SettingsDefaults.DIAGNOSTIC_LEVEL,
                        onReset = { diagnostics.onSetLevel(SettingsDefaults.DIAGNOSTIC_LEVEL) },
                    )
                    ToggleRow(
                        label = "Include the system log",
                        supporting = "Adds JCode's logcat output — including proot and the Linux " +
                            "environment running under it, which is where most of the detail about " +
                            "toolchains, extensions and language servers ends up. Only JCode's own " +
                            "entries are readable; another app's never are.",
                        checked = diagnostics.captureSystemLog,
                        onCheckedChange = diagnostics.onSetCaptureSystemLog,
                        modified = diagnostics.captureSystemLog != SettingsDefaults.DIAGNOSTIC_SYSTEM_LOG,
                        onReset = { diagnostics.onSetCaptureSystemLog(SettingsDefaults.DIAGNOSTIC_SYSTEM_LOG) },
                    )
                    ToggleRow(
                        label = "Record crashes",
                        supporting = "Append the stack trace when the app crashes, so the log covers the " +
                            "failure itself and not just what led up to it.",
                        checked = diagnostics.captureCrashes,
                        onCheckedChange = diagnostics.onSetCaptureCrashes,
                        modified = diagnostics.captureCrashes != SettingsDefaults.DIAGNOSTIC_CRASHES,
                        onReset = { diagnostics.onSetCaptureCrashes(SettingsDefaults.DIAGNOSTIC_CRASHES) },
                    )
                    SummaryRow(label = "Recorded", value = formatLogSize(diagnostics.sizeBytes))
                    SummaryRow(label = "Location", value = diagnostics.location.ifBlank { "Starting…" })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    CompactFilledButton(
                        text = "View",
                        onClick = { showLog = true },
                        enabled = diagnostics.sizeBytes > 0L,
                    )
                    CompactOutlinedButton(
                        text = "Export…",
                        onClick = diagnostics.onExport,
                        enabled = diagnostics.sizeBytes > 0L,
                    )
                    CompactOutlinedButton(
                        text = "Clear",
                        onClick = diagnostics.onClear,
                        enabled = diagnostics.sizeBytes > 0L,
                    )
                }
                if (showLog) {
                    DiagnosticLogDialog(lines = diagnostics.recentLines(), onDismiss = { showLog = false })
                }
            }

            } // end Diagnostics

            SettingsGroup("Editor") {
            SettingsCard(
                title = "Editor defaults",
                description = "Default font size and word wrap for the code editor. A workspace or " +
                    "project can override the font size on its own settings tab.",
                keywords = "editor font size text scale sp word wrap soft wrap line long lines default global",
            ) {
                val editorFontSizeSetting = LocalEditorFontSizeSetting.current
                val editorWordWrapSetting = LocalEditorWordWrapSetting.current
                StepperRow(
                    label = "Font size",
                    value = "${editorFontSizeSetting.value.toInt()} sp",
                    onDecrease = { editorFontSizeSetting.onChange((editorFontSizeSetting.value - 1f).coerceAtLeast(8f)) },
                    onIncrease = { editorFontSizeSetting.onChange((editorFontSizeSetting.value + 1f).coerceAtMost(72f)) },
                    modified = editorFontSizeSetting.value != SettingsDefaults.EDITOR_FONT_SIZE,
                    onReset = { editorFontSizeSetting.onChange(SettingsDefaults.EDITOR_FONT_SIZE) },
                )
                ToggleRow(
                    label = "Word wrap",
                    supporting = "Wrap long lines to the editor width instead of scrolling horizontally.",
                    checked = editorWordWrapSetting.enabled,
                    onCheckedChange = { editorWordWrapSetting.onChange(it) },
                    modified = editorWordWrapSetting.enabled != SettingsDefaults.EDITOR_WORD_WRAP,
                    onReset = { editorWordWrapSetting.onChange(SettingsDefaults.EDITOR_WORD_WRAP) },
                )
            }
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
                keywords = "tabs tab close button hide editor terminal accidental coloring color accent random directory width size small medium large shorten ellipsis truncate",
            ) {
                ToggleRow(
                    label = "Hide tab close button",
                    supporting = "Removes the × on editor and terminal tabs to avoid accidental closes. Close a tab from its long-press menu instead.",
                    checked = tabCloseSetting.hidden,
                    onCheckedChange = tabCloseSetting.onChange,
                    modified = tabCloseSetting.hidden != SettingsDefaults.HIDE_TAB_CLOSE_BUTTON,
                    onReset = { tabCloseSetting.onChange(SettingsDefaults.HIDE_TAB_CLOSE_BUTTON) },
                )
                SettingsDropdownRow(
                    label = "Tab width",
                    supporting = "The most an editor or terminal tab widens before its name is shortened " +
                        "in the middle (e.g. \"build.gradle.kts\" → \"build.g…kts\").",
                    options = TabMaxSize.entries.map { it.name },
                    selected = tabMaxSizeSetting.size.name,
                    onSelect = { tabMaxSizeSetting.onChange(TabMaxSize.valueOf(it)) },
                    modified = tabMaxSizeSetting.size != SettingsDefaults.TAB_MAX_SIZE,
                    onReset = { tabMaxSizeSetting.onChange(SettingsDefaults.TAB_MAX_SIZE) },
                )
                SettingsDropdownRow(
                    label = "Tab coloring",
                    supporting = "Color-code editor file tabs. Long-press a file tab to set its color by hand; " +
                        "colors are remembered in the project's .jcode. A project can override this default.",
                    options = TabColoring.entries.map { it.name },
                    selected = tabColoringSetting.mode.name,
                    onSelect = { tabColoringSetting.onChange(TabColoring.valueOf(it)) },
                    optionLabel = { tabColoringLabel(TabColoring.valueOf(it)) },
                    modified = tabColoringSetting.mode != SettingsDefaults.TAB_COLORING,
                    onReset = { tabColoringSetting.onChange(SettingsDefaults.TAB_COLORING) },
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

            SettingsCard(
                title = "Markdown preview",
                description = "How the rendered Markdown preview lays out.",
                keywords = "markdown preview word wrap portrait landscape width horizontal scroll pan wide tables code",
            ) {
                val markdownPreviewSetting = LocalMarkdownPreviewSetting.current
                ToggleRow(
                    label = "Word wrap in portrait",
                    supporting = "Off: a portrait preview lays out at landscape width (the screen height, " +
                        "honoring the device-cutout setting) and pans sideways — wide tables and code stay unbroken.",
                    checked = markdownPreviewSetting.wrapInPortrait,
                    onCheckedChange = { markdownPreviewSetting.onSetWrapInPortrait(it) },
                    modified = markdownPreviewSetting.wrapInPortrait != SettingsDefaults.MARKDOWN_WRAP_PORTRAIT,
                    onReset = { markdownPreviewSetting.onSetWrapInPortrait(SettingsDefaults.MARKDOWN_WRAP_PORTRAIT) },
                )
            }

            } // end Editor

            SettingsGroup("Explorer") {
            SettingsCard(
                title = "Exclude Files/Folders",
                description = "Exclude files and folders at the project root in the Explorer. \"By-injected\" " +
                    "comes from each project's .gitignore, kept in sync by the Source Control extension. " +
                    "Excluded entries are greyed out by default, or hidden from the tree entirely.",
                keywords = "explorer files folder exclude hide hidden grey greyed grey-out dim de-emphasize project root gitignore jcode ignore injected specified show reveal by-line effect",
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
                SettingsDropdownRow(
                    label = "When excluded",
                    options = ExplorerExcludeEffect.entries.map { it.name },
                    selected = explorerHiddenSetting.effect.name,
                    onSelect = { explorerHiddenSetting.onSetEffect(ExplorerExcludeEffect.valueOf(it)) },
                    optionLabel = { explorerExcludeEffectLabel(ExplorerExcludeEffect.valueOf(it)) },
                    modified = explorerHiddenSetting.effect != SettingsDefaults.EXCLUDE_EFFECT,
                    onReset = { explorerHiddenSetting.onSetEffect(SettingsDefaults.EXCLUDE_EFFECT) },
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
            SettingsCard(
                title = "Trash",
                description = "Where deleted files go before they are gone. Covers Delete in the Explorer " +
                    "and Discard in Source Control; the Trash itself opens from the Explorer toolbar.",
                keywords = "trash bin recycle delete deleted remove restore recover undelete discard scm source control retention keep days empty permanently",
            ) {
                ToggleRow(
                    label = "Move deleted files to Trash",
                    supporting = "Deleting a file or folder, or discarding a change in Source Control, keeps a " +
                        "copy that can be restored. Turn this off to delete immediately and permanently.",
                    checked = trashSettings.enabled,
                    onCheckedChange = trashSettings.onSetEnabled,
                    modified = trashSettings.enabled != SettingsDefaults.TRASH_ENABLED,
                    onReset = { trashSettings.onSetEnabled(SettingsDefaults.TRASH_ENABLED) },
                )
                if (trashSettings.enabled) {
                    SettingsDropdownRow(
                        label = "Keep deleted files for",
                        supporting = "Older items are removed when JCode starts and when the Trash is opened. " +
                            "The Trash is app-private storage, so what is in it counts against the app's size.",
                        options = TRASH_RETENTION_CHOICES.map { it.toString() },
                        selected = trashSettings.retentionDays.toString(),
                        onSelect = { trashSettings.onSetRetentionDays(it.toInt()) },
                        optionLabel = { trashRetentionLabel(it.toInt()) },
                        modified = trashSettings.retentionDays != SettingsDefaults.TRASH_RETENTION_DAYS,
                        onReset = { trashSettings.onSetRetentionDays(SettingsDefaults.TRASH_RETENTION_DAYS) },
                    )
                }
            }

            } // end Explorer

            SettingsGroup("Developer") {
            SettingsCard(
                title = "Developer options",
                description = "Tools for building and testing JCode extensions.",
                keywords = "developer options extension sideload unsigned jext debug dev tools inspector validator log console reload make tool third party",
            ) {
                val developerSetting = LocalDeveloperSetting.current
                ToggleRow(
                    label = "Enable developer options",
                    supporting = "Adds an \"Ext Dev\" tab to the right panel (inspector, manifest validator, " +
                        "live log) for debugging an unsigned .jext or .vsix. Importing one does not need this; " +
                        "signed marketplace extensions are unaffected.",
                    checked = developerSetting.enabled,
                    onCheckedChange = { developerSetting.onSetEnabled(it) },
                    modified = developerSetting.enabled != SettingsDefaults.DEVELOPER_OPTIONS,
                    onReset = { developerSetting.onSetEnabled(SettingsDefaults.DEVELOPER_OPTIONS) },
                )
                if (developerSetting.enabled) {
                    Text(
                        "Compile and pack your extension with the JCode extension make tool, then import the " +
                            "unsigned .jext from the Extensions panel — the Ext Dev tab auto-reloads it on each " +
                            "rebuild. Only signed packages (signed privately by the JCode maintainers) reach the " +
                            "marketplace.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            } // end Developer

            } // end Global tab

            if (showScopedTab) {
            // The active tab already names the scope; this caption just states its reach.
            if (query.isBlank()) {
                Text(
                    text = when (selectedScope) {
                        ConfigScope.Workspace -> "These settings save to the workspace .jcode and apply across its projects unless a project override exists."
                        ConfigScope.Project -> "These settings save to the project .jcode and only affect the selected local project."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = Space.xxs),
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

            SettingsGroup("Editor", stateKey = "scoped.Editor") {
            SettingsCard(
                title = "Editor behavior",
                description = "These controls write back to YAML and update the open editor immediately.",
                keywords = "editor behavior font size tab size ligatures indent tab coloring color accent",
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
                    label = "Ligatures",
                    supporting = "Keep enabled for the editor surface, but let users disable it for long coding sessions.",
                    checked = ligatures,
                    onCheckedChange = { onUpdateLigatures(selectedScope, it) },
                    modified = scopedEditor?.ligatures != null,
                    onReset = { onUpdateLigatures(selectedScope, null) },
                )
                // Sanitize: a hand-edited .jcode may hold an unknown enum name; fall back to the
                // app default rather than crashing composition on TabColoring.valueOf.
                val tabColoring = (scopedEditor?.tabColoring ?: effectiveConfig.editor.tabColoring)
                    ?.let { runCatching { TabColoring.valueOf(it) }.getOrNull() }
                    ?.name
                    ?: tabColoringSetting.mode.name
                SettingsDropdownRow(
                    label = "Tab coloring",
                    supporting = "Overrides the app-level default for this scope.",
                    options = TabColoring.entries.map { it.name },
                    selected = tabColoring,
                    onSelect = { onUpdateTabColoring(selectedScope, it) },
                    optionLabel = { runCatching { tabColoringLabel(TabColoring.valueOf(it)) }.getOrDefault(it) },
                    modified = scopedEditor?.tabColoring != null,
                    onReset = { onUpdateTabColoring(selectedScope, null) },
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
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        listOf("Tree", "List").forEach { option ->
                            val selected = explorerViewMode == option
                            if (selected) {
                                CompactFilledButton(
                                    text = option,
                                    onClick = { onUpdateExplorerViewMode(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                )
                            } else {
                                CompactOutlinedButton(
                                    text = option,
                                    onClick = { onUpdateExplorerViewMode(selectedScope, option) },
                                    modifier = Modifier.weight(1f),
                                )
                            }
                        }
                    }
                }
            }

            } // end Editor (scoped)

            SettingsGroup("Files") {
            SettingsCard(
                title = "YAML files",
                description = "Open the backing config files directly when you want full control.",
                keywords = "yaml files config workspace project open backing edit",
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    CompactFilledButton(text = "Open workspace YAML", onClick = onOpenWorkspaceConfig)
                    CompactOutlinedButton(
                        text = "Open project YAML",
                        onClick = onOpenProjectConfig,
                        enabled = projectOverridesAvailable,
                    )
                }
            }
            } // end Files

            } // end Project/Workspace tab

            // Composed after every card, so matchSink.count reflects the whole page.
            if (!isEnvVarTab && query.isNotBlank() && matchSink.count == 0) {
                SettingsNoResults(query)
            }
        }
        }
    }
}

/** Human-readable labels for the exclude "Mode" dropdown — WHICH entries are excluded. */
/**
 * The floor below which the device's Chromium counts as outdated in the Web engine card.
 *
 * Chromium 108 shipped dynamic viewport units (`dvh`), the first modern-CSS line whose absence
 * makes whole sites render blank rather than merely imperfect; a small margin above it counts as
 * current enough. Deliberately far below the actual current release: the card exists to flag
 * engines that *break* pages, not to nag every device that trails by a few versions.
 */
private const val WEBVIEW_MODERN_MAJOR = 110

/** Google's updatable WebView provider on Play — the install target for outdated engines. */
private const val GOOGLE_WEBVIEW_PACKAGE = "com.google.android.webview"

private fun explorerHiddenModeLabel(mode: ExplorerHiddenMode): String = when (mode) {
    ExplorerHiddenMode.HideSpecifiedAndInjected -> "Specified + By-Injected"
    ExplorerHiddenMode.HideInjected -> "By-Injected only"
    ExplorerHiddenMode.None -> "Off"
}

/** Human-readable labels for the "When excluded" dropdown — HOW excluded entries appear. */
private fun explorerExcludeEffectLabel(effect: ExplorerExcludeEffect): String = when (effect) {
    ExplorerExcludeEffect.GreyOut -> "Grey out"
    ExplorerExcludeEffect.Hide -> "Hide"
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

private fun headerActionButtonLabel(button: HeaderActionButton): String = when (button) {
    HeaderActionButton.Terminal -> "Terminal"
    HeaderActionButton.CommandPalette -> "Command Palette"
    HeaderActionButton.Hidden -> "Hidden"
}

private fun tabColoringLabel(mode: TabColoring): String = when (mode) {
    TabColoring.RandomRemember -> "Random (if not exist then remember)"
    TabColoring.Random -> "Random"
    TabColoring.DirectoryBased -> "Directory based (then remember)"
    TabColoring.Disabled -> "Disabled"
}

/** [defaultSuffix] disambiguates the per-button System Default label, e.g. "System Default (Vol Up)". */
private fun volumeKeyActionLabel(action: VolumeKeyAction, defaultSuffix: String): String = when (action) {
    VolumeKeyAction.SystemDefault -> "System Default ($defaultSuffix)"
    VolumeKeyAction.Undo -> "Undo"
    VolumeKeyAction.Redo -> "Redo"
    VolumeKeyAction.KeyLeft -> "Key Left"
    VolumeKeyAction.KeyRight -> "Key Right"
    VolumeKeyAction.KeyUp -> "Key Up"
    VolumeKeyAction.KeyDown -> "Key Down"
    VolumeKeyAction.ScrollUp -> "Scroll Up"
    VolumeKeyAction.ScrollDown -> "Scroll Down"
    VolumeKeyAction.CommandPalette -> "Command Palette"
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
        shape = RoundedCornerShape(Radius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp)
                .padding(horizontal = Space.ms),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Icon(
                imageVector = Icons.Rounded.Search,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.md),
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
            .padding(vertical = Space.xxl),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(Space.xs),
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

/**
 * Which settings groups are open, held outside the composition.
 *
 * `rememberSaveable` is not enough here: rotating the device makes the workbench swap between its
 * modal and docked layouts, which disposes this whole subtree along with its saveable registry, so
 * every group would snap shut on rotation. Session-scoped by design — a fresh launch starts
 * collapsed. Each group owns its own [MutableState] so toggling one doesn't invalidate the rest.
 */
private val settingsGroupExpanded = mutableMapOf<String, MutableState<Boolean>>()

/** Each group's y offset inside the scrolling column, published as it is laid out, so
 *  [SettingsFeature.revealGroup] can scroll to one. */
private val settingsGroupOffsets = mutableMapOf<String, Float>()

/**
 * A run of [SettingsCard]s under one heading, collapsed by default so the page opens as a short list
 * of headings instead of one long scroll.
 *
 * While a search is running the heading and the collapse are bypassed entirely and [content] is
 * emitted straight into the caller's Column — not merely un-collapsed. Cards filter themselves and
 * count themselves into [LocalSettingsMatchSink], so wrapping them at all would both hide matches
 * inside collapsed groups and, for a group whose cards all filtered out, leave an empty child behind
 * that the parent's `spacedBy` would still pad around.
 *
 * [stateKey] separates groups that share a title — "Editor" is a heading on both the global and the
 * scoped tab.
 */
@Composable
private fun ColumnScope.SettingsGroup(
    title: String,
    stateKey: String = title,
    content: @Composable () -> Unit,
) {
    if (LocalSettingsQuery.current.isNotBlank()) {
        content()
        return
    }
    var expanded by remember(stateKey) { settingsGroupExpanded.getOrPut(stateKey) { mutableStateOf(false) } }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .onGloballyPositioned { settingsGroupOffsets[stateKey] = it.positionInParent().y }
            .clickable { expanded = !expanded }
            .padding(top = Space.s, start = Space.xxs, end = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        Icon(
            painter = jcIcon(if (expanded) JCodeIcon.ChevronUp else JCodeIcon.ChevronDown),
            contentDescription = if (expanded) "Collapse $title" else "Expand $title",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(IconSize.md),
        )
    }
    // AnimatedVisibility stacks its children like a Box, so the cards need their own Column to keep
    // the page's 10dp rhythm instead of drawing on top of each other.
    AnimatedVisibility(visible = expanded) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) { content() }
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
    if (query.isNotEmpty() && !matchesSettingsQuery(query, title, description, keywords)) return
    LocalSettingsMatchSink.current.count++
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
        shape = RoundedCornerShape(Radius.xxl),
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.ms),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
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
        shape = RoundedCornerShape(Radius.xxl),
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.s),
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
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(onClick = onClick)
            .padding(vertical = Space.s, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.xxs)) {
            swatch.take(4).forEach { color ->
                Box(
                    modifier = Modifier
                        .size(14.dp)
                        .clip(RoundedCornerShape(Radius.sm))
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
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

@Composable
private fun UiIconSetRow(
    set: UiIconSet,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Five slots a set is most likely to have restyled, so two packs are told apart at a glance
    // rather than by their names.
    val sample = listOf(JCodeIcon.Files, JCodeIcon.Run, JCodeIcon.Terminal, JCodeIcon.Search, JCodeIcon.Settings)
    IconSetRow(
        name = set.name,
        description = set.description.ifBlank { "${set.filledSlots} icons" },
        selected = selected,
        onClick = onClick,
        preview = {
            sample.forEach { slot ->
                Icon(
                    painter = set.art(slot).painter(),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(IconSize.sm),
                )
            }
        },
    )
}

@Composable
private fun FileIconSetRow(
    name: String,
    description: String,
    detail: FileIconSet?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    // Previewed through the same resolver the Explorer uses, on names a pack of any language is
    // likely to answer — so the row shows what the set will actually draw, not a curated sample.
    val sample = listOf("src" to true, "index.ts" to false, "app.py" to false, "README.md" to false)
    IconSetRow(
        name = name,
        description = description.ifBlank { detail?.let { "${it.iconCount} icons" } ?: "" },
        selected = selected,
        onClick = onClick,
        preview = {
            CompositionLocalProvider(LocalFileIconSet provides detail) {
                sample.forEach { (fileName, isDirectory) ->
                    FileTypeIcon(
                        name = fileName,
                        isDirectory = isDirectory,
                        size = IconSize.sm,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
    )
}

@Composable
private fun IconSetRow(
    name: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.lg))
            .clickable(onClick = onClick)
            .padding(vertical = Space.s, horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            content = { preview() },
        )
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
                modifier = Modifier.size(IconSize.md),
            )
        }
    }
}

/** Human-readable size for the Diagnostics card's "Recorded" row. */
private fun formatLogSize(bytes: Long): String = when {
    bytes <= 0L -> "Nothing yet"
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> "${bytes / 1024L} KB"
    else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
}

/**
 * The tail of the current diagnostic session. Shown so a user can see exactly what is being recorded
 * before deciding to share it — opting in should not mean opting in blind.
 */
@Composable
private fun DiagnosticLogDialog(lines: List<String>, onDismiss: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Recent diagnostics") },
        text = {
            if (lines.isEmpty()) {
                Text("Nothing recorded yet.", style = MaterialTheme.typography.bodySmall)
            } else {
                // Newest last, scrolled to the bottom: the end of the log is what a report is about.
                val scroll = rememberScrollState()
                LaunchedEffect(lines.size) { scroll.scrollTo(scroll.maxValue) }
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(scroll)
                        .horizontalScroll(rememberScrollState()),
                ) {
                    lines.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                        )
                    }
                }
            }
        },
        confirmButton = { CompactFilledButton(text = "Close", onClick = onDismiss) },
        dismissButton = {
            CompactOutlinedButton(
                text = "Copy",
                onClick = {
                    clipboard.setText(AnnotatedString(buildString { lines.forEach { appendLine(it) } }))
                },
                enabled = lines.isNotEmpty(),
            )
        },
    )
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
private fun EnvVarEditor(settings: EnvVarSettings) {
    // Dialog state: null = closed; [adding] distinguishes a brand-new variable from editing [editTarget].
    var editTarget by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(Space.ms)) {
        // A plain heading, not a SettingsGroup: this tab is one section and has no search field.
        Text(
            text = "Environment variables",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = Space.s, start = Space.xxs),
        )
        Text(
            text = "Exported into every terminal and Build & Run session (e.g. API keys, GOPRIVATE, " +
                "JAVA_OPTS). Applied to newly opened terminals.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val entries = settings.vars.entries.sortedBy { it.key.lowercase() }
        if (entries.isEmpty()) {
            Text(
                text = "No variables yet. Tap “Add variable” to create one.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = Space.sm),
            )
        } else {
            entries.forEach { (name, value) ->
                SettingsResettableRow(modified = false, onReset = null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                text = value.ifEmpty { "(empty)" },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        CompactOutlinedButton(text = "Edit", onClick = { editTarget = name; adding = false })
                        CompactOutlinedButton(text = "Delete", onClick = { settings.onRemove(name) })
                    }
                }
            }
        }
        CompactFilledButton(text = "Add variable", onClick = { editTarget = ""; adding = true })
    }

    val target = editTarget
    if (target != null) {
        EnvVarDialog(
            initialName = if (adding) "" else target,
            initialValue = if (adding) "" else (settings.vars[target] ?: ""),
            existingNames = settings.vars.keys,
            editingName = if (adding) null else target,
            onDismiss = { editTarget = null },
            onSave = { name, value ->
                settings.onSet(name, value, if (adding) null else target)
                editTarget = null
            },
        )
    }
}

@Composable
private fun EnvVarDialog(
    initialName: String,
    initialValue: String,
    existingNames: Set<String>,
    editingName: String?,
    onDismiss: () -> Unit,
    onSave: (name: String, value: String) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var value by remember { mutableStateOf(initialValue) }
    val nameValid = name.matches(Regex("[A-Za-z_][A-Za-z0-9_]*"))
    val duplicate = name != editingName && name in existingNames
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editingName == null) "Add variable" else "Edit variable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.trim() },
                    label = { Text("Name") },
                    singleLine = true,
                    isError = name.isNotEmpty() && (!nameValid || duplicate),
                    supportingText = {
                        if (duplicate) {
                            Text("A variable named \"$name\" already exists")
                        } else if (name.isNotEmpty() && !nameValid) {
                            Text("Letters, digits and underscore only; can't start with a digit")
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    label = { Text("Value") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            CompactFilledButton(
                text = "Save",
                onClick = { onSave(name, value) },
                enabled = nameValid && !duplicate,
            )
        },
        dismissButton = { CompactOutlinedButton(text = "Cancel", onClick = onDismiss) },
    )
}

@Composable
private fun StepperRow(
    label: String,
    value: String,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
    modified: Boolean = false,
    onReset: (() -> Unit)? = null,
    supporting: String? = null,
) {
    SettingsResettableRow(modified = modified, onReset = onReset) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (supporting != null) {
                    Text(
                        text = supporting,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StepperButton(JCodeIcon.Minus, "Decrease $label", filled = false, onClick = onDecrease)
                StepperButton(JCodeIcon.Add, "Increase $label", filled = true, onClick = onIncrease)
            }
        }
    }
}

/**
 * One half of a stepper.
 *
 * An icon rather than a typed "-" and "+", which are a hyphen and a plus sign set at text size:
 * different weights, different widths, and sitting on a text baseline inside a button that holds no
 * text. Two glyphs meant to be a matched pair looked like neither.
 *
 * Round, because that is the shape of a button holding one glyph and nothing else: a pill is a shape
 * that expects a word in it, and reads as a button whose label failed to load. The emphasis pairing
 * stays — outlined to step down, tonal to step up, as elsewhere on the page.
 *
 * They are also the only controls on this page a screen reader could not name: "-" reads as a
 * hyphen and says nothing about what it steps. Each now says which setting it moves.
 */
@Composable
private fun StepperButton(
    icon: JCodeIcon,
    contentDescription: String,
    filled: Boolean,
    onClick: () -> Unit,
) {
    // Sized down from the 40dp default, and the interactive minimum relaxed with it. That minimum
    // is there for good reason and is not worth keeping here: it pads each button out to 48dp of
    // layout, which is most of the gap between the two, and a settings row is not a place anyone
    // taps in a hurry.
    val sizing = Modifier.size(34.dp)
    val glyph: @Composable () -> Unit = {
        Icon(jcIcon(icon), contentDescription, modifier = Modifier.size(IconSize.md))
    }
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        if (filled) {
            FilledTonalIconButton(onClick = onClick, modifier = sizing) { glyph() }
        } else {
            OutlinedIconButton(
                onClick = onClick,
                modifier = sizing,
                // Stated rather than defaulted. An outlined icon button draws its border from the
                // content colour, which in a dark theme is near-white and shouts across a page of
                // quiet rows. `outline` is what every switch on this page already draws its own
                // border with, and these sit in the same column as those switches.
                border = BorderStroke(StrokeWidth.thin, MaterialTheme.colorScheme.outline),
            ) { glyph() }
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
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
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
            horizontalArrangement = Arrangement.spacedBy(Space.md),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
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

package dev.jcode
import androidx.compose.foundation.isSystemInDarkTheme
import dev.jcode.core.editor.EditorLanguageAction
import dev.jcode.core.editor.decor.Layer
import dev.jcode.design.IconBundleRegistry
import dev.jcode.design.JCodeIcon
import dev.jcode.editor.SyntaxHighlighter
import dev.jcode.editor.TokenPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.jcode.design.ThemeBundleRegistry
import dev.jcode.design.jcIcon

import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.adaptive.JCodeWindowInfo
import dev.jcode.adaptive.JCodeWindowWidthClass
import dev.jcode.adaptive.rememberJCodeWindowInfo
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.config.ProjectConfig
import dev.jcode.core.config.WorkspaceConfig
import dev.jcode.core.distro.DistroBind
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.DistroProfile
import dev.jcode.core.distro.DistroWizardProgress
import dev.jcode.core.distro.ProotManager
import dev.jcode.core.distro.RootfsDownloader
import dev.jcode.core.distro.RootfsManager
import dev.jcode.core.distro.LspCatalogState
import dev.jcode.core.distro.SdkCatalogState
import dev.jcode.core.term.TerminalSessionManager
import dev.jcode.core.term.TerminalView
import dev.jcode.design.CommandRegistry
import dev.jcode.design.ThemeMode
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorPageKind
import dev.jcode.feature.editor.pane.EditorPane
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.explorer.ExplorerFeature
import dev.jcode.feature.explorer.ExplorerViewMode
import dev.jcode.feature.marketplace.ExtensionType
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.isUpdateAvailable
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.ScaffoldState
import dev.jcode.feature.onboarding.OnboardingFeature
import dev.jcode.feature.lspmanager.LspManagerFeature
import dev.jcode.feature.sdkmanager.SdkManagerFeature
import dev.jcode.feature.settings.SettingsFeature
import dev.jcode.workbench.dialog.NewItemDialog
import dev.jcode.workbench.dialog.OpenFolderTypeDialog
import dev.jcode.workbench.marketplace.ExtensionsPanel
import dev.jcode.workbench.LocalTerminalTapConfig
import dev.jcode.workbench.RightPanelTab
import dev.jcode.workbench.TerminalTapConfig
import dev.jcode.workbench.WorkbenchTool
import dev.jcode.fs.DEFAULT_SHARED_PROJECTS_ROOT
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.run.ProjectRunner
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceCrumb
import dev.jcode.fs.WorkspaceManager
import dev.jcode.fs.WorkspaceNodeType
import dev.jcode.fs.rememberOpenFolderLauncher
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull


@Composable
fun JCodeApp(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val windowInfo by rememberJCodeWindowInfo()
    val workspace by viewModel.currentWorkspace.collectAsStateWithLifecycle()
    val breadcrumb by viewModel.breadcrumb.collectAsStateWithLifecycle()
    val selectedProject by viewModel.selectedProject.collectAsStateWithLifecycle()
    val editorGroup by viewModel.editorGroup.collectAsStateWithLifecycle()
    val showNewItemDialog by viewModel.showNewItemDialog.collectAsStateWithLifecycle()
    val scaffoldState by viewModel.scaffoldState.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val workspaceConfig by viewModel.workspaceConfig.collectAsStateWithLifecycle()
    val projectConfig by viewModel.projectConfig.collectAsStateWithLifecycle()
    val effectiveConfig by viewModel.effectiveConfig.collectAsStateWithLifecycle()
    val workspaceConfigError by viewModel.workspaceConfigError.collectAsStateWithLifecycle()
    val projectConfigError by viewModel.projectConfigError.collectAsStateWithLifecycle()
    val environmentState by viewModel.environmentState.collectAsStateWithLifecycle()
    val sdkCatalogState by viewModel.sdkCatalogState.collectAsStateWithLifecycle()
    val lspCatalogState by viewModel.lspCatalogState.collectAsStateWithLifecycle()
    val autoSetupProgress by viewModel.autoSetupProgress.collectAsStateWithLifecycle(initialValue = DistroWizardProgress.Idle)
    val installedExtensions by viewModel.installedExtensions.collectAsStateWithLifecycle()
    val marketplaceEntries by viewModel.marketplaceEntries.collectAsStateWithLifecycle()
    val marketplaceBusy by viewModel.marketplaceBusy.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeBundleId by viewModel.themeBundleId.collectAsStateWithLifecycle()
    val iconBundleId by viewModel.iconBundleId.collectAsStateWithLifecycle()
    val formatterId by viewModel.formatterId.collectAsStateWithLifecycle()
    val railToolOrder by viewModel.railToolOrder.collectAsStateWithLifecycle()
    val terminalDoubleTapToFocus by viewModel.terminalDoubleTapToFocus.collectAsStateWithLifecycle()
    val tapContext = LocalContext.current
    val terminalTapConfig = TerminalTapConfig(
        doubleTapToFocus = terminalDoubleTapToFocus,
        onToken = { token ->
            val trimmed = token.trim().trimEnd('.', ',', ')', ']', '}', ';')
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                ProjectRunner.openInBrowser(tapContext, trimmed)
            } else {
                viewModel.openFileByGuestPath(token)
            }
        },
    )
    val snackbarHostState = remember { SnackbarHostState() }
    val openFolderLauncher = rememberOpenFolderLauncher(
        onFolderPicked = viewModel::openExternalFolder,
    )
    val openFolderTypePrompt by viewModel.openFolderTypePrompt.collectAsStateWithLifecycle()
    val environmentNotConfigured = environmentState.smokeTestPassed != true
    val showFirstRunEnvironmentScreen = environmentNotConfigured && !environmentState.firstRunSetupDeferred

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(selectedProject?.id, editorGroup.tabs.count { !it.isPage }) {
        // Page tabs (e.g. Settings) don't count as project content, so the bootstrap file still opens.
        if (selectedProject != null && editorGroup.tabs.none { !it.isPage }) {
            viewModel.ensureProjectBootstrapTab()
        }
    }

    CompositionLocalProvider(LocalTerminalTapConfig provides terminalTapConfig) {
    JCodeShell(
        modifier = modifier,
        windowInfo = windowInfo,
        workspace = workspace,
        selectedProject = selectedProject,
        editorGroup = editorGroup,
        workspaceConfig = workspaceConfig,
        projectConfig = projectConfig,
        effectiveConfig = effectiveConfig,
        workspaceConfigError = workspaceConfigError,
        projectConfigError = projectConfigError,
        environmentState = environmentState,
        autoSetupProgress = autoSetupProgress,
        sdkCatalogState = sdkCatalogState,
        lspCatalogState = lspCatalogState,
        workspaceManager = viewModel.workspaceManager,
        snackbarHostState = snackbarHostState,
        openFolderLauncher = openFolderLauncher,
        breadcrumb = breadcrumb,
        onCloseWorkspace = viewModel::closeWorkspace,
        onCloseProject = viewModel::closeProject,
        onCreateProject = viewModel::requestNew,
        onRemoveProject = viewModel::removeProject,
        onSelectProject = viewModel::selectProject,
        onOpenProject = viewModel::openProject,
        onRenameProject = viewModel::renameProject,
        onOpenFile = viewModel::openFile,
        onSelectEditorTab = viewModel::selectEditorTab,
        onCloseEditorTab = viewModel::closeEditorTab,
        onUpdateEditorFontSize = viewModel::updateEditorFontSize,
        onUpdateEditorTabSize = viewModel::updateEditorTabSize,
        onUpdateEditorWordWrap = viewModel::updateEditorWordWrap,
        onUpdateEditorMinimap = viewModel::updateEditorMinimap,
        onUpdateEditorLigatures = viewModel::updateEditorLigatures,
        onUpdateExplorerViewMode = viewModel::updateExplorerViewMode,
        themeMode = themeMode,
        onUpdateThemeMode = { viewModel.setThemeMode(it) },
        themeBundleId = themeBundleId,
        onUpdateThemeBundle = viewModel::setThemeBundle,
        iconBundleId = iconBundleId,
        onUpdateIconBundle = viewModel::setIconBundle,
        formatterId = formatterId,
        onSelectFormatter = viewModel::setFormatter,
        installedExtensions = installedExtensions,
        marketplaceEntries = marketplaceEntries,
        marketplaceBusy = marketplaceBusy,
        onRefreshMarketplace = viewModel::refreshMarketplace,
        onInstallExtension = viewModel::installExtension,
        onUninstallExtension = viewModel::uninstallExtension,
        onOpenWorkspaceConfig = viewModel::openWorkspaceConfigFile,
        onOpenProjectConfig = viewModel::openProjectConfigFile,
        onRefreshEnvironment = viewModel::refreshEnvironment,
        onOpenEnvironmentWizard = { viewModel.openEnvironmentPage() },
        onSelectDistro = { viewModel.selectWizardDistro(it) },
        onAutoSetup = viewModel::runAutoSetup,
        onRefreshSdkCatalog = viewModel::refreshSdkCatalog,
        onInstallSdkCatalogEntry = viewModel::installSdkCatalogEntry,
        onVerifySdkCatalogEntry = viewModel::verifySdkCatalogEntry,
        onUninstallSdkCatalogEntry = viewModel::uninstallSdkCatalogEntry,
        onRefreshLspCatalog = viewModel::refreshLspCatalog,
        onInstallLspCatalogEntry = viewModel::installLspCatalogEntry,
        onVerifyLspCatalogEntry = viewModel::verifyLspCatalogEntry,
        onUninstallLspCatalogEntry = viewModel::uninstallLspCatalogEntry,
        railToolOrder = railToolOrder,
        onReorderRail = viewModel::setRailToolOrder,
        onOpenSettingsPage = viewModel::openSettingsPage,
        terminalDoubleTapToFocus = terminalDoubleTapToFocus,
        onUpdateTerminalDoubleTapToFocus = viewModel::setTerminalDoubleTapToFocus,
    )
    }

    if (showNewItemDialog) {
        NewItemDialog(
            templates = templates,
            scaffoldState = scaffoldState,
            installedToolchains = sdkCatalogState.installedEntryIds,
            onDismiss = viewModel::dismissNewDialog,
            onConfirm = viewModel::createNewItem,
        )
    }

    openFolderTypePrompt?.let { path ->
        OpenFolderTypeDialog(
            folderName = path.displayName,
            onDismiss = viewModel::dismissOpenFolderPrompt,
            onConfirm = viewModel::resolveOpenFolderType,
        )
    }

    if (showFirstRunEnvironmentScreen) {
        OnboardingFeature.FirstRunEnvironmentScreen(
            environmentState = environmentState,
            autoSetupProgress = autoSetupProgress,
            onRefresh = { viewModel.refreshEnvironment() },
            onSelectDistro = { viewModel.selectWizardDistro(it) },
            onRunStep = { stepId -> viewModel.runEnvironmentStep(stepId) },
            onAutoSetup = { viewModel.runAutoSetup() },
            onSetupManualLater = { viewModel.deferFirstRunEnvironmentSetup() },
            onDismiss = { viewModel.deferFirstRunEnvironmentSetup() },
        )
    }

}

@Composable
private fun JCodeShell(
    windowInfo: JCodeWindowInfo,
    workspace: Workspace?,
    selectedProject: Project?,
    editorGroup: EditorGroup,
    workspaceConfig: WorkspaceConfig?,
    projectConfig: ProjectConfig?,
    effectiveConfig: EffectiveConfig,
    workspaceConfigError: String?,
    projectConfigError: String?,
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    sdkCatalogState: SdkCatalogState,
    lspCatalogState: LspCatalogState,
    workspaceManager: WorkspaceManager,
    snackbarHostState: SnackbarHostState,
    openFolderLauncher: ActivityResultLauncher<Uri?>,
    breadcrumb: List<WorkspaceCrumb>,
    onCloseWorkspace: () -> Unit,
    onCloseProject: () -> Unit,
    onCreateProject: () -> Unit,
    onRemoveProject: (Long) -> Unit,
    onSelectProject: (Long) -> Unit,
    onOpenProject: (Project) -> Unit,
    onRenameProject: (Long, String) -> Unit,
    onOpenFile: (dev.jcode.fs.FsNode) -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onUpdateEditorFontSize: (ConfigScope, Float) -> Unit,
    onUpdateEditorTabSize: (ConfigScope, Int) -> Unit,
    onUpdateEditorWordWrap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorMinimap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorLigatures: (ConfigScope, Boolean) -> Unit,
    onUpdateExplorerViewMode: (ConfigScope, String) -> Unit,
    themeMode: ThemeMode,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    themeBundleId: String,
    onUpdateThemeBundle: (String) -> Unit,
    iconBundleId: String,
    onUpdateIconBundle: (String) -> Unit,
    installedExtensions: List<InstalledExtension>,
    marketplaceEntries: List<MarketplaceEntry>,
    marketplaceBusy: Boolean,
    onRefreshMarketplace: () -> Unit,
    onInstallExtension: (MarketplaceEntry) -> Unit,
    onUninstallExtension: (String) -> Unit,
    formatterId: String,
    onSelectFormatter: (String) -> Unit,
    onOpenWorkspaceConfig: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onAutoSetup: () -> Unit,
    onSelectDistro: (DistroProfile) -> Unit,
    onRefreshSdkCatalog: () -> Unit,
    onInstallSdkCatalogEntry: (String) -> Unit,
    onVerifySdkCatalogEntry: (String) -> Unit,
    onUninstallSdkCatalogEntry: (String) -> Unit,
    onRefreshLspCatalog: () -> Unit,
    onInstallLspCatalogEntry: (String) -> Unit,
    onVerifyLspCatalogEntry: (String) -> Unit,
    onUninstallLspCatalogEntry: (String) -> Unit,
    railToolOrder: List<String>,
    onReorderRail: (List<String>) -> Unit,
    onOpenSettingsPage: () -> Unit,
    terminalDoubleTapToFocus: Boolean,
    onUpdateTerminalDoubleTapToFocus: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val appContext = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val focusRequester = remember { FocusRequester() }
    val compactDrawerState = rememberDrawerState(DrawerValue.Closed)
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val isMobileLandscapeMode = isLandscape && windowInfo.widthClass != JCodeWindowWidthClass.Expanded
    val usesModalWorkspace = !isLandscape || isMobileLandscapeMode
    val isPortraitMobileMode = usesModalWorkspace && windowInfo.widthClass == JCodeWindowWidthClass.Compact
    val hasLandscapeInspectorSidebar = isLandscape
    val canShowRightSidebar = true
    val leftSidebarWidth = if (windowInfo.widthClass == JCodeWindowWidthClass.Expanded) 284.dp else 236.dp
    val rightSidebarWidth = (configuration.screenWidthDp * 0.75f).dp
    val activeTab = editorGroup.activeTab
    val metrics = rememberEditorMetrics(activeTab)
    val showPersistentRail = !isPortraitMobileMode
    val portraitRightSidebarTabs = remember { RightPanelTab.entries.filter { it.enabled }.toSet() }

    var selectedTool by rememberSaveable { mutableStateOf(WorkbenchTool.Explorer) }
    // A previously-persisted selection may point at a now-hidden destination; fall back to Explorer.
    LaunchedEffect(Unit) { if (!selectedTool.available) selectedTool = WorkbenchTool.Explorer }

    // Syntax highlighting for the active file, driven by the installed language pack (pack-driven:
    // no matching pack => plain text). Re-tokenizes on every edit; spans are pushed as a GLYPH_COLOR
    // decoration layer that the editor renderer consumes.
    val systemDark = isSystemInDarkTheme()
    val editorDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }
    val highlightTab = editorGroup.activeTab
    LaunchedEffect(highlightTab?.id, installedExtensions, editorDark) {
        val state = highlightTab?.editorState ?: return@LaunchedEffect
        val fileName = highlightTab.filePath.name
        val lang = installedExtensions.firstNotNullOfOrNull { ext ->
            ext.language?.takeIf { it.matchesFile(fileName) }
        }
        if (lang == null) {
            state.updateDecorations { it.replaceLayer(Layer.GLYPH_COLOR, emptyList()) }
            return@LaunchedEffect
        }
        val palette = if (editorDark) TokenPalette.DARK else TokenPalette.LIGHT
        state.snapshot.collectLatest { snap ->
            val spans = withContext(Dispatchers.Default) {
                val text = runCatching { snap.readRangeAsUtf16(0, snap.byteLength) }.getOrDefault("")
                SyntaxHighlighter.highlight(text, lang, palette)
            }
            state.updateDecorations { it.replaceLayer(Layer.GLYPH_COLOR, spans) }
        }
    }
    val railTools = remember(railToolOrder) { orderedRailTools(railToolOrder) }
    // Settings opens as an in-editor page; every other tool drives the side panel.
    val onSelectWorkbenchTool: (WorkbenchTool) -> Unit = { tool ->
        if (tool == WorkbenchTool.Settings) onOpenSettingsPage() else selectedTool = tool
    }
    var leftSidebarExpanded by rememberSaveable(isLandscape, windowInfo.widthClass) {
        mutableStateOf(isLandscape && windowInfo.widthClass != JCodeWindowWidthClass.Compact)
    }
    val isPersistentLeftSidebarVisible = !usesModalWorkspace && leftSidebarExpanded
    var rightSidebarVisible by rememberSaveable(isLandscape, windowInfo.widthClass) {
        mutableStateOf(false)
    }
    var rightPanelTab by rememberSaveable {
        mutableStateOf(RightPanelTab.Terminal)
    }
    var commandPaletteVisible by rememberSaveable { mutableStateOf(false) }
    var terminalSessionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedTerminalSessionId by rememberSaveable { mutableStateOf("") }

    // Process-lifetime manager so terminal sessions (native PTY processes) survive Activity
    // recreation/backgrounding instead of being orphaned with the composition.
    val terminalSessionManager = remember(appContext) { TerminalSessionHost.manager(appContext) }
    val terminalShellCommand = effectiveConfig.terminal.shellLinux?.takeIf { it.isNotBlank() } ?: "/bin/bash --login"
    val terminalReady = environmentState.prootInstalled && environmentState.distroInstalled == true

    // Core session spawner shared by the manual "+" terminal and the Run pipeline. Returns the new
    // session (already tracked + foregrounded) or null if it could not start (snackbar shown).
    fun spawnTerminalSession(label: String? = null): dev.jcode.core.term.TerminalSessionManager.Session? {
        if (!terminalReady) {
            scope.launch {
                snackbarHostState.showSnackbar("Finish environment setup before opening the terminal.")
            }
            return null
        }
        // Mount the whole projects tree at /workspace so the shell can navigate the full
        // Workspace/Project hierarchy, and start in the open Project (or the open User Workspace when
        // no project is selected), mapped to its path under /workspace:
        //   /workspace                            (Default, nothing open)
        //   /workspace/{project}                  (Default, project open)
        //   /workspace/{workspace}                (User Workspace, no project)
        //   /workspace/{workspace}/{project}      (User Workspace, project open)
        // A SAF project lives outside the projects root, so it falls back to /workspace.
        val projectsRoot = DEFAULT_SHARED_PROJECTS_ROOT.trimEnd('/')
        fun guestPathFor(hostPath: String?): String? = when {
            hostPath.isNullOrBlank() -> null
            hostPath == projectsRoot -> "/workspace"
            hostPath.startsWith("$projectsRoot/") -> "/workspace" + hostPath.removePrefix(projectsRoot)
            else -> null
        }
        val projectHost = (selectedProject?.fsPath as? FsPath.Local)?.file?.absolutePath
        val workspaceHost = workspace?.takeIf { breadcrumb.size > 1 }?.rootPath
        val terminalWorkdir = guestPathFor(projectHost)
            ?: guestPathFor(workspaceHost)
            ?: "/workspace"
        val session = terminalSessionManager.createSession(
            distroId = environmentState.runtime.selectedDistro.id,
            binds = listOf(DistroBind(host = DEFAULT_SHARED_PROJECTS_ROOT, target = "/workspace")),
            workdir = terminalWorkdir,
            user = "root", // Use root since jcode user may not exist in minimal rootfs
            shellCommand = terminalShellCommand,
            rootfsArch = environmentState.runtime.selectedDistro.arch,
            label = label,
        )
        if (session == null) {
            scope.launch {
                val message = if (terminalSessionManager.sessionCount >= terminalSessionManager.maxSessions) {
                    "Maximum terminal sessions reached (${terminalSessionManager.maxSessions})."
                } else {
                    "Failed to start terminal session."
                }
                snackbarHostState.showSnackbar(message)
            }
            return null
        }
        terminalSessionIds = terminalSessionIds + session.id
        selectedTerminalSessionId = session.id
        terminalSessionManager.switchSession(session.id)
        // Hold the foreground service while this terminal is alive (keeps the process from being
        // killed/frozen in the background so the shell keeps running).
        TerminalSessionHost.onSessionStarted(appContext, session.id)
        return session
    }

    // Manual "+" terminal: spawn a plain interactive shell (result intentionally ignored).
    fun createTerminalSession() {
        spawnTerminalSession()
    }

    fun selectTerminalSession(sessionId: String) {
        selectedTerminalSessionId = sessionId
        terminalSessionManager.switchSession(sessionId)
    }

    // Run state: the URL of the most recent run (for "Open in browser") and whether we're still
    // waiting for the dev frontend to come up. [runSessionIds] are the run's terminals (e.g. Server +
    // Client), torn down and respawned on each run and stopped together by handleStopRun.
    var runUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var runInProgress by remember { mutableStateOf(false) }
    var runSessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    var runPollJob by remember { mutableStateOf<Job?>(null) }

    // Drop a terminal tab when its shell exits on its own (e.g. `exit`, or a finished one-shot command).
    // The manager has already reaped the PTY + released the foreground hold; here we just sync the UI.
    DisposableEffect(Unit) {
        TerminalSessionHost.setUiExitListener { exitedId ->
            terminalSessionIds = terminalSessionIds.filterNot { it == exitedId }
            if (selectedTerminalSessionId == exitedId) {
                selectedTerminalSessionId = terminalSessionIds.lastOrNull().orEmpty()
                selectedTerminalSessionId.takeIf { it.isNotEmpty() }
                    ?.let { terminalSessionManager.switchSession(it) }
            }
            if (exitedId in runSessionIds) {
                runSessionIds = runSessionIds.filterNot { it == exitedId }
                if (runSessionIds.isEmpty()) {
                    runInProgress = false
                    runUrl = null
                }
            }
        }
        onDispose { TerminalSessionHost.setUiExitListener(null) }
    }

    // Build & Run the selected project: spawn a dedicated terminal in the right drawer, stream the
    // compile/run output into it, then open the device browser once the server is reachable.
    fun handleRun() {
        val project = selectedProject
        if (!terminalReady) {
            scope.launch { snackbarHostState.showSnackbar("Finish environment setup before running.") }
            return
        }
        if (project == null) {
            scope.launch { snackbarHostState.showSnackbar("Open a project to build & run.") }
            return
        }
        val plan = ProjectRunner.detectRunPlan(project)
        if (plan == null) {
            scope.launch {
                snackbarHostState.showSnackbar("No run configuration detected for this project.")
            }
            return
        }
        rightPanelTab = RightPanelTab.Terminal
        rightSidebarVisible = true
        // Tear down any previous run terminals (frees the dev ports, avoids tab accumulation), then
        // spawn one fresh terminal per plan step in order - e.g. "Server" then "Client" - each running
        // its own script. They run side by side; the browser opens the dev frontend once it is up.
        val previous = runSessionIds
        previous.forEach { id ->
            terminalSessionManager.closeSession(id)
            TerminalSessionHost.onSessionStopped(id)
        }
        if (previous.isNotEmpty()) {
            terminalSessionIds = terminalSessionIds.filterNot { it in previous }
        }
        val startedIds = mutableListOf<String>()
        for (terminal in plan.terminals) {
            val session = spawnTerminalSession(label = terminal.label) ?: break
            terminalSessionManager.sendInput(session.id, ProjectRunner.runInvocation(project, terminal) + "\n")
            startedIds += session.id
        }
        if (startedIds.isEmpty()) return
        runSessionIds = startedIds
        selectTerminalSession(startedIds.last()) // focus the frontend terminal
        runUrl = plan.url
        runInProgress = true
        // Cancel any in-flight poll from a previous run so the browser only opens once.
        runPollJob?.cancel()
        runPollJob = scope.launch {
            val up = ProjectRunner.awaitServer(plan.readyPort)
            runInProgress = false
            if (up) {
                ProjectRunner.openInBrowser(appContext, plan.url)
            } else {
                snackbarHostState.showSnackbar("Dev server didn't start in time; check the run terminals.")
            }
        }
    }

    // Stop the current run: Ctrl-C the run terminal (graceful server/build shutdown) and reset run
    // state. The terminal session is kept so its output stays visible and a re-run can reuse it.
    fun handleStopRun() {
        runPollJob?.cancel()
        runSessionIds.forEach { id ->
            if (id in terminalSessionIds && terminalSessionManager.getSession(id) != null) {
                terminalSessionManager.sendInput(id, byteArrayOf(0x03)) // Ctrl-C / SIGINT
            }
        }
        runInProgress = false
        runUrl = null
    }

    fun closeTerminalSession(sessionId: String) {
        val remaining = terminalSessionIds.filterNot { it == sessionId }
        terminalSessionManager.closeSession(sessionId)
        TerminalSessionHost.onSessionStopped(sessionId)
        terminalSessionIds = remaining
        if (selectedTerminalSessionId == sessionId) {
            if (remaining.isNotEmpty()) {
                selectedTerminalSessionId = remaining.last()
                terminalSessionManager.switchSession(selectedTerminalSessionId)
            } else {
                selectedTerminalSessionId = ""
            }
        }
    }

    fun closeAllTerminalSessions() {
        // Kill every live PTY (the tracked ids may drift from the manager's sessions), then clear state.
        val ids = (terminalSessionIds + terminalSessionManager.sessions.keys).distinct()
        ids.forEach { id ->
            terminalSessionManager.closeSession(id)
            TerminalSessionHost.onSessionStopped(id)
        }
        terminalSessionIds = emptyList()
        selectedTerminalSessionId = ""
    }

    // Set when the user explicitly closes the project: suppresses the convenience auto-start so the
    // terminal stays empty (a clean slate). Reset once a project/workspace is opened again.
    var terminalAutoStartSuppressed by rememberSaveable { mutableStateOf(false) }

    // Closing a project tears down its working context: kill its terminal sessions (the VM clears the
    // editor + unregisters the project), leaving a clean slate.
    val handleCloseProject: () -> Unit = {
        closeAllTerminalSessions()
        terminalAutoStartSuppressed = true
        onCloseProject()
    }

    LaunchedEffect(selectedProject?.id, breadcrumb.size) {
        if (selectedProject != null || breadcrumb.size > 1) terminalAutoStartSuppressed = false
    }

    var terminalAutoStarted by rememberSaveable { mutableStateOf(false) }

    // Reconcile restored session ids with the process-lifetime manager's live sessions. Covers:
    //  - Activity recreation: the manager survived, so adopt its live sessions (and their scrollback).
    //  - Process restart: ids may be restored from saved state but the PTYs are gone -> clear stale
    //    ids and allow auto-start to spawn a fresh shell.
    LaunchedEffect(Unit) {
        val live = terminalSessionManager.sessions.keys.toList()
        if (live.isNotEmpty()) {
            terminalSessionIds = live
            if (selectedTerminalSessionId !in live) selectedTerminalSessionId = live.last()
            terminalSessionManager.switchSession(selectedTerminalSessionId)
            terminalAutoStarted = true
        } else {
            if (terminalSessionIds.isNotEmpty()) {
                terminalSessionIds = emptyList()
                selectedTerminalSessionId = ""
            }
            terminalAutoStarted = false
        }
    }

    // Auto-start terminal session on launch if environment is ready
    LaunchedEffect(terminalReady, terminalAutoStarted) {
        if (terminalReady && !terminalAutoStarted && !terminalAutoStartSuppressed && terminalSessionIds.isEmpty()) {
            terminalAutoStarted = true
            createTerminalSession()
        }
    }

    DisposableEffect(onCreateProject, openFolderLauncher, onOpenWorkspaceConfig, onOpenProjectConfig, onAutoSetup, selectedProject?.id) {
        CommandRegistry.clear()
        CommandRegistry.register(
            id = "workspace.newFolder",
            title = "New Folder",
            group = "Workspace",
            action = onCreateProject,
        )
        CommandRegistry.register(
            id = "workspace.openFolder",
            title = "Open Folder",
            group = "Workspace",
            action = { openFolderLauncher.launch(null) },
        )
        CommandRegistry.register(
            id = "workspace.autoSetupEnvironment",
            title = "Auto-Setup Environment",
            group = "Workspace",
            action = onAutoSetup,
        )
        CommandRegistry.register(
            id = "workbench.focusExplorer",
            title = "Focus Explorer",
            group = "Workbench",
            action = { selectedTool = WorkbenchTool.Explorer },
        )
        CommandRegistry.register(
            id = "workbench.showSearch",
            title = "Show Search Placeholder",
            group = "Workbench",
            action = { selectedTool = WorkbenchTool.Search },
        )
        CommandRegistry.register(
            id = "settings.openPage",
            title = "Open Settings",
            group = "Settings",
            action = onOpenSettingsPage,
        )
        CommandRegistry.register(
            id = "settings.openWorkspaceYaml",
            title = "Open Workspace YAML",
            group = "Settings",
            action = onOpenWorkspaceConfig,
        )
        CommandRegistry.register(
            id = "settings.openProjectYaml",
            title = "Open Project YAML",
            group = "Settings",
            action = onOpenProjectConfig,
            whenPredicate = { selectedProject?.fsPath is FsPath.Local },
        )
        onDispose { }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    LaunchedEffect(rightSidebarVisible, rightPanelTab, terminalReady) {
        if (rightSidebarVisible && rightPanelTab == RightPanelTab.Terminal && terminalReady &&
            !terminalAutoStartSuppressed && terminalSessionIds.isEmpty()
        ) {
            createTerminalSession()
        }
    }

    val drawerContent: @Composable () -> Unit = {
        ModalDrawerSheet(
            modifier = Modifier.width(332.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surface,
        ) {
            WorkspacePanel(
                selectedTool = selectedTool,
                workspace = workspace,
                selectedProject = selectedProject,
                editorGroup = editorGroup,
                effectiveConfig = effectiveConfig,
                environmentState = environmentState,
                autoSetupProgress = autoSetupProgress,
                sdkCatalogState = sdkCatalogState,
                lspCatalogState = lspCatalogState,
                workspaceManager = workspaceManager,
                windowInfo = windowInfo,
                modifier = Modifier.fillMaxHeight(),
                breadcrumb = breadcrumb,
                onCloseWorkspace = onCloseWorkspace,
                onCloseProject = handleCloseProject,
                onCreateProject = onCreateProject,
                onRemoveProject = onRemoveProject,
                onSelectProject = onSelectProject,
                onOpenProject = onOpenProject,
                onRenameProject = onRenameProject,
                onSelectTool = onSelectWorkbenchTool,
                onOpenExternalFolder = { openFolderLauncher.launch(null) },
                onOpenFile = onOpenFile,
                onOpenProjectConfig = onOpenProjectConfig,
                onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                onAutoSetup = onAutoSetup,
                onRefreshSdkCatalog = onRefreshSdkCatalog,
                onInstallSdkCatalogEntry = onInstallSdkCatalogEntry,
                onVerifySdkCatalogEntry = onVerifySdkCatalogEntry,
                onUninstallSdkCatalogEntry = onUninstallSdkCatalogEntry,
                onRefreshLspCatalog = onRefreshLspCatalog,
                onInstallLspCatalogEntry = onInstallLspCatalogEntry,
                onVerifyLspCatalogEntry = onVerifyLspCatalogEntry,
                onUninstallLspCatalogEntry = onUninstallLspCatalogEntry,
                onRun = ::handleRun,
                onStopRun = ::handleStopRun,
                runUrl = runUrl,
                runInProgress = runInProgress,
                onOpenRunInBrowser = { runUrl?.let { ProjectRunner.openInBrowser(appContext, it) } },
                onSnackbar = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
                themeBundleId = themeBundleId,
                onUpdateThemeBundle = onUpdateThemeBundle,
                iconBundleId = iconBundleId,
                onUpdateIconBundle = onUpdateIconBundle,
                installedExtensions = installedExtensions,
                marketplaceEntries = marketplaceEntries,
                marketplaceBusy = marketplaceBusy,
                onRefreshMarketplace = onRefreshMarketplace,
                onInstallExtension = onInstallExtension,
                onUninstallExtension = onUninstallExtension,
            )
        }
    }

    val content: @Composable () -> Unit = {
        Scaffold(
            modifier = Modifier
                .fillMaxSize()
                .focusRequester(focusRequester)
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if ((event.isCtrlPressed || event.isMetaPressed) && event.isShiftPressed && event.key == Key.P) {
                        commandPaletteVisible = true
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Escape && commandPaletteVisible) {
                        commandPaletteVisible = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                WorkbenchStatusBar(
                    metrics = metrics,
                    activeTab = activeTab,
                    selectedProject = selectedProject,
                    workspace = workspace,
                    effectiveConfig = effectiveConfig,
                    windowInfo = windowInfo,
                )
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                if (showPersistentRail) {
                    DockRail(
                        tools = railTools,
                        selectedTool = selectedTool,
                        onToolSelected = { tool ->
                            if (tool == WorkbenchTool.Settings) {
                                onOpenSettingsPage()
                            } else {
                                selectedTool = tool
                                if (usesModalWorkspace) {
                                    scope.launch { compactDrawerState.open() }
                                } else {
                                    leftSidebarExpanded = true
                                }
                            }
                        },
                        onReorder = onReorderRail,
                        modifier = Modifier.fillMaxHeight(),
                    )
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    EditorWorkspace(
                        windowInfo = windowInfo,
                        workspace = workspace,
                        selectedProject = selectedProject,
                        editorGroup = editorGroup,
                        activeTab = activeTab,
                        leftSidebarExpanded = isPersistentLeftSidebarVisible,
                        canShowRightSidebar = canShowRightSidebar,
                        rightSidebarVisible = rightSidebarVisible,
                        modifier = Modifier.fillMaxSize(),
                        onToggleLeftSidebar = {
                            if (usesModalWorkspace) {
                                scope.launch { compactDrawerState.open() }
                            } else {
                                leftSidebarExpanded = !leftSidebarExpanded
                            }
                        },
                        onToggleRightSidebar = {
                            if (usesModalWorkspace) {
                                rightSidebarVisible = !rightSidebarVisible
                            } else if (canShowRightSidebar) {
                                rightSidebarVisible = !rightSidebarVisible
                            }
                        },
                        onRun = ::handleRun,
                        onShowTerminal = {
                            rightPanelTab = RightPanelTab.Terminal
                            rightSidebarVisible = true
                        },
                        onSelectEditorTab = onSelectEditorTab,
                        onCloseEditorTab = onCloseEditorTab,
                        onOpenFileRequest = {
                            selectedTool = WorkbenchTool.Explorer
                            if (usesModalWorkspace) {
                                scope.launch { compactDrawerState.open() }
                            } else {
                                leftSidebarExpanded = true
                            }
                        },
                        languageActionsEnabled = run {
                            val name = editorGroup.activeTab?.filePath?.name
                            name != null && installedExtensions.any { it.language?.matchesFile(name) == true }
                        },
                        onEditorLanguageAction = { action, word ->
                            // Semantic actions need a language server (deferred); surface intent.
                            scope.launch {
                                val target = if (word.isNotBlank()) " \"$word\"" else ""
                                snackbarHostState.showSnackbar("${action.label}$target — needs a language server (coming soon)")
                            }
                        },
                        editorPageContent = { tab ->
                            when (tab.pageKind) {
                                EditorPageKind.Settings -> SettingsFeature.Content(
                                    effectiveConfig = effectiveConfig,
                                    workspaceConfig = workspaceConfig,
                                    projectConfig = projectConfig,
                                    workspaceError = workspaceConfigError,
                                    projectError = projectConfigError,
                                    projectOverridesAvailable = selectedProject?.fsPath is FsPath.Local,
                                    environmentState = environmentState,
                                    onOpenWorkspaceConfig = onOpenWorkspaceConfig,
                                    onOpenProjectConfig = onOpenProjectConfig,
                                    onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                                    onRefreshEnvironment = onRefreshEnvironment,
                                    onUpdateFontSize = onUpdateEditorFontSize,
                                    onUpdateTabSize = onUpdateEditorTabSize,
                                    onUpdateWordWrap = onUpdateEditorWordWrap,
                                    onUpdateMinimap = onUpdateEditorMinimap,
                                    onUpdateLigatures = onUpdateEditorLigatures,
                                    onUpdateExplorerViewMode = onUpdateExplorerViewMode,
                                    themeMode = themeMode,
                                    onUpdateThemeMode = onUpdateThemeMode,
                                    themeBundleId = themeBundleId,
                                    onUpdateThemeBundle = onUpdateThemeBundle,
                                    iconBundleId = iconBundleId,
                                    onUpdateIconBundle = onUpdateIconBundle,
                                    formatterId = formatterId,
                                    formatterOptions = listOf("builtin" to "Built-in") +
                                        installedExtensions
                                            .filter { it.type == ExtensionType.Formatter }
                                            .map { it.id to it.name },
                                    onSelectFormatter = onSelectFormatter,
                                    terminalDoubleTapToFocus = terminalDoubleTapToFocus,
                                    onUpdateTerminalDoubleTapToFocus = onUpdateTerminalDoubleTapToFocus,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                EditorPageKind.Environment -> OnboardingFeature.EnvironmentSetupPage(
                                    environmentState = environmentState,
                                    autoSetupProgress = autoSetupProgress,
                                    onRefresh = onRefreshEnvironment,
                                    onSelectDistro = onSelectDistro,
                                    onAutoSetup = onAutoSetup,
                                )
                                EditorPageKind.None -> Unit
                            }
                        },
                    )

                    if (isPersistentLeftSidebarVisible) {
                        WorkspacePanel(
                            selectedTool = selectedTool,
                            workspace = workspace,
                            selectedProject = selectedProject,
                            editorGroup = editorGroup,
                            effectiveConfig = effectiveConfig,
                            environmentState = environmentState,
                            autoSetupProgress = autoSetupProgress,
                            sdkCatalogState = sdkCatalogState,
                            lspCatalogState = lspCatalogState,
                            workspaceManager = workspaceManager,
                            windowInfo = windowInfo,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .fillMaxHeight()
                                .width(leftSidebarWidth)
                                .zIndex(1f),
                            breadcrumb = breadcrumb,
                            onCloseWorkspace = onCloseWorkspace,
                            onCloseProject = handleCloseProject,
                            onCreateProject = onCreateProject,
                            onRemoveProject = onRemoveProject,
                            onSelectProject = onSelectProject,
                            onOpenProject = onOpenProject,
                            onRenameProject = onRenameProject,
                            onSelectTool = onSelectWorkbenchTool,
                            onOpenExternalFolder = { openFolderLauncher.launch(null) },
                            onOpenFile = onOpenFile,
                            onOpenProjectConfig = onOpenProjectConfig,
                            onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                            onAutoSetup = onAutoSetup,
                            onRefreshSdkCatalog = onRefreshSdkCatalog,
                            onInstallSdkCatalogEntry = onInstallSdkCatalogEntry,
                            onVerifySdkCatalogEntry = onVerifySdkCatalogEntry,
                            onUninstallSdkCatalogEntry = onUninstallSdkCatalogEntry,
                            onRefreshLspCatalog = onRefreshLspCatalog,
                            onInstallLspCatalogEntry = onInstallLspCatalogEntry,
                            onVerifyLspCatalogEntry = onVerifyLspCatalogEntry,
                            onUninstallLspCatalogEntry = onUninstallLspCatalogEntry,
                            onRun = ::handleRun,
                            onStopRun = ::handleStopRun,
                            runUrl = runUrl,
                            runInProgress = runInProgress,
                            onOpenRunInBrowser = { runUrl?.let { ProjectRunner.openInBrowser(appContext, it) } },
                            onSnackbar = { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            },
                            themeBundleId = themeBundleId,
                            onUpdateThemeBundle = onUpdateThemeBundle,
                            iconBundleId = iconBundleId,
                            onUpdateIconBundle = onUpdateIconBundle,
                            installedExtensions = installedExtensions,
                            marketplaceEntries = marketplaceEntries,
                            marketplaceBusy = marketplaceBusy,
                            onRefreshMarketplace = onRefreshMarketplace,
                            onInstallExtension = onInstallExtension,
                            onUninstallExtension = onUninstallExtension,
                        )
                    }
                }

                if (hasLandscapeInspectorSidebar && !usesModalWorkspace && rightSidebarVisible) {
                    WorkbenchRightSidebar(
                        selectedTab = rightPanelTab,
                        selectedProject = selectedProject,
                        terminalSessionIds = terminalSessionIds,
                        selectedTerminalSessionId = selectedTerminalSessionId,
                        activeTerminalPty = terminalSessionManager.getSession(selectedTerminalSessionId)?.pty,
                        terminalSessionFor = { id -> terminalSessionManager.getSession(id) },
                        terminalReady = terminalReady,
                        onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(rightSidebarWidth),
                        onTabSelected = { rightPanelTab = it },
                        onSelectTerminalSession = ::selectTerminalSession,
                        onAddTerminalSession = ::createTerminalSession,
                        onRemoveTerminalSession = ::closeTerminalSession,
                        onHide = { rightSidebarVisible = false },
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
        if (usesModalWorkspace) {
            ModalNavigationDrawer(
                drawerState = compactDrawerState,
                drawerContent = drawerContent,
                content = content,
            )
        } else {
            content()
        }

        if (usesModalWorkspace) {
            AnimatedVisibility(
                visible = rightSidebarVisible,
                enter = fadeIn() + slideInHorizontally(initialOffsetX = { it }),
                exit = fadeOut() + slideOutHorizontally(targetOffsetX = { it }),
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = 0.36f))
                            .clickable { rightSidebarVisible = false },
                    )
                    WorkbenchRightSidebar(
                        selectedTab = rightPanelTab.takeIf { it in portraitRightSidebarTabs } ?: RightPanelTab.Terminal,
                        selectedProject = selectedProject,
                        terminalSessionIds = terminalSessionIds,
                        selectedTerminalSessionId = selectedTerminalSessionId,
                        activeTerminalPty = terminalSessionManager.getSession(selectedTerminalSessionId)?.pty,
                        terminalSessionFor = { id -> terminalSessionManager.getSession(id) },
                        terminalReady = terminalReady,
                        onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                        onTabSelected = { rightPanelTab = it },
                        onSelectTerminalSession = ::selectTerminalSession,
                        onAddTerminalSession = ::createTerminalSession,
                        onRemoveTerminalSession = ::closeTerminalSession,
                        onHide = { rightSidebarVisible = false },
                    )
                }
            }
        }

        CommandPalette(
            visible = commandPaletteVisible,
            compact = usesModalWorkspace,
            onDismiss = { commandPaletteVisible = false },
        )
    }
}

/**
 * Merge the persisted rail order (tool names) with the full tool set: saved tools keep their order,
 * any tool missing from the saved order (e.g. a newly added one) is appended, unknown names dropped.
 */
private fun orderedRailTools(order: List<String>): List<WorkbenchTool> {
    val available = WorkbenchTool.entries.filter { it.available }
    val byName = available.associateBy { it.name }
    val result = LinkedHashSet<WorkbenchTool>()
    order.forEach { name -> byName[name]?.let(result::add) }
    result.addAll(available)
    return result.toList()
}

/**
 * Minified left rail: icon-only buttons (titles shown as long-press tooltips), vertically
 * scrollable, with long-press-and-drag reordering that persists via [onReorder].
 */
@Composable
private fun DockRail(
    tools: List<WorkbenchTool>,
    selectedTool: WorkbenchTool,
    onToolSelected: (WorkbenchTool) -> Unit,
    onReorder: (List<String>) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = 8.dp
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val scrollState = rememberScrollState()

    // Local working order so a drag reorders instantly; it re-seeds when the persisted order changes.
    var items by remember(tools) { mutableStateOf(tools) }
    var draggingTool by remember { mutableStateOf<WorkbenchTool?>(null) }
    // Tooltip shows on the picked-up icon only until the drag actually moves (keeps the title popup
    // from lingering at the now-stale anchor while the icon follows the finger).
    var tooltipTool by remember { mutableStateOf<WorkbenchTool?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var itemHeightPx by remember { mutableFloatStateOf(0f) }
    // The long-lived pointerInput captures these once per gesture start; keep them fresh so a tap
    // after a config change (e.g. rotation flipping modal vs. persistent layout) routes correctly.
    val currentOnToolSelected by rememberUpdatedState(onToolSelected)
    val currentOnReorder by rememberUpdatedState(onReorder)

    fun moveTool(from: Int, to: Int) {
        if (to < 0 || to >= items.size || from == to) return
        items = items.toMutableList().also { it.add(to, it.removeAt(from)) }
        currentOnReorder(items.map { it.name })
    }

    Surface(
        modifier = modifier.width(60.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .verticalScroll(scrollState)
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(spacing),
        ) {
            items.forEachIndexed { index, tool ->
                key(tool) {
                    val isDragging = draggingTool == tool
                    RailButton(
                        tool = tool,
                        selected = selectedTool == tool,
                        dragging = isDragging,
                        showTooltip = tooltipTool == tool,
                        onActivate = { currentOnToolSelected(tool) },
                        onMoveUp = if (index > 0) ({ moveTool(index, index - 1) }) else null,
                        onMoveDown = if (index < items.size - 1) ({ moveTool(index, index + 1) }) else null,
                        modifier = Modifier
                            .onSizeChanged { size -> if (size.height > 0) itemHeightPx = size.height.toFloat() }
                            .zIndex(if (isDragging) 1f else 0f)
                            .graphicsLayer { translationY = if (isDragging) dragOffsetY else 0f }
                            .pointerInput(tools) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    var up: PointerInputChange? = null
                                    val timedOut = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                        up = waitForUpOrCancellation()
                                    } == null
                                    if (!timedOut) {
                                        // Released/cancelled before the long-press fired: a plain tap selects
                                        // (ignored while another icon is mid-drag).
                                        if (up != null && draggingTool == null) currentOnToolSelected(tool)
                                        return@awaitEachGesture
                                    }
                                    // Only one icon may own the drag at a time (guards multi-touch).
                                    if (draggingTool != null) return@awaitEachGesture
                                    // Long press: pick the icon up and let it follow the finger.
                                    draggingTool = tool
                                    tooltipTool = tool
                                    dragOffsetY = 0f
                                    val startItems = items
                                    while (true) {
                                        val event = awaitPointerEvent()
                                        val change = event.changes.firstOrNull { it.id == down.id }
                                        if (change == null || !change.pressed) {
                                            change?.consume()
                                            break
                                        }
                                        val dy = change.positionChange().y
                                        if (dy != 0f) {
                                            change.consume()
                                            tooltipTool = null
                                            dragOffsetY += dy
                                            val pitch = itemHeightPx + spacingPx
                                            if (pitch > 0f) {
                                                val curIndex = items.indexOf(tool)
                                                if (curIndex >= 0) {
                                                    val target = (curIndex + (dragOffsetY / pitch).roundToInt())
                                                        .coerceIn(0, items.size - 1)
                                                    if (target != curIndex) {
                                                        items = items.toMutableList().also { list ->
                                                            list.add(target, list.removeAt(curIndex))
                                                        }
                                                        dragOffsetY -= (target - curIndex) * pitch
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    draggingTool = null
                                    tooltipTool = null
                                    dragOffsetY = 0f
                                    // Persist only when the order actually changed (avoids a disk write
                                    // on an accidental long-press that never moved).
                                    if (items != startItems) currentOnReorder(items.map { it.name })
                                }
                            },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RailButton(
    tool: WorkbenchTool,
    selected: Boolean,
    dragging: Boolean,
    showTooltip: Boolean,
    onActivate: () -> Unit,
    onMoveUp: (() -> Unit)?,
    onMoveDown: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val tooltipState = rememberTooltipState(isPersistent = true)
    LaunchedEffect(showTooltip) {
        if (showTooltip) tooltipState.show() else tooltipState.dismiss()
    }
    val container = when {
        dragging -> MaterialTheme.colorScheme.primary.copy(alpha = 0.24f)
        selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
        else -> Color.Transparent
    }
    val content = if (selected || dragging) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    // Keep the rail operable for accessibility services: the pointer gesture handles sighted touch,
    // while these semantics expose activation and keyboard/TalkBack reordering.
    val moveActions = remember(onMoveUp, onMoveDown) {
        buildList {
            onMoveUp?.let { add(CustomAccessibilityAction("Move up") { it(); true }) }
            onMoveDown?.let { add(CustomAccessibilityAction("Move down") { it(); true }) }
        }
    }

    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(tool.label) } },
        state = tooltipState,
        enableUserInput = false,
        modifier = modifier.semantics(mergeDescendants = true) {
            role = Role.Button
            onClick(label = "Open ${tool.label}") { onActivate(); true }
            if (moveActions.isNotEmpty()) customActions = moveActions
        },
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = CircleShape,
            color = container,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = jcIcon(tool.icon),
                    contentDescription = tool.label,
                    tint = content,
                )
            }
        }
    }
}

@Composable
private fun WorkbenchIconActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .size(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(16.dp),
                tint = contentColor,
            )
        }
    }
}

@Composable
private fun WorkbenchActionButton(
    text: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    val containerColor = if (active) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
    } else {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
    }
    val contentColor = if (active) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = containerColor,
    ) {
        Box(
            modifier = Modifier
                .height(32.dp)
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun WorkspacePanel(
    selectedTool: WorkbenchTool,
    workspace: Workspace?,
    selectedProject: Project?,
    editorGroup: EditorGroup,
    effectiveConfig: EffectiveConfig,
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    sdkCatalogState: SdkCatalogState,
    lspCatalogState: LspCatalogState,
    workspaceManager: WorkspaceManager,
    windowInfo: JCodeWindowInfo,
    breadcrumb: List<WorkspaceCrumb>,
    onCloseWorkspace: () -> Unit,
    onCloseProject: () -> Unit,
    onCreateProject: () -> Unit,
    onRemoveProject: (Long) -> Unit,
    onSelectProject: (Long) -> Unit,
    onOpenProject: (Project) -> Unit,
    onRenameProject: (Long, String) -> Unit,
    onSelectTool: (WorkbenchTool) -> Unit,
    onOpenExternalFolder: () -> Unit,
    onOpenFile: (dev.jcode.fs.FsNode) -> Unit,
    onOpenProjectConfig: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onAutoSetup: () -> Unit,
    onRefreshSdkCatalog: () -> Unit,
    onInstallSdkCatalogEntry: (String) -> Unit,
    onVerifySdkCatalogEntry: (String) -> Unit,
    onUninstallSdkCatalogEntry: (String) -> Unit,
    onRefreshLspCatalog: () -> Unit,
    onInstallLspCatalogEntry: (String) -> Unit,
    onVerifyLspCatalogEntry: (String) -> Unit,
    onUninstallLspCatalogEntry: (String) -> Unit,
    onRun: () -> Unit,
    onStopRun: () -> Unit,
    runUrl: String?,
    runInProgress: Boolean,
    onOpenRunInBrowser: () -> Unit,
    onSnackbar: (String) -> Unit,
    themeBundleId: String,
    onUpdateThemeBundle: (String) -> Unit,
    iconBundleId: String,
    onUpdateIconBundle: (String) -> Unit,
    installedExtensions: List<InstalledExtension>,
    marketplaceEntries: List<MarketplaceEntry>,
    marketplaceBusy: Boolean,
    onRefreshMarketplace: () -> Unit,
    onInstallExtension: (MarketplaceEntry) -> Unit,
    onUninstallExtension: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // breadcrumb depth tells us whether we're inside a User Workspace (a container of
            // projects) vs the Default Workspace (single open project, no roster).
            val inUserWorkspace = breadcrumb.size > 1
            WorkspaceHeader(
                selectedTool = selectedTool,
                workspace = workspace,
                selectedProject = selectedProject,
                inUserWorkspace = inUserWorkspace,
                onSelectTool = onSelectTool,
                onCreateProject = onCreateProject,
                onOpenExternalFolder = onOpenExternalFolder,
                onCloseWorkspace = onCloseWorkspace,
                onCloseProject = onCloseProject,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            AnimatedContent(
                targetState = selectedTool,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "workspace-tool",
            ) { tool ->
                when (tool) {
                    WorkbenchTool.Explorer -> {
                        val rosterProjects = workspace?.projects.orEmpty()
                        Column(modifier = Modifier.fillMaxSize()) {
                            if (inUserWorkspace) {
                                ProjectRoster(
                                    projects = rosterProjects,
                                    selectedProjectId = selectedProject?.id ?: -1L,
                                    onOpenProject = onOpenProject,
                                    onRenameProject = onRenameProject,
                                    onRemoveProject = onRemoveProject,
                                    onOpenProjectSettings = { id -> onSelectProject(id); onOpenProjectConfig() },
                                    onCreateProject = onCreateProject,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }

                            when {
                                selectedProject != null -> {
                                    // Key on the project id so roster mutations never remount/reload the tree.
                                    key(selectedProject.id) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            ExplorerFeature.Content(
                                                workspace = workspace,
                                                project = selectedProject,
                                                fs = workspaceManager.fsFor(selectedProject.fsPath),
                                                context = context,
                                                modifier = Modifier.fillMaxSize(),
                                                viewMode = explorerViewModeOf(effectiveConfig.explorer.viewMode),
                                                onFileSelected = onOpenFile,
                                                onSnackbar = onSnackbar,
                                            )
                                        }
                                    }
                                }
                                // User Workspace with nothing selected: roster only, tree hidden.
                                inUserWorkspace -> Spacer(modifier = Modifier.weight(1f))
                                // Default Workspace with nothing open: New/Open actions.
                                else -> Box(modifier = Modifier.weight(1f)) {
                                    WorkspaceEmptyState(
                                        workspace = workspace,
                                        onCreateProject = onCreateProject,
                                        onOpenExternalFolder = onOpenExternalFolder,
                                    )
                                }
                            }
                        }
                    }

                    WorkbenchTool.Search -> ToolPanelPlaceholder(
                        title = "Search",
                        icon = jcIcon(JCodeIcon.Search),
                        lines = listOf(
                            "Quick file search, symbol search, and ripgrep results live here.",
                            "Suggested UI: recent queries, include/exclude globs, and pinned result groups.",
                            "Best next widgets: match counters, replace preview, and staged search history.",
                        ),
                    )

                    WorkbenchTool.Scm -> ToolPanelPlaceholder(
                        title = "Source Control",
                        icon = jcIcon(JCodeIcon.Scm),
                        lines = listOf(
                            "Show branch, dirty files, staged changes, and last sync status.",
                            "A better tablet/desktop layout is a split staged/unstaged list with inline diff preview.",
                            "The status bar should eventually mirror this panel's active branch and error state.",
                        ),
                    )

                    WorkbenchTool.RunDebug -> RunDebugPanel(
                        selectedProject = selectedProject,
                        runUrl = runUrl,
                        runInProgress = runInProgress,
                        onRun = onRun,
                        onStop = onStopRun,
                        onOpenInBrowser = onOpenRunInBrowser,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.Extensions -> ExtensionsPanel(
                        installed = installedExtensions,
                        available = marketplaceEntries,
                        busy = marketplaceBusy,
                        onRefreshMarketplace = onRefreshMarketplace,
                        onInstall = onInstallExtension,
                        onUninstall = onUninstallExtension,
                        themeBundleId = themeBundleId,
                        onSelectTheme = onUpdateThemeBundle,
                        iconBundleId = iconBundleId,
                        onSelectIcon = onUpdateIconBundle,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.SdkManager -> SdkManagerFeature.Content(
                        state = sdkCatalogState,
                        environmentState = environmentState,
                        onRefresh = onRefreshSdkCatalog,
                        onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                        onInstallEntry = onInstallSdkCatalogEntry,
                        onVerifyEntry = onVerifySdkCatalogEntry,
                        onUninstallEntry = onUninstallSdkCatalogEntry,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.LspManager -> LspManagerFeature.Content(
                        state = lspCatalogState,
                        environmentState = environmentState,
                        onRefresh = onRefreshLspCatalog,
                        onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                        onInstallEntry = onInstallLspCatalogEntry,
                        onVerifyEntry = onVerifyLspCatalogEntry,
                        onUninstallEntry = onUninstallLspCatalogEntry,
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Settings opens as an in-editor page (see EditorWorkspace); it is never the
                    // selected side-panel tool, so this branch renders nothing.
                    WorkbenchTool.Settings -> Unit
                }
            }
        }
    }
}

@Composable
private fun WorkspaceHeader(
    selectedTool: WorkbenchTool,
    workspace: Workspace?,
    selectedProject: Project?,
    inUserWorkspace: Boolean,
    onSelectTool: (WorkbenchTool) -> Unit,
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
    onCloseWorkspace: () -> Unit,
    onCloseProject: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
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
                        .clip(RoundedCornerShape(8.dp))
                        .then(
                            if (hasCloseAction) Modifier.clickable { menuExpanded = true } else Modifier,
                        )
                        .padding(vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(1.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
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
                                modifier = Modifier.size(16.dp),
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
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text(if (inUserWorkspace) "Close workspace" else "Close project") },
                        leadingIcon = {
                            Icon(imageVector = jcIcon(JCodeIcon.Close), contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            if (inUserWorkspace) onCloseWorkspace() else onCloseProject()
                        },
                    )
                }
            }

            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Add),
                contentDescription = "Add project",
                onClick = onCreateProject,
            )
            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Destinations),
                contentDescription = "Open external folder",
                onClick = onOpenExternalFolder,
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WorkbenchTool.entries.filter { it.available }.forEach { tool ->
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
private fun SidebarToolButton(
    tool: WorkbenchTool,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = jcIcon(tool.icon),
                contentDescription = tool.label,
                modifier = Modifier.size(14.dp),
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = tool.compactLabel,
                style = MaterialTheme.typography.labelMedium,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun WorkspaceEmptyState(
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
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
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
                text = workspace?.name ?: "J Code",
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
private fun ProjectRoster(
    projects: List<Project>,
    selectedProjectId: Long,
    onOpenProject: (Project) -> Unit,
    onRenameProject: (Long, String) -> Unit,
    onRemoveProject: (Long) -> Unit,
    onOpenProjectSettings: (Long) -> Unit,
    onCreateProject: () -> Unit,
) {
    var renameTarget by remember { mutableStateOf<Project?>(null) }
    var openMenuId by remember { mutableStateOf<Long?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 3.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "PROJECTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Add),
                contentDescription = "New project in this workspace",
                onClick = onCreateProject,
            )
        }

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
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = project.name,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = project.distroBindTarget,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Box {
                            IconButton(onClick = { openMenuId = project.id }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = jcIcon(JCodeIcon.MoreVert),
                                    contentDescription = "Project actions",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            DropdownMenu(
                                expanded = openMenuId == project.id,
                                onDismissRequest = { openMenuId = null },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(if (isWorkspace) "Open workspace" else "Open") },
                                    onClick = { openMenuId = null; onOpenProject(project) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Rename") },
                                    onClick = { openMenuId = null; renameTarget = project },
                                )
                                DropdownMenuItem(
                                    text = { Text("Remove") },
                                    onClick = { openMenuId = null; onRemoveProject(project.id) },
                                )
                                DropdownMenuItem(
                                    text = { Text("Project settings") },
                                    onClick = { openMenuId = null; onOpenProjectSettings(project.id) },
                                )
                            }
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
private fun ToolPanelPlaceholder(
    title: String,
    icon: ImageVector,
    lines: List<String>,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f))
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Column {
                        Text(title, fontWeight = FontWeight.SemiBold)
                        Text(
                            text = "Planned surface",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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
    }
}

@Composable
private fun EditorWorkspace(
    windowInfo: JCodeWindowInfo,
    workspace: Workspace?,
    selectedProject: Project?,
    editorGroup: EditorGroup,
    activeTab: EditorTab?,
    leftSidebarExpanded: Boolean,
    canShowRightSidebar: Boolean,
    rightSidebarVisible: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onRun: () -> Unit,
    onShowTerminal: () -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onOpenFileRequest: () -> Unit,
    languageActionsEnabled: Boolean,
    onEditorLanguageAction: (EditorLanguageAction, String) -> Unit,
    editorPageContent: @Composable (EditorTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkbenchTopBar(
                workspace = workspace,
                selectedProject = selectedProject,
                activeTab = activeTab,
                leftSidebarExpanded = leftSidebarExpanded,
                canShowRightSidebar = canShowRightSidebar,
                rightSidebarVisible = rightSidebarVisible,
                onToggleLeftSidebar = onToggleLeftSidebar,
                onToggleRightSidebar = onToggleRightSidebar,
                onShowTerminal = onShowTerminal,
                onRun = onRun,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))

            if (editorGroup.tabs.isEmpty()) {
                // No open file: a clean, empty editor surface (no startup page).
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                EditorPane(
                    group = editorGroup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onTabSelected = onSelectEditorTab,
                    onTabClosed = onCloseEditorTab,
                    onOpenFile = onOpenFileRequest,
                    languageActionsEnabled = languageActionsEnabled,
                    onLanguageAction = onEditorLanguageAction,
                    pageContent = editorPageContent,
                )
            }
        }
    }
}

@Composable
private fun WorkbenchTopBar(
    workspace: Workspace?,
    selectedProject: Project?,
    activeTab: EditorTab?,
    leftSidebarExpanded: Boolean,
    canShowRightSidebar: Boolean,
    rightSidebarVisible: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onShowTerminal: () -> Unit,
    onRun: () -> Unit,
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
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.MenuToggle),
                contentDescription = if (leftSidebarExpanded) "Hide left sidebar" else "Show left sidebar",
                onClick = onToggleLeftSidebar,
                active = leftSidebarExpanded,
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    text = activeTab?.title ?: selectedProject?.name ?: "J Code",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = listOfNotNull(
                        workspace?.name,
                        selectedProject?.name,
                        activeTab?.title,
                    ).joinToString(" / ").ifBlank { "Editor workspace" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Terminal),
                contentDescription = "Terminal",
                onClick = onShowTerminal,
            )
            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Run),
                contentDescription = "Run",
                onClick = onRun,
            )
            WorkbenchIconActionButton(
                icon = jcIcon(JCodeIcon.Logs),
                contentDescription = "Toggle right sidebar",
                onClick = onToggleRightSidebar,
                active = canShowRightSidebar && rightSidebarVisible,
            )
        }
    }
}

@Composable
private fun WelcomeCard(
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

@Composable
private fun WorkbenchRightSidebar(
    selectedTab: RightPanelTab,
    selectedProject: Project?,
    terminalSessionIds: List<String>,
    selectedTerminalSessionId: String,
    activeTerminalPty: dev.jcode.core.term.PtyProcess?,
    terminalSessionFor: (String) -> dev.jcode.core.term.TerminalSessionManager.Session?,
    terminalReady: Boolean,
    onOpenEnvironmentWizard: () -> Unit,
    onTabSelected: (RightPanelTab) -> Unit,
    onSelectTerminalSession: (String) -> Unit,
    onAddTerminalSession: () -> Unit,
    onRemoveTerminalSession: (String) -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.97f),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RightPanelTab.entries.filter { it.enabled }.forEach { tab ->
                    val selected = tab == selectedTab
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onTabSelected(tab) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            val tint = if (selected) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                            Icon(
                                imageVector = jcIcon(tab.icon),
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = tint,
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = tint,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                WorkbenchActionButton(text = "Hide", onClick = onHide)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
            WorkbenchRightSidebarBody(
                tab = selectedTab,
                selectedProject = selectedProject,
                terminalSessionIds = terminalSessionIds,
                selectedTerminalSessionId = selectedTerminalSessionId,
                activeTerminalPty = activeTerminalPty,
                terminalSessionFor = terminalSessionFor,
                terminalReady = terminalReady,
                onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                onSelectTerminalSession = onSelectTerminalSession,
                onAddTerminalSession = onAddTerminalSession,
                onRemoveTerminalSession = onRemoveTerminalSession,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun WorkbenchRightSidebarBody(
    tab: RightPanelTab,
    selectedProject: Project?,
    terminalSessionIds: List<String>,
    selectedTerminalSessionId: String,
    activeTerminalPty: dev.jcode.core.term.PtyProcess?,
    terminalSessionFor: (String) -> dev.jcode.core.term.TerminalSessionManager.Session?,
    terminalReady: Boolean,
    onOpenEnvironmentWizard: () -> Unit,
    onSelectTerminalSession: (String) -> Unit,
    onAddTerminalSession: () -> Unit,
    onRemoveTerminalSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        RightPanelTab.Terminal -> {
            TerminalSidebarContent(
                selectedProject = selectedProject,
                terminalSessionIds = terminalSessionIds,
                selectedTerminalSessionId = selectedTerminalSessionId,
                activeTerminalPty = activeTerminalPty,
                terminalSessionFor = terminalSessionFor,
                terminalReady = terminalReady,
                onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                onSelectTerminalSession = onSelectTerminalSession,
                onAddTerminalSession = onAddTerminalSession,
                onRemoveTerminalSession = onRemoveTerminalSession,
                modifier = modifier,
            )
        }
        RightPanelTab.Output -> {
            OutputSidebarContent(
                modifier = modifier,
            )
        }
        RightPanelTab.Problems -> {
            DisabledTabContent(
                title = "Problems",
                message = "Project problem detection coming in a future update.",
                modifier = modifier,
            )
        }
        RightPanelTab.DebugConsole -> {
            DisabledTabContent(
                title = "Debug Console",
                message = "Debug console coming in a future update.",
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun TerminalSidebarContent(
    selectedProject: Project?,
    terminalSessionIds: List<String>,
    selectedTerminalSessionId: String,
    activeTerminalPty: dev.jcode.core.term.PtyProcess?,
    terminalSessionFor: (String) -> dev.jcode.core.term.TerminalSessionManager.Session?,
    terminalReady: Boolean,
    onOpenEnvironmentWizard: () -> Unit,
    onSelectTerminalSession: (String) -> Unit,
    onAddTerminalSession: () -> Unit,
    onRemoveTerminalSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        var menuForId by remember { mutableStateOf<String?>(null) }
        var renameForId by remember { mutableStateOf<String?>(null) }
        // Renamed labels, kept observable so the tab recomposes (mutating Session.label alone wouldn't).
        val labelOverrides = remember { mutableStateMapOf<String, String>() }
        // Session tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            terminalSessionIds.forEachIndexed { index, sessionId ->
                Box {
                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .combinedClickable(
                                onClick = { onSelectTerminalSession(sessionId) },
                                onLongClick = { menuForId = sessionId },
                            ),
                        shape = RoundedCornerShape(8.dp),
                        color = if (sessionId == selectedTerminalSessionId) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        } else {
                            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
                        },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = labelOverrides[sessionId]
                                    ?: terminalSessionFor(sessionId)?.label
                                    ?: "bash ${index + 1}",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (sessionId == selectedTerminalSessionId) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            // Close button
                            Surface(
                                modifier = Modifier
                                    .size(18.dp)
                                    .clip(CircleShape)
                                    .clickable { onRemoveTerminalSession(sessionId) },
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.2f),
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "×",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(top = 1.dp),
                                    )
                                }
                            }
                        }
                    }
                    DropdownMenu(
                        expanded = menuForId == sessionId,
                        onDismissRequest = { menuForId = null },
                    ) {
                        DropdownMenuItem(
                            text = { Text("Rename") },
                            onClick = { menuForId = null; renameForId = sessionId },
                        )
                        DropdownMenuItem(
                            text = { Text("Clear") },
                            onClick = {
                                terminalSessionFor(sessionId)?.pty?.write(byteArrayOf(0x0C))
                                menuForId = null
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Close") },
                            onClick = { menuForId = null; onRemoveTerminalSession(sessionId) },
                        )
                        DropdownMenuItem(
                            text = { Text("Close others") },
                            onClick = {
                                menuForId = null
                                terminalSessionIds.filter { it != sessionId }.forEach(onRemoveTerminalSession)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("Close all") },
                            onClick = {
                                menuForId = null
                                terminalSessionIds.toList().forEach(onRemoveTerminalSession)
                            },
                        )
                    }
                }
            }
            Surface(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onAddTerminalSession),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
            ) {
                Text(
                    text = "+",
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        renameForId?.let { id ->
            var name by remember(id) {
                mutableStateOf(labelOverrides[id] ?: terminalSessionFor(id)?.label.orEmpty())
            }
            AlertDialog(
                onDismissRequest = { renameForId = null },
                title = { Text("Rename terminal") },
                text = {
                    TextField(
                        value = name,
                        onValueChange = { name = it },
                        singleLine = true,
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        val trimmed = name.trim()
                        if (trimmed.isNotEmpty()) {
                            terminalSessionFor(id)?.label = trimmed
                            labelOverrides[id] = trimmed
                        }
                        renameForId = null
                    }) { Text("Rename") }
                },
                dismissButton = {
                    TextButton(onClick = { renameForId = null }) { Text("Cancel") }
                },
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))

        if (!terminalReady) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.Sdk),
                    contentDescription = null,
                    modifier = Modifier.size(48.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Terminal unavailable",
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Finish distro setup first, then open the terminal again.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                )
                Spacer(modifier = Modifier.height(12.dp))
                FilledTonalButton(onClick = onOpenEnvironmentWizard) {
                    Text("Open setup")
                }
            }
        } else if (terminalSessionIds.isNotEmpty() && activeTerminalPty != null) {
            // One TerminalView per session, each kept alive in the composition with its OWN PTY, VT
            // parser, read loop and scrollback. Only the active session is visible + focused; the rest
            // stay attached (still reading their output) but transparent and behind it. Switching tabs
            // is instant and every session keeps its own independent content.
            val tapConfig = LocalTerminalTapConfig.current
            Box(modifier = Modifier.fillMaxSize()) {
                terminalSessionIds.forEach { sessionId ->
                    val session = terminalSessionFor(sessionId) ?: return@forEach
                    val isActive = sessionId == selectedTerminalSessionId
                    key(sessionId) {
                        AndroidView(
                            factory = { ctx ->
                                TerminalView(ctx).apply {
                                    setFontSize(30f)
                                    doubleTapToFocus = tapConfig.doubleTapToFocus
                                    onTapToken = tapConfig.onToken
                                    bind(session)
                                }
                            },
                            update = { view ->
                                view.doubleTapToFocus = tapConfig.doubleTapToFocus
                                view.onTapToken = tapConfig.onToken
                                view.bind(session) // no-op if already bound to this session
                                view.setActive(isActive)
                            },
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(if (isActive) 1f else 0f)
                                .alpha(if (isActive) 1f else 0f),
                        )
                    }
                }
            }
        } else {
            // No sessions - show placeholder
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Terminal),
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    Text(
                        text = "No terminal sessions",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    )
                    Text(
                        text = "Tap + to start a new terminal",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                    )
                    selectedProject?.distroBindTarget?.let { target ->
                        Text(
                            text = "Workspace target: $target",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DisabledTabContent(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = jcIcon(JCodeIcon.Sdk),
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun OutputSidebarContent(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Output", fontWeight = FontWeight.SemiBold)
        Text(
            text = "Build logs and tool output will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SidebarSessionChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(10.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.75f)
        },
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SidebarSectionCard(
    title: String,
    lines: List<String>,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, fontWeight = FontWeight.SemiBold)
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private data class SidebarSectionSpec(
    val title: String,
    val lines: List<String>,
)

@Composable
private fun InspectorSidebar(
    selectedProject: Project?,
    activeTab: EditorTab?,
    editorGroup: EditorGroup,
    effectiveConfig: EffectiveConfig,
    windowInfo: JCodeWindowInfo,
    selectedTool: WorkbenchTool,
    onShowCommandPalette: () -> Unit,
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                WelcomeCard(
                    title = "Workspace snapshot",
                    icon = jcIcon(JCodeIcon.Code),
                    lines = listOf(
                        "Mode: ${deviceModeLabel(windowInfo)}",
                        "Tool: ${selectedTool.label}",
                        "Open tabs: ${editorGroup.tabs.size}",
                    ),
                )
            }
            item {
                WelcomeCard(
                    title = "Editor defaults",
                    icon = jcIcon(JCodeIcon.Settings),
                    lines = listOf(
                        "Font: ${effectiveConfig.editor.fontSize.toInt()} sp",
                        "Tabs: ${effectiveConfig.editor.tabSize} spaces",
                        "Distro: ${effectiveConfig.distro.id}",
                    ),
                )
            }
            item {
                WelcomeCard(
                    title = "Active context",
                    icon = jcIcon(JCodeIcon.Destinations),
                    lines = listOf(
                        "Project: ${selectedProject?.name ?: "None"}",
                        "Bind target: ${selectedProject?.distroBindTarget ?: "--"}",
                        "File: ${activeTab?.title ?: "No file open"}",
                    ),
                )
            }
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f))
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(jcIcon(JCodeIcon.Help), contentDescription = null)
                        Text("Quick actions", fontWeight = FontWeight.SemiBold)
                    }
                    WorkbenchActionButton(text = "Command Palette", onClick = onShowCommandPalette, active = true)
                    WorkbenchActionButton(text = "New Folder", onClick = onCreateProject)
                    WorkbenchActionButton(text = "Open Folder", onClick = onOpenExternalFolder)
                }
            }
        }
    }
}

private fun deviceModeLabel(windowInfo: JCodeWindowInfo): String = buildString {
    append(windowInfo.widthClass.name.lowercase())
    append(" / ")
    append(windowInfo.heightClass.name.lowercase())
    if (windowInfo.hasPhysicalKeyboard) {
        append(" / keyboard")
    }
}

private fun explorerViewModeOf(value: String): ExplorerViewMode =
    ExplorerViewMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ExplorerViewMode.Tree

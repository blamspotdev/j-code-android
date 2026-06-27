package dev.jcode

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
import androidx.compose.foundation.focusable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.BuildCircle
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.DatasetLinked
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radar
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Source
import androidx.compose.material.icons.rounded.SyncProblem
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.adaptive.JCodePosture
import dev.jcode.adaptive.JCodeWindowInfo
import dev.jcode.adaptive.JCodeWindowWidthClass
import dev.jcode.adaptive.rememberJCodeWindowInfo
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.config.ProjectConfig
import dev.jcode.core.config.WorkspaceConfig
import dev.jcode.core.distro.DistroBind
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.DistroWizardProgress
import dev.jcode.core.distro.ProotManager
import dev.jcode.core.distro.RootfsDownloader
import dev.jcode.core.distro.RootfsManager
import dev.jcode.core.distro.SdkCatalogState
import dev.jcode.core.term.TerminalSessionManager
import dev.jcode.core.term.TerminalView
import dev.jcode.design.CommandRegistry
import dev.jcode.design.CommandSpec
import dev.jcode.design.ThemeMode
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorPane
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.explorer.ExplorerFeature
import dev.jcode.feature.explorer.ExplorerViewMode
import dev.jcode.feature.marketplace.MarketplaceServiceLocator
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.ScaffoldState
import dev.jcode.feature.marketplace.TemplateExtension
import dev.jcode.feature.onboarding.OnboardingFeature
import dev.jcode.feature.sdkmanager.SdkManagerFeature
import dev.jcode.feature.settings.SettingsFeature
import dev.jcode.fs.DEFAULT_SHARED_PROJECTS_ROOT
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceCrumb
import dev.jcode.fs.WorkspaceManager
import dev.jcode.fs.WorkspaceNodeType
import dev.jcode.fs.rememberOpenFolderLauncher
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class WorkbenchTool(
    val label: String,
    val icon: ImageVector,
    val compactLabel: String = label,
) {
    Explorer("Explorer", Icons.Rounded.FolderOpen, "Files"),
    Search("Search", Icons.Rounded.Search, "Find"),
    Scm("SCM", Icons.Rounded.Source, "SCM"),
    RunDebug("Run/Debug", Icons.Rounded.PlayArrow, "Run"),
    Extensions("Extensions", Icons.Rounded.Extension, "Ext"),
    SdkManager("SDK Manager", Icons.Rounded.BuildCircle, "SDK"),
    Settings("Settings", Icons.Rounded.Settings, "Prefs"),
}

private enum class RightPanelTab(
    val label: String,
    val icon: ImageVector,
    val enabled: Boolean = true,
) {
    Terminal("Terminal", Icons.Rounded.Terminal, enabled = true),
    Output("Output", Icons.AutoMirrored.Rounded.Article, enabled = true),
    Problems("Problems", Icons.Rounded.SyncProblem, enabled = false),
    DebugConsole("Debug Console", Icons.Rounded.Radar, enabled = false),
}

private data class EditorMetrics(
    val line: Int = 1,
    val column: Int = 1,
    val language: String = "Plain Text",
    val encoding: String = "UTF-8",
)

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
    val autoSetupProgress by viewModel.autoSetupProgress.collectAsStateWithLifecycle(initialValue = DistroWizardProgress.Idle)
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val openFolderLauncher = rememberOpenFolderLauncher(
        onFolderPicked = viewModel::openExternalFolder,
    )
    val openFolderTypePrompt by viewModel.openFolderTypePrompt.collectAsStateWithLifecycle()
    var environmentWizardVisible by rememberSaveable { mutableStateOf(false) }
    val environmentNotConfigured = environmentState.smokeTestPassed != true
    val showFirstRunEnvironmentScreen = environmentNotConfigured && !environmentState.firstRunSetupDeferred

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    LaunchedEffect(selectedProject?.id, editorGroup.tabs.size) {
        if (selectedProject != null && editorGroup.tabs.isEmpty()) {
            viewModel.ensureProjectBootstrapTab()
        }
    }

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
        onOpenWorkspaceConfig = viewModel::openWorkspaceConfigFile,
        onOpenProjectConfig = viewModel::openProjectConfigFile,
        onRefreshEnvironment = viewModel::refreshEnvironment,
        onOpenEnvironmentWizard = { environmentWizardVisible = true },
        onAutoSetup = viewModel::runAutoSetup,
        onRefreshSdkCatalog = viewModel::refreshSdkCatalog,
        onInstallSdkCatalogEntry = viewModel::installSdkCatalogEntry,
        onVerifySdkCatalogEntry = viewModel::verifySdkCatalogEntry,
        onUninstallSdkCatalogEntry = viewModel::uninstallSdkCatalogEntry,
    )

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

    if (environmentWizardVisible) {
        OnboardingFeature.TermuxDistroWizard(
            environmentState = environmentState,
            autoSetupProgress = autoSetupProgress,
            onDismiss = { environmentWizardVisible = false },
            onRefresh = { viewModel.refreshEnvironment() },
            onSelectDistro = { viewModel.selectWizardDistro(it) },
            onRunStep = { stepId -> viewModel.runEnvironmentStep(stepId) },
            onAutoSetup = { viewModel.runAutoSetup() },
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
    onOpenWorkspaceConfig: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onAutoSetup: () -> Unit,
    onRefreshSdkCatalog: () -> Unit,
    onInstallSdkCatalogEntry: (String) -> Unit,
    onVerifySdkCatalogEntry: (String) -> Unit,
    onUninstallSdkCatalogEntry: (String) -> Unit,
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
    val portraitRightSidebarTabs = remember { RightPanelTab.entries.toSet() }

    var selectedTool by rememberSaveable { mutableStateOf(WorkbenchTool.Explorer) }
    var leftSidebarExpanded by rememberSaveable(isLandscape, windowInfo.widthClass) {
        mutableStateOf(isLandscape && windowInfo.widthClass != JCodeWindowWidthClass.Compact)
    }
    val isPersistentLeftSidebarVisible = !usesModalWorkspace && leftSidebarExpanded
    var rightSidebarVisible by rememberSaveable(isLandscape, windowInfo.widthClass) {
        mutableStateOf(isLandscape)
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

    fun createTerminalSession() {
        if (!terminalReady) {
            scope.launch {
                snackbarHostState.showSnackbar("Finish environment setup before opening the terminal.")
            }
            return
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
            return
        }
        terminalSessionIds = terminalSessionIds + session.id
        selectedTerminalSessionId = session.id
        terminalSessionManager.switchSession(session.id)
        // Hold the foreground service while this terminal is alive (keeps the process from being
        // killed/frozen in the background so the shell keeps running).
        TerminalSessionHost.onSessionStarted(appContext, session.id)
    }

    fun selectTerminalSession(sessionId: String) {
        selectedTerminalSessionId = sessionId
        terminalSessionManager.switchSession(sessionId)
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
            id = "help.about",
            title = "Show About Placeholder",
            group = "Help",
            action = { selectedTool = WorkbenchTool.Settings },
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
                workspaceConfig = workspaceConfig,
                projectConfig = projectConfig,
                effectiveConfig = effectiveConfig,
                workspaceConfigError = workspaceConfigError,
                projectConfigError = projectConfigError,
                environmentState = environmentState,
                autoSetupProgress = autoSetupProgress,
                sdkCatalogState = sdkCatalogState,
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
                onSelectTool = { selectedTool = it },
                onOpenExternalFolder = { openFolderLauncher.launch(null) },
                onOpenFile = onOpenFile,
                onUpdateEditorFontSize = onUpdateEditorFontSize,
                onUpdateEditorTabSize = onUpdateEditorTabSize,
                onUpdateEditorWordWrap = onUpdateEditorWordWrap,
                onUpdateEditorMinimap = onUpdateEditorMinimap,
                onUpdateEditorLigatures = onUpdateEditorLigatures,
                onUpdateExplorerViewMode = onUpdateExplorerViewMode,
                themeMode = themeMode,
                onUpdateThemeMode = onUpdateThemeMode,
                onOpenWorkspaceConfig = onOpenWorkspaceConfig,
                onOpenProjectConfig = onOpenProjectConfig,
                onRefreshEnvironment = onRefreshEnvironment,
                onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                onAutoSetup = onAutoSetup,
                onRefreshSdkCatalog = onRefreshSdkCatalog,
                onInstallSdkCatalogEntry = onInstallSdkCatalogEntry,
                onVerifySdkCatalogEntry = onVerifySdkCatalogEntry,
                onUninstallSdkCatalogEntry = onUninstallSdkCatalogEntry,
                onSnackbar = { message ->
                    scope.launch { snackbarHostState.showSnackbar(message) }
                },
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
                        selectedTool = selectedTool,
                        onToolSelected = {
                            selectedTool = it
                            if (usesModalWorkspace) {
                                scope.launch { compactDrawerState.open() }
                            } else {
                                leftSidebarExpanded = true
                            }
                        },
                        compact = true,
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
                        metrics = metrics,
                        effectiveConfig = effectiveConfig,
                        isLandscape = isLandscape,
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
                        onToolSelected = { selectedTool = it },
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
                    )

                    if (isPersistentLeftSidebarVisible) {
                        WorkspacePanel(
                            selectedTool = selectedTool,
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
                            onSelectTool = { selectedTool = it },
                            onOpenExternalFolder = { openFolderLauncher.launch(null) },
                            onOpenFile = onOpenFile,
                            onUpdateEditorFontSize = onUpdateEditorFontSize,
                            onUpdateEditorTabSize = onUpdateEditorTabSize,
                            onUpdateEditorWordWrap = onUpdateEditorWordWrap,
                            onUpdateEditorMinimap = onUpdateEditorMinimap,
                            onUpdateEditorLigatures = onUpdateEditorLigatures,
                            onUpdateExplorerViewMode = onUpdateExplorerViewMode,
                            themeMode = themeMode,
                            onUpdateThemeMode = onUpdateThemeMode,
                            onOpenWorkspaceConfig = onOpenWorkspaceConfig,
                            onOpenProjectConfig = onOpenProjectConfig,
                            onRefreshEnvironment = onRefreshEnvironment,
                            onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                            onAutoSetup = onAutoSetup,
                            onRefreshSdkCatalog = onRefreshSdkCatalog,
                            onInstallSdkCatalogEntry = onInstallSdkCatalogEntry,
                            onVerifySdkCatalogEntry = onVerifySdkCatalogEntry,
                            onUninstallSdkCatalogEntry = onUninstallSdkCatalogEntry,
                            onSnackbar = { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            },
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

@Composable
private fun DockRail(
    selectedTool: WorkbenchTool,
    onToolSelected: (WorkbenchTool) -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.width(if (compact) 60.dp else 68.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
    ) {
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(vertical = 10.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            WorkbenchTool.entries.forEach { tool ->
                RailButton(
                    tool = tool,
                    selected = selectedTool == tool,
                    onClick = { onToolSelected(tool) },
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Text(
                text = "API 36",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RailButton(
    tool: WorkbenchTool,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val container = if (selected) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
    } else {
        Color.Transparent
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(
        modifier = Modifier
            .width(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp, horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Surface(
            modifier = Modifier.size(34.dp),
            shape = CircleShape,
            color = container,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = tool.icon,
                    contentDescription = tool.label,
                    tint = content,
                )
            }
        }
        Text(
            text = tool.compactLabel,
            style = MaterialTheme.typography.bodySmall,
            color = content,
            maxLines = 1,
        )
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
    workspaceConfig: WorkspaceConfig?,
    projectConfig: ProjectConfig?,
    effectiveConfig: EffectiveConfig,
    workspaceConfigError: String?,
    projectConfigError: String?,
    environmentState: DistroEnvironmentState,
    autoSetupProgress: DistroWizardProgress,
    sdkCatalogState: SdkCatalogState,
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
    onUpdateEditorFontSize: (ConfigScope, Float) -> Unit,
    onUpdateEditorTabSize: (ConfigScope, Int) -> Unit,
    onUpdateEditorWordWrap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorMinimap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorLigatures: (ConfigScope, Boolean) -> Unit,
    onUpdateExplorerViewMode: (ConfigScope, String) -> Unit,
    themeMode: ThemeMode,
    onUpdateThemeMode: (ThemeMode) -> Unit,
    onOpenWorkspaceConfig: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onAutoSetup: () -> Unit,
    onRefreshSdkCatalog: () -> Unit,
    onInstallSdkCatalogEntry: (String) -> Unit,
    onVerifySdkCatalogEntry: (String) -> Unit,
    onUninstallSdkCatalogEntry: (String) -> Unit,
    onSnackbar: (String) -> Unit,
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
                        icon = Icons.Rounded.Search,
                        lines = listOf(
                            "Quick file search, symbol search, and ripgrep results live here.",
                            "Suggested UI: recent queries, include/exclude globs, and pinned result groups.",
                            "Best next widgets: match counters, replace preview, and staged search history.",
                        ),
                    )

                    WorkbenchTool.Scm -> ToolPanelPlaceholder(
                        title = "Source Control",
                        icon = Icons.Rounded.Source,
                        lines = listOf(
                            "Show branch, dirty files, staged changes, and last sync status.",
                            "A better tablet/desktop layout is a split staged/unstaged list with inline diff preview.",
                            "The status bar should eventually mirror this panel's active branch and error state.",
                        ),
                    )

                    WorkbenchTool.RunDebug -> ToolPanelPlaceholder(
                        title = "Run & Debug",
                        icon = Icons.Rounded.PlayArrow,
                        lines = listOf(
                            "Show recent tasks, launch targets, and the active distro session.",
                            "Useful additions: one-tap Gradle assemble, npm dev, cargo test, and adb install.",
                            "Desktop mode should expose a pinned debug controls row above the bottom terminal.",
                        ),
                    )

                    WorkbenchTool.Extensions -> {
                        val extensionContext = LocalContext.current
                        val templateExtension = remember(extensionContext) {
                            MarketplaceServiceLocator.templateCatalog(extensionContext).extension()
                        }
                        ExtensionsPanel(
                            extension = templateExtension,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

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

                    WorkbenchTool.Settings -> SettingsFeature.Content(
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
                        modifier = Modifier.fillMaxSize(),
                    )
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
                                imageVector = Icons.Rounded.ArrowDropDown,
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
                            Icon(imageVector = Icons.Rounded.Close, contentDescription = null)
                        },
                        onClick = {
                            menuExpanded = false
                            if (inUserWorkspace) onCloseWorkspace() else onCloseProject()
                        },
                    )
                }
            }

            WorkbenchIconActionButton(
                icon = Icons.Rounded.Add,
                contentDescription = "Add project",
                onClick = onCreateProject,
            )
            WorkbenchIconActionButton(
                icon = Icons.Rounded.DatasetLinked,
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
            WorkbenchTool.entries.forEach { tool ->
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
                imageVector = tool.icon,
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
                imageVector = Icons.Rounded.FolderOpen,
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
                icon = Icons.Rounded.Add,
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
                                    imageVector = if (isWorkspace) Icons.Rounded.FolderOpen else Icons.Rounded.Code,
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
                                    imageVector = Icons.Rounded.MoreVert,
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
    metrics: EditorMetrics,
    effectiveConfig: EffectiveConfig,
    isLandscape: Boolean,
    leftSidebarExpanded: Boolean,
    canShowRightSidebar: Boolean,
    rightSidebarVisible: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onToolSelected: (WorkbenchTool) -> Unit,
    onShowTerminal: () -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onOpenFileRequest: () -> Unit,
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
                metrics = metrics,
                effectiveConfig = effectiveConfig,
                isLandscape = isLandscape,
                leftSidebarExpanded = leftSidebarExpanded,
                canShowRightSidebar = canShowRightSidebar,
                rightSidebarVisible = rightSidebarVisible,
                onToggleLeftSidebar = onToggleLeftSidebar,
                onToggleRightSidebar = onToggleRightSidebar,
                onShowTerminal = onShowTerminal,
                onToolSelected = onToolSelected,
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
    metrics: EditorMetrics,
    effectiveConfig: EffectiveConfig,
    isLandscape: Boolean,
    leftSidebarExpanded: Boolean,
    canShowRightSidebar: Boolean,
    rightSidebarVisible: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onShowTerminal: () -> Unit,
    onToolSelected: (WorkbenchTool) -> Unit,
) {
    val compactCursorLabel = "L${metrics.line}:C${metrics.column}"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                WorkbenchIconActionButton(
                    icon = Icons.AutoMirrored.Rounded.MenuOpen,
                    contentDescription = if (isLandscape && leftSidebarExpanded) {
                        "Hide left sidebar"
                    } else {
                        "Show left sidebar"
                    },
                    onClick = onToggleLeftSidebar,
                    active = isLandscape && leftSidebarExpanded,
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
                    icon = Icons.Rounded.Terminal,
                    contentDescription = "Terminal",
                    onClick = onShowTerminal,
                )
                WorkbenchIconActionButton(
                    icon = Icons.Rounded.PlayArrow,
                    contentDescription = "Run",
                    onClick = { onToolSelected(WorkbenchTool.RunDebug) },
                )
                WorkbenchIconActionButton(
                    icon = Icons.AutoMirrored.Rounded.Article,
                    contentDescription = "Toggle right sidebar",
                    onClick = onToggleRightSidebar,
                    active = canShowRightSidebar && rightSidebarVisible,
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                MetricChip(
                    icon = Icons.Rounded.Code,
                    text = activeTab?.languageDescriptor?.name ?: metrics.language,
                )
                MetricChip(
                    icon = Icons.Rounded.DatasetLinked,
                    text = selectedProject?.distroBindTarget ?: "/workspace/default",
                )
                MetricChip(
                    icon = Icons.Rounded.Search,
                    text = "${effectiveConfig.distro.id} / ${effectiveConfig.editor.fontSize.toInt()}sp / tab ${effectiveConfig.editor.tabSize}",
                )
                if (!isLandscape) {
                    MetricChip(
                        icon = Icons.AutoMirrored.Rounded.Article,
                        text = compactCursorLabel,
                    )
                } else {
                    MetricChip(
                        icon = Icons.AutoMirrored.Rounded.Article,
                        text = "Line ${metrics.line}, Col ${metrics.column}",
                    )
                }
            }
        }
    }
}

@Composable
private fun MetricChip(
    icon: ImageVector,
    text: String,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.2f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
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
                RightPanelTab.entries.forEach { tab ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (tab == selectedTab) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                        } else if (!tab.enabled) {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = tab.enabled) { onTabSelected(tab) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                                tint = if (tab == selectedTab) {
                                    MaterialTheme.colorScheme.primary
                                } else if (!tab.enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (tab == selectedTab) {
                                    MaterialTheme.colorScheme.primary
                                } else if (!tab.enabled) {
                                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
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
        // Session tab bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            terminalSessionIds.forEachIndexed { index, sessionId ->
                Surface(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onSelectTerminalSession(sessionId) },
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
                            text = "bash ${index + 1}",
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
                    imageVector = Icons.Rounded.BuildCircle,
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
            Box(modifier = Modifier.fillMaxSize()) {
                terminalSessionIds.forEach { sessionId ->
                    val session = terminalSessionFor(sessionId) ?: return@forEach
                    val isActive = sessionId == selectedTerminalSessionId
                    key(sessionId) {
                        AndroidView(
                            factory = { ctx ->
                                TerminalView(ctx).apply {
                                    setFontSize(30f)
                                    bind(session)
                                }
                            },
                            update = { view ->
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
                        imageVector = Icons.Rounded.Terminal,
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
            imageVector = Icons.Rounded.BuildCircle,
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
            text = "Build logs, LSP messages, and extension host output will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Logs are path-redacted and contain no PII.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
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
                    icon = Icons.Rounded.Code,
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
                    icon = Icons.Rounded.Settings,
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
                    icon = Icons.Rounded.DatasetLinked,
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
                        Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null)
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

@Composable
private fun WorkbenchStatusBar(
    metrics: EditorMetrics,
    selectedProject: Project?,
    workspace: Workspace?,
    effectiveConfig: EffectiveConfig,
    windowInfo: JCodeWindowInfo,
) {
    Surface(
        modifier = Modifier.navigationBarsPadding(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(20.dp)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            StatusCell("branch: --")
            StatusCell("problems: 0")
            StatusCell("cursor: ${metrics.line}:${metrics.column}")
            StatusCell("lang: ${metrics.language}")
            StatusCell("enc: ${metrics.encoding}")
            StatusCell("distro: ${if (selectedProject != null) effectiveConfig.distro.id else "--"}")
            Spacer(modifier = Modifier.weight(1f))
            StatusCell(workspace?.name ?: "Workspace")
            StatusCell("posture: ${windowInfo.posture.shortLabel()}")
        }
    }
}

@Composable
private fun RowScope.StatusCell(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun rememberEditorMetrics(activeTab: EditorTab?): EditorMetrics {
    if (activeTab == null) {
        return EditorMetrics()
    }

    val carets by activeTab.editorState.carets.collectAsStateWithLifecycle()
    val snapshot by activeTab.editorState.snapshot.collectAsStateWithLifecycle()
    val caret = carets.firstOrNull()
    val offset = caret?.head ?: 0
    val (line, column) = remember(snapshot, offset) {
        snapshot.offsetToLineColumn(offset)
    }

    return EditorMetrics(
        line = line + 1,
        column = column + 1,
        language = activeTab.languageDescriptor?.name ?: "Plain Text",
        encoding = "UTF-8",
    )
}

private fun JCodePosture.shortLabel(): String = when (this) {
    JCodePosture.Flat -> "flat"
    JCodePosture.TableTop -> "tabletop"
    JCodePosture.Book -> "book"
}

private fun deviceModeLabel(windowInfo: JCodeWindowInfo): String = buildString {
    append(windowInfo.widthClass.name.lowercase())
    append(" / ")
    append(windowInfo.heightClass.name.lowercase())
    if (windowInfo.hasPhysicalKeyboard) {
        append(" / keyboard")
    }
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun CommandPalette(
    visible: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    var query by rememberSaveable { mutableStateOf("") }
    val commands = remember(query) {
        CommandRegistry.all().filter { command ->
            command.isEnabled() && fuzzyMatches(query, "${command.group} ${command.title}")
        }
    }
    val focusRequester = remember { FocusRequester() }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Type a command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commands.firstOrNull()?.action?.invoke()
                    onDismiss()
                }),
                singleLine = true,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(commands, key = CommandSpec::id) { command ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                command.action()
                                onDismiss()
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(command.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = command.group,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (compact) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            content()
        }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                content()
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

private fun fuzzyMatches(query: String, candidate: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.lowercase()
    val haystack = candidate.lowercase()
    var index = 0
    needle.forEach { ch ->
        index = haystack.indexOf(ch, startIndex = index)
        if (index < 0) return false
        index += 1
    }
    return true
}

private fun explorerViewModeOf(value: String): ExplorerViewMode =
    ExplorerViewMode.entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: ExplorerViewMode.Tree

@Composable
private fun ExtensionsPanel(
    extension: TemplateExtension,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Rounded.Extension,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(extension.name, style = MaterialTheme.typography.titleMedium)
                val meta = listOfNotNull(
                    extension.publisher,
                    extension.version?.let { "v$it" },
                    "Preloaded",
                ).joinToString(" · ")
                Text(
                    meta,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (extension.description.isNotBlank()) {
            Text(
                extension.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Text(
            "Project templates (${extension.templates.size})",
            style = MaterialTheme.typography.labelLarge,
        )
        extension.templates.forEach { template ->
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(template.name, style = MaterialTheme.typography.bodyMedium)
                if (template.description.isNotBlank()) {
                    Text(
                        template.description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (template.requires.isNotEmpty()) {
                    Text(
                        "Requires: ${template.requires.joinToString(", ")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun OpenFolderTypeDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: (isWorkspace: Boolean) -> Unit,
) {
    var isWorkspace by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set folder type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "\"$folderName\" has no .jcode config yet. Is it a Project or a Workspace?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeOption(
                        label = "Project",
                        selected = !isWorkspace,
                        onSelect = { isWorkspace = false },
                        modifier = Modifier.weight(1f),
                    )
                    TypeOption(
                        label = "Workspace",
                        selected = isWorkspace,
                        onSelect = { isWorkspace = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(isWorkspace) }) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun NewItemDialog(
    templates: List<ProjectTemplate>,
    scaffoldState: ScaffoldState,
    installedToolchains: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (MainViewModel.NewItemRequest) -> Unit,
) {
    if (scaffoldState.templateId != null) {
        ScaffoldProgressDialog(state = scaffoldState, onDismiss = onDismiss)
        return
    }

    var isWorkspace by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedTemplateId by rememberSaveable(templates) {
        mutableStateOf(templates.firstOrNull()?.id)
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Left column: folder type + name. Right column (Project): template list; (Workspace): a hint.
    val primarySection: @Composable () -> Unit = {
        Text("Folder type", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeOption(
                label = "Project",
                selected = !isWorkspace,
                onSelect = { isWorkspace = false },
                modifier = Modifier.weight(1f),
            )
            TypeOption(
                label = "Workspace",
                selected = isWorkspace,
                onSelect = { isWorkspace = true },
                modifier = Modifier.weight(1f),
            )
        }
        TextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (isWorkspace) "Workspace name" else "Project name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
    val detailSection: @Composable () -> Unit = {
        if (isWorkspace) {
            Text("Workspace", style = MaterialTheme.typography.labelLarge)
            Text(
                "A workspace is a container folder that holds multiple projects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Template", style = MaterialTheme.typography.labelLarge)
            templates.forEach { template ->
                TemplateOption(
                    template = template,
                    selected = template.id == selectedTemplateId,
                    installedToolchains = installedToolchains,
                    onSelect = { selectedTemplateId = template.id },
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (isLandscape) Modifier.fillMaxWidth(0.82f).widthIn(max = 760.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !isLandscape),
        title = { Text("New") },
        text = {
            if (isLandscape) {
                // Two columns side by side so the (tall) template list doesn't overflow the short
                // landscape dialog height.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        primarySection()
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        detailSection()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    primarySection()
                    detailSection()
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        MainViewModel.NewItemRequest(
                            name = name,
                            isWorkspace = isWorkspace,
                            templateId = if (isWorkspace) null else selectedTemplateId,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun TypeOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun TemplateOption(
    template: ProjectTemplate,
    selected: Boolean,
    installedToolchains: Set<String>,
    onSelect: () -> Unit,
) {
    val missing = template.requires.filter { it !in installedToolchains }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(template.name, style = MaterialTheme.typography.bodyLarge)
            if (template.description.isNotBlank()) {
                Text(
                    template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (template.requires.isNotEmpty()) {
                val requires = "Requires: ${template.requires.joinToString(", ")}"
                Text(
                    if (missing.isEmpty()) requires
                    else "$requires — install ${missing.joinToString(", ")} via SDK Manager",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ScaffoldProgressDialog(
    state: ScaffoldState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (state.finished) onDismiss() },
        title = {
            Text(
                when {
                    state.errorMessage != null -> "Scaffold failed"
                    state.finished -> "Project ready"
                    else -> "Scaffolding…"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    if (state.totalSteps > 0) {
                        LinearProgressIndicator(
                            progress = { state.currentStep.toFloat() / state.totalSteps.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val counter = if (state.totalSteps > 0) " (${state.currentStep}/${state.totalSteps})" else ""
                    Text(
                        "${state.currentLabel ?: "Working…"}$counter",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.logLines.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.logLines.takeLast(120).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state.finished) "Close" else "Hide")
            }
        },
    )
}

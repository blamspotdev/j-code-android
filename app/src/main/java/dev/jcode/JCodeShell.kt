package dev.jcode

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.provider.Settings
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.automirrored.rounded.MenuOpen
import androidx.compose.material.icons.rounded.Add
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
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.adaptive.JCodePosture
import dev.jcode.adaptive.JCodeWindowInfo
import dev.jcode.adaptive.JCodeWindowWidthClass
import dev.jcode.adaptive.rememberJCodeWindowInfo
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.config.ProjectConfig
import dev.jcode.core.config.WorkspaceConfig
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.design.CommandRegistry
import dev.jcode.design.CommandSpec
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorPane
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.explorer.ExplorerFeature
import dev.jcode.feature.onboarding.OnboardingFeature
import dev.jcode.feature.settings.SettingsFeature
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceManager
import dev.jcode.fs.rememberOpenFolderLauncher
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class WorkbenchTool(
    val label: String,
    val icon: ImageVector,
    val compactLabel: String = label,
) {
    Explorer("Explorer", Icons.Rounded.FolderOpen, "Files"),
    Search("Search", Icons.Rounded.Search),
    Scm("SCM", Icons.Rounded.Source, "Source"),
    RunDebug("Run/Debug", Icons.Rounded.PlayArrow, "Run"),
    Extensions("Extensions", Icons.Rounded.Extension, "Ext"),
    SdkManager("SDK Manager", Icons.Rounded.BuildCircle, "SDK"),
    Settings("Settings", Icons.Rounded.Settings),
}

private enum class BottomPanelTab(
    val label: String,
    val icon: ImageVector,
) {
    Terminal("Terminal", Icons.Rounded.Terminal),
    Problems("Problems", Icons.Rounded.SyncProblem),
    Output("Output", Icons.AutoMirrored.Rounded.Article),
    Tasks("Tasks", Icons.Rounded.Radar),
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
    val selectedProject by viewModel.selectedProject.collectAsStateWithLifecycle()
    val editorGroup by viewModel.editorGroup.collectAsStateWithLifecycle()
    val showCreateDialog by viewModel.showCreateProjectDialog.collectAsStateWithLifecycle()
    val workspaceConfig by viewModel.workspaceConfig.collectAsStateWithLifecycle()
    val projectConfig by viewModel.projectConfig.collectAsStateWithLifecycle()
    val effectiveConfig by viewModel.effectiveConfig.collectAsStateWithLifecycle()
    val workspaceConfigError by viewModel.workspaceConfigError.collectAsStateWithLifecycle()
    val projectConfigError by viewModel.projectConfigError.collectAsStateWithLifecycle()
    val environmentState by viewModel.environmentState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val openFolderLauncher = rememberOpenFolderLauncher(
        workspaceManager = viewModel.workspaceManager,
        onSafWarning = { warning ->
            scope.launch { snackbarHostState.showSnackbar(warning) }
        },
    )
    var environmentWizardVisible by rememberSaveable { mutableStateOf(false) }

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
        workspaceManager = viewModel.workspaceManager,
        snackbarHostState = snackbarHostState,
        openFolderLauncher = openFolderLauncher,
        onCreateProject = viewModel::requestCreateProject,
        onRemoveProject = viewModel::removeProject,
        onSelectProject = viewModel::selectProject,
        onOpenFile = viewModel::openFile,
        onSelectEditorTab = viewModel::selectEditorTab,
        onCloseEditorTab = viewModel::closeEditorTab,
        onUpdateEditorFontSize = viewModel::updateEditorFontSize,
        onUpdateEditorTabSize = viewModel::updateEditorTabSize,
        onUpdateEditorWordWrap = viewModel::updateEditorWordWrap,
        onUpdateEditorMinimap = viewModel::updateEditorMinimap,
        onUpdateEditorLigatures = viewModel::updateEditorLigatures,
        onOpenWorkspaceConfig = viewModel::openWorkspaceConfigFile,
        onOpenProjectConfig = viewModel::openProjectConfigFile,
        onRefreshEnvironment = viewModel::refreshEnvironment,
        onOpenEnvironmentWizard = { environmentWizardVisible = true },
    )

    if (showCreateDialog) {
        CreateProjectDialog(
            onDismiss = viewModel::dismissCreateProjectDialog,
            onConfirm = viewModel::createProject,
        )
    }

    if (environmentWizardVisible) {
        OnboardingFeature.TermuxDistroWizard(
            environmentState = environmentState,
            onDismiss = { environmentWizardVisible = false },
            onRefresh = viewModel::refreshEnvironment,
            onSelectDistro = viewModel::selectWizardDistro,
            onRunStep = viewModel::runEnvironmentStep,
            onInstallTermuxFromFdroid = {
                context.openExternalUrl("https://f-droid.org/en/packages/com.termux/")
            },
            onOpenTermuxGitHubReleases = {
                context.openExternalUrl("https://github.com/termux/termux-app/releases")
            },
            onInstallTermuxApi = {
                context.openExternalUrl("https://f-droid.org/en/packages/com.termux.api/")
            },
            onGrantRunCommandPermission = {
                context.openAppDetailsSettings()
            },
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
    workspaceManager: WorkspaceManager,
    snackbarHostState: SnackbarHostState,
    openFolderLauncher: ActivityResultLauncher<Uri?>,
    onCreateProject: () -> Unit,
    onRemoveProject: (Long) -> Unit,
    onSelectProject: (Long) -> Unit,
    onOpenFile: (dev.jcode.fs.FsNode) -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onUpdateEditorFontSize: (ConfigScope, Float) -> Unit,
    onUpdateEditorTabSize: (ConfigScope, Int) -> Unit,
    onUpdateEditorWordWrap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorMinimap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorLigatures: (ConfigScope, Boolean) -> Unit,
    onOpenWorkspaceConfig: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val configuration = LocalConfiguration.current
    val focusRequester = remember { FocusRequester() }
    val compactDrawerState = rememberDrawerState(DrawerValue.Closed)
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val usesModalWorkspace = !isLandscape
    val canShowRightSidebar = isLandscape
    val leftSidebarWidth = if (windowInfo.widthClass == JCodeWindowWidthClass.Expanded) 284.dp else 236.dp
    val rightSidebarWidth = if (windowInfo.widthClass == JCodeWindowWidthClass.Expanded) 216.dp else 188.dp
    val activeTab = editorGroup.activeTab
    val metrics = rememberEditorMetrics(activeTab)

    var selectedTool by rememberSaveable { mutableStateOf(WorkbenchTool.Explorer) }
    var leftSidebarExpanded by rememberSaveable(isLandscape, windowInfo.widthClass) {
        mutableStateOf(isLandscape && windowInfo.widthClass == JCodeWindowWidthClass.Expanded)
    }
    var rightSidebarVisible by rememberSaveable(canShowRightSidebar, windowInfo.widthClass) {
        mutableStateOf(canShowRightSidebar)
    }
    var bottomPanelTab by rememberSaveable { mutableStateOf(BottomPanelTab.Terminal) }
    var bottomPanelExpanded by rememberSaveable(usesModalWorkspace, windowInfo.hasPhysicalKeyboard) {
        mutableStateOf(!usesModalWorkspace || windowInfo.hasPhysicalKeyboard)
    }
    var commandPaletteVisible by rememberSaveable { mutableStateOf(false) }

    DisposableEffect(onCreateProject, openFolderLauncher, onOpenWorkspaceConfig, onOpenProjectConfig, selectedProject?.id) {
        CommandRegistry.clear()
        CommandRegistry.register(
            id = "workspace.addProject",
            title = "Add Project",
            group = "Workspace",
            action = onCreateProject,
        )
        CommandRegistry.register(
            id = "workspace.openExternalFolder",
            title = "Open External Folder",
            group = "Workspace",
            action = { openFolderLauncher.launch(null) },
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

    val drawerContent: @Composable () -> Unit = {
        ModalDrawerSheet(
            modifier = Modifier.width(384.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surface,
        ) {
            Row(modifier = Modifier.fillMaxHeight()) {
                DockRail(
                    selectedTool = selectedTool,
                    onToolSelected = {
                        selectedTool = it
                        if (isLandscape) {
                            leftSidebarExpanded = true
                        }
                        scope.launch { compactDrawerState.close() }
                    },
                    compact = true,
                    modifier = Modifier.fillMaxHeight(),
                )
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
                    workspaceManager = workspaceManager,
                    windowInfo = windowInfo,
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f),
                    onCreateProject = onCreateProject,
                    onRemoveProject = onRemoveProject,
                    onSelectProject = onSelectProject,
                    onOpenExternalFolder = { openFolderLauncher.launch(null) },
                    onOpenFile = onOpenFile,
                    onUpdateEditorFontSize = onUpdateEditorFontSize,
                    onUpdateEditorTabSize = onUpdateEditorTabSize,
                    onUpdateEditorWordWrap = onUpdateEditorWordWrap,
                    onUpdateEditorMinimap = onUpdateEditorMinimap,
                    onUpdateEditorLigatures = onUpdateEditorLigatures,
                    onOpenWorkspaceConfig = onOpenWorkspaceConfig,
                    onOpenProjectConfig = onOpenProjectConfig,
                    onRefreshEnvironment = onRefreshEnvironment,
                    onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                    onSnackbar = { message ->
                        scope.launch { snackbarHostState.showSnackbar(message) }
                    },
                )
            }
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
                if (!usesModalWorkspace) {
                    DockRail(
                        selectedTool = selectedTool,
                        onToolSelected = {
                            selectedTool = it
                            leftSidebarExpanded = true
                        },
                        compact = true,
                        modifier = Modifier.fillMaxHeight(),
                    )
                    if (leftSidebarExpanded) {
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
                            workspaceManager = workspaceManager,
                            windowInfo = windowInfo,
                            modifier = Modifier
                                .fillMaxHeight()
                                .width(leftSidebarWidth),
                            onCreateProject = onCreateProject,
                            onRemoveProject = onRemoveProject,
                            onSelectProject = onSelectProject,
                            onOpenExternalFolder = { openFolderLauncher.launch(null) },
                            onOpenFile = onOpenFile,
                            onUpdateEditorFontSize = onUpdateEditorFontSize,
                            onUpdateEditorTabSize = onUpdateEditorTabSize,
                            onUpdateEditorWordWrap = onUpdateEditorWordWrap,
                            onUpdateEditorMinimap = onUpdateEditorMinimap,
                            onUpdateEditorLigatures = onUpdateEditorLigatures,
                            onOpenWorkspaceConfig = onOpenWorkspaceConfig,
                            onOpenProjectConfig = onOpenProjectConfig,
                            onRefreshEnvironment = onRefreshEnvironment,
                            onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                            onSnackbar = { message ->
                                scope.launch { snackbarHostState.showSnackbar(message) }
                            },
                        )
                    }
                }

                EditorWorkspace(
                    windowInfo = windowInfo,
                    workspace = workspace,
                    selectedProject = selectedProject,
                    editorGroup = editorGroup,
                    activeTab = activeTab,
                    metrics = metrics,
                    effectiveConfig = effectiveConfig,
                    isLandscape = isLandscape,
                    leftSidebarExpanded = leftSidebarExpanded,
                    canShowRightSidebar = canShowRightSidebar,
                    rightSidebarVisible = rightSidebarVisible,
                    modifier = Modifier.weight(1f),
                    onToggleLeftSidebar = {
                        if (usesModalWorkspace) {
                            scope.launch { compactDrawerState.open() }
                        } else {
                            leftSidebarExpanded = !leftSidebarExpanded
                        }
                    },
                    onToggleRightSidebar = {
                        if (canShowRightSidebar) {
                            rightSidebarVisible = !rightSidebarVisible
                        }
                    },
                    onToolSelected = { selectedTool = it },
                    onCreateProject = onCreateProject,
                    onOpenExternalFolder = { openFolderLauncher.launch(null) },
                    onShowCommandPalette = { commandPaletteVisible = true },
                    onShowProblems = {
                        bottomPanelTab = BottomPanelTab.Problems
                        bottomPanelExpanded = true
                    },
                    onToggleBottomPanel = { bottomPanelExpanded = !bottomPanelExpanded },
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
                    selectedBottomPanelTab = bottomPanelTab,
                    onBottomPanelTabSelected = { bottomPanelTab = it },
                    showBottomPanel = bottomPanelExpanded,
                )

                if (canShowRightSidebar && rightSidebarVisible) {
                    InspectorSidebar(
                        selectedProject = selectedProject,
                        activeTab = activeTab,
                        editorGroup = editorGroup,
                        effectiveConfig = effectiveConfig,
                        windowInfo = windowInfo,
                        selectedTool = selectedTool,
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(rightSidebarWidth),
                        onShowCommandPalette = { commandPaletteVisible = true },
                        onCreateProject = onCreateProject,
                        onOpenExternalFolder = { openFolderLauncher.launch(null) },
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (usesModalWorkspace) {
            ModalNavigationDrawer(
                drawerState = compactDrawerState,
                drawerContent = drawerContent,
                content = content,
            )
        } else {
            content()
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
            Surface(
                modifier = Modifier.size(38.dp),
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "JC",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

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
    workspaceManager: WorkspaceManager,
    windowInfo: JCodeWindowInfo,
    onCreateProject: () -> Unit,
    onRemoveProject: (Long) -> Unit,
    onSelectProject: (Long) -> Unit,
    onOpenExternalFolder: () -> Unit,
    onOpenFile: (dev.jcode.fs.FsNode) -> Unit,
    onUpdateEditorFontSize: (ConfigScope, Float) -> Unit,
    onUpdateEditorTabSize: (ConfigScope, Int) -> Unit,
    onUpdateEditorWordWrap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorMinimap: (ConfigScope, Boolean) -> Unit,
    onUpdateEditorLigatures: (ConfigScope, Boolean) -> Unit,
    onOpenWorkspaceConfig: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            WorkspaceHeader(
                selectedTool = selectedTool,
                workspace = workspace,
                selectedProject = selectedProject,
                onCreateProject = onCreateProject,
                onOpenExternalFolder = onOpenExternalFolder,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            AnimatedContent(
                targetState = selectedTool,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "workspace-tool",
            ) { tool ->
                when (tool) {
                    WorkbenchTool.Explorer -> {
                        if (selectedProject == null) {
                            WorkspaceEmptyState(
                                workspace = workspace,
                                onCreateProject = onCreateProject,
                                onOpenExternalFolder = onOpenExternalFolder,
                            )
                        } else {
                            Column(modifier = Modifier.fillMaxSize()) {
                                ProjectRoster(
                                    projects = workspace?.projects.orEmpty(),
                                    selectedProjectId = selectedProject.id,
                                    editorGroup = editorGroup,
                                    onSelectProject = onSelectProject,
                                    onRemoveProject = onRemoveProject,
                                )
                                HorizontalDivider(
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                                )
                                Box(modifier = Modifier.weight(1f)) {
                                    ExplorerFeature.Content(
                                        workspace = workspace,
                                        project = selectedProject,
                                        fs = workspaceManager.fsFor(selectedProject.fsPath),
                                        context = context,
                                        modifier = Modifier.fillMaxSize(),
                                        onFileSelected = onOpenFile,
                                        onSnackbar = onSnackbar,
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

                    WorkbenchTool.Extensions -> ToolPanelPlaceholder(
                        title = "Extensions",
                        icon = Icons.Rounded.Extension,
                        lines = listOf(
                            "Surface marketplace, sideload status, and extension permissions here.",
                            "Better UI: separate installed, recommended, and blocked-by-policy sections.",
                            "WASM runtime health and storage usage belong in a footer card.",
                        ),
                    )

                    WorkbenchTool.SdkManager -> ToolPanelPlaceholder(
                        title = "SDK Manager",
                        icon = Icons.Rounded.BuildCircle,
                        lines = listOf(
                            "Track Android SDK, NDK, CMake, and distro toolchain readiness.",
                            "Useful quick info: missing packages, last sync, free space, and target ABI matrix.",
                            "On desktop mode, show a denser two-column package table with filter chips.",
                        ),
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
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = selectedTool.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = workspace?.name ?: "Default Workspace",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = selectedProject?.distroBindTarget ?: "No bind target yet",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
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
                text = "Start with a local project or a SAF-backed external folder.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorkbenchActionButton(text = "Add Project", onClick = onCreateProject, active = true)
            WorkbenchActionButton(text = "Open Folder", onClick = onOpenExternalFolder)
        }
    }
}

@Composable
private fun ProjectRoster(
    projects: List<Project>,
    selectedProjectId: Long,
    editorGroup: EditorGroup,
    onSelectProject: (Long) -> Unit,
    onRemoveProject: (Long) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Projects",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "${editorGroup.tabs.size} open tabs",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            projects.forEach { project ->
                val selected = project.id == selectedProjectId
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onSelectProject(project.id) },
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f)
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Surface(
                            modifier = Modifier.size(30.dp),
                            shape = RoundedCornerShape(10.dp),
                            color = if (selected) {
                                MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
                            } else {
                                MaterialTheme.colorScheme.surfaceVariant
                            },
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.Code,
                                    contentDescription = null,
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
                        WorkbenchActionButton(text = "Remove", onClick = { onRemoveProject(project.id) })
                    }
                }
            }
        }
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
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
    onShowCommandPalette: () -> Unit,
    onShowProblems: () -> Unit,
    onToggleBottomPanel: () -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onOpenFileRequest: () -> Unit,
    selectedBottomPanelTab: BottomPanelTab,
    onBottomPanelTabSelected: (BottomPanelTab) -> Unit,
    showBottomPanel: Boolean,
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
                showBottomPanel = showBottomPanel,
                onToggleLeftSidebar = onToggleLeftSidebar,
                onToggleRightSidebar = onToggleRightSidebar,
                onShowCommandPalette = onShowCommandPalette,
                onShowProblems = onShowProblems,
                onToggleBottomPanel = onToggleBottomPanel,
                onToolSelected = onToolSelected,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.96f)),
            ) {
                if (editorGroup.tabs.isEmpty()) {
                    WorkbenchWelcomePane(
                        selectedProject = selectedProject,
                        onCreateProject = onCreateProject,
                        onOpenExternalFolder = onOpenExternalFolder,
                        onShowCommandPalette = onShowCommandPalette,
                    )
                } else {
                    EditorPane(
                        group = editorGroup,
                        modifier = Modifier.fillMaxSize(),
                        onTabSelected = onSelectEditorTab,
                        onTabClosed = onCloseEditorTab,
                        onOpenFile = onOpenFileRequest,
                    )
                }
            }

            if (showBottomPanel) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                BottomWorkbenchPanel(
                    selectedTab = selectedBottomPanelTab,
                    selectedProject = selectedProject,
                    compact = windowInfo.widthClass == JCodeWindowWidthClass.Compact,
                    onTabSelected = onBottomPanelTabSelected,
                    onHide = onToggleBottomPanel,
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
    showBottomPanel: Boolean,
    onToggleLeftSidebar: () -> Unit,
    onToggleRightSidebar: () -> Unit,
    onShowCommandPalette: () -> Unit,
    onShowProblems: () -> Unit,
    onToggleBottomPanel: () -> Unit,
    onToolSelected: (WorkbenchTool) -> Unit,
) {
    val compactCursorLabel = "L${metrics.line}:C${metrics.column}"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
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

                WorkbenchActionButton(
                    text = "Panel",
                    onClick = onToggleBottomPanel,
                    active = showBottomPanel,
                )
                if (canShowRightSidebar) {
                    WorkbenchActionButton(
                        text = "Right",
                        onClick = onToggleRightSidebar,
                        active = rightSidebarVisible,
                    )
                }
                WorkbenchIconActionButton(
                    icon = Icons.Rounded.SyncProblem,
                    contentDescription = "Problems",
                    onClick = onShowProblems,
                )
                WorkbenchIconActionButton(
                    icon = Icons.Rounded.PlayArrow,
                    contentDescription = "Run",
                    onClick = { onToolSelected(WorkbenchTool.RunDebug) },
                )
                WorkbenchActionButton(text = "Cmd", onClick = onShowCommandPalette)
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
private fun WorkbenchWelcomePane(
    selectedProject: Project?,
    onCreateProject: () -> Unit,
    onOpenExternalFolder: () -> Unit,
    onShowCommandPalette: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Workspace shell",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = selectedProject?.name ?: "No editor tab is open for the selected project.",
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "Next useful actions are file open, environment setup, and recent task history.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchActionButton(text = "Add Project", onClick = onCreateProject, active = true)
                WorkbenchActionButton(text = "Open Folder", onClick = onOpenExternalFolder)
                WorkbenchActionButton(text = "Cmd", onClick = onShowCommandPalette)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            WelcomeCard(
                modifier = Modifier.weight(1f),
                title = "Useful next",
                icon = Icons.Rounded.Terminal,
                lines = listOf(
                    "Open a file from Explorer",
                    "Run environment setup from Settings",
                    "Wire command history into this pane",
                ),
            )
            WelcomeCard(
                modifier = Modifier.weight(1f),
                title = "Planned shell",
                icon = Icons.Rounded.Code,
                lines = listOf(
                    "Editor-first on phone widths",
                    "Persistent rail + side pane on larger widths",
                    "Bottom panel reserved for terminal and logs",
                ),
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
private fun BottomWorkbenchPanel(
    selectedTab: BottomPanelTab,
    selectedProject: Project?,
    compact: Boolean,
    onTabSelected: (BottomPanelTab) -> Unit,
    onHide: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(if (compact) 132.dp else 140.dp),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                BottomPanelTab.entries.forEach { tab ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (tab == selectedTab) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.18f)
                        },
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onTabSelected(tab) },
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                        ) {
                            Icon(
                                imageVector = tab.icon,
                                contentDescription = null,
                                modifier = Modifier.size(13.dp),
                                tint = if (tab == selectedTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = tab.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (tab == selectedTab) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                WorkbenchActionButton(text = "Hide", onClick = onHide)
            }

            AnimatedContent(
                targetState = selectedTab,
                transitionSpec = { fadeIn() togetherWith fadeOut() },
                label = "bottom-panel",
            ) { tab ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    when (tab) {
                        BottomPanelTab.Terminal -> {
                            Text("Terminal pane", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = selectedProject?.distroBindTarget?.let { "Target bind: $it" }
                                    ?: "Select a project to attach the future distro shell.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Next Phase 7 step: replace this placeholder with a real PTY session and terminal tabs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        BottomPanelTab.Problems -> {
                            Text("Problems pane", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Show diagnostics, task failures, and quick jump-to-line actions here.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Preferred desktop shape: dense filterable table with source, severity, and file columns.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        BottomPanelTab.Output -> {
                            Text("Output stream", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Reserve this for Gradle, native build, LSP, and extension host logs.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "Keep logs path-redacted and mirror long-running state into the status bar.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }

                        BottomPanelTab.Tasks -> {
                            Text("Task runner", fontWeight = FontWeight.SemiBold)
                            Text(
                                text = "Good first actions: assembleDebug, npm dev, cargo test, and open config YAML.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "For larger windows, a dense queue with last result and duration beats large cards.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

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
                    WorkbenchActionButton(text = "Create Project", onClick = onCreateProject)
                    WorkbenchActionButton(text = "Attach Folder", onClick = onOpenExternalFolder)
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
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.75f)) {
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

private fun Context.openExternalUrl(url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
}

private fun Context.openAppDetailsSettings() {
    val intent = Intent(
        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
        Uri.fromParts("package", packageName, null),
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { startActivity(intent) }
        .onFailure {
            if (it is ActivityNotFoundException) return
        }
}

@Composable
private fun CreateProjectDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New Project") },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Project name") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(
                    onDone = {
                        if (name.isNotBlank()) onConfirm(name)
                    },
                ),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name) },
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

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

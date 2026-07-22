package dev.jcode
import androidx.compose.foundation.isSystemInDarkTheme
import dev.jcode.core.editor.EditorLanguageAction
import dev.jcode.core.editor.decor.Layer
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.EditorSaveActions
import dev.jcode.design.IconBundleRegistry
import dev.jcode.core.editor.completion.LocalCompletionSource
import dev.jcode.editor.languagePackCompletionItems
import dev.jcode.design.LocalEditorSaveActions
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JcTooltip
import dev.jcode.design.EditorDragSetting
import dev.jcode.design.LocalEditorDragMovesCursor
import dev.jcode.design.LocalRestoreSession
import dev.jcode.design.LocalTabCloseButtonSetting
import dev.jcode.design.BottomBarSetting
import dev.jcode.design.EditorTabActions
import dev.jcode.design.FontOption
import dev.jcode.design.FontSettings
import dev.jcode.design.LocalEditorTabActions
import dev.jcode.design.LocalEditorTypeface
import dev.jcode.design.LocalFontSettings
import dev.jcode.design.LocalTerminalTypeface
import dev.jcode.design.ExtraKeysSetting
import dev.jcode.design.ExtraKeysState
import dev.jcode.design.LocalBottomBarSetting
import dev.jcode.design.LocalExtraKeysGlyphFontFamily
import dev.jcode.design.LocalExtraKeysSetting
import dev.jcode.design.LocalExtraKeysState
import dev.jcode.design.RestoreSessionSetting
import dev.jcode.design.TabCloseButtonSetting
import dev.jcode.editor.SyntaxHighlighter
import dev.jcode.editor.TokenPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import dev.jcode.design.ThemeBundleRegistry
import dev.jcode.design.jcIcon

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.isImeVisible
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.DialogProperties
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.jcode.adaptive.JCodeWindowInfo
import dev.jcode.adaptive.JCodeWindowHeightClass
import dev.jcode.adaptive.JCodeWindowWidthClass
import dev.jcode.adaptive.rememberJCodeWindowInfo
import dev.jcode.core.config.ConfigScope
import dev.jcode.core.config.EffectiveConfig
import dev.jcode.core.config.ProjectConfig
import dev.jcode.core.config.BuildConfig as RunBuildConfig
import dev.jcode.core.config.RunConfig
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
import dev.jcode.design.ChromeControls
import dev.jcode.design.CommandPaletteSetting
import dev.jcode.design.CommandRegistry
import dev.jcode.design.LocalChromeControls
import dev.jcode.design.LocalCommandPaletteSetting
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import dev.jcode.design.DeveloperSetting
import dev.jcode.design.LocalDeveloperSetting
import dev.jcode.design.EditorFontSizeSetting
import dev.jcode.design.EditorWordWrapSetting
import dev.jcode.design.LocalEditorFontSizeSetting
import dev.jcode.design.LocalEditorWordWrapSetting
import dev.jcode.design.LocalMarkdownPreviewSetting
import dev.jcode.workbench.ExtensionDevState
import dev.jcode.workbench.LocalExtensionDevState
import dev.jcode.design.MarkdownPreviewSetting
import dev.jcode.design.ThemeMode
import dev.jcode.feature.editor.pane.EditorGroup
import dev.jcode.feature.editor.pane.EditorMenuContribution
import dev.jcode.feature.editor.pane.EditorMenuExtras
import dev.jcode.feature.editor.pane.EditorPageKind
import dev.jcode.feature.editor.pane.EditorPane
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.editor.pane.LocalEditorMenuExtras
import dev.jcode.feature.explorer.ExplorerFeature
import dev.jcode.feature.explorer.ExplorerViewMode
import dev.jcode.feature.marketplace.ExtensionType
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.languageFor
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.isUpdateAvailable
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.ScaffoldState
import dev.jcode.feature.onboarding.EnvironmentManagerActions
import dev.jcode.feature.onboarding.LocalEnvironmentManager
import dev.jcode.feature.onboarding.OnboardingFeature
import dev.jcode.feature.debug.DebugEngineManagerFeature
import dev.jcode.feature.lspmanager.LspManagerFeature
import dev.jcode.feature.sdkmanager.SdkManagerFeature
import dev.jcode.feature.settings.SettingsFeature
import dev.jcode.workbench.dialog.ImportProgressHost
import dev.jcode.workbench.dialog.NewItemDialog
import dev.jcode.workbench.dialog.PostCloneDialog
import dev.jcode.workbench.dialog.OpenFolderTypeDialog
import dev.jcode.feature.marketplace.ExtensionActivation
import dev.jcode.workbench.marketplace.ExtensionActivationSetting
import dev.jcode.workbench.marketplace.ExtensionCapabilitySetting
import dev.jcode.workbench.marketplace.ExtensionKeepAliveSetting
import dev.jcode.workbench.marketplace.LocalExtensionCapabilities
import dev.jcode.workbench.marketplace.LocalExtensionKeepAlive
import dev.jcode.workbench.ExtensionWebViewPage
import dev.jcode.workbench.BrowserPage
import dev.jcode.workbench.BuiltinBrowser
import dev.jcode.workbench.DevtoolsSidebarContent
import dev.jcode.workbench.ExtensionDevSidebarContent
import dev.jcode.workbench.ImageViewerPage
import dev.jcode.workbench.MarkdownPreviewPage
import dev.jcode.workbench.SearchToolPanel
import dev.jcode.workbench.marketplace.DbManagerPanel
import dev.jcode.workbench.marketplace.hasDbManagerClient
import dev.jcode.workbench.marketplace.ScmPanel
import dev.jcode.workbench.marketplace.hasScmClient
import dev.jcode.workbench.marketplace.VmPanel
import dev.jcode.workbench.marketplace.hasVmManagerClient
import dev.jcode.workbench.marketplace.ExtensionDetailPage
import dev.jcode.workbench.marketplace.ExtensionPermissionsPage
import dev.jcode.workbench.marketplace.LocalExtensionActivation
import dev.jcode.workbench.marketplace.ExtensionsPanel
import dev.jcode.workbench.DebugEditorState
import dev.jcode.workbench.DebugSessionUi
import dev.jcode.workbench.LocalDebugCatalogState
import dev.jcode.workbench.LocalDebugEditorState
import dev.jcode.workbench.LocalDebugSession
import dev.jcode.workbench.LocalExtensionInstallPhases
import dev.jcode.workbench.LocalPendingReload
import dev.jcode.workbench.PendingReloadUi
import dev.jcode.workbench.LocalRunConfigPresets
import dev.jcode.workbench.LocalSetupTerminalSessionId
import dev.jcode.design.EnvVarSettings
import dev.jcode.design.PerformanceSettings
import dev.jcode.design.ExtensionSettingSpec
import dev.jcode.design.ExtensionSettingsGroup
import dev.jcode.design.ExtensionSettingsUi
import dev.jcode.design.CutoutSetting
import dev.jcode.design.EditorTabColors
import dev.jcode.design.LocalEditorTabColors
import dev.jcode.design.LocalTabColoringSetting
import dev.jcode.design.LocalTabMaxSize
import dev.jcode.design.MiddleEllipsisText
import dev.jcode.design.TabColoring
import dev.jcode.design.TabColoringSetting
import dev.jcode.design.TabMaxSizeSetting
import dev.jcode.design.ExplorerExcludeEffect
import dev.jcode.design.ExplorerHiddenMode
import dev.jcode.design.ExplorerHiddenSetting
import dev.jcode.design.ExtraKey
import dev.jcode.design.AppUpdateSetting
import dev.jcode.design.LocalAppUpdate
import dev.jcode.design.LocalSettingsBackup
import dev.jcode.design.SettingsBackupActions
import dev.jcode.design.EnvironmentBackupActions
import dev.jcode.design.LocalEnvironmentBackup
import dev.jcode.design.LocalCutoutSetting
import dev.jcode.design.LocalExplorerHiddenSetting
import dev.jcode.design.LocalVolumeKeysSetting
import dev.jcode.design.VolumeKeyAction
import dev.jcode.design.VolumeKeysSetting
import dev.jcode.design.LocalExtensionSettingsUi
import dev.jcode.design.LocalEnvVarSettings
import dev.jcode.design.LocalPerformanceSettings
import dev.jcode.design.WebPreviewBrowsers
import dev.jcode.design.LocalWebPreviewBrowsers
import dev.jcode.workbench.TerminalInstance
import dev.jcode.workbench.WorkbenchTopBar
import dev.jcode.workbench.WorkspaceHeader
import dev.jcode.workbench.ProjectRoster
import dev.jcode.workbench.WelcomeCard
import dev.jcode.workbench.WorkspaceEmptyState
import dev.jcode.workbench.SidebarToolButton
import dev.jcode.workbench.WorkbenchActionButton
import dev.jcode.workbench.WorkbenchIconActionButton
import dev.jcode.workbench.AgentChatActions
import dev.jcode.workbench.AgentChatSidebarContent
import dev.jcode.workbench.AgentChatWebViewHolder
import dev.jcode.workbench.ScmBackgroundHost
import dev.jcode.workbench.ScmWebViewHolder
import dev.jcode.workbench.agentChatTabTitle
import dev.jcode.workbench.hasAgentChatExtension
import dev.jcode.workbench.LocalAgentChatActions
import dev.jcode.workbench.CloseTarget
import dev.jcode.workbench.IssueActions
import dev.jcode.workbench.LocalIssueActions
import dev.jcode.core.debug.DebugState
import dev.jcode.core.distro.DebugEngineCatalog
import dev.jcode.workbench.LocalTerminalTapConfig
import dev.jcode.workbench.TerminalExtraKeysTarget
import dev.jcode.workbench.WorkbenchExtraKeysBar
import dev.jcode.workbench.WorkbenchSnackbarHost
import dev.jcode.workbench.BottomStatusBarSlot
import dev.jcode.workbench.RightPanelTab
import dev.jcode.workbench.WorkbenchManagerActions
import dev.jcode.workbench.WorkbenchRunActions
import dev.jcode.workbench.TerminalTapConfig
import dev.jcode.workbench.WorkbenchTool
import dev.jcode.feature.explorer.ExplorerContextAction
import dev.jcode.feature.explorer.ExplorerScmUi
import dev.jcode.feature.explorer.LocalExplorerScmUi
import dev.jcode.feature.marketplace.hasWebUi
import dev.jcode.feature.marketplace.webUiFile
import dev.jcode.fs.FsKind
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import dev.jcode.fs.ProjectKind
import dev.jcode.fs.RecentEntity
import dev.jcode.run.ProjectRunner
import dev.jcode.run.BuildConfigPage
import dev.jcode.run.RunConfigPage
import dev.jcode.fs.Workspace
import dev.jcode.fs.WorkspaceCrumb
import dev.jcode.fs.WorkspaceManager
import dev.jcode.fs.WorkspaceNodeType
import dev.jcode.fs.rememberOpenFolderLauncher
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.SharedFlow
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
    val pendingEditorClose by viewModel.pendingEditorClose.collectAsStateWithLifecycle()
    val showNewItemDialog by viewModel.showNewItemDialog.collectAsStateWithLifecycle()
    val postClonePrompt by viewModel.postClonePrompt.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val workspaceConfig by viewModel.workspaceConfig.collectAsStateWithLifecycle()
    val projectConfig by viewModel.projectConfig.collectAsStateWithLifecycle()
    val effectiveConfig by viewModel.effectiveConfig.collectAsStateWithLifecycle()
    val workspaceConfigError by viewModel.workspaceConfigError.collectAsStateWithLifecycle()
    val projectConfigError by viewModel.projectConfigError.collectAsStateWithLifecycle()
    val environmentState by viewModel.environmentState.collectAsStateWithLifecycle()
    val installedEnvironments by viewModel.environments.collectAsStateWithLifecycle()
    val sdkCatalogState by viewModel.sdkCatalogState.collectAsStateWithLifecycle()
    val lspCatalogState by viewModel.lspCatalogState.collectAsStateWithLifecycle()
    val debugCatalogState by viewModel.debugCatalogState.collectAsStateWithLifecycle()
    val breakpoints by viewModel.breakpoints.collectAsStateWithLifecycle()
    val debugLocation by viewModel.debugLocation.collectAsStateWithLifecycle()
    val debugState by viewModel.debugState.collectAsStateWithLifecycle()
    val debugCallStack by viewModel.debugCallStack.collectAsStateWithLifecycle()
    val debugVariables by viewModel.debugVariables.collectAsStateWithLifecycle()
    val debugOutput by viewModel.debugOutput.collectAsStateWithLifecycle()
    val runConfigVersion by viewModel.runConfigVersion.collectAsStateWithLifecycle()
    val autoSetupProgress by viewModel.autoSetupProgress.collectAsStateWithLifecycle(initialValue = DistroWizardProgress.Idle)
    val installedExtensions by viewModel.installedExtensions.collectAsStateWithLifecycle()
    // Extensions whose contributions are active (not set to Manual) — used for all language-pack lookups
    // so the per-extension activation mode actually gates highlighting/completions/formatting.
    val activeLanguageExtensions by viewModel.activeLanguageExtensions.collectAsStateWithLifecycle()
    val extensionActivations by viewModel.extensionActivations.collectAsStateWithLifecycle()
    val extensionActivationSetting = remember(extensionActivations) {
        ExtensionActivationSetting(
            modeFor = { id -> extensionActivations[id] ?: ExtensionActivation.Default },
            onChange = viewModel::setExtensionActivation,
        )
    }
    val extensionCapabilityDenials by viewModel.extensionCapabilityDenials.collectAsStateWithLifecycle()
    val extensionCapabilitySetting = remember(extensionCapabilityDenials) {
        ExtensionCapabilitySetting(
            grantedFor = { id, capability -> capability !in (extensionCapabilityDenials[id] ?: emptySet()) },
            onSetGranted = viewModel::setExtensionCapability,
        )
    }
    val extensionKeepAliveDisabled by viewModel.extensionKeepAliveDisabled.collectAsStateWithLifecycle()
    val extensionKeepAliveSetting = remember(extensionKeepAliveDisabled) {
        ExtensionKeepAliveSetting(
            enabledFor = { id -> id !in extensionKeepAliveDisabled },
            onSetEnabled = { id, enabled ->
                viewModel.setExtensionKeepAlive(id, enabled)
                // Turning it off tears down the persisted WebView so it can't linger detached.
                if (!enabled) AgentChatWebViewHolder.destroy(id)
            },
        )
    }
    val extensionSettings by viewModel.extensionSettings.collectAsStateWithLifecycle()
    val extensionSettingsUi = remember(installedExtensions, extensionSettings) {
        ExtensionSettingsUi(
            groups = installedExtensions.filter { it.settings.isNotEmpty() }.map { ext ->
                ExtensionSettingsGroup(
                    extensionId = ext.id,
                    extensionName = ext.name,
                    specs = ext.settings.map { s ->
                        ExtensionSettingSpec(
                            key = s.key,
                            label = s.label,
                            type = s.type.name.lowercase(),
                            options = s.options,
                            default = s.default ?: "",
                            description = s.description,
                        )
                    },
                )
            },
            valueOf = viewModel::extensionSettingValue,
            onChange = viewModel::setExtensionSetting,
        )
    }
    val marketplaceEntries by viewModel.marketplaceEntries.collectAsStateWithLifecycle()
    val marketplaceBusy by viewModel.marketplaceBusy.collectAsStateWithLifecycle()
    val extensionInstallPhases by viewModel.extensionInstallPhases.collectAsStateWithLifecycle()
    val setupTerminalSessionId by viewModel.setupTerminalRunner.sessionId.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themeBundleId by viewModel.themeBundleId.collectAsStateWithLifecycle()
    val iconBundleId by viewModel.iconBundleId.collectAsStateWithLifecycle()
    val formatterId by viewModel.formatterId.collectAsStateWithLifecycle()
    val hardwareAcceleration by viewModel.hardwareAcceleration.collectAsStateWithLifecycle()
    val confirmCloseRunning by viewModel.confirmCloseRunning.collectAsStateWithLifecycle()
    val autoCloseIdleTerminals by viewModel.autoCloseIdleTerminals.collectAsStateWithLifecycle()
    val idleTimeoutMinutes by viewModel.idleTimeoutMinutes.collectAsStateWithLifecycle()
    val maxTerminalSessions by viewModel.maxTerminalSessions.collectAsStateWithLifecycle()
    val nestedShellTabs by viewModel.nestedShellTabs.collectAsStateWithLifecycle()
    val installTimeoutMinutes by viewModel.installTimeoutMinutes.collectAsStateWithLifecycle()
    val exitOnSwipeAway by viewModel.exitOnSwipeAway.collectAsStateWithLifecycle()
    val performanceSettings = remember(hardwareAcceleration, confirmCloseRunning, autoCloseIdleTerminals, idleTimeoutMinutes, maxTerminalSessions, nestedShellTabs, installTimeoutMinutes, exitOnSwipeAway) {
        PerformanceSettings(
            hardwareAcceleration = hardwareAcceleration,
            confirmCloseRunning = confirmCloseRunning,
            autoCloseIdleTerminals = autoCloseIdleTerminals,
            idleTimeoutMinutes = idleTimeoutMinutes,
            maxTerminalSessions = maxTerminalSessions,
            nestedShellTabs = nestedShellTabs,
            installTimeoutMinutes = installTimeoutMinutes,
            exitOnSwipeAway = exitOnSwipeAway,
            onSetHardwareAcceleration = viewModel::setHardwareAcceleration,
            onSetConfirmCloseRunning = viewModel::setConfirmCloseRunning,
            onSetAutoCloseIdleTerminals = viewModel::setAutoCloseIdleTerminals,
            onSetIdleTimeoutMinutes = viewModel::setIdleTimeoutMinutes,
            onSetMaxTerminalSessions = viewModel::setMaxTerminalSessions,
            onSetNestedShellTabs = viewModel::setNestedShellTabs,
            onSetInstallTimeoutMinutes = viewModel::setInstallTimeoutMinutes,
            onSetExitOnSwipeAway = viewModel::setExitOnSwipeAway,
        )
    }
    val envVarsMap by viewModel.envVars.collectAsStateWithLifecycle()
    val envVarSettings = remember(envVarsMap) {
        EnvVarSettings(
            vars = envVarsMap,
            onSet = { name, value, oldName -> viewModel.setEnvVar(name, value, oldName) },
            onRemove = viewModel::removeEnvVar,
        )
    }
    val webPreviewBrowserGlobal by viewModel.webPreviewBrowser.collectAsStateWithLifecycle()
    val webPreviewBrowserProjects by viewModel.webPreviewBrowserProjects.collectAsStateWithLifecycle()
    val webPreviewBrowsers = remember(webPreviewBrowserGlobal, webPreviewBrowserProjects) {
        WebPreviewBrowsers(
            available = viewModel.installedBrowsers,
            globalChoice = webPreviewBrowserGlobal,
            projectChoice = { key -> webPreviewBrowserProjects[key] ?: WebPreviewBrowsers.INHERIT },
            currentProjectKey = selectedProject?.id?.toString().orEmpty(),
            onSetGlobal = viewModel::setWebPreviewBrowser,
            onSetProject = viewModel::setProjectWebPreviewBrowser,
        )
    }
    val hideStatusBarWithKeyboard by viewModel.hideStatusBarWithKeyboard.collectAsStateWithLifecycle()
    val hideTabCloseButton by viewModel.hideTabCloseButton.collectAsStateWithLifecycle()
    // Carried via CompositionLocal (not a JCodeShell param — that composable is at the ART verifier's
    // register limit) so both the tab UIs and the settings toggle can read value + setter.
    val tabCloseSetting = remember(hideTabCloseButton) {
        TabCloseButtonSetting(hideTabCloseButton, viewModel::setHideTabCloseButton)
    }
    val editorDragMovesCursor by viewModel.editorDragMovesCursor.collectAsStateWithLifecycle()
    val cursorDragVerticalLevel by viewModel.editorCursorDragVerticalLevel.collectAsStateWithLifecycle()
    val explorerHiddenMode by viewModel.explorerHiddenMode.collectAsStateWithLifecycle()
    val explorerExcludeEffect by viewModel.explorerExcludeEffect.collectAsStateWithLifecycle()
    val explorerHiddenPatterns by viewModel.explorerHiddenPatterns.collectAsStateWithLifecycle()
    val hiddenInjected by viewModel.hiddenInjected.collectAsStateWithLifecycle()
    val explorerHiddenSetting = remember(explorerHiddenMode, explorerExcludeEffect, explorerHiddenPatterns, hiddenInjected) {
        ExplorerHiddenSetting(
            mode = explorerHiddenMode,
            effect = explorerExcludeEffect,
            specifiedRaw = explorerHiddenPatterns,
            onSetMode = viewModel::setExplorerHiddenMode,
            onSetEffect = viewModel::setExplorerExcludeEffect,
            onSetSpecifiedRaw = viewModel::setExplorerHiddenPatterns,
            hiddenPatternsFor = { pid ->
                when (explorerHiddenMode) {
                    ExplorerHiddenMode.None -> emptyList()
                    ExplorerHiddenMode.HideInjected -> hiddenInjected[pid].orEmpty()
                    ExplorerHiddenMode.HideSpecifiedAndInjected ->
                        explorerHiddenPatterns.lines().map { it.trim() }.filter { it.isNotEmpty() } + hiddenInjected[pid].orEmpty()
                }
            },
        )
    }
    val respectDeviceCutout by viewModel.respectDeviceCutout.collectAsStateWithLifecycle()
    val cutoutContext = LocalContext.current
    val hasDeviceCutout = remember(cutoutContext) {
        (cutoutContext as? android.app.Activity)?.display?.cutout != null
    }
    // layoutInDisplayCutoutMode is mutable after window creation, so apply the choice live (no restart).
    LaunchedEffect(respectDeviceCutout) {
        (cutoutContext as? android.app.Activity)?.let { act ->
            act.window.attributes = act.window.attributes.apply {
                layoutInDisplayCutoutMode = if (respectDeviceCutout) {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                } else {
                    android.view.WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                }
            }
        }
    }
    val cutoutSetting = remember(respectDeviceCutout, hasDeviceCutout) {
        CutoutSetting(respect = respectDeviceCutout, hasCutout = hasDeviceCutout, onChange = viewModel::setRespectDeviceCutout)
    }
    val updateInfo by viewModel.updateInfo.collectAsStateWithLifecycle()
    val updateChecking by viewModel.updateChecking.collectAsStateWithLifecycle()
    val updateInstallState by viewModel.updateInstallState.collectAsStateWithLifecycle()
    val updateContext = LocalContext.current
    val openReleasePage: () -> Unit = {
        val url = updateInfo?.releaseUrl
            ?: "https://github.com/blamspotdev/j-code-android/releases/latest"
        runCatching {
            updateContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        Unit
    }
    val appUpdateSetting = remember(updateInfo, updateChecking, updateInstallState) {
        AppUpdateSetting(
            currentVersion = BuildConfig.VERSION_NAME,
            latestVersion = updateInfo?.latestVersion,
            updateAvailable = updateInfo?.updateAvailable == true,
            checking = updateChecking,
            onCheck = viewModel::checkForUpdate,
            onOpenRelease = openReleasePage,
            onInstallUpdate = {
                // In-app install when the release ships an APK; otherwise open the release page.
                if (updateInfo?.apkUrl != null) viewModel.installUpdate(updateContext) else openReleasePage()
            },
            installing = updateInstallState is AppUpdateInstaller.State.Downloading ||
                updateInstallState is AppUpdateInstaller.State.Installing,
            installProgress = (updateInstallState as? AppUpdateInstaller.State.Downloading)?.percent ?: 0,
        )
    }
    val paletteDisabledCommands by viewModel.paletteDisabledCommands.collectAsStateWithLifecycle()
    val commandPaletteSetting = remember(paletteDisabledCommands) {
        CommandPaletteSetting(disabledIds = paletteDisabledCommands, onSetEnabled = viewModel::setPaletteCommandEnabled)
    }
    val markdownWrapPortrait by viewModel.markdownWrapPortrait.collectAsStateWithLifecycle()
    val markdownPreviewSetting = remember(markdownWrapPortrait) {
        MarkdownPreviewSetting(wrapInPortrait = markdownWrapPortrait, onSetWrapInPortrait = viewModel::setMarkdownWrapPortrait)
    }
    val editorFontSizeGlobal by viewModel.editorFontSizeGlobal.collectAsStateWithLifecycle()
    val editorFontSizeSetting = remember(editorFontSizeGlobal) {
        EditorFontSizeSetting(value = editorFontSizeGlobal, onChange = viewModel::setEditorFontSizeGlobal)
    }
    val editorWordWrap by viewModel.editorWordWrap.collectAsStateWithLifecycle()
    val editorWordWrapSetting = remember(editorWordWrap) {
        EditorWordWrapSetting(enabled = editorWordWrap, onChange = viewModel::setEditorWordWrap)
    }
    val pendingReloadList by viewModel.pendingReload.collectAsStateWithLifecycle()
    val pendingReloadUi = remember(pendingReloadList) {
        PendingReloadUi(pendingReloadList.map { it.name }, viewModel::reloadPendingExtensions)
    }
    // Developer options: reveals the Extension Dev right-drawer tab + unsigned .jext sideloading.
    val developerOptions by viewModel.developerOptions.collectAsStateWithLifecycle()
    val jextPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.sideloadExtension(uri)
    }
    var showSideloadWarning by remember { mutableStateOf(false) }
    val developerSetting = remember(developerOptions) {
        DeveloperSetting(
            enabled = developerOptions,
            onSetEnabled = viewModel::setDeveloperOptions,
            onLoadExtension = { showSideloadWarning = true },
        )
    }
    val extensionDevState = remember(installedExtensions) {
        ExtensionDevState(
            extensions = installedExtensions.filter { it.dev },
            hostApiVersion = EXTENSION_API_VERSION,
            onReload = viewModel::refreshInstalledExtensions,
            onLoad = { showSideloadWarning = true },
        )
    }
    if (showSideloadWarning) {
        AlertDialog(
            onDismissRequest = { showSideloadWarning = false },
            title = { Text("Load unsigned extension?") },
            text = {
                Text(
                    "Sideloaded extensions aren't verified by the marketplace. Only load a .jext from a " +
                        "developer you trust — it can run commands in the Linux runtime.\n\n" +
                        "An unsigned package loads as a debuggable dev extension; a signed one installs " +
                        "normally but can't be debugged here.",
                )
            },
            confirmButton = {
                TextButton(onClick = { showSideloadWarning = false; jextPicker.launch("*/*") }) { Text("Choose .jext") }
            },
            dismissButton = { TextButton(onClick = { showSideloadWarning = false }) { Text("Cancel") } },
        )
    }
    val cursorDragHorizontalLevel by viewModel.editorCursorDragHorizontalLevel.collectAsStateWithLifecycle()
    val editorDragSetting = remember(editorDragMovesCursor, cursorDragVerticalLevel, cursorDragHorizontalLevel) {
        EditorDragSetting(
            enabled = editorDragMovesCursor,
            onChange = viewModel::setEditorDragMovesCursor,
            verticalLevel = cursorDragVerticalLevel,
            horizontalLevel = cursorDragHorizontalLevel,
            onVerticalLevelChange = viewModel::setEditorCursorDragVerticalLevel,
            onHorizontalLevelChange = viewModel::setEditorCursorDragHorizontalLevel,
        )
    }
    val restoreLastSession by viewModel.restoreLastSession.collectAsStateWithLifecycle()
    val restoreSessionSetting = remember(restoreLastSession) {
        RestoreSessionSetting(restoreLastSession, viewModel::setRestoreLastSession)
    }
    val extraKeysPortrait by viewModel.extraKeysPortrait.collectAsStateWithLifecycle()
    val extraKeysLandscape by viewModel.extraKeysLandscape.collectAsStateWithLifecycle()
    val extraKeysFunctionKeys by viewModel.extraKeysFunctionKeys.collectAsStateWithLifecycle()
    val extraKeysSetting = remember(extraKeysPortrait, extraKeysLandscape, extraKeysFunctionKeys) {
        ExtraKeysSetting(
            portrait = extraKeysPortrait,
            landscape = extraKeysLandscape,
            functionKeys = extraKeysFunctionKeys,
            onChangePortrait = viewModel::setExtraKeysPortrait,
            onChangeLandscape = viewModel::setExtraKeysLandscape,
            onChangeFunctionKeys = viewModel::setExtraKeysFunctionKeys,
        )
    }
    val extraKeysState = remember { ExtraKeysState() }
    val bottomStatusBar by viewModel.bottomStatusBar.collectAsStateWithLifecycle()
    val bottomBarSetting = remember(bottomStatusBar) {
        BottomBarSetting(bottomStatusBar, viewModel::setBottomStatusBar)
    }
    val volumeUpAction by viewModel.volumeUpAction.collectAsStateWithLifecycle()
    val volumeDownAction by viewModel.volumeDownAction.collectAsStateWithLifecycle()
    val volumeKeysSetting = remember(volumeUpAction, volumeDownAction) {
        VolumeKeysSetting(volumeUpAction, volumeDownAction, viewModel::setVolumeUpAction, viewModel::setVolumeDownAction)
    }
    val tabColoringMode by viewModel.tabColoringMode.collectAsStateWithLifecycle()
    val tabColoringSetting = remember(tabColoringMode) {
        TabColoringSetting(tabColoringMode, viewModel::setTabColoringMode)
    }
    val editorTabColorMap by viewModel.editorTabColors.collectAsStateWithLifecycle()
    val effectiveTabColoring by viewModel.effectiveTabColoring.collectAsStateWithLifecycle()
    val editorTabColors = remember(editorTabColorMap, effectiveTabColoring) {
        EditorTabColors({ path -> editorTabColorMap[path] }, effectiveTabColoring != TabColoring.Disabled)
    }
    val tabMaxSize by viewModel.tabMaxSize.collectAsStateWithLifecycle()
    val tabMaxSizeSetting = remember(tabMaxSize) { TabMaxSizeSetting(tabMaxSize, viewModel::setTabMaxSize) }
    val editorFontId by viewModel.editorFontId.collectAsStateWithLifecycle()
    val terminalFontId by viewModel.terminalFontId.collectAsStateWithLifecycle()
    val environmentFonts by viewModel.environmentFonts.collectAsStateWithLifecycle()
    val fontContext = LocalContext.current
    val envFontPaths = remember(environmentFonts) { environmentFonts.associate { it.id to it.path } }
    val editorTypeface = remember(editorFontId, envFontPaths) { MonoFontCatalog.resolve(fontContext, editorFontId, envFontPaths) }
    val terminalTypeface = remember(terminalFontId, envFontPaths) { MonoFontCatalog.resolve(fontContext, terminalFontId, envFontPaths, systemFallback = true) }
    // Bundled JetBrains Mono as a Compose family for the extra-keys arrows (←↑↓→) — it ships those
    // glyphs, so the row never falls back to a manufacturer font that might lack or misalign them.
    val extraKeysGlyphFont = remember { FontFamily(Font(dev.jcode.core.editor.R.font.jetbrains_mono_regular)) }
    val fontSettings = remember(editorFontId, terminalFontId, environmentFonts) {
        FontSettings(
            options = MonoFontCatalog.options + environmentFonts.map { FontOption(it.id, it.name) },
            editorFontId = editorFontId,
            terminalFontId = terminalFontId,
            editorDefaultId = MonoFontCatalog.EDITOR_DEFAULT,
            terminalDefaultId = MonoFontCatalog.TERMINAL_DEFAULT,
            onSelectEditorFont = viewModel::setEditorFont,
            onSelectTerminalFont = viewModel::setTerminalFont,
            onScanFonts = viewModel::refreshEnvironmentFonts,
        )
    }
    val editorSaveActions = remember(viewModel) {
        EditorSaveActions(
            onUndo = viewModel::undoActiveTab,
            onRedo = viewModel::redoActiveTab,
            onDiscard = viewModel::discardActiveTab,
            onSaveAll = viewModel::saveAllTabs,
            onFormat = viewModel::formatActiveTab,
            onSaveAllAwait = viewModel::saveAllDirtyAwait,
        )
    }
    // Completion items for the focused file, resolved from its installed language pack (if any).
    val activeFileName = editorGroup.activeTab?.filePath?.name
    val activeLanguagePack = remember(activeFileName, activeLanguageExtensions) {
        activeFileName?.let { n -> activeLanguageExtensions.firstNotNullOfOrNull { it.languageFor(n) } }
    }
    val completionSource = remember(activeLanguagePack) {
        { prefix: String -> languagePackCompletionItems(activeLanguagePack, prefix) }
    }
    // Debug launch target = the active source file, if it has an installed debug engine (stdio adapters
    // and js-debug's TCP adapter are both supported now).
    val activeDebugFile = editorGroup.activeTab?.takeIf { !it.isPage }?.filePath
    val activeDebugEngine = remember(activeDebugFile?.path) {
        activeDebugFile?.let { f ->
            val ext = "." + f.name.substringAfterLast('.', "")
            DebugEngineCatalog.BUILT_IN.firstOrNull { ext in it.extensions }
        }
    }
    val canDebug = activeDebugEngine != null &&
        activeDebugEngine.id in debugCatalogState.installedEntryIds
    // remember-ed so the holder is stable across recompositions (its callbacks are bound viewModel
    // refs, which allocate a fresh — thus unequal — instance every pass; an unremembered holder
    // provided as a CompositionLocal invalidates every reader on any JCodeApp recomposition).
    val debugSessionUi = remember(debugState, debugCallStack, debugVariables, debugOutput, activeDebugFile, canDebug) {
        DebugSessionUi(
            state = debugState,
            callStack = debugCallStack,
            variables = debugVariables,
            output = debugOutput,
            debugTargetName = activeDebugFile?.name,
            canDebug = canDebug,
            onDebug = { activeDebugFile?.let { viewModel.startDebug(it.path) } },
            onContinue = viewModel::debugContinue,
            onStepOver = viewModel::debugStepOver,
            onStepInto = viewModel::debugStepInto,
            onStepOut = viewModel::debugStepOut,
            onStop = viewModel::debugStop,
            onEvaluate = viewModel::debugEvaluate,
        )
    }
    StatusBarKeyboardController(enabled = hideStatusBarWithKeyboard)
    val tapContext = LocalContext.current
    // Routing for a URL surfaced by the terminal — a tapped link, or a guest CLI's xdg-open/$BROWSER
    // (OSC 7714). Both honor the same web-preview browser choice as Build & Run. rememberUpdatedState
    // keeps the long-lived OSC listener pointed at the latest choice/project without re-registering.
    val openTerminalUrl by rememberUpdatedState<(String) -> Unit> { url ->
        val trimmed = url.trim().trimEnd('.', ',', ')', ']', '}', ';')
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            val choice = webPreviewBrowsers.effective(selectedProject?.id?.toString().orEmpty())
            if (choice == WebPreviewBrowsers.BUILTIN) BuiltinBrowser.requestOpen(trimmed)
            else ProjectRunner.openInBrowser(tapContext, trimmed, choice)
        }
    }
    val terminalTapConfig = TerminalTapConfig(
        onToken = { token ->
            val trimmed = token.trim().trimEnd('.', ',', ')', ']', '}', ';')
            if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) openTerminalUrl(trimmed)
            else viewModel.openFileByGuestPath(token)
        },
        onPasteImage = { uri -> viewModel.savePastedImage(uri) },
    )
    DisposableEffect(viewModel) {
        TerminalSessionHost.setUiOpenFileListener { token -> viewModel.openFileByGuestPath(token) }
        TerminalSessionHost.setUiOpenUrlListener { url -> openTerminalUrl(url) }
        onDispose {
            TerminalSessionHost.setUiOpenFileListener(null)
            TerminalSessionHost.setUiOpenUrlListener(null)
        }
    }
    // Surface the built-in browser editor tab whenever something requests it (a "Built-in browser"
    // preview, or a terminal-URL tap, bumps BuiltinBrowser.revealSignal). Handled here because this is
    // where the view model is in scope; the inner run handlers only touch the BuiltinBrowser singleton.
    LaunchedEffect(Unit) {
        snapshotFlow { BuiltinBrowser.revealSignal.value }.collect { if (it > 0) viewModel.openBrowserTab() }
    }
    val snackbarHostState = remember { SnackbarHostState() }
    val openFolderLauncher = rememberOpenFolderLauncher(
        onFolderPicked = viewModel::openExternalFolder,
    )
    // Project/recent "Export to storage": a SAF tree picker chooses the destination folder, then the
    // view model copies the tree. The pending request is a saveable "<name>\n<source dir>" token (a
    // lambda would be dropped if the process dies behind the picker, silently ignoring the pick);
    // an empty dir segment marks a non-local source, which exportDirTo rejects with a message.
    var pendingTreeExport by rememberSaveable { mutableStateOf<String?>(null) }
    val exportTreeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val pending = pendingTreeExport
        pendingTreeExport = null
        if (uri != null && pending != null) {
            val dir = pending.substringAfter('\n')
            viewModel.exportDirTo(pending.substringBefore('\n'), dir.ifBlank { null }?.let(::File), uri)
        }
    }
    val openFolderTypePrompt by viewModel.openFolderTypePrompt.collectAsStateWithLifecycle()
    val environmentNotConfigured = environmentState.smokeTestPassed != true
    // The first-run screen latches once shown and stays up until the user taps "Done" (which defers).
    // Without this it would vanish the instant setup finishes — smokeTestPassed flips true at the end of
    // runAllPendingSteps, just before AllDone is emitted — skipping the "Done" confirmation. Saveable so
    // it survives a rotation mid-setup.
    var firstRunScreenLatched by rememberSaveable { mutableStateOf(false) }
    if (environmentNotConfigured && !environmentState.firstRunSetupDeferred) {
        firstRunScreenLatched = true
    }
    val showFirstRunEnvironmentScreen = firstRunScreenLatched && !environmentState.firstRunSetupDeferred

    LaunchedEffect(viewModel) {
        viewModel.messages.collectLatest { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Actionable prompt (restart the app) as a snackbar with a button. Extension-reload prompts render
    // as a compact banner atop the Extensions panel instead (see ExtensionsPanel).
    LaunchedEffect(viewModel) {
        viewModel.prompts.collect { prompt ->
            when (prompt) {
                is WorkbenchPrompt.RestartApp -> {
                    val result = snackbarHostState.showSnackbar(
                        message = prompt.message,
                        actionLabel = "Restart",
                        duration = SnackbarDuration.Long,
                    )
                    if (result == SnackbarResult.ActionPerformed) viewModel.restartApp()
                }
            }
        }
    }

    // Surface an "update available" toast (with an Update action) once per launch when the startup
    // GitHub-release check finds a newer version. The Settings > About card offers a manual re-check.
    var updateToastShown by remember { mutableStateOf(false) }
    LaunchedEffect(updateInfo?.updateAvailable) {
        val info = updateInfo
        if (info?.updateAvailable == true && !updateToastShown) {
            updateToastShown = true
            val result = snackbarHostState.showSnackbar(
                message = "Update available: v${info.latestVersion}",
                actionLabel = "Update",
                duration = SnackbarDuration.Long,
            )
            if (result == SnackbarResult.ActionPerformed) appUpdateSetting.onInstallUpdate()
        }
    }

    // In-app updater feedback: prompt for the "install unknown apps" permission, surface a failure,
    // and confirm success. Download progress shows on the Settings → About "Update" button.
    LaunchedEffect(updateInstallState) {
        when (val s = updateInstallState) {
            is AppUpdateInstaller.State.NeedsUnknownSourcePermission -> {
                viewModel.resetUpdateInstall()
                AppUpdateInstaller.openUnknownSourceSettings(updateContext)
                snackbarHostState.showSnackbar("Allow JCode to install apps, then tap Update again.")
            }
            is AppUpdateInstaller.State.Failed -> {
                viewModel.resetUpdateInstall()
                snackbarHostState.showSnackbar("Update failed: ${s.message}")
            }
            is AppUpdateInstaller.State.Success -> {
                viewModel.resetUpdateInstall()
                snackbarHostState.showSnackbar("Update installed — v${updateInfo?.latestVersion ?: ""}.")
            }
            else -> Unit
        }
    }

    // Recover a stuck in-app update: if the system installer was dismissed without completing, clear
    // the "Installing…" state when we return to the foreground so the Update button works again.
    val updateLifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(updateLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.onAppResumed()
        }
        updateLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { updateLifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep clean editor tabs mirrored to disk: re-sync on every foreground regain and on a slow tick
    // while foregrounded, so external writes (e.g. an agent in the terminal) show up in the editor.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, viewModel) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) {
                viewModel.requestSyncOpenFilesFromDisk()
                delay(1500)
            }
        }
    }

    val environmentManagerActions = remember(installedEnvironments) {
        EnvironmentManagerActions(
            environments = installedEnvironments,
            onSwitch = viewModel::setActiveEnvironment,
            onDelete = viewModel::deleteEnvironment,
            onStorageAccessGranted = viewModel::onStorageAccessGranted,
        )
    }

    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val contributedStartActions by viewModel.contributedEditorStartActions.collectAsStateWithLifecycle()
    val contributedDrawerActions by viewModel.contributedDrawerActions.collectAsStateWithLifecycle()
    val contributedContextActions by viewModel.contributedEditorContextActions.collectAsStateWithLifecycle()
    // Extra items for the editor's long-press context menu, computed for the ACTIVE tab: the built-in
    // Markdown "Preview" toggle plus extension-contributed actions matching the file's extension.
    val menuTab = editorGroup.activeTab
    val menuTabId = menuTab?.id
    val menuIsFileTab = menuTab?.editorState != null
    val menuFileName = menuTab?.let { t -> t.filePath.name.ifBlank { t.title } }.orEmpty()
    // "Go to line" / "Find text" from the editor menu. The menu is built in this parent scope but the
    // go-to-line dialog and search panel live inside JCodeShell, so the taps bump trigger state that
    // is passed down as params and reacted to there (find carries a nonce so the same word re-seeds).
    var editorGoToLineNonce by remember { mutableStateOf(0) }
    var editorFindRequest by remember { mutableStateOf<Pair<Int, String>?>(null) }
    val editorMenuExtras = remember(viewModel, menuTabId, menuIsFileTab, menuFileName, contributedContextActions) {
        val fileExt = menuFileName.substringAfterLast('.', "").lowercase()
        EditorMenuExtras(
            previewToggle = if (menuIsFileTab && menuTabId != null && SyntaxHighlighter.isMarkdownFile(menuFileName)) {
                { viewModel.toggleTabPreview(menuTabId) }
            } else {
                null
            },
            onGoToLine = if (menuIsFileTab) {
                { editorGoToLineNonce++ }
            } else {
                null
            },
            onFindText = if (menuIsFileTab) {
                { word -> editorFindRequest = ((editorFindRequest?.first ?: 0) + 1) to word }
            } else {
                null
            },
            contributions = if (menuIsFileTab) {
                contributedContextActions
                    .filter { it.fileExtensions.isEmpty() || fileExt in it.fileExtensions }
                    .map { EditorMenuContribution("${it.extId}:${it.id}", it.label, contributedMenuIcon(it.icon)) }
            } else {
                emptyList()
            },
            onContribution = { c, word ->
                contributedContextActions.firstOrNull { "${it.extId}:${it.id}" == c.key }
                    ?.let { viewModel.handleEditorContextAction(it, word) }
            },
        )
    }
    val vcsActions = remember(contributedDrawerActions) {
        VcsActions(drawerActions = contributedDrawerActions, onAction = viewModel::openContributedView)
    }
    // Explorer VCS decorations (pushed by the SCM extension) + extension-contributed row menu actions,
    // sliced for the selected project and handed to the explorer as a CompositionLocal.
    val contributedExplorerActions by viewModel.contributedExplorerContextActions.collectAsStateWithLifecycle()
    val contributedRunPresets by viewModel.contributedRunConfigPresets.collectAsStateWithLifecycle()
    val explorerScmDecorations by viewModel.explorerScmDecorations.collectAsStateWithLifecycle()
    val explorerScmUi = remember(contributedExplorerActions, explorerScmDecorations, selectedProject?.id) {
        val slice = selectedProject?.id?.toString()?.let { explorerScmDecorations[it] }
        ExplorerScmUi(
            status = slice?.status.orEmpty(),
            submodules = slice?.submodules.orEmpty(),
            contextActions = contributedExplorerActions.map {
                ExplorerContextAction("${it.extId}:${it.id}", it.label, contributedMenuIcon(it.icon), it.fileExtensions, it.targets)
            },
            onContextAction = { action, node ->
                val hostFile = (node.path as? FsPath.Local)?.file
                if (hostFile != null) {
                    contributedExplorerActions.firstOrNull { "${it.extId}:${it.id}" == action.key }
                        ?.let { viewModel.handleExplorerContextAction(it, hostFile, node.kind == FsKind.Directory) }
                }
            },
            onFsActivity = viewModel::notifyWorkspaceFilesChanged,
        )
    }
    // Persistent SCM WebView: boots the decorations-contributing SCM extension per open project so
    // git status reaches the explorer without the SCM panel ever being shown. Excludes hosts the user
    // stopped from the Task Manager (they'd otherwise re-attach on the next project open).
    val suspendedBackgroundExts by viewModel.suspendedBackgroundExtensions.collectAsStateWithLifecycle()
    val scmHostExt = remember(installedExtensions, extensionActivations, suspendedBackgroundExts) {
        installedExtensions.firstOrNull {
            it.type == ExtensionType.Scm && it.hasWebUi && it.contributes.explorerDecorations &&
                (extensionActivations[it.id] ?: ExtensionActivation.Default) != ExtensionActivation.Manual &&
                it.id !in suspendedBackgroundExts
        }
    }
    ScmBackgroundHost(
        ext = scmHostExt,
        projectKey = selectedProject?.id?.toString(),
        owner = viewModel,
        exec = viewModel::runtimeExecJson,
        apiRequest = viewModel::extensionApiRequest,
        events = viewModel.extensionEvents,
        liveHosts = viewModel.liveExtensionHosts,
        onHostGone = viewModel::clearExplorerScmDecorations,
    )
    val editorEmptyActions = remember(recents, contributedStartActions) {
        EditorEmptyActions(
            recents = recents,
            onOpenRecent = viewModel::openRecent,
            onExportRecent = { recent ->
                val dir = if (recent.kind == ProjectKind.Local) recent.uri else ""
                pendingTreeExport = File(dir).name.ifBlank { "project" } + "\n" + dir
                exportTreeLauncher.launch(null)
            },
            onNewProject = viewModel::requestNew,
            onOpenFolder = { openFolderLauncher.launch(null) },
            startActions = contributedStartActions,
            onAction = viewModel::openContributedView,
        )
    }

    val issueActions = remember(viewModel) {
        IssueActions(onOpen = { path, line -> viewModel.openFileByGuestPath("$path:${line + 1}") })
    }

    // Mirror the active file's bus diagnostics onto its editor as squiggly underlines. Positions are
    // resolved against the current snapshot; they drift with unsaved edits until the next check runs.
    val allDiagnostics by dev.jcode.core.lsp.LspModule.diagnosticsBus.allDiagnostics.collectAsStateWithLifecycle()
    val activeDiagTab = editorGroup.activeTab
    LaunchedEffect(activeDiagTab?.id, activeDiagTab?.previewMode, allDiagnostics) {
        val state = activeDiagTab?.editorState ?: return@LaunchedEffect
        if (activeDiagTab.previewMode) return@LaunchedEffect
        val diags = allDiagnostics[activeDiagTab.filePath.path].orEmpty()
        val snap = state.snapshot.value
        val decos = if (snap.lineCount == 0) emptyList() else diags.mapIndexed { i, d ->
            val line = d.startLine.coerceIn(0, snap.lineCount - 1)
            val (ls, le) = snap.lineAt(line)
            val endLine = d.endLine.coerceIn(line, snap.lineCount - 1)
            val (els, ele) = snap.lineAt(endLine)
            var start = (ls + d.startCol).coerceIn(ls, le)
            var end = (els + d.endCol).coerceIn(els, ele)
            if (end <= start) { start = ls; end = le } // zero-width diagnostic: underline the line
            dev.jcode.core.editor.decor.SquiggleDecoration(
                id = "diag:${activeDiagTab.id}:$i",
                startByte = start,
                endByte = end,
                severity = when (d.severity) {
                    dev.jcode.core.lsp.DiagnosticSeverity.ERROR -> dev.jcode.core.editor.decor.DiagnosticSeverity.ERROR
                    dev.jcode.core.lsp.DiagnosticSeverity.WARNING -> dev.jcode.core.editor.decor.DiagnosticSeverity.WARNING
                    dev.jcode.core.lsp.DiagnosticSeverity.INFORMATION -> dev.jcode.core.editor.decor.DiagnosticSeverity.INFO
                    dev.jcode.core.lsp.DiagnosticSeverity.HINT -> dev.jcode.core.editor.decor.DiagnosticSeverity.HINT
                },
                message = d.message,
                source = d.source,
            )
        }
        state.updateDecorations { it.replaceLayer(dev.jcode.core.editor.decor.Layer.SQUIGGLY, decos) }
    }

    // Settings backup/restore: SAF pickers save/load the app-preferences DataStore as a JSON file.
    val backupScope = rememberCoroutineScope()
    val settingsExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(SettingsBackup.MIME),
    ) { uri ->
        if (uri != null) backupScope.launch {
            val outcome = runCatching {
                val json = viewModel.exportSettingsJson()
                withContext(Dispatchers.IO) {
                    updateContext.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                        ?: error("Could not open the file for writing")
                }
            }
            snackbarHostState.showSnackbar(
                outcome.fold({ "Settings exported" }, { "Export failed: ${it.message}" }),
            )
        }
    }
    val settingsImportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri ->
        if (uri != null) backupScope.launch {
            val outcome = runCatching {
                val text = withContext(Dispatchers.IO) {
                    updateContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("Could not read the file")
                }
                viewModel.importSettingsJson(text)
            }
            snackbarHostState.showSnackbar(
                outcome.fold({ "Imported $it settings" }, { "Import failed: ${it.message}" }),
            )
        }
    }
    val settingsBackupActions = remember {
        SettingsBackupActions(
            onExport = { settingsExportLauncher.launch(SettingsBackup.SUGGESTED_NAME) },
            onImport = { settingsImportLauncher.launch("*/*") },
        )
    }

    // Environment backup/restore: pack/unpack the active Linux rootfs as a .tar.gz via a SAF picker.
    val envBackupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/gzip"),
    ) { uri -> if (uri != null) viewModel.backupEnvironmentTo(uri) }
    val envRestoreLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.restoreEnvironmentFrom(uri) }
    // Onboarding restore runs THROUGH the setup pipeline (proot/user/smoke-test) so a fresh install
    // restored from a backup ends up fully working — unlike the Settings path which just swaps the rootfs.
    val envRestoreOnboardingLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> if (uri != null) viewModel.restoreEnvironmentOnboarding(uri) }
    val systemPackagesUpdating by viewModel.systemPackagesUpdating.collectAsStateWithLifecycle()
    val environmentBackupActions = remember(systemPackagesUpdating) {
        EnvironmentBackupActions(
            onBackup = {
                val id = viewModel.distroService.selectedEnvironment().id
                envBackupLauncher.launch("jcode-env-$id.tar.gz")
            },
            onRestore = { envRestoreLauncher.launch("*/*") },
            onUpdatePackages = { viewModel.updateSystemPackages() },
            updatingPackages = systemPackagesUpdating,
        )
    }
    val envBackupStatus by viewModel.envBackupStatus.collectAsStateWithLifecycle()
    envBackupStatus?.let { status ->
        AlertDialog(
            onDismissRequest = {},
            confirmButton = {},
            title = { Text("Environment") },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(status)
                }
            },
        )
    }

    CompositionLocalProvider(
        LocalTerminalTapConfig provides terminalTapConfig,
        LocalTabCloseButtonSetting provides tabCloseSetting,
        LocalExtraKeysSetting provides extraKeysSetting,
        LocalExtraKeysState provides extraKeysState,
        LocalVolumeKeysSetting provides volumeKeysSetting,
        LocalBottomBarSetting provides bottomBarSetting,
        LocalFontSettings provides fontSettings,
        LocalEditorTypeface provides editorTypeface,
        LocalTerminalTypeface provides terminalTypeface,
        LocalExtraKeysGlyphFontFamily provides extraKeysGlyphFont,
        LocalEditorTabActions provides remember {
            EditorTabActions(
                onTogglePin = viewModel::toggleEditorTabPinned,
                onCloseOthers = viewModel::closeOtherEditorTabs,
                onCloseToRight = viewModel::closeEditorTabsToRight,
                onSetTabColor = viewModel::setTabColor,
            )
        },
        LocalTabColoringSetting provides tabColoringSetting,
        LocalEditorTabColors provides editorTabColors,
        LocalTabMaxSize provides tabMaxSizeSetting,
        LocalEditorDragMovesCursor provides editorDragSetting,
        LocalRestoreSession provides restoreSessionSetting,
        LocalExtensionActivation provides extensionActivationSetting,
        LocalExtensionCapabilities provides extensionCapabilitySetting,
        LocalExtensionKeepAlive provides extensionKeepAliveSetting,
        LocalAgentChatActions provides remember(extensionKeepAliveDisabled) {
            AgentChatActions(
                extensions = viewModel.installedExtensions,
                exec = viewModel::runtimeExecJson,
                apiRequest = viewModel::extensionApiRequest,
                events = viewModel.extensionEvents,
                onStopAllServices = viewModel::stopAllRuntimeServices,
                keepAliveFor = { id -> id !in extensionKeepAliveDisabled },
            )
        },
        LocalTaskManagerBackgroundActions provides remember {
            TaskManagerBackgroundActions(
                snapshot = viewModel::backgroundExtensionSnapshot,
                onStop = viewModel::stopBackgroundExtension,
                onStart = viewModel::startBackgroundExtension,
            )
        },
        LocalEditorSaveActions provides editorSaveActions,
        LocalEditorMenuExtras provides editorMenuExtras,
        LocalExplorerScmUi provides explorerScmUi,
        LocalCompletionSource provides completionSource,
        LocalEnvironmentManager provides environmentManagerActions,
        LocalEditorEmptyActions provides editorEmptyActions,
        LocalVcsActions provides vcsActions,
        LocalDebugCatalogState provides debugCatalogState,
        LocalExtensionInstallPhases provides extensionInstallPhases,
        LocalPendingReload provides pendingReloadUi,
        LocalRunConfigPresets provides contributedRunPresets,
        LocalSetupTerminalSessionId provides setupTerminalSessionId,
        LocalDebugSession provides debugSessionUi,
        LocalPerformanceSettings provides performanceSettings,
        LocalEnvVarSettings provides envVarSettings,
        LocalExplorerHiddenSetting provides explorerHiddenSetting,
        LocalCutoutSetting provides cutoutSetting,
        LocalAppUpdate provides appUpdateSetting,
        LocalSettingsBackup provides settingsBackupActions,
        LocalEnvironmentBackup provides environmentBackupActions,
        LocalCommandPaletteSetting provides commandPaletteSetting,
        LocalMarkdownPreviewSetting provides markdownPreviewSetting,
        LocalEditorFontSizeSetting provides editorFontSizeSetting,
        LocalEditorWordWrapSetting provides editorWordWrapSetting,
        LocalDeveloperSetting provides developerSetting,
        LocalExtensionDevState provides extensionDevState,
        LocalExtensionSettingsUi provides extensionSettingsUi,
        LocalWebPreviewBrowsers provides webPreviewBrowsers,
        LocalIssueActions provides issueActions,
        LocalDebugEditorState provides remember(breakpoints, debugLocation) {
            DebugEditorState(
                breakpoints = breakpoints,
                stoppedPath = debugLocation?.hostPath,
                stoppedLine = debugLocation?.line,
                onToggleBreakpoint = viewModel::toggleBreakpoint,
            )
        },
    ) {
    JCodeShell(
        modifier = modifier,
        editorGoToLineNonce = editorGoToLineNonce,
        editorFindRequest = editorFindRequest,
        onEditorFind = { editorFindRequest = ((editorFindRequest?.first ?: 0) + 1) to "" },
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
        onExportProject = { project ->
            val dir = (project.fsPath as? FsPath.Local)?.file?.absolutePath ?: ""
            pendingTreeExport = project.name + "\n" + dir
            exportTreeLauncher.launch(null)
        },
        onOpenFile = viewModel::openFile,
        onOpenPathAtLine = viewModel::openFileByGuestPath,
        onSelectEditorTab = viewModel::selectEditorTab,
        onCloseEditorTab = viewModel::closeEditorTab,
        onSaveActiveTab = viewModel::saveActiveTab,
        onUpdateEditorFontSize = viewModel::updateEditorFontSize,
        onUpdateEditorTabSize = viewModel::updateEditorTabSize,
        onUpdateEditorMinimap = viewModel::updateEditorMinimap,
        onUpdateEditorTabColoring = viewModel::updateEditorTabColoring,
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
        onOpenWorkspaceConfig = viewModel::openWorkspaceConfigFile,
        onOpenProjectConfig = viewModel::openProjectConfigFile,
        onRefreshEnvironment = viewModel::refreshEnvironment,
        onOpenEnvironmentWizard = { viewModel.openEnvironmentPage() },
        onSelectDistro = { viewModel.selectWizardDistro(it) },
        onAutoSetup = viewModel::runAutoSetup,
        onConfigureRun = viewModel::openRunConfigPage,
        onDebugConfig = viewModel::startDebugForConfig,
        onConfigureBuild = viewModel::openBuildConfigPage,
        onSaveRunConfig = viewModel::saveRunConfig,
        onSaveRunConfigs = viewModel::saveRunConfigs,
        onSaveBuildConfig = viewModel::saveBuildConfig,
        onDeleteRun = viewModel::deleteRunConfig,
        onDeleteBuild = viewModel::deleteBuildConfig,
        runConfigVersion = runConfigVersion,
        managerActions = remember(viewModel) { WorkbenchManagerActions(
            onCheckSdkStatuses = viewModel::checkSdkStatuses,
            onInstallSdkCatalogEntry = viewModel::installSdkCatalogEntry,
            onInstallSdkCatalogVersion = viewModel::installSdkCatalogVersion,
            onUninstallSdkCatalogVersion = viewModel::uninstallSdkCatalogVersion,
            onVerifySdkCatalogEntry = viewModel::verifySdkCatalogEntry,
            onUninstallSdkCatalogEntry = viewModel::uninstallSdkCatalogEntry,
            onOpenSdkDetail = viewModel::openSdkDetailPage,
            onCheckLspStatuses = viewModel::checkLspStatuses,
            onInstallLspCatalogEntry = viewModel::installLspCatalogEntry,
            onVerifyLspCatalogEntry = viewModel::verifyLspCatalogEntry,
            onUninstallLspCatalogEntry = viewModel::uninstallLspCatalogEntry,
            onOpenLspDetail = viewModel::openLspDetailPage,
            onCheckDebugStatuses = viewModel::checkDebugEngineStatuses,
            onUpdateAllToolchains = viewModel::updateAllToolchains,
            onInstallDebugEngine = viewModel::installDebugEngine,
            onVerifyDebugEngine = viewModel::verifyDebugEngine,
            onUninstallDebugEngine = viewModel::uninstallDebugEngine,
            onOpenDebugEngineDetail = viewModel::openDebugEngineDetailPage,
            onRefreshMarketplace = viewModel::refreshMarketplace,
            onInstallExtension = viewModel::installExtension,
            onUninstallExtension = viewModel::uninstallExtension,
            onOpenExtensionDetail = viewModel::openExtensionDetailPage,
            onOpenExtensionPermissions = viewModel::openExtensionPermissionsPage,
            onOpenExtensionApp = viewModel::openExtensionAppPage,
            // The Source Control extension renders its git-identity + GitHub-auth screen at its
            // `#github` route (a global-config screen that works with no project open).
            onOpenExtensionConfig = { id -> viewModel.openExtensionViewPage(id, "github", "Git Configuration") },
            onExtensionExec = viewModel::runtimeExecJson,
            onExtensionApiRequest = { extId, envelope ->
                val ext = viewModel.installedExtensions.value.firstOrNull { it.id == extId }
                if (ext == null) """{"ok":false,"error":"unknown extension: $extId"}"""
                else viewModel.extensionApiRequest(ext, envelope)
            },
            extensionEvents = viewModel.extensionEvents,
        ) },
        onOpenSettingsPage = viewModel::openSettingsPage,
        hideStatusBarWithKeyboard = hideStatusBarWithKeyboard,
        onUpdateHideStatusBarWithKeyboard = viewModel::setHideStatusBarWithKeyboard,
        bringEditorToFront = viewModel.bringEditorToFront,
        volumeKeyAction = viewModel.volumeKeyAction,
        runTerminalCompletions = viewModel.runTerminalCompletions,
    )
    }

    if (showNewItemDialog) {
        NewItemDialog(
            templates = templates,
            installedToolchains = sdkCatalogState.installedEntryIds,
            onDismiss = viewModel::dismissNewDialog,
            onConfirm = viewModel::createNewItem,
            resolveDynamicOptions = viewModel::runTemplateOptionsCommand,
        )
    }

    postClonePrompt?.let { project ->
        PostCloneDialog(
            projectName = project.name,
            onOpen = { viewModel.resolvePostClone(open = true) },
            onAdd = { viewModel.resolvePostClone(open = false) },
        )
    }

    openFolderTypePrompt?.let { pending ->
        OpenFolderTypeDialog(
            folderName = pending.label,
            onDismiss = viewModel::dismissOpenFolderPrompt,
            onConfirm = viewModel::resolveOpenFolderType,
        )
    }

    ImportProgressHost(viewModel.importProgress)

    // Closing tabs with unsaved changes: Save / Discard / Close Saved (dismiss = keep everything).
    pendingEditorClose?.let { pending ->
        UnsavedChangesDialog(
            titles = pending.dirtyTitles,
            thirdLabel = "Close Saved",
            onSave = { viewModel.resolveEditorClose(EditorCloseChoice.SAVE) },
            onDiscard = { viewModel.resolveEditorClose(EditorCloseChoice.DISCARD) },
            onThird = { viewModel.resolveEditorClose(EditorCloseChoice.CLOSE_SAVED) },
            onDismiss = { viewModel.resolveEditorClose(EditorCloseChoice.CANCEL) },
            onCancel = { viewModel.resolveEditorClose(EditorCloseChoice.CANCEL) },
        )
    }

    if (showFirstRunEnvironmentScreen) {
        OnboardingFeature.FirstRunEnvironmentScreen(
            environmentState = environmentState,
            autoSetupProgress = autoSetupProgress,
            onRefresh = { viewModel.refreshEnvironment() },
            onSelectDistro = { viewModel.selectWizardDistro(it) },
            onAutoSetup = { viewModel.runAutoSetup() },
            onStorageAccessGranted = { viewModel.onStorageAccessGranted() },
            onDismiss = { viewModel.deferFirstRunEnvironmentSetup() },
            onRestoreEnvironment = { envRestoreOnboardingLauncher.launch("*/*") },
        )
    }

}

@OptIn(ExperimentalLayoutApi::class) // WindowInsets.imeAnimationTarget (snap IME padding)
@Composable
private fun JCodeShell(
    editorGoToLineNonce: Int,
    editorFindRequest: Pair<Int, String>?,
    onEditorFind: () -> Unit,
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
    onExportProject: (Project) -> Unit,
    onOpenFile: (dev.jcode.fs.FsNode) -> Unit,
    onOpenPathAtLine: (String) -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onSaveActiveTab: () -> Unit,
    onUpdateEditorFontSize: (ConfigScope, Float?) -> Unit,
    onUpdateEditorTabSize: (ConfigScope, Int?) -> Unit,
    onUpdateEditorMinimap: (ConfigScope, Boolean?) -> Unit,
    onUpdateEditorTabColoring: (ConfigScope, String?) -> Unit,
    onUpdateEditorLigatures: (ConfigScope, Boolean?) -> Unit,
    onUpdateExplorerViewMode: (ConfigScope, String?) -> Unit,
    themeMode: ThemeMode,
    onUpdateThemeMode: (ThemeMode?) -> Unit,
    themeBundleId: String,
    onUpdateThemeBundle: (String) -> Unit,
    iconBundleId: String,
    onUpdateIconBundle: (String) -> Unit,
    installedExtensions: List<InstalledExtension>,
    marketplaceEntries: List<MarketplaceEntry>,
    marketplaceBusy: Boolean,
    managerActions: WorkbenchManagerActions,
    formatterId: String,
    onSelectFormatter: (String) -> Unit,
    onOpenWorkspaceConfig: () -> Unit,
    onOpenProjectConfig: () -> Unit,
    onRefreshEnvironment: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onAutoSetup: () -> Unit,
    onSelectDistro: (DistroProfile) -> Unit,
    onConfigureRun: (Project, Int?) -> Unit,
    onDebugConfig: (Project, RunConfig) -> Unit,
    onConfigureBuild: (Project, Int?) -> Unit,
    onSaveRunConfig: (Project, Int?, RunConfig) -> Unit,
    onSaveRunConfigs: (Project, List<RunConfig>) -> Unit,
    onSaveBuildConfig: (Project, Int?, RunBuildConfig) -> Unit,
    onDeleteRun: (Project, Int) -> Unit,
    onDeleteBuild: (Project, Int) -> Unit,
    runConfigVersion: Int,
    onOpenSettingsPage: () -> Unit,
    hideStatusBarWithKeyboard: Boolean,
    onUpdateHideStatusBarWithKeyboard: (Boolean) -> Unit,
    bringEditorToFront: SharedFlow<Unit>,
    volumeKeyAction: SharedFlow<VolumeKeyAction>,
    runTerminalCompletions: SharedFlow<Pair<String, Int>>,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    // Resolve the VCS actions here (under the CompositionLocal provider) and capture into closures below;
    // the editor-page dispatch is invoked in a subcomposition where the local would fall back to default.
    val vcs = LocalVcsActions.current
    val appContext = LocalContext.current.applicationContext
    val configuration = LocalConfiguration.current
    val focusRequester = remember { FocusRequester() }
    val compactDrawerState = rememberDrawerState(DrawerValue.Closed)
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    // A phone in landscape is WIDE (Expanded width) but SHORT (Compact height); a real tablet in
    // landscape is tall too. Key off height, not width, so phones keep the modal drawers they use in
    // portrait instead of switching to the cramped persistent side panels a tablet would want.
    val isMobileLandscapeMode = isLandscape && windowInfo.heightClass == JCodeWindowHeightClass.Compact
    val usesModalWorkspace = !isLandscape || isMobileLandscapeMode
    val isPortraitMobileMode = usesModalWorkspace && windowInfo.widthClass == JCodeWindowWidthClass.Compact
    val hasLandscapeInspectorSidebar = isLandscape
    val canShowRightSidebar = true
    val leftSidebarWidth = if (windowInfo.widthClass == JCodeWindowWidthClass.Expanded) 284.dp else 236.dp
    val rightSidebarWidth = (configuration.screenWidthDp * 0.75f).dp
    val activeTab = editorGroup.activeTab
    // The Extension Dev tab exists only when Developer options is on, so it's excluded here too —
    // otherwise a persisted ExtensionDev selection would survive turning developer mode off.
    val developerModeEnabled = LocalDeveloperSetting.current.enabled
    val portraitRightSidebarTabs = remember(developerModeEnabled) {
        RightPanelTab.entries
            .filter { it.enabled && (it != RightPanelTab.ExtensionDev || developerModeEnabled) }
            .toSet()
    }

    var selectedTool by rememberSaveable { mutableStateOf(WorkbenchTool.Explorer) }
    // A previously-persisted selection may point at a now-hidden destination; fall back to Explorer.
    LaunchedEffect(Unit) { if (!selectedTool.available) selectedTool = WorkbenchTool.Explorer }
    // The "DB Managers" tool is hidden until a DB-manager client extension is installed; if it's
    // selected when none is present (or the client is removed), fall back to the Explorer.
    val dbManagerAvailable = installedExtensions.hasDbManagerClient()
    LaunchedEffect(dbManagerAvailable) {
        if (!dbManagerAvailable && selectedTool == WorkbenchTool.DbManager) {
            selectedTool = WorkbenchTool.Explorer
        }
    }
    // The "SCM" tool is likewise hidden until a source-control client extension is installed.
    val scmAvailable = installedExtensions.hasScmClient()
    LaunchedEffect(scmAvailable) {
        if (!scmAvailable && selectedTool == WorkbenchTool.Scm) {
            selectedTool = WorkbenchTool.Explorer
        }
    }

    // Syntax highlighting for the active file: the installed language pack if one matches, else the
    // built-in Markdown pack (.md) or a generic fallback, so every file gets baseline coloring.
    // Re-tokenizes on every edit; spans are pushed as a GLYPH_COLOR decoration layer the renderer consumes.
    val systemDark = isSystemInDarkTheme()
    val editorDark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> systemDark
    }
    // Only extensions not set to Manual contribute language features (highlighting/completions/format).
    val activeLanguageExtensions = installedExtensions.filter {
        LocalExtensionActivation.current.modeFor(it.id) != ExtensionActivation.Manual
    }
    // The Mermaid Preview extension's bundled engine, when installed (and not Manual): the built-in
    // Markdown preview loads it to render ```mermaid fences inline instead of as plain code.
    val mermaidScriptFile = remember(activeLanguageExtensions) {
        activeLanguageExtensions.firstOrNull { it.id == "jcode.ext.mermaidprev" }
            ?.webUiFile?.parentFile
            ?.let { java.io.File(it, "mermaid.min.js") }
            ?.takeIf { it.isFile }
    }
    val highlightTab = editorGroup.activeTab
    // previewMode is a key (not just a guard) so flipping back to source restarts the effect and
    // re-runs one highlight pass over whatever the buffer holds by then.
    LaunchedEffect(highlightTab?.id, highlightTab?.previewMode, activeLanguageExtensions, editorDark) {
        val state = highlightTab?.editorState ?: return@LaunchedEffect
        if (highlightTab.previewMode) return@LaunchedEffect
        val fileName = highlightTab.filePath.name
        val lang = activeLanguageExtensions.firstNotNullOfOrNull { ext -> ext.languageFor(fileName) }
        val palette = if (editorDark) TokenPalette.DARK else TokenPalette.LIGHT
        state.snapshot.collectLatest { snap ->
            // Debounce: collectLatest cancels this wait on the next keystroke, so a typing burst
            // pays for one highlight pass of the final text instead of one per keystroke.
            kotlinx.coroutines.delay(30)
            val startedAt = android.os.SystemClock.uptimeMillis()
            val spans = withContext(Dispatchers.Default) {
                SyntaxHighlighter.highlightFor(snap, fileName, lang, palette)
            }
            android.util.Log.d(
                "SyntaxHighlight",
                "pass=${android.os.SystemClock.uptimeMillis() - startedAt}ms spans=${spans.size} bytes=${snap.byteLength}",
            )
            state.updateDecorations { it.replaceLayer(Layer.GLYPH_COLOR, spans) }
        }
    }
    // Settings opens as an in-editor page; every other tool drives the side panel.
    val onSelectWorkbenchTool: (WorkbenchTool) -> Unit = { tool ->
        if (tool == WorkbenchTool.Settings) onOpenSettingsPage() else selectedTool = tool
    }
    // Refresh installed/update-available status when the merged manager comes into view (async, guarded).
    LaunchedEffect(selectedTool) {
        if (selectedTool == WorkbenchTool.ToolchainManager) {
            managerActions.onCheckSdkStatuses()
            managerActions.onCheckLspStatuses()
            managerActions.onCheckDebugStatuses()
        }
    }
    var leftSidebarExpanded by rememberSaveable(isLandscape, windowInfo.widthClass) {
        mutableStateOf(isLandscape && windowInfo.widthClass != JCodeWindowWidthClass.Compact)
    }
    val isPersistentLeftSidebarVisible = !usesModalWorkspace && leftSidebarExpanded
    // NOT keyed on orientation/size: an open right drawer must survive a rotation. Keying it on
    // isLandscape/widthClass re-ran the initializer on every rotation and slammed it shut.
    var rightSidebarVisible by rememberSaveable { mutableStateOf(false) }
    // Opening a file or an editor-area page should surface the editor; in modal layouts the terminal
    // sits in a right drawer over the editor and the tools sit in the left drawer, so close both.
    LaunchedEffect(bringEditorToFront, usesModalWorkspace) {
        bringEditorToFront.collect {
            if (usesModalWorkspace) {
                rightSidebarVisible = false
                compactDrawerState.close()
            }
        }
    }
    var rightPanelTab by rememberSaveable {
        mutableStateOf(RightPanelTab.Terminal)
    }
    // Turning Developer options off must fully retire the Ext Dev tab — including the landscape
    // persistent sidebar, which renders rightPanelTab directly (no portrait clamp). Reset the
    // selection so its panel (and its auto-reload loop) stop composing everywhere.
    LaunchedEffect(developerModeEnabled) {
        if (!developerModeEnabled && rightPanelTab == RightPanelTab.ExtensionDev) {
            rightPanelTab = RightPanelTab.Terminal
        }
    }
    // When a debug session starts, surface its console: reveal the right drawer on its Debug tab
    // (the left Run/Debug panel keeps only the launch button + call stack + variables).
    val debugSessionActive = LocalDebugSession.current.state.let {
        it != DebugState.DISCONNECTED && it != DebugState.TERMINATED
    }
    LaunchedEffect(debugSessionActive) {
        if (debugSessionActive) {
            rightPanelTab = RightPanelTab.DebugConsole
            rightSidebarVisible = true
        }
    }
    // The Chat tab only exists while an agent-chat extension is installed; if it goes away (or was
    // never installed) while selected, fall back to the Terminal tab so the drawer isn't left on it.
    val chatExtensionAvailable = hasAgentChatExtension()
    LaunchedEffect(chatExtensionAvailable) {
        if (!chatExtensionAvailable && rightPanelTab == RightPanelTab.Chat) {
            rightPanelTab = RightPanelTab.Terminal
        }
    }
    // Read once here so the run handlers below (defined before the settings block) can resolve the
    // per-project "Open web previews in" choice when opening a dev-server URL.
    val webPreviewBrowsersLocal = LocalWebPreviewBrowsers.current
    // Reveal + select the DevTools drawer tab whenever the built-in browser is opened (a preview or a
    // direct open bumps BuiltinBrowser.revealSignal).
    LaunchedEffect(Unit) {
        snapshotFlow { BuiltinBrowser.revealSignal.value }.collect { sig ->
            if (sig > 0) {
                rightPanelTab = RightPanelTab.Devtools
                rightSidebarVisible = true
            }
        }
    }
    var commandPaletteVisible by rememberSaveable { mutableStateOf(false) }
    // Palette-driven view modes and one-shot tools. Window-level modes live in WindowModeState (a
    // process holder, so the status-bar keyboard controller can coordinate); pure-UI state stays here.
    var chromeHidden by rememberSaveable { mutableStateOf(false) }
    var goToLineVisible by remember { mutableStateOf(false) }
    var colorPickActive by remember { mutableStateOf(false) }
    var sampledColor by remember { mutableStateOf<Int?>(null) }
    // Seed for the Find-in-Files panel when "Find text" is chosen from the editor menu, threaded down
    // to the search panel. Driven by the parent's editor-menu trigger params.
    var editorSearchSeed by remember { mutableStateOf<Pair<Int, String>?>(null) }
    LaunchedEffect(editorGoToLineNonce) {
        if (editorGoToLineNonce > 0) goToLineVisible = true
    }
    LaunchedEffect(editorFindRequest) {
        val req = editorFindRequest ?: return@LaunchedEffect
        editorSearchSeed = req
        selectedTool = WorkbenchTool.Search
        if (usesModalWorkspace) scope.launch { compactDrawerState.open() } else leftSidebarExpanded = true
    }
    val fullscreenMode by WindowModeState.fullscreen.collectAsStateWithLifecycle()
    val keepAwakeMode by WindowModeState.keepAwake.collectAsStateWithLifecycle()
    val orientationLockedMode by WindowModeState.orientationLocked.collectAsStateWithLifecycle()
    WindowModeController()
    // Volume-key bindings (Settings → Input): route focused-pane arrows/scroll to the same target the
    // extra-keys row drives, and open the command palette. Undo/Redo run directly in the Activity.
    val volumeExtraKeys = LocalExtraKeysState.current
    LaunchedEffect(volumeKeyAction) {
        volumeKeyAction.collect { action ->
            when (action) {
                VolumeKeyAction.KeyLeft -> volumeExtraKeys.target?.onExtraKey(ExtraKey.Left, false, false)
                VolumeKeyAction.KeyRight -> volumeExtraKeys.target?.onExtraKey(ExtraKey.Right, false, false)
                VolumeKeyAction.KeyUp -> volumeExtraKeys.target?.onExtraKey(ExtraKey.Up, false, false)
                VolumeKeyAction.KeyDown -> volumeExtraKeys.target?.onExtraKey(ExtraKey.Down, false, false)
                VolumeKeyAction.ScrollUp -> volumeExtraKeys.target?.onScroll(3)
                VolumeKeyAction.ScrollDown -> volumeExtraKeys.target?.onScroll(-3)
                VolumeKeyAction.CommandPalette -> commandPaletteVisible = true
                else -> Unit
            }
        }
    }
    var terminalSessionIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var selectedTerminalSessionId by rememberSaveable { mutableStateOf("") }
    // Live tab names keyed by session id, driven by the shell's OSC 7712 (the running program name).
    val terminalTitles = remember { mutableStateMapOf<String, String>() }
    // Terminals the user has actually looked at; anything else is a "new background" instance (badge dot).
    var seenTerminalIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(rightSidebarVisible, rightPanelTab, terminalSessionIds) {
        val live = terminalSessionIds.toSet()
        // Viewing the terminal panel marks everything seen; otherwise just prune closed sessions so the
        // set can't grow stale (this also runs on every open/close/run-teardown that changes the ids).
        seenTerminalIds = if (rightSidebarVisible && rightPanelTab == RightPanelTab.Terminal) {
            live
        } else {
            seenTerminalIds intersect live
        }
    }
    // Derived once per actual change (not per recomposition) so a busy-title update doesn't rebuild the
    // whole top bar, and the instance list isn't a fresh List object every frame.
    val terminalBusy by remember {
        derivedStateOf { terminalSessionIds.any { (terminalTitles[it] ?: "terminal") != "terminal" } }
    }
    val terminalHasUnseen by remember {
        derivedStateOf { terminalSessionIds.any { it !in seenTerminalIds } }
    }
    val terminalInstances by remember {
        derivedStateOf {
            terminalSessionIds.mapIndexed { index, id ->
                TerminalInstance(id, "${index + 1}. ${terminalTabLabel(terminalTitles[id])}")
            }
        }
    }

    // Process-lifetime manager so terminal sessions (native PTY processes) survive Activity
    // recreation/backgrounding instead of being orphaned with the composition.
    val terminalSessionManager = remember(appContext) { TerminalSessionHost.manager(appContext) }
    // Apply the user's configurable instance limit to the process-lifetime manager.
    val configuredMaxTerminals = LocalPerformanceSettings.current.maxTerminalSessions
    LaunchedEffect(terminalSessionManager, configuredMaxTerminals) {
        terminalSessionManager.maxSessions = configuredMaxTerminals
    }
    // Relocate interactive sub-shells into their own tab (OSC 7715). Pushed to the manager so
    // createSession installs (or removes) the guest shell wrappers to match on the next session.
    val nestedShellTabsEnabled = LocalPerformanceSettings.current.nestedShellTabs
    LaunchedEffect(terminalSessionManager, nestedShellTabsEnabled) {
        terminalSessionManager.nestedShellTabs = nestedShellTabsEnabled
    }
    val rawShellCommand = effectiveConfig.terminal.shellLinux?.takeIf { it.isNotBlank() } ?: "/bin/bash --login"
    // A bare (non-absolute) shell name PATH-resolves to our nested-shell wrapper and would wrongly
    // relocate the tab's own top shell; mark it so the wrapper inlines just this invocation (the shell's
    // own sub-shells still relocate). The default "/bin/bash --login" is absolute and never affected.
    val terminalShellCommand = if (nestedShellTabsEnabled && !rawShellCommand.trimStart().startsWith("/")) {
        "JCODE_NSH_TOP=1 $rawShellCommand"
    } else {
        rawShellCommand
    }
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
        val projectsRoot = dev.jcode.core.distro.WorkspaceHostPaths.projectsRoot.trimEnd('/')
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
            binds = listOf(DistroBind(host = dev.jcode.core.distro.WorkspaceHostPaths.projectsRoot, target = "/workspace")),
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

    // Relocate an interactive sub-shell (OSC 7715 from the guest wrapper) into its own temporary tab,
    // focused and linked to its parent so exiting/closing it returns focus to the parent. A no-op if the
    // feature is off, the parent tab is gone, or the payload is malformed — the guest wrapper's watchdog
    // then falls back to running the sub-shell inline in the parent tab.
    fun spawnRelocatedChild(parentId: String, payload: String) {
        // Read the manager's live flag, not the composition-captured `nestedShellTabsEnabled` val: this
        // runs from a listener registered once (DisposableEffect), so a captured val would be stale.
        if (!terminalSessionManager.nestedShellTabs) return
        if (parentId !in terminalSessionIds) return
        val parts = payload.split(";", limit = 3)
        if (parts.size < 3 || parts[0] != "open") return
        val child = terminalSessionManager.createNestedShellSession(parentId, parts[1], parts[2]) ?: return
        terminalSessionIds = terminalSessionIds + child.id
        selectedTerminalSessionId = child.id
        terminalSessionManager.switchSession(child.id)
        TerminalSessionHost.onSessionStarted(appContext, child.id)
    }

    fun selectTerminalSession(sessionId: String) {
        selectedTerminalSessionId = sessionId
        terminalSessionManager.switchSession(sessionId)
    }

    // Surface the background "Setup" terminal (toolchain installs / project scaffolds) as a tab
    // WITHOUT focusing it or opening the drawer — the unseen-badge is the only attention cue.
    val setupTerminalSessionId = LocalSetupTerminalSessionId.current
    LaunchedEffect(setupTerminalSessionId) {
        val id = setupTerminalSessionId ?: return@LaunchedEffect
        if (terminalSessionManager.getSession(id) != null && id !in terminalSessionIds) {
            terminalSessionIds = terminalSessionIds + id
            if (selectedTerminalSessionId.isEmpty()) selectedTerminalSessionId = id
        }
    }

    // Run state: the URL of the most recent run (for "Open in browser") and whether we're still
    // waiting for the dev frontend to come up. [runSessionIds] are the run's terminals (e.g. Server +
    // Client), torn down and respawned on each run and stopped together by handleStopRun.
    var runUrl by rememberSaveable { mutableStateOf<String?>(null) }
    var runInProgress by remember { mutableStateOf(false) }
    var runningProjectId by remember { mutableStateOf<Long?>(null) }
    var runningRunName by remember { mutableStateOf<String?>(null) }
    // The terminals of the current/most-recent run. Kept until the NEXT run (or a stop/exit) so a new
    // run tears these down first — strictly one run-config's terminals exist per project at a time,
    // even after the command has finished (its terminal stays open showing output until replaced).
    var runSessionIds by remember { mutableStateOf<List<String>>(emptyList()) }
    // Run terminals whose command reported completion (OSC 7713); the run is "done" once all have.
    var runDoneSessionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var runPollJob by remember { mutableStateOf<Job?>(null) }

    // Clear the ACTIVE-run status (so the UI shows Run again) without touching [runSessionIds] — the
    // terminals stay for teardown-on-next-run. A run is no longer active once its command is done/killed.
    fun clearRunActiveStatus() {
        runPollJob?.cancel()
        runPollJob = null
        runInProgress = false
        runUrl = null
        runningProjectId = null
        runningRunName = null
    }

    // A run terminal going away — the server crashed/exited (EOF), the user closed the tab, or killed
    // it from the Task Manager — drops it from the run and clears the active status when none remain.
    val onRunSessionGone: (String) -> Unit = { sessionId ->
        if (sessionId in runSessionIds) {
            runSessionIds = runSessionIds.filterNot { it == sessionId }
            runDoneSessionIds = runDoneSessionIds - sessionId
            if (runSessionIds.isEmpty()) clearRunActiveStatus()
        }
    }

    // Drop a terminal tab when its shell exits on its own (e.g. `exit`, or a finished one-shot command).
    // The manager has already reaped the PTY + released the foreground hold; here we just sync the UI.
    DisposableEffect(Unit) {
        TerminalSessionHost.setUiTitleListener { id, title ->
            terminalTitles[id] = title.ifBlank { "terminal" }
        }
        onDispose { TerminalSessionHost.setUiTitleListener(null) }
    }

    DisposableEffect(Unit) {
        TerminalSessionHost.setUiExitListener { exitedId ->
            // A relocated sub-shell that exits returns focus to its parent tab (Windows-CMD style),
            // not just the last tab. relocationParentOf survives the manager's reap so it's readable here.
            val parentId = terminalSessionManager.relocationParentOf(exitedId)
            terminalSessionIds = terminalSessionIds.filterNot { it == exitedId }
            terminalTitles.remove(exitedId)
            if (selectedTerminalSessionId == exitedId) {
                val next = parentId?.takeIf { it in terminalSessionIds }
                    ?: terminalSessionIds.lastOrNull().orEmpty()
                selectedTerminalSessionId = next
                next.takeIf { it.isNotEmpty() }?.let { terminalSessionManager.switchSession(it) }
            }
            terminalSessionManager.clearRelocation(exitedId)
            onRunSessionGone(exitedId)
        }
        onDispose { TerminalSessionHost.setUiExitListener(null) }
    }

    // Relocate an interactive sub-shell into its own temporary tab when a guest wrapper asks (OSC 7715).
    DisposableEffect(Unit) {
        TerminalSessionHost.setUiNestedShellListener { parentId, payload ->
            spawnRelocatedChild(parentId, payload)
        }
        onDispose { TerminalSessionHost.setUiNestedShellListener(null) }
    }

    // A run command reporting completion (OSC 7713, emitted after the command) marks that terminal
    // done. The run is done once every one of its commands has completed — then the active status
    // clears (UI shows Run again), but the terminals stay open with their output until the next run
    // tears them down. Non-run sessions (setup/manual) aren't in runSessionIds, so they're ignored.
    LaunchedEffect(runTerminalCompletions) {
        runTerminalCompletions.collect { (sessionId, _) ->
            if (sessionId in runSessionIds) {
                runDoneSessionIds = runDoneSessionIds + sessionId
                if (runSessionIds.all { it in runDoneSessionIds }) clearRunActiveStatus()
            }
        }
    }

    // Build & Run the selected project: spawn a dedicated terminal in the right drawer, stream the
    // compile/run output into it, then open the device browser once the server is reachable.
    fun handleRun(project: Project, config: RunConfig) {
        if (!terminalReady) {
            scope.launch { snackbarHostState.showSnackbar("Finish environment setup before running.") }
            return
        }
        val plan = ProjectRunner.runConfigToPlan(config)
        if (plan.terminals.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("'${config.name}' has no terminals. Tap Configure to set it up.") }
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
            previous.forEach { terminalTitles.remove(it) }
        }
        OutputLog.beginRun(plan.kindLabel)
        val startedIds = mutableListOf<String>()
        for (terminal in plan.terminals) {
            val session = spawnTerminalSession(label = terminal.label) ?: break
            // After the command exits, emit an OSC-7713 marker with its exit code so the run unbinds
            // (done/killed) while the terminal stays open showing the output. Appended on the same line
            // as the command so it's one prompt entry, not a stray extra line.
            terminalSessionManager.sendInput(
                session.id,
                ProjectRunner.runInvocation(project, terminal).trimEnd('\n') + "; printf '\\033]7713;run;%s\\007' \"\$?\"\n",
            )
            startedIds += session.id
            OutputLog.captureSession(session.id)
        }
        if (startedIds.isEmpty()) return
        runSessionIds = startedIds
        runDoneSessionIds = emptySet()
        runningProjectId = project.id
        runningRunName = config.name
        selectTerminalSession(startedIds.last()) // focus the frontend terminal
        runPollJob?.cancel()
        if (plan.readyPort > 0) {
            runUrl = plan.url
            runInProgress = true
            // Cancel any in-flight poll from a previous run so the browser only opens once.
            runPollJob = scope.launch {
                val up = ProjectRunner.awaitServer(plan.readyPort)
                runInProgress = false
                if (up) {
                    OutputLog.append("✓ Dev server ready — opening ${plan.url}")
                    val choice = webPreviewBrowsersLocal.effective(project.id.toString())
                    if (choice == WebPreviewBrowsers.BUILTIN) BuiltinBrowser.requestOpen(plan.url)
                    else ProjectRunner.openInBrowser(appContext, plan.url, choice)
                } else {
                    OutputLog.append("✗ Dev server didn't start in time; check the run terminals.", OutputKind.Error)
                    snackbarHostState.showSnackbar("Dev server didn't start in time; check the run terminals.")
                }
            }
        } else {
            // No server port to wait on (e.g. a build/script-only config): just run the terminals.
            runUrl = null
            runInProgress = false
        }
    }

    /** Run the project's first effective run config (top-bar / empty-editor Run buttons). */
    fun handleRunFirst(project: Project) {
        val config = ProjectRunner.effectiveRuns(project).firstOrNull()
        if (config == null) {
            // No run config yet — open the Run panel so the user can add one (framework-detected or
            // blank), instead of dead-ending on a toast.
            selectedTool = WorkbenchTool.RunDebug
            if (usesModalWorkspace) scope.launch { compactDrawerState.open() } else leftSidebarExpanded = true
            return
        }
        handleRun(project, config)
    }

    /** Run a build task in its own terminal (fire-and-forget — not tracked as a run). */
    fun handleBuild(project: Project, config: RunBuildConfig) {
        if (!terminalReady) {
            scope.launch { snackbarHostState.showSnackbar("Finish environment setup before building.") }
            return
        }
        if (config.command.isBlank()) {
            scope.launch { snackbarHostState.showSnackbar("'${config.name}' has no command. Tap Configure to set it up.") }
            return
        }
        rightPanelTab = RightPanelTab.Terminal
        rightSidebarVisible = true
        val session = spawnTerminalSession(label = config.name) ?: return
        val invocation = ProjectRunner.runInvocation(project, ProjectRunner.RunTerminal(config.name, config.command))
        terminalSessionManager.sendInput(session.id, invocation + "\n")
        OutputLog.beginRun(config.name)
        OutputLog.captureSession(session.id)
        selectTerminalSession(session.id)
    }

    // Stop the current run: Ctrl-C the run terminal (graceful server/build shutdown) and reset run
    // state. The terminal session is kept so its output stays visible and a re-run can reuse it.
    fun handleStopRun() {
        OutputLog.append("■ Stopped run")
        runSessionIds.forEach { id ->
            if (id in terminalSessionIds && terminalSessionManager.getSession(id) != null) {
                terminalSessionManager.sendInput(id, byteArrayOf(0x03)) // Ctrl-C / SIGINT
            }
        }
        // Killed: clear the active status. The terminals stay in runSessionIds so the next run tears
        // them down — only one run-config's terminals exist per project.
        clearRunActiveStatus()
    }

    fun closeTerminalSession(sessionId: String) {
        val parentId = terminalSessionManager.relocationParentOf(sessionId)
        // Closing a parent strands its blocked relocated child(ren); close them first (this recurses and
        // mutates terminalSessionIds), then compute the remaining list from the current state.
        terminalSessionManager.childrenOf(sessionId).forEach { closeTerminalSession(it) }
        val remaining = terminalSessionIds.filterNot { it == sessionId }
        terminalSessionManager.closeSession(sessionId)
        TerminalSessionHost.onSessionStopped(sessionId)
        terminalTitles.remove(sessionId)
        terminalSessionManager.clearRelocation(sessionId)
        terminalSessionIds = remaining
        // Manual close doesn't fire the EOF exit listener, so clear run state here too (Build & Run row).
        onRunSessionGone(sessionId)
        if (selectedTerminalSessionId == sessionId) {
            // A relocated child returns focus to its parent; otherwise fall back to the last tab.
            val next = parentId?.takeIf { it in remaining } ?: remaining.lastOrNull().orEmpty()
            selectedTerminalSessionId = next
            next.takeIf { it.isNotEmpty() }?.let { terminalSessionManager.switchSession(it) }
        }
    }

    fun closeAllTerminalSessions() {
        // Kill every live PTY (the tracked ids may drift from the manager's sessions), then clear state.
        val ids = (terminalSessionIds + terminalSessionManager.sessions.keys).distinct()
        ids.forEach { id ->
            terminalSessionManager.closeSession(id)
            TerminalSessionHost.onSessionStopped(id)
            terminalSessionManager.clearRelocation(id)
            onRunSessionGone(id)
        }
        terminalSessionIds = emptyList()
        terminalTitles.clear()
        selectedTerminalSessionId = ""
    }

    // Set when the user explicitly closes the project: suppresses the convenience auto-start so the
    // terminal stays empty (a clean slate). Reset once a project/workspace is opened again.
    var terminalAutoStartSuppressed by rememberSaveable { mutableStateOf(false) }

    // Closing a project/workspace tears down its working context. Everything still running is stopped:
    // the run (Ctrl-C), the debug adapter, and every terminal PTY (proot --kill-on-exit reaps the tree).
    // If something is running and the user opted to be warned, a confirm dialog gates the teardown.
    val perf = LocalPerformanceSettings.current
    val debugSessionUiLocal = LocalDebugSession.current
    val agentChatActionsLocal = LocalAgentChatActions.current
    val editorSaveActionsLocal = LocalEditorSaveActions.current
    var pendingCloseTarget by remember { mutableStateOf<CloseTarget?>(null) }
    var pendingUnsavedSwitch by remember { mutableStateOf<CloseTarget?>(null) }

    fun runningItems(): List<String> = buildList {
        terminalSessionManager.foregroundSessions().forEach { (_, prog) -> add("Terminal: $prog") }
        if (runInProgress || runningProjectId != null) add("Build & Run")
        val dbgState = debugSessionUiLocal.state
        if (dbgState != DebugState.DISCONNECTED && dbgState != DebugState.TERMINATED) add("Debug session")
    }

    fun teardownRunning() {
        if (runInProgress || runningProjectId != null) handleStopRun()
        debugSessionUiLocal.onStop()
        agentChatActionsLocal.onStopAllServices()
        AgentChatWebViewHolder.destroyAll()
        ScmWebViewHolder.destroyAll()
        closeAllTerminalSessions()
    }

    // System back: navigate back one step (close the active page tab, then any open drawer). With
    // nothing left to go back to, the second back within 2s exits — but if foreground work is running
    // (terminal program, Build & Run, debug session) it asks first: terminate it all, keep it running
    // in the background, or cancel. Extracted into its own composable so its locals stay out of
    // JCodeShell's (already huge) generated method. Sits below runningItems/teardownRunning because
    // it captures them.
    WorkbenchBackHandler(
        activeTabIsPage = editorGroup.activeTab?.isPage == true,
        activeTabId = editorGroup.activeTab?.id,
        onCloseActiveTab = onCloseEditorTab,
        drawerOpen = compactDrawerState.isOpen,
        onCloseDrawer = { scope.launch { compactDrawerState.close() } },
        rightSidebarVisible = rightSidebarVisible,
        onCloseRightSidebar = { rightSidebarVisible = false },
        runningItems = { runningItems() },
        onTerminateAll = { teardownRunning() },
        snackbarHostState = snackbarHostState,
    )

    fun performClose(target: CloseTarget) {
        teardownRunning()
        terminalAutoStartSuppressed = true
        when (target) {
            CloseTarget.Project -> onCloseProject()
            CloseTarget.Workspace -> onCloseWorkspace()
        }
    }

    fun proceedClose(target: CloseTarget) {
        if (perf.confirmCloseRunning && runningItems().isNotEmpty()) pendingCloseTarget = target
        else performClose(target)
    }

    fun requestClose(target: CloseTarget) {
        // Unsaved editor buffers are dropped on a workspace/project switch — prompt before anything is
        // torn down (so Cancel is a true no-op), then fall through to the running-process guard.
        if (editorGroup.tabs.any { !it.isPage && it.isDirty }) pendingUnsavedSwitch = target
        else proceedClose(target)
    }

    val handleCloseProject: () -> Unit = { requestClose(CloseTarget.Project) }
    val handleCloseWorkspace: () -> Unit = { requestClose(CloseTarget.Workspace) }

    pendingCloseTarget?.let { target ->
        val items = runningItems()
        AlertDialog(
            onDismissRequest = { pendingCloseTarget = null },
            title = { Text(if (target == CloseTarget.Workspace) "Close workspace?" else "Close project?") },
            text = {
                Text(
                    "Still running — closing will stop:\n" +
                        items.joinToString("\n") { "•  $it" },
                )
            },
            confirmButton = {
                TextButton(onClick = { pendingCloseTarget = null; performClose(target) }) {
                    Text("Close anyway")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingCloseTarget = null }) { Text("Cancel") }
            },
        )
    }

    // Unsaved editor buffers about to be dropped by a workspace/project switch: Save / Discard / Cancel.
    // Keeping the dirty tabs open isn't an option here (the whole context is going away), so the third
    // button is Cancel. Save awaits the writes, then falls through to the running-process guard.
    pendingUnsavedSwitch?.let { target ->
        val titles = editorGroup.tabs.filter { !it.isPage && it.isDirty }.map { it.title }
        UnsavedChangesDialog(
            titles = titles,
            thirdLabel = "Cancel",
            onSave = {
                pendingUnsavedSwitch = null
                scope.launch {
                    // Only proceed once everything is on disk; if some tab couldn't be saved (e.g. a
                    // SAF/blank-path file), re-raise the prompt so the user can Discard or Cancel
                    // instead of the switch silently dropping the still-unsaved buffers.
                    if (editorSaveActionsLocal.onSaveAllAwait()) proceedClose(target)
                    else pendingUnsavedSwitch = target
                }
            },
            onDiscard = { pendingUnsavedSwitch = null; proceedClose(target) },
            onThird = { pendingUnsavedSwitch = null },
            onDismiss = { pendingUnsavedSwitch = null },
        )
    }

    // Auto-close idle terminals (no foreground program + no I/O past the threshold) to free proot
    // trees + memory, when enabled in Performance settings. onSessionExit drops each reaped tab.
    LaunchedEffect(perf.autoCloseIdleTerminals, perf.idleTimeoutMinutes) {
        if (!perf.autoCloseIdleTerminals) return@LaunchedEffect
        val idleMs = perf.idleTimeoutMinutes.toLong() * 60_000L
        while (isActive) {
            delay(60_000L)
            runCatching { terminalSessionManager.reapIdle(idleMs) }
        }
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

    // Context for the configurable palette commands: what the focused screen is showing decides
    // which entries register at all (predicates would go stale — closures capture these values, so
    // they are also keys below and re-register on every relevant change).
    val paletteSetting = LocalCommandPaletteSetting.current
    val paletteSaveActions = LocalEditorSaveActions.current
    val paletteTab = editorGroup.activeTab
    val paletteHasTabs = editorGroup.tabs.isNotEmpty()
    val paletteEditorActive = paletteTab?.editorState != null && !paletteTab.isPage && !paletteTab.previewMode
    val paletteLanguageIdentified = run {
        val name = paletteTab?.filePath?.name
        name != null && activeLanguageExtensions.any { it.languageFor(name) != null }
    }
    val paletteFontSize = effectiveConfig.editor.fontSize
    // Mirror Settings' most-specific-scope rule so a font-size nudge isn't masked by an existing
    // project override.
    val paletteFontScope = if (selectedProject?.fsPath is FsPath.Local) ConfigScope.Project else ConfigScope.Workspace

    DisposableEffect(
        onCreateProject, openFolderLauncher, onOpenWorkspaceConfig, onOpenProjectConfig, onAutoSetup,
        selectedProject?.id, paletteSetting.disabledIds, paletteTab?.id, paletteHasTabs,
        paletteEditorActive, paletteLanguageIdentified, chromeHidden, fullscreenMode, keepAwakeMode,
        orientationLockedMode, paletteFontSize,
    ) {
        CommandRegistry.clear()
        // A configurable command registers only while enabled in Settings → Command Palette AND its
        // context applies (view/focus-dependent entries stay out of the list entirely otherwise).
        fun registerConfigurable(id: String, title: String, group: String, icon: JCodeIcon, visible: Boolean = true, action: () -> Unit) {
            if (id in paletteSetting.disabledIds || !visible) return
            CommandRegistry.register(id = id, title = title, group = group, action = action, icon = icon)
        }
        registerConfigurable(
            id = "view.orientationLock",
            title = if (orientationLockedMode) "Unlock Screen Orientation" else "Lock Screen Orientation",
            group = "View",
            icon = JCodeIcon.ScreenRotation,
        ) { WindowModeState.orientationLocked.value = !orientationLockedMode }
        registerConfigurable(
            id = "view.fullscreen",
            title = if (fullscreenMode) "Exit Fullscreen" else "Enter Fullscreen",
            group = "View",
            icon = JCodeIcon.Fullscreen,
        ) { WindowModeState.fullscreen.value = !fullscreenMode }
        registerConfigurable(
            id = "view.keepAwake",
            title = if (keepAwakeMode) "Keep Awake: Turn Off" else "Keep Awake: Turn On",
            group = "View",
            icon = JCodeIcon.KeepAwake,
        ) { WindowModeState.keepAwake.value = !keepAwakeMode }
        registerConfigurable(
            id = "view.hideChrome",
            title = if (chromeHidden) "Show Header and Tabs" else "Hide Header and Tabs",
            group = "View",
            icon = JCodeIcon.Collapse,
            visible = paletteHasTabs,
        ) { chromeHidden = !chromeHidden }
        registerConfigurable(
            id = "editor.goToLine",
            title = "Go to Line…",
            group = "Editor",
            icon = JCodeIcon.Cursor,
            visible = paletteEditorActive,
        ) { goToLineVisible = true }
        registerConfigurable(
            id = "editor.formatDocument",
            title = "Format Document",
            group = "Editor",
            icon = JCodeIcon.Format,
            visible = paletteEditorActive && paletteLanguageIdentified,
        ) { paletteSaveActions.onFormat() }
        registerConfigurable(
            id = "editor.fontSizeIncrease",
            title = "Increase Editor Font Size",
            group = "Editor",
            icon = JCodeIcon.TextIncrease,
            visible = paletteEditorActive,
        ) { onUpdateEditorFontSize(paletteFontScope, (paletteFontSize + 1f).coerceIn(8f, 72f)) }
        registerConfigurable(
            id = "editor.fontSizeDecrease",
            title = "Decrease Editor Font Size",
            group = "Editor",
            icon = JCodeIcon.TextDecrease,
            visible = paletteEditorActive,
        ) { onUpdateEditorFontSize(paletteFontScope, (paletteFontSize - 1f).coerceIn(8f, 72f)) }
        registerConfigurable(
            id = "tools.colorSearch",
            title = "Color Search (pick from screen)",
            group = "Tools",
            icon = JCodeIcon.Palette,
        ) { colorPickActive = true }
        CommandRegistry.register(
            id = "workspace.newFolder",
            title = "New Folder",
            group = "Workspace",
            action = onCreateProject,
            icon = JCodeIcon.NewFolder,
        )
        CommandRegistry.register(
            id = "workspace.openFolder",
            title = "Open Folder",
            group = "Workspace",
            action = { openFolderLauncher.launch(null) },
            icon = JCodeIcon.Files,
        )
        CommandRegistry.register(
            id = "workspace.autoSetupEnvironment",
            title = "Auto-Setup Environment",
            group = "Workspace",
            action = onAutoSetup,
            icon = JCodeIcon.Sdk,
        )
        CommandRegistry.register(
            id = "workbench.focusExplorer",
            title = "Focus Explorer",
            group = "Workbench",
            action = { selectedTool = WorkbenchTool.Explorer },
            icon = JCodeIcon.Files,
        )
        CommandRegistry.register(
            id = "workbench.showSearch",
            title = "Show Search Placeholder",
            group = "Workbench",
            action = { selectedTool = WorkbenchTool.Search },
            icon = JCodeIcon.Search,
        )
        CommandRegistry.register(
            id = "settings.openPage",
            title = "Open Settings",
            group = "Settings",
            action = onOpenSettingsPage,
            icon = JCodeIcon.Settings,
        )
        CommandRegistry.register(
            id = "settings.openWorkspaceYaml",
            title = "Open Workspace YAML",
            group = "Settings",
            action = onOpenWorkspaceConfig,
            icon = JCodeIcon.Code,
        )
        CommandRegistry.register(
            id = "settings.openProjectYaml",
            title = "Open Project YAML",
            group = "Settings",
            action = onOpenProjectConfig,
            whenPredicate = { selectedProject?.fsPath is FsPath.Local },
            icon = JCodeIcon.Code,
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

    val runActions = WorkbenchRunActions(
        onRun = { project, config -> handleRun(project, config) },
        onDebug = onDebugConfig,
        onBuild = { project, config -> handleBuild(project, config) },
        onStop = ::handleStopRun,
        onOpenInBrowser = {
            runUrl?.let { url ->
                val choice = webPreviewBrowsersLocal.effective(runningProjectId?.toString().orEmpty())
                if (choice == WebPreviewBrowsers.BUILTIN) BuiltinBrowser.requestOpen(url)
                else ProjectRunner.openInBrowser(appContext, url, choice)
            }
        },
        onConfigureRun = onConfigureRun,
        onConfigureBuild = onConfigureBuild,
        onSaveRuns = onSaveRunConfigs,
        onSaveBuild = onSaveBuildConfig,
        onDeleteRun = onDeleteRun,
        onDeleteBuild = onDeleteBuild,
    )

    val drawerContent: @Composable () -> Unit = {
        ModalDrawerSheet(
            modifier = Modifier.width(332.dp),
            drawerContainerColor = MaterialTheme.colorScheme.surface,
        ) {
            WorkspacePanel(
                selectedTool = selectedTool,
                workspace = workspace,
                selectedProject = selectedProject,
                searchSeed = editorSearchSeed,
                onSearchSeedConsumed = { editorSearchSeed = null },
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
                onCloseWorkspace = handleCloseWorkspace,
                onCloseProject = handleCloseProject,
                onCreateProject = onCreateProject,
                onRemoveProject = onRemoveProject,
                onSelectProject = onSelectProject,
                onOpenProject = onOpenProject,
                onRenameProject = onRenameProject,
                onExportProject = onExportProject,
                onSelectTool = onSelectWorkbenchTool,
                onOpenExternalFolder = { openFolderLauncher.launch(null) },
                contributedDrawerActions = vcs.drawerActions,
                onDrawerAction = vcs.onAction,
                onOpenFile = onOpenFile,
                onOpenPathAtLine = onOpenPathAtLine,
                onOpenProjectConfig = onOpenProjectConfig,
                onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                onAutoSetup = onAutoSetup,
                managerActions = managerActions,
                runActions = runActions,
                runningProjectId = runningProjectId,
                runningRunName = runningRunName,
                runConfigVersion = runConfigVersion,
                runUrl = runUrl,
                runInProgress = runInProgress,
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
                // Modal drawer: only live-watch the filesystem while the drawer is actually open.
                explorerAutoRefresh = compactDrawerState.isOpen,
            )
        }
    }

    val content: @Composable () -> Unit = {
        val editorActions = LocalEditorSaveActions.current
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
                    // Ctrl+Shift+S saves all open editor tabs. This root preview handler runs before the
                    // focused editor/terminal AndroidView, so it wins in every focus state; the identical
                    // per-view handlers are a fallback should that Compose interop ordering ever change.
                    if ((event.isCtrlPressed || event.isMetaPressed) && event.isShiftPressed && event.key == Key.S) {
                        editorActions.onSaveAll()
                        return@onPreviewKeyEvent true
                    }
                    if (event.key == Key.Escape && commandPaletteVisible) {
                        commandPaletteVisible = false
                        return@onPreviewKeyEvent true
                    }
                    false
                },
            bottomBar = {
                Column {
                    // Sits above the status bar so Scaffold's innerPadding reserves space for it and
                    // the editor/terminal content shrinks instead of being covered while typing.
                    WorkbenchExtraKeysBar()
                    BottomStatusBarSlot {
                        WorkbenchStatusBar(
                            activeTab = activeTab,
                            selectedProject = selectedProject,
                            effectiveConfig = effectiveConfig,
                            activeDistroId = environmentState.runtime.selectedDistro.id,
                        )
                    }
                }
            },
            containerColor = MaterialTheme.colorScheme.background,
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                ) {
                    CompositionLocalProvider(
                        LocalChromeControls provides remember(chromeHidden) {
                            ChromeControls(
                                chromeHidden = chromeHidden,
                                onSetChromeHidden = { chromeHidden = it },
                            )
                        },
                    ) {
                    // Run configs behind the top-bar Run button: a tap runs the first (or opens a picker
                    // when there's more than one); long-press lists them all.
                    val topBarRunConfigs = remember(selectedProject?.id, runConfigVersion) {
                        selectedProject?.let { ProjectRunner.effectiveRuns(it) }.orEmpty()
                    }
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
                        onRun = { selectedProject?.let(::handleRunFirst) },
                        onStop = ::handleStopRun,
                        onRerun = { selectedProject?.let(::handleRunFirst) },
                        runConfigNames = topBarRunConfigs.map { it.name },
                        onRunConfig = { index ->
                            selectedProject?.let { project ->
                                topBarRunConfigs.getOrNull(index)?.let { config -> handleRun(project, config) }
                            }
                        },
                        isRunning = runningProjectId != null,
                        terminalBusy = terminalBusy,
                        terminalHasUnseen = terminalHasUnseen,
                        terminalSessions = terminalInstances,
                        onOpenTerminalSession = { id ->
                            rightPanelTab = RightPanelTab.Terminal
                            rightSidebarVisible = true
                            selectTerminalSession(id)
                        },
                        onShowTerminal = {
                            rightPanelTab = RightPanelTab.Terminal
                            rightSidebarVisible = true
                        },
                        onSelectEditorTab = onSelectEditorTab,
                        onCloseEditorTab = onCloseEditorTab,
                        onSave = onSaveActiveTab,
                        onFind = onEditorFind,
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
                            name != null && activeLanguageExtensions.any { it.languageFor(name) != null }
                        },
                        onEditorLanguageAction = { action, word ->
                            if (action == EditorLanguageAction.FormatDocument) {
                                editorActions.onFormat()
                            } else {
                                // Semantic actions need a language server (deferred); surface intent.
                                scope.launch {
                                    val target = if (word.isNotBlank()) " \"$word\"" else ""
                                    snackbarHostState.showSnackbar("${action.label}$target — needs a language server (coming soon)")
                                }
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
                                    onUpdateMinimap = onUpdateEditorMinimap,
                                    onUpdateTabColoring = onUpdateEditorTabColoring,
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
                                    hideStatusBarWithKeyboard = hideStatusBarWithKeyboard,
                                    onUpdateHideStatusBarWithKeyboard = onUpdateHideStatusBarWithKeyboard,
                                    isUserWorkspace = breadcrumb.size > 1,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                EditorPageKind.Environment -> OnboardingFeature.EnvironmentSetupPage(
                                    environmentState = environmentState,
                                    autoSetupProgress = autoSetupProgress,
                                    onRefresh = onRefreshEnvironment,
                                    onSelectDistro = onSelectDistro,
                                    onAutoSetup = onAutoSetup,
                                )
                                EditorPageKind.SdkDetail -> {
                                    val id = tab.id.substringAfter(MainViewModel.SDK_DETAIL_PREFIX)
                                    sdkCatalogState.entries.firstOrNull { it.id == id }?.let { entry ->
                                        SdkManagerFeature.DetailPage(
                                            entry = entry,
                                            state = sdkCatalogState,
                                            environmentState = environmentState,
                                            onInstall = managerActions.onInstallSdkCatalogEntry,
                                            onUpdate = managerActions.onInstallSdkCatalogEntry,
                                            onUninstall = managerActions.onUninstallSdkCatalogEntry,
                                            onVerify = managerActions.onVerifySdkCatalogEntry,
                                            onInstallVersion = managerActions.onInstallSdkCatalogVersion,
                                            onUninstallVersion = managerActions.onUninstallSdkCatalogVersion,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                EditorPageKind.LspDetail -> {
                                    val id = tab.id.substringAfter(MainViewModel.LSP_DETAIL_PREFIX)
                                    lspCatalogState.entries.firstOrNull { it.id == id }?.let { entry ->
                                        LspManagerFeature.DetailPage(
                                            entry = entry,
                                            state = lspCatalogState,
                                            environmentState = environmentState,
                                            onInstall = managerActions.onInstallLspCatalogEntry,
                                            onUpdate = managerActions.onInstallLspCatalogEntry,
                                            onUninstall = managerActions.onUninstallLspCatalogEntry,
                                            onVerify = managerActions.onVerifyLspCatalogEntry,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                EditorPageKind.DebugEngineDetail -> {
                                    val id = tab.id.substringAfter(MainViewModel.DEBUG_ENGINE_DETAIL_PREFIX)
                                    val debugState = LocalDebugCatalogState.current
                                    debugState.entries.firstOrNull { it.id == id }?.let { entry ->
                                        DebugEngineManagerFeature.DetailPage(
                                            entry = entry,
                                            state = debugState,
                                            environmentState = environmentState,
                                            onInstall = managerActions.onInstallDebugEngine,
                                            onUpdate = managerActions.onInstallDebugEngine,
                                            onUninstall = managerActions.onUninstallDebugEngine,
                                            onVerify = managerActions.onVerifyDebugEngine,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                EditorPageKind.RunConfig -> {
                                    val rest = tab.id.substringAfter(MainViewModel.RUN_CONFIG_PREFIX)
                                    val id = rest.substringBefore('#').toLongOrNull()
                                    val index = rest.substringAfter('#', "").toIntOrNull()
                                    val project = (workspace?.projects.orEmpty() + listOfNotNull(selectedProject))
                                        .firstOrNull { it.id == id }
                                    if (project != null) {
                                        // key: the page slot is positional, so without it switching between two
                                        // Run Config tabs would reuse the first's form state and Save could
                                        // overwrite the other config.
                                        key(tab.id) {
                                            val initial = remember(runConfigVersion) {
                                                ProjectRunner.editableRunConfig(project, index)
                                            }
                                            RunConfigPage(
                                                initial = initial,
                                                onSave = { onSaveRunConfig(project, index, it) },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                                EditorPageKind.BuildConfig -> {
                                    val rest = tab.id.substringAfter(MainViewModel.BUILD_CONFIG_PREFIX)
                                    val id = rest.substringBefore('#').toLongOrNull()
                                    val index = rest.substringAfter('#', "").toIntOrNull()
                                    val project = (workspace?.projects.orEmpty() + listOfNotNull(selectedProject))
                                        .firstOrNull { it.id == id }
                                    if (project != null) {
                                        key(tab.id) {
                                            val initial = remember(runConfigVersion) {
                                                ProjectRunner.editableBuildConfig(project, index)
                                            }
                                            BuildConfigPage(
                                                initial = initial,
                                                onSave = { onSaveBuildConfig(project, index, it) },
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                                EditorPageKind.ExtensionDetail -> {
                                    val id = tab.id.substringAfter(MainViewModel.EXT_DETAIL_PREFIX)
                                    val entry = marketplaceEntries.firstOrNull { it.id == id }
                                    val inst = installedExtensions.firstOrNull { it.id == id }
                                    if (entry != null || inst != null) {
                                        ExtensionDetailPage(
                                            entry = entry,
                                            installed = inst,
                                            available = marketplaceEntries,
                                            installedIds = installedExtensions.map { it.id }.toSet(),
                                            busy = marketplaceBusy,
                                            installPhase = LocalExtensionInstallPhases.current[entry?.id ?: inst?.id],
                                            onInstall = managerActions.onInstallExtension,
                                            onUninstall = managerActions.onUninstallExtension,
                                            onOpenApp = managerActions.onOpenExtensionApp,
                                            onOpenExtensionDetail = managerActions.onOpenExtensionDetail,
                                            onOpenSdkDetail = managerActions.onOpenSdkDetail,
                                            onOpenLspDetail = managerActions.onOpenLspDetail,
                                            onOpenDebugEngineDetail = managerActions.onOpenDebugEngineDetail,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                                EditorPageKind.ExtensionPermissions -> ExtensionPermissionsPage(
                                    installed = installedExtensions,
                                    onOpenConfig = managerActions.onOpenExtensionConfig,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                EditorPageKind.Browser -> BrowserPage(modifier = Modifier.fillMaxSize())
                                EditorPageKind.ImageViewer -> key(tab.id) {
                                    // Key by tab id so switching between image tabs (same call site)
                                    // recreates the WebView instead of reusing the previous image.
                                    ImageViewerPage(
                                        source = tab.id,
                                        name = tab.title,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                                EditorPageKind.ExtensionApp -> {
                                    // Tab id is EXT_APP_PREFIX + extId, optionally + "#view" for an alternate screen.
                                    val rest = tab.id.substringAfter(MainViewModel.EXT_APP_PREFIX)
                                    val appId = rest.substringBefore("#")
                                    val view = rest.substringAfter("#", "")
                                    installedExtensions.firstOrNull { it.id == appId }?.let { ext ->
                                        // Key by id+view so each extension app/view gets its own WebView.
                                        key(ext.id, view) {
                                            ExtensionWebViewPage(
                                                extension = ext,
                                                onExec = managerActions.onExtensionExec,
                                                onApiRequest = { envelope ->
                                                    managerActions.onExtensionApiRequest(ext.id, envelope)
                                                },
                                                events = managerActions.extensionEvents,
                                                route = view,
                                                modifier = Modifier.fillMaxSize(),
                                            )
                                        }
                                    }
                                }
                                EditorPageKind.None -> if (tab.previewMode && tab.editorState != null) {
                                    // Key by tab id so switching between two previewed files (same call
                                    // site) recreates the WebView instead of reusing the previous content.
                                    key(tab.id) {
                                        MarkdownPreviewPage(
                                            tab = tab,
                                            dark = editorDark,
                                            languagePacks = activeLanguageExtensions,
                                            mermaidScript = mermaidScriptFile,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                } else {
                                    Unit
                                }
                            }
                        },
                    )
                    }

                    if (isPersistentLeftSidebarVisible) {
                        WorkspacePanel(
                            selectedTool = selectedTool,
                            workspace = workspace,
                            selectedProject = selectedProject,
                            searchSeed = editorSearchSeed,
                            onSearchSeedConsumed = { editorSearchSeed = null },
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
                            onCloseWorkspace = handleCloseWorkspace,
                            onCloseProject = handleCloseProject,
                            onCollapseSidebar = { leftSidebarExpanded = false },
                            onCreateProject = onCreateProject,
                            onRemoveProject = onRemoveProject,
                            onSelectProject = onSelectProject,
                            onOpenProject = onOpenProject,
                            onRenameProject = onRenameProject,
                            onExportProject = onExportProject,
                            onSelectTool = onSelectWorkbenchTool,
                            onOpenExternalFolder = { openFolderLauncher.launch(null) },
                            contributedDrawerActions = vcs.drawerActions,
                            onDrawerAction = vcs.onAction,
                            onOpenFile = onOpenFile,
                            onOpenPathAtLine = onOpenPathAtLine,
                            onOpenProjectConfig = onOpenProjectConfig,
                            onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                            onAutoSetup = onAutoSetup,
                            managerActions = managerActions,
                            runActions = runActions,
                            runningProjectId = runningProjectId,
                            runningRunName = runningRunName,
                            runConfigVersion = runConfigVersion,
                            runUrl = runUrl,
                            runInProgress = runInProgress,
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
                            // Persistent sidebar is only composed while visible (guarded above).
                            explorerAutoRefresh = true,
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
                        terminalTitleFor = { id -> terminalTitles[id] },
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
                        runningProjectName = runningProjectId?.let { id ->
                            workspace?.projects?.firstOrNull { it.id == id }?.name ?: selectedProject?.name
                        },
                        runInProgress = runInProgress,
                        onStopRun = ::handleStopRun,
                    )
                }
            }
        }
    }

    // imeAnimationTarget, not imePadding: following the IME animation re-lays-out the ENTIRE shell on
    // every frame of the keyboard slide (~27ms of measure/layout per frame on low-end hardware — the
    // dominant source of keyboard-toggle frame drops). Snapping the padding to the animation's target
    // does ONE relayout per toggle; the keyboard then slides over an already-settled layout.
    Box(modifier = modifier.fillMaxSize().windowInsetsPadding(WindowInsets.imeAnimationTarget)) {
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
                    Column(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .fillMaxHeight()
                            .fillMaxWidth()
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    ) {
                        WorkbenchRightSidebar(
                            selectedTab = rightPanelTab.takeIf { it in portraitRightSidebarTabs } ?: RightPanelTab.Terminal,
                            selectedProject = selectedProject,
                            terminalSessionIds = terminalSessionIds,
                            selectedTerminalSessionId = selectedTerminalSessionId,
                            activeTerminalPty = terminalSessionManager.getSession(selectedTerminalSessionId)?.pty,
                            terminalSessionFor = { id -> terminalSessionManager.getSession(id) },
                            terminalTitleFor = { id -> terminalTitles[id] },
                            terminalReady = terminalReady,
                            onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            onTabSelected = { rightPanelTab = it },
                            onSelectTerminalSession = ::selectTerminalSession,
                            onAddTerminalSession = ::createTerminalSession,
                            onRemoveTerminalSession = ::closeTerminalSession,
                            onHide = { rightSidebarVisible = false },
                            runningProjectName = runningProjectId?.let { id ->
                                workspace?.projects?.firstOrNull { it.id == id }?.name ?: selectedProject?.name
                            },
                            runInProgress = runInProgress,
                            onStopRun = ::handleStopRun,
                        )
                        // The modal sidebar covers the Scaffold's bottom bar, so it hosts its own
                        // copy; as a Column child it pushes the terminal up rather than covering it.
                        WorkbenchExtraKeysBar()
                    }
                }
            }
        }

        CommandPalette(
            visible = commandPaletteVisible,
            compact = usesModalWorkspace,
            onDismiss = { commandPaletteVisible = false },
        )

        if (goToLineVisible) {
            val goToState = editorGroup.activeTab?.editorState
            if (goToState == null) {
                LaunchedEffect(Unit) { goToLineVisible = false }
            } else {
                GoToLineDialog(
                    lineCount = goToState.snapshot.value.lineCount,
                    onDismiss = { goToLineVisible = false },
                    onGo = { line, column ->
                        goToState.requestReveal((line - 1).coerceAtLeast(0), (column - 1).coerceAtLeast(0))
                    },
                )
            }
        }

        if (colorPickActive) {
            ColorPickOverlay(
                onPicked = { colorPickActive = false; sampledColor = it },
                onCancel = { colorPickActive = false },
            )
        }
        sampledColor?.let { color ->
            ColorSampleDialog(
                argb = color,
                onPickAgain = { sampledColor = null; colorPickActive = true },
                onDismiss = { sampledColor = null },
            )
        }

        // Snackbar host lives at the root — above the modal drawer sheet and the right sidebar overlay
        // (the Scaffold's own slot sits under them), so messages are never covered by an open drawer.
        WorkbenchSnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(bottom = 8.dp),
        )
    }
}

@Composable
private fun WorkspacePanel(
    selectedTool: WorkbenchTool,
    workspace: Workspace?,
    selectedProject: Project?,
    searchSeed: Pair<Int, String>? = null,
    onSearchSeedConsumed: () -> Unit = {},
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
    onExportProject: (Project) -> Unit,
    onSelectTool: (WorkbenchTool) -> Unit,
    onOpenExternalFolder: () -> Unit,
    contributedDrawerActions: List<MainViewModel.ShellContribution>,
    onDrawerAction: (MainViewModel.ShellContribution) -> Unit,
    onOpenFile: (dev.jcode.fs.FsNode) -> Unit,
    onOpenPathAtLine: (String) -> Unit,
    onOpenProjectConfig: () -> Unit,
    onOpenEnvironmentWizard: () -> Unit,
    onAutoSetup: () -> Unit,
    managerActions: WorkbenchManagerActions,
    runActions: WorkbenchRunActions,
    runningProjectId: Long?,
    runningRunName: String?,
    runConfigVersion: Int,
    runUrl: String?,
    runInProgress: Boolean,
    onSnackbar: (String) -> Unit,
    themeBundleId: String,
    onUpdateThemeBundle: (String) -> Unit,
    iconBundleId: String,
    onUpdateIconBundle: (String) -> Unit,
    installedExtensions: List<InstalledExtension>,
    marketplaceEntries: List<MarketplaceEntry>,
    marketplaceBusy: Boolean,
    onCollapseSidebar: (() -> Unit)? = null,
    /** Whether this panel is actually on-screen (drawer open / sidebar visible), so the Explorer only
     *  live-watches the filesystem while the user can see it. */
    explorerAutoRefresh: Boolean = true,
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
                dbManagerAvailable = installedExtensions.hasDbManagerClient(),
                scmAvailable = installedExtensions.hasScmClient(),
                vmManagerAvailable = installedExtensions.hasVmManagerClient(),
                onSelectTool = onSelectTool,
                onCreateProject = onCreateProject,
                onOpenExternalFolder = onOpenExternalFolder,
                contributedDrawerActions = contributedDrawerActions,
                onDrawerAction = onDrawerAction,
                onCloseWorkspace = onCloseWorkspace,
                onCloseProject = onCloseProject,
                onCollapseSidebar = onCollapseSidebar,
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
                                    onExportProject = onExportProject,
                                    onCreateProject = onCreateProject,
                                )
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }

                            when {
                                selectedProject != null -> {
                                    // Key on id + location so roster mutations never remount/reload the tree,
                                    // but a REUSED row id (SQLite hands the deleted max rowid to the next
                                    // insert — routine in the single-slot Default workspace) still remounts
                                    // when it names a different folder.
                                    key(selectedProject.id, selectedProject.location) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            ExplorerFeature.Content(
                                                workspace = workspace,
                                                project = selectedProject,
                                                fs = workspaceManager.fsFor(selectedProject.fsPath),
                                                context = context,
                                                modifier = Modifier.fillMaxSize(),
                                                viewMode = explorerViewModeOf(effectiveConfig.explorer.viewMode),
                                                hiddenPatterns = LocalExplorerHiddenSetting.current.hiddenPatternsFor(selectedProject.id.toString()),
                                                greyOutExcluded = LocalExplorerHiddenSetting.current.effect == ExplorerExcludeEffect.GreyOut,
                                                autoRefreshEnabled = explorerAutoRefresh,
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

                    WorkbenchTool.Search -> SearchToolPanel(
                        project = selectedProject,
                        activeTab = editorGroup.activeTab,
                        onOpenResult = onOpenPathAtLine,
                        seed = searchSeed,
                        onSeedConsumed = onSearchSeedConsumed,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.Scm -> ScmPanel(
                        installed = installedExtensions,
                        onExec = managerActions.onExtensionExec,
                        onApiRequest = managerActions.onExtensionApiRequest,
                        events = managerActions.extensionEvents,
                        projectKey = selectedProject?.id,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.RunDebug -> RunPanel(
                        // A User Workspace lists every project (pick one first); the Default Workspace
                        // shows just the open project's Build/Run detail.
                        projects = if (inUserWorkspace) workspace?.projects.orEmpty() else listOfNotNull(selectedProject),
                        inUserWorkspace = inUserWorkspace,
                        runningProjectId = runningProjectId,
                        runningRunName = runningRunName,
                        runUrl = runUrl,
                        runInProgress = runInProgress,
                        runConfigVersion = runConfigVersion,
                        debugUi = LocalDebugSession.current,
                        onRun = runActions.onRun,
                        onDebug = runActions.onDebug,
                        onBuild = runActions.onBuild,
                        onStop = runActions.onStop,
                        onOpenInBrowser = runActions.onOpenInBrowser,
                        onConfigureRun = runActions.onConfigureRun,
                        onConfigureBuild = runActions.onConfigureBuild,
                        onAddRunPresets = { project, configs -> runActions.onSaveRuns(project, configs) },
                        onAddBuildPreset = { project, config -> runActions.onSaveBuild(project, null, config) },
                        onDeleteRun = runActions.onDeleteRun,
                        onDeleteBuild = runActions.onDeleteBuild,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.Extensions -> {
                        val pendingReload = LocalPendingReload.current
                        ExtensionsPanel(
                            installed = installedExtensions,
                            available = marketplaceEntries,
                            busy = marketplaceBusy,
                            installPhases = LocalExtensionInstallPhases.current,
                            onRefreshMarketplace = managerActions.onRefreshMarketplace,
                            onOpenDetail = managerActions.onOpenExtensionDetail,
                            onOpenPermissions = managerActions.onOpenExtensionPermissions,
                            pendingReloadNames = pendingReload.names,
                            onReloadPending = pendingReload.onReload,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    WorkbenchTool.ToolchainManager -> ToolchainManagerPanel(
                        sdkState = sdkCatalogState,
                        lspState = lspCatalogState,
                        debugState = LocalDebugCatalogState.current,
                        environmentState = environmentState,
                        onRefreshAll = {
                            managerActions.onCheckSdkStatuses()
                            managerActions.onCheckLspStatuses()
                            managerActions.onCheckDebugStatuses()
                        },
                        onUpdateAll = managerActions.onUpdateAllToolchains,
                        onOpenSdkDetail = managerActions.onOpenSdkDetail,
                        onOpenLspDetail = managerActions.onOpenLspDetail,
                        onOpenDebugDetail = managerActions.onOpenDebugEngineDetail,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.DbManager -> DbManagerPanel(
                        installed = installedExtensions,
                        onExec = managerActions.onExtensionExec,
                        onApiRequest = managerActions.onExtensionApiRequest,
                        events = managerActions.extensionEvents,
                        modifier = Modifier.fillMaxSize(),
                    )

                    WorkbenchTool.VmManager -> VmPanel(
                        installed = installedExtensions,
                        onExec = managerActions.onExtensionExec,
                        onApiRequest = managerActions.onExtensionApiRequest,
                        events = managerActions.extensionEvents,
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
    onStop: () -> Unit,
    onRerun: () -> Unit,
    runConfigNames: List<String>,
    onRunConfig: (Int) -> Unit,
    isRunning: Boolean,
    terminalBusy: Boolean,
    terminalHasUnseen: Boolean,
    terminalSessions: List<TerminalInstance>,
    onOpenTerminalSession: (String) -> Unit,
    onShowTerminal: () -> Unit,
    onSelectEditorTab: (String) -> Unit,
    onCloseEditorTab: (String) -> Unit,
    onSave: () -> Unit,
    onFind: () -> Unit,
    onOpenFileRequest: () -> Unit,
    languageActionsEnabled: Boolean,
    onEditorLanguageAction: (EditorLanguageAction, String) -> Unit,
    editorPageContent: @Composable (EditorTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val chrome = LocalChromeControls.current
    Surface(
        modifier = modifier.fillMaxHeight(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // The palette's "Hide Header and Tabs" mode collapses the header (the tab strip hides
            // inside EditorPane via the same local); the floating pill below restores it.
            AnimatedVisibility(
                visible = !chrome.chromeHidden,
                enter = expandVertically(animationSpec = tween(200)),
                exit = shrinkVertically(animationSpec = tween(200)),
            ) {
                Column {
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
                        onStop = onStop,
                        onRerun = onRerun,
                        runConfigNames = runConfigNames,
                        onRunConfig = onRunConfig,
                        isRunning = isRunning,
                        terminalBusy = terminalBusy,
                        terminalHasUnseen = terminalHasUnseen,
                        terminalSessions = terminalSessions,
                        onOpenTerminalSession = onOpenTerminalSession,
                        onSave = onSave,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
                }
            }

            if (editorGroup.tabs.isEmpty()) {
                EditorEmptyState(
                    hasProject = selectedProject != null,
                    onOpenFileRequest = onOpenFileRequest,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            } else {
                val dbg = LocalDebugEditorState.current
                val dbgSession = LocalDebugSession.current
                EditorPane(
                    group = editorGroup,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    onTabSelected = onSelectEditorTab,
                    onTabClosed = onCloseEditorTab,
                    onOpenFile = onOpenFileRequest,
                    onSave = onSave,
                    onFind = onFind,
                    languageActionsEnabled = languageActionsEnabled,
                    onLanguageAction = onEditorLanguageAction,
                    breakpointLinesFor = { tab -> dbg.breakpoints[tab.filePath.path].orEmpty() },
                    stoppedLineFor = { tab -> if (dbg.stoppedPath == tab.filePath.path) dbg.stoppedLine else null },
                    onToggleBreakpoint = { tab, line -> dbg.onToggleBreakpoint(tab.filePath.path, line) },
                    // Long-press variable inspection is live only while the debugger is paused.
                    evaluateInDebugFrame = if (dbgSession.state == DebugState.STOPPED) dbgSession.onEvaluate else null,
                    pageContent = editorPageContent,
                )
            }
        }
        if (chrome.chromeHidden) {
            // 44dp box (≥ the 48dp min once IconButton's own touch expansion is added) keeps the only
            // exit from hidden-chrome mode comfortably tappable.
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(44.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f))
                    .clickable { chrome.onSetChromeHidden(false) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.ChevronDown),
                    contentDescription = "Show header and tabs",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        }
    }
}

/**
 * Recents + folder actions for the empty-editor "no project" state. Provided by [JCodeApp] and read by
 * [EditorEmptyState] via CompositionLocal so the (register-pressured) shell composable's signature stays
 * untouched.
 */
internal data class EditorEmptyActions(
    val recents: List<RecentEntity> = emptyList(),
    val onOpenRecent: (RecentEntity) -> Unit = {},
    val onExportRecent: (RecentEntity) -> Unit = {},
    val onNewProject: () -> Unit = {},
    val onOpenFolder: () -> Unit = {},
    /** Extension-contributed actions (e.g. Clone, Remote Repo) shown after New/Open Folder. */
    val startActions: List<MainViewModel.ShellContribution> = emptyList(),
    val onAction: (MainViewModel.ShellContribution) -> Unit = {},
)

internal val LocalEditorEmptyActions = compositionLocalOf { EditorEmptyActions() }

/**
 * Clone / Remote-Repo state + callbacks for the VCS editor pages and the drawer "Open Folder" dropdown.
 * Provided by [JCodeApp] (which holds the ViewModel) and read by the inner shell via CompositionLocal.
 */
internal data class VcsActions(
    val drawerActions: List<MainViewModel.ShellContribution> = emptyList(),
    val onAction: (MainViewModel.ShellContribution) -> Unit = {},
)

internal val LocalVcsActions = compositionLocalOf { VcsActions() }

/**
 * Editor area when no tab is open. With a project open it points at the Explorer; with no project it
 * lists recently opened folders plus New/Open actions.
 */
@Composable
private fun EditorEmptyState(
    hasProject: Boolean,
    onOpenFileRequest: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (hasProject) {
        EditorEmptyHint(
            title = "No file open",
            message = "Pick a file from the Explorer to start editing.",
            actionLabel = "Open Explorer",
            onAction = onOpenFileRequest,
            modifier = modifier,
        )
    } else {
        EditorRecents(actions = LocalEditorEmptyActions.current, modifier = modifier)
    }
}

@Composable
private fun EditorEmptyHint(
    title: String,
    message: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Surface(
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Files),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            WorkbenchActionButton(text = actionLabel, onClick = onAction, active = true)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EditorRecents(actions: EditorEmptyActions, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.TopCenter) {
        Column(
            modifier = Modifier
                .widthIn(max = 460.dp)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.Files),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text("Open a project", fontWeight = FontWeight.SemiBold)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WorkbenchActionButton(text = "New Folder", onClick = actions.onNewProject, active = true)
                WorkbenchActionButton(text = "Open Folder", onClick = actions.onOpenFolder)
                actions.startActions.forEach { action ->
                    WorkbenchActionButton(text = action.label, onClick = { actions.onAction(action) })
                }
            }
            if (actions.recents.isEmpty()) {
                Text(
                    text = "No recent projects yet. Open or create a folder to get started.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = "Recent",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                actions.recents.forEach { recent ->
                    RecentRow(
                        recent = recent,
                        onOpen = { actions.onOpenRecent(recent) },
                        onExport = { actions.onExportRecent(recent) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentRow(recent: RecentEntity, onOpen: () -> Unit, onExport: () -> Unit) {
    var menuOpen by remember { mutableStateOf(false) }
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onOpen),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 8.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                modifier = Modifier.size(24.dp),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Code),
                        contentDescription = null,
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = recentDisplayName(recent),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = recentSubtitle(recent),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // SAF recents have no local dir to copy out; only offer Export for Local ones.
            if (recent.kind == ProjectKind.Local) {
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.MoreVert),
                            contentDescription = "Recent project actions",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompactContextMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        listActions = listOf(
                            ContextAction(JCodeIcon.Save, "Export to storage") { onExport() },
                        ),
                    )
                }
            }
        }
    }
}

private fun recentDisplayName(recent: RecentEntity): String = when (recent.kind) {
    ProjectKind.Local -> File(recent.uri).name.ifBlank { recent.uri }
    ProjectKind.Saf -> Uri.parse(recent.uri).lastPathSegment
        ?.substringAfterLast('/')?.substringAfterLast(':')?.ifBlank { null }
        ?: "Folder"
}

private fun recentSubtitle(recent: RecentEntity): String = when (recent.kind) {
    ProjectKind.Local -> File(recent.uri).parent ?: recent.uri
    ProjectKind.Saf -> "External folder"
}

/** A live terminal instance shown in the Terminal-button long-press list. */
/** Terminal tab/instance label, capped at 8 characters (the OSC title can be a long command line). */
private fun terminalTabLabel(raw: String?): String {
    val s = (raw ?: "terminal").ifBlank { "terminal" }
    return if (s.length > 8) s.take(7) + "…" else s
}

@Composable
private fun WorkbenchRightSidebar(
    selectedTab: RightPanelTab,
    selectedProject: Project?,
    terminalSessionIds: List<String>,
    selectedTerminalSessionId: String,
    activeTerminalPty: dev.jcode.core.term.PtyProcess?,
    terminalSessionFor: (String) -> dev.jcode.core.term.TerminalSessionManager.Session?,
    terminalTitleFor: (String) -> String?,
    terminalReady: Boolean,
    onOpenEnvironmentWizard: () -> Unit,
    onTabSelected: (RightPanelTab) -> Unit,
    onSelectTerminalSession: (String) -> Unit,
    onAddTerminalSession: () -> Unit,
    onRemoveTerminalSession: (String) -> Unit,
    onHide: () -> Unit,
    runningProjectName: String?,
    runInProgress: Boolean,
    onStopRun: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Fully opaque: any translucency lets bright editor text ghost through the panel content
    // (clearly visible on OLED), and every tab's text then sits on an unstable background.
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Tabs scroll within their own weighted area so the trailing actions below stay
                // pinned to the right edge — no scrolling to reach "Hide".
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // The Chat tab's title tracks the installed agent extension (e.g. "OpenChamber"),
                    // and the tab is shown only while such an extension is installed.
                    val chatAvailable = hasAgentChatExtension()
                    val chatTabTitle = agentChatTabTitle()
                    val devMode = LocalDeveloperSetting.current.enabled
                    RightPanelTab.entries
                        .filter {
                            it.enabled &&
                                (it != RightPanelTab.Chat || chatAvailable) &&
                                (it != RightPanelTab.Devtools || BuiltinBrowser.everOpened.value) &&
                                (it != RightPanelTab.ExtensionDev || devMode)
                        }
                        .forEach { tab ->
                        val selected = tab == selectedTab
                        val tabLabel = if (tab == RightPanelTab.Chat) chatTabTitle else tab.label
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
                                    text = tabLabel,
                                    style = MaterialTheme.typography.labelMedium,
                                    color = tint,
                                )
                            }
                        }
                    }
                }
                if (selectedTab == RightPanelTab.Chat) {
                    WorkbenchIconActionButton(
                        icon = jcIcon(JCodeIcon.Settings),
                        contentDescription = "Agent settings",
                        onClick = { AgentChatWebViewHolder.postCommand("showSettings") },
                    )
                }
                WorkbenchIconActionButton(
                    icon = jcIcon(JCodeIcon.Close),
                    contentDescription = "Close",
                    onClick = onHide,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
            WorkbenchRightSidebarBody(
                tab = selectedTab,
                selectedProject = selectedProject,
                terminalSessionIds = terminalSessionIds,
                selectedTerminalSessionId = selectedTerminalSessionId,
                activeTerminalPty = activeTerminalPty,
                terminalSessionFor = terminalSessionFor,
                terminalTitleFor = terminalTitleFor,
                terminalReady = terminalReady,
                onOpenEnvironmentWizard = onOpenEnvironmentWizard,
                onSelectTerminalSession = onSelectTerminalSession,
                onAddTerminalSession = onAddTerminalSession,
                onRemoveTerminalSession = onRemoveTerminalSession,
                runningProjectName = runningProjectName,
                runInProgress = runInProgress,
                onStopRun = onStopRun,
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
    terminalTitleFor: (String) -> String?,
    terminalReady: Boolean,
    onOpenEnvironmentWizard: () -> Unit,
    onSelectTerminalSession: (String) -> Unit,
    onAddTerminalSession: () -> Unit,
    onRemoveTerminalSession: (String) -> Unit,
    runningProjectName: String?,
    runInProgress: Boolean,
    onStopRun: () -> Unit,
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
                terminalTitleFor = terminalTitleFor,
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
            IssuesSidebarContent(modifier = modifier)
        }
        RightPanelTab.DebugConsole -> {
            DebugConsoleSidebarContent(modifier = modifier)
        }
        RightPanelTab.Tasks -> {
            TaskManagerSidebarContent(
                terminalSessionIds = terminalSessionIds,
                terminalSessionFor = terminalSessionFor,
                terminalTitleFor = terminalTitleFor,
                onCloseTerminal = onRemoveTerminalSession,
                runningProjectName = runningProjectName,
                runInProgress = runInProgress,
                onStopRun = onStopRun,
                modifier = modifier,
            )
        }
        RightPanelTab.Devtools -> {
            DevtoolsSidebarContent(modifier = modifier)
        }
        RightPanelTab.ExtensionDev -> {
            ExtensionDevSidebarContent(modifier = modifier)
        }
        RightPanelTab.Chat -> {
            AgentChatSidebarContent(modifier = modifier)
        }
    }
}

/**
 * Prompt for closing editor tab(s) that have unsaved changes. Save persists then closes; Discard closes
 * and loses the edits; the third action differs by context — "Close Saved" for a tab close (keep the
 * dirty tabs open, close the already-saved ones) or "Cancel" for a workspace/project switch (where the
 * tabs can't be kept). Dismissing keeps everything as-is.
 */
@Composable
private fun UnsavedChangesDialog(
    titles: List<String>,
    thirdLabel: String,
    onSave: () -> Unit,
    onDiscard: () -> Unit,
    onThird: () -> Unit,
    onDismiss: () -> Unit,
    // When set, a visible "Cancel" button that aborts the close (keeping everything). Omitted where the
    // third button already serves as Cancel, so the row never shows two cancels.
    onCancel: (() -> Unit)? = null,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Unsaved changes") },
        text = {
            Text(
                if (titles.size == 1) "\"${titles.first()}\" has unsaved changes."
                else "These files have unsaved changes:\n" + titles.joinToString("\n") { "•  $it" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // Destructive "Discard" is kept out of the rightmost (reflexive-tap) slot; "Save" is the safe
        // primary at the end.
        confirmButton = {
            TextButton(onClick = onDiscard) {
                Text("Discard", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onThird) { Text(thirdLabel) }
            TextButton(onClick = onSave) { Text("Save") }
        },
        dismissButton = {
            onCancel?.let { cancel ->
                TextButton(onClick = cancel) { Text("Cancel") }
            }
        },
    )
}

/**
 * Prompt for closing terminal tab(s) with a running foreground program. Kill ends the program(s) and
 * closes them; Close Unbusy closes only the idle terminals and keeps the busy ones (disabled when the
 * set is entirely busy); Cancel keeps everything.
 */
@Composable
private fun TerminalRunningDialog(
    runningNames: List<String>,
    closeUnbusyEnabled: Boolean,
    onKill: () -> Unit,
    onCloseUnbusy: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(if (runningNames.size == 1) "Running process" else "Running processes") },
        text = {
            Text(
                "Still running:\n" + runningNames.joinToString("\n") { "•  $it" },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        // Destructive "Kill" is kept out of the rightmost (reflexive-tap) slot; "Cancel" is the safe
        // default at the end.
        confirmButton = {
            TextButton(onClick = onKill) {
                Text("Kill", color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = onCloseUnbusy, enabled = closeUnbusyEnabled) { Text("Close Unbusy") }
            TextButton(onClick = onCancel) { Text("Cancel") }
        },
        dismissButton = {},
    )
}

@Composable
private fun TerminalSidebarContent(
    selectedProject: Project?,
    terminalSessionIds: List<String>,
    selectedTerminalSessionId: String,
    activeTerminalPty: dev.jcode.core.term.PtyProcess?,
    terminalSessionFor: (String) -> dev.jcode.core.term.TerminalSessionManager.Session?,
    terminalTitleFor: (String) -> String?,
    terminalReady: Boolean,
    onOpenEnvironmentWizard: () -> Unit,
    onSelectTerminalSession: (String) -> Unit,
    onAddTerminalSession: () -> Unit,
    onRemoveTerminalSession: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        var menuForId by remember { mutableStateOf<String?>(null) }
        // Pinned terminals sort to the front, hide their "×", and are skipped by Close others/all.
        // Terminals are ephemeral (never persisted), so this pin state is UI-local too.
        var pinnedTerminalIds by rememberSaveable { mutableStateOf(emptyList<String>()) }
        // Drop pins for sessions that have gone away so the set can't leak stale ids.
        LaunchedEffect(terminalSessionIds) {
            val live = pinnedTerminalIds.filter { it in terminalSessionIds }
            if (live.size != pinnedTerminalIds.size) pinnedTerminalIds = live
        }
        val orderedSessionIds = terminalSessionIds.sortedBy { it !in pinnedTerminalIds }
        // Closing a terminal running a foreground program prompts first (Kill / Close Unbusy / Cancel).
        // "Unbusy" = idle at the prompt (Session.foreground == null). Honors the same confirm-close
        // preference as the project/workspace close guard.
        val perf = LocalPerformanceSettings.current
        var pendingTerminalClose by remember { mutableStateOf<List<String>?>(null) }
        fun terminalBusy(id: String) = terminalSessionFor(id)?.foreground != null
        fun requestCloseTerminals(ids: List<String>) {
            val targets = ids.filter { it in terminalSessionIds }
            if (targets.isEmpty()) return
            if (perf.confirmCloseRunning && targets.any(::terminalBusy)) pendingTerminalClose = targets
            else targets.forEach(onRemoveTerminalSession)
        }
        pendingTerminalClose?.let { ids ->
            val runningNames = ids.filter(::terminalBusy).map { terminalSessionFor(it)?.foreground ?: "a process" }
            TerminalRunningDialog(
                runningNames = runningNames,
                closeUnbusyEnabled = ids.any { !terminalBusy(it) },
                onKill = { pendingTerminalClose = null; ids.forEach(onRemoveTerminalSession) },
                onCloseUnbusy = {
                    pendingTerminalClose = null
                    ids.filterNot(::terminalBusy).forEach(onRemoveTerminalSession)
                },
                onCancel = { pendingTerminalClose = null },
            )
        }
        // Session tab bar — flat editor-style tabs (see EditorPane.TabItem), sized down for the drawer.
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .height(30.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                orderedSessionIds.forEach { sessionId ->
                    val isActive = sessionId == selectedTerminalSessionId
                    val isPinned = sessionId in pinnedTerminalIds
                    // Relocated interactive sub-shell tabs (OSC 7715) are transient: marked with ↳ and
                    // not pinnable (they close automatically when their sub-shell exits).
                    val isRelocated = terminalSessionFor(sessionId)?.relocationParentId != null
                    Box {
                        Row(
                            modifier = Modifier
                                .combinedClickable(
                                    onClick = { onSelectTerminalSession(sessionId) },
                                    onLongClick = { menuForId = sessionId },
                                )
                                .background(
                                    if (isActive) MaterialTheme.colorScheme.surfaceVariant
                                    else MaterialTheme.colorScheme.surface
                                )
                                .height(30.dp)
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            // Pinned terminal shows a leading pin instead of a "×" (close via long-press).
                            if (isPinned) {
                                Icon(
                                    imageVector = jcIcon(JCodeIcon.Pin),
                                    contentDescription = "Pinned",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(12.dp),
                                )
                            }
                            if (isRelocated && !isPinned) {
                                Text(
                                    text = "↳",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            MiddleEllipsisText(
                                text = terminalTabLabel(
                                    terminalTitleFor(sessionId) ?: terminalSessionFor(sessionId)?.label
                                ),
                                maxWidth = LocalTabMaxSize.current.size.titleMaxWidth,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isActive) {
                                    MaterialTheme.colorScheme.onSurface
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                            // The "×" appears ONLY on the active, unpinned tab (so tapping a background
                            // tab to switch can't close it). A plain clickable Box keeps the touch target
                            // tight (18dp) — an IconButton's 48dp minimum would spill over the title.
                            if (isActive && !isPinned && !LocalTabCloseButtonSetting.current.hidden) {
                                JcTooltip("Close terminal") {
                                    Box(
                                        modifier = Modifier
                                            .size(18.dp)
                                            .clip(CircleShape)
                                            .clickable { requestCloseTerminals(listOf(sessionId)) },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Text(
                                            text = "×",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                        CompactContextMenu(
                            expanded = menuForId == sessionId,
                            onDismissRequest = { menuForId = null },
                            quickActions = listOf(
                                ContextAction(JCodeIcon.Close, "Close") { requestCloseTerminals(listOf(sessionId)) },
                            ),
                            listActions = buildList {
                                if (!isRelocated) {
                                    add(ContextAction(JCodeIcon.Pin, if (isPinned) "Unpin" else "Pin") {
                                        pinnedTerminalIds = if (isPinned) {
                                            pinnedTerminalIds - sessionId
                                        } else {
                                            pinnedTerminalIds + sessionId
                                        }
                                    })
                                }
                                add(ContextAction(JCodeIcon.Clear, "Clear") {
                                    terminalSessionFor(sessionId)?.pty?.write(byteArrayOf(0x0C))
                                })
                                add(ContextAction(JCodeIcon.Close, "Close others") {
                                    requestCloseTerminals(terminalSessionIds.filter { it != sessionId && it !in pinnedTerminalIds })
                                })
                                add(ContextAction(JCodeIcon.Close, "Close all") {
                                    requestCloseTerminals(terminalSessionIds.filter { it !in pinnedTerminalIds })
                                })
                            },
                        )
                    }
                }
                JcTooltip("New terminal") {
                    IconButton(
                        onClick = onAddTerminalSession,
                        modifier = Modifier.size(width = 32.dp, height = 30.dp),
                    ) {
                        Text(
                            text = "+",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
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
            val extraKeys = LocalExtraKeysState.current
            val terminalTypeface = LocalTerminalTypeface.current
            val termSaveActions = LocalEditorSaveActions.current
            var termMenu by remember { mutableStateOf<TerminalMenuRequest?>(null) }
            // Points the extra-keys row at whichever terminal owns the IME; cleared on focus loss so
            // the row never acts on (or shows for) a surface that isn't being typed into.
            val wireExtraKeys: (TerminalView) -> Unit = { view ->
                val adapter = TerminalExtraKeysTarget(view)
                view.onFocusStateChanged = { focused ->
                    if (focused) {
                        extraKeys.clearModifiers()
                        extraKeys.target = adapter
                    } else if (extraKeys.target === adapter) {
                        extraKeys.clearModifiers()
                        extraKeys.target = null
                    }
                }
                view.onPendingModifiersConsumed = { extraKeys.clearModifiers() }
                if (view.isFocused) extraKeys.target = adapter
            }
            Box(modifier = Modifier.fillMaxSize()) {
                terminalSessionIds.forEach { sessionId ->
                    val session = terminalSessionFor(sessionId) ?: return@forEach
                    val isActive = sessionId == selectedTerminalSessionId
                    key(sessionId) {
                        AndroidView(
                            factory = { ctx ->
                                TerminalView(ctx).apply {
                                    setFontSize(30f)
                                    setTypeface(terminalTypeface)
                                    onTapToken = tapConfig.onToken
                                    onContextMenu = { x, y -> termMenu = TerminalMenuRequest(x, y, this) }
                                    onPasteImage = tapConfig.onPasteImage
                                    onCloseTabRequest = { requestCloseTerminals(listOf(sessionId)) }
                                    onSaveAllRequest = { termSaveActions.onSaveAll() }
                                    wireExtraKeys(this)
                                    bind(session)
                                }
                            },
                            update = { view ->
                                view.setTypeface(terminalTypeface)
                                view.onTapToken = tapConfig.onToken
                                view.onContextMenu = { x, y -> termMenu = TerminalMenuRequest(x, y, view) }
                                view.onPasteImage = tapConfig.onPasteImage
                                view.onCloseTabRequest = { requestCloseTerminals(listOf(sessionId)) }
                                view.onSaveAllRequest = { termSaveActions.onSaveAll() }
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
                termMenu?.let { req ->
                    val menuOffset = with(LocalDensity.current) { DpOffset(req.x.toDp(), req.y.toDp()) }
                    CompactContextMenu(
                        expanded = true,
                        onDismissRequest = { termMenu = null },
                        offset = menuOffset,
                        quickActions = buildList {
                            if (req.view.hasSelection()) {
                                add(ContextAction(JCodeIcon.Copy, "Copy") { req.view.contextCopy() })
                            }
                            add(ContextAction(JCodeIcon.Paste, "Paste") { req.view.contextPaste() })
                        },
                        listActions = listOf(
                            ContextAction(JCodeIcon.Cursor, "Select Text") { req.view.beginTextSelection() },
                            ContextAction(JCodeIcon.SelectAll, "Select all") { req.view.contextSelectAll() },
                            ContextAction(JCodeIcon.Clear, "Clear") { req.view.contextClear() },
                        ),
                    )
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
private fun OutputSidebarContent(
    modifier: Modifier = Modifier,
) {
    val lines by OutputLog.lines.collectAsStateWithLifecycle()
    Box(modifier = modifier.fillMaxSize()) {
        if (lines.isEmpty()) {
            Text(
                text = "Build logs and tool output will appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        } else {
            val listState = rememberLazyListState()
            // Auto-follow new output only when already pinned to the bottom, so scrolling up to read
            // mid-build doesn't yank the view back down on the next line.
            val atBottom by remember {
                derivedStateOf {
                    val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                    last == null || last.index >= listState.layoutInfo.totalItemsCount - 1
                }
            }
            LaunchedEffect(lines.size) {
                if (atBottom && lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 12.dp),
            ) {
                items(lines) { line ->
                    Text(
                        text = line.text.ifEmpty { " " },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 16.sp,
                        color = outputColor(line.kind),
                    )
                }
            }
        }
        if (lines.isNotEmpty()) {
            Box(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                WorkbenchIconActionButton(
                    icon = jcIcon(JCodeIcon.Clear),
                    contentDescription = "Clear output",
                    onClick = { OutputLog.clear() },
                )
            }
        }
    }
}

@Composable
private fun outputColor(kind: OutputKind) = when (kind) {
    OutputKind.Header -> MaterialTheme.colorScheme.primary
    OutputKind.Error -> MaterialTheme.colorScheme.error
    OutputKind.Info -> MaterialTheme.colorScheme.onSurfaceVariant
    OutputKind.Stdout -> MaterialTheme.colorScheme.onSurface
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

/** Unwrap the hosting [Activity] from a Compose [Context] (it may be a ContextWrapper). */
internal fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}

/**
 * When [enabled], hides the system status bar while the soft keyboard is up (and restores it when the
 * keyboard closes), giving the editor and terminal more vertical room. The bar can still be revealed
 * with a swipe from the top. Restores the bar when the setting is off or this leaves composition.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StatusBarKeyboardController(enabled: Boolean) {
    val activity = LocalContext.current.findActivity() ?: return
    val imeVisible = WindowInsets.isImeVisible
    val fullscreen by WindowModeState.fullscreen.collectAsStateWithLifecycle()
    // Hiding the system bar kicks off a second insets animation + relayout wave; delay it until the
    // IME animation has settled so the two don't overlap (the delay also drops the pending hide for
    // free when the keyboard closes again quickly). Showing stays immediate so closing the keyboard
    // never leaves the bar hidden. While the palette's Fullscreen mode owns the bars, do nothing —
    // re-showing here would undo it on every keyboard transition.
    LaunchedEffect(enabled, imeVisible, fullscreen) {
        if (fullscreen) return@LaunchedEffect
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (enabled && imeVisible) {
            delay(300)
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.statusBars())
        } else {
            controller.show(WindowInsetsCompat.Type.statusBars())
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            if (WindowModeState.fullscreen.value) return@onDispose
            activity.window?.let { window ->
                WindowCompat.getInsetsController(window, window.decorView)
                    .show(WindowInsetsCompat.Type.statusBars())
            }
        }
    }
}

/**
 * System-back handling for the workbench. One back navigates back a step (close the active page tab,
 * then the modal drawer, then the right sidebar). With nothing left, a second back within 2s exits —
 * unless [isBusy] (a run is going or a terminal is live), in which case it backgrounds the app.
 */
@Composable
private fun WorkbenchBackHandler(
    activeTabIsPage: Boolean,
    activeTabId: String?,
    onCloseActiveTab: (String) -> Unit,
    drawerOpen: Boolean,
    onCloseDrawer: () -> Unit,
    rightSidebarVisible: Boolean,
    onCloseRightSidebar: () -> Unit,
    runningItems: () -> List<String>,
    onTerminateAll: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val activity = LocalContext.current.findActivity()
    val scope = rememberCoroutineScope()
    var lastBackAt by remember { mutableStateOf(0L) }
    var exitPromptItems by remember { mutableStateOf<List<String>?>(null) }
    BackHandler(enabled = true) {
        when {
            activeTabIsPage && activeTabId != null -> onCloseActiveTab(activeTabId)
            drawerOpen -> onCloseDrawer()
            rightSidebarVisible -> onCloseRightSidebar()
            else -> {
                val now = System.currentTimeMillis()
                if (now - lastBackAt < 2000L) {
                    val items = runningItems()
                    if (items.isEmpty()) {
                        // Also reaps idle terminals: exiting must not leave proot trees behind.
                        onTerminateAll()
                        activity?.finish()
                    } else {
                        exitPromptItems = items
                    }
                } else {
                    lastBackAt = now
                    scope.launch { snackbarHostState.showSnackbar("Press back again to exit") }
                }
            }
        }
    }
    exitPromptItems?.let { items ->
        AlertDialog(
            onDismissRequest = { exitPromptItems = null },
            title = { Text("Exit JCode?") },
            text = {
                Text("Still running:\n" + items.joinToString("\n") { "•  $it" })
            },
            confirmButton = {
                TextButton(onClick = { exitPromptItems = null; activity?.moveTaskToBack(true) }) {
                    Text("Run in background")
                }
                TextButton(
                    onClick = {
                        exitPromptItems = null
                        onTerminateAll()
                        activity?.finish()
                    },
                ) {
                    Text("Terminate & exit", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { exitPromptItems = null }) { Text("Cancel") }
            },
        )
    }
}

/** Pending terminal long-press menu: the touch point (view px) and the view to act on. */
private data class TerminalMenuRequest(
    val x: Float,
    val y: Float,
    val view: dev.jcode.core.term.TerminalView,
)

/** Map an extension-contributed action's manifest `icon` name onto a semantic icon slot. */
private fun contributedMenuIcon(name: String?): JCodeIcon = when (name?.lowercase()) {
    "scm", "source-control", "git" -> JCodeIcon.Scm
    "database", "db" -> JCodeIcon.Database
    "vm" -> JCodeIcon.Vm
    "run" -> JCodeIcon.Run
    "debug" -> JCodeIcon.Debug
    "terminal" -> JCodeIcon.Terminal
    "browser", "web" -> JCodeIcon.Browser
    "search" -> JCodeIcon.Search
    "settings", "gear" -> JCodeIcon.Settings
    "chat" -> JCodeIcon.Chat
    "image" -> JCodeIcon.Image
    "code" -> JCodeIcon.Code
    "preview", "eye" -> JCodeIcon.Preview
    "format" -> JCodeIcon.Format
    else -> JCodeIcon.Extensions
}

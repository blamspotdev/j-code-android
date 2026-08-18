package dev.jcode.workbench

import androidx.compose.runtime.compositionLocalOf
import dev.jcode.core.config.BuildConfig
import dev.jcode.core.config.RunConfig
import dev.jcode.core.debug.DapStackFrame
import dev.jcode.core.debug.DebugState
import dev.jcode.core.distro.DebugEngineCatalogState
import dev.jcode.debug.VariableRow
import dev.jcode.design.JCodeIcon
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.fs.Project
import dev.jcode.run.ProjectRunner

/** A live terminal session's id + display label, listed in the top-bar terminal menu. */
internal data class TerminalInstance(val id: String, val label: String)

/** Terminal tap behavior, provided to the deeply-nested terminal view without prop-drilling. */
internal data class TerminalTapConfig(
    /** Invoked with the tapped token (a URL is opened in the browser; a path in the editor). */
    val onToken: (String) -> Unit = {},
    /** Invoked on paste when the clipboard holds an image: saves it into the active project and
     *  returns the guest path to paste (or null to skip). */
    val onPasteImage: (android.net.Uri) -> String? = { null },
)

internal val LocalTerminalTapConfig = compositionLocalOf { TerminalTapConfig() }

/**
 * Debug-engine catalog state, provided to the giant [dev.jcode.JCodeShell] composable via a
 * CompositionLocal instead of one more param (the composable is at the ART verifier's register limit).
 */
internal val LocalDebugCatalogState = compositionLocalOf { DebugEngineCatalogState() }

/** How far the running toolchain install has got (percent + phase), or null when nothing is running
 *  or the script reports nothing. Same register-limit rationale as [LocalDebugCatalogState]. */
internal val LocalCatalogProgress = compositionLocalOf<dev.jcode.core.distro.CatalogProgress?> { null }

/** The SDK the user pressed Install on, which differs from the running one while a toolchain it
 *  requires is being installed first. Same register-limit rationale as [LocalCatalogProgress]. */
internal val LocalSdkInstallRequestedId = compositionLocalOf<String?> { null }

/** Per-extension install phase labels ("Installing…", "Installing required tools…", "Verifying…"),
 *  keyed by extension id. A CompositionLocal so the giant [dev.jcode.JCodeShell] composable stays
 *  under the ART register limit. */
internal val LocalExtensionInstallPhases = compositionLocalOf<Map<String, String>> { emptyMap() }

/** State for the Extension Sources page: the configured source repo URLs, the newest `.vsix` release
 *  resolved per URL, the installed-extension-id → source URL attribution, and whether a refresh is in
 *  flight. Provided as a CompositionLocal so the page reads it without threading four params through
 *  the workbench composables (same reason as [LocalExtensionInstallPhases]). */
internal data class ExtensionSourcesState(
    val sources: List<String> = emptyList(),
    val releases: Map<String, dev.jcode.ProviderRelease> = emptyMap(),
    val attribution: Map<String, String> = emptyMap(),
    val refreshing: Boolean = false,
)

internal val LocalExtensionSources = compositionLocalOf { ExtensionSourcesState() }

/** Names of extensions updated this session and awaiting a reload, plus the reload action — shown as a
 *  compact banner atop the Extensions panel. A CompositionLocal for the same register-limit reason. */
internal data class PendingReloadUi(val names: List<String> = emptyList(), val onReload: () -> Unit = {})
internal val LocalPendingReload = compositionLocalOf { PendingReloadUi() }

/** Run-config presets active extensions contribute, matched against the project's files on the
 *  Configure Run page. Same register-limit rationale as above. */
internal val LocalRunConfigPresets =
    compositionLocalOf<List<ProjectRunner.ExtensionRunPreset>> { emptyList() }

/** State for the right-drawer "Extension Dev" tab (developer options): the installed **dev** (unsigned
 *  sideloaded) extensions plus the host's extension-API version and reload/sideload actions. Provided
 *  via a CompositionLocal so the register-limited shell needn't thread it. */
internal data class ExtensionDevState(
    val extensions: List<InstalledExtension> = emptyList(),
    val hostApiVersion: Int = 1,
    val onReload: () -> Unit = {},
    val onLoad: () -> Unit = {},
)

internal val LocalExtensionDevState = compositionLocalOf { ExtensionDevState() }

/** Session id of the background "Setup" terminal (toolchain installs / project scaffolds), or null
 *  while none has been started. Same register-limit rationale as above. */
internal val LocalSetupTerminalSessionId = compositionLocalOf<String?> { null }

/** Editor-facing debug state (breakpoints + current stopped location + toggle), provided via a
 *  CompositionLocal so the giant [dev.jcode.JCodeShell] composable stays under the register limit. */
internal data class DebugEditorState(
    val breakpoints: Map<String, Set<Int>> = emptyMap(),
    val stoppedPath: String? = null,
    val stoppedLine: Int? = null,
    val onToggleBreakpoint: (String, Int) -> Unit = { _, _ -> },
)

internal val LocalDebugEditorState = compositionLocalOf { DebugEditorState() }

/** Live debug-session state + controls for the Run/Debug panel, provided via a CompositionLocal so
 *  the giant [dev.jcode.JCodeShell] composable stays under the ART verifier's register limit. */
internal data class DebugSessionUi(
    val state: DebugState = DebugState.DISCONNECTED,
    val callStack: List<DapStackFrame> = emptyList(),
    val variables: List<VariableRow> = emptyList(),
    val output: List<String> = emptyList(),
    /** Name of the file that Debug will launch (the active editor tab), or null if none is debuggable. */
    val debugTargetName: String? = null,
    /** True when [debugTargetName] has an installed debug engine (enables the Debug button). */
    val canDebug: Boolean = false,
    val onDebug: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onStepOver: () -> Unit = {},
    val onStepInto: () -> Unit = {},
    val onStepOut: () -> Unit = {},
    val onStop: () -> Unit = {},
    /** Evaluate an expression in the stopped frame (DAP hover); null result = no value. */
    val onEvaluate: (String, (String?) -> Unit) -> Unit = { _, cb -> cb(null) },
)

internal val LocalDebugSession = compositionLocalOf { DebugSessionUi() }

/** What a close request targets — used to route the confirm-before-close teardown. */
internal enum class CloseTarget { Project, Workspace }

/** Issues-tab actions, provided via a CompositionLocal so the deeply nested right drawer can open a
 *  diagnostic's file at its line without threading params through the register-limited shell. */
internal data class IssueActions(
    /** Open [path] (host path) in the editor and jump to the 0-based [line] and [column]. */
    val onOpen: (path: String, line: Int, column: Int) -> Unit = { _, _, _ -> },
)

internal val LocalIssueActions = compositionLocalOf { IssueActions() }

/**
 * The SDK / LSP / Extension manager callbacks, bundled so the giant [dev.jcode.JCodeShell] composable
 * stays under the ART verifier's per-method register limit (too many individual params overflow it).
 */
/** Run/Build/Debug callbacks for the Run panel + config editors, bundled (see register-limit note). */
internal data class WorkbenchRunActions(
    val onRun: (Project, RunConfig) -> Unit,
    val onDebug: (Project, RunConfig) -> Unit,
    val onBuild: (Project, BuildConfig) -> Unit,
    val onStop: () -> Unit,
    val onOpenInBrowser: () -> Unit,
    val onConfigureRun: (Project, Int?) -> Unit,
    val onConfigureBuild: (Project, Int?) -> Unit,
    val onSaveRuns: (Project, List<RunConfig>) -> Unit,
    val onSaveBuild: (Project, Int?, BuildConfig) -> Unit,
    val onDeleteRun: (Project, Int) -> Unit,
    val onDeleteBuild: (Project, Int) -> Unit,
)

internal data class WorkbenchManagerActions(
    val onCheckSdkStatuses: () -> Unit,
    val onInstallSdkCatalogEntry: (String) -> Unit,
    val onInstallSdkCatalogVersion: (String, String) -> Unit,
    val onUninstallSdkCatalogVersion: (String, String) -> Unit,
    val onUninstallSdkCatalogEntry: (String) -> Unit,
    val onOpenSdkDetail: (String) -> Unit,
    val onCheckLspStatuses: () -> Unit,
    val onInstallLspCatalogEntry: (String) -> Unit,
    val onUninstallLspCatalogEntry: (String) -> Unit,
    val onOpenLspDetail: (String) -> Unit,
    val onCheckDebugStatuses: () -> Unit,
    val onInstallDebugEngine: (String) -> Unit,
    val onUninstallDebugEngine: (String) -> Unit,
    val onOpenDebugEngineDetail: (String) -> Unit,
    val onRefreshMarketplace: () -> Unit,
    val onInstallExtension: (MarketplaceEntry) -> Unit,
    val onUninstallExtension: (String) -> Unit,
    val onOpenExtensionDetail: (String) -> Unit,
    val onOpenExtensionPermissions: () -> Unit,
    /** Opens the Extension Sources page (manage custom .vsix release repos) as an editor tab. */
    val onOpenExtensionSources: () -> Unit,
    /** Extension Sources page actions: add/remove a source repo URL, re-resolve releases, and
     *  install/update the newest `.vsix` from a source. */
    val onAddExtensionSource: (String) -> Unit = {},
    val onRemoveExtensionSource: (String) -> Unit = {},
    val onRefreshExtensionSources: () -> Unit = {},
    val onInstallFromSource: (String) -> Unit = {},
    /** Starts the "import a VS Code .vsix" flow from the extension list. Not a developer action —
     *  importing an extension JCode does not publish is ordinary use, so it is always available. */
    val onImportVsix: (() -> Unit)? = null,
    /** Opens an extension's web frontend at its `#config` route by extension id (e.g. Source Control
     *  git identity/credentials), reachable without an open project. */
    val onOpenExtensionConfig: (String) -> Unit,
    /** Runs `adb pair <target> <code>` in the Linux runtime; null = paired, else the failure text. */
    val onAdbPair: suspend (target: String, code: String) -> String? =
        { _, _ -> "ADB pairing is unavailable." },
    /** Runs a command in the Linux runtime for an extension web frontend; returns a JSON result. */
    val onExtensionExec: suspend (command: String, timeoutMs: Long) -> String,
    /** Spawns a long-lived runtime process with stdio attached, used to run an imported `.vsix`. */
    val onSpawnRuntimeProcess: ((command: String) -> Process?)? = null,
    /** Surfaces a webview panel an imported `.vsix` created (`createWebviewPanel`) as an editor tab. */
    val onOpenVsixPanel: (extensionId: String, handle: String, title: String) -> Unit = { _, _, _ -> },
    /** Extension API v1 envelope handler: (extensionId, requestJson) -> response JSON. */
    val onExtensionApiRequest: suspend (extensionId: String, envelopeJson: String) -> String =
        { _, _ -> """{"ok":false,"error":"extension API unavailable"}""" },
    /** Host events pushed to the live extension WebView as `JCode._onEvent(name, json)`. */
    val extensionEvents: kotlinx.coroutines.flow.SharedFlow<Pair<String, String>>? = null,
)

internal enum class WorkbenchTool(
    val label: String,
    val icon: JCodeIcon,
    val compactLabel: String = label,
    /** Hidden from the activity bar until it has a working UI (kept in the enum for `when` exhaustiveness). */
    val available: Boolean = true,
) {
    Explorer("Explorer", JCodeIcon.Files, "Files"),
    Search("Search", JCodeIcon.Search, "Find"),
    Scm("SCM", JCodeIcon.Scm, "SCM"),
    RunDebug("Run", JCodeIcon.Run, "Run"),
    Extensions("Extensions", JCodeIcon.Extensions, "Ext"),
    /** SDKs + language servers + debug engines, merged into one searchable/filterable catalog. */
    ToolchainManager("Toolchains", JCodeIcon.Sdk, "Tools"),
    DbManager("DB Managers", JCodeIcon.Database, "DB"),
    VmManager("VM Manager", JCodeIcon.Vm, "VM"),
    Settings("Settings", JCodeIcon.Settings, "Settings"),
}

internal enum class RightPanelTab(
    val label: String,
    val icon: JCodeIcon,
    val enabled: Boolean = true,
) {
    Terminal("Terminal", JCodeIcon.Terminal, enabled = true),
    Output("Output", JCodeIcon.Logs, enabled = true),
    Problems("Issues", JCodeIcon.Problems, enabled = true),
    DebugConsole("Debug", JCodeIcon.Debug, enabled = true),
    Tasks("Tasks", JCodeIcon.Tasks, enabled = true),
    /** Built-in browser DevTools (console / network / elements); only shown once the in-app browser
     *  has been opened this session (see [dev.jcode.workbench.BuiltinBrowser]). */
    Devtools("DevTools", JCodeIcon.DevTools, enabled = true),
    /** Extension-authoring tools (inspector / manifest validator / live log); shown only when
     *  Developer options is enabled (see [dev.jcode.design.LocalDeveloperSetting]). */
    ExtensionDev("Ext Dev", JCodeIcon.Extensions, enabled = true),
}

/**
 * What the right drawer is showing: one of its built-in panels, or an imported `.vsix`.
 *
 * The built-ins are a fixed set and stay an enum; extensions are not, so they cannot be. An imported
 * extension gets a tab of its own, which is why the selection has to name one rather than being a
 * single hardcoded slot.
 */
internal sealed interface RightPanelSelection {
    data class Builtin(val tab: RightPanelTab) : RightPanelSelection

    data class Extension(val extensionId: String) : RightPanelSelection

    /** Stable form for [androidx.compose.runtime.saveable.rememberSaveable]. */
    fun asKey(): String = when (this) {
        is Builtin -> "builtin:${tab.name}"
        is Extension -> "ext:$extensionId"
    }

    /** Fall back to the default when this names a built-in the caller isn't offering. */
    fun clampedTo(allowed: Set<RightPanelTab>): RightPanelSelection =
        if (this is Builtin && tab !in allowed) Default else this

    companion object {
        val Default = Builtin(RightPanelTab.Terminal)

        fun fromKey(key: String): RightPanelSelection = when {
            key.startsWith("ext:") -> Extension(key.removePrefix("ext:"))
            else -> key.removePrefix("builtin:")
                .let { name -> RightPanelTab.entries.firstOrNull { it.name == name } }
                ?.let { Builtin(it) } ?: Default
        }
    }
}

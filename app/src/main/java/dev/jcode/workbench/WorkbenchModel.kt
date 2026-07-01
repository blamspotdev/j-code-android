package dev.jcode.workbench

import androidx.compose.runtime.compositionLocalOf
import dev.jcode.core.distro.DebugEngineCatalogState
import dev.jcode.design.JCodeIcon
import dev.jcode.feature.marketplace.MarketplaceEntry

/** Terminal tap behavior, provided to the deeply-nested terminal view without prop-drilling. */
internal data class TerminalTapConfig(
    /** True: single tap opens a link/path, double tap shows the keyboard. False: single tap = keyboard. */
    val doubleTapToFocus: Boolean = true,
    /** Invoked with the tapped token (a URL is opened in the browser; a path in the editor). */
    val onToken: (String) -> Unit = {},
)

internal val LocalTerminalTapConfig = compositionLocalOf { TerminalTapConfig() }

/**
 * Debug-engine catalog state, provided to the giant [dev.jcode.JCodeShell] composable via a
 * CompositionLocal instead of one more param (the composable is at the ART verifier's register limit).
 */
internal val LocalDebugCatalogState = compositionLocalOf { DebugEngineCatalogState() }

/** Editor-facing debug state (breakpoints + current stopped location + toggle), provided via a
 *  CompositionLocal so the giant [dev.jcode.JCodeShell] composable stays under the register limit. */
internal data class DebugEditorState(
    val breakpoints: Map<String, Set<Int>> = emptyMap(),
    val stoppedPath: String? = null,
    val stoppedLine: Int? = null,
    val onToggleBreakpoint: (String, Int) -> Unit = { _, _ -> },
)

internal val LocalDebugEditorState = compositionLocalOf { DebugEditorState() }

/**
 * The SDK / LSP / Extension manager callbacks, bundled so the giant [dev.jcode.JCodeShell] composable
 * stays under the ART verifier's per-method register limit (too many individual params overflow it).
 */
internal data class WorkbenchManagerActions(
    val onCheckSdkStatuses: () -> Unit,
    val onInstallSdkCatalogEntry: (String) -> Unit,
    val onVerifySdkCatalogEntry: (String) -> Unit,
    val onUninstallSdkCatalogEntry: (String) -> Unit,
    val onOpenSdkDetail: (String) -> Unit,
    val onCheckLspStatuses: () -> Unit,
    val onInstallLspCatalogEntry: (String) -> Unit,
    val onVerifyLspCatalogEntry: (String) -> Unit,
    val onUninstallLspCatalogEntry: (String) -> Unit,
    val onOpenLspDetail: (String) -> Unit,
    val onCheckDebugStatuses: () -> Unit,
    val onInstallDebugEngine: (String) -> Unit,
    val onVerifyDebugEngine: (String) -> Unit,
    val onUninstallDebugEngine: (String) -> Unit,
    val onOpenDebugEngineDetail: (String) -> Unit,
    val onRefreshMarketplace: () -> Unit,
    val onInstallExtension: (MarketplaceEntry) -> Unit,
    val onUninstallExtension: (String) -> Unit,
    val onOpenExtensionDetail: (String) -> Unit,
    val onOpenExtensionPermissions: () -> Unit,
    /** Opens an extension's bundled web-frontend ("Manage"/DB-manager) screen by extension id. */
    val onOpenExtensionApp: (String) -> Unit,
    /** Runs a command in the Linux runtime for an extension web frontend; returns a JSON result. */
    val onExtensionExec: suspend (command: String, timeoutMs: Long) -> String,
)

internal enum class WorkbenchTool(
    val label: String,
    val icon: JCodeIcon,
    val compactLabel: String = label,
    /** Hidden from the activity bar until it has a working UI (kept in the enum for `when` exhaustiveness). */
    val available: Boolean = true,
) {
    Explorer("Explorer", JCodeIcon.Files, "Files"),
    Search("Search", JCodeIcon.Search, "Find", available = false),
    Scm("SCM", JCodeIcon.Scm, "SCM", available = false),
    RunDebug("Run/Debug", JCodeIcon.Run, "Run"),
    Extensions("Extensions", JCodeIcon.Extensions, "Ext"),
    SdkManager("SDK Manager", JCodeIcon.Sdk, "SDK"),
    LspManager("LSP Manager", JCodeIcon.Lsp, "LSP"),
    DebugEngineManager("Debug Engines", JCodeIcon.Debug, "DBG"),
    DbManager("DB Managers", JCodeIcon.Database, "DB"),
    Settings("Settings", JCodeIcon.Settings, "Settings"),
}

internal enum class RightPanelTab(
    val label: String,
    val icon: JCodeIcon,
    val enabled: Boolean = true,
) {
    Terminal("Terminal", JCodeIcon.Terminal, enabled = true),
    Output("Output", JCodeIcon.Logs, enabled = true),
    Problems("Problems", JCodeIcon.Problems, enabled = false),
    DebugConsole("Debug Console", JCodeIcon.Radar, enabled = false),
}

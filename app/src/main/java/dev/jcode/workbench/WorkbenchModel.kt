package dev.jcode.workbench

import androidx.compose.runtime.compositionLocalOf
import dev.jcode.design.JCodeIcon

/** Terminal tap behavior, provided to the deeply-nested terminal view without prop-drilling. */
internal data class TerminalTapConfig(
    /** True: single tap opens a link/path, double tap shows the keyboard. False: single tap = keyboard. */
    val doubleTapToFocus: Boolean = true,
    /** Invoked with the tapped token (a URL is opened in the browser; a path in the editor). */
    val onToken: (String) -> Unit = {},
)

internal val LocalTerminalTapConfig = compositionLocalOf { TerminalTapConfig() }

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
    Settings("App Settings", JCodeIcon.Settings, "App Settings"),
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

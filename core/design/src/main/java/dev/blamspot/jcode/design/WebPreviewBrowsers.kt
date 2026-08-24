package dev.blamspot.jcode.design

import androidx.compose.runtime.compositionLocalOf

/** An installed app that can open http(s) URLs. */
data class BrowserApp(val packageName: String, val label: String)

/**
 * "Open web previews in" preferences, shared (via [LocalWebPreviewBrowsers]) with the settings screen
 * (global default) and the Build & Run panel (per-project override). A choice is [SYSTEM] (the device
 * default browser), [ASK] (the Android chooser), a browser package name, or [INHERIT] (per-project only:
 * fall back to the global default). [available] is the installed-browser list for the picker.
 */
class WebPreviewBrowsers(
    val available: List<BrowserApp> = emptyList(),
    val globalChoice: String = SYSTEM,
    /** Per-project raw choice (may be [INHERIT]); keyed by a stable project key. */
    val projectChoice: (projectKey: String) -> String = { INHERIT },
    /** Key of the currently selected project, so the settings screen can scope its per-project override. */
    val currentProjectKey: String = "",
    val onSetGlobal: (String) -> Unit = {},
    val onSetProject: (projectKey: String, choice: String) -> Unit = { _, _ -> },
) {
    /** The choice actually used for [projectKey]: its override, or the global default when inheriting. */
    fun effective(projectKey: String): String =
        projectChoice(projectKey).let { if (it.isBlank() || it == INHERIT) globalChoice else it }

    /** Human label for a stored choice value. */
    fun label(choice: String): String = when (choice) {
        INHERIT -> "Use global default"
        SYSTEM -> "System default"
        ASK -> "Always ask"
        BUILTIN -> "Built-in browser"
        else -> available.firstOrNull { it.packageName == choice }?.label ?: choice
    }

    companion object {
        const val SYSTEM = "SYSTEM"
        const val ASK = "ASK"
        /** Open the preview inside JCode's own in-editor browser (with DevTools) instead of an external app. */
        const val BUILTIN = "BUILTIN"
        const val INHERIT = ""
    }
}

val LocalWebPreviewBrowsers = compositionLocalOf { WebPreviewBrowsers() }

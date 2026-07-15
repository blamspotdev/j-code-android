package dev.jcode.workbench

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf

/** A console message captured from the built-in browser's page (via WebChromeClient.onConsoleMessage),
 *  or a REPL result / error (level "eval"). */
data class BrowserConsoleEntry(
    val level: String,
    val message: String,
    val source: String = "",
    val line: Int = 0,
)

/** A network request observed by the injected `fetch`/`XMLHttpRequest` shim. [status] 0 means the
 *  request failed (or is a navigation with no captured status). */
data class BrowserNetworkEntry(
    val method: String,
    val url: String,
    val status: Int,
    val durationMs: Long,
)

/** Controls the live WebView backing the built-in browser; set by [BrowserPage] while it is on screen
 *  and null otherwise (so the DevTools panel can disable actions when the browser tab isn't open). */
interface BrowserController {
    fun navigate(url: String)
    fun goBack()
    fun goForward()
    fun reload()
    fun stop()
    /** Evaluate [script] in the page and deliver the JSON-encoded result (or "null"). Main-thread only. */
    fun eval(script: String, onResult: (String) -> Unit)
}

/**
 * Shared state for JCode's single built-in browser, observed by both [BrowserPage] (the editor-area
 * browser) and the DevTools right-drawer panel, which live in different parts of the composition. A
 * process singleton because there is at most one built-in browser tab at a time.
 */
object BuiltinBrowser {
    /** True once the browser has been opened this session — gates the DevTools drawer tab's visibility. */
    val everOpened = mutableStateOf(false)

    /** Bumped on each open request so the shell can reveal + select the DevTools drawer tab. */
    val revealSignal = mutableStateOf(0)

    /** A pending navigation for [BrowserPage] to consume (set by MainViewModel.openBrowserPage). */
    val pendingUrl = mutableStateOf<String?>(null)

    val currentUrl = mutableStateOf("")
    val title = mutableStateOf("Browser")
    val loading = mutableStateOf(false)
    val progress = mutableStateOf(0)
    val canGoBack = mutableStateOf(false)
    val canGoForward = mutableStateOf(false)

    val console = mutableStateListOf<BrowserConsoleEntry>()
    val network = mutableStateListOf<BrowserNetworkEntry>()

    /** The live page controller, or null while the browser tab is not on screen. */
    var controller: BrowserController? = null

    /** Open (or navigate) the built-in browser to [url]; also flags it for the DevTools reveal. */
    fun requestOpen(url: String) {
        everOpened.value = true
        pendingUrl.value = normalizeUrl(url)
        revealSignal.value += 1
    }

    fun addConsole(entry: BrowserConsoleEntry) {
        if (console.size >= MAX_ENTRIES) console.removeAt(0)
        console.add(entry)
    }

    fun addNetwork(entry: BrowserNetworkEntry) {
        if (network.size >= MAX_ENTRIES) network.removeAt(0)
        network.add(entry)
    }

    fun clearConsole() = console.clear()
    fun clearNetwork() = network.clear()

    /** Turn address-bar text into a loadable URL: keep known schemes; http for localhost, else https. */
    fun normalizeUrl(raw: String): String {
        val t = raw.trim()
        if (t.isEmpty()) return "about:blank"
        val lower = t.lowercase()
        if (lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("about:") || lower.startsWith("file://") || lower.startsWith("data:")
        ) {
            return t
        }
        val local = lower.startsWith("localhost") || lower.startsWith("127.0.0.1") || lower.startsWith("0.0.0.0")
        return (if (local) "http://" else "https://") + t
    }

    private const val MAX_ENTRIES = 500
}

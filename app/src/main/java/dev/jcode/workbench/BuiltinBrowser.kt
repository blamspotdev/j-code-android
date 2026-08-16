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

/**
 * One request the page made, from either of the two things a WebView will tell us about.
 *
 * **The `fetch`/`XMLHttpRequest` shim** wraps the calls the page's own code makes, so those rows are
 * complete: status, both sets of headers, the payload sent and the body that came back.
 *
 * **Resource Timing** reports everything the *browser* fetched on the page's behalf — the document,
 * scripts, stylesheets, images, fonts — which is most of what a real Network panel lists and none of
 * which passes through any JS function we could wrap. The catch is that the API is a timing API: it
 * has the URL, the kind, the duration and the transferred size, and no status code and no body,
 * because nothing in a page is allowed to read those for a resource it did not request itself. Rows
 * from this source carry [timingOnly] so the detail view can say so rather than draw empty sections.
 * Cross-origin entries additionally report [bytes] as 0 unless the server sends `Timing-Allow-Origin`.
 *
 * [status] 0 means "not known" — a failed request, or one of the timing-only rows above.
 */
data class BrowserNetworkEntry(
    val method: String,
    val url: String,
    val status: Int,
    val durationMs: Long,
    /** document | fetch | xhr | script | css | img | font | media | other — the Network filter's axis. */
    val kind: String = "other",
    /** Bytes over the wire, or -1 when unknown (see [timingOnly]). 0 is meaningful — see [encodedBytes]. */
    val bytes: Long = -1,
    /**
     * The body's own size, which is how a zero [bytes] is read: with a body size, nothing went over
     * the wire because the cache answered; without one, the response is cross-origin and declined
     * to disclose either figure. -1 when not reported at all.
     */
    val encodedBytes: Long = -1,
    val mimeType: String = "",
    val failed: Boolean = false,
    val timingOnly: Boolean = false,
    val requestHeaders: List<Pair<String, String>> = emptyList(),
    val requestBody: String = "",
    val responseHeaders: List<Pair<String, String>> = emptyList(),
    val responseBody: String = "",
    /** Set when a body hit the capture cap and what is held is a prefix. */
    val bodyTruncated: Boolean = false,
    /** Assigned by [BuiltinBrowser.addNetwork]; identifies a row when two requests are identical. */
    val id: Long = 0,
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

    /**
     * Whether the page is being asked for as a desktop would ask for it.
     *
     * Here rather than in [BrowserPage] because it has to outlive the page: the tab's WebView is
     * destroyed whenever the tab is not the one on screen, and a mode that reset itself every time
     * you looked at something else would be a mode nobody could use to compare two layouts.
     */
    val desktopMode = mutableStateOf(false)

    /**
     * The page's own icon, as the page hands it over.
     *
     * Deliberately *not* what the address bar's indicator draws: that slot says whether the
     * connection can be trusted, and a mark supplied by the site being asked about is the one thing
     * that cannot answer it. It identifies instead — in the site panel's heading, beside the host —
     * which is the job it is good at.
     */
    val favicon = mutableStateOf<android.graphics.Bitmap?>(null)

    val console = mutableStateListOf<BrowserConsoleEntry>()
    val network = mutableStateListOf<BrowserNetworkEntry>()

    /**
     * The text of whichever source the Sources pane is showing, and whether it is still coming.
     *
     * Here rather than in the pane because an external file cannot be read synchronously: the page
     * fetches it and hands it back through the `JCodeDevTools` bridge, which is a different thread
     * and a later moment than the `evaluateJavascript` that asked for it.
     */
    val sourceText = mutableStateOf<String?>(null)

    fun deliverSource(text: String) {
        sourceText.value = text
    }

    /**
     * The Application pane's last survey of the page, as the JSON the page sent back.
     *
     * Delivered through the bridge rather than returned from the eval because most of what the pane
     * asks about — the databases, the caches, the service workers, the manifest — is only reachable
     * through a Promise, and `evaluateJavascript` hands back whatever the expression evaluated to
     * synchronously, which for an async function is nothing useful.
     *
     * Held raw: the shapes inside are the pane's business, and nothing else in the app reads them.
     */
    val appDump = mutableStateOf<String?>(null)

    fun deliverAppDump(json: String) {
        appDump.value = json
    }

    /** Bumped to make the Application pane re-survey; its own menu lives outside the pane. */
    val appRefreshSignal = mutableStateOf(0)

    fun requestAppRefresh() {
        appRefreshSignal.value += 1
    }

    /** The live page controller, or null while the browser tab is not on screen. */
    var controller: BrowserController? = null

    /** Open (or navigate) the built-in browser to [url]; also flags it for the DevTools reveal. */
    fun requestOpen(url: String) {
        everOpened.value = true
        pendingUrl.value = normalizeUrl(url)
        revealSignal.value += 1
    }

    /**
     * Open (or come back to) the browser, leaving the page it is on alone.
     *
     * The difference from [requestOpen] is [pendingUrl], and it is the whole point: everything that
     * opened this browser until now arrived holding a URL — a preview, a link in a terminal — so
     * "open the browser" and "go here" were one action. Reaching it from the Command Palette is the
     * first case with nothing to navigate to, and sending it somewhere would throw away the page
     * that was already loaded.
     */
    fun requestOpen() {
        everOpened.value = true
        revealSignal.value += 1
    }

    fun addConsole(entry: BrowserConsoleEntry) {
        if (console.size >= MAX_ENTRIES) console.removeAt(0)
        console.add(entry)
    }

    fun addNetwork(entry: BrowserNetworkEntry) {
        if (network.size >= MAX_ENTRIES) network.removeAt(0)
        network.add(entry.copy(id = ++networkSeq))
    }

    fun clearConsole() = console.clear()
    fun clearNetwork() = network.clear()

    /**
     * Whether the console and network logs survive a navigation.
     *
     * Off by default, which is both Chrome's default and the only one that makes the panel readable:
     * what a page did on load is the common question, and finding it under three pages of history is
     * the common frustration. The times you want the other thing — a redirect chain, a form post that
     * navigates away, an OAuth bounce — are exactly the times you know in advance to switch it on.
     */
    val preserveLog = mutableStateOf(false)

    /** Called as each navigation commits; drops the previous page's records unless [preserveLog]. */
    fun onNavigate() {
        if (preserveLog.value) return
        console.clear()
        network.clear()
    }

    private var networkSeq = 0L

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

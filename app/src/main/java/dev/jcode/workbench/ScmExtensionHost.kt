package dev.jcode.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.design.JCodeTheme
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.webUiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Process-scoped holder for a persistent SCM WebView (mirrors [AgentChatWebViewHolder]). An SCM
 * extension that contributes `explorerDecorations` is booted here as soon as a project opens — its
 * sidebar surface computes git status and pushes Explorer decorations without the SCM panel ever
 * being shown — and the SCM panel attaches this same WebView when visible, so the panel keeps its
 * state across drawer switches. Keyed by extension id; recreated on extension update or project
 * switch (so repo detection reruns). [generation] bumps on every put/destroy so composables
 * observing the holder re-check entries.
 */
internal object ScmWebViewHolder {
    class Entry(
        val webView: WebView,
        val scope: CoroutineScope,
        val version: String?,
        val projectKey: String,
        /** The bridge closures capture this owner's ViewModel — a relaunched Activity brings a new
         *  owner, so a surviving entry must be rebuilt rather than reused with dead lambdas. */
        val owner: Any,
        /** Latest theme CSS-vars script, re-evaluated by the host on theme change and on page load. */
        @Volatile var themeJs: String = "",
    )

    private val entries = HashMap<String, Entry>()
    val generation = MutableStateFlow(0)

    fun get(id: String): Entry? = entries[id]

    fun put(id: String, entry: Entry) {
        entries[id] = entry
        generation.value++
    }

    fun ids(): List<String> = entries.keys.toList()

    fun destroy(id: String) {
        entries.remove(id)?.let { e ->
            (e.webView.parent as? ViewGroup)?.removeView(e.webView)
            e.webView.destroy()
            e.scope.cancel()
            generation.value++
        }
    }

    fun destroyAll() {
        ids().forEach { destroy(it) }
    }
}

/**
 * Invisible manager for the persistent SCM WebView: creates the holder entry for [ext] (the installed
 * SCM extension contributing `explorerDecorations`, already filtered for activation) whenever a
 * project is open, and tears it down on uninstall/update/project change. Registers the extension in
 * [liveHosts] so MainViewModel pushes `explorerAction` events instead of opening a view page.
 */
@Composable
internal fun ScmBackgroundHost(
    ext: InstalledExtension?,
    projectKey: String?,
    owner: Any,
    exec: suspend (command: String, timeoutMs: Long) -> String,
    apiRequest: suspend (ext: InstalledExtension, envelopeJson: String) -> String,
    events: SharedFlow<Pair<String, String>>?,
    liveHosts: MutableSet<String>,
    /** An entry was torn down (uninstall/update/project switch) — lets the owner drop pushed state
     *  (stale decorations) instead of showing it frozen until the next push. */
    onHostGone: () -> Unit,
) {
    val appContext = LocalContext.current.applicationContext
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JCodeTheme.semanticColors
    val backgroundArgb = colorScheme.background.toArgb()
    val themeJs = remember(colorScheme, semantic) { extensionThemeJs(colorScheme, semantic) }
    val generation by ScmWebViewHolder.generation.collectAsState()

    LaunchedEffect(ext?.id, ext?.version, projectKey, generation) {
        ScmWebViewHolder.ids().forEach { id ->
            val entry = ScmWebViewHolder.get(id) ?: return@forEach
            val stale = ext == null || id != ext.id || entry.version != ext.version ||
                entry.projectKey != projectKey || entry.owner !== owner
            if (stale) {
                ScmWebViewHolder.destroy(id)
                liveHosts.remove(id)
                onHostGone()
            }
        }
        if (ext == null || projectKey == null) return@LaunchedEffect
        if (ScmWebViewHolder.get(ext.id) == null) {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val entry = ScmWebViewHolder.Entry(
                webView = createScmWebView(appContext, ext, exec, apiRequest, events, scope, backgroundArgb, liveHosts),
                scope = scope,
                version = ext.version,
                projectKey = projectKey,
                owner = owner,
                themeJs = themeJs,
            )
            ScmWebViewHolder.put(ext.id, entry)
        }
    }

    LaunchedEffect(themeJs, ext?.id, generation) {
        val entry = ext?.id?.let(ScmWebViewHolder::get) ?: return@LaunchedEffect
        entry.themeJs = themeJs
        runCatching { entry.webView.evaluateJavascript(themeJs, null) }
    }
}

/** Attaches the persistent SCM WebView into the SCM panel without destroying it on dispose — only
 *  detaches, so status keeps updating (and panel state survives) while the drawer shows other tools.
 *  Keyed on [entry] so a rebuilt holder entry swaps its fresh WebView into the panel. */
@Composable
internal fun ScmHostWebView(entry: ScmWebViewHolder.Entry, modifier: Modifier = Modifier) {
    key(entry) {
        AndroidView(
            modifier = modifier,
            factory = {
                (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
                entry.webView
            },
        )
        DisposableEffect(entry) {
            onDispose { (entry.webView.parent as? ViewGroup)?.removeView(entry.webView) }
        }
    }
}

/** Build the persistent SCM WebView wired to the extension bridge on a caller-owned [scope], so the
 *  bridge and host-event relay keep working while the WebView is detached from the view tree. */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createScmWebView(
    context: Context,
    extension: InstalledExtension,
    exec: suspend (command: String, timeoutMs: Long) -> String,
    apiRequest: suspend (ext: InstalledExtension, envelopeJson: String) -> String,
    events: SharedFlow<Pair<String, String>>?,
    scope: CoroutineScope,
    backgroundArgb: Int,
    liveHosts: MutableSet<String>,
): WebView {
    lateinit var webView: WebView
    // Replies/events are evaluated directly on the scope's main dispatcher — NOT via View.post,
    // which on a detached view queues runnables until the view is (re)attached to a window, stalling
    // the extension's bridge exactly when this host is doing its background work.
    fun evalJs(js: String) {
        scope.launch { runCatching { webView.evaluateJavascript(js, null) } }
    }
    val bridge = ExtensionBridge(
        onExec = { reqId, command, timeoutMs ->
            scope.launch {
                val payload = runCatching { exec(command, timeoutMs) }.getOrElse { e ->
                    JSONObject().put("error", e.message ?: "exec failed").toString()
                }
                evalJs("window.JCode && window.JCode._onExec(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})")
            }
        },
        onRequest = { reqId, envelope ->
            // The first request is proof the page's JS is alive — only now may explorerAction taps
            // be pushed instead of stashed (registering at WebView creation loses taps into a page
            // that hasn't defined window.JCode yet).
            liveHosts.add(extension.id)
            scope.launch {
                val payload = runCatching { apiRequest(extension, envelope) }.getOrElse { e ->
                    JSONObject().put("ok", false).put("error", e.message ?: "request failed").toString()
                }
                evalJs(
                    "window.JCode && window.JCode._onResult && " +
                        "window.JCode._onResult(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})",
                )
            }
        },
    )
    webView = WebView(context).apply {
        setBackgroundColor(backgroundArgb)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION")
        settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        settings.allowUniversalAccessFromFileURLs = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                ScmWebViewHolder.get(extension.id)?.themeJs?.let { view.evaluateJavascript(it, null) }
            }

            // A crashed renderer must not take the app down. Drop the dead entry; the background
            // host recreates a fresh one on the generation bump.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                liveHosts.remove(extension.id)
                if (ScmWebViewHolder.get(extension.id)?.webView === view) {
                    ScmWebViewHolder.destroy(extension.id)
                } else {
                    (view.parent as? ViewGroup)?.removeView(view)
                    view.destroy()
                }
                return true
            }
        }
        // Claim touches that start in the WebView so the nav drawer's swipe-to-open can't steal a
        // scroll/drag while the panel is attached.
        setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        addJavascriptInterface(bridge, "JCodeNative")
        extension.webUiFile?.let { loadUrl("file://${it.absolutePath}") }
    }
    events?.let { flow ->
        scope.launch {
            flow.collect { (name, json) ->
                // `config`/`explorerAction` are scoped to one extension; `contextAction` targets a
                // view page at the action's route — never this surface.
                if (name == "contextAction") return@collect
                if (name == "config" || name == "explorerAction") {
                    val target = runCatching { JSONObject(json).optString("extensionId") }.getOrNull()
                    if (!target.isNullOrEmpty() && target != extension.id) return@collect
                }
                evalJs(
                    "window.JCode && window.JCode._onEvent && " +
                        "window.JCode._onEvent(${JSONObject.quote(name)}, ${JSONObject.quote(json)})",
                )
            }
        }
    }
    return webView
}

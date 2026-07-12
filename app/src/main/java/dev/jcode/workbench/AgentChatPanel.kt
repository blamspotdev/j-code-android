package dev.jcode.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.hasWebUi
import dev.jcode.feature.marketplace.webUiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Runtime access for the Chat tab, provided as a CompositionLocal (JCodeShell ART-limit convention). */
class AgentChatActions(
    val extensions: StateFlow<List<InstalledExtension>>? = null,
    val exec: suspend (command: String, timeoutMs: Long) -> String = { _, _ -> "{}" },
    val apiRequest: suspend (ext: InstalledExtension, envelopeJson: String) -> String = { _, _ -> "{}" },
    val events: SharedFlow<Pair<String, String>>? = null,
    /** Reap all long-lived runtime services (e.g. the opencode agent) on app teardown. */
    val onStopAllServices: () -> Unit = {},
    /** Whether the chat WebView should survive the panel closing (per-extension "keep running"). */
    val keepAliveFor: (extensionId: String) -> Boolean = { true },
)

val LocalAgentChatActions = compositionLocalOf { AgentChatActions() }

private const val OPENCHAMBER_EXT_ID = "jcode.ext.openchamber"

// Stable fallback so [installedAgentChatExtension]'s collectAsState is always called unconditionally.
private val EmptyAgentExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())

/** The installed agent-chat extension (OpenChamber with a web UI), or null when none is installed. */
@Composable
private fun installedAgentChatExtension(): InstalledExtension? {
    val actions = LocalAgentChatActions.current
    val extensions by (actions.extensions ?: EmptyAgentExtensions).collectAsState()
    return extensions.firstOrNull { it.id == OPENCHAMBER_EXT_ID && it.hasWebUi }
}

/** True when an agent-chat extension is installed, so the right drawer should show the Chat tab. */
@Composable
internal fun hasAgentChatExtension(): Boolean = installedAgentChatExtension() != null

/** The right-drawer chat tab's dynamic title: the installed agent extension's name (e.g. its own
 *  "OpenChamber"), or "Chat" when no agent extension with a web UI is installed. */
@Composable
internal fun agentChatTabTitle(): String = installedAgentChatExtension()?.name ?: "Chat"

/**
 * Process-scoped holder for a "keep running in background" chat WebView. The WebView (and its own
 * long-lived coroutine scope for the bridge/events) outlive the composable, so the agent session and
 * UI state survive the right drawer closing or the tab switching. Created with the application
 * context to avoid leaking the Activity while detached. Destroyed on app teardown, keep-alive
 * disable, or uninstall.
 */
internal object AgentChatWebViewHolder {
    class Entry(val webView: WebView, val scope: CoroutineScope, val version: String?)

    private val entries = HashMap<String, Entry>()

    fun get(id: String): Entry? = entries[id]
    fun put(id: String, entry: Entry) { entries[id] = entry }

    fun destroy(id: String) {
        entries.remove(id)?.let { e ->
            (e.webView.parent as? ViewGroup)?.removeView(e.webView)
            e.webView.destroy()
            e.scope.cancel()
        }
    }

    fun destroyAll() {
        entries.keys.toList().forEach { destroy(it) }
    }

    /** Post a host command (e.g. "showSettings") into every live chat WebView — how the drawer's
     *  gear opens OpenChamber's own settings view. */
    fun postCommand(command: String) {
        entries.values.forEach { e ->
            val js = "window.postMessage({type:'command',command:${JSONObject.quote(command)}}, '*')"
            e.webView.post { e.webView.evaluateJavascript(js, null) }
        }
    }
}

/**
 * Right-drawer chat tab: hosts the agent extension's web frontend (OpenChamber's static build talking
 * to a local opencode server) with the full extension bridge. When the extension's "keep running in
 * background" permission is on, the WebView persists across panel close / tab switch; otherwise it's
 * torn down like any other extension page. Installing the pieces lives in Extensions + Toolchains.
 */
@Composable
internal fun AgentChatSidebarContent(modifier: Modifier = Modifier) {
    val actions = LocalAgentChatActions.current
    val ext = installedAgentChatExtension()
    if (ext == null) {
        AgentChatPlaceholder(modifier)
        return
    }
    if (actions.keepAliveFor(ext.id)) {
        PersistentChatWebView(ext, actions, modifier)
    } else {
        key(ext.id, ext.version) {
            ExtensionWebViewPage(
                extension = ext,
                onExec = actions.exec,
                onApiRequest = { envelope -> actions.apiRequest(ext, envelope) },
                events = actions.events,
                modifier = modifier,
            )
        }
    }
}

/** Attaches the persisted WebView (reusing or creating the holder entry) without destroying it on
 *  dispose — only detaches, so the session keeps running while the panel is closed. */
@Composable
private fun PersistentChatWebView(
    extension: InstalledExtension,
    actions: AgentChatActions,
    modifier: Modifier,
) {
    val appContext = LocalContext.current.applicationContext
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val entry = remember(extension.id, extension.version) {
        val existing = AgentChatWebViewHolder.get(extension.id)
        if (existing != null && existing.version == extension.version) {
            existing
        } else {
            // Version changed (updated extension) — replace any stale WebView.
            AgentChatWebViewHolder.destroy(extension.id)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val webView = createChatWebView(appContext, extension, actions, scope, backgroundArgb)
            AgentChatWebViewHolder.Entry(webView, scope, extension.version)
                .also { AgentChatWebViewHolder.put(extension.id, it) }
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = {
            (entry.webView.parent as? ViewGroup)?.removeView(entry.webView)
            entry.webView
        },
    )
    DisposableEffect(entry) {
        onDispose { (entry.webView.parent as? ViewGroup)?.removeView(entry.webView) }
    }
}

/** Build a chat WebView wired to the extension bridge on a caller-owned [scope] (so the bridge and
 *  host-event relay keep working while the WebView is detached from the view tree). */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
private fun createChatWebView(
    context: Context,
    extension: InstalledExtension,
    actions: AgentChatActions,
    scope: CoroutineScope,
    backgroundArgb: Int,
): WebView {
    lateinit var webView: WebView
    val bridge = ExtensionBridge(
        onExec = { reqId, command, timeoutMs ->
            scope.launch {
                val payload = runCatching { actions.exec(command, timeoutMs) }.getOrElse { e ->
                    JSONObject().put("error", e.message ?: "exec failed").toString()
                }
                val js = "window.JCode && window.JCode._onExec(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})"
                webView.post { webView.evaluateJavascript(js, null) }
            }
        },
        onRequest = { reqId, envelope ->
            scope.launch {
                val payload = runCatching { actions.apiRequest(extension, envelope) }.getOrElse { e ->
                    JSONObject().put("ok", false).put("error", e.message ?: "request failed").toString()
                }
                val js = "window.JCode && window.JCode._onResult && " +
                    "window.JCode._onResult(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})"
                webView.post { webView.evaluateJavascript(js, null) }
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
        webViewClient = WebViewClient()
        setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            false
        }
        addJavascriptInterface(bridge, "JCodeNative")
        extension.webUiFile?.let { loadUrl("file://${it.absolutePath}") }
    }
    actions.events?.let { events ->
        scope.launch {
            events.collect { (name, json) ->
                // A `config` event is scoped to the extension whose setting changed — skip others.
                if (name == "config") {
                    val target = runCatching { JSONObject(json).optString("extensionId") }.getOrNull()
                    if (!target.isNullOrEmpty() && target != extension.id) return@collect
                }
                // `contextAction` is delivered only to the extension view page at the action's route
                // (or pulled on that page's boot) — never to the chat surface.
                if (name == "contextAction") return@collect
                val js = "window.JCode && window.JCode._onEvent && " +
                    "window.JCode._onEvent(${JSONObject.quote(name)}, ${JSONObject.quote(json)})"
                webView.post { webView.evaluateJavascript(js, null) }
            }
        }
    }
    return webView
}

@Composable
private fun AgentChatPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Agent chat", style = MaterialTheme.typography.titleSmall)
        Text(
            "Install the OpenChamber extension from the Extensions panel, and the opencode agent " +
                "from Tools → Toolchains (AI category). The chat UI will appear here.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

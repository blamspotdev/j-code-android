package dev.jcode.workbench

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.design.JCodeTheme
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.webUiFile
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Bridge object injected into an extension's WebView frontend as `window.JCodeNative`. The frontend
 * calls `JCodeNative.exec(reqId, command)` to run a command in the Linux runtime; the result is
 * delivered back by evaluating `window.JCode._onExec(reqId, jsonPayload)`. [onExec] runs on the
 * WebView's JS thread, so it must only hand work off (it does — to a coroutine).
 */
class ExtensionBridge(
    private val onExec: (reqId: String, command: String, timeoutMs: Long) -> Unit,
    private val onRequest: (reqId: String, envelopeJson: String) -> Unit = { _, _ -> },
) {
    @JavascriptInterface
    fun exec(reqId: String, command: String, timeoutMs: Int) = onExec(reqId, command, timeoutMs.toLong())

    /** Extension API v1: [envelopeJson] is `{"type":"family.verb","payload":{...}}`; the reply is
     *  delivered by evaluating `window.JCode._onResult(reqId, jsonString)` where the JSON is
     *  `{"ok":true,"data":{...}}` or `{"ok":false,"error":"..."}`. Same JS-thread rule as [exec]. */
    @JavascriptInterface
    fun request(reqId: String, envelopeJson: String) = onRequest(reqId, envelopeJson)
}

/**
 * Hosts an installed extension's bundled web frontend (its [InstalledExtension.webUiFile]) in a WebView,
 * wired to the runtime via [onExec] (legacy shell bridge) and [onApiRequest] (Extension API v1 envelope).
 * Host events (e.g. the focused editor file) stream in via [events] and are handed to the page as
 * `window.JCode._onEvent(name, jsonString)`. Opened as a full-screen in-editor page.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun ExtensionWebViewPage(
    extension: InstalledExtension,
    onExec: suspend (command: String, timeoutMs: Long) -> String,
    onApiRequest: suspend (envelopeJson: String) -> String,
    events: SharedFlow<Pair<String, String>>? = null,
    /** Optional view route appended to the loaded URL as `#route` so an extension can render an
     *  alternate screen (e.g. a full-page sign-in) from the same bundle. */
    route: String = "",
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var webView by remember(extension.id) { mutableStateOf<WebView?>(null) }
    val bridge = remember(extension.id) {
        ExtensionBridge(
            onExec = { reqId, command, timeoutMs ->
                scope.launch {
                    val payload = runCatching { onExec(command, timeoutMs) }.getOrElse { e ->
                        JSONObject().put("error", e.message ?: "exec failed").toString()
                    }
                    val js = "window.JCode && window.JCode._onExec(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})"
                    webView?.post { webView?.evaluateJavascript(js, null) }
                }
            },
            onRequest = { reqId, envelope ->
                scope.launch {
                    val payload = runCatching { onApiRequest(envelope) }.getOrElse { e ->
                        JSONObject().put("ok", false).put("error", e.message ?: "request failed").toString()
                    }
                    val js = "window.JCode && window.JCode._onResult && " +
                        "window.JCode._onResult(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})"
                    webView?.post { webView?.evaluateJavascript(js, null) }
                }
            },
        )
    }
    DisposableEffect(extension.id) {
        onDispose { webView?.destroy(); webView = null }
    }
    // JCode's live theme as CSS variables (--jcode-*), so extension UIs match the app. Injected on
    // page load and re-injected here whenever the theme (colors) change while the page is open.
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JCodeTheme.semanticColors
    val themeJs = remember(colorScheme, semantic) {
        fun hex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())
        val vars = listOf(
            "--jcode-background" to hex(colorScheme.background),
            "--jcode-surface" to hex(colorScheme.surface),
            "--jcode-surface-variant" to hex(colorScheme.surfaceVariant),
            "--jcode-on-surface" to hex(colorScheme.onSurface),
            "--jcode-on-surface-variant" to hex(colorScheme.onSurfaceVariant),
            "--jcode-outline" to hex(colorScheme.outline),
            "--jcode-outline-variant" to hex(colorScheme.outlineVariant),
            "--jcode-primary" to hex(colorScheme.primary),
            "--jcode-on-primary" to hex(colorScheme.onPrimary),
            "--jcode-error" to hex(colorScheme.error),
            "--jcode-success" to hex(semantic.success),
            "--jcode-warning" to hex(semantic.warning),
        )
        val sets = vars.joinToString("") { (k, v) -> "r.setProperty('$k','$v');" }
        "(function(){try{var r=document.documentElement.style;$sets}catch(e){}})()"
    }
    val themeJsState = rememberUpdatedState(themeJs)
    LaunchedEffect(themeJs) { webView?.post { webView?.evaluateJavascript(themeJs, null) } }
    // Relay host events to the page while this extension's WebView is alive. Pages that care must
    // define window.JCode._onEvent; on (re)load they should pull current state (workbench.activeFile)
    // since events published while the tab was backgrounded are not replayed.
    if (events != null) {
        LaunchedEffect(extension.id) {
            events.collect { (name, json) ->
                // A `config` event is scoped to the extension whose setting changed — skip other
                // extensions' WebViews so they don't reload their own config needlessly.
                if (name == "config") {
                    val target = runCatching { JSONObject(json).optString("extensionId") }.getOrNull()
                    if (!target.isNullOrEmpty() && target != extension.id) return@collect
                }
                val js = "window.JCode && window.JCode._onEvent && " +
                    "window.JCode._onEvent(${JSONObject.quote(name)}, ${JSONObject.quote(json)})"
                webView?.post { webView?.evaluateJavascript(js, null) }
            }
        }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.allowFileAccess = true
                // Extension pages load from file:// but talk HTTP to servers inside the local
                // runtime (opencode on 127.0.0.1); the null origin would otherwise be CORS-blocked.
                @Suppress("DEPRECATION")
                settings.allowUniversalAccessFromFileURLs = true
                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView, url: String) {
                        view.evaluateJavascript(themeJsState.value, null)
                    }
                }
                // Claim touches that start in the WebView so the nav drawer's swipe-to-open can't steal
                // a scroll/drag (otherwise scrolling the extension UI pops the left drawer). The WebView
                // still handles the gesture itself (listener returns false).
                setOnTouchListener { v, event ->
                    if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                        v.parent?.requestDisallowInterceptTouchEvent(true)
                    }
                    false
                }
                addJavascriptInterface(bridge, "JCodeNative")
                val file = extension.webUiFile
                if (file != null) {
                    loadUrl("file://${file.absolutePath}" + if (route.isNotBlank()) "#$route" else "")
                } else {
                    loadData(NO_UI_HTML, "text/html", "utf-8")
                }
                webView = this
            }
        },
    )
}

private const val NO_UI_HTML =
    "<html><body style=\"font-family:sans-serif;color:#9aa;padding:24px\">" +
        "This extension does not ship a UI.</body></html>"

package dev.jcode.workbench

import android.annotation.SuppressLint
import android.view.MotionEvent
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.webUiFile
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
) {
    @JavascriptInterface
    fun exec(reqId: String, command: String, timeoutMs: Int) = onExec(reqId, command, timeoutMs.toLong())
}

/**
 * Hosts an installed extension's bundled web frontend (its [InstalledExtension.webUiFile]) in a WebView,
 * wired to the runtime via [onExec] (a suspend that runs a command and returns a JSON payload string).
 * Opened as a full-screen in-editor page (the VM Manager's screen, SQL Client's DB-manager screen).
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun ExtensionWebViewPage(
    extension: InstalledExtension,
    onExec: suspend (command: String, timeoutMs: Long) -> String,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    var webView by remember(extension.id) { mutableStateOf<WebView?>(null) }
    val bridge = remember(extension.id) {
        ExtensionBridge(onExec = { reqId, command, timeoutMs ->
            scope.launch {
                val payload = runCatching { onExec(command, timeoutMs) }.getOrElse { e ->
                    JSONObject().put("error", e.message ?: "exec failed").toString()
                }
                val js = "window.JCode && window.JCode._onExec(${JSONObject.quote(reqId)}, ${JSONObject.quote(payload)})"
                webView?.post { webView?.evaluateJavascript(js, null) }
            }
        })
    }
    DisposableEffect(extension.id) {
        onDispose { webView?.destroy(); webView = null }
    }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.allowFileAccess = true
                webViewClient = WebViewClient()
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
                    loadUrl("file://${file.absolutePath}")
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

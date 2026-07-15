package dev.jcode.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.json.JSONObject

/** Keeps the soft keyboard out of the IME's fullscreen "extract" mode so a focused input inside the
 *  page isn't covered (same rationale as the extension WebView host). */
private class BrowserWebView(context: Context) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return ic
    }
}

/** Bridge for the injected fetch/XHR network shim: the page reports each request as JSON here. */
private class DevToolsBridge {
    private val main = Handler(Looper.getMainLooper())

    @JavascriptInterface
    fun net(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val entry = BrowserNetworkEntry(
            method = o.optString("method", "GET"),
            url = o.optString("url"),
            status = o.optInt("status", 0),
            durationMs = o.optLong("ms", 0),
        )
        main.post { BuiltinBrowser.addNetwork(entry) }
    }
}

/**
 * JCode's built-in browser, shown as a full-screen editor page: address bar + back/forward/reload,
 * and it feeds the DevTools drawer panel — console messages (via WebChromeClient) and network requests
 * (via an injected `fetch`/`XMLHttpRequest` shim). State lives in [BuiltinBrowser] so DevTools, which
 * sits in a different part of the tree, can observe it and drive JS eval / DOM snapshots.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserPage(modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    var webView by remember { mutableStateOf<WebView?>(null) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(BuiltinBrowser.currentUrl.value) }
    // Follow the page URL in the address bar, except while the user is editing it.
    LaunchedEffect(BuiltinBrowser.currentUrl.value) {
        if (!editing) address = BuiltinBrowser.currentUrl.value
    }
    // Drive navigations requested from elsewhere (openBrowserPage / previews) once the WebView exists.
    LaunchedEffect(webView) {
        val wv = webView ?: return@LaunchedEffect
        snapshotFlow { BuiltinBrowser.pendingUrl.value }.collect { url ->
            if (url != null) {
                wv.loadUrl(url)
                BuiltinBrowser.currentUrl.value = url
                BuiltinBrowser.pendingUrl.value = null
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            BuiltinBrowser.controller = null
            webView?.destroy()
            webView = null
        }
    }

    fun go(raw: String) {
        editing = false
        focus.clearFocus()
        val url = BuiltinBrowser.normalizeUrl(raw)
        webView?.loadUrl(url)
        BuiltinBrowser.currentUrl.value = url
    }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            IconButton(onClick = { webView?.goBack() }, enabled = BuiltinBrowser.canGoBack.value) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { webView?.goForward() }, enabled = BuiltinBrowser.canGoForward.value) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { if (BuiltinBrowser.loading.value) webView?.stopLoading() else webView?.reload() }) {
                Icon(
                    if (BuiltinBrowser.loading.value) Icons.Rounded.Close else Icons.Rounded.Refresh,
                    contentDescription = if (BuiltinBrowser.loading.value) "Stop" else "Reload",
                    modifier = Modifier.size(18.dp),
                )
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                BasicTextField(
                    value = address,
                    onValueChange = { address = it; editing = true },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = MaterialTheme.typography.bodySmall.fontSize,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { go(address) }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                )
            }
        }
        // Thin determinate progress bar while loading (avoids Material3 API-version differences).
        Box(modifier = Modifier.fillMaxWidth().height(2.dp)) {
            if (BuiltinBrowser.loading.value) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(BuiltinBrowser.progress.value.coerceIn(2, 100) / 100f)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary),
                )
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val wv = BrowserWebView(ctx)
                    wv.setBackgroundColor(Color.White.toArgb())
                    wv.settings.javaScriptEnabled = true
                    wv.settings.domStorageEnabled = true
                    // BuiltinBrowser.normalizeUrl accepts file:// URLs; the WebView default
                    // flipped to no-file-access at targetSdk 30.
                    wv.settings.allowFileAccess = true
                    wv.settings.useWideViewPort = true
                    wv.settings.loadWithOverviewMode = true
                    wv.settings.builtInZoomControls = true
                    wv.settings.displayZoomControls = false
                    wv.settings.mediaPlaybackRequiresUserGesture = false
                    wv.addJavascriptInterface(DevToolsBridge(), "JCodeDevTools")
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            BuiltinBrowser.loading.value = true
                            BuiltinBrowser.currentUrl.value = url
                            view.evaluateJavascript(NET_SHIM_JS, null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            BuiltinBrowser.loading.value = false
                            BuiltinBrowser.currentUrl.value = url
                            BuiltinBrowser.canGoBack.value = view.canGoBack()
                            BuiltinBrowser.canGoForward.value = view.canGoForward()
                            view.evaluateJavascript(NET_SHIM_JS, null)
                        }
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                            BuiltinBrowser.canGoBack.value = view.canGoBack()
                            BuiltinBrowser.canGoForward.value = view.canGoForward()
                        }
                    }
                    wv.webChromeClient = object : WebChromeClient() {
                        override fun onProgressChanged(view: WebView, newProgress: Int) {
                            BuiltinBrowser.progress.value = newProgress
                        }
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            BuiltinBrowser.title.value = title?.ifBlank { "Browser" } ?: "Browser"
                        }
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            BuiltinBrowser.addConsole(
                                BrowserConsoleEntry(
                                    level = msg.messageLevel().name.lowercase(),
                                    message = msg.message() ?: "",
                                    source = msg.sourceId()?.substringAfterLast('/') ?: "",
                                    line = msg.lineNumber(),
                                ),
                            )
                            return true
                        }
                    }
                    BuiltinBrowser.controller = object : BrowserController {
                        override fun navigate(url: String) = wv.loadUrl(BuiltinBrowser.normalizeUrl(url))
                        override fun goBack() { if (wv.canGoBack()) wv.goBack() }
                        override fun goForward() { if (wv.canGoForward()) wv.goForward() }
                        override fun reload() = wv.reload()
                        override fun stop() = wv.stopLoading()
                        override fun eval(script: String, onResult: (String) -> Unit) =
                            wv.evaluateJavascript(script) { onResult(it ?: "null") }
                    }
                    // Restore the last page when the tab is re-composed; the pending-URL effect drives
                    // brand-new navigations requested while the tab wasn't on screen.
                    wv.loadUrl(BuiltinBrowser.currentUrl.value.ifBlank { "about:blank" })
                    webView = wv
                    wv
                },
            )
        }
    }
}

/** Monkeypatches `fetch` and `XMLHttpRequest` to report method/url/status/timing to the DevTools
 *  Network panel via the JCodeDevTools bridge. Idempotent (guarded), injected on each page load. */
private const val NET_SHIM_JS = """
(function(){
  if (window.__jcodeNetHooked) return; window.__jcodeNetHooked = true;
  function rep(m,u,s,t){ try{ JCodeDevTools.net(JSON.stringify({method:m,url:String(u),status:s,ms:Math.round(performance.now()-t)})); }catch(e){} }
  var of = window.fetch;
  if (of) { window.fetch = function(){ var a=arguments, t=performance.now();
    var u=(a[0]&&a[0].url)||a[0], m=(a[1]&&a[1].method)||'GET';
    return of.apply(this,a).then(function(r){ rep(m,u,r.status,t); return r; })
                           .catch(function(e){ rep(m,u,0,t); throw e; }); }; }
  var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.send;
  XMLHttpRequest.prototype.open=function(m,u){ this.__m=m; this.__u=u; return xo.apply(this,arguments); };
  XMLHttpRequest.prototype.send=function(){ var s=this, t=performance.now();
    s.addEventListener('loadend', function(){ rep(s.__m||'GET', s.__u, s.status, t); });
    return xs.apply(this,arguments); };
})();
"""

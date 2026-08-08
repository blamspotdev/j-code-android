package dev.jcode.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
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
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import dev.jcode.design.JCodeTheme
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.webUiFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Bridge object injected into an extension's WebView frontend as `window.JCodeNative`. The frontend
 * calls `JCodeNative.exec(reqId, command)` to run a command in the Linux runtime; the result is
 * delivered back by evaluating `window.JCode._onExec(reqId, jsonPayload)`. [onExec] runs on the
 * WebView's JS thread, so it must only hand work off (it does — to a coroutine).
 */
/**
 * WebView that keeps the soft keyboard out of fullscreen "extract" mode. Without this, a focused
 * input inside the extension UI triggers the IME's landscape fullscreen editor, which covers the
 * whole WebView (the field being edited becomes invisible behind the keyboard). With these flags the
 * keyboard stays a normal bottom overlay; since the activity is `adjustResize`, the WebView shrinks
 * and the page's centered modal re-centers above the keyboard.
 */
/** Host dir that ProotManager bind-mounts into every runtime as `/jcode-transfer`; the `file.import`
 *  bridge stream-copies SAF-picked files here so extensions can reach them by a runtime path. */
private fun transferHostDir(context: Context): File =
    dev.jcode.core.distro.WorkspaceHostPaths.transferRoot(context.filesDir)

private class NoFullscreenWebView(context: Context) : WebView(context) {
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val ic = super.onCreateInputConnection(outAttrs)
        outAttrs.imeOptions = outAttrs.imeOptions or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI
        return ic
    }
}

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
    /** Spawns a long-lived process in the Linux runtime, used to run an imported `.vsix`. */
    spawnProcess: ((command: String) -> Process?)? = null,
    modifier: Modifier = Modifier,
) {
    // A .vsix has no page on disk to point at — its UI is built by the extension's own code — so it
    // takes a different route entirely.
    val vsixEntry = remember(extension.id) {
        File(extension.dir, dev.jcode.feature.marketplace.VsixPackage.VSIX_MARKER)
            .takeIf { it.isFile }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
    }
    if (vsixEntry != null) {
        if (spawnProcess == null) {
            ExtensionNotice("${extension.name} needs the Linux runtime to run, and it isn't available here.", modifier)
        } else {
            VsixExtensionWebView(extension, spawnProcess, modifier)
        }
        return
    }

    val scope = rememberCoroutineScope()
    var webView by remember(extension.id) { mutableStateOf<WebView?>(null) }
    // SAF file picking for extension `<input type="file">` (e.g. the SQL Client "restore from .bak"
    // flow). onShowFileChooser stashes the WebView's callback here; the GetContent launcher (which
    // opens the Android Storage Access Framework picker) delivers the chosen content:// URI back to it.
    val pendingFilePick = remember(extension.id) { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        pendingFilePick.value?.onReceiveValue(if (uri != null) arrayOf(uri) else null)
        pendingFilePick.value = null
    }
    // Native "import a file into the runtime" bridge (the `file.import` API request): the SAF picker
    // returns a content:// URI, which we stream-copy into a host dir that is bind-mounted into the proot
    // (transferHostDir -> /jcode-transfer). The extension then gets a runtime path it can hand to
    // scp/RESTORE — no base64, so it scales to multi-hundred-MB backups. Reply is the usual _onResult.
    val context = LocalContext.current
    val pendingImport = remember(extension.id) { mutableStateOf<String?>(null) }
    val importPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val reqId = pendingImport.value ?: return@rememberLauncherForActivityResult
        pendingImport.value = null
        scope.launch {
            val json = runCatching {
                if (uri == null) throw IllegalStateException("cancelled")
                val cr = context.contentResolver
                var name = "import.bin"
                cr.query(uri, null, null, null, null)?.use { cur ->
                    val idx = cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cur.moveToFirst()) cur.getString(idx)?.let { name = it }
                }
                val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "import.bin" }
                val size = withContext(Dispatchers.IO) {
                    val destDir = transferHostDir(context).apply { mkdirs() }
                    val dest = File(destDir, safe)
                    var copied = 0L
                    cr.openInputStream(uri).use { input ->
                        requireNotNull(input) { "cannot open the selected file" }
                        FileOutputStream(dest).use { output ->
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                output.write(buf, 0, n); copied += n
                            }
                        }
                    }
                    copied
                }
                JSONObject().put("ok", true).put(
                    "data", JSONObject().put("path", "/jcode-transfer/$safe").put("name", name).put("size", size),
                ).toString()
            }.getOrElse { e ->
                JSONObject().put("ok", false).put("error", e.message ?: "import failed").toString()
            }
            val js = "window.JCode && window.JCode._onResult && " +
                "window.JCode._onResult(${JSONObject.quote(reqId)}, ${JSONObject.quote(json)})"
            webView?.post { webView?.evaluateJavascript(js, null) }
        }
    }
    // Native "export a runtime file to device storage" bridge (the `file.export` API request): the ext
    // writes a file into /jcode-transfer (transferHostDir) — e.g. a pg_dump/.bak backup — then asks
    // to save it out; the SAF "create document" picker lets the user choose the destination and we
    // stream-copy the host file there. The mirror of `file.import`.
    val pendingExport = remember(extension.id) { mutableStateOf<Pair<String, File>?>(null) }
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val (reqId, src) = pendingExport.value ?: return@rememberLauncherForActivityResult
        pendingExport.value = null
        scope.launch {
            val json = runCatching {
                if (uri == null) throw IllegalStateException("cancelled")
                if (!src.exists()) throw IllegalStateException("backup file not found")
                val size = withContext(Dispatchers.IO) {
                    var copied = 0L
                    FileInputStream(src).use { input ->
                        context.contentResolver.openOutputStream(uri).use { output ->
                            requireNotNull(output) { "cannot open the destination" }
                            val buf = ByteArray(1 shl 20)
                            while (true) {
                                val n = input.read(buf); if (n < 0) break
                                output.write(buf, 0, n); copied += n
                            }
                        }
                    }
                    copied
                }
                JSONObject().put("ok", true).put("data", JSONObject().put("size", size)).toString()
            }.getOrElse { e ->
                JSONObject().put("ok", false).put("error", e.message ?: "export failed").toString()
            }
            val js = "window.JCode && window.JCode._onResult && " +
                "window.JCode._onResult(${JSONObject.quote(reqId)}, ${JSONObject.quote(json)})"
            webView?.post { webView?.evaluateJavascript(js, null) }
        }
    }
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
                val type = runCatching { JSONObject(envelope).optString("type") }.getOrNull()
                if (type == "file.import") {
                    // Native SAF import — handled here since the launcher lives in this composable; the
                    // reply is delivered later by importPicker's callback via _onResult.
                    pendingImport.value = reqId
                    webView?.post { runCatching { importPicker.launch("*/*") } }
                } else if (type == "file.export") {
                    // Native SAF export — save a /jcode-transfer file out to a user-chosen device location.
                    val payload = runCatching { JSONObject(envelope).optJSONObject("payload") }.getOrNull()
                    val basename = (payload?.optString("path") ?: "").substringAfterLast('/')
                    val name = payload?.optString("name")?.ifBlank { null } ?: basename.ifBlank { "backup.bin" }
                    pendingExport.value = reqId to File(transferHostDir(context), basename)
                    webView?.post { runCatching { exportPicker.launch(name) } }
                } else scope.launch {
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
    // Stop the WebView re-rendering while the app is backgrounded — the user isn't looking, so drawing
    // off-screen just burns GPU/battery. onPause() halts rendering/animations but NOT JavaScript, so any
    // allowed background work keeps running; onResume() re-renders on-demand when the app returns.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, webView) {
        val wv = webView
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_STOP -> wv?.onPause()
                Lifecycle.Event.ON_START -> wv?.onResume()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // JCode's live theme as CSS variables (--jcode-*), so extension UIs match the app. Injected on
    // page load and re-injected here whenever the theme (colors) change while the page is open.
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JCodeTheme.semanticColors
    // Paint the WebView backdrop with the theme background from creation, so there is no flash of the
    // WebView's default white before the page's CSS loads and the --jcode-* vars are injected.
    val backgroundArgb = colorScheme.background.toArgb()
    val themeJs = remember(colorScheme, semantic) { extensionThemeJs(colorScheme, semantic) }
    val themeJsState = rememberUpdatedState(themeJs)
    LaunchedEffect(themeJs, backgroundArgb) {
        webView?.post { webView?.setBackgroundColor(backgroundArgb); webView?.evaluateJavascript(themeJs, null) }
    }
    // Relay host events to the page while this extension's WebView is alive. Pages that care must
    // define window.JCode._onEvent; on (re)load they should pull current state (workbench.activeFile)
    // since events published while the tab was backgrounded are not replayed.
    if (events != null) {
        LaunchedEffect(extension.id) {
            events.collect { (name, json) ->
                // A `reload` after an update re-fetches THIS extension's updated on-disk page (a plain
                // update otherwise leaves the open tab running the old bundle). Handled natively — never
                // forwarded to the page's JS bridge.
                if (name == "reload") {
                    val target = runCatching { JSONObject(json).optString("extensionId") }.getOrNull()
                    if (target.isNullOrEmpty() || target == extension.id) {
                        webView?.post { webView?.reload() }
                    }
                    return@collect
                }
                // `config` / `contextAction` events are scoped to one extension — skip other
                // extensions' WebViews so they don't react to traffic that isn't theirs. A
                // `contextAction` is further targeted at the view showing the action's route, so a
                // tap is handled exactly once (drawer embeds and other views never see it).
                // `explorerAction` is pushed only to an extension's persistent background host.
                if (name == "explorerAction") return@collect
                if (name == "config" || name == "contextAction") {
                    val o = runCatching { JSONObject(json) }.getOrNull()
                    val target = o?.optString("extensionId")
                    if (!target.isNullOrEmpty() && target != extension.id) return@collect
                    if (name == "contextAction" && o?.optString("actionId") != route) return@collect
                }
                if (ExtensionDevLog.isDev(extension.id)) {
                    ExtensionDevLog.log(ExtensionDevLogEntry.Kind.Event, extension.id, "$name $json")
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
            NoFullscreenWebView(ctx).apply {
                setBackgroundColor(backgroundArgb)
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

                    // A crashed renderer must not take the app down (the default). Drop the dead
                    // view; the user reopens the page to get a fresh one.
                    override fun onRenderProcessGone(
                        view: WebView,
                        detail: android.webkit.RenderProcessGoneDetail,
                    ): Boolean {
                        (view.parent as? android.view.ViewGroup)?.removeView(view)
                        view.destroy()
                        if (webView === view) webView = null
                        return true
                    }
                }
                // Route `<input type="file">` to the SAF picker so extensions can select a file from
                // device storage (e.g. a .bak to restore). Without a WebChromeClient the input is inert.
                webChromeClient = object : WebChromeClient() {
                    // Surface the extension web UI's console in the Extension Dev tools (dev extensions only).
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                        if (ExtensionDevLog.isDev(extension.id)) {
                            val level = msg.messageLevel().name.lowercase()
                            val src = msg.sourceId()?.substringAfterLast('/').orEmpty()
                            val loc = if (msg.lineNumber() > 0) "  ($src:${msg.lineNumber()})" else ""
                            ExtensionDevLog.log(
                                if (level == "error") ExtensionDevLogEntry.Kind.Error else ExtensionDevLogEntry.Kind.Console,
                                extension.id, "[$level] ${msg.message().orEmpty()}$loc",
                            )
                        }
                        // Don't claim the message — let the WebView's default logcat forwarding run
                        // too (important for signed extensions we don't record).
                        return false
                    }

                    override fun onShowFileChooser(
                        wv: WebView?,
                        filePathCallback: ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?,
                    ): Boolean {
                        pendingFilePick.value?.onReceiveValue(null)
                        pendingFilePick.value = filePathCallback
                        return try {
                            filePicker.launch("*/*"); true
                        } catch (e: Exception) {
                            pendingFilePick.value = null; false
                        }
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

/** Origin the extension's own files are served from, matching `webview.cspSource` in the host. */
private const val VSIX_RESOURCE_ORIGIN = "https://jcode.webview"

/**
 * Renders an imported `.vsix`.
 *
 * Nothing can be shown until the extension has run: its HTML is produced by its own code. So the
 * host is started, the extension activated, and the first webview view it registered resolved —
 * whatever HTML comes back is what gets loaded. Requests to [VSIX_RESOURCE_ORIGIN] are served
 * straight from the install directory, which is how the extension's scripts and styles resolve
 * without the page ever learning where on disk it actually lives.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
private fun VsixExtensionWebView(
    extension: InstalledExtension,
    spawnProcess: (command: String) -> Process?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember(extension.id) { mutableStateOf("Starting ${extension.name}…") }
    var failure by remember(extension.id) { mutableStateOf<String?>(null) }
    var html by remember(extension.id) { mutableStateOf<String?>(null) }
    var viewHandle by remember(extension.id) { mutableStateOf<String?>(null) }
    var host by remember(extension.id) { mutableStateOf<VsCodeExtensionHost?>(null) }
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f

    DisposableEffect(extension.id) {
        onDispose { host?.dispose() }
    }

    LaunchedEffect(extension.id) {
        val started = VsCodeExtensionHost(context, extension, spawnProcess) { method, params ->
            when (method) {
                "host/log" -> ExtensionDevLog.log(
                    if (params.optString("level") == "error") ExtensionDevLogEntry.Kind.Error
                    else ExtensionDevLogEntry.Kind.Console,
                    extension.id,
                    "[host] ${params.optString("text")}",
                )
                // The extension re-rendering its view is normal: it sets html whenever its state changes.
                "webview/html" -> html = params.optString("html")
                else -> ExtensionDevLog.log(
                    ExtensionDevLogEntry.Kind.Event, extension.id, "$method ${params}",
                )
            }
        }
        host = started

        started.start()?.let { failure = it; return@LaunchedEffect }
        status = "Loading ${extension.name}…"

        val activated = started.activate(
            folders = listOf("workspace" to "/workspace"),
            configuration = JSONObject(),
        )
        // Tell the extension which theme it is being shown in before it builds its view, so it
        // styles itself correctly the first time rather than after a repaint.
        runCatching { started.setTheme(dark = isDarkTheme) }
        activated.optString("error").takeIf { it.isNotBlank() }?.let { failure = it; return@LaunchedEffect }

        val views = activated.optJSONArray("views")
        val viewId = (0 until (views?.length() ?: 0)).firstNotNullOfOrNull { views?.optString(it) }
        if (viewId.isNullOrBlank()) {
            failure = "${extension.name} registered no view to show."
            return@LaunchedEffect
        }
        val resolved = started.resolveWebviewView(viewId)
        resolved.optString("error").takeIf { it.isNotBlank() }?.let { failure = it; return@LaunchedEffect }
        viewHandle = resolved.optString("handle")
        html = resolved.optString("html")
    }

    val current = failure
    if (current != null) {
        ExtensionNotice(current, modifier)
        return
    }
    val page = html
    if (page.isNullOrBlank()) {
        ExtensionNotice(status, modifier)
        return
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            NoFullscreenWebView(ctx).apply {
                setBackgroundColor(backgroundArgb)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                @Suppress("DEPRECATION")
                settings.allowFileAccess = true
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView,
                        request: android.webkit.WebResourceRequest,
                    ): android.webkit.WebResourceResponse? = serveExtensionResource(extension.dir, request.url)
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: android.webkit.ConsoleMessage): Boolean {
                        ExtensionDevLog.log(
                            if (msg.messageLevel().name.lowercase() == "error") ExtensionDevLogEntry.Kind.Error
                            else ExtensionDevLogEntry.Kind.Console,
                            extension.id,
                            "[${msg.messageLevel().name.lowercase()}] ${msg.message().orEmpty()}",
                        )
                        return false
                    }
                }
                addJavascriptInterface(
                    object {
                        @JavascriptInterface
                        fun postMessage(payload: String) {
                            val handle = viewHandle ?: return
                            scope.launch { host?.postToWebview(handle, payload) }
                        }
                    },
                    "JCodeVsix",
                )
                loadDataWithBaseURL("$VSIX_RESOURCE_ORIGIN/", VSIX_BOOTSTRAP + page, "text/html", "utf-8", null)
            }
        },
        update = { view ->
            // The extension replaces its HTML whenever its own state changes; reload rather than
            // trying to reconcile a document we did not author.
            val stamp = page.hashCode()
            if (view.tag != stamp) {
                view.tag = stamp
                view.loadDataWithBaseURL("$VSIX_RESOURCE_ORIGIN/", VSIX_BOOTSTRAP + page, "text/html", "utf-8", null)
            }
        },
    )
}

/**
 * What every VS Code webview expects to find waiting for it: `acquireVsCodeApi()`, and a document
 * with a height.
 *
 * The height is not a given here. On the WebView builds JCode ships to, the document lays out
 * against a zero-height viewport, so `html` computes to 0 and an extension whose root is
 * `height: 100%` — which is most of them — fills nothing and renders blank. The real size is only
 * available in JS, so it is applied from there as pixels and republished as a variable for anything
 * sizing itself in viewport units.
 */
private val VSIX_BOOTSTRAP = """
<style>html,body{margin:0;padding:0;overflow:hidden}</style>
<script>
(function () {
  var applySize = function () {
    var root = document.documentElement;
    var height = window.innerHeight + 'px';
    root.style.setProperty('--jcode-viewport-height', height);
    root.style.setProperty('--jcode-viewport-width', window.innerWidth + 'px');
    root.style.height = height;
    if (document.body) document.body.style.height = height;
  };
  applySize();
  window.addEventListener('resize', applySize);
  document.addEventListener('DOMContentLoaded', applySize);
})();
(function () {
  var state = {};
  var listeners = [];
  window.acquireVsCodeApi = function () {
    return {
      postMessage: function (message) { window.JCodeVsix.postMessage(JSON.stringify(message)); },
      getState: function () { return state; },
      setState: function (next) { state = next; return next; },
    };
  };
  // The host delivers extension -> page messages through here.
  window.__jcodeDeliver = function (json) {
    var data;
    try { data = JSON.parse(json); } catch (e) { data = json; }
    window.dispatchEvent(new MessageEvent('message', { data: data }));
  };
})();
</script>
""".trimIndent()

/** Serve a file from the extension's install directory for [VSIX_RESOURCE_ORIGIN] requests. */
private fun serveExtensionResource(extensionDir: File, url: Uri): android.webkit.WebResourceResponse? {
    if (!url.toString().startsWith("$VSIX_RESOURCE_ORIGIN/")) return null
    val relative = url.path?.trimStart('/').orEmpty()
    if (relative.isEmpty()) return null
    val file = File(extensionDir, relative)
    val root = extensionDir.canonicalPath + File.separator
    // Never serve outside the extension, whatever the page asks for.
    if (!file.canonicalPath.startsWith(root) || !file.isFile) return null
    val mime = when (file.extension.lowercase()) {
        "js", "mjs", "cjs" -> "text/javascript"
        "css" -> "text/css"
        "html" -> "text/html"
        "json", "map" -> "application/json"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        else -> "application/octet-stream"
    }
    return android.webkit.WebResourceResponse(mime, "utf-8", file.inputStream())
}

/** Centred one-line message for a page that cannot be shown (yet). */
@Composable
private fun ExtensionNotice(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

private const val NO_UI_HTML =
    "<html><body style=\"font-family:sans-serif;color:#9aa;padding:24px\">" +
        "This extension does not ship a UI.</body></html>"

/** JCode's live theme as CSS variables (--jcode-*), injected into extension pages so they match the app. */
internal fun extensionThemeJs(
    colorScheme: androidx.compose.material3.ColorScheme,
    semantic: dev.jcode.design.JCodeSemanticColors,
): String {
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
    return "(function(){try{var r=document.documentElement.style;$sets}catch(e){}})()"
}

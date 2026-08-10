package dev.jcode.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.FrameLayout
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
import androidx.compose.runtime.mutableStateMapOf
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
import dev.jcode.feature.marketplace.VsixCommand
import dev.jcode.feature.marketplace.VsixPackage
import dev.jcode.feature.marketplace.webUiFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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

    /**
     * Ctrl+V with an image on the clipboard: Chromium's own paste carries text only, so the image
     * would silently paste as nothing. Handled here and delivered as a real `paste` event; a
     * clipboard holding anything else falls through to normal handling untouched.
     */
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean {
        val isPasteChord = event.action == android.view.KeyEvent.ACTION_DOWN &&
            event.keyCode == android.view.KeyEvent.KEYCODE_V &&
            event.isCtrlPressed
        if (isPasteChord && pasteClipboardImage()) return true
        return super.dispatchKeyEvent(event)
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
    /** Surface a webview panel an imported `.vsix` created as an editor tab. */
    onOpenPanel: (handle: String, title: String) -> Unit = { _, _ -> },
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
            VsixExtensionView(extension, spawnProcess, onApiRequest, onOpenPanel, modifier)
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
                        view.evaluateJavascript(VIEWPORT_SIZE_JS, null)
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
 * A running `.vsix`: its extension host, a WebView per webview the extension has open, and the state
 * whoever is displaying them reads.
 *
 * Deliberately not composition-scoped. A VS Code extension is a process that takes seconds to come
 * up — OpenChamber launches `opencode` and waits for it — so tearing it down because a drawer closed
 * or a tab lost focus would restart it every time. The session outlives its mount points; only
 * [dispose] ends it. WebViews are built with the application context so a detached session cannot
 * hold an Activity.
 *
 * An extension has more than one surface: the view it contributes to the drawer, plus any panel it
 * opens with `createWebviewPanel` (OpenChamber's "Open Session in Editor" and its Agent Manager).
 * Every message the host sends names the webview it belongs to, so surfaces are kept per handle —
 * routing them all to one WebView let a panel overwrite the drawer.
 */
internal class VsixSession private constructor(
    val extension: InstalledExtension,
    val version: String?,
    val host: VsCodeExtensionHost,
    private val scope: CoroutineScope,
    private val context: Context,
    private val backgroundArgb: Int,
    private val onOpenPanel: (handle: String, title: String) -> Unit,
) {
    /** What to show while there is no page yet, and why if there never will be. */
    var status by mutableStateOf("Starting ${extension.name}…")
        private set
    var failure by mutableStateOf<String?>(null)
        private set

    /** One webview the extension has open, mounted wherever it belongs. */
    inner class Surface(val handle: String, val webView: WebView) {
        var hasPage by mutableStateOf(false)
            internal set
        internal var loadedStamp: Int? = null
    }

    private val surfaces = mutableStateMapOf<String, Surface>()

    /** The handle of the view the extension contributed, known once it has been resolved. */
    private var viewHandle by mutableStateOf<String?>(null)
    private var projectName: String? = null
    private var projectPath: String? = null

    /** The drawer's surface: the view this extension contributes. */
    val viewSurface: Surface? get() = viewHandle?.let { surfaces[it] }

    /** An editor panel's surface, or null if the extension has not created (or has closed) it. */
    fun panel(handle: String): Surface? = surfaces[handle]

    fun execute(commandId: String) {
        scope.launch { host.executeCommand(commandId) }
    }

    /**
     * Delivers a clipboard image to the surface the user is working in as a `paste` event; false when
     * the clipboard holds no image. Prefers the focused WebView — an extension can have both a drawer
     * view and editor panels open, and the image belongs to whichever one has the caret.
     */
    fun pasteClipboardImage(): Boolean {
        val focused = surfaces.values.firstOrNull { it.webView.hasFocus() }
        return (focused ?: viewSurface)?.webView?.pasteClipboardImage() ?: false
    }

    fun dispose() {
        host.dispose()
        surfaces.values.forEach { surface ->
            (surface.webView.parent as? ViewGroup)?.removeView(surface.webView)
            surface.webView.destroy()
        }
        surfaces.clear()
        scope.cancel()
    }

    private fun onHostEvent(method: String, params: JSONObject) {
        when (method) {
            "host/log" -> ExtensionDevLog.log(
                if (params.optString("level") == "error") ExtensionDevLogEntry.Kind.Error
                else ExtensionDevLogEntry.Kind.Console,
                extension.id,
                "[host] ${params.optString("text")}",
            )
            // The extension re-rendering a webview is normal: it sends this whenever its state changes.
            "webview/html" -> render(params.optString("handle"), params.optString("html"))
            // `createWebviewPanel` — the extension wants a surface of its own in the editor area. This
            // is how "Open Session in Editor" and the Agent Manager reach the main screen.
            "webview/panelCreated" -> {
                val handle = params.optString("handle").takeIf { it.isNotBlank() } ?: return
                val title = params.optString("title").ifBlank { extension.name }
                scope.launch {
                    surfaceFor(handle)
                    rememberPanelTitle(handle, title)
                    onOpenPanel(handle, title)
                }
            }
            // `panel.reveal()` — bring it back to front. Reopening covers the case where the user
            // closed the tab: the extension still holds the panel and only ever reveals it.
            "webview/reveal" -> {
                val handle = params.optString("handle").takeIf { it.isNotBlank() } ?: return
                if (handle != viewHandle) {
                    scope.launch { onOpenPanel(handle, panelTitles[handle] ?: extension.name) }
                }
            }
            "webview/disposed" -> {
                val handle = params.optString("handle").takeIf { it.isNotBlank() } ?: return
                scope.launch { closeSurface(handle) }
            }
            // The extension talking to one of its pages. This is a request, not a notification: the
            // extension waits on it, so a missing reply strands whatever it was doing — which is how
            // a page ends up sitting on its splash forever.
            "webview/postMessage" -> {
                val payload = params.opt("message")
                val handle = params.optString("handle")
                ExtensionDevLog.log(
                    ExtensionDevLogEntry.Kind.Event,
                    extension.id,
                    "ext → page[$handle] ${payload?.toString()?.take(200)}",
                )
                val json = JSONObject.quote(
                    when (payload) {
                        null, JSONObject.NULL -> "null"
                        else -> payload.toString()
                    },
                )
                scope.launch {
                    surfaces[handle]?.webView
                        ?.evaluateJavascript("window.__jcodeDeliver && window.__jcodeDeliver($json)", null)
                }
                host.reply(params.optInt("__requestId"), null)
            }
            else -> {
                ExtensionDevLog.log(ExtensionDevLogEntry.Kind.Event, extension.id, "$method $params")
                // Anything else the extension asks for is not implemented yet, but it must still be
                // answered — an unanswered request blocks the extension rather than degrading it.
                if (params.has("__requestId")) {
                    host.reply(params.optInt("__requestId"), null, "$method is not implemented by JCode")
                }
            }
        }
    }

    private fun onPageMessage(handle: String, payload: String) {
        ExtensionDevLog.log(ExtensionDevLogEntry.Kind.Event, extension.id, "page[$handle] → ext ${payload.take(200)}")
        scope.launch { host.postToWebview(handle, payload) }
    }

    /** Titles the extension gave its panels, so a later `reveal` can name the tab it reopens. */
    private val panelTitles = HashMap<String, String>()

    /** The surface for [handle], creating its WebView on first use. Main thread only. */
    private fun surfaceFor(handle: String): Surface = surfaces.getOrPut(handle) {
        Surface(handle, newWebView(context, extension, backgroundArgb, handle, ::onPageMessage))
    }

    private fun closeSurface(handle: String) {
        surfaces.remove(handle)?.let { surface ->
            (surface.webView.parent as? ViewGroup)?.removeView(surface.webView)
            surface.webView.destroy()
        }
        panelTitles.remove(handle)
    }

    /**
     * Load [html] into the webview [handle] names, skipping a render it is already showing.
     *
     * Marshalled through [scope] (main-immediate) rather than `webView.post`: a WebView is not in the
     * view tree until there is a page to show, and `View.post` on a detached view defers the runnable
     * until it attaches — which would never happen, because attaching is what this enables.
     */
    private fun render(handle: String, html: String) {
        if (html.isBlank() || handle.isBlank()) return
        val document = vsixBootstrap(projectName, projectPath) + html
        val stamp = html.hashCode()
        scope.launch {
            val surface = surfaceFor(handle)
            if (surface.loadedStamp == stamp) return@launch
            surface.loadedStamp = stamp
            surface.webView.loadDataWithBaseURL("$VSIX_RESOURCE_ORIGIN/", document, "text/html", "utf-8", null)
            surface.hasPage = true
        }
    }

    private suspend fun start(
        apiRequest: suspend (envelopeJson: String) -> String,
        isDarkTheme: Boolean,
    ) {
        // Resolved before the host is spawned, not after: the project is the host's HOME, and an
        // extension reads workspaceFolders to decide what it is working on, so the wrong answer here
        // is the difference between it loading the project and asking the user to pick one.
        val project = runCatching {
            JSONObject(apiRequest("""{"type":"workbench.projectInfo","payload":{}}""")).optJSONObject("data")
        }.getOrNull()
        projectPath = project?.optString("path")?.takeIf { it.isNotBlank() }
        projectName = project?.optString("name")?.takeIf { it.isNotBlank() }
            ?: projectPath?.substringAfterLast('/')

        host.start(projectDir = projectPath)?.let { failure = it; return }
        status = "Loading ${extension.name}…"

        val activated = host.activate(
            folders = projectPath?.let { listOf((projectName ?: it.substringAfterLast('/')) to it) }.orEmpty(),
            configuration = JSONObject(),
        )
        // Tell the extension which theme it is being shown in before it builds its view, so it styles
        // itself correctly the first time rather than after a repaint.
        runCatching { host.setTheme(dark = isDarkTheme) }
        activated.optString("error").takeIf { it.isNotBlank() }?.let { failure = it; return }

        val views = activated.optJSONArray("views")
        val viewId = (0 until (views?.length() ?: 0)).firstNotNullOfOrNull { views?.optString(it) }
        if (viewId.isNullOrBlank()) {
            failure = "${extension.name} registered no view to show."
            return
        }
        VsixViewHolder.titleActions[extension.id] = readTitleActions(viewId)

        val resolved = host.resolveWebviewView(viewId)
        resolved.optString("error").takeIf { it.isNotBlank() }?.let { failure = it; return }
        val handle = resolved.optString("handle")
        viewHandle = handle
        render(handle, resolved.optString("html"))
    }

    /** Remember a panel's title so a later `reveal` can reopen its tab under the same name. */
    private fun rememberPanelTitle(handle: String, title: String) {
        panelTitles[handle] = title
    }

    /** The extension's own manifest, unpacked into the install dir, is the source for its actions. */
    private fun readTitleActions(viewId: String): List<VsixCommand> = runCatching {
        val manifest = File(extension.dir, "package.json").takeIf { it.isFile }?.readText() ?: return emptyList()
        val nls = File(extension.dir, "package.nls.json").takeIf { it.isFile }?.readText()
        VsixPackage.parseViewTitleActions(manifest, nls, viewId)
    }.getOrDefault(emptyList())

    companion object {
        fun start(
            context: Context,
            extension: InstalledExtension,
            spawnProcess: (command: String) -> Process?,
            apiRequest: suspend (envelopeJson: String) -> String,
            backgroundArgb: Int,
            isDarkTheme: Boolean,
            onOpenPanel: (handle: String, title: String) -> Unit,
        ): VsixSession {
            val appContext = context.applicationContext
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            // The host callback can only fire once the host is started, which happens after `session`
            // is assigned below.
            lateinit var session: VsixSession
            val host = VsCodeExtensionHost(appContext, extension, spawnProcess) { method, params ->
                session.onHostEvent(method, params)
            }
            session = VsixSession(
                extension = extension,
                version = extension.version,
                host = host,
                scope = scope,
                context = appContext,
                backgroundArgb = backgroundArgb,
                onOpenPanel = onOpenPanel,
            )
            scope.launch { session.start(apiRequest, isDarkTheme) }
            return session
        }

        /**
         * A WebView for one of the extension's webviews. Each carries its own bridge, closed over the
         * handle it belongs to, so a page's messages reach the webview the extension is listening on
         * rather than whichever one happens to be the view.
         */
        @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
        private fun newWebView(
            context: Context,
            extension: InstalledExtension,
            backgroundArgb: Int,
            handle: String,
            onPageMessage: (handle: String, payload: String) -> Unit,
        ): WebView = NoFullscreenWebView(context).apply {
            setBackgroundColor(backgroundArgb)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION")
            settings.allowFileAccess = true
            // Honour the viewport meta the bootstrap injects, which declares an explicit
            // height=device-height. That is what gives this WebView a layout viewport with a real
            // height, so `vh` and percentage chains resolve instead of collapsing — fixing it here
            // rather than rewriting the extension's stylesheet afterwards. Safe to enable because this
            // page always carries that meta; a page without one would fall back to a 980px-wide
            // desktop viewport.
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // The page is served over https so its resource origin can back a Content-Security-Policy,
            // but the server an extension talks to is plain http on loopback inside the runtime —
            // OpenChamber starts opencode and then calls it. That mix is blocked by default and the
            // extension simply hangs, so allow it: both ends are on this device.
            settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    // Dynamic units (dvh) are rejected outright by these engines whatever the viewport
                    // is, so the repair pass still runs — it no-ops where unneeded.
                    view.evaluateJavascript(VIEWPORT_SIZE_JS, null)
                }

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
            // A WebView nested in a scrollable panel loses drags to its parent otherwise.
            setOnTouchListener { v, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                }
                false
            }
            addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun postMessage(payload: String) = onPageMessage(handle, payload)
                },
                "JCodeVsix",
            )
        }
    }
}

/**
 * The live `.vsix` sessions, one per extension, keyed by id.
 *
 * Process-scoped for the reason in [VsixSession]: an extension must survive its view being hidden.
 * Mirrors [ScmWebViewHolder] — mount points detach, only this destroys.
 */
internal object VsixViewHolder {
    private val sessions = HashMap<String, VsixSession>()

    /**
     * Each running extension's view-title actions, by extension id.
     *
     * Kept here rather than on the session because the drawer header composes before the body starts
     * the session, so a header reading through the session gets null on the pass that matters and does
     * not reliably resubscribe once the actions land — the menu then stayed hidden until something
     * else forced recomposition. This map exists from the start, so the read always registers.
     */
    val titleActions = mutableStateMapOf<String, List<VsixCommand>>()

    fun get(id: String): VsixSession? = sessions[id]

    fun ids(): List<String> = sessions.keys.toList()

    fun destroy(id: String) {
        sessions.remove(id)?.dispose()
        titleActions.remove(id)
    }

    fun destroyAll() {
        sessions.keys.toList().forEach { destroy(it) }
    }

    /** The session for [extension], starting it if it is not running (or is running a stale version). */
    fun getOrStart(
        context: Context,
        extension: InstalledExtension,
        spawnProcess: (command: String) -> Process?,
        apiRequest: suspend (envelopeJson: String) -> String,
        backgroundArgb: Int,
        isDarkTheme: Boolean,
        onOpenPanel: (handle: String, title: String) -> Unit,
    ): VsixSession {
        sessions[extension.id]?.let { existing ->
            // A session that failed is not worth keeping: the usual cause is something missing from
            // the runtime, so reopening the view after installing it should retry rather than show the
            // same stale error forever.
            if (existing.version == extension.version && existing.failure == null) return existing
            destroy(extension.id)
        }
        return VsixSession.start(
            context = context,
            extension = extension,
            spawnProcess = spawnProcess,
            apiRequest = apiRequest,
            backgroundArgb = backgroundArgb,
            isDarkTheme = isDarkTheme,
            onOpenPanel = onOpenPanel,
        ).also { sessions[extension.id] = it }
    }
}

/**
 * Shows an imported `.vsix`, wherever it is mounted.
 *
 * Nothing can be drawn until the extension has run — its HTML is produced by its own code — so this
 * reports progress until a page arrives. Requests to [VSIX_RESOURCE_ORIGIN] are served straight from
 * the install directory, which is how the extension's scripts and styles resolve without the page
 * ever learning where on disk it actually lives.
 */
@Composable
internal fun VsixExtensionView(
    extension: InstalledExtension,
    spawnProcess: (command: String) -> Process?,
    onApiRequest: suspend (envelopeJson: String) -> String,
    onOpenPanel: (handle: String, title: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val backgroundArgb = MaterialTheme.colorScheme.background.toArgb()
    val isDarkTheme = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val session = remember(extension.id, extension.version) {
        VsixViewHolder.getOrStart(
            context = context,
            extension = extension,
            spawnProcess = spawnProcess,
            apiRequest = onApiRequest,
            backgroundArgb = backgroundArgb,
            isDarkTheme = isDarkTheme,
            onOpenPanel = onOpenPanel,
        )
    }

    session.failure?.let {
        ExtensionNotice(it, modifier)
        return
    }
    val surface = session.viewSurface
    if (surface == null || !surface.hasPage) {
        ExtensionNotice(session.status, modifier)
        return
    }
    VsixSurfaceView(surface, modifier)
}

/**
 * Shows a webview panel the extension opened, as a page in the editor area.
 *
 * This is what `createWebviewPanel` means on a phone: OpenChamber's "Open Session in Editor" and its
 * Agent Manager both ask for one. The panel belongs to the session, so it keeps running while the tab
 * is not on screen and reopening the tab shows it as it was.
 */
@Composable
internal fun VsixPanelPage(
    extension: InstalledExtension,
    handle: String,
    modifier: Modifier = Modifier,
) {
    val surface = VsixViewHolder.get(extension.id)?.panel(handle)
    if (surface == null) {
        ExtensionNotice("${extension.name} closed this view.", modifier)
        return
    }
    if (!surface.hasPage) {
        ExtensionNotice("Opening ${extension.name}…", modifier)
        return
    }
    VsixSurfaceView(surface, modifier)
}

/** Mount one of a session's WebViews, reparenting it rather than rebuilding it. */
@Composable
private fun VsixSurfaceView(surface: VsixSession.Surface, modifier: Modifier) {
    PersistentWebViewHost(surface.webView, modifier.fillMaxSize())
}

/**
 * Mounts a WebView that outlives this composable, re-parenting it in and out instead of building or
 * destroying it.
 *
 * The WebView goes into a container this mount owns rather than being handed to [AndroidView]
 * directly, because two mounts can be alive at the same moment: rotating the device composes the
 * landscape drawer before the portrait one is torn down, and a teardown that detached the WebView
 * from "whatever its parent happens to be" then ripped it straight back out of the mount that had
 * just adopted it — which is why an extension's view went blank after a rotation. Removing it from
 * *this* container is a no-op once another mount owns it, so either order is safe.
 */
@Composable
internal fun PersistentWebViewHost(webView: WebView, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { context -> FrameLayout(context) },
        update = { host ->
            if (webView.parent !== host) {
                (webView.parent as? ViewGroup)?.removeView(webView)
                host.addView(
                    webView,
                    FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
            }
        },
        onRelease = { host -> host.removeView(webView) },
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
private fun vsixBootstrap(projectName: String?, projectPath: String?): String {
    // A VS Code webview is handed its workspace through page config, not through the extension API —
    // the page cannot call vscode.workspace itself. Telling only the extension host which project is
    // open therefore leaves the UI to fall back to whatever it last persisted, which is how it ends
    // up showing a stale folder instead of the one on screen.
    val folders = if (projectPath != null) {
        """[{"name":${JSONObject.quote(projectName ?: projectPath.substringAfterLast('/'))},""" +
            """"path":${JSONObject.quote(projectPath)},"uri":${JSONObject.quote("file://$projectPath")}}]"""
    } else {
        "[]"
    }
    val folder = if (projectPath != null) JSONObject.quote(projectPath) else "null"
    return """
<script>
(function () {
  var ours = { workspaceFolder: $folder, workspaceFolders: $folders };
  var config = Object.assign({ theme: 'dark', platform: 'linux' }, ours);
  // The extension writes this config itself once its own script runs, which would drop the workspace
  // we just resolved and send it back to whatever it last persisted. Keep everything it sets except
  // the folders, which JCode is the authority on.
  Object.defineProperty(window, '__VSCODE_CONFIG__', {
    configurable: false,
    get: function () { return config; },
    set: function (value) { config = Object.assign({}, value || {}, ours); },
  });
  // The home an extension browses from. Pinned for the same reason as the folders, and it matters
  // more here: the extension host runs as the runtime's root user, so an unpinned value resolves to
  // /root — a directory holding nothing but dotfiles, which is why the folder picker opened on an
  // empty list. With a project open, the project IS the sensible place to start.
  if ($folder) {
    var home = $folder;
    Object.defineProperty(window, '__OPENCHAMBER_HOME__', {
      configurable: false,
      get: function () { return home; },
      set: function () {},
    });
    // Seed the "~" a file-browsing extension expands against. It is a cache the page derives from
    // whatever directory it can find and then persists, and the process it asks lives in /root, so
    // without this it settles on a directory holding nothing the user wants to see. Written before
    // the extension's own script runs so the value is in place the first time it is read.
    try {
      window.localStorage.setItem('homeDirectory', home);
      window.localStorage.setItem('lastDirectory', home);
    } catch (e) {}
  }
})();
</script>
""" + VSIX_BOOTSTRAP
}

private val VSIX_BOOTSTRAP = """
<meta name="viewport" content="width=device-width, height=device-height, initial-scale=1, user-scalable=no">
<style>
html,body{margin:0;padding:0;overflow:hidden}
/* VS Code hands a webview its theme as --vscode-* variables. An extension's styling resolves
   against those, so without them it draws in a washed-out fallback. Only the variables are
   supplied — the extension owns what it does with them. */
:root{
  --vscode-editor-background:#14151d; --vscode-editor-foreground:#d5d9e0;
  --vscode-sideBar-background:#101118; --vscode-sideBar-foreground:#c8cdd6;
  --vscode-panel-background:#14151d; --vscode-panel-border:#2a2d3c;
  --vscode-button-background:#3d5afe; --vscode-button-foreground:#ffffff;
  --vscode-button-hoverBackground:#4d68ff; --vscode-button-secondaryBackground:#2a2d3c;
  --vscode-button-secondaryForeground:#d5d9e0;
  --vscode-input-background:#1c1e29; --vscode-input-foreground:#d5d9e0;
  --vscode-input-border:#2a2d3c; --vscode-input-placeholderForeground:#8b93a3;
  --vscode-focusBorder:#3d5afe; --vscode-errorForeground:#d06262;
  --vscode-descriptionForeground:#8b93a3; --vscode-textLink-foreground:#7f9cff;
  --vscode-foreground:#d5d9e0; --vscode-widget-border:#2a2d3c;
  --vscode-list-hoverBackground:#1c1e29; --vscode-list-activeSelectionBackground:#2a2d3c;
  --vscode-editorWidget-background:#181a24; --vscode-editorWidget-border:#2a2d3c;
  --vscode-editor-font-family:monospace; --vscode-font-family:system-ui,sans-serif;
  --vscode-font-size:13px; --vscode-font-weight:400;
}
</style>
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

/**
 * Undo Tailwind's opacity fallback on engines without `color-mix()`.
 *
 * A translucent utility compiles to a solid colour plus a `@supports color-mix` block that adds the
 * alpha. Where `color-mix()` is unsupported only the solid colour survives, so every intended tint
 * paints at full strength — a 10% selection wash becomes a solid bar. The alpha cannot be recovered
 * in CSS (the colour arrives through a variable, and neither relative colour syntax nor `color-mix`
 * exists here), but the intent can: a faint tint reads far closer to nothing than to solid. So the
 * faint ones are dropped and the strong ones kept.
 *
 * Appended rather than edited in place: same selector, same specificity, later in the sheet.
 */
private fun tintOverridesFor(css: String): String {
    val supportsBlock = Regex("""@supports\s*\(color:\s*color-mix\([^)]*\)\)\s*\{((?:[^{}]|\{[^{}]*\})*)\}""")
    val rule = Regex("""([^{}]+)\{([^{}]*)\}""")
    val tinted = Regex("""([-a-zA-Z]+)\s*:\s*color-mix\(in oklab,[^,]+?([\d.]+)%\s*,\s*transparent\)""")

    val overrides = StringBuilder()
    for (block in supportsBlock.findAll(css)) {
        for (inner in rule.findAll(block.groupValues[1])) {
            val selector = inner.groupValues[1].trim()
            if (selector.isEmpty() || selector.startsWith("@")) continue
            for (decl in tinted.findAll(inner.groupValues[2])) {
                val percent = decl.groupValues[2].toFloatOrNull() ?: continue
                if (percent >= FAINT_TINT_CEILING) continue
                overrides.append(selector).append('{').append(decl.groupValues[1]).append(":transparent}")
            }
        }
    }
    return overrides.toString()
}

/** Above this, a tint is a real fill and is left alone; below it, it was meant to be barely there. */
private const val FAINT_TINT_CEILING = 60f

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
    if (mime == "text/css") {
        val css = file.readText()
        val patched = css + tintOverridesFor(css)
        return android.webkit.WebResourceResponse(mime, "utf-8", patched.byteInputStream())
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
/**
 * Publishes the WebView's real size to every extension page as `--jcode-viewport-height` /
 * `--jcode-viewport-width`, and repairs viewport-height lengths on engines that cannot resolve them.
 *
 * Android WebView is a system component many devices never update, and older builds lay a page out
 * against a viewport whose height is zero even though `window.innerHeight` reports the true size:
 * every `vh` length there resolves to 0, so an extension's dialogs and side panels — sized against
 * the viewport — render as empty slivers. Dynamic units (`dvh`, Chromium 108+) are rejected outright
 * on top of that.
 *
 * A probe decides whether this engine is affected, so healthy WebViews do no work at all. Where it
 * is, every viewport-height length in the page's stylesheets is rewritten against the published
 * variable. Rules are edited in place so their `@media` / `@supports` / `@layer` context is
 * preserved, the pass is deferred off first paint, and a `<head>` observer catches stylesheets that
 * arrive later — a code-split bundle usually loads its CSS well after the page "finishes".
 */
private const val VIEWPORT_SIZE_JS = """
(function () {
  var root = document.documentElement;
  var publish = function () {
    root.style.setProperty('--jcode-viewport-height', window.innerHeight + 'px');
    root.style.setProperty('--jcode-viewport-width', window.innerWidth + 'px');
  };
  publish();
  window.addEventListener('resize', publish);

  var probe = document.createElement('div');
  probe.style.cssText = 'position:absolute;visibility:hidden;pointer-events:none;height:100vh';
  (document.body || root).appendChild(probe);
  var viewportUnitsWork = probe.getBoundingClientRect().height > 1;
  probe.parentNode.removeChild(probe);
  if (viewportUnitsWork || !window.innerHeight) return;

  // Heights only — viewport widths resolve correctly even on the affected engines.
  var LENGTH = /(-?\d*\.?\d+)(?:[dls])?(?:vh|vb)\b/gi;
  var repair = function (value) {
    return value.replace(LENGTH, function (match, amount) {
      var fraction = parseFloat(amount) / 100;
      return fraction === 1
        ? 'var(--jcode-viewport-height)'
        : 'calc(var(--jcode-viewport-height) * ' + fraction + ')';
    });
  };

  var seen = [];
  var repairRules = function (rules) {
    for (var i = 0; i < rules.length; i++) {
      var rule = rules[i];
      if (rule.cssRules) { repairRules(rule.cssRules); continue; }
      var style = rule.style;
      if (!style || !style.length) continue;
      for (var j = 0; j < style.length; j++) {
        var prop = style[j];
        var value = style.getPropertyValue(prop);
        if (value.indexOf('vh') < 0 && value.indexOf('vb') < 0) continue;
        var next = repair(value);
        if (next !== value) { style.setProperty(prop, next, style.getPropertyPriority(prop)); repaired++; }
      }
    }
  };

  var scheduled = false;
  var repaired = 0;
  var sweep = function () {
    scheduled = false;
    var started = Date.now();
    var before = repaired;
    var sheets = document.styleSheets;
    for (var i = 0; i < sheets.length; i++) {
      var sheet = sheets[i];
      if (seen.indexOf(sheet) >= 0) continue;
      // A sheet the page cannot read, or has not parsed yet, throws. Only record it as done on
      // success — otherwise a sheet still parsing when this runs would never be revisited.
      try {
        var rules = sheet.cssRules;
        repairRules(rules);
        seen.push(sheet);
      } catch (e) {}
    }
    if (repaired > before) {
      console.log('[jcode] viewport-height CSS repaired: ' + repaired + ' declaration(s) in '
        + (Date.now() - started) + 'ms');
    }
  };
  var schedule = function () {
    if (scheduled) return;
    scheduled = true;
    if (window.requestIdleCallback) window.requestIdleCallback(sweep, { timeout: 500 });
    else window.setTimeout(sweep, 0);
  };

  schedule();
  // Watch <head> only: that is where a bundler injects its stylesheets, and watching the whole
  // document would fire this callback on every render of a busy app for no benefit.
  if (window.MutationObserver && document.head) {
    new MutationObserver(schedule).observe(document.head, { childList: true, subtree: true });
  }
})();
"""

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

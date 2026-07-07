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
private const val JCODE_TRANSFER_HOST = "/storage/emulated/0/JCode/.jcode-transfer"

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
    modifier: Modifier = Modifier,
) {
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
    // (JCODE_TRANSFER_HOST -> /jcode-transfer). The extension then gets a runtime path it can hand to
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
                    val destDir = File(JCODE_TRANSFER_HOST).apply { mkdirs() }
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
    // writes a file into /jcode-transfer (JCODE_TRANSFER_HOST) — e.g. a pg_dump/.bak backup — then asks
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
                    pendingExport.value = reqId to File(JCODE_TRANSFER_HOST, basename)
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
    LaunchedEffect(themeJs, backgroundArgb) {
        webView?.post { webView?.setBackgroundColor(backgroundArgb); webView?.evaluateJavascript(themeJs, null) }
    }
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
                }
                // Route `<input type="file">` to the SAF picker so extensions can select a file from
                // device storage (e.g. a .bak to restore). Without a WebChromeClient the input is inert.
                webChromeClient = object : WebChromeClient() {
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

private const val NO_UI_HTML =
    "<html><body style=\"font-family:sans-serif;color:#9aa;padding:24px\">" +
        "This extension does not ship a UI.</body></html>"

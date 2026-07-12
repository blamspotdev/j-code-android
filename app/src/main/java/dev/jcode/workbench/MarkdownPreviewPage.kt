package dev.jcode.workbench

import android.annotation.SuppressLint
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.ColorScheme
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeIcon
import dev.jcode.design.JCodeSemanticColors
import dev.jcode.design.JCodeTheme
import dev.jcode.design.JcTooltip
import dev.jcode.design.jcIcon
import dev.jcode.editor.MarkdownHtml
import dev.jcode.editor.TokenPalette
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.feature.editor.pane.LocalEditorMenuExtras
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.languageFor
import dev.jcode.run.ProjectRunner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * Rendered Markdown preview for a file tab in preview mode. Reads the LIVE buffer (unsaved edits
 * included) from the tab's EditorState snapshot flow and re-renders debounced as the user types;
 * the body is patched via JS so the WebView keeps its scroll position. Long-press (or the header
 * button) flips back to the source editor through [LocalEditorMenuExtras]'s preview toggle.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun MarkdownPreviewPage(
    tab: EditorTab,
    dark: Boolean,
    languagePacks: List<InstalledExtension>,
    modifier: Modifier = Modifier,
) {
    val state = tab.editorState ?: return
    val density = LocalDensity.current
    val menuExtras = LocalEditorMenuExtras.current
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JCodeTheme.semanticColors
    val backgroundArgb = colorScheme.background.toArgb()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var latestBodyJs by remember { mutableStateOf<String?>(null) }
    var menuAt by remember { mutableStateOf<Pair<Float, Float>?>(null) }

    val palette = if (dark) TokenPalette.DARK else TokenPalette.LIGHT
    val packResolver = remember(languagePacks) {
        { name: String -> languagePacks.firstNotNullOfOrNull { it.languageFor(name) } }
    }
    val baseDirPath = tab.filePath.path.takeIf { it.isNotBlank() }?.let { tab.filePath.parentFile?.path }
    val imageResolver = remember(baseDirPath) {
        { src: String -> inlineLocalImage(baseDirPath?.let(::File), src) }
    }

    LaunchedEffect(state, palette, packResolver, imageResolver) {
        state.snapshot.collectLatest { snap ->
            delay(120)
            // Snapshot reads, rendering, and the (large) JS-string quoting all stay off-main.
            latestBodyJs = withContext(Dispatchers.Default) {
                val text = runCatching { snap.readRangeAsUtf16(0, snap.byteLength) }.getOrDefault("")
                var inlinedBytes = 0L
                val budgetedImages: (String) -> String? = { src ->
                    if (inlinedBytes >= MAX_TOTAL_INLINE_IMAGE_BYTES) null
                    else imageResolver(src)?.also { inlinedBytes += it.length }
                }
                val body = runCatching { MarkdownHtml.render(text, palette, packResolver, budgetedImages) }
                    .getOrDefault("<pre>Preview unavailable.</pre>")
                "document.getElementById('md').innerHTML = " + JSONObject.quote(body) + ";"
            }
        }
    }

    LaunchedEffect(latestBodyJs, pageReady, webView) {
        val js = latestBodyJs ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        webView?.evaluateJavascript(js, null)
    }

    // Live theme: vars are baked into the shell document, then re-injected on theme changes.
    val themeJs = remember(colorScheme, semantic) {
        val sets = themeVars(colorScheme, semantic)
            .joinToString("") { (k, v) -> "r.setProperty('$k','$v');" }
        "(function(){try{var r=document.documentElement.style;$sets}catch(e){}})()"
    }
    val themeJsState = rememberUpdatedState(themeJs)
    // Markdown link taps honor the user's global web-preview browser choice (was hardcoded to SYSTEM).
    val webBrowserChoice by rememberUpdatedState(dev.jcode.design.LocalWebPreviewBrowsers.current.globalChoice)
    LaunchedEffect(themeJs, backgroundArgb, webView) {
        webView?.post {
            webView?.setBackgroundColor(backgroundArgb)
            webView?.evaluateJavascript(themeJs, null)
        }
    }

    DisposableEffect(tab.id) {
        onDispose { webView?.destroy(); webView = null }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = tab.title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                text = "  ·  Preview",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            JcTooltip("Show source") {
                IconButton(onClick = { menuExtras.previewToggle?.invoke() }, modifier = Modifier.size(36.dp)) {
                    Icon(
                        imageVector = jcIcon(JCodeIcon.Code),
                        contentDescription = "Show source",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            // Keyed so a crashed WebView renderer disposes this view and the factory builds a fresh
            // one; the injection effect re-delivers the current body once the new page reports ready.
            key(webViewGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(backgroundArgb)
                        settings.javaScriptEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                view.evaluateJavascript(themeJsState.value, null)
                                pageReady = true
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                val url = request.url?.toString().orEmpty()
                                if (url.startsWith("http://") || url.startsWith("https://")) {
                                    if (webBrowserChoice == dev.jcode.design.WebPreviewBrowsers.BUILTIN) {
                                        BuiltinBrowser.requestOpen(url)
                                    } else {
                                        ProjectRunner.openInBrowser(view.context, url, webBrowserChoice)
                                    }
                                }
                                return true
                            }

                            override fun onRenderProcessGone(
                                view: WebView,
                                detail: RenderProcessGoneDetail,
                            ): Boolean {
                                (view.parent as? ViewGroup)?.removeView(view)
                                view.destroy()
                                if (webView === view) {
                                    webView = null
                                    pageReady = false
                                    webViewGeneration++
                                }
                                return true
                            }
                        }
                        var downX = 0f
                        var downY = 0f
                        setOnTouchListener { v, event ->
                            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                                downX = event.x
                                downY = event.y
                                v.parent?.requestDisallowInterceptTouchEvent(true)
                            }
                            false
                        }
                        setOnLongClickListener {
                            menuAt = downX to downY
                            true
                        }
                        val shell = shellDocument(colorScheme, semantic)
                        loadUrl(
                            "data:text/html;charset=utf-8;base64," +
                                Base64.encodeToString(shell.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                        )
                        webView = this
                    }
                },
            )
            }
            menuAt?.let { (x, y) ->
                val offset = with(density) { DpOffset(x.toDp(), y.toDp()) }
                CompactContextMenu(
                    expanded = true,
                    onDismissRequest = { menuAt = null },
                    offset = offset,
                    listActions = listOfNotNull(
                        menuExtras.previewToggle?.let { toggle ->
                            ContextAction(JCodeIcon.Code, "Show source") { toggle() }
                        },
                    ),
                )
            }
        }
    }
}

private fun themeVars(colorScheme: ColorScheme, semantic: JCodeSemanticColors): List<Pair<String, String>> {
    fun hex(c: Color): String = String.format("#%06X", 0xFFFFFF and c.toArgb())
    return listOf(
        "--jcode-background" to hex(colorScheme.background),
        "--jcode-surface" to hex(colorScheme.surface),
        "--jcode-surface-variant" to hex(colorScheme.surfaceVariant),
        "--jcode-on-surface" to hex(colorScheme.onSurface),
        "--jcode-on-surface-variant" to hex(colorScheme.onSurfaceVariant),
        "--jcode-outline" to hex(colorScheme.outline),
        "--jcode-outline-variant" to hex(colorScheme.outlineVariant),
        "--jcode-primary" to hex(colorScheme.primary),
        "--jcode-error" to hex(colorScheme.error),
        "--jcode-success" to hex(semantic.success),
        "--jcode-warning" to hex(semantic.warning),
    )
}

/** Empty themed shell; the rendered body is injected into `#md` once the page is ready. */
private fun shellDocument(colorScheme: ColorScheme, semantic: JCodeSemanticColors): String {
    val rootVars = themeVars(colorScheme, semantic).joinToString("") { (k, v) -> "$k:$v;" }
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{$rootVars}
html,body{margin:0;padding:0;background:var(--jcode-background);color:var(--jcode-on-surface);
font-family:-apple-system,Roboto,'Segoe UI',sans-serif;font-size:14px;line-height:1.55;
-webkit-text-size-adjust:100%;word-wrap:break-word}
#md{padding:14px 16px 48px}
h1,h2,h3,h4,h5,h6{line-height:1.25;margin:1.1em 0 .5em;font-weight:600}
h1{font-size:1.7em;padding-bottom:.25em;border-bottom:1px solid var(--jcode-outline-variant)}
h2{font-size:1.4em;padding-bottom:.25em;border-bottom:1px solid var(--jcode-outline-variant)}
h3{font-size:1.2em}h4{font-size:1.05em}h5,h6{font-size:1em}
p{margin:.6em 0}
a{color:var(--jcode-primary);text-decoration:none}
code{font-family:monospace;font-size:.92em;background:var(--jcode-surface-variant);
padding:.15em .35em;border-radius:4px}
pre{background:var(--jcode-surface-variant);border-radius:8px;padding:10px 12px;overflow-x:auto;margin:.7em 0}
pre code{background:none;padding:0;font-size:12px;line-height:1.5;white-space:pre}
blockquote{margin:.7em 0;padding:.05em 0 .05em 12px;border-left:3px solid var(--jcode-outline);
color:var(--jcode-on-surface-variant)}
ul,ol{margin:.4em 0;padding-left:1.6em}
li{margin:.15em 0}
hr{border:none;border-top:1px solid var(--jcode-outline-variant);margin:1.2em 0}
table{border-collapse:collapse;margin:.7em 0;display:block;overflow-x:auto;max-width:100%}
th,td{border:1px solid var(--jcode-outline-variant);padding:5px 10px}
th{background:var(--jcode-surface-variant)}
img{max-width:100%;border-radius:4px}
input[type=checkbox]{vertical-align:middle;margin-right:4px}
</style></head><body><div id="md"></div></body></html>"""
}

/** Inline a local (relative or absolute) image referenced by the Markdown as a data: URI, so it
 *  renders inside the data:-origin preview document. Remote/data URLs pass through untouched. */
private fun inlineLocalImage(baseDir: File?, src: String): String? {
    val cleaned = src.substringBefore('#').substringBefore('?').trim()
    if (cleaned.isEmpty() || cleaned.contains(':')) return null
    val decoded = runCatching { java.net.URLDecoder.decode(cleaned, "UTF-8") }.getOrDefault(cleaned)
    val file = listOf(cleaned, decoded)
        .distinct()
        .mapNotNull { candidate ->
            val f = if (candidate.startsWith("/")) File(candidate) else File(baseDir ?: return@mapNotNull null, candidate)
            runCatching { f.canonicalFile }.getOrNull()
        }
        .firstOrNull { it.isFile }
        ?: return null
    if (file.length() > MAX_INLINE_IMAGE_BYTES) return null
    val mime = when (file.name.substringAfterLast('.', "").lowercase()) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "bmp" -> "image/bmp"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        else -> return null
    }
    val b64 = runCatching { Base64.encodeToString(file.readBytes(), Base64.NO_WRAP) }.getOrNull() ?: return null
    return "data:$mime;base64,$b64"
}

private const val MAX_INLINE_IMAGE_BYTES = 8L * 1024 * 1024
private const val MAX_TOTAL_INLINE_IMAGE_BYTES = 16L * 1024 * 1024

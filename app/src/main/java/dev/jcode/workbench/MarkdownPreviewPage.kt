package dev.jcode.workbench

import android.annotation.SuppressLint
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewGroup
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.ColorScheme
import android.content.res.Configuration
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.CompactSearchField
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon
import dev.jcode.design.LocalCutoutSetting
import dev.jcode.design.LocalMarkdownPreviewSetting
import dev.jcode.findActivity
import dev.jcode.design.JCodeSemanticColors
import dev.jcode.design.JCodeTheme
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
 * the body is patched via JS so the WebView keeps its scroll position. Leaving the tab destroys the
 * WebView (the shell keys this page by tab id), so the reading position is stashed in
 * [PreviewScrollCache] and re-applied to its replacement. Long-press opens a context menu: Select
 * Text (highlights the pressed block), Copy (the highlighted text), and View Source Code, which
 * flips back to the source editor through [LocalEditorMenuExtras]'s preview toggle.
 */
@SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility")
@Composable
fun MarkdownPreviewPage(
    tab: EditorTab,
    dark: Boolean,
    languagePacks: List<InstalledExtension>,
    mermaidScript: File? = null,
    modifier: Modifier = Modifier,
) {
    val state = tab.editorState ?: return
    val density = LocalDensity.current
    val menuExtras = LocalEditorMenuExtras.current
    val clipboard = LocalClipboardManager.current
    val colorScheme = MaterialTheme.colorScheme
    val semantic = JCodeTheme.semanticColors
    val backgroundArgb = colorScheme.background.toArgb()

    var webView by remember { mutableStateOf<WebView?>(null) }
    var webViewGeneration by remember { mutableStateOf(0) }
    var pageReady by remember { mutableStateOf(false) }
    var latestBodyJs by remember { mutableStateOf<String?>(null) }
    var menuAt by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    // What "Select Text" last highlighted (the select script returns it) — Copy uses this directly.
    // The next long-press's own touch-down collapses the page selection before any query could run,
    // so the selection must be captured when it is MADE, not when the menu reopens.
    var selectedText by remember { mutableStateOf("") }
    // Find-in-page (native WebView.findAllAsync) + Go-to-line, both from the long-press menu.
    var findVisible by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findMatches by remember { mutableStateOf(0) }
    var findActive by remember { mutableStateOf(0) }
    var goToLineVisible by remember { mutableStateOf(false) }
    // Read the latest find state from the (non-recomposing) body-injection JS callback.
    val findVisibleState = rememberUpdatedState(findVisible)
    val findQueryState = rememberUpdatedState(findQuery)

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
                // The mermaid pass is a no-op stub unless the shell loaded the extension's engine.
                "document.getElementById('md').innerHTML = " + JSONObject.quote(body) + ";" +
                    "window.jcodeRenderMermaid&&window.jcodeRenderMermaid();"
            }
        }
    }

    // Restore only for the first body this WebView receives: later re-renders (typing) must leave the
    // reader wherever they are now, not yank them back to where the tab was last left.
    var restoredScroll by remember(webViewGeneration) { mutableStateOf(false) }

    LaunchedEffect(latestBodyJs, pageReady, webView) {
        val js = latestBodyJs ?: return@LaunchedEffect
        if (!pageReady) return@LaunchedEffect
        val view = webView ?: return@LaunchedEffect
        // Re-run the find AFTER the innerHTML swap completes (the JS callback), so an open find bar's
        // highlights + counter refresh against the new DOM instead of going stale — this also covers
        // the first body after a renderer-crash rebuild, where the initial findAllAsync hit an empty
        // page. Keyed on findVisible/findQuery via rememberUpdatedState so the callback sees current
        // values without re-running this effect.
        view.evaluateJavascript(js) {
            if (findVisibleState.value) {
                if (findQueryState.value.isBlank()) view.clearMatches() else view.findAllAsync(findQueryState.value)
            }
        }
        if (restoredScroll) return@LaunchedEffect
        restoredScroll = true
        val (x, y) = PreviewScrollCache.load(tab.id) ?: return@LaunchedEffect
        if (x == 0 && y == 0) return@LaunchedEffect
        // scrollTo would clamp to the top while the injected body is still unmeasured; the visual
        // state callback is the point at which that DOM is laid out and ready to draw.
        view.postVisualStateCallback(
            0L,
            object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    view.scrollTo(x, y)
                }
            },
        )
    }

    // "Word wrap in portrait" off → lay the portrait preview out at landscape width (the screen
    // height, minus the cutout when the app respects it) and let the WebView pan sideways. CSS px in
    // the WebView equal Android dp, so the width is computed in dp. 0 = normal wrapped layout.
    val configuration = LocalConfiguration.current
    val wrapSetting = LocalMarkdownPreviewSetting.current
    val cutoutSetting = LocalCutoutSetting.current
    val previewContext = LocalContext.current
    val mdMinWidthDp = if (
        !wrapSetting.wrapInPortrait &&
        configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    ) {
        // WindowMetrics bounds include the system bars that Configuration.screenHeightDp excludes
        // at targetSdk 33, so this matches the width the edge-to-edge preview actually gets after
        // rotating to landscape (minus only the cutout, when the app respects it).
        val activity = previewContext.findActivity()
        val heightPx = activity?.windowManager?.currentWindowMetrics?.bounds?.height()
        val cutoutPx = if (cutoutSetting.respect) {
            activity?.display?.cutout?.safeInsetTop ?: 0
        } else {
            0
        }
        val widthDp = heightPx?.let { ((it - cutoutPx) / density.density).toInt() }
            ?: configuration.screenHeightDp
        widthDp.coerceAtLeast(configuration.screenWidthDp)
    } else {
        0
    }

    // Live theme: vars are baked into the shell document, then re-injected on theme changes (the
    // wrap width rides the same pipeline so orientation/setting flips apply to the open page).
    val themeJs = remember(colorScheme, semantic, mdMinWidthDp) {
        val sets = themeVars(colorScheme, semantic)
            .joinToString("") { (k, v) -> "r.setProperty('$k','$v');" }
        "(function(){try{var r=document.documentElement.style;$sets" +
            "r.setProperty('--jcode-md-minw','${mdMinWidthDp}px');}catch(e){}})()"
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

    // Re-run the native find as the query changes (debounced like the body re-render); blank clears.
    LaunchedEffect(findQuery, webView) {
        val view = webView ?: return@LaunchedEffect
        delay(120)
        if (findQuery.isBlank()) view.clearMatches() else view.findAllAsync(findQuery)
    }

    DisposableEffect(tab.id) {
        onDispose {
            webView?.let { view ->
                view.clearMatches()
                PreviewScrollCache.save(tab.id, view.scrollX, view.scrollY)
                view.destroy()
            }
            webView = null
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
            // Keyed so a crashed WebView renderer disposes this view and the factory builds a fresh
            // one; the injection effect re-delivers the current body once the new page reports ready.
            key(webViewGeneration) {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    WebView(ctx).apply {
                        setBackgroundColor(backgroundArgb)
                        settings.javaScriptEnabled = true
                        setFindListener { activeOrdinal, total, _ ->
                            findActive = if (total == 0) 0 else activeOrdinal + 1
                            findMatches = total
                        }
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
                        val shell = shellDocument(colorScheme, semantic, dark, mermaidScript != null)
                        if (mermaidScript != null) {
                            // A data: page may not load file:// subresources, so the shell loads
                            // with the extension's www/ as its file base — the relative
                            // <script src="./mermaid.min.js"> then resolves to the bundled engine.
                            settings.allowFileAccess = true
                            loadDataWithBaseURL(
                                "file://${mermaidScript.parentFile?.absolutePath}/",
                                shell,
                                "text/html",
                                "utf-8",
                                null,
                            )
                        } else {
                            loadUrl(
                                "data:text/html;charset=utf-8;base64," +
                                    Base64.encodeToString(shell.toByteArray(Charsets.UTF_8), Base64.NO_WRAP),
                            )
                        }
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
                    quickActions = listOf(
                        ContextAction(JCodeIcon.Copy, "Copy", enabled = selectedText.isNotEmpty()) {
                            clipboard.setText(AnnotatedString(selectedText))
                        },
                    ),
                    listActions = listOfNotNull(
                        ContextAction(JCodeIcon.Cursor, "Select Text") {
                            webView?.evaluateJavascript(selectBlockJs(x, y)) { res ->
                                selectedText = decodeJsString(res)
                            }
                        },
                        ContextAction(JCodeIcon.Search, "Find text") { findVisible = true },
                        ContextAction(JCodeIcon.GoToLine, "Go to line") { goToLineVisible = true },
                        menuExtras.previewToggle?.let { toggle ->
                            ContextAction(JCodeIcon.Code, "View Source Code") { toggle() }
                        },
                    ),
                )
            }

            if (findVisible) {
                FindBar(
                    query = findQuery,
                    onQueryChange = { findQuery = it },
                    active = findActive,
                    total = findMatches,
                    onPrev = { webView?.findNext(false) },
                    onNext = { webView?.findNext(true) },
                    onClose = {
                        webView?.clearMatches()
                        findQuery = ""
                        findVisible = false
                    },
                    modifier = Modifier.align(Alignment.TopCenter),
                )
            }

            if (goToLineVisible) {
                dev.jcode.GoToLineDialog(
                    lineCount = state.snapshot.value.lineCount,
                    onDismiss = { goToLineVisible = false },
                    onGo = { line, col ->
                        // Same reveal path the palette command uses; flipping to source lets the
                        // source EditorView consume the (replayed) reveal request when it mounts.
                        state.requestReveal((line - 1).coerceAtLeast(0), (col - 1).coerceAtLeast(0))
                        menuExtras.previewToggle?.invoke()
                    },
                )
            }
    }
}

/** Compact in-page find bar over the preview WebView: a search field, a live match counter, and
 *  prev/next/close. Matches are driven natively via WebView.findAllAsync/findNext. */
@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    active: Int,
    total: Int,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(enabled = true, onBack = onClose)
    Surface(
        modifier = modifier
            .padding(8.dp)
            .fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 4.dp,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            CompactSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "Find",
                autoFocus = true,
                onImeAction = onNext,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = if (query.isBlank()) "" else "$active/$total",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            IconButton(onClick = onPrev, enabled = total > 0, modifier = Modifier.size(32.dp)) {
                Icon(jcIcon(JCodeIcon.ChevronUp), contentDescription = "Previous match")
            }
            IconButton(onClick = onNext, enabled = total > 0, modifier = Modifier.size(32.dp)) {
                Icon(jcIcon(JCodeIcon.ChevronDown), contentDescription = "Next match")
            }
            IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                Icon(jcIcon(JCodeIcon.Close), contentDescription = "Close find")
            }
        }
    }
}

/**
 * Where each previewed tab was last scrolled to, in WebView (view) pixels — the space both
 * `View.getScrollX/Y` and `View.scrollTo` speak, so the value round-trips unchanged whatever the
 * page zoom. Keyed by tab id and bounded, since a long session can page through many previews.
 */
private object PreviewScrollCache {
    private const val MAX_TABS = 32

    private val offsets = object : LinkedHashMap<String, Pair<Int, Int>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Int, Int>>): Boolean =
            size > MAX_TABS
    }

    @Synchronized
    fun save(tabId: String, x: Int, y: Int) {
        offsets[tabId] = x to y
    }

    @Synchronized
    fun load(tabId: String): Pair<Int, Int>? = offsets[tabId]
}

/** evaluateJavascript delivers a JSON-encoded value (`"text"` or `null`) — decode to a plain string. */
private fun decodeJsString(result: String?): String =
    runCatching { org.json.JSONTokener(result ?: "null").nextValue() as? String }.getOrNull().orEmpty()

/** Highlight the text block (paragraph, code block, list item, heading, table cell…) under a
 *  long-press at view coordinates ([x], [y]), so the context menu's Copy has a visible target.
 *  Coordinates convert to CSS client space, including pinch-zoom via the visual viewport. */
private fun selectBlockJs(x: Float, y: Float): String = """
(function(px,py){try{
var dpr=window.devicePixelRatio||1,vv=window.visualViewport;
var cx=px/dpr,cy=py/dpr;
if(vv){cx=cx/vv.scale+vv.offsetLeft;cy=cy/vv.scale+vv.offsetTop;}
var r=document.caretRangeFromPoint(cx,cy);
if(!r)return '';
var blocks={P:1,PRE:1,LI:1,H1:1,H2:1,H3:1,H4:1,H5:1,H6:1,TD:1,TH:1,BLOCKQUOTE:1,TABLE:1};
var n=r.startContainer;
while(n){
  if(n.nodeType===1){
    if(blocks[n.tagName])break;
    if(n.id==='md'||n===document.body){n=null;break;}
  }
  n=n.parentNode;
}
if(!n)return '';
var sel=window.getSelection();sel.removeAllRanges();
var range=document.createRange();range.selectNodeContents(n);sel.addRange(range);
return sel.toString();
}catch(e){return ''}})($x,$y)
""".trimIndent()

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

/** Empty themed shell; the rendered body is injected into `#md` once the page is ready. With
 *  [withMermaid] the shell loads the Mermaid Preview extension's bundled engine (relative to the
 *  page's file base) and defines the render pass that swaps `pre.mermaid-src` blocks for drawn
 *  diagrams — cached by source hash so live typing only re-renders the fence being edited. */
private fun shellDocument(
    colorScheme: ColorScheme,
    semantic: JCodeSemanticColors,
    dark: Boolean,
    withMermaid: Boolean,
): String {
    val rootVars = themeVars(colorScheme, semantic).joinToString("") { (k, v) -> "$k:$v;" }
    val mermaidTags = if (!withMermaid) "" else """
<script src="./mermaid.min.js"></script>
<script>
(function(){
if(!window.mermaid)return;
mermaid.initialize({startOnLoad:false,securityLevel:'strict',theme:'${if (dark) "dark" else "default"}',
fontFamily:'-apple-system,Roboto,sans-serif'});
var cache={},seq=0;
function h(s){var x=5381;for(var i=0;i<s.length;i++){x=((x<<5)+x+s.charCodeAt(i))|0;}
return (x>>>0).toString(36)+':'+s.length;}
window.jcodeRenderMermaid=function(){
document.querySelectorAll('pre.mermaid-src:not([data-mmd])').forEach(function(pre){
pre.setAttribute('data-mmd','1');
var src=(pre.textContent||'').trim();
if(!src)return;
var key=h(src);
var holder=document.createElement('div');
holder.className='mmd-diagram';
if(cache[key]){holder.innerHTML=cache[key];pre.replaceWith(holder);return;}
var id='mmdjc'+(seq++);
mermaid.render(id,src).then(function(r){
cache[key]=r.svg;
holder.innerHTML=r.svg;
if(pre.parentNode)pre.replaceWith(holder);
}).catch(function(e){
var el=document.getElementById('d'+id);if(el)el.remove();
if(pre.parentNode){pre.classList.add('mmd-err');pre.setAttribute('title',String(e&&e.message||e));}
});
});
};
})();
</script>"""
    return """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<style>
:root{$rootVars}
.mmd-diagram{margin:.7em 0;overflow-x:auto}
.mmd-diagram svg{max-width:100%;height:auto}
pre.mermaid-src.mmd-err{border:1px solid var(--jcode-error)}
html,body{margin:0;padding:0;background:var(--jcode-background);color:var(--jcode-on-surface);
font-family:-apple-system,Roboto,'Segoe UI',sans-serif;font-size:14px;line-height:1.55;
-webkit-text-size-adjust:100%;word-wrap:break-word}
#md{padding:14px 16px 48px;min-width:var(--jcode-md-minw,0px)}
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
</style>$mermaidTags</head><body><div id="md"></div></body></html>"""
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

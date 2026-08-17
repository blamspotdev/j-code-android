package dev.jcode.workbench

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.ContextThemeWrapper
import android.view.MotionEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.R
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon
import dev.jcode.run.ProjectRunner
import java.text.DateFormat
import org.json.JSONArray
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

    /** A source file the page fetched on the Sources pane's behalf — see [BuiltinBrowser.sourceText]. */
    @JavascriptInterface
    fun source(text: String) {
        main.post { BuiltinBrowser.deliverSource(text) }
    }

    /** The Application pane's survey of the page's storage — see [BuiltinBrowser.appDump]. */
    @JavascriptInterface
    fun app(json: String) {
        main.post { BuiltinBrowser.deliverAppDump(json) }
    }

    @JavascriptInterface
    fun net(json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val entry = BrowserNetworkEntry(
            method = o.optString("method", "GET"),
            url = o.optString("url"),
            status = o.optInt("status", 0),
            durationMs = o.optLong("ms", 0),
            kind = o.optString("kind", "other"),
            bytes = o.optLong("bytes", -1),
            encodedBytes = o.optLong("enc", -1),
            mimeType = o.optString("mime"),
            failed = o.optBoolean("failed"),
            timingOnly = o.optBoolean("timing"),
            requestHeaders = headerPairs(o.optJSONArray("reqH")),
            requestBody = o.optString("reqB"),
            responseHeaders = headerPairs(o.optJSONArray("resH")),
            responseBody = o.optString("resB"),
            bodyTruncated = o.optBoolean("reqT") || o.optBoolean("resT"),
        )
        main.post { BuiltinBrowser.addNetwork(entry) }
    }

    /** `[["content-type","application/json"], ...]` as the shim sends it. */
    private fun headerPairs(array: JSONArray?): List<Pair<String, String>> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val p = array.optJSONArray(i) ?: return@mapNotNull null
            p.optString(0) to p.optString(1)
        }
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

    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    // Asked of the palette that is actually painting this tab, not of the phone: JCode's own theme
    // setting can differ from the device's, and the browser should be the colour of the window it is
    // in rather than the colour of the machine that window happens to be on.
    val pageIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pageBackground = MaterialTheme.colorScheme.background.toArgb()
    var menuOpen by remember { mutableStateOf(false) }
    var siteInfoOpen by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // One compact row. The bar was built out of default 48dp icon buttons over a field with 9dp
        // of its own padding, which is a phone browser's chrome on a pane that is already sharing a
        // screen with the editor, the tab strip and the workbench header — nearly sixty density-
        // independent pixels of frame around a page that is the entire point of the tab.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            IconButton(
                onClick = { webView?.goBack() },
                enabled = BuiltinBrowser.canGoBack.value,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = { webView?.goForward() },
                enabled = BuiltinBrowser.canGoForward.value,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = { if (BuiltinBrowser.loading.value) webView?.stopLoading() else webView?.reload() },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    if (BuiltinBrowser.loading.value) Icons.Rounded.Close else Icons.Rounded.Refresh,
                    contentDescription = if (BuiltinBrowser.loading.value) "Stop" else "Reload",
                    modifier = Modifier.size(16.dp),
                )
            }
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(7.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box {
                        val trust = trustOf(BuiltinBrowser.currentUrl.value)
                        IconButton(
                            onClick = { siteInfoOpen = true },
                            modifier = Modifier.size(26.dp),
                        ) {
                            Icon(
                                jcIcon(trust.icon),
                                contentDescription = "Site information: ${trust.summary}",
                                tint = trust.tint(),
                                modifier = Modifier.size(14.dp),
                            )
                        }
                        SiteInfoPanel(
                            expanded = siteInfoOpen,
                            onDismiss = { siteInfoOpen = false },
                            trust = trust,
                            webView = webView,
                        )
                    }
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
                        modifier = Modifier.weight(1f).padding(end = 8.dp, top = 6.dp, bottom = 6.dp),
                    )
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(30.dp)) {
                    Icon(
                        jcIcon(JCodeIcon.MoreVert),
                        contentDescription = "More browser options",
                        modifier = Modifier.size(17.dp),
                    )
                }
                BrowserMenu(
                    expanded = menuOpen,
                    onDismiss = { menuOpen = false },
                    webView = webView,
                    context = context,
                    onCopyUrl = { clipboard.setText(AnnotatedString(BuiltinBrowser.currentUrl.value)) },
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
            // Keyed on the theme, because a `WebView` reads its night mode from the configuration of
            // the context it was built with and there is no setter for it afterwards. A theme change
            // is rare and re-navigates to the same URL, which is a page that has to be re-rendered
            // anyway; the alternative is a browser that stays light until the tab is closed.
            key(pageIsDark) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                    val wv = BrowserWebView(webThemed(ctx, pageIsDark))
                    // The surface behind the page, which is what shows before it has painted. White
                    // regardless of the theme meant every navigation in a dark workbench flashed a
                    // full-pane white rectangle.
                    wv.setBackgroundColor(pageBackground)
                    // Follows the workbench's theme rather than the phone's, which is the promise the
                    // rest of this app makes: JCode's own theme setting can differ from the device's,
                    // and the browser inside it should be the colour of the window it is in. A site
                    // that handles `prefers-color-scheme` (via the themed context above) is told the
                    // truth and themes itself.
                    //
                    // Algorithmic darkening is deliberately NOT enabled. It force-darkens a page's DOM
                    // but cannot touch a `<canvas>`, so a self-theming app viewed in its LIGHT theme
                    // comes out wrong — Excalidraw's white canvas under a force-darkened toolbar and
                    // menu. Letting the page paint its own colours, which is what a mobile browser
                    // does, is the one rule that is right for both a light site and a dark one.
                    wv.settings.isAlgorithmicDarkeningAllowed = false
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
                    // Applied on creation, not only on the toggle: the WebView is destroyed whenever
                    // this tab is not the one on screen, so a mode set here has to be re-stated to
                    // every WebView after it or comparing two layouts means setting it each time.
                    if (BuiltinBrowser.desktopMode.value) {
                        wv.settings.userAgentString = desktopUserAgent(ctx)
                    }
                    wv.addJavascriptInterface(DevToolsBridge(), "JCodeDevTools")
                    // Claim the gesture the moment a finger lands on the page, or the workbench's
                    // navigation drawer takes any drag that has some sideways in it and slides itself
                    // open over the site. A page is full of things that answer a sideways drag — a
                    // carousel, a row of tabs, a map, and plain scrolling that is never perfectly
                    // vertical on a touchscreen — so the drawer was winning gestures that were never
                    // meant for it. Every other embedded surface here already says this: the editor,
                    // the terminal, the markdown preview, the extension hosts and the virtual
                    // device's screen. The browser was the one that did not.
                    wv.setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        // False: the WebView still handles the event itself. This only settles who
                        // *else* is allowed to take it away.
                        false
                    }
                    wv.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                            // Dropped at the start of every load: a heading showing the last site's
                            // mark beside this site's host is the one mistake this panel must not make.
                            BuiltinBrowser.favicon.value = favicon
                            BuiltinBrowser.loading.value = true
                            BuiltinBrowser.currentUrl.value = url
                            // Before the shim, so the new page's first requests survive the clear.
                            BuiltinBrowser.onNavigate()
                            view.evaluateJavascript(VIEWPORT_FIX_JS, null)
                            view.evaluateJavascript(NET_SHIM_JS, null)
                        }
                        override fun onPageFinished(view: WebView, url: String) {
                            BuiltinBrowser.loading.value = false
                            BuiltinBrowser.currentUrl.value = url
                            BuiltinBrowser.canGoBack.value = view.canGoBack()
                            BuiltinBrowser.canGoForward.value = view.canGoForward()
                            view.evaluateJavascript(VIEWPORT_FIX_JS, null)
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
                        override fun onReceivedIcon(view: WebView, icon: android.graphics.Bitmap?) {
                            BuiltinBrowser.favicon.value = icon
                        }
                        override fun onReceivedTitle(view: WebView, title: String?) {
                            BuiltinBrowser.title.value = title?.ifBlank { "Browser" } ?: "Browser"
                        }
                        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
                            BuiltinBrowser.addConsole(
                                BrowserConsoleEntry(
                                    level = msg.messageLevel().name.lowercase(),
                                    message = msg.message() ?: "",
                                    // The whole id, not just the file name: the Sources pane has to
                                    // match this against a real URL to jump to it, and a bare
                                    // "settings" matches nothing. The short form is a render-time
                                    // concern — see DevToolsPanel.
                                    source = msg.sourceId().orEmpty(),
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
}

/**
 * What the address bar's leading mark says about the connection.
 *
 * A padlock and not the site's own icon, which is what a favicon is: the question the slot answers
 * is whether what is on the screen came from where it says it did, and a picture the site supplied
 * cannot be evidence about the site. Browsers learned this the hard way — a favicon of a padlock was
 * a phishing kit's first move — and the favicon is in the panel's heading instead, next to the host,
 * where identifying is all it is being asked to do.
 */
private enum class SiteTrust(val icon: JCodeIcon, val summary: String, val detail: String) {
    Secure(
        JCodeIcon.Lock,
        "connection is secure",
        "Encrypted between this device and the site. What was sent cannot be read or changed on the way.",
    ),
    Insecure(
        JCodeIcon.LockOpen,
        "connection is not secure",
        "Sent in the clear over HTTP. Anything on the network between here and the server can read it " +
            "and change it — ordinary for a dev server on this machine, and worth noticing anywhere else.",
    ),
    Local(
        JCodeIcon.Files,
        "local file",
        "Loaded from this device rather than fetched over a network.",
    ),
    Blank(
        JCodeIcon.Browser,
        "no page loaded",
        "Nothing has been loaded into this tab yet.",
    );

    @Composable
    fun tint(): Color = when (this) {
        Secure -> MaterialTheme.colorScheme.primary
        Insecure -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
}

private fun trustOf(url: String): SiteTrust = when (Uri.parse(url).scheme?.lowercase()) {
    "https" -> SiteTrust.Secure
    "http" -> SiteTrust.Insecure
    "file", "content" -> SiteTrust.Local
    else -> SiteTrust.Blank
}

/**
 * What this one site is, is allowed to remember, and was served by.
 *
 * Everything here is about the *current origin*, which is what separates it from the overflow menu:
 * that one clears every site's cookies because it is a blunt instrument for starting again, and this
 * one clears the cookies of the thing being looked at, because a login you are debugging is not a
 * reason to sign out of everything else.
 *
 * The rows are the questions a browser is actually asked. What is this connection, who says so
 * (certificate), what has it stored on me (cookies, site data). Nothing about trackers, because
 * nothing here blocks any and a row claiming otherwise would be worse than no row.
 */
@Composable
private fun SiteInfoPanel(
    expanded: Boolean,
    onDismiss: () -> Unit,
    trust: SiteTrust,
    webView: WebView?,
) {
    val url = BuiltinBrowser.currentUrl.value
    val uri = remember(url) { Uri.parse(url) }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: url.ifBlank { "about:blank" }
    val origin = remember(url) { uri.scheme?.let { "$it://${uri.authority}" }.orEmpty() }

    // Re-read each time the panel opens: cookies and storage change under a page without anything
    // telling the composition, so a value remembered from last time is a value that is quietly wrong.
    var cookies by remember { mutableStateOf(0) }
    var storage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expanded, url) {
        if (!expanded) return@LaunchedEffect
        cookies = runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()
            ?.split(';')
            ?.count { it.isNotBlank() }
            ?: 0
        storage = null
        // Asked of the page rather than of WebStorage, whose `getOrigins` only knows about the
        // quota-managed APIs and answers "nothing" for the one storage anybody actually uses. A row
        // that says None whatever the site has stored is worse than no row: it is a wrong answer to
        // the question a dev opened this panel to ask.
        BuiltinBrowser.controller?.eval(STORAGE_COUNT_JS) { result ->
            val counts = result.trim('"').split('|')
            val local = counts.getOrNull(0)?.toIntOrNull() ?: 0
            val session = counts.getOrNull(1)?.toIntOrNull() ?: 0
            storage = when {
                local == 0 && session == 0 -> "None"
                session == 0 -> "$local in localStorage"
                local == 0 -> "$session in sessionStorage"
                else -> "$local local, $session session"
            }
        } ?: run { storage = "None" }
    }

    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp).width(260.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BuiltinBrowser.favicon.value?.let { icon ->
                    Image(
                        bitmap = icon.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.size(16.dp).padding(end = 6.dp),
                    )
                }
                Text(host, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(
                modifier = Modifier.padding(top = 8.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    jcIcon(trust.icon),
                    contentDescription = null,
                    tint = trust.tint(),
                    modifier = Modifier.size(15.dp).padding(top = 2.dp),
                )
                Column(modifier = Modifier.padding(start = 8.dp)) {
                    Text(
                        trust.summary.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = trust.tint(),
                    )
                    Text(
                        trust.detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            // Who says so. Only https has an answer, and it is the answer a padlock is shorthand for.
            webView?.certificate?.let { cert ->
                SiteInfoRow("Issued to", cert.issuedTo?.cName?.ifBlank { host } ?: host)
                SiteInfoRow("Issued by", cert.issuedBy?.oName?.ifBlank { "—" } ?: "—")
                SiteInfoRow("Expires", cert.validNotAfterDate?.let { DateFormat.getDateInstance().format(it) } ?: "—")
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SiteInfoRow("Cookies", if (cookies == 0) "None" else "$cookies for this site")
            SiteInfoRow("Site data", storage ?: "Reading…")
            TextButton(
                onClick = {
                    // This origin only. The overflow menu is where "forget everything" lives.
                    runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                        ?.split(';')
                        ?.mapNotNull { it.substringBefore('=').trim().takeIf(String::isNotBlank) }
                        ?.forEach { name ->
                            // Expiring it in the past is how a cookie is deleted; there is no
                            // per-site remove in CookieManager.
                            CookieManager.getInstance()
                                .setCookie(url, "$name=; Max-Age=0; Path=/")
                        }
                    CookieManager.getInstance().flush()
                    if (origin.isNotBlank()) runCatching { WebStorage.getInstance().deleteOrigin(origin) }
                    // The same two the row counts, or the button would clear something else.
                    BuiltinBrowser.controller?.eval(STORAGE_CLEAR_JS) {}
                    cookies = 0
                    storage = "None"
                    webView?.reload()
                    onDismiss()
                },
                enabled = trust != SiteTrust.Blank,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Text("Clear this site's data", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun SiteInfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(74.dp),
        )
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Everything the toolbar has no room for, behind one button.
 *
 * The bar carries what is pressed on the way somewhere — back, forward, reload, the address. These
 * are the ones pressed *about* a page, which is a different frequency: a person clears site data
 * once an afternoon and would still rather it were two taps away than a paragraph in a README.
 *
 * They are the ones a **built-in** browser is for, too. This one exists to look at a dev server on
 * the machine it is being written on, so the list is what that job asks for and not what a phone
 * browser ships: how the server sees this client, what it is allowed to remember, and the two ways
 * out of here — the system's own browser, and DevTools.
 */
@Composable
private fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    webView: WebView?,
    context: android.content.Context,
    onCopyUrl: () -> Unit,
) {
    val desktop = BuiltinBrowser.desktopMode.value
    CompactContextMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        listActions = listOf(
            ContextAction(
                icon = JCodeIcon.Preview,
                // The action, not the state: a row that reads "Desktop site" with no tick beside it
                // is a question about which of the two it is telling you.
                label = if (desktop) "Request mobile site" else "Request desktop site",
                enabled = webView != null,
            ) {
                BuiltinBrowser.desktopMode.value = !desktop
                webView?.let { wv ->
                    wv.settings.userAgentString = if (desktop) null else desktopUserAgent(context)
                    // The server was told the old thing; only a fresh request unsays it.
                    wv.reload()
                }
            },
            ContextAction(
                icon = JCodeIcon.Refresh,
                label = "Reload without cache",
                enabled = webView != null,
            ) {
                // The one a dev server asks for by the hour: a preview that keeps serving last
                // build's bundle looks exactly like a change that did not work.
                webView?.clearCache(true)
                webView?.reload()
            },
            ContextAction(
                icon = JCodeIcon.Delete,
                label = "Clear cookies and site data",
                destructive = true,
                enabled = webView != null,
            ) {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
                webView?.clearCache(true)
                // Reloaded so the clearing is something you can see happen. Nothing about a cleared
                // localStorage shows on a page that is still the one it was drawn from.
                webView?.reload()
            },
            ContextAction(icon = JCodeIcon.Copy, label = "Copy URL", onClick = onCopyUrl),
            ContextAction(icon = JCodeIcon.Open, label = "Open in system browser") {
                ProjectRunner.openInBrowser(context, BuiltinBrowser.currentUrl.value)
            },
            ContextAction(icon = JCodeIcon.DevTools, label = "DevTools") {
                // The same signal a preview sends, which is what reveals the drawer panel; the
                // browser tab it also focuses is the one already in front.
                BuiltinBrowser.requestOpen()
            },
        ),
    )
}

/**
 * The device's own user agent, said the way a desktop says it.
 *
 * Composed from [WebSettings.getDefaultUserAgent] rather than written down, so the engine version in
 * it is the version actually rendering the page — a made-up Chrome number is a lie a server can act
 * on, and this browser exists to show what a server does. That static is also why the mobile string
 * can be recovered: it answers with the device default however many times the setting has been
 * overwritten, which reading the setting back would not.
 */
private fun desktopUserAgent(context: android.content.Context): String {
    val mobile = runCatching { WebSettings.getDefaultUserAgent(context) }.getOrDefault("")
    val chrome = Regex("Chrome/([\\d.]+)").find(mobile)?.groupValues?.getOrNull(1) ?: "120.0.0.0"
    return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
        "Chrome/$chrome Safari/537.36"
}

/** How much this origin is keeping, counted where it is actually kept. Guarded: a page on `file://`
 *  or with storage blocked throws on the property access rather than returning empty. */
private const val STORAGE_COUNT_JS =
    "(function(){var l=0,s=0;try{l=localStorage.length}catch(e){};try{s=sessionStorage.length}catch(e){};return l+'|'+s})()"

private const val STORAGE_CLEAR_JS =
    "(function(){try{localStorage.clear()}catch(e){};try{sessionStorage.clear()}catch(e){};return 1})()"

/**
 * Repair for a WebView quirk measured on the AYN Odin2's WebView (Chromium 101, and still there on
 * 150): CSS viewport-length units — `vh`, `dvh`, `svh`, `lvh` and their `vw` counterparts — resolve
 * to ~0 on a top-level page, while `window.innerHeight`, `documentElement.clientHeight`, percentage
 * heights and `position:fixed` offsets all report and use the real size. Measured on-device:
 * `innerHeight` 631, `clientHeight` 632, `height:100%` (even on a fixed element) 632, but
 * `100vh`/`100dvh`/`100svh`/`100lvh` all 0. So the layout size is fine; it is only the viewport
 * *unit* that is wrong. Anything sized off `100vh` then collapses — a full-screen canvas SPA, and the
 * case this was extended for: Excalidraw's mobile menu and modals shrank to a sliver. The same zero
 * height reaches `@media (min-/max-height)` and `orientation` too, so a height-gated block matches as
 * if the screen were 0px tall — which is why Excalidraw's empty-canvas welcome screen never appears.
 *
 * The site cannot be edited, so this: (1) exposes the real `1vh`/`1vw` as the pixel custom properties
 * `--jcode-vh`/`--jcode-vw`, kept in step on resize; (2) rewrites every `vh`/`vw`-family unit in the
 * page's own CSS — stylesheets, grouped `@media`/`@supports` rules, inline styles and custom
 * properties — to `calc(var(--jcode-vh) * N)`, which was verified on-device to resolve correctly
 * where the bare unit does not; (3) pins `<html>` to `innerHeight` as a definite root for any
 * `height:100%` chain; (4) re-evaluates height/orientation `@media` conditions against the real
 * `innerHeight`/`innerWidth` and pins each rule to that result, since the engine measures those
 * queries against the same zero height. A `MutationObserver` and a short re-probe catch stylesheets
 * and nodes added after first paint (a modal mounting on demand), and it fires one `resize` on arming
 * so a page that already laid out against the broken unit recomputes.
 *
 * It arms only when the bug is present (a `100vh` probe measures under half of `innerHeight`), so on
 * a healthy WebView it never touches the page.
 */
private const val VIEWPORT_FIX_JS = """
(function(){
  if (window.__jcodeVPFix) return; window.__jcodeVPFix = true;
  var d = document, root = d.documentElement, armed = false, mo = null, seen = null;
  try { seen = new WeakSet(); } catch (e) { seen = null; }

  var HASU = /[\d.](?:dvh|svh|lvh|vh|dvw|svw|lvw|vw|vmin|vmax)\b/i;
  var UNIT = /(-?[\d.]+)(dvh|svh|lvh|vh|dvw|svw|lvw|vw|vmin|vmax)\b/gi;
  function repl(v){
    return v.replace(UNIT, function(_, n, u){
      u = u.toLowerCase();
      if (u === 'vmin') return 'calc(min(var(--jcode-vw), var(--jcode-vh)) * ' + n + ')';
      if (u === 'vmax') return 'calc(max(var(--jcode-vw), var(--jcode-vh)) * ' + n + ')';
      var w = u.charAt(u.length - 1) === 'w';
      return 'calc(var(' + (w ? '--jcode-vw' : '--jcode-vh') + ') * ' + n + ')';
    });
  }
  // Rewrite every property whose value carries a viewport unit — iterate by index so custom
  // properties and every longhand are covered, and keep each declaration's !important priority.
  function fixDecl(s){
    if (!s || !s.length) return;
    for (var i = 0; i < s.length; i++){
      var p = s[i], v = s.getPropertyValue(p);
      if (v && HASU.test(v)) { try { s.setProperty(p, repl(v), s.getPropertyPriority(p)); } catch (e) {} }
    }
  }
  function walk(rules){
    if (!rules) return;
    for (var i = 0; i < rules.length; i++){
      var r = rules[i];
      if (r.media && r.media.mediaText != null) fixMedia(r);   // @media height/orientation gates
      if (r.style) fixDecl(r.style);
      if (r.cssRules) walk(r.cssRules);   // @media / @supports / @container
    }
  }
  function fixSheet(sh){
    if (seen && seen.has(sh)) return;
    var rules; try { rules = sh.cssRules; } catch (e) { return; }  // cross-origin / not yet loaded: retry later
    if (!rules) return;
    walk(rules);
    if (seen) seen.add(sh);
  }
  function fixSheets(){ var ss = d.styleSheets; for (var i = 0; i < ss.length; i++) fixSheet(ss[i]); }
  function fixInline(scope){
    var els; try { els = (scope || d).querySelectorAll('[style*="vh"],[style*="vw"]'); } catch (e) { return; }
    for (var i = 0; i < els.length; i++) fixDecl(els[i].style);
  }
  function setVars(){
    root.style.setProperty('--jcode-vh', (window.innerHeight / 100) + 'px');
    root.style.setProperty('--jcode-vw', (window.innerWidth / 100) + 'px');
  }
  function pin(){ root.style.setProperty('height', window.innerHeight + 'px', 'important'); }

  // Media queries carry the same disease as the units: this WebView evaluates the viewport HEIGHT as 0
  // for `@media (min-/max-height)` and for `orientation` (width is fine). So a height-gated block — and
  // Excalidraw hides its empty-canvas welcome screen behind `@media (max-height: ...)` — matches as if
  // the screen were 0px tall and vanishes. The engine won't say what it measures a query against, so
  // re-evaluate each height/orientation feature against the REAL innerHeight/innerWidth and pin the rule
  // to that result (what it would do on a real phone of this size). Done ONCE per rule and never again:
  // re-mutating a page's `mediaText` on the resize a rotation fires wedged Excalidraw into a blank canvas
  // that only a reload cleared, and the visual-viewport pin already carries the layout across a rotation.
  // The trade-off is that a rotated window keeps the media verdict from the orientation it loaded in.
  var mediaOrig = null; try { mediaOrig = new WeakMap(); } catch (e) { mediaOrig = null; }
  function forceCond(pass){ return pass ? '(min-width: 0px)' : '(min-width: 99999px)'; }
  function rewriteMedia(mt){
    var ih = window.innerHeight, iw = window.innerWidth;
    var out = mt.replace(/\(\s*(min-|max-)?height\s*:\s*([\d.]+)px\s*\)/gi, function(_, pre, num){
      var n = parseFloat(num);
      return forceCond(pre === 'min-' ? ih >= n : pre === 'max-' ? ih <= n : Math.abs(ih - n) < 1);
    });
    out = out.replace(/\(\s*orientation\s*:\s*(portrait|landscape)\s*\)/gi, function(_, o){
      var portrait = ih >= iw;
      return forceCond(o.toLowerCase() === 'portrait' ? portrait : !portrait);
    });
    return out;
  }
  function fixMedia(rule){
    var m = rule.media; if (!m || m.mediaText == null) return;
    if (mediaOrig && mediaOrig.has(rule)) return;   // rewrite each rule once; never re-touch on resize
    var orig = m.mediaText; if (mediaOrig) mediaOrig.set(rule, orig);
    if (!/height|orientation/i.test(orig)) return;
    var neu = rewriteMedia(orig);
    if (neu !== m.mediaText) { try { m.mediaText = neu; } catch (e) {} }
  }

  function broken(){
    if (!(window.innerHeight > 0)) return false;
    var p = d.createElement('div');
    p.style.cssText = 'position:fixed;left:-9999px;top:0;width:1px;height:100vh;visibility:hidden;pointer-events:none';
    (d.body || root).appendChild(p);
    var z = p.getBoundingClientRect().height;
    p.remove();
    return z < window.innerHeight * 0.5;
  }
  function onArm(){
    setVars(); pin(); fixSheets(); fixInline();
    try {
      mo = new MutationObserver(function(muts){
        var reSheet = false;
        for (var i = 0; i < muts.length; i++){
          var a = muts[i].addedNodes; if (!a) continue;
          for (var j = 0; j < a.length; j++){
            var nd = a[j]; if (!nd || nd.nodeType !== 1) continue;
            var tag = nd.tagName;
            if (tag === 'STYLE' || tag === 'LINK') { reSheet = true; if (tag === 'LINK') { try { nd.addEventListener('load', fixSheets); } catch (e) {} } }
            if (nd.getAttribute) { var sv = nd.getAttribute('style'); if (sv && (sv.indexOf('vh') !== -1 || sv.indexOf('vw') !== -1)) fixDecl(nd.style); }
            if (nd.querySelectorAll) fixInline(nd);
          }
        }
        if (reSheet) fixSheets();
      });
      mo.observe(root, { childList: true, subtree: true });
    } catch (e) {}
    try { window.dispatchEvent(new Event('resize')); } catch (e) {}
  }
  function tryArm(){ if (armed) return true; if (broken()) { armed = true; onArm(); } return armed; }

  d.addEventListener('DOMContentLoaded', tryArm);
  window.addEventListener('load', tryArm);
  window.addEventListener('resize', function(){ if (armed) { setVars(); pin(); } });
  // The re-pin + `resize` each tick are a RECOVERY, not churn: this device's WebView returns from a
  // reload with a black, uncomposited content surface on a minority of loads (measured — even a
  // position:fixed probe fails to paint), and re-applying the pin + nudging the page to re-render once
  // it has mounted forces a fresh frame that unsticks it. Removing these MORE than doubled the black
  // rate in testing, so they run for a longer window here to catch a late-mounting SPA. The resize goes
  // only through the (media-free) resize handler, so it cannot re-trigger the rotation blank.
  var n = 0, t = setInterval(function(){ tryArm(); if (armed) { setVars(); pin(); fixSheets(); fixInline(); try { window.dispatchEvent(new Event('resize')); } catch (e) {} } if (++n > 45) clearInterval(t); }, 200);
  tryArm();
})();
"""

/**
 * The same context, dressed so a `WebView` built from it agrees with the workbench about dark.
 *
 * The attribute that decides this is **`android:isLightTheme`**, read off the context's theme — not
 * the night bits of the configuration, which is the obvious guess and is wrong. Measured: with the
 * configuration forced to `UI_MODE_NIGHT_YES` and algorithmic darkening allowed, the page still
 * answered `false` to `prefers-color-scheme: dark`, because JCode's runtime theme has a Light parent
 * and that is what the WebView was asking.
 *
 * The configuration is set as well, for anything else in there that reads the night bits, but the
 * theme is the part that does the work.
 */
private fun webThemed(context: Context, dark: Boolean): Context {
    val night = if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
    val configuration = Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    }
    val based = runCatching { context.createConfigurationContext(configuration) }.getOrDefault(context)
    return ContextThemeWrapper(
        based,
        if (dark) R.style.Theme_JCode_Web_Dark else R.style.Theme_JCode_Web_Light,
    )
}

/**
 * What feeds the Network panel. Injected on each page load, guarded so it installs once.
 *
 * Two mechanisms, because no single one sees everything (see [BrowserNetworkEntry]):
 *
 *  - `fetch` and `XMLHttpRequest` are wrapped, which is the only way to get at a payload or a
 *    response body — those exist as JS values for exactly as long as the call that made them, and
 *    a response can only be read once, so the wrapper `clone()`s it and reads the copy.
 *  - a `PerformanceObserver` on resource timings reports what the browser fetched by itself: the
 *    document, scripts, stylesheets, images, fonts. `buffered: true` replays the entries recorded
 *    before this script ran, so a shim that installs at `onPageFinished` still lists the whole load.
 *
 * The overlap between them is settled by [t0]: a fetch/XHR timing entry that started after the
 * wrappers went in is the wrappers' to report, and reporting it here as well would draw one request
 * on two lines. The ones that started earlier are reported here, without a body, because a row that
 * only knows the URL still beats a request that silently never happened.
 *
 * Bodies are capped at 16 KB and skipped for non-text and very large responses. A DevTools panel
 * that holds a page's images in memory as strings is a memory leak with a nice UI.
 */
private const val NET_SHIM_JS = """
(function(){
  if (window.__jcodeNetT0 !== undefined) return;
  var t0 = performance.now(); window.__jcodeNetT0 = t0;
  var CAP = 16384, BIG = 2097152;
  function post(o){ try{ JCodeDevTools.net(JSON.stringify(o)); }catch(e){} }
  function textual(ct){ ct=String(ct||'').toLowerCase();
    return !ct || /json|text|xml|javascript|urlencoded|html|csv|graphql/.test(ct); }
  function cap(s){ s=(s==null)?'':String(s); return s.length>CAP ? [s.slice(0,CAP),true] : [s,false]; }
  function pairs(h){ var o=[]; try{
      if(!h) return o;
      if(Array.isArray(h)){ h.forEach(function(p){ o.push([String(p[0]),String(p[1])]); }); return o; }
      if(typeof h.forEach==='function'){ h.forEach(function(v,k){ o.push([String(k),String(v)]); }); return o; }
      Object.keys(h).forEach(function(k){ o.push([k,String(h[k])]); });
    }catch(e){} return o; }
  function rawPairs(t){ var o=[]; String(t||'').split(/\r?\n/).forEach(function(l){
      var i=l.indexOf(':'); if(i>0) o.push([l.slice(0,i).trim(), l.slice(i+1).trim()]); }); return o; }
  function bodyOf(b){ try{
      if(b==null) return '';
      if(typeof b==='string') return b;
      if(typeof URLSearchParams!=='undefined' && b instanceof URLSearchParams) return b.toString();
      if(typeof FormData!=='undefined' && b instanceof FormData){ var a=[];
        b.forEach(function(v,k){ a.push(k+'='+(typeof v==='string'?v:'[file]')); }); return a.join('&'); }
      if(typeof Blob!=='undefined' && b instanceof Blob) return '[Blob '+b.size+' bytes]';
      if(b.byteLength!==undefined) return '[binary '+b.byteLength+' bytes]';
      return String(b);
    }catch(e){ return ''; } }

  var of = window.fetch;
  if (of) window.fetch = function(){
    var a=arguments, t=performance.now(), req=a[0], init=a[1]||{}, url, m, rh;
    try{
      if(req && typeof req==='object' && req.url){ url=req.url; m=init.method||req.method||'GET'; rh=pairs(init.headers||req.headers); }
      else { url=String(req); m=init.method||'GET'; rh=pairs(init.headers); }
    }catch(e){ url=String(req); m='GET'; rh=[]; }
    var rb=cap(bodyOf(init.body));
    return of.apply(this,a).then(function(r){
      var ct='', len=0;
      try{ ct=r.headers.get('content-type')||''; len=parseInt(r.headers.get('content-length')||'0',10)||0; }catch(e){}
      var o={kind:'fetch',method:m,url:url,status:r.status,ms:Math.round(performance.now()-t),
             bytes:len||-1,mime:ct,reqH:rh,reqB:rb[0],reqT:rb[1],resH:pairs(r.headers)};
      if(!textual(ct) || len>BIG){ post(o); return r; }
      var c; try{ c=r.clone(); }catch(e){ post(o); return r; }
      c.text().then(function(tx){ var b=cap(tx); o.resB=b[0]; o.resT=b[1];
                                  if(o.bytes<0) o.bytes=tx.length; post(o); },
                    function(){ post(o); });
      return r;
    }).catch(function(e){
      post({kind:'fetch',method:m,url:url,status:0,ms:Math.round(performance.now()-t),
            failed:true,reqH:rh,reqB:rb[0],reqT:rb[1]});
      throw e;
    });
  };

  var xo=XMLHttpRequest.prototype.open, xs=XMLHttpRequest.prototype.send,
      xh=XMLHttpRequest.prototype.setRequestHeader;
  XMLHttpRequest.prototype.open=function(m,u){ this.__m=m; this.__u=u; this.__h=[]; return xo.apply(this,arguments); };
  XMLHttpRequest.prototype.setRequestHeader=function(k,v){
    try{ (this.__h=this.__h||[]).push([String(k),String(v)]); }catch(e){} return xh.apply(this,arguments); };
  XMLHttpRequest.prototype.send=function(b){
    var s=this, t=performance.now(), rb=cap(bodyOf(b));
    s.addEventListener('loadend', function(){
      var ct='', tx='';
      try{ ct=s.getResponseHeader('content-type')||''; }catch(e){}
      try{ if(!s.responseType||s.responseType==='text') tx=String(s.responseText||'');
           else if(s.responseType==='json') tx=JSON.stringify(s.response); }catch(e){}
      var cb=cap(tx);
      post({kind:'xhr',method:s.__m||'GET',url:String(s.__u),status:s.status||0,
            ms:Math.round(performance.now()-t),failed:!s.status,mime:ct,bytes:tx.length||-1,
            reqH:s.__h||[],reqB:rb[0],reqT:rb[1],
            resH:rawPairs(s.getAllResponseHeaders?s.getAllResponseHeaders():''),resB:cb[0],resT:cb[1]});
    });
    return xs.apply(this,arguments);
  };

  var TYPE={link:'css',css:'css',script:'script',img:'img',image:'img',imageset:'img',
            input:'img',font:'font',audio:'media',video:'media',track:'media',
            iframe:'document',frame:'document',navigation:'document'};
  function res(e){
    var it=e.initiatorType||'other';
    if((it==='fetch'||it==='xmlhttprequest') && e.startTime>=t0) return;
    // encodedBodySize alongside transferSize is what separates "came from the cache" (nothing on
    // the wire, but the body's size is known) from "cross-origin without Timing-Allow-Origin"
    // (both zero because the server declined to say). Both read 0 B otherwise, which looks like
    // one fact and is two.
    post({kind:(it==='fetch'?'fetch':it==='xmlhttprequest'?'xhr':(TYPE[it]||'other')),
          method:'GET',url:e.name,status:0,ms:Math.round(e.duration),
          bytes:(e.transferSize===undefined?-1:e.transferSize),
          enc:(e.encodedBodySize===undefined?-1:e.encodedBodySize),timing:true});
  }
  try{ new PerformanceObserver(function(l){ l.getEntries().forEach(res); }).observe({type:'resource',buffered:true}); }
  catch(e){ try{ performance.getEntriesByType('resource').forEach(res); }catch(e2){} }

  function nav(){ try{
      var n=performance.getEntriesByType('navigation')[0]; if(!n) return;
      post({kind:'document',method:'GET',url:n.name,status:0,ms:Math.round(n.duration),
            bytes:(n.transferSize===undefined?-1:n.transferSize),timing:true});
    }catch(e){} }
  // A navigation entry's duration runs to loadEventEnd, which is not set until the load handlers
  // have all returned — so reading it from inside one reports 0. A tick later it is settled.
  if(document.readyState==='complete') nav(); else addEventListener('load', function(){ setTimeout(nav,0); });
})();
"""

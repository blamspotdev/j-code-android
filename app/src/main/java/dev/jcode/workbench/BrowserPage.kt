package dev.jcode.workbench

import android.net.Uri
import android.view.MotionEvent
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon
import dev.jcode.run.ProjectRunner
import dev.jcode.webengine.WebEngine
import dev.jcode.webengine.WebEngineEvents
import dev.jcode.webengine.WebEngineHost
import dev.jcode.webengine.WebEnginePlaceholder
import dev.jcode.webengine.WebEngineTab

/**
 * JCode's built-in browser, shown as a full-screen editor page: address bar + back/forward/reload
 * over a page rendered by **JCode's own web engine** — the `:webengine` split (GeckoView),
 * delivered by the Web Engine marketplace extension. When the engine isn't installed the page is
 * an install prompt; there is deliberately no fallback to the device's system WebView, whose
 * version the app can neither choose nor trust.
 *
 * State lives in [BuiltinBrowser] so DevTools, which sits in a different part of the tree, can
 * observe it. The WebView-era DevTools shims don't apply to the engine — the panes show an engine
 * notice until the Gecko RDP client lands (see the Web Engine extension's roadmap).
 */
@Composable
fun BrowserPage(modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val context = LocalContext.current
    val engine = remember { WebEngineHost.get(context) }

    var tab by remember { mutableStateOf<WebEngineTab?>(null) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf(BuiltinBrowser.currentUrl.value) }
    // Follow the page URL in the address bar, except while the user is editing it.
    LaunchedEffect(BuiltinBrowser.currentUrl.value) {
        if (!editing) address = BuiltinBrowser.currentUrl.value
    }
    // Drive navigations requested from elsewhere (openBrowserPage / previews) once the tab exists.
    LaunchedEffect(tab) {
        val t = tab ?: return@LaunchedEffect
        snapshotFlow { BuiltinBrowser.pendingUrl.value }.collect { url ->
            if (url != null) {
                t.navigate(url)
                BuiltinBrowser.currentUrl.value = url
                BuiltinBrowser.pendingUrl.value = null
            }
        }
    }
    // The engine renders pages the colour of the window they are in — JCode's own theme setting,
    // not the device's. Engine-wide, so it also holds for future engine-backed surfaces.
    val pageIsDark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    LaunchedEffect(engine, pageIsDark) { engine?.setColorScheme(pageIsDark) }
    DisposableEffect(Unit) {
        onDispose {
            BuiltinBrowser.controller = null
            BuiltinBrowser.engineBacked.value = false
            tab?.dispose()
            tab = null
        }
    }

    fun go(raw: String) {
        editing = false
        focus.clearFocus()
        val url = BuiltinBrowser.normalizeUrl(raw)
        tab?.navigate(url)
        BuiltinBrowser.currentUrl.value = url
    }

    val clipboard = LocalClipboardManager.current
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
                onClick = { tab?.goBack() },
                enabled = BuiltinBrowser.canGoBack.value,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = { tab?.goForward() },
                enabled = BuiltinBrowser.canGoForward.value,
                modifier = Modifier.size(30.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowForward, contentDescription = "Forward", modifier = Modifier.size(17.dp))
            }
            IconButton(
                onClick = { if (BuiltinBrowser.loading.value) tab?.stopLoading() else tab?.reload() },
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
                            engine = engine,
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
                    engine = engine,
                    tab = tab,
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

        if (engine == null) {
            WebEnginePlaceholder(surface = "browser", modifier = Modifier.fillMaxSize())
        } else {
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    val t = engine.createTab(ctx, browserEvents())
                    // Claim the gesture the moment a finger lands on the page, or the workbench's
                    // navigation drawer takes any drag that has some sideways in it and slides
                    // itself open over the site. Every embedded surface here says this: the editor,
                    // the terminal, the markdown preview, the extension hosts, the virtual device.
                    t.view.setOnTouchListener { v, event ->
                        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                        }
                        // False: the engine still handles the event itself. This only settles who
                        // *else* is allowed to take it away.
                        false
                    }
                    t.setDesktopMode(BuiltinBrowser.desktopMode.value)
                    BuiltinBrowser.engineBacked.value = true
                    BuiltinBrowser.controller = object : BrowserController {
                        override fun navigate(url: String) = t.navigate(BuiltinBrowser.normalizeUrl(url))
                        override fun goBack() = t.goBack()
                        override fun goForward() = t.goForward()
                        override fun reload() = t.reload()
                        override fun stop() = t.stopLoading()
                        // The engine exposes no page eval — the WebView-era DevTools shims don't
                        // apply. The RDP-based DevTools replaces this; until then callers get null.
                        override fun eval(script: String, onResult: (String) -> Unit) = onResult("null")
                    }
                    // Restore the last page when the tab is re-composed; the pending-URL effect
                    // drives brand-new navigations requested while the tab wasn't on screen.
                    t.navigate(BuiltinBrowser.currentUrl.value.ifBlank { "about:blank" })
                    tab = t
                    t.view
                },
            )
        }
    }
}

/** Wires engine callbacks into [BuiltinBrowser]'s observable state. */
private fun browserEvents(): WebEngineEvents = object : WebEngineEvents {
    override fun onUrlChange(url: String) {
        BuiltinBrowser.currentUrl.value = url
        // A navigation replaces what the DevTools logs describe — same contract as before.
        BuiltinBrowser.onNavigate()
    }

    override fun onTitleChange(title: String) {
        BuiltinBrowser.title.value = title.ifBlank { "Browser" }
    }

    override fun onProgress(progress: Int) {
        BuiltinBrowser.progress.value = progress
    }

    override fun onLoadingChange(loading: Boolean) {
        BuiltinBrowser.loading.value = loading
    }

    override fun onNavigationState(canGoBack: Boolean, canGoForward: Boolean) {
        BuiltinBrowser.canGoBack.value = canGoBack
        BuiltinBrowser.canGoForward.value = canGoForward
    }

    override fun onSecurityChange(secure: Boolean) {
        // The address bar's trust mark derives from the URL scheme; nothing extra to store yet.
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
 * What this one site is and what the connection to it means.
 *
 * Leaner than its WebView-era ancestor by necessity and honesty both: cookie counts, per-origin
 * storage numbers and certificate fields were read from the *system WebView's* jar and page —
 * the wrong jar entirely for the engine now rendering. A number from the wrong store is worse
 * than no number. Per-site rows return when the engine's storage API is plumbed through the
 * seam; until then the panel says what it knows to be true.
 */
@Composable
private fun SiteInfoPanel(
    expanded: Boolean,
    onDismiss: () -> Unit,
    trust: SiteTrust,
    engine: WebEngine?,
) {
    val url = BuiltinBrowser.currentUrl.value
    val uri = remember(url) { Uri.parse(url) }
    val host = uri.host?.takeIf { it.isNotBlank() } ?: url.ifBlank { "about:blank" }

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
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            SiteInfoRow("Engine", engine?.label ?: "not installed")
            SiteInfoRow("Site data", "kept by the engine — clear from the ⋮ menu")
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
 */
@Composable
private fun BrowserMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    engine: WebEngine?,
    tab: WebEngineTab?,
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
                enabled = tab != null,
            ) {
                BuiltinBrowser.desktopMode.value = !desktop
                // The engine reloads as part of switching modes; the server was told the old
                // thing, and only a fresh request unsays it.
                tab?.setDesktopMode(!desktop)
            },
            ContextAction(
                icon = JCodeIcon.Refresh,
                label = "Reload without cache",
                enabled = tab != null,
            ) {
                // The one a dev server asks for by the hour: a preview that keeps serving last
                // build's bundle looks exactly like a change that did not work.
                tab?.reloadBypassingCache()
            },
            ContextAction(
                icon = JCodeIcon.Delete,
                label = "Clear cookies and site data",
                destructive = true,
                enabled = engine != null && tab != null,
            ) {
                engine?.clearBrowsingData()
                // Reloaded so the clearing is something you can see happen. Nothing about a cleared
                // localStorage shows on a page that is still the one it was drawn from.
                tab?.reload()
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

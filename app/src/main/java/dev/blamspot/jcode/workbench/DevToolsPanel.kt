package dev.blamspot.jcode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.material3.Icon
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.luminance
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.editor.TokenPalette
import dev.blamspot.jcode.feature.marketplace.LanguagePack
import dev.blamspot.jcode.lsp.SemanticToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.blamspot.jcode.design.CompactContextMenu
import dev.blamspot.jcode.design.ContextAction
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.LocalTerminalFontSizeSetting
import dev.blamspot.jcode.design.JCodeTheme
import dev.blamspot.jcode.design.ManagerFilterChip
import dev.blamspot.jcode.design.jcIcon
import org.json.JSONTokener
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.background
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.lazy.itemsIndexed
import org.json.JSONArray
import org.json.JSONObject

private enum class DevToolsPane(val label: String) {
    Console("Console"),
    Sources("Sources"),
    Network("Network"),
    Application("Application"),
    Elements("Elements"),
}

/** Where a console line came from, once it is something you can go to rather than read. */
private data class SourceJump(val url: String, val line: Int)

/**
 * The built-in browser's DevTools, shown in the right drawer while the in-app browser is in use.
 * Reads [BuiltinBrowser] directly (it lives in a different part of the tree).
 *
 * Not a real Chrome DevTools — a WebView exposes no debugger protocol, so there is no stepping, no
 * breakpoints and no live DOM editing, and there is no honest way to pretend otherwise. What there
 * is, is everything reachable by asking the page: a Console with a JS REPL, Sources (the document,
 * inline scripts, and external files the page re-fetches for us), a Network log from an injected
 * fetch/XHR shim, Application (storage and cookies, per key), and a refreshable read-only Elements
 * snapshot.
 *
 * Console and Sources are joined: a message's `source:line` is a link, and following it opens the
 * file at that line. That is the loop a console exists to close — the alternative is reading
 * "settings:1" and then going to find settings line 1 yourself.
 */
@Composable
fun DevtoolsSidebarContent(modifier: Modifier = Modifier) {
    val support = LocalDevToolsCodeSupport.current
    val packResolver = support.packResolver
    val semanticTokens = support.semanticTokens
    var pane by remember { mutableStateOf(DevToolsPane.Console) }
    // Set by a console line's source link and consumed by the Sources pane, which is the whole of
    // "click the thing that says where it went wrong and end up there".
    var jump by remember { mutableStateOf<SourceJump?>(null) }
    Column(modifier = modifier.fillMaxSize()) {
        // A tab strip, the way this app already draws tab strips — the editor's and the terminal's
        // are flat, butt against each other, and mark the active one by lifting it to
        // `surfaceVariant` off a `surface` rail. These were rounded pills with gaps between them,
        // which is a different component making the same claim, and two idioms for "pick one of
        // these" in one window is one too many. Same 36dp, same padding, same label style.
        Surface(color = MaterialTheme.colorScheme.surface) {
            Row(
                modifier = Modifier.fillMaxWidth().height(36.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Scrollable, because five panes do not fit across a phone and the alternative is
                // five tabs squeezed until none of them can be read.
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(Space.none),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    DevToolsPane.entries.forEach { p ->
                        val selected = p == pane
                        Box(
                            modifier = Modifier
                                .clickable { pane = p }
                                .background(
                                    if (selected) {
                                        MaterialTheme.colorScheme.surfaceVariant
                                    } else {
                                        Color.Transparent
                                    },
                                )
                                .fillMaxHeight()
                                .padding(horizontal = Space.md),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = p.label,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
                if (pane != DevToolsPane.Sources && pane != DevToolsPane.Elements) {
                    PaneMenuButton(pane)
                }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        when (pane) {
            DevToolsPane.Console -> ConsolePane(
                onOpenSource = { url, line ->
                    jump = SourceJump(url, line)
                    pane = DevToolsPane.Sources
                },
                modifier = Modifier.weight(1f),
            )
            DevToolsPane.Sources -> SourcesPane(
                jump = jump,
                onJumpConsumed = { jump = null },
                packResolver = packResolver,
                semanticTokens = semanticTokens,
                modifier = Modifier.weight(1f),
            )
            DevToolsPane.Network -> NetworkPane(Modifier.weight(1f))
            DevToolsPane.Application -> ApplicationPane(Modifier.weight(1f))
            DevToolsPane.Elements -> ElementsPane(
                packResolver = packResolver,
                semanticTokens = semanticTokens,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * A pane's overflow menu.
 *
 * Console and Network had a bare "Clear" link. Clearing is the one thing there you cannot undo, and
 * it was the only thing in reach — while the setting that decides whether the log survives the next
 * page load, which is what you want set *before* the interesting request happens, had nowhere to
 * live at all. Application had the same shape of problem from the other side: its "Refresh" sat at
 * the bottom of a long scroll, past everything it refreshes.
 */
@Composable
private fun PaneMenuButton(pane: DevToolsPane) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    Box {
        Icon(
            painter = jcIcon(JCodeIcon.MoreVert),
            contentDescription = "${pane.label} options",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = Space.sm, vertical = Space.sm)
                .size(18.dp),
        )
        val actions = when (pane) {
            DevToolsPane.Application -> listOf(
                ContextAction(icon = JCodeIcon.Refresh, label = "Refresh") {
                    BuiltinBrowser.requestAppRefresh()
                },
                ContextAction(
                    icon = JCodeIcon.Delete,
                    label = "Clear site data",
                    destructive = true,
                ) { clearSiteData() },
            )
            else -> {
                val network = pane == DevToolsPane.Network
                listOf(
                    ContextAction(
                        icon = JCodeIcon.Pin,
                        label = "Preserve log",
                        checked = BuiltinBrowser.preserveLog.value,
                    ) { BuiltinBrowser.preserveLog.value = !BuiltinBrowser.preserveLog.value },
                    ContextAction(
                        icon = JCodeIcon.Copy,
                        label = if (network) "Copy all requests" else "Copy all messages",
                        enabled = if (network) {
                            BuiltinBrowser.network.isNotEmpty()
                        } else {
                            BuiltinBrowser.console.isNotEmpty()
                        },
                    ) {
                        val text = if (network) {
                            BuiltinBrowser.network.joinToString("\n") {
                                "${statusLabel(it)}\t${it.method}\t${it.url}\t${it.durationMs}ms"
                            }
                        } else {
                            BuiltinBrowser.console.joinToString("\n") { "[${it.level}] ${it.message}" }
                        }
                        clipboard.setText(AnnotatedString(text))
                    },
                    ContextAction(
                        icon = JCodeIcon.Clear,
                        label = if (network) "Clear network" else "Clear console",
                        destructive = true,
                    ) { if (network) BuiltinBrowser.clearNetwork() else BuiltinBrowser.clearConsole() },
                )
            }
        }
        CompactContextMenu(expanded = open, onDismissRequest = { open = false }, listActions = actions)
    }
}

/**
 * Everything this origin has kept, gone.
 *
 * `WebStorage.deleteAllData()` is what reaches IndexedDB and the caches — no page-side API can
 * empty another origin's databases, and enumerating our own to delete them one by one would still
 * miss the ones the page never named. The reload is not decoration: nothing about cleared storage
 * shows on a page still running against what it read at load.
 */
private fun clearSiteData() {
    CookieManager.getInstance().removeAllCookies(null)
    CookieManager.getInstance().flush()
    WebStorage.getInstance().deleteAllData()
    BuiltinBrowser.controller?.eval(clearStoreJs("localStorage")) {}
    BuiltinBrowser.controller?.eval(clearStoreJs("sessionStorage")) {}
    BuiltinBrowser.controller?.reload()
    BuiltinBrowser.requestAppRefresh()
}

@Composable
private fun ConsolePane(onOpenSource: (String, Int) -> Unit, modifier: Modifier = Modifier) {
    val entries = BuiltinBrowser.console
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
    // The terminal's size, not a number of this pane's own. Both are monospace logs of a machine
    // talking back, they are two tabs of the same drawer, and somebody who sized the terminal to
    // something they can read on this screen has already answered the question for both.
    val fontSize = LocalTerminalFontSizeSetting.current.value.sp
    var input by remember { mutableStateOf("") }
    // Folded, because a page repeats itself and a console that repeats with it is unreadable. One
    // load of an ordinary site put the same Permissions-Policy warning on the screen three times,
    // two wrapped lines each, and pushed everything worth reading off the top.
    val rows by remember { derivedStateOf { foldConsole(entries) } }
    // Follow the newest line. A log that has to be scrolled to see the thing that just happened is
    // a log nobody looks at while the thing is happening.
    // Scrolled to the *prompt* rather than to the last message, which is now one row further down:
    // the point of following the output is to end up looking at the place you type the next thing.
    LaunchedEffect(rows.size) {
        listState.scrollToItem(if (rows.isEmpty()) 0 else rows.size)
    }
    fun run() {
        val script = input.trim()
        if (script.isEmpty()) return
        BuiltinBrowser.addConsole(BrowserConsoleEntry("input", script))
        val ctl = BuiltinBrowser.controller
        if (ctl == null) {
            BuiltinBrowser.addConsole(BrowserConsoleEntry("error", "No page — open the built-in browser first."))
        } else {
            ctl.eval(script) { raw ->
                BuiltinBrowser.addConsole(BrowserConsoleEntry("eval", decodeJsResult(raw)))
            }
        }
        input = ""
    }
    // The prompt is the last *row of the log*, not a bar across the floor of the panel. That is
    // where a browser's console puts it, and the reason is that the prompt and the answer to it are
    // the same conversation: what you typed, what came back, and the caret waiting for the next
    // thing all read in one column. Pinned to the bottom it was a separate instrument, with the
    // whole empty middle of the panel between a result and the place you would type the follow-up.
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        if (rows.isEmpty()) {
            item {
                Text(
                    text = "Console messages from the page appear here. Type JavaScript below to " +
                        "run it in the page.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.ms),
                )
            }
        } else {
            items(rows) { row -> ConsoleRowView(row, fontSize, clipboard, onOpenSource) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = Space.xs, end = Space.sm, top = Space.xxs, bottom = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same marker every other row has, because this is one of them.
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = fontSize * 1.3f,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(14.dp),
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = fontSize,
                        lineHeight = fontSize * 1.3f,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { run() }),
                    // No box around it and no run button beside it. A field with a filled, rounded
                    // background is a form control; this is a line in a log that happens to take
                    // typing, and the keyboard's Go key is how a console has always been submitted.
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** One console line, and how many times the page said it without saying anything else in between. */
private data class ConsoleRow(val entry: BrowserConsoleEntry, val count: Int)

/**
 * Collapses runs of identical messages.
 *
 * Only *consecutive* ones, which is what a browser's console does and is the honest version: two
 * identical errors either side of something else happened at different moments and are two events.
 * Folding them all together would lose the ordering that makes a log worth reading.
 */
private fun foldConsole(entries: List<BrowserConsoleEntry>): List<ConsoleRow> {
    val out = ArrayList<ConsoleRow>(entries.size)
    entries.forEach { entry ->
        val last = out.lastOrNull()
        if (last != null && last.entry == entry) {
            out[out.lastIndex] = last.copy(count = last.count + 1)
        } else {
            out += ConsoleRow(entry, 1)
        }
    }
    return out
}

/**
 * A console line: a coloured rail, a one-character marker, the message, and where it came from.
 *
 * The rail and the marker do the work a colour alone was doing badly. Every line used to be the same
 * shape — one paragraph of monospace, wrapping flush to the left margin — so a long error's second
 * line looked exactly like the next entry, and telling an error from a log meant comparing two
 * shades of text against each other rather than reading one mark.
 *
 * `source:line` is on the right rather than appended to the message, where it used to wrap into the
 * middle of the prose it was supposed to annotate.
 */
/**
 * A console line: collapsed to one line, opened to all of it.
 *
 * The panel is a drawer on a phone, and a console's longest messages are the ones you least want it
 * spending every row on — one uncaught stack trace used to own the whole panel and push the ten
 * lines that led to it off the top. So a row is a *summary* until it is asked to be more, and the
 * chevron only appears on rows that actually have more, decided by whether the collapsed text
 * overflowed rather than by guessing at a length.
 *
 * The origin is a link. Reading "settings:1" and then hunting for settings line 1 by hand is the
 * step a browser's console exists to remove, so it goes to the Sources pane at that line.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleRowView(
    row: ConsoleRow,
    fontSize: TextUnit,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onOpenSource: (String, Int) -> Unit,
) {
    // Leading is a proportion of the type rather than a constant, so a console set to 18sp does not
    // end up with lines touching each other.
    val leading = fontSize * 1.3f
    val entry = row.entry
    val accent = when (entry.level) {
        "error" -> MaterialTheme.colorScheme.error
        "warning", "warn" -> JCodeTheme.semanticColors.warning
        "input" -> MaterialTheme.colorScheme.primary
        "eval" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val marker = when (entry.level) {
        "error" -> "✕"
        "warning", "warn" -> "!"
        "input" -> "›"
        "eval" -> "‹"
        else -> "·"
    }
    val tint = when (entry.level) {
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.07f)
        "warning", "warn" -> JCodeTheme.semanticColors.warning.copy(alpha = 0.07f)
        "input" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    var expanded by remember(entry) { mutableStateOf(false) }
    var overflows by remember(entry) { mutableStateOf(false) }
    val expandable = overflows || expanded

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(tint)
            .combinedClickable(
                onClick = { if (expandable) expanded = !expanded },
                onLongClick = { clipboard.setText(AnnotatedString(entry.message)) },
            )
            .padding(vertical = Space.xs),
    ) {
        // No rail. The marker and the tint already say what kind of line this is, and a third
        // statement of the same fact down the left edge was a stripe on every row of a log whose
        // whole job is to let two or three rows stand out from the rest.
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = marker,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                // The message's line height, not the marker's own. Both texts then have first line
                // boxes of the same height, so the mark sits on the first line of what it is marking
                // rather than drifting down the middle of a message that ran to three.
                lineHeight = leading,
                textAlign = TextAlign.Center,
                // A narrow gutter. There is one character in it, and the 28dp it used to cost was
                // 28dp off the front of every line on a panel a phone can spare about forty for.
                modifier = Modifier.padding(start = Space.xs).width(14.dp),
            )
            Text(
                text = entry.message,
                color = if (entry.level == "log" || entry.level == "input") {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    accent
                },
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize,
                lineHeight = leading,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                // Measured, not guessed: only a line with something hidden earns a chevron, and the
                // collapsed pass is the only thing that knows whether there was.
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.weight(1f).padding(end = Space.s),
            )
            if (row.count > 1) {
                Text(
                    text = "×${row.count}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize * 0.85f,
                    lineHeight = leading,
                    modifier = Modifier
                        .padding(end = Space.s)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            RoundedCornerShape(Radius.md),
                        )
                        .padding(horizontal = Space.s, vertical = Space.hairline),
                )
            }
            if (expandable) {
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize,
                    lineHeight = leading,
                    modifier = Modifier.width(16.dp),
                )
            }
        }
        if (entry.line > 0 && entry.source.isNotBlank()) {
            Text(
                text = "${entry.source.substringAfterLast('/').ifBlank { entry.source }}:${entry.line}",
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = fontSize * 0.85f,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = Space.xl, top = Space.hairline, end = Space.sm)
                    .clickable { onOpenSource(entry.source, entry.line) },
            )
        }
    }
}

/** One thing the page loaded, as the Sources pane lists it. */
private data class PageSource(val kind: String, val url: String, val index: Int, val inline: Boolean) {
    val label: String
        get() = when {
            kind == "document" -> "(document)"
            inline -> "(inline script #${index + 1})"
            else -> url.substringAfterLast('/').substringBefore('?').ifBlank { url }
        }
}

/**
 * Sources: what the page is made of, and the text of it.
 *
 * A real DevTools gets this from the debugger protocol, which a WebView does not expose — so this
 * asks the page instead. Inline scripts and the document it reads outright; an external file it has
 * the page `fetch`, which is the same request the page already made and so is usually a cache hit.
 *
 * **Known bound, and it is the platform's rather than a missing feature:** a cross-origin file
 * served without CORS headers cannot be read back by the page that loaded it. This says so, instead
 * of showing an empty file and letting you conclude the file was empty.
 */
@Composable
private fun SourcesPane(
    jump: SourceJump?,
    onJumpConsumed: () -> Unit,
    packResolver: (String) -> LanguagePack?,
    semanticTokens: suspend (String, String) -> List<SemanticToken>,
    modifier: Modifier = Modifier,
) {
    var sources by remember { mutableStateOf<List<PageSource>>(emptyList()) }
    var selected by remember { mutableStateOf<PageSource?>(null) }
    var highlight by remember { mutableStateOf(0) }
    var prettyPrinted by remember { mutableStateOf(false) }
    val text by BuiltinBrowser.sourceText
    val minified = remember(text) { text?.let(CodeColoring::looksMinified) == true }
    // Reformatting is a full pass over the source, which for the bundles this button exists for is
    // hundreds of kilobytes; on the main thread it is an ANR, so the raw text stands until it lands.
    val shown by produceState(initialValue = text.orEmpty(), text, prettyPrinted) {
        val raw = text.orEmpty()
        value = if (!prettyPrinted) raw else withContext(Dispatchers.Default) { CodeColoring.prettyPrint(raw) }
    }
    val listState = rememberLazyListState()

    fun list() {
        BuiltinBrowser.controller?.eval(SOURCES_LIST_JS) { raw ->
            sources = parseSources(decodeJsResult(raw))
        }
    }

    fun open(source: PageSource, atLine: Int) {
        selected = source
        highlight = atLine
        // Per source, not sticky: the next file may not be minified, and reformatting one that
        // already has lines only moves them.
        prettyPrinted = false
        BuiltinBrowser.sourceText.value = null
        val ctl = BuiltinBrowser.controller ?: return
        when {
            source.kind == "document" ->
                ctl.eval(DOCUMENT_TEXT_JS) { BuiltinBrowser.sourceText.value = decodeJsResult(it) }
            source.inline ->
                ctl.eval(inlineScriptJs(source.index)) { BuiltinBrowser.sourceText.value = decodeJsResult(it) }
            // Asynchronous: the answer comes back through the JCodeDevTools bridge, not this callback.
            else -> ctl.eval(fetchSourceJs(source.url)) {}
        }
    }

    LaunchedEffect(Unit) { list() }

    // A console line asked for a file and a line. Matched against what the page actually loaded, so
    // the pane opens on the file rather than on a name that resembles one.
    LaunchedEffect(jump, sources) {
        val target = jump ?: return@LaunchedEffect
        if (sources.isEmpty()) return@LaunchedEffect
        val match = sources.firstOrNull { it.url == target.url }
            ?: sources.firstOrNull { it.url.isNotBlank() && it.url.endsWith(target.url) }
            ?: sources.firstOrNull { it.kind == "document" }
        if (match != null) open(match, target.line)
        onJumpConsumed()
    }

    LaunchedEffect(text, highlight) {
        if (text != null && highlight > 1) listState.scrollToItem((highlight - 3).coerceAtLeast(0))
    }

    Column(modifier = modifier.fillMaxSize()) {
        val chosen = selected
        if (chosen == null) {
            if (sources.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    EmptyHint("Nothing loaded yet. Open a page in the built-in browser, then come back.")
                }
            } else {
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    items(sources) { source ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { open(source, 0) }
                                .padding(horizontal = Space.ms, vertical = Space.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = when (source.kind) {
                                    "document" -> "html"
                                    "style" -> "css"
                                    else -> "js"
                                },
                                color = MaterialTheme.colorScheme.primary,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 9.sp,
                                modifier = Modifier
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                        RoundedCornerShape(Radius.sm),
                                    )
                                    .padding(horizontal = Space.xs, vertical = Space.hairline),
                            )
                            Text(
                                text = source.label,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = Space.sm),
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹ Back",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { selected = null }
                        .padding(end = Space.ms, top = Space.xxs, bottom = Space.xxs),
                )
                Text(
                    text = chosen.label,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // Offered only when the source needs it, which on a real site is nearly always.
                if (minified) {
                    Text(
                        text = "{ }",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (prettyPrinted) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier
                            .clickable { prettyPrinted = !prettyPrinted }
                            .padding(horizontal = Space.sm, vertical = Space.xxs),
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
            val body = text
            when {
                body == null -> Box(Modifier.weight(1f).fillMaxWidth()) { EmptyHint("Reading…") }

                body.startsWith(SOURCE_UNREADABLE) -> Box(Modifier.weight(1f).fillMaxWidth()) {
                    EmptyHint(
                        "Served from another origin without CORS headers, so the page cannot read it " +
                            "back. Its requests are still on the Network pane.",
                    )
                }

                else -> CodeListing(
                    body = shown,
                    fileName = CodeColoring.pseudoFileName(chosen.url, chosen.kind),
                    packResolver = packResolver,
                    semanticTokens = semanticTokens,
                    listState = listState,
                    highlightLine = highlight,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/**
 * Application: what the page has kept on this device.
 *
 * The half of Chrome's Application panel anybody opens — storage and cookies — and the half a
 * WebView can answer honestly. Rows go one at a time, because the reason to look at storage while
 * debugging is usually to drop a single key and try again, not to wipe the site and lose the session
 * that took ten minutes to get into.
 */
/** One stored key, and where it is stored — the store is what a delete has to be addressed to. */
private data class StoredEntry(
    val key: String,
    val value: String,
    val store: String,
    /** Cookies the page's own script cannot see; only the WebView's cookie jar reports them. */
    val httpOnly: Boolean = false,
)

private data class IdbStore(val name: String, val count: Int)
private data class IdbDatabase(val name: String, val version: Int, val stores: List<IdbStore>)
private data class CacheBucket(val name: String, val count: Int, val urls: List<String>)
private data class WorkerInfo(val scope: String, val script: String, val state: String)

private class AppSurvey(
    val origin: String,
    val secure: Boolean,
    val usage: Long,
    val quota: Long,
    val persisted: Boolean?,
    val local: List<StoredEntry>,
    val session: List<StoredEntry>,
    val cookies: List<StoredEntry>,
    val databases: List<IdbDatabase>,
    val caches: List<CacheBucket>,
    val workers: List<WorkerInfo>,
    val manifestUrl: String,
    val manifest: String,
)

@Composable
private fun ApplicationPane(modifier: Modifier = Modifier) {
    val dump = BuiltinBrowser.appDump.value
    val pageUrl = BuiltinBrowser.currentUrl.value
    // Re-parsed only when the page answers again, or when the URL moves under it — the jar is read
    // per-URL, so the same dump against a different origin is a different set of cookies.
    val survey = remember(dump, pageUrl) { dump?.let { parseAppSurvey(it, pageUrl) } }
    var open by remember { mutableStateOf<StoredEntry?>(null) }

    fun refresh() = BuiltinBrowser.controller?.eval(APP_DUMP_JS) {}

    LaunchedEffect(Unit) { refresh() }
    // A navigation replaces everything this pane describes, so the survey is retaken rather than
    // left showing the last site's storage under this site's name.
    LaunchedEffect(pageUrl) { if (pageUrl.isNotBlank()) refresh() }
    LaunchedEffect(BuiltinBrowser.appRefreshSignal.value) {
        if (BuiltinBrowser.appRefreshSignal.value > 0) refresh()
    }

    if (BuiltinBrowser.controller == null) {
        Box(modifier.fillMaxSize()) {
            EmptyHint("Open a page in the built-in browser to see what it has stored.")
        }
        return
    }
    if (survey == null) {
        Box(modifier.fillMaxSize()) { EmptyHint("Reading what this page has stored…") }
        return
    }
    val entry = open
    if (entry != null) {
        StoredValueDetail(
            entry = entry,
            onBack = { open = null },
            onDelete = {
                open = null
                deleteEntry(entry, pageUrl) { refresh() }
            },
            modifier = modifier,
        )
        return
    }

    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        QuotaBar(survey)
        DetailSection("Page") {
            DetailPairs(
                buildList {
                    add("Origin" to survey.origin.ifBlank { "—" })
                    add("Secure context" to if (survey.secure) "yes" else "no")
                    if (survey.persisted != null) {
                        add("Storage" to if (survey.persisted) "persistent" else "best-effort")
                    }
                },
                LocalClipboardManager.current,
            )
        }
        StoredSection("Local storage", survey.local, ::refresh, pageUrl) { open = it }
        StoredSection("Session storage", survey.session, ::refresh, pageUrl) { open = it }
        StoredSection("Cookies", survey.cookies, ::refresh, pageUrl) { open = it }
        IndexedDbSection(survey.databases)
        CacheSection(survey.caches)
        WorkerSection(survey.workers)
        ManifestSection(survey.manifestUrl, survey.manifest)
    }
}

/**
 * How much of the origin's allowance the page is using.
 *
 * At the top because it is the question the other sections are evidence for: a site misbehaving
 * over storage shows up here as a number long before you would think to count cache entries.
 */
@Composable
private fun QuotaBar(survey: AppSurvey) {
    if (survey.usage < 0 || survey.quota <= 0) return
    val fraction = (survey.usage.toFloat() / survey.quota).coerceIn(0f, 1f)
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm)) {
        Text(
            text = "${formatBytes(survey.usage)} used of ${formatBytes(survey.quota)}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Space.s)
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction.coerceAtLeast(0.004f))
                    .fillMaxHeight()
                    .background(MaterialTheme.colorScheme.primary),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
}

@Composable
private fun StoredSection(
    title: String,
    rows: List<StoredEntry>,
    onChanged: () -> Unit,
    pageUrl: String,
    onOpen: (StoredEntry) -> Unit,
) {
    // Clearing a whole store stays reachable, but rows delete one at a time: the reason to look at
    // storage while debugging is usually to drop a single key and try again, not to wipe the site
    // and lose the session that took ten minutes to get into.
    val clearable = rows.isNotEmpty() && title != "Cookies"
    DetailSection(
        title = title,
        trailing = if (rows.isEmpty()) "empty" else rows.size.toString(),
        // The two people actually open the pane for start open; the rest state their count in the
        // header, which is the whole map of what a site keeps in one screen.
        initiallyExpanded = rows.isNotEmpty() && title != "Session storage",
        actionLabel = if (clearable) "Clear" else null,
        onAction = if (clearable) {
            { BuiltinBrowser.controller?.eval(clearStoreJs(rows.first().store)) { onChanged() } }
        } else {
            null
        },
    ) {
        if (rows.isEmpty()) {
            Text(
                text = "Nothing stored here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 30.dp, bottom = Space.sm),
            )
            return@DetailSection
        }
        rows.forEach { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(row) }
                    .padding(start = 30.dp, end = Space.sm, top = Space.xs, bottom = Space.xs),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        Text(
                            text = row.key,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        if (row.httpOnly) {
                            Text(
                                text = "HttpOnly",
                                color = JCodeTheme.semanticColors.warning,
                                fontSize = 9.sp,
                                lineHeight = 14.sp,
                            )
                        }
                    }
                    Text(
                        text = row.value,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = "✕",
                    color = MaterialTheme.colorScheme.error,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    // Matches the key's line box above it, so the cross is on the row it deletes
                    // rather than floating between the key and its value.
                    lineHeight = 15.sp,
                    modifier = Modifier
                        .clickable { deleteEntry(row, pageUrl) { onChanged() } }
                        .padding(start = Space.ms),
                )
            }
        }
    }
}

/**
 * One stored value, in full.
 *
 * The reason this screen exists: a two-line preview is enough to recognise a key and never enough
 * to debug one. A framework's module cache or a session blob runs to kilobytes of JSON, and the
 * question is always what is *in* it.
 */
@Composable
private fun StoredValueDetail(
    entry: StoredEntry,
    onBack: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(horizontal = Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(
                painter = jcIcon(JCodeIcon.ArrowBack),
                contentDescription = "Back to storage",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.sm),
            )
            Text(
                text = entry.key,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "Delete",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onDelete).padding(horizontal = Space.s, vertical = Space.xxs),
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DetailPairs(
                buildList {
                    add("Store" to storeLabel(entry.store))
                    add("Size" to formatBytes(entry.value.length.toLong()))
                    if (entry.httpOnly) add("Flag" to "HttpOnly — not visible to page script")
                },
                clipboard,
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
            DetailBody(entry.value, truncated = false, clipboard = clipboard)
        }
    }
}

@Composable
private fun IndexedDbSection(databases: List<IdbDatabase>) {
    DetailSection("IndexedDB", if (databases.isEmpty()) "none" else databases.size.toString()) {
        if (databases.isEmpty()) {
            SectionNote("No databases on this origin.")
            return@DetailSection
        }
        databases.forEach { db ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = Space.ms, bottom = Space.s)) {
                Text(
                    text = "${db.name} · v${db.version}",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                if (db.stores.isEmpty()) {
                    Text(
                        text = "no object stores",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                    )
                }
                db.stores.forEach { store ->
                    Text(
                        text = "${store.name} — ${if (store.count >= 0) "${store.count} records" else "count unavailable"}",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = Space.ms),
                    )
                }
            }
        }
    }
}

@Composable
private fun CacheSection(caches: List<CacheBucket>) {
    DetailSection("Cache storage", if (caches.isEmpty()) "none" else caches.size.toString()) {
        if (caches.isEmpty()) {
            SectionNote("No caches. A service worker is what usually puts them here.")
            return@DetailSection
        }
        caches.forEach { bucket ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = Space.ms, bottom = Space.s)) {
                Text(
                    text = "${bucket.name} · ${bucket.count} entries",
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                )
                bucket.urls.forEach { url ->
                    Text(
                        text = shortName(url),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 10.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = Space.ms),
                    )
                }
                if (bucket.count > bucket.urls.size) {
                    Text(
                        text = "… and ${bucket.count - bucket.urls.size} more",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp,
                        modifier = Modifier.padding(start = Space.ms),
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkerSection(workers: List<WorkerInfo>) {
    DetailSection("Service workers", if (workers.isEmpty()) "none" else workers.size.toString()) {
        if (workers.isEmpty()) {
            SectionNote("No service worker registered for this origin.")
            return@DetailSection
        }
        workers.forEach { w ->
            Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = Space.ms, bottom = Space.sm)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    Text(
                        text = w.state,
                        color = if (w.state == "activated") {
                            JCodeTheme.semanticColors.success
                        } else {
                            JCodeTheme.semanticColors.warning
                        },
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                    )
                    Text(
                        text = "Unregister",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clickable { BuiltinBrowser.controller?.eval(unregisterWorkerJs(w.scope)) {} }
                            .padding(vertical = Space.xxs),
                    )
                }
                Text(
                    text = w.script.ifBlank { w.scope },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun ManifestSection(url: String, manifest: String) {
    DetailSection("Manifest", if (url.isBlank()) "none" else "") {
        if (url.isBlank()) {
            SectionNote("This page declares no web app manifest.")
            return@DetailSection
        }
        Text(
            text = url,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            modifier = Modifier.padding(start = 30.dp, end = Space.ms, bottom = Space.xs),
        )
        if (manifest.isBlank()) {
            SectionNote("Declared, but could not be read — it may be cross-origin.")
        } else {
            DetailBody(manifest, truncated = false, clipboard = LocalClipboardManager.current)
        }
    }
}

@Composable
private fun SectionNote(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 30.dp, end = Space.ms, bottom = Space.sm),
    )
}

private fun storeLabel(store: String): String = when (store) {
    "localStorage" -> "Local storage"
    "sessionStorage" -> "Session storage"
    else -> "Cookie"
}

/**
 * Drop one stored value.
 *
 * Cookies go through the WebView's own jar rather than `document.cookie`, because the jar is the
 * only one of the two that can reach an HttpOnly cookie — which is exactly the kind you most often
 * want gone while debugging a stuck session.
 */
private fun deleteEntry(entry: StoredEntry, pageUrl: String, onDone: () -> Unit) {
    if (entry.store == "cookie") {
        val jar = CookieManager.getInstance()
        jar.setCookie(pageUrl, "${entry.key}=; Max-Age=0; Path=/") {
            jar.flush()
            onDone()
        }
    } else {
        BuiltinBrowser.controller?.eval(removeItemJs(entry.store, entry.key)) { onDone() }
    }
}

private fun parseAppSurvey(json: String, pageUrl: String): AppSurvey? {
    val root = runCatching { JSONObject(json) }.getOrNull() ?: return null
    return AppSurvey(
        origin = root.optString("origin"),
        secure = root.optBoolean("secure"),
        usage = root.optLong("usage", -1),
        quota = root.optLong("quota", -1),
        persisted = if (root.isNull("persisted")) null else root.optBoolean("persisted"),
        local = parseStored(root.optJSONArray("local"), "localStorage"),
        session = parseStored(root.optJSONArray("session"), "sessionStorage"),
        cookies = parseCookies(root.optString("cookies"), pageUrl),
        databases = parseDatabases(root.optJSONArray("idb")),
        caches = parseCaches(root.optJSONArray("caches")),
        workers = parseWorkers(root.optJSONArray("sw")),
        manifestUrl = root.optString("manifestUrl"),
        manifest = root.optString("manifest"),
    )
}

private fun parseStored(array: JSONArray?, store: String): List<StoredEntry> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        StoredEntry(key = o.optString("k"), value = o.optString("v"), store = store)
    }
}

/**
 * The page's cookies, from both places they can be read.
 *
 * `document.cookie` is what the page can see; the WebView's jar is what the server actually gets,
 * and the difference between the two lists is precisely the HttpOnly cookies. Chrome shows those;
 * a panel built only on `document.cookie` silently omits the session cookie on most sites, which
 * is the one you came to look at.
 */
private fun parseCookies(documentCookie: String, pageUrl: String): List<StoredEntry> {
    fun split(raw: String) = raw.split(';').mapNotNull { part ->
        val t = part.trim()
        if (t.isBlank()) null else t.substringBefore('=') to t.substringAfter('=', "")
    }
    val visible = split(documentCookie)
    val visibleKeys = visible.map { it.first }.toSet()
    val jar = runCatching { CookieManager.getInstance().getCookie(pageUrl) }.getOrNull().orEmpty()
    val hidden = split(jar).filter { it.first !in visibleKeys }
    return visible.map { StoredEntry(it.first, it.second, "cookie") } +
        hidden.map { StoredEntry(it.first, it.second, "cookie", httpOnly = true) }
}

private fun parseDatabases(array: JSONArray?): List<IdbDatabase> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val stores = o.optJSONArray("stores")
        IdbDatabase(
            name = o.optString("name"),
            version = o.optInt("version"),
            stores = (0 until (stores?.length() ?: 0)).mapNotNull { j ->
                val s = stores?.optJSONObject(j) ?: return@mapNotNull null
                IdbStore(s.optString("name"), s.optInt("count", -1))
            },
        )
    }
}

private fun parseCaches(array: JSONArray?): List<CacheBucket> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        val urls = o.optJSONArray("urls")
        CacheBucket(
            name = o.optString("name"),
            count = o.optInt("count"),
            urls = (0 until (urls?.length() ?: 0)).map { urls!!.optString(it) },
        )
    }
}

private fun parseWorkers(array: JSONArray?): List<WorkerInfo> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        WorkerInfo(o.optString("scope"), o.optString("script"), o.optString("state"))
    }
}

private fun parseSources(json: String): List<PageSource> {
    val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        PageSource(
            kind = o.optString("kind"),
            url = o.optString("url"),
            index = o.optInt("index"),
            inline = o.optBoolean("inline"),
        )
    }
}

/** What the fetch shim hands back instead of a body it was not allowed to read. */
private const val SOURCE_UNREADABLE = "\u0000unreadable"

private val SOURCES_LIST_JS = buildString {
    append("(function(){var out=[];out.push({kind:'document',url:location.href,index:-1,inline:true});")
    append("var s=document.scripts;for(var i=0;i<s.length;i++){")
    append("out.push({kind:'script',url:s[i].src||'',index:i,inline:!s[i].src})}")
    append("var l=document.querySelectorAll('link[rel=stylesheet]');for(var j=0;j<l.length;j++){")
    append("out.push({kind:'style',url:l[j].href||'',index:j,inline:false})}")
    append("return JSON.stringify(out)})()")
}

private const val DOCUMENT_TEXT_JS =
    "(function(){try{return document.documentElement.outerHTML}catch(e){return String(e)}})()"

private fun inlineScriptJs(index: Int): String =
    "(function(){try{return document.scripts[$index].text||''}catch(e){return String(e)}})()"

private fun fetchSourceJs(url: String): String =
    "(function(){fetch(${JSONObject.quote(url)}).then(function(r){return r.text()})" +
        ".then(function(t){JCodeDevTools.source(t)})" +
        ".catch(function(e){JCodeDevTools.source('\\u0000unreadable')});return 1})()"

/**
 * Everything the Application pane asks the page about itself.
 *
 * Async throughout and answered through the bridge, not the return value — see
 * [BuiltinBrowser.appDump]. Every block is wrapped separately so one unavailable API leaves the
 * rest of the survey intact: `caches` and `serviceWorker` exist only in a secure context, and a
 * page served over plain http would otherwise take the whole report down with it.
 */
private val APP_DUMP_JS = buildString {
    append("(function(){(async function(){")
    append("var out={origin:location.origin,url:location.href,secure:!!window.isSecureContext,")
    append("local:[],session:[],cookies:'',idb:[],caches:[],sw:[],manifestUrl:'',manifest:'',")
    append("usage:-1,quota:-1,persisted:null};")
    append("function d(s){var o=[];try{for(var i=0;i<s.length;i++){var k=s.key(i);")
    append("o.push({k:k,v:String(s.getItem(k))})}}catch(e){}return o}")
    append("out.local=d(localStorage);out.session=d(sessionStorage);")
    append("try{out.cookies=document.cookie||''}catch(e){}")
    append("try{var es=await navigator.storage.estimate();out.usage=es.usage;out.quota=es.quota}catch(e){}")
    append("try{out.persisted=await navigator.storage.persisted()}catch(e){}")
    // Named databases only: indexedDB.databases() is what makes them enumerable at all, and a
    // page cannot discover a database it did not name.
    append("try{if(indexedDB.databases){var dbs=await indexedDB.databases();")
    append("for(var i=0;i<dbs.length;i++){var info={name:dbs[i].name,version:dbs[i].version,stores:[]};")
    append("try{var db=await new Promise(function(res,rej){var r=indexedDB.open(dbs[i].name);")
    append("r.onsuccess=function(){res(r.result)};r.onerror=function(){rej(r.error)};")
    append("r.onblocked=function(){rej(0)}});")
    append("var ns=Array.prototype.slice.call(db.objectStoreNames);")
    append("for(var j=0;j<ns.length;j++){var n=-1;try{n=await new Promise(function(res,rej){")
    append("var q=db.transaction(ns[j],'readonly').objectStore(ns[j]).count();")
    append("q.onsuccess=function(){res(q.result)};q.onerror=function(){rej(0)}})}catch(e){}")
    append("info.stores.push({name:ns[j],count:n})}db.close()}catch(e){}out.idb.push(info)}}}catch(e){}")
    append("try{if(window.caches){var ks=await caches.keys();for(var i=0;i<ks.length;i++){")
    append("var c=await caches.open(ks[i]);var rq=await c.keys();")
    append("out.caches.push({name:ks[i],count:rq.length,")
    append("urls:rq.slice(0,50).map(function(x){return x.url})})}}}catch(e){}")
    append("try{if(navigator.serviceWorker&&navigator.serviceWorker.getRegistrations){")
    append("var rs=await navigator.serviceWorker.getRegistrations();out.sw=rs.map(function(r){")
    append("var w=r.active||r.waiting||r.installing;return {scope:r.scope,")
    append("script:w?w.scriptURL:'',state:w?w.state:'none'}})}}catch(e){}")
    append("try{var lk=document.querySelector('link[rel~=\"manifest\"]');")
    append("if(lk&&lk.href){out.manifestUrl=lk.href;")
    append("try{var mr=await fetch(lk.href);out.manifest=(await mr.text()).slice(0,16384)}catch(e){}}}catch(e){}")
    append("try{JCodeDevTools.app(JSON.stringify(out))}catch(e){}})();return 1})()")
}

private fun clearStoreJs(store: String): String =
    "(function(){try{$store.clear()}catch(e){};return 1})()"

private fun removeItemJs(store: String, key: String): String =
    "(function(){try{$store.removeItem(${JSONObject.quote(key)})}catch(e){};return 1})()"

/**
 * Unregister the worker at [scope], then re-survey.
 *
 * The re-survey is chained onto the unregistration rather than fired alongside it: `unregister()`
 * returns a promise, and an eval callback returns long before it settles, so a survey taken then
 * would list the worker it just removed.
 */
private fun unregisterWorkerJs(scope: String): String =
    "(function(){navigator.serviceWorker.getRegistrations().then(function(rs){" +
        "return Promise.all(rs.filter(function(r){return r.scope===${JSONObject.quote(scope)}})" +
        ".map(function(r){return r.unregister()}))}).then(function(){$APP_DUMP_JS});return 1})()"

/** The Network pane's type filter. Null matches everything; the rest match [BrowserNetworkEntry.kind]. */
private val NETWORK_FILTERS: List<Pair<String, Set<String>?>> = listOf(
    "All" to null,
    "Fetch/XHR" to setOf("fetch", "xhr"),
    "Doc" to setOf("document"),
    "JS" to setOf("script"),
    "CSS" to setOf("css"),
    "Img" to setOf("img"),
    "Media" to setOf("media", "font"),
    "Other" to setOf("other"),
)

@Composable
private fun NetworkPane(modifier: Modifier = Modifier) {
    val entries = BuiltinBrowser.network
    var filter by remember { mutableStateOf(0) }
    var selectedId by remember { mutableStateOf<Long?>(null) }
    // By id, not by value: two identical polls of the same endpoint are two rows, and picking the
    // second one has to open the second one.
    val selected = entries.firstOrNull { it.id == selectedId }
    if (selected != null) {
        NetworkDetail(selected, onBack = { selectedId = null }, modifier = modifier)
        return
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Space.ms, vertical = Space.s),
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            NETWORK_FILTERS.forEachIndexed { i, (label, kinds) ->
                val n = if (kinds == null) entries.size else entries.count { it.kind in kinds }
                ManagerFilterChip(
                    selected = filter == i,
                    // The count is the point of the row: it says where the requests went without
                    // making you tap through eight filters to find out.
                    label = if (n > 0) "$label $n" else label,
                ) { filter = i }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        val shown = NETWORK_FILTERS[filter].second?.let { k -> entries.filter { it.kind in k } } ?: entries
        if (shown.isEmpty()) {
            EmptyHint(
                if (entries.isEmpty()) {
                    "Requests the page makes appear here — documents, scripts, styles, images, " +
                        "fetch and XHR. Tap one for its headers, payload and response."
                } else {
                    "No ${NETWORK_FILTERS[filter].first} requests."
                },
            )
            return
        }
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(shown, key = { it.id }) { e -> NetworkRow(e) { selectedId = e.id } }
        }
    }
}

@Composable
private fun NetworkRow(e: BrowserNetworkEntry, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Space.ms, vertical = Space.s),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = statusLabel(e),
                color = statusColor(e),
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.widthIn(min = 28.dp),
            )
            Text(
                text = shortName(e.url),
                color = MaterialTheme.colorScheme.onSurface,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "${e.durationMs}ms",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
            )
        }
        Text(
            text = buildList {
                add(if (e.method == "GET") e.kind else "${e.method} · ${e.kind}")
                sizeLabel(e)?.let { add(it) }
                hostOf(e.url)?.let { add(it) }
            }.joinToString(" · "),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 36.dp),
        )
    }
}

/**
 * One request, opened out.
 *
 * The sections mirror Chrome's tabs (Headers / Payload / Response) because that is the order the
 * questions come in, but only the ones with an answer are drawn: a row that came from resource
 * timing has no headers and no body to show, and four empty accordions would suggest the request
 * had none rather than that nothing here can see them.
 */
@Composable
private fun NetworkDetail(e: BrowserNetworkEntry, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val clipboard = LocalClipboardManager.current
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onBack)
                .padding(horizontal = Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(
                painter = jcIcon(JCodeIcon.ArrowBack),
                contentDescription = "Back to requests",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.sm),
            )
            Text(
                text = shortName(e.url),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            DetailSection("General", initiallyExpanded = true) {
                DetailPairs(
                    buildList {
                        add("Request URL" to e.url)
                        add("Method" to e.method)
                        add("Status" to if (e.status > 0) e.status.toString() else if (e.failed) "(failed)" else "—")
                        add("Type" to e.kind)
                        if (e.mimeType.isNotBlank()) add("Content-Type" to e.mimeType)
                        add(
                            "Transferred" to when {
                                e.bytes > 0 -> formatBytes(e.bytes)
                                e.bytes == 0L && e.encodedBytes > 0 ->
                                    "0 B — served from cache (${formatBytes(e.encodedBytes)} decoded)"
                                e.bytes == 0L ->
                                    "not disclosed — cross-origin without Timing-Allow-Origin"
                                else -> "unknown"
                            },
                        )
                        add("Time" to "${e.durationMs} ms")
                    },
                    clipboard,
                )
            }
            if (e.timingOnly) {
                Text(
                    text = "Loaded by the browser, not by page script. Resource timing reports its " +
                        "URL, size, duration, and — where the origin allows it — the status; the " +
                        "response body and headers aren't exposed to the page, so there's nothing " +
                        "more to show here.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                )
                return@Column
            }
            if (e.requestHeaders.isNotEmpty()) {
                DetailSection("Request headers", "${e.requestHeaders.size}") {
                    DetailPairs(e.requestHeaders, clipboard)
                }
            }
            if (e.requestBody.isNotBlank()) {
                DetailSection("Payload", initiallyExpanded = true) {
                    DetailBody(e.requestBody, e.bodyTruncated, clipboard)
                }
            }
            if (e.responseHeaders.isNotEmpty()) {
                DetailSection("Response headers", "${e.responseHeaders.size}") {
                    DetailPairs(e.responseHeaders, clipboard)
                }
            }
            if (e.responseBody.isNotBlank()) {
                DetailSection("Response", initiallyExpanded = true) {
                    DetailBody(e.responseBody, e.bodyTruncated, clipboard)
                }
            } else if (!e.failed) {
                Text(
                    text = "No response body was captured — it was empty, binary, or larger than the " +
                        "capture limit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                )
            }
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    trailing: String = "",
    initiallyExpanded: Boolean = false,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(start = Space.ms, end = Space.sm, top = Space.sm, bottom = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(
                painter = jcIcon(if (expanded) JCodeIcon.ChevronDown else JCodeIcon.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(IconSize.xs),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = trailing,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (actionLabel != null && onAction != null) {
                Text(
                    text = actionLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onAction).padding(horizontal = Space.xs, vertical = Space.xxs),
                )
            }
        }
        if (expanded) content()
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.18f))
    }
}

@Composable
private fun DetailPairs(
    pairs: List<Pair<String, String>>,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = Space.ms, bottom = Space.s)) {
        pairs.forEach { (k, v) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { clipboard.setText(AnnotatedString(v)) }
                    .padding(vertical = Space.xxs),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                Text(
                    text = k,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.width(96.dp),
                )
                Text(
                    text = v,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun DetailBody(
    raw: String,
    truncated: Boolean,
    clipboard: androidx.compose.ui.platform.ClipboardManager,
) {
    // Pretty-printed when it parses as JSON, which is what most of these are and none of which is
    // readable as one long line on a phone-width panel.
    val text = remember(raw) { prettyJson(raw) }
    Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = Space.ms, bottom = Space.sm)) {
        Text(
            text = "Copy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { clipboard.setText(AnnotatedString(raw)) }
                .padding(vertical = Space.xxs),
        )
        Text(
            text = text,
            color = MaterialTheme.colorScheme.onSurface,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
        if (truncated) {
            Text(
                text = "… truncated at the 16 KB capture limit",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun prettyJson(raw: String): String {
    val t = raw.trim()
    if (!t.startsWith("{") && !t.startsWith("[")) return raw
    return runCatching {
        when (val v = JSONTokener(t).nextValue()) {
            is JSONObject -> v.toString(2)
            is JSONArray -> v.toString(2)
            else -> raw
        }
    }.getOrDefault(raw)
}

private fun statusLabel(e: BrowserNetworkEntry): String = when {
    e.status > 0 -> e.status.toString()
    e.failed -> "ERR"
    else -> "—"
}

@Composable
private fun statusColor(e: BrowserNetworkEntry) = when {
    e.failed || e.status >= 400 -> MaterialTheme.colorScheme.error
    e.status >= 300 -> JCodeTheme.semanticColors.warning
    e.status > 0 -> JCodeTheme.semanticColors.success
    // Not an error, just unknown — resource-timing rows have no status to report, and painting
    // those red would put a page's whole image list in the colour of something being wrong.
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/**
 * The last path segment plus the query, which is what tells one request from another in a list.
 *
 * The query is kept because dropping it makes whole pages illegible: a site with a bundler or a
 * resource loader answers everything from one path, and Wikipedia's fifteen stylesheets arrive as
 * fifteen rows that all read `load.php`. The row ellipsizes, so a long query costs nothing.
 */
private fun shortName(url: String): String {
    val noHash = url.substringBefore('#')
    if (noHash.startsWith("data:")) return "(data URL)"
    val path = noHash.substringBefore('?')
    val query = noHash.substringAfter('?', "")
    val tail = path.trimEnd('/').substringAfterLast('/').ifBlank { hostOf(url) ?: url }
    return if (query.isEmpty()) tail else "$tail?$query"
}

private fun hostOf(url: String): String? =
    runCatching { java.net.URI(url).host }.getOrNull()?.takeIf { it.isNotBlank() }

/**
 * The size as the list shows it, or null to leave it out.
 *
 * A cross-origin resource that withholds its figures is left out rather than printed as "0 B":
 * on a page whose images all come from a CDN that is most of the list, and "0 B" reads as a
 * measurement rather than as a refusal to measure.
 */
private fun sizeLabel(e: BrowserNetworkEntry): String? = when {
    e.bytes > 0 -> formatBytes(e.bytes)
    e.bytes == 0L && e.encodedBytes > 0 -> "cached"
    e.bytes == 0L -> null
    else -> null
}

private fun formatBytes(n: Long): String = when {
    n <= 0 -> "0 B"
    n < 1024 -> "$n B"
    n < 1024 * 1024 -> "${n / 1024} kB"
    n < 1024L * 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
    // A storage quota is measured in tens of gigabytes; without this it reported "134379.8 MB".
    else -> String.format(java.util.Locale.US, "%.1f GB", n / 1073741824.0)
}

@Composable
private fun ElementsPane(
    packResolver: (String) -> LanguagePack?,
    semanticTokens: suspend (String, String) -> List<SemanticToken>,
    modifier: Modifier = Modifier,
) {
    var dom by remember { mutableStateOf("") }
    fun refresh() {
        BuiltinBrowser.controller?.eval("document.documentElement.outerHTML") { raw ->
            dom = decodeJsResult(raw)
        } ?: run { dom = "" }
    }
    // Serialised in one line by the DOM, which is how it arrived and not how it can be read.
    val pretty = remember(dom) { prettyHtml(dom) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.sm, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = "Refresh snapshot",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { refresh() }.padding(horizontal = Space.sm, vertical = Space.xs),
            )
            Text(
                "read-only DOM at the moment you refresh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        if (pretty.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyHint(
                    if (BuiltinBrowser.controller == null) {
                        "Open a page in the built-in browser first."
                    } else {
                        "Tap “Refresh snapshot” to capture the current page's HTML."
                    },
                )
            }
        } else {
            CodeListing(
                body = pretty,
                fileName = "snapshot.html",
                packResolver = packResolver,
                semanticTokens = semanticTokens,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * Break a serialised DOM onto lines it can be read on.
 *
 * `outerHTML` comes back as one line, because that is what serialising a tree produces — the whole
 * document arrives as a single row that scrolls sideways forever. This puts each tag on its own
 * line and indents by depth.
 *
 * Script, style and preformatted content is copied through untouched. Their text is not markup, and
 * re-wrapping a JavaScript body on its angle brackets would produce something that reads like code
 * and is not the code that ran.
 */
private fun prettyHtml(src: String): String {
    if (src.isBlank() || src.length > CodeColoring.MAX_COLORED_CHARS) return src
    val sb = StringBuilder(src.length + src.length / 4)
    var i = 0
    var depth = 0
    fun newline() {
        if (sb.isNotEmpty()) sb.append('\n')
        repeat(depth.coerceIn(0, 30)) { sb.append("  ") }
    }
    while (i < src.length) {
        val lt = src.indexOf('<', i)
        if (lt < 0) {
            val rest = src.substring(i)
            if (rest.isNotBlank()) { newline(); sb.append(rest.trim()) }
            break
        }
        val between = src.substring(i, lt)
        if (between.isNotBlank()) { newline(); sb.append(between.trim()) }
        val gt = src.indexOf('>', lt)
        if (gt < 0) { sb.append(src, lt, src.length); break }
        val tag = src.substring(lt, gt + 1)
        val name = htmlTagName(tag)
        val closing = tag.startsWith("</")
        if (closing) depth--
        newline()
        sb.append(tag)
        if (!closing && !tag.endsWith("/>") && name !in VOID_TAGS && name.isNotEmpty()) depth++
        i = gt + 1
        if (!closing && name in RAW_TEXT_TAGS) {
            val close = src.indexOf("</$name", i, ignoreCase = true)
            val end = if (close < 0) src.length else close
            sb.append(src, i, end)
            i = end
        }
    }
    return sb.toString()
}

private fun htmlTagName(tag: String): String =
    tag.trim('<', '>', '/').substringBefore(' ').substringBefore('\n').substringBefore('\t').lowercase()

private val VOID_TAGS = setOf(
    "area", "base", "br", "col", "embed", "hr", "img", "input",
    "link", "meta", "param", "source", "track", "wbr", "!doctype",
)
private val RAW_TEXT_TAGS = setOf("script", "style", "pre", "textarea")

/**
 * A source file as the panes draw it: numbered lines, coloured, one row each.
 *
 * The colouring arrives in up to three passes, so the text is never waiting on it — plain first,
 * then the tokenizer, then a language server's classification if one answers. See [CodeColoring].
 */
@Composable
private fun CodeListing(
    body: String,
    fileName: String,
    packResolver: (String) -> LanguagePack?,
    semanticTokens: suspend (String, String) -> List<SemanticToken>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    highlightLine: Int = 0,
) {
    val palette = if (MaterialTheme.colorScheme.background.luminance() < 0.5f) {
        TokenPalette.DARK
    } else {
        TokenPalette.LIGHT
    }
    val lines by produceState(initialValue = emptyList<AnnotatedString>(), body, fileName, palette) {
        // Every stage is off the main thread. Tokenizing a page bundle is hundreds of milliseconds
        // of work, and doing it in composition is an ANR rather than a slow frame.
        value = withContext(Dispatchers.Default) {
            body.lines().map { AnnotatedString(CodeColoring.clipLine(it)) }
        }
        val pack = packResolver(fileName)
        val tokenized = withContext(Dispatchers.Default) {
            CodeColoring.coloredLines(body, fileName, pack, palette)
        }
        if (tokenized != null) value = tokenized
        // Last and optional. A server that is not installed, not running, or not interested returns
        // nothing, and what is already on screen stands.
        val tokens = runCatching { semanticTokens(fileName, body) }.getOrDefault(emptyList())
        if (tokens.isEmpty()) return@produceState
        val semantic = withContext(Dispatchers.Default) {
            CodeColoring.coloredLines(body, fileName, pack, palette, tokens)
        }
        if (semantic != null) value = semantic
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxWidth()) {
        itemsIndexed(lines) { index, line ->
            val number = index + 1
            Row(
                modifier = Modifier.fillMaxWidth().background(
                    if (number == highlightLine) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                    } else {
                        Color.Transparent
                    },
                ),
            ) {
                Text(
                    text = number.toString(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(42.dp).padding(end = Space.sm),
                )
                Text(
                    text = line,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    // One row per line, scrolled sideways rather than wrapped: a wrapped row makes
                    // the number in the gutter point at something several rows tall, and the
                    // console's jump-to-line lands nowhere in particular.
                    softWrap = false,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(end = Space.ms),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(Space.xxl), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** WebView.evaluateJavascript returns the result JSON-encoded (e.g. a string comes back quoted). Decode
 *  it to a plain display string; fall back to the raw value for non-JSON. */
private fun decodeJsResult(raw: String): String =
    runCatching { JSONTokener(raw).nextValue()?.toString() ?: raw }.getOrDefault(raw)

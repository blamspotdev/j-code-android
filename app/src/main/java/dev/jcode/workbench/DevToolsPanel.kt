package dev.jcode.workbench

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jcode.design.JCodeTheme
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
                    horizontalArrangement = Arrangement.spacedBy(0.dp),
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
                                .padding(horizontal = 12.dp),
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
                val clearAction: (() -> Unit)? = when (pane) {
                    DevToolsPane.Console -> BuiltinBrowser::clearConsole
                    DevToolsPane.Network -> BuiltinBrowser::clearNetwork
                    else -> null
                }
                if (clearAction != null) {
                    Text(
                        text = "Clear",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable { clearAction() }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
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
                modifier = Modifier.weight(1f),
            )
            DevToolsPane.Network -> NetworkPane(Modifier.weight(1f))
            DevToolsPane.Application -> ApplicationPane(Modifier.weight(1f))
            DevToolsPane.Elements -> ElementsPane(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConsolePane(onOpenSource: (String, Int) -> Unit, modifier: Modifier = Modifier) {
    val entries = BuiltinBrowser.console
    val listState = rememberLazyListState()
    val clipboard = LocalClipboardManager.current
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        } else {
            items(rows) { row -> ConsoleRowView(row, clipboard, onOpenSource) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // The same marker every other row has, because this is one of them.
                Text(
                    text = "›",
                    color = MaterialTheme.colorScheme.primary,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.width(20.dp),
                )
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp,
                        lineHeight = 15.sp,
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
    clipboard: androidx.compose.ui.platform.ClipboardManager,
    onOpenSource: (String, Int) -> Unit,
) {
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
            .padding(vertical = 3.dp),
    ) {
        // No rail. The marker and the tint already say what kind of line this is, and a third
        // statement of the same fact down the left edge was a stripe on every row of a log whose
        // whole job is to let two or three rows stand out from the rest.
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = marker,
                color = accent,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                // The message's line height, not the marker's own. Both texts then have first line
                // boxes of the same height, so the mark sits on the first line of what it is marking
                // rather than drifting down the middle of a message that ran to three.
                lineHeight = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(start = 8.dp).width(20.dp),
            )
            Text(
                text = entry.message,
                color = if (entry.level == "log" || entry.level == "input") {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    accent
                },
                fontFamily = FontFamily.Monospace,
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = if (expanded) Int.MAX_VALUE else 1,
                overflow = if (expanded) TextOverflow.Clip else TextOverflow.Ellipsis,
                // Measured, not guessed: only a line with something hidden earns a chevron, and the
                // collapsed pass is the only thing that knows whether there was.
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )
            if (row.count > 1) {
                Text(
                    text = "×${row.count}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier
                        .padding(end = 6.dp)
                        .background(
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                            RoundedCornerShape(6.dp),
                        )
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
            if (expandable) {
                Text(
                    text = if (expanded) "⌃" else "⌄",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    modifier = Modifier.width(18.dp),
                )
            }
        }
        if (entry.line > 0 && entry.source.isNotBlank()) {
            Text(
                text = "${entry.source.substringAfterLast('/').ifBlank { entry.source }}:${entry.line}",
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(start = 28.dp, top = 1.dp, end = 8.dp)
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
private fun SourcesPane(jump: SourceJump?, onJumpConsumed: () -> Unit, modifier: Modifier = Modifier) {
    var sources by remember { mutableStateOf<List<PageSource>>(emptyList()) }
    var selected by remember { mutableStateOf<PageSource?>(null) }
    var highlight by remember { mutableStateOf(0) }
    val text by BuiltinBrowser.sourceText
    val listState = rememberLazyListState()

    fun list() {
        BuiltinBrowser.controller?.eval(SOURCES_LIST_JS) { raw ->
            sources = parseSources(decodeJsResult(raw))
        }
    }

    fun open(source: PageSource, atLine: Int) {
        selected = source
        highlight = atLine
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
                                .padding(horizontal = 10.dp, vertical = 7.dp),
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
                                        RoundedCornerShape(4.dp),
                                    )
                                    .padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                            Text(
                                text = source.label,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.5.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "‹ Back",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .clickable { selected = null }
                        .padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
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

                else -> {
                    val lines = remember(body) { body.lines() }
                    LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                        itemsIndexed(lines) { index, line ->
                            val number = index + 1
                            Row(
                                modifier = Modifier.fillMaxWidth().background(
                                    if (number == highlight) {
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
                                    modifier = Modifier.width(42.dp).padding(end = 8.dp),
                                )
                                Text(
                                    text = line.ifEmpty { " " },
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                                )
                            }
                        }
                    }
                }
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
@Composable
private fun ApplicationPane(modifier: Modifier = Modifier) {
    var local by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var session by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var cookies by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }

    fun refresh() {
        BuiltinBrowser.controller?.eval(STORAGE_DUMP_JS) { raw ->
            val root = runCatching { JSONObject(decodeJsResult(raw)) }.getOrNull() ?: return@eval
            local = parsePairs(root.optJSONArray("local"))
            session = parsePairs(root.optJSONArray("session"))
            cookies = root.optString("cookies").split(';').mapNotNull { part ->
                val trimmed = part.trim()
                if (trimmed.isBlank()) null else trimmed.substringBefore('=') to trimmed.substringAfter('=', "")
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (BuiltinBrowser.controller == null) {
        Box(modifier.fillMaxSize()) {
            EmptyHint("Open a page in the built-in browser to see what it has stored.")
        }
        return
    }
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        StorageSection(
            title = "Local storage",
            rows = local,
            onRemove = { key -> BuiltinBrowser.controller?.eval(removeItemJs("localStorage", key)) { refresh() } },
            onClear = { BuiltinBrowser.controller?.eval(clearStoreJs("localStorage")) { refresh() } },
        )
        StorageSection(
            title = "Session storage",
            rows = session,
            onRemove = { key -> BuiltinBrowser.controller?.eval(removeItemJs("sessionStorage", key)) { refresh() } },
            onClear = { BuiltinBrowser.controller?.eval(clearStoreJs("sessionStorage")) { refresh() } },
        )
        StorageSection(
            title = "Cookies",
            rows = cookies,
            onRemove = { key -> BuiltinBrowser.controller?.eval(expireCookieJs(key)) { refresh() } },
            onClear = null,
        )
        Text(
            text = "Refresh",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.clickable { refresh() }.padding(horizontal = 12.dp, vertical = 10.dp),
        )
    }
}

@Composable
private fun StorageSection(
    title: String,
    rows: List<Pair<String, String>>,
    onRemove: (String) -> Unit,
    onClear: (() -> Unit)?,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = if (rows.isEmpty()) "empty" else rows.size.toString(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (onClear != null && rows.isNotEmpty()) {
            Text(
                text = "Clear",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onClear() }.padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
            )
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.22f))
    rows.forEach { (key, value) ->
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = key,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = value,
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
                // Matches the key's line box above it, so the cross is on the row it deletes rather
                // than floating between the key and its value.
                lineHeight = 15.sp,
                modifier = Modifier.clickable { onRemove(key) }.padding(start = 10.dp),
            )
        }
    }
}

private fun parsePairs(array: JSONArray?): List<Pair<String, String>> {
    if (array == null) return emptyList()
    return (0 until array.length()).mapNotNull { i ->
        val o = array.optJSONObject(i) ?: return@mapNotNull null
        o.optString("k") to o.optString("v")
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

private val STORAGE_DUMP_JS = buildString {
    append("(function(){function d(s){var o=[];try{for(var i=0;i<s.length;i++){")
    append("var k=s.key(i);o.push({k:k,v:String(s.getItem(k))})}}catch(e){}return o}")
    append("return JSON.stringify({local:d(localStorage),session:d(sessionStorage),cookies:document.cookie||''})})()")
}

private fun clearStoreJs(store: String): String =
    "(function(){try{$store.clear()}catch(e){};return 1})()"

private fun removeItemJs(store: String, key: String): String =
    "(function(){try{$store.removeItem(${JSONObject.quote(key)})}catch(e){};return 1})()"

private fun expireCookieJs(key: String): String =
    "(function(){document.cookie=${JSONObject.quote(key)}+'=; Max-Age=0; Path=/';return 1})()"

@Composable
private fun NetworkPane(modifier: Modifier = Modifier) {
    val entries = BuiltinBrowser.network
    if (entries.isEmpty()) {
        Box(modifier) { EmptyHint("fetch / XHR requests made by the page appear here.") }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(entries) { e ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 3.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                val statusColor = when {
                    e.status == 0 -> MaterialTheme.colorScheme.error
                    e.status >= 400 -> MaterialTheme.colorScheme.error
                    e.status >= 300 -> JCodeTheme.semanticColors.warning
                    else -> JCodeTheme.semanticColors.success
                }
                Text(if (e.status == 0) "ERR" else e.status.toString(), color = statusColor,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(e.method, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace, fontSize = 12.sp)
                Text(e.url, color = MaterialTheme.colorScheme.onSurface, fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("${e.durationMs}ms", color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace, fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun ElementsPane(modifier: Modifier = Modifier) {
    var dom by remember { mutableStateOf("") }
    fun refresh() {
        BuiltinBrowser.controller?.eval("document.documentElement.outerHTML") { raw ->
            dom = decodeJsResult(raw)
        } ?: run { dom = "No page — open the built-in browser first." }
    }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = "Refresh snapshot",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { refresh() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "read-only DOM at the moment you refresh",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (dom.isEmpty()) {
                EmptyHint("Tap “Refresh snapshot” to capture the current page's HTML.")
            } else {
                Text(
                    text = dom,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                        .horizontalScroll(rememberScrollState()).padding(10.dp),
                )
            }
        }
    }
}

@Composable
private fun EmptyHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** WebView.evaluateJavascript returns the result JSON-encoded (e.g. a string comes back quoted). Decode
 *  it to a plain display string; fall back to the raw value for non-JSON. */
private fun decodeJsResult(raw: String): String =
    runCatching { JSONTokener(raw).nextValue()?.toString() ?: raw }.getOrDefault(raw)

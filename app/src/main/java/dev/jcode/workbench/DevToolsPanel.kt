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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Icon
import dev.jcode.design.CompactContextMenu
import dev.jcode.design.ContextAction
import dev.jcode.design.JCodeIcon
import dev.jcode.design.LocalTerminalFontSizeSetting
import dev.jcode.design.JCodeTheme
import dev.jcode.design.ManagerFilterChip
import dev.jcode.design.jcIcon
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
                if (pane == DevToolsPane.Console || pane == DevToolsPane.Network) {
                    LogMenuButton(pane)
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

/**
 * The Console and Network panes' overflow menu.
 *
 * Was a bare "Clear" link. Clearing is the one thing here you cannot undo, and it was the only thing
 * in reach — while the setting that decides whether the log survives the next page load, which is
 * what you actually want *before* the interesting request happens, had nowhere to live at all.
 */
@Composable
private fun LogMenuButton(pane: DevToolsPane) {
    var open by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current
    val network = pane == DevToolsPane.Network
    Box {
        Icon(
            imageVector = jcIcon(JCodeIcon.MoreVert),
            contentDescription = if (network) "Network options" else "Console options",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .clickable { open = true }
                .padding(horizontal = 8.dp, vertical = 8.dp)
                .size(18.dp),
        )
        CompactContextMenu(
            expanded = open,
            onDismissRequest = { open = false },
            listActions = listOf(
                ContextAction(
                    icon = JCodeIcon.Pin,
                    label = "Preserve log",
                    checked = BuiltinBrowser.preserveLog.value,
                ) { BuiltinBrowser.preserveLog.value = !BuiltinBrowser.preserveLog.value },
                ContextAction(
                    icon = JCodeIcon.Copy,
                    label = if (network) "Copy all requests" else "Copy all messages",
                    enabled = if (network) BuiltinBrowser.network.isNotEmpty() else BuiltinBrowser.console.isNotEmpty(),
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
            ),
        )
    }
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
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        } else {
            items(rows) { row -> ConsoleRowView(row, fontSize, clipboard, onOpenSource) }
        }
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 8.dp, top = 2.dp, bottom = 8.dp),
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
                fontSize = fontSize,
                // The message's line height, not the marker's own. Both texts then have first line
                // boxes of the same height, so the mark sits on the first line of what it is marking
                // rather than drifting down the middle of a message that ran to three.
                lineHeight = leading,
                textAlign = TextAlign.Center,
                // A narrow gutter. There is one character in it, and the 28dp it used to cost was
                // 28dp off the front of every line on a panel a phone can spare about forty for.
                modifier = Modifier.padding(start = 4.dp).width(14.dp),
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
                modifier = Modifier.weight(1f).padding(end = 6.dp),
            )
            if (row.count > 1) {
                Text(
                    text = "×${row.count}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontFamily = FontFamily.Monospace,
                    fontSize = fontSize * 0.85f,
                    lineHeight = leading,
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
                    .padding(start = 18.dp, top = 1.dp, end = 8.dp)
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
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.ArrowBack),
                contentDescription = "Back to requests",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp),
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
                        "URL, size and duration; the status code and body are not exposed to the " +
                        "page, so there is nothing here to show for them.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(initiallyExpanded) }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                imageVector = jcIcon(if (expanded) JCodeIcon.ChevronDown else JCodeIcon.ChevronRight),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            if (trailing.isNotEmpty()) {
                Text(
                    text = trailing,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
    Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 10.dp, bottom = 6.dp)) {
        pairs.forEach { (k, v) ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { clipboard.setText(AnnotatedString(v)) }
                    .padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
    Column(modifier = Modifier.fillMaxWidth().padding(start = 30.dp, end = 10.dp, bottom = 8.dp)) {
        Text(
            text = "Copy",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clickable { clipboard.setText(AnnotatedString(raw)) }
                .padding(vertical = 2.dp),
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
    else -> String.format(java.util.Locale.US, "%.1f MB", n / 1048576.0)
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

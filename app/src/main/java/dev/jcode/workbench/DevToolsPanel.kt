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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import androidx.compose.foundation.layout.IntrinsicSize
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

private enum class DevToolsPane(val label: String) { Console("Console"), Network("Network"), Elements("Elements") }

/**
 * The built-in browser's DevTools, shown in the right drawer while the in-app browser is in use.
 * Reads [BuiltinBrowser] directly (it lives in a different part of the tree). Not a real Chrome
 * DevTools — Android WebView can't host one — but a practical Console (with a JS REPL), a Network log
 * (from an injected fetch/XHR shim), and a refreshable read-only Elements (DOM) snapshot.
 */
@Composable
fun DevtoolsSidebarContent(modifier: Modifier = Modifier) {
    var pane by remember { mutableStateOf(DevToolsPane.Console) }
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevToolsPane.entries.forEach { p ->
                val selected = p == pane
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (selected) {
                        MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    },
                    modifier = Modifier.clickable { pane = p },
                ) {
                    Text(
                        text = p.label,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    )
                }
            }
            Box(Modifier.weight(1f))
            val clearAction: (() -> Unit)? = when (pane) {
                DevToolsPane.Console -> BuiltinBrowser::clearConsole
                DevToolsPane.Network -> BuiltinBrowser::clearNetwork
                DevToolsPane.Elements -> null
            }
            if (clearAction != null) {
                Text(
                    text = "Clear",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { clearAction() }.padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        when (pane) {
            DevToolsPane.Console -> ConsolePane(Modifier.weight(1f))
            DevToolsPane.Network -> NetworkPane(Modifier.weight(1f))
            DevToolsPane.Elements -> ElementsPane(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ConsolePane(modifier: Modifier = Modifier) {
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
    LaunchedEffect(rows.size) {
        if (rows.isNotEmpty()) listState.scrollToItem(rows.lastIndex)
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
    Column(modifier = modifier.fillMaxSize()) {
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyHint("Console messages from the page appear here. Type JavaScript below to run it in the page.")
            }
        } else {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
                items(rows) { row -> ConsoleRowView(row, clipboard) }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("›", color = MaterialTheme.colorScheme.primary, fontFamily = FontFamily.Monospace)
            Surface(
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
            ) {
                BasicTextField(
                    value = input,
                    onValueChange = { input = it },
                    singleLine = true,
                    textStyle = LocalTextStyle.current.copy(
                        color = MaterialTheme.colorScheme.onSurface,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = { run() }),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                )
            }
            Icon(
                Icons.Rounded.PlayArrow,
                contentDescription = "Run",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(22.dp).clickable { run() },
            )
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
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConsoleRowView(row: ConsoleRow, clipboard: androidx.compose.ui.platform.ClipboardManager) {
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
    // Tinted rather than outlined: a row of dividers on a log this dense reads as a table, and the
    // two lines worth finding are the ones that are not ordinary.
    val tint = when (entry.level) {
        "error" -> MaterialTheme.colorScheme.error.copy(alpha = 0.07f)
        "warning", "warn" -> JCodeTheme.semanticColors.warning.copy(alpha = 0.07f)
        "input" -> MaterialTheme.colorScheme.primary.copy(alpha = 0.06f)
        else -> Color.Transparent
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .background(tint)
            .combinedClickable(
                onClick = {},
                // The reason anybody reaches for a console line: to put it somewhere else.
                onLongClick = { clipboard.setText(AnnotatedString(entry.message)) },
            )
            .padding(vertical = 3.dp),
    ) {
        Box(
            modifier = Modifier
                .padding(start = 6.dp)
                .width(2.dp)
                .fillMaxHeight()
                .background(accent.copy(alpha = if (tint == Color.Transparent) 0.35f else 1f)),
        )
        Text(
            text = marker,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(20.dp).padding(top = 1.dp),
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
            modifier = Modifier.weight(1f).padding(end = 6.dp),
        )
        if (row.count > 1) {
            Text(
                text = "×${row.count}",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                modifier = Modifier
                    .padding(end = 6.dp, top = 1.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                        RoundedCornerShape(6.dp),
                    )
                    .padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
        if (entry.line > 0 && entry.source.isNotBlank()) {
            Text(
                text = "${entry.source}:${entry.line}",
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 110.dp).padding(end = 8.dp, top = 1.dp),
            )
        }
    }
}

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

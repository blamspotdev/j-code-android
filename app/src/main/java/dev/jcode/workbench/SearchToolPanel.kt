package dev.jcode.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.core.search.SearchMatch
import dev.jcode.core.search.SearchModule
import dev.jcode.core.search.SearchOptions
import dev.jcode.design.JCodeIcon
import dev.jcode.design.LocalIconBundle
import dev.jcode.design.ManagerFilterChip
import dev.jcode.feature.editor.pane.EditorTab
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

private const val MAX_DISPLAY_RESULTS = 2000
private const val MIN_QUERY_LENGTH = 2

/** Where the Search tool looks for matches. */
internal enum class SearchScope(val label: String, val placeholder: String) {
    Content("Content", "Search in files"),
    Names("Names", "Search file names"),
    CurrentDoc("Current", "Search in current document"),
}

/** Compact 3-way scope switch: a segmented control that fills its row so the labels always fit
 *  (equal thirds, no horizontal scroll, no truncation) regardless of drawer width. */
@Composable
private fun SearchScopeSelector(
    selected: SearchScope,
    onSelect: (SearchScope) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f))
            .padding(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        SearchScope.entries.forEach { s ->
            val sel = s == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(if (sel) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f) else Color.Transparent)
                    .clickable { onSelect(s) }
                    .padding(vertical = 5.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = s.label,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (sel) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Find in Files panel (left-drawer Search tool). Streams matches from [SearchModule.engine]
 * (native ripgrep FFI when present, Kotlin fallback otherwise) and opens a tapped result at its
 * line/column via [onOpenResult], which expects a `path:line:col` token (1-based).
 *
 * Three scopes: file content across the project (default), file names only, and the current
 * document — the active editor tab's live buffer, so unsaved edits are searched too.
 */
@Composable
internal fun SearchToolPanel(
    project: Project?,
    activeTab: EditorTab?,
    onOpenResult: (String) -> Unit,
    seed: Pair<Int, String>? = null,
    onSeedConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val rootFile = (project?.fsPath as? FsPath.Local)?.file

    if (rootFile == null) {
        SearchHint("Open a local project to search its files.", modifier)
        return
    }

    var query by remember { mutableStateOf("") }
    // Seed the query when "Find text" is invoked from the editor menu (keyed by the request nonce so
    // repeating the same word re-seeds), then consume it one-shot so re-entering the panel (e.g.
    // switching sidebar tools and back) doesn't re-inject over a query the user cleared or edited.
    LaunchedEffect(seed?.first) {
        seed?.second?.takeIf { it.isNotBlank() }?.let { query = it }
        if (seed != null) onSeedConsumed()
    }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var scope by remember { mutableStateOf(SearchScope.Content) }
    var searching by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<SearchMatch>() }

    // Page tabs and never-saved buffers have no path to reopen a result at, so Current Doc needs a
    // real file-backed tab.
    val activeDocTab = activeTab?.takeIf {
        !it.isPage && it.editorState != null && it.filePath.path.isNotBlank()
    }

    // Re-run on any input change, debounced so each keystroke doesn't spawn a walk. Changing the key
    // cancels the prior collection, which closes the flow and stops the native search.
    LaunchedEffect(rootFile, caseSensitive, regex, scope, activeDocTab?.id) {
        snapshotFlow { query }
            .debounce(250)
            .distinctUntilChanged()
            .collect { q ->
                results.clear()
                truncated = false
                if (q.length < MIN_QUERY_LENGTH || (scope == SearchScope.CurrentDoc && activeDocTab == null)) {
                    searching = false
                    return@collect
                }
                searching = true
                val options = SearchOptions(
                    query = q,
                    isRegex = regex,
                    caseSensitive = caseSensitive,
                    maxResults = MAX_DISPLAY_RESULTS,
                )
                val matches = when (scope) {
                    SearchScope.Content -> SearchModule.engine.search(rootFile, options)
                    SearchScope.Names -> SearchModule.engine.searchFileNames(rootFile, options)
                    SearchScope.CurrentDoc -> {
                        val snap = activeDocTab!!.editorState!!.snapshot.value
                        SearchModule.engine.searchLines(
                            lineCount = snap.lineCount,
                            lineText = snap::lineText,
                            options = options,
                            filePath = activeDocTab.filePath.relativeToOrNull(rootFile)?.path
                                ?: activeDocTab.filePath.name,
                        )
                    }
                }
                try {
                    matches.collect { match ->
                        if (results.size < MAX_DISPLAY_RESULTS) {
                            results.add(match)
                        } else {
                            truncated = true
                        }
                    }
                } finally {
                    searching = false
                }
            }
    }

    // Current Doc results live outside the project-relative token scheme the other scopes use.
    val openMatch: (SearchMatch) -> Unit = { match ->
        val base = if (scope == SearchScope.CurrentDoc) {
            activeDocTab?.filePath?.absolutePath
        } else {
            File(rootFile, match.filePath).absolutePath
        }
        base?.let { onOpenResult("$it:${match.lineNumber + 1}:${match.columnStart + 1}") }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            SearchField(
                query = query,
                onQueryChange = { query = it },
                searching = searching,
                placeholder = scope.placeholder,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ManagerFilterChip(selected = caseSensitive, label = "Aa") { caseSensitive = !caseSensitive }
                ManagerFilterChip(selected = regex, label = ".*") { regex = !regex }
                SearchScopeSelector(
                    selected = scope,
                    onSelect = { scope = it },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        val grouped = remember(results.size) {
            results.groupBy { it.filePath }.entries.toList()
        }
        val fileCount = grouped.size

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val plus = if (truncated) "+" else ""
            val summary = when {
                scope == SearchScope.CurrentDoc && activeDocTab == null ->
                    "Open a file to search the current document"
                query.length < MIN_QUERY_LENGTH -> "Type at least $MIN_QUERY_LENGTH characters"
                results.isEmpty() && searching -> "Searching…"
                results.isEmpty() -> "No results"
                scope == SearchScope.Names ->
                    "${results.size}$plus file${if (results.size == 1) "" else "s"}"
                else -> "${results.size}$plus results in $fileCount file${if (fileCount == 1) "" else "s"}"
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            if (scope == SearchScope.Names) {
                items(results, key = { it.filePath }) { match ->
                    FileNameRow(match) { openMatch(match) }
                }
            } else {
                grouped.forEach { (path, matches) ->
                    item(key = "file:$path") {
                        Text(
                            text = path,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                    items(matches, key = { "${path}:${it.lineNumber}:${it.columnStart}" }) { match ->
                        MatchRow(match) { openMatch(match) }
                    }
                }
            }
        }
    }
}

@Composable
private fun FileNameRow(match: SearchMatch, onClick: () -> Unit) {
    val parent = match.filePath.replace('\\', '/').substringBeforeLast('/', "")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = match.lineText,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (parent.isNotEmpty()) {
            Text(
                text = parent,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun MatchRow(match: SearchMatch, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 24.dp, end = 12.dp, top = 3.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = "${match.lineNumber + 1}",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = match.lineText.trim(),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Compact single-line search field (~36dp) matching the manager panels — the default
 *  OutlinedTextField's 56dp min height is too bulky for this dense left-drawer panel. */
@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    searching: Boolean,
    placeholder: String,
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.heightIn(min = 36.dp).padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                LocalIconBundle.current[JCodeIcon.Search],
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text(
                        placeholder,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (searching) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
            } else if (query.isNotEmpty()) {
                Icon(
                    LocalIconBundle.current[JCodeIcon.Close],
                    contentDescription = "Clear search",
                    modifier = Modifier.size(16.dp).clickable { onQueryChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SearchHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

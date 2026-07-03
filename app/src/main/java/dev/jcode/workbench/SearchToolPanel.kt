package dev.jcode.workbench

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import dev.jcode.fs.FsPath
import dev.jcode.fs.Project
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import java.io.File

private const val MAX_DISPLAY_RESULTS = 2000
private const val MIN_QUERY_LENGTH = 2

/**
 * Find in Files panel (left-drawer Search tool). Streams matches from [SearchModule.engine]
 * (native ripgrep FFI when present, Kotlin fallback otherwise) and opens a tapped result at its
 * line/column via [onOpenResult], which expects a `path:line:col` token (1-based).
 */
@Composable
internal fun SearchToolPanel(
    project: Project?,
    onOpenResult: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val rootFile = (project?.fsPath as? FsPath.Local)?.file

    if (rootFile == null) {
        SearchHint("Open a local project to search its files.", modifier)
        return
    }

    var query by remember { mutableStateOf("") }
    var caseSensitive by remember { mutableStateOf(false) }
    var regex by remember { mutableStateOf(false) }
    var searching by remember { mutableStateOf(false) }
    var truncated by remember { mutableStateOf(false) }
    val results = remember { mutableStateListOf<SearchMatch>() }

    // Re-run on any input change, debounced so each keystroke doesn't spawn a walk. Changing the key
    // cancels the prior collection, which closes the flow and stops the native search.
    LaunchedEffect(rootFile, caseSensitive, regex) {
        snapshotFlow { query }
            .debounce(250)
            .distinctUntilChanged()
            .collect { q ->
                results.clear()
                truncated = false
                if (q.length < MIN_QUERY_LENGTH) {
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
                try {
                    SearchModule.engine.search(rootFile, options).collect { match ->
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

    Column(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("Search in files") },
                leadingIcon = {
                    Icon(LocalIconBundle.current[JCodeIcon.Search], contentDescription = null, modifier = Modifier.size(18.dp))
                },
                trailingIcon = {
                    if (searching) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchToggleChip("Aa", caseSensitive) { caseSensitive = !caseSensitive }
                SearchToggleChip(".*", regex) { regex = !regex }
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
            val summary = when {
                query.length < MIN_QUERY_LENGTH -> "Type at least $MIN_QUERY_LENGTH characters"
                results.isEmpty() && searching -> "Searching…"
                results.isEmpty() -> "No results"
                else -> "${results.size}${if (truncated) "+" else ""} results in $fileCount file${if (fileCount == 1) "" else "s"}"
            }
            Text(
                text = summary,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                    MatchRow(match) {
                        val token = File(rootFile, match.filePath).absolutePath +
                            ":${match.lineNumber + 1}:${match.columnStart + 1}"
                        onOpenResult(token)
                    }
                }
            }
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

@Composable
private fun SearchToggleChip(label: String, selected: Boolean, onToggle: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onToggle,
        label = { Text(label, fontFamily = FontFamily.Monospace) },
    )
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

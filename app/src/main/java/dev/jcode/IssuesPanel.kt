package dev.jcode

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.core.lsp.DiagnosticSeverity
import dev.jcode.core.lsp.LspModule
import dev.jcode.workbench.LocalIssueActions

/**
 * The right-drawer "Issues" tab: every diagnostic on the [dev.jcode.core.lsp.DiagnosticsBus],
 * grouped by file — config (.jcode YAML) errors, on-save syntax checks, and (once wired) LSP
 * diagnostics all land on the same bus. Tapping an issue opens its file at the line.
 */
@Composable
internal fun IssuesSidebarContent(modifier: Modifier = Modifier) {
    val all by LspModule.diagnosticsBus.allDiagnostics.collectAsStateWithLifecycle()
    val actions = LocalIssueActions.current
    val files = all.filterValues { it.isNotEmpty() }.toSortedMap()

    if (files.isEmpty()) {
        Column(modifier = modifier.fillMaxSize().padding(12.dp)) {
            Text(
                "No issues detected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Configuration errors and on-save syntax checks appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = 6.dp, vertical = 4.dp)) {
        files.forEach { (path, diags) ->
            item(key = "hdr:$path") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                ) {
                    Text(
                        text = path.substringAfterLast('/'),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = shortParent(path),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        text = "${diags.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(diags.size, key = { i -> "$path#$i" }) { i ->
                val d = diags[i]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onOpen(path, d.startLine) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(d.severity.tint(), CircleShape),
                    )
                    Text(
                        text = d.message,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "Ln ${d.startLine + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun DiagnosticSeverity.tint(): Color = when (this) {
    DiagnosticSeverity.ERROR -> MaterialTheme.colorScheme.error
    DiagnosticSeverity.WARNING -> Color(0xFFF2C94C)
    DiagnosticSeverity.INFORMATION, DiagnosticSeverity.HINT -> MaterialTheme.colorScheme.primary
}

/** "…/parent/dir" — just enough of the path to disambiguate same-named files. */
private fun shortParent(path: String): String {
    val parent = path.substringBeforeLast('/', "")
    if (parent.isEmpty()) return ""
    val tail = parent.split('/').takeLast(2).joinToString("/")
    return "…/$tail"
}

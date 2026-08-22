package dev.blamspot.jcode

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blamspot.jcode.core.lsp.DiagnosticSeverity
import dev.blamspot.jcode.core.lsp.LspModule
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.LocalIconBundle
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.workbench.LocalIssueActions

/**
 * The right-drawer "Issues" tab: every diagnostic on the [dev.blamspot.jcode.core.lsp.DiagnosticsBus],
 * grouped by file — config (.jcode YAML) errors, on-save syntax checks and language-server
 * diagnostics all land on the same bus. Tapping an issue opens its file at the line and column.
 *
 * Above them sit the workbench's own problems ([WorkbenchNotices]) — a failed toolchain install and
 * the like. Those belong to no file, so they are listed rather than linked, and they are here
 * because this is the one place the user already looks for what went wrong.
 */
@Composable
internal fun IssuesSidebarContent(modifier: Modifier = Modifier) {
    val all by LspModule.diagnosticsBus.allDiagnostics.collectAsStateWithLifecycle()
    val noticesBySource by WorkbenchNotices.notices.collectAsStateWithLifecycle()
    val actions = LocalIssueActions.current
    val files = all.filterValues { it.isNotEmpty() }.toSortedMap()
    val notices = noticesBySource.toSortedMap()

    if (files.isEmpty() && notices.isEmpty()) {
        Column(modifier = modifier.fillMaxSize().padding(Space.md)) {
            Text(
                "No issues detected.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                "Configuration errors and on-save syntax checks appear here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.padding(top = Space.xs),
            )
        }
        return
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(horizontal = Space.s, vertical = Space.xs)) {
        notices.forEach { (source, messages) ->
            item(key = "notice-hdr:$source") {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    modifier = Modifier.fillMaxWidth().padding(start = Space.xs, top = Space.sm, bottom = Space.xxs),
                ) {
                    Text(
                        text = source,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = "${messages.size}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(messages.size, key = { i -> "notice:$source#$i" }) { i ->
                NoticeRow(messages[i])
            }
        }
        files.forEach { (path, diags) ->
            item(key = "hdr:$path") {
                // The header is a tap target too, landing on the file's first diagnostic — it reads
                // like a file row, so tapping it and getting nothing would be the surprising outcome.
                val first = diags.first()
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onOpen(path, first.startLine, first.startCol) }
                        .padding(start = Space.xs, top = Space.sm, bottom = Space.xxs),
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
                    horizontalArrangement = Arrangement.spacedBy(Space.sm),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { actions.onOpen(path, d.startLine, d.startCol) }
                        .padding(horizontal = Space.sm, vertical = Space.s),
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
                    // Diagnostics count from 0; editors (and the status bar) count from 1.
                    Text(
                        text = "Ln ${d.startLine + 1}, Col ${d.startCol + 1}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * One workbench problem, opened up on demand.
 *
 * Collapsed it is the one line the failing tool led with, which usually names the outcome and not a
 * reason; expanded it is that run's own log. Kept behind a tap because the log is dozens of lines
 * and the pane is a list of problems, not one problem's transcript.
 */
@Composable
private fun NoticeRow(notice: WorkbenchNotices.Notice) {
    var expanded by rememberSaveable(notice.message) { mutableStateOf(false) }
    val hasDetail = notice.detail.isNotEmpty()

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasDetail) Modifier.clickable { expanded = !expanded } else Modifier)
                .padding(horizontal = Space.sm, vertical = Space.s),
        ) {
            Box(
                modifier = Modifier
                    .padding(top = Space.s)
                    .size(8.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
            )
            // Not clamped to two lines like a diagnostic: this is the whole of what the tool said.
            Text(
                text = notice.message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            if (hasDetail) {
                Icon(
                    imageVector = LocalIconBundle.current[
                        if (expanded) JCodeIcon.ChevronUp else JCodeIcon.ChevronDown,
                    ],
                    contentDescription = if (expanded) "Hide details" else "Show details",
                    modifier = Modifier.padding(top = Space.xxs).size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (expanded) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                shape = RoundedCornerShape(Radius.md),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = Space.xl, end = Space.sm, bottom = Space.s),
            ) {
                // Horizontally scrollable rather than wrapped: these are command lines and tool
                // output, where a wrapped line stops looking like the thing that was actually run.
                Column(
                    modifier = Modifier
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Space.sm, vertical = Space.s),
                ) {
                    notice.detail.forEach { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            softWrap = false,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
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

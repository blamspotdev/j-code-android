package dev.blamspot.jcode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Shared header for the Extensions / SDK / LSP manager panels: a title with icon-only Search and
 * Refresh buttons, an "N installed" count, and (when Search is toggled on) a filter field. The
 * panel owns the [query]/[searchActive] state and does the actual filtering + installed-first sort.
 */
@Composable
fun ManagerPanelHeader(
    title: String,
    installedCount: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    searchActive: Boolean = false,
    onToggleSearch: () -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = "Search",
    onManage: (() -> Unit)? = null,
    manageContentDescription: String = "Manage",
    onImport: (() -> Unit)? = null,
    importIcon: JCodeIcon = JCodeIcon.Open,
    importContentDescription: String = "Import",
    onExtras: (() -> Unit)? = null,
    extrasIcon: JCodeIcon = JCodeIcon.MoreVert,
    extrasContentDescription: String = "More",
    /**
     * Problems this panel wants to report. A count rather than the text: the messages themselves go
     * to the Issues pane, which is where the workbench already collects things that went wrong and
     * has the room to show them. A banner in the panel pushed the list down instead.
     */
    noticeCount: Int = 0,
    onNotice: (() -> Unit)? = null,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onExtras != null) {
                HeaderIconButton(
                    icon = LocalIconBundle.current[extrasIcon],
                    contentDescription = extrasContentDescription,
                    onClick = onExtras,
                )
            }
            if (onImport != null) {
                HeaderIconButton(
                    icon = LocalIconBundle.current[importIcon],
                    contentDescription = importContentDescription,
                    onClick = onImport,
                )
            }
            if (onManage != null) {
                HeaderIconButton(
                    icon = LocalIconBundle.current[JCodeIcon.Settings],
                    contentDescription = manageContentDescription,
                    onClick = onManage,
                )
            }
            if (noticeCount > 0 && onNotice != null) {
                HeaderNoticeButton(count = noticeCount, onClick = onNotice)
            }
            HeaderIconButton(
                icon = LocalIconBundle.current[JCodeIcon.Search],
                contentDescription = "Search",
                onClick = onToggleSearch,
                active = searchActive,
            )
            if (busy) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            } else {
                HeaderIconButton(
                    icon = LocalIconBundle.current[JCodeIcon.Refresh],
                    contentDescription = "Refresh",
                    onClick = onRefresh,
                )
            }
        }
        Text(
            text = "$installedCount installed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (searchActive) {
            ManagerSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = searchPlaceholder,
            )
        }
    }
}

/** Compact single-line search field (~36dp) — the default OutlinedTextField's 56dp min height and
 *  padding are too bulky for the dense manager panels. */
@Composable
private fun ManagerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    CompactSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        autoFocus = true,
    )
}

/**
 * The "!" that stands in for a notice banner. Drawn rather than taken from the icon bundle: it is a
 * single glyph, and every bundle would otherwise have to carry a slot for it.
 */
@Composable
private fun HeaderNoticeButton(count: Int, onClick: () -> Unit) {
    val label = if (count == 1) "1 problem — show in Issues" else "$count problems — show in Issues"
    JcTooltip(label) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .background(MaterialTheme.colorScheme.error, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "!",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onError,
                )
            }
        }
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    JcTooltip(contentDescription) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(IconSize.md),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

package dev.jcode.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Install state shown across the SDK / LSP / Extension managers. */
enum class ManagerItemStatus { NotInstalled, Installed, UpdateAvailable }

/** Compact status chip used in the manager list rows and detail headers. */
@Composable
fun ManagerStatusChip(
    status: ManagerItemStatus,
    checking: Boolean = false,
    checkingLabel: String = "Checking…",
    modifier: Modifier = Modifier,
    /** Show a small progress ring alongside the label while [checking] (detail header only). */
    spinner: Boolean = false,
) {
    val (text, active) = when {
        checking -> checkingLabel to false
        status == ManagerItemStatus.UpdateAvailable -> "Update available" to true
        status == ManagerItemStatus.Installed -> "Installed" to true
        else -> "Not installed" to false
    }
    Surface(
        modifier = modifier,
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (checking && spinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

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
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onManage != null) {
                HeaderIconButton(
                    icon = LocalIconBundle.current[JCodeIcon.Settings],
                    contentDescription = manageContentDescription,
                    onClick = onManage,
                )
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
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = 8.dp),
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
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
            }
            if (query.isNotEmpty()) {
                Icon(
                    LocalIconBundle.current[JCodeIcon.Close],
                    contentDescription = "Clear search",
                    modifier = Modifier
                        .size(18.dp)
                        .clickable { onQueryChange("") },
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** Compact filter pill for manager panels — matches the tab-pill styling, denser than M3 FilterChip. */
@Composable
fun ManagerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
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
                modifier = Modifier.size(18.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A compact, clickable manager list row: name + one-line description + trailing status chip.
 * Actions live on the detail page, so the drawer stays dense.
 */
@Composable
fun ManagerListRow(
    name: String,
    description: String,
    status: ManagerItemStatus,
    onClick: () -> Unit,
    checking: Boolean = false,
    checkingLabel: String = "Checking…",
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ManagerStatusChip(status = status, checking = checking, checkingLabel = checkingLabel)
    }
}

/** A titled section/header card used in the manager panels. */
@Composable
fun ManagerSectionCard(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            content()
        }
    }
}

@Composable
fun ManagerSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun ManagerNoticeCard(title: String, message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun CompactFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun CompactOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = 32.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * Shared detail-page body for a manager item: header (title + subtitle + status), description,
 * an optional [extra] slot (e.g. Extensions' samples / requirements), and the
 * install/update/uninstall actions. Rendered full-width as an in-editor page. Command output is not
 * shown here — every install/verify runs in the shared right-drawer Setup terminal.
 */
@Composable
fun ManagerDetailScreen(
    title: String,
    subtitle: String,
    description: String,
    status: ManagerItemStatus,
    busy: Boolean,
    actionsEnabled: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    busyLabel: String? = null,
    showActions: Boolean = true,
    showVerify: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onManage: (() -> Unit)? = null,
    manageLabel: String = "Manage",
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (subtitle.isNotBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ManagerStatusChip(status = status, checking = busy, checkingLabel = busyLabel ?: "Checking…", spinner = true)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (description.isNotBlank()) {
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }

        extra()

        if (onManage != null) {
            CompactFilledButton(manageLabel, onClick = onManage, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth())
        }

        if (showActions) {
            val installed = status == ManagerItemStatus.Installed || status == ManagerItemStatus.UpdateAvailable
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!installed) {
                    CompactFilledButton("Install", onClick = onInstall, enabled = actionsEnabled, modifier = Modifier.weight(1f))
                } else {
                    CompactFilledButton(
                        text = if (status == ManagerItemStatus.UpdateAvailable) "Update" else "Reinstall",
                        onClick = onUpdate,
                        enabled = actionsEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showVerify) {
                    CompactOutlinedButton("Verify", onClick = onVerify, enabled = actionsEnabled, modifier = Modifier.weight(1f))
                }
                CompactOutlinedButton("Uninstall", onClick = onUninstall, enabled = installed && actionsEnabled, modifier = Modifier.weight(1f))
            }
        }
    }
}

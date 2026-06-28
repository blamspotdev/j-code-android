package dev.jcode.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
    modifier: Modifier = Modifier,
) {
    val (text, active) = when {
        checking -> "Checking…" to false
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
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
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
        ManagerStatusChip(status = status, checking = checking)
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

/** Monospace rolling output (install/verify logs). */
@Composable
fun ManagerOutputLog(lines: List<String>, max: Int = 120) {
    if (lines.isEmpty()) {
        Text(
            text = "No output captured yet.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        lines.takeLast(max).forEach { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Shared detail-page body for a manager item: header (title + subtitle + status), description,
 * an optional [extra] slot (e.g. Extensions' samples / requirements), the install/update/uninstall
 * actions, and a rolling output log. Rendered full-width as an in-editor page.
 */
@Composable
fun ManagerDetailScreen(
    title: String,
    subtitle: String,
    description: String,
    status: ManagerItemStatus,
    busy: Boolean,
    actionsEnabled: Boolean,
    logLines: List<String>,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    showActions: Boolean = true,
    showVerify: Boolean = true,
    showOutput: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (subtitle.isNotBlank()) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                ManagerStatusChip(status = status, checking = busy)
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (description.isNotBlank()) {
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }

        extra()

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

        if (showOutput) {
            ManagerSectionCard(title = "Output", description = "Rolling output from the last action.") {
                ManagerOutputLog(logLines)
            }
        }
    }
}

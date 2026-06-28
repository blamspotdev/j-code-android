package dev.jcode.feature.lspmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.LspCatalogAction
import dev.jcode.core.distro.LspCatalogEntry
import dev.jcode.core.distro.LspCatalogState

object LspManagerFeature {

    @Composable
    fun Content(
        state: LspCatalogState,
        environmentState: DistroEnvironmentState,
        onRefresh: () -> Unit,
        onOpenEnvironmentWizard: () -> Unit,
        onInstallEntry: (String) -> Unit,
        onVerifyEntry: (String) -> Unit,
        onUninstallEntry: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val groupedEntries = state.entries.groupBy(LspCatalogEntry::category)
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LspCard(
                title = "Language servers",
                description = "Install language servers into the active distro, tracked per distro.",
            ) {
                SummaryRow("Selected distro", environmentState.runtime.selectedDistro.label)
                SummaryRow(
                    "Environment",
                    if (environmentReady) "Ready for installs" else "Finish setup first",
                )
                SummaryRow(
                    "Installed servers",
                    "${state.installedEntryIds.size} / ${state.entries.size}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactFilledButton("Refresh", onClick = onRefresh, modifier = Modifier.weight(1f))
                    CompactOutlinedButton("Environment setup", onClick = onOpenEnvironmentWizard, modifier = Modifier.weight(1f))
                }
            }

            if (!environmentReady) {
                NoticeCard(
                    title = "Environment required",
                    message = "Run the distro setup first so the jcode user, sudo, and /workspace bind are ready before installing servers.",
                )
            }

            state.errorMessage?.let { message ->
                NoticeCard(
                    title = "LSP manager notice",
                    message = message,
                )
            }

            groupedEntries.forEach { (category, entries) ->
                if (entries.isEmpty()) return@forEach
                LspCard(
                    title = category,
                    description = "Language servers for $category.",
                ) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        EntryRow(
                            entry = entry,
                            installed = entry.id in state.installedEntryIds,
                            running = state.runningEntryId == entry.id,
                            runningAction = state.runningAction,
                            actionsEnabled = environmentReady && state.runningEntryId == null,
                            onInstall = { onInstallEntry(entry.id) },
                            onVerify = { onVerifyEntry(entry.id) },
                            onUninstall = { onUninstallEntry(entry.id) },
                        )
                    }
                }
            }

            if (state.executionLabel != null || state.logLines.isNotEmpty()) {
                LspCard(
                    title = state.executionLabel ?: "Latest command output",
                    description = "Rolling output from the last action.",
                ) {
                    if (state.logLines.isEmpty()) {
                        Text(
                            text = "No output captured yet.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.logLines.takeLast(80).forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LspCard(
    title: String,
    description: String,
    content: @Composable () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
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
private fun CompactFilledButton(
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
private fun CompactOutlinedButton(
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

@Composable
private fun EntryRow(
    entry: LspCatalogEntry,
    installed: Boolean,
    running: Boolean,
    runningAction: LspCatalogAction?,
    actionsEnabled: Boolean,
    onInstall: () -> Unit,
    onVerify: () -> Unit,
    onUninstall: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = entry.name,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    text = entry.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            StatusBadge(
                text = when {
                    running -> runningAction?.label ?: "Running"
                    installed -> "Installed"
                    else -> "Not installed"
                },
                active = running || installed,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CompactFilledButton(
                text = if (installed) "Reinstall" else "Install",
                onClick = onInstall,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedButton(
                text = "Verify",
                onClick = onVerify,
                enabled = actionsEnabled,
                modifier = Modifier.weight(1f),
            )
            CompactOutlinedButton(
                text = "Remove",
                onClick = onUninstall,
                enabled = installed && actionsEnabled,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StatusBadge(
    text: String,
    active: Boolean,
) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun SummaryRow(
    label: String,
    value: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
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
private fun NoticeCard(
    title: String,
    message: String,
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f),
    ) {
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
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }
}

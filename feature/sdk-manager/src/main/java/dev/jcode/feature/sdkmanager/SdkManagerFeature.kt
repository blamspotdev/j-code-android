package dev.jcode.feature.sdkmanager

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.SdkCatalogAction
import dev.jcode.core.distro.SdkCatalogCategory
import dev.jcode.core.distro.SdkCatalogEntry
import dev.jcode.core.distro.SdkCatalogState

object SdkManagerFeature {

    @Composable
    fun Content(
        state: SdkCatalogState,
        environmentState: DistroEnvironmentState,
        onRefresh: () -> Unit,
        onOpenEnvironmentWizard: () -> Unit,
        onInstallEntry: (String) -> Unit,
        onVerifyEntry: (String) -> Unit,
        onUninstallEntry: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val groupedEntries = state.entries.groupBy(SdkCatalogEntry::category)
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SdkCard(
                title = "Distro SDK manager",
                description = "Toolchains for the active distro, tracked per distro.",
            ) {
                SummaryRow("Selected distro", environmentState.runtime.selectedDistro.label)
                SummaryRow(
                    "Environment",
                    if (environmentReady) "Ready for installs" else "Finish setup first",
                )
                SummaryRow(
                    "Installed entries",
                    "${state.installedEntryIds.size} / ${state.entries.size}",
                )
                SummaryRow(
                    "Primary bind",
                    environmentState.runtime.binds.firstOrNull()?.let { "${it.host} -> ${it.target}" } ?: "--",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactFilledButton("Refresh catalog", onClick = onRefresh, modifier = Modifier.weight(1f))
                    CompactOutlinedButton("Environment setup", onClick = onOpenEnvironmentWizard, modifier = Modifier.weight(1f))
                }
            }

            if (!environmentReady) {
                NoticeCard(
                    title = "Environment required",
                    message = "Run the Termux + distro setup first so the jcode user, sudo, and /workspace bind are ready before package installs.",
                )
            }

            state.errorMessage?.let { message ->
                NoticeCard(
                    title = "SDK manager notice",
                    message = message,
                )
            }

            SdkCatalogCategory.entries.forEach { category ->
                val entries = groupedEntries[category].orEmpty()
                if (entries.isEmpty()) return@forEach

                SdkCard(
                    title = category.label,
                    description = categoryDescription(category),
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
                SdkCard(
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
private fun SdkCard(
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
    entry: SdkCatalogEntry,
    installed: Boolean,
    running: Boolean,
    runningAction: SdkCatalogAction?,
    actionsEnabled: Boolean,
    onInstall: () -> Unit,
    onVerify: () -> Unit,
    onUninstall: () -> Unit,
) {
    var showTerminalCommand by remember { mutableStateOf(false) }
    val terminalCommand = extractTerminalCommand(entry.installScript)

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

        if (terminalCommand != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "Terminal install:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactOutlinedButton(
                    text = if (showTerminalCommand) "Hide" else "Show",
                    onClick = { showTerminalCommand = !showTerminalCommand },
                )
            }
            if (showTerminalCommand) {
                Surface(
                    color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        text = terminalCommand,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }
        }
    }
}

/**
 * Extract a one-line terminal install command from the install script.
 * Returns null if the script is too complex (multi-step, curl-based, etc.).
 */
private fun extractTerminalCommand(script: String): String? {
    val lines = script.trim().lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .toList()

    // If it's a simple apt-get install, extract it
    val aptInstallLines = lines.filter { it.contains("apt-get install") && !it.contains("update") }
    if (aptInstallLines.size == 1) {
        val line = aptInstallLines.first()
        // Remove sudo prefix for terminal display (user will run as jcode with sudo)
        return line.replace("sudo ", "")
    }

    // If it's a simple apt-get update + install pattern
    if (lines.size == 2 && lines[0].contains("apt-get update") && lines[1].contains("apt-get install")) {
        return lines[1].replace("sudo ", "")
    }

    // For complex scripts (curl-based, multi-step), show a hint
    if (lines.any { it.contains("curl") || it.contains("wget") }) {
        return "# Complex install — use the Install button above"
    }

    return null
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

private fun categoryDescription(category: SdkCatalogCategory): String {
    return when (category) {
        SdkCatalogCategory.Languages -> "Runtimes, compilers, and LSP packages."
        SdkCatalogCategory.BuildTools -> "Build tools for J Code and native projects."
        SdkCatalogCategory.Android -> "Android prerequisites for the distro."
        SdkCatalogCategory.DotNet -> "Managed runtime tooling."
        SdkCatalogCategory.Embedded -> "Cross-compilers and device tooling."
        SdkCatalogCategory.Databases -> "Database servers and data stores."
    }
}

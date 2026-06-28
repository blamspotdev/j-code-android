package dev.jcode.feature.sdkmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.SdkCatalogCategory
import dev.jcode.core.distro.SdkCatalogEntry
import dev.jcode.core.distro.SdkCatalogState
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.ManagerListRow
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.ManagerSummaryRow

object SdkManagerFeature {

    @Composable
    fun Content(
        state: SdkCatalogState,
        environmentState: DistroEnvironmentState,
        onRefresh: () -> Unit,
        onOpenEnvironmentWizard: () -> Unit,
        onOpenDetail: (String) -> Unit,
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
            ManagerSectionCard(
                title = "SDK manager",
                description = "Toolchains for the active distro, tracked per distro.",
            ) {
                ManagerSummaryRow("Selected distro", environmentState.runtime.selectedDistro.label)
                ManagerSummaryRow(
                    "Environment",
                    if (environmentReady) "Ready for installs" else "Finish setup first",
                )
                ManagerSummaryRow(
                    "Installed",
                    if (state.checking) "Checking…" else "${state.installedEntryIds.size} / ${state.entries.size}",
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CompactFilledButton("Refresh", onClick = onRefresh, modifier = Modifier.weight(1f))
                    CompactOutlinedButton("Environment setup", onClick = onOpenEnvironmentWizard, modifier = Modifier.weight(1f))
                }
            }

            if (!environmentReady) {
                ManagerNoticeCard(
                    title = "Environment required",
                    message = "Run the distro setup first so the jcode user, sudo, and /workspace bind are ready before installs.",
                )
            }

            state.errorMessage?.let { message ->
                ManagerNoticeCard(title = "SDK manager notice", message = message)
            }

            SdkCatalogCategory.entries.forEach { category ->
                val entries = groupedEntries[category].orEmpty()
                if (entries.isEmpty()) return@forEach

                ManagerSectionCard(title = category.label, description = categoryDescription(category)) {
                    entries.forEachIndexed { index, entry ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        ManagerListRow(
                            name = entry.name,
                            description = entry.description,
                            status = statusOf(entry.id, state),
                            checking = state.checking && entry.id !in state.installedEntryIds,
                            onClick = { onOpenDetail(entry.id) },
                        )
                    }
                }
            }
        }
    }

    /** Full-width detail page for one SDK entry, opened as an in-editor page tab. */
    @Composable
    fun DetailPage(
        entry: SdkCatalogEntry,
        state: SdkCatalogState,
        environmentState: DistroEnvironmentState,
        onInstall: (String) -> Unit,
        onUpdate: (String) -> Unit,
        onUninstall: (String) -> Unit,
        onVerify: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true
        ManagerDetailScreen(
            title = entry.name,
            subtitle = entry.category.label,
            description = entry.description,
            status = statusOf(entry.id, state),
            busy = state.checking || state.runningEntryId == entry.id,
            actionsEnabled = environmentReady && state.runningEntryId == null && !state.checking,
            logLines = state.logLines,
            onInstall = { onInstall(entry.id) },
            onUpdate = { onUpdate(entry.id) },
            onUninstall = { onUninstall(entry.id) },
            onVerify = { onVerify(entry.id) },
            modifier = modifier,
        )
    }

    private fun statusOf(id: String, state: SdkCatalogState): ManagerItemStatus = when {
        id in state.updatableEntryIds -> ManagerItemStatus.UpdateAvailable
        id in state.installedEntryIds -> ManagerItemStatus.Installed
        else -> ManagerItemStatus.NotInstalled
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

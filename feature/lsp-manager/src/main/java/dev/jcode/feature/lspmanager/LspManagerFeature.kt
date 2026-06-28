package dev.jcode.feature.lspmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.LspCatalogEntry
import dev.jcode.core.distro.LspCatalogState
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.ManagerListRow
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.ManagerSummaryRow

object LspManagerFeature {

    @Composable
    fun Content(
        state: LspCatalogState,
        environmentState: DistroEnvironmentState,
        onRefresh: () -> Unit,
        onOpenDetail: (String) -> Unit,
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
            ManagerSectionCard(
                title = "Language servers",
                description = "Install language servers into the active distro, tracked per distro.",
            ) {
                ManagerSummaryRow(
                    "Installed",
                    if (state.checking) "Checking…" else "${state.installedEntryIds.size} / ${state.entries.size}",
                )
                CompactFilledButton("Refresh", onClick = onRefresh, modifier = Modifier.fillMaxWidth())
            }

            if (!environmentReady) {
                ManagerNoticeCard(
                    title = "Environment required",
                    message = "Set up the Linux environment in Settings before installing servers.",
                )
            }

            state.errorMessage?.let { message ->
                ManagerNoticeCard(title = "LSP manager notice", message = message)
            }

            groupedEntries.forEach { (category, entries) ->
                if (entries.isEmpty()) return@forEach
                ManagerSectionCard(title = category, description = "Language servers for $category.") {
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

    /** Full-width detail page for one language server, opened as an in-editor page tab. */
    @Composable
    fun DetailPage(
        entry: LspCatalogEntry,
        state: LspCatalogState,
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
            subtitle = entry.category,
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

    private fun statusOf(id: String, state: LspCatalogState): ManagerItemStatus = when {
        id in state.updatableEntryIds -> ManagerItemStatus.UpdateAvailable
        id in state.installedEntryIds -> ManagerItemStatus.Installed
        else -> ManagerItemStatus.NotInstalled
    }
}

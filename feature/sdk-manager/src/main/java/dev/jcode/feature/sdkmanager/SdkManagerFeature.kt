package dev.jcode.feature.sdkmanager

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.SdkCatalogEntry
import dev.jcode.core.distro.SdkCatalogState
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.ManagerListRow
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerPanelHeader

object SdkManagerFeature {

    @Composable
    fun Content(
        state: SdkCatalogState,
        environmentState: DistroEnvironmentState,
        onRefresh: () -> Unit,
        onOpenDetail: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true
        var query by remember { mutableStateOf("") }
        var searchActive by remember { mutableStateOf(false) }

        // One flat list, installed/updatable first, then by name. Category becomes the row subtitle.
        // Entries that don't support the active distro/arch are hidden (e.g. the x86_64-only SQL Server
        // engine never appears on an ARM64 environment).
        val selectedDistro = environmentState.runtime.selectedDistro
        val rows = remember(state.entries, state.installedEntryIds, state.updatableEntryIds, query, selectedDistro) {
            state.entries
                .filter { it.isSupportedOn(selectedDistro.id, selectedDistro.arch) }
                .filter {
                    query.isBlank() ||
                        it.name.contains(query, ignoreCase = true) ||
                        it.description.contains(query, ignoreCase = true) ||
                        it.category.label.contains(query, ignoreCase = true)
                }
                .sortedWith(
                    compareByDescending<SdkCatalogEntry> {
                        it.id in state.installedEntryIds || it.id in state.updatableEntryIds
                    }.thenBy { it.name.lowercase() },
                )
        }

        Column(
            modifier = modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ManagerPanelHeader(
                title = "SDK manager",
                installedCount = state.installedEntryIds.size,
                onRefresh = onRefresh,
                busy = state.checking,
                searchActive = searchActive,
                onToggleSearch = { searchActive = !searchActive; if (!searchActive) query = "" },
                query = query,
                onQueryChange = { query = it },
                searchPlaceholder = "Search SDKs",
            )

            if (!environmentReady) {
                ManagerNoticeCard(
                    title = "Environment required",
                    message = "Set up the Linux environment in Settings before installing.",
                )
            }
            state.errorMessage?.let { message ->
                ManagerNoticeCard(title = "SDK manager notice", message = message)
            }

            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
            ) {
                if (rows.isEmpty()) {
                    Text(
                        text = if (query.isBlank()) "No SDKs available." else "No SDKs match “$query”.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(12.dp),
                    )
                } else {
                    Column {
                        rows.forEachIndexed { index, entry ->
                            if (index > 0) {
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            }
                            ManagerListRow(
                                name = entry.name,
                                description = "${entry.category.label} · ${entry.description}",
                                status = statusOf(entry.id, state),
                                checking = state.checking && entry.id !in state.installedEntryIds,
                                onClick = { onOpenDetail(entry.id) },
                            )
                        }
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

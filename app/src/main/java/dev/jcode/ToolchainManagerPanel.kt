package dev.jcode

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jcode.core.distro.DebugEngineCatalogState
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.LspCatalogState
import dev.jcode.core.distro.SdkCatalogState
import androidx.compose.ui.Alignment
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.ManagerListRow
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerFilterChip
import dev.jcode.design.ManagerPanelHeader

/** What a unified toolchain row is: which catalog it came from decides its detail route. */
private enum class ToolchainKind(val chip: String, val section: String) {
    Sdk("SDKs", "SDKs"),
    Lsp("Servers", "Language servers"),
    Debugger("Debuggers", "Debug engines"),
}

private data class ToolchainRow(
    val kind: ToolchainKind,
    val id: String,
    val name: String,
    val description: String,
    val status: ManagerItemStatus,
    val checking: Boolean,
)

/**
 * The merged "Toolchains" side panel: SDKs, language servers, and debug engines in one searchable,
 * filterable list. Rows keep their per-catalog detail pages and install machinery — this panel only
 * unifies browsing; a search for "python" surfaces the SDK, Pyright, and debugpy together.
 */
@Composable
internal fun ToolchainManagerPanel(
    sdkState: SdkCatalogState,
    lspState: LspCatalogState,
    debugState: DebugEngineCatalogState,
    environmentState: DistroEnvironmentState,
    onRefreshAll: () -> Unit,
    onOpenSdkDetail: (String) -> Unit,
    onOpenLspDetail: (String) -> Unit,
    onOpenDebugDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    var filterName by rememberSaveable { mutableStateOf("") } // "" = All, else ToolchainKind.name
    val filter = ToolchainKind.entries.firstOrNull { it.name == filterName }

    val selectedDistro = environmentState.runtime.selectedDistro
    fun matches(name: String, category: String, description: String): Boolean =
        query.isBlank() ||
            name.contains(query, ignoreCase = true) ||
            category.contains(query, ignoreCase = true) ||
            description.contains(query, ignoreCase = true)

    val rows = remember(sdkState, lspState, debugState, query, filterName, selectedDistro) {
        val all = buildList {
            if (filter == null || filter == ToolchainKind.Sdk) {
                sdkState.entries
                    // Entries that don't support the active distro/arch stay hidden, as in the SDK manager.
                    .filter { it.isSupportedOn(selectedDistro.id, selectedDistro.arch) }
                    .filter { matches(it.name, it.category.label, it.description) }
                    .forEach {
                        add(
                            ToolchainRow(
                                kind = ToolchainKind.Sdk,
                                id = it.id,
                                name = it.name,
                                description = "${it.category.label} · ${it.description}",
                                status = statusOf(it.id, sdkState.installedEntryIds, sdkState.updatableEntryIds),
                                checking = sdkState.checking && it.id !in sdkState.installedEntryIds,
                            ),
                        )
                    }
            }
            if (filter == null || filter == ToolchainKind.Lsp) {
                lspState.entries
                    .filter { matches(it.name, it.category, it.description) }
                    .forEach {
                        add(
                            ToolchainRow(
                                kind = ToolchainKind.Lsp,
                                id = it.id,
                                name = it.name,
                                description = "${it.category} · ${it.description}",
                                status = statusOf(it.id, lspState.installedEntryIds, lspState.updatableEntryIds),
                                checking = lspState.checking && it.id !in lspState.installedEntryIds,
                            ),
                        )
                    }
            }
            if (filter == null || filter == ToolchainKind.Debugger) {
                debugState.entries
                    .filter { matches(it.name, it.category, it.description) }
                    .forEach {
                        add(
                            ToolchainRow(
                                kind = ToolchainKind.Debugger,
                                id = it.id,
                                name = it.name,
                                description = "${it.category} · ${it.description}",
                                status = statusOf(it.id, debugState.installedEntryIds, debugState.updatableEntryIds),
                                checking = debugState.checking && it.id !in debugState.installedEntryIds,
                            ),
                        )
                    }
            }
        }
        all
            .groupBy { it.kind }
            .mapValues { (_, entries) ->
                entries.sortedWith(
                    compareByDescending<ToolchainRow> { it.status != ManagerItemStatus.NotInstalled }
                        .thenBy { it.name.lowercase() },
                )
            }
    }

    val installedTotal = sdkState.installedEntryIds.size +
        lspState.installedEntryIds.size +
        debugState.installedEntryIds.size
    val busy = sdkState.checking || lspState.checking || debugState.checking

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ManagerPanelHeader(
            title = "Toolchains",
            installedCount = installedTotal,
            onRefresh = onRefreshAll,
            busy = busy,
            searchActive = searchActive,
            onToggleSearch = { searchActive = !searchActive; if (!searchActive) query = "" },
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = "Search SDKs, servers, debuggers",
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ManagerFilterChip(
                selected = filter == null,
                label = "All",
                onClick = { filterName = "" },
            )
            ToolchainKind.entries.forEach { kind ->
                ManagerFilterChip(
                    selected = filter == kind,
                    label = kind.chip,
                    onClick = { filterName = if (filter == kind) "" else kind.name },
                )
            }
        }

        if (!environmentReady) {
            ManagerNoticeCard(
                title = "Environment required",
                message = "Set up the Linux environment in Settings before installing.",
            )
        }
        listOfNotNull(sdkState.errorMessage, lspState.errorMessage, debugState.errorMessage)
            .distinct()
            .forEach { ManagerNoticeCard(title = "Toolchains notice", message = it) }

        val visibleKinds = ToolchainKind.entries.filter { !rows[it].isNullOrEmpty() }
        if (visibleKinds.isEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
            ) {
                Text(
                    text = if (query.isBlank()) "Nothing available." else "Nothing matches “$query”.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }
        visibleKinds.forEach { kind ->
            val entries = rows[kind].orEmpty()
            Text(
                text = kind.section.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
            Surface(
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
            ) {
                Column {
                    entries.forEachIndexed { index, row ->
                        if (index > 0) {
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        }
                        ManagerListRow(
                            name = row.name,
                            description = row.description,
                            status = row.status,
                            checking = row.checking,
                            onClick = {
                                when (row.kind) {
                                    ToolchainKind.Sdk -> onOpenSdkDetail(row.id)
                                    ToolchainKind.Lsp -> onOpenLspDetail(row.id)
                                    ToolchainKind.Debugger -> onOpenDebugDetail(row.id)
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

private fun statusOf(id: String, installed: Set<String>, updatable: Set<String>): ManagerItemStatus = when {
    id in updatable -> ManagerItemStatus.UpdateAvailable
    id in installed -> ManagerItemStatus.Installed
    else -> ManagerItemStatus.NotInstalled
}

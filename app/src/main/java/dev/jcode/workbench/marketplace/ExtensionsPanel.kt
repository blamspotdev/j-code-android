package dev.jcode.workbench.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.IconBundleRegistry
import dev.jcode.design.JCodeIcon
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.ManagerListRow
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.ThemeBundleRegistry
import dev.jcode.feature.marketplace.CodeSample
import dev.jcode.feature.marketplace.ExtensionDeps
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.isUpdateAvailable

@Composable
internal fun ExtensionsPanel(
    installed: List<InstalledExtension>,
    available: List<MarketplaceEntry>,
    busy: Boolean,
    onRefreshMarketplace: () -> Unit,
    onOpenDetail: (String) -> Unit,
    themeBundleId: String,
    onSelectTheme: (String) -> Unit,
    iconBundleId: String,
    onSelectIcon: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { if (available.isEmpty()) onRefreshMarketplace() }
    val installedById = remember(installed) { installed.associateBy { it.id } }
    val availableIds = remember(available) { available.map { it.id }.toSet() }
    val installedOnly = remember(installed, availableIds) { installed.filter { it.id !in availableIds } }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ManagerSectionCard(
            title = "Extensions",
            description = "Templates, language packs, and formatters from the marketplace.",
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactFilledButton(
                    text = if (busy) "Refreshing…" else "Refresh",
                    onClick = onRefreshMarketplace,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (available.isEmpty() && installedOnly.isEmpty()) {
            Text(
                if (busy) "Loading marketplace…" else "Refresh to load installable extensions.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (available.isNotEmpty()) {
            ManagerSectionCard(title = "Marketplace", description = "Browse and install extensions.") {
                available.forEachIndexed { index, entry ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ManagerListRow(
                        name = entry.name,
                        description = entry.description ?: entrySubtitle(entry),
                        status = marketStatus(entry, installedById[entry.id]),
                        onClick = { onOpenDetail(entry.id) },
                    )
                }
            }
        }

        if (installedOnly.isNotEmpty()) {
            ManagerSectionCard(title = "Installed", description = "Extensions not in the current marketplace index.") {
                installedOnly.forEachIndexed { index, ext ->
                    if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ManagerListRow(
                        name = ext.name,
                        description = ext.description.ifBlank { ext.type.name.lowercase() },
                        status = ManagerItemStatus.Installed,
                        onClick = { onOpenDetail(ext.id) },
                    )
                }
            }
        }

        ManagerSectionCard(title = "Theme bundles", description = "Switch the app-wide color theme.") {
            val activeTheme = themeBundleId.ifEmpty { ThemeBundleRegistry.default.id }
            ThemeBundleRegistry.builtIns.forEach { bundle ->
                BundleGalleryRow(
                    name = bundle.name,
                    description = bundle.description,
                    selected = activeTheme == bundle.id,
                    onApply = { onSelectTheme(bundle.id) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        listOf(bundle.dark.primary, bundle.dark.secondary, bundle.dark.tertiary, bundle.dark.surface).forEach { color ->
                            Box(
                                modifier = Modifier
                                    .size(14.dp)
                                    .clip(RoundedCornerShape(3.dp))
                                    .background(color),
                            )
                        }
                    }
                }
            }
        }

        ManagerSectionCard(title = "Icon bundles", description = "Switch the app-wide icon set.") {
            val activeIcon = iconBundleId.ifEmpty { IconBundleRegistry.default.id }
            IconBundleRegistry.builtIns.forEach { bundle ->
                BundleGalleryRow(
                    name = bundle.name,
                    description = bundle.description,
                    selected = activeIcon == bundle.id,
                    onApply = { onSelectIcon(bundle.id) },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf(JCodeIcon.Files, JCodeIcon.Run, JCodeIcon.Terminal, JCodeIcon.Search, JCodeIcon.Settings).forEach { slot ->
                            Icon(
                                imageVector = bundle[slot],
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Full-width detail page for one extension, opened as an in-editor page tab. */
@Composable
internal fun ExtensionDetailPage(
    entry: MarketplaceEntry?,
    installed: InstalledExtension?,
    available: List<MarketplaceEntry>,
    installedIds: Set<String>,
    busy: Boolean,
    onInstall: (MarketplaceEntry) -> Unit,
    onUninstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = entry?.id ?: installed?.id ?: return
    val status = marketStatus(entry, installed)
    val subtitle = buildString {
        val type = entry?.type ?: installed?.type
        type?.let { append(it.name.lowercase()) }
        entry?.category?.let { append(" · $it") }
        val version = installed?.version ?: entry?.version
        version?.let { append(" · v$it") }
    }
    val description = entry?.longDescription
        ?: installed?.longDescription
        ?: entry?.description
        ?: installed?.description.orEmpty()
    val samples = (installed?.samples.orEmpty()).ifEmpty { entry?.samples.orEmpty() }
    val hasDeps = entry != null && (!entry.requires.isEmpty || !entry.suggests.isEmpty)
    var showDeps by remember(id) { mutableStateOf(false) }

    ManagerDetailScreen(
        title = entry?.name ?: installed?.name ?: id,
        subtitle = subtitle,
        description = description,
        status = status,
        busy = busy,
        actionsEnabled = !busy,
        showVerify = false,
        showOutput = false,
        logLines = emptyList(),
        onInstall = { if (hasDeps) showDeps = true else entry?.let(onInstall) },
        onUpdate = { entry?.let(onInstall) },
        onUninstall = { onUninstall(id) },
        onVerify = {},
        modifier = modifier,
        extra = {
            if (samples.isNotEmpty()) {
                ManagerSectionCard(title = "Samples", description = "Example usage.") {
                    samples.forEach { sample -> SampleBlock(sample) }
                }
            }
            if (entry != null && (!entry.requires.isEmpty || !entry.suggests.isEmpty)) {
                ManagerSectionCard(title = "Requirements", description = "Works best with these installed.") {
                    RequirementList("Required", entry.requires, available, installedIds)
                    RequirementList("Suggested", entry.suggests, available, installedIds)
                }
            }
        },
    )

    if (showDeps && entry != null) {
        DependencyDialog(
            entry = entry,
            available = available,
            installedIds = installedIds,
            busy = busy,
            onInstall = onInstall,
            onProceed = { onInstall(entry); showDeps = false },
            onDismiss = { showDeps = false },
        )
    }
}

private fun entrySubtitle(entry: MarketplaceEntry): String = buildString {
    append(entry.type.name.lowercase())
    entry.category?.let { append(" · $it") }
    entry.version?.let { append(" · v$it") }
}

private fun marketStatus(entry: MarketplaceEntry?, installed: InstalledExtension?): ManagerItemStatus = when {
    installed == null -> ManagerItemStatus.NotInstalled
    isUpdateAvailable(entry?.version, installed.version) -> ManagerItemStatus.UpdateAvailable
    else -> ManagerItemStatus.Installed
}

@Composable
private fun SampleBlock(sample: CodeSample) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(sample.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
        sample.description?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Surface(color = MaterialTheme.colorScheme.surface.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
            Text(
                text = sample.code,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(8.dp),
            )
        }
    }
}

@Composable
private fun RequirementList(
    label: String,
    deps: ExtensionDeps,
    available: List<MarketplaceEntry>,
    installedIds: Set<String>,
) {
    if (deps.isEmpty) return
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    deps.extensions.forEach { id ->
        val name = available.firstOrNull { it.id == id }?.name ?: id
        DependencyRow(name = name, kind = if (id in installedIds) "extension · installed" else "extension")
    }
    deps.sdks.forEach { id -> DependencyRow(name = id, kind = "SDK · install via SDK Manager") }
    deps.lsps.forEach { id -> DependencyRow(name = id, kind = "language server · install via LSP Manager") }
}

@Composable
private fun DependencyDialog(
    entry: MarketplaceEntry,
    available: List<MarketplaceEntry>,
    installedIds: Set<String>,
    busy: Boolean,
    onInstall: (MarketplaceEntry) -> Unit,
    onProceed: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Install ${entry.name}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Works best with these installed:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                DependencyGroup("Required", entry.requires, available, installedIds, busy, onInstall)
                DependencyGroup("Suggested", entry.suggests, available, installedIds, busy, onInstall)
            }
        },
        confirmButton = { TextButton(onClick = onProceed, enabled = !busy) { Text("Install ${entry.name}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DependencyGroup(
    label: String,
    deps: ExtensionDeps,
    available: List<MarketplaceEntry>,
    installedIds: Set<String>,
    busy: Boolean,
    onInstall: (MarketplaceEntry) -> Unit,
) {
    if (deps.isEmpty) return
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    deps.extensions.forEach { id ->
        val depEntry = available.firstOrNull { it.id == id }
        DependencyRow(name = depEntry?.name ?: id, kind = "extension") {
            when {
                id in installedIds -> Text(
                    "Installed",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                depEntry != null -> TextButton(onClick = { onInstall(depEntry) }, enabled = !busy) { Text("Install") }
                else -> Text(
                    "unavailable",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    deps.sdks.forEach { id -> DependencyRow(name = id, kind = "SDK · install via SDK Manager") {} }
    deps.lsps.forEach { id -> DependencyRow(name = id, kind = "language server · install via LSP Manager") {} }
}

@Composable
private fun DependencyRow(name: String, kind: String, trailing: @Composable () -> Unit = {}) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
    }
}

@Composable
private fun BundleGalleryRow(
    name: String,
    description: String,
    selected: Boolean,
    onApply: () -> Unit,
    preview: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onApply)
            .padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        preview()
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selected) {
            Text(
                text = "Active",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

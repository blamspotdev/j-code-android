package dev.jcode.workbench.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.IconBundleRegistry
import dev.jcode.design.JCodeIcon
import dev.jcode.design.ThemeBundleRegistry
import dev.jcode.design.jcIcon
import dev.jcode.feature.marketplace.ExtensionType
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.isUpdateAvailable

@Composable
internal fun ExtensionsPanel(
    installed: List<InstalledExtension>,
    available: List<MarketplaceEntry>,
    busy: Boolean,
    onRefreshMarketplace: () -> Unit,
    onInstall: (MarketplaceEntry) -> Unit,
    onUninstall: (String) -> Unit,
    themeBundleId: String,
    onSelectTheme: (String) -> Unit,
    iconBundleId: String,
    onSelectIcon: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { if (available.isEmpty()) onRefreshMarketplace() }
    val installedIds = remember(installed) { installed.map { it.id }.toSet() }
    var depPrompt by remember { mutableStateOf<MarketplaceEntry?>(null) }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = jcIcon(JCodeIcon.Extensions),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                "Extensions",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefreshMarketplace) {
                    Icon(jcIcon(JCodeIcon.Refresh), contentDescription = "Refresh marketplace")
                }
            }
        }

        Text("Marketplace (${available.size})", style = MaterialTheme.typography.labelLarge)
        if (available.isEmpty() && !busy) {
            Text(
                "Refresh to load installable extensions from the marketplace.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val installedById = remember(installed) { installed.associateBy { it.id } }
        available.forEach { entry ->
            val sub = buildString {
                append(entry.type.name.lowercase())
                entry.category?.let { append(" · $it") }
                entry.subcategory?.let { append("/$it") }
                entry.version?.let { append(" · v$it") }
            }
            ExtensionRow(name = entry.name, subtitle = sub) {
                val inst = installedById[entry.id]
                val hasDeps = !entry.requires.isEmpty || !entry.suggests.isEmpty
                when {
                    inst == null ->
                        TextButton(
                            onClick = { if (hasDeps) depPrompt = entry else onInstall(entry) },
                            enabled = !busy,
                        ) { Text("Install") }
                    isUpdateAvailable(entry.version, inst.version) ->
                        TextButton(onClick = { onInstall(entry) }, enabled = !busy) { Text("Update") }
                    else -> Text(
                        "Installed",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Text("Installed (${installed.size})", style = MaterialTheme.typography.labelLarge)
        if (installed.isEmpty()) {
            Text(
                "No extensions installed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val availableById = remember(available) { available.associateBy { it.id } }
        installed.forEach { ext ->
            val latest = availableById[ext.id]?.version
            val updatable = isUpdateAvailable(latest, ext.version)
            val detail = buildString {
                append(ext.type.name.lowercase())
                if (updatable) append(" · v${ext.version} → v$latest") else ext.version?.let { append(" · v$it") }
                if (ext.type == ExtensionType.Templates && ext.templates.isNotEmpty()) {
                    append(" · ${ext.templates.size} templates")
                }
                ext.language?.let { append(" · ${it.completions.size} snippets") }
            }
            ExtensionRow(name = ext.name, subtitle = detail) {
                if (updatable) {
                    availableById[ext.id]?.let { entry ->
                        TextButton(onClick = { onInstall(entry) }, enabled = !busy) { Text("Update") }
                    }
                }
                TextButton(onClick = { onUninstall(ext.id) }) { Text("Remove") }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Text("Theme bundles (${ThemeBundleRegistry.builtIns.size})", style = MaterialTheme.typography.labelLarge)
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

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Text("Icon bundles (${IconBundleRegistry.builtIns.size})", style = MaterialTheme.typography.labelLarge)
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

        depPrompt?.let { pending ->
            DependencyDialog(
                entry = pending,
                available = available,
                installedIds = installedIds,
                busy = busy,
                onInstall = onInstall,
                onProceed = { onInstall(pending); depPrompt = null },
                onDismiss = { depPrompt = null },
            )
        }
    }
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
    deps: dev.jcode.feature.marketplace.ExtensionDeps,
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
    deps.lsps.forEach { id -> DependencyRow(name = id, kind = "language server") {} }
}

@Composable
private fun DependencyRow(name: String, kind: String, trailing: @Composable () -> Unit) {
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
private fun ExtensionRow(
    name: String,
    subtitle: String,
    trailing: @Composable () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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

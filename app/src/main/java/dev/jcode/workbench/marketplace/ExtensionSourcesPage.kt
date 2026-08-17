package dev.jcode.workbench.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import dev.jcode.ProviderRelease
import dev.jcode.ProviderReleaseFetcher
import dev.jcode.design.JCodeIcon
import dev.jcode.design.LocalIconBundle
import dev.jcode.design.ManagerSectionCard
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.isUpdateAvailable
import dev.jcode.workbench.ExtensionSourcesState

/**
 * The **Extension Sources** editor page: manage custom repos whose releases publish `.vsix` files.
 * Each source is a GitHub repo (e.g. OpenChamber); JCode resolves its newest `.vsix` release and can
 * install or update the extension from here, so a VSIX extension stays current inside JCode without
 * relying on its own updater (which an embedded host doesn't run). Update badges for extensions
 * installed from a source also appear in the main Extensions list.
 */
@Composable
internal fun ExtensionSourcesPage(
    state: ExtensionSourcesState,
    installed: List<InstalledExtension>,
    busy: Boolean,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onRefresh: () -> Unit,
    onInstall: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newUrl by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Extension Sources",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (state.refreshing) {
                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onRefresh) {
                    Icon(
                        imageVector = LocalIconBundle.current[JCodeIcon.Refresh],
                        contentDescription = "Refresh sources",
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        Text(
            text = "Add a GitHub repo whose releases publish a .vsix (e.g. OpenChamber). JCode checks it " +
                "for new releases and installs or updates the extension from here — the same install and " +
                "\"Update available\" flow as the built-in marketplace.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Extensions from a source install as unsigned third-party code — only add sources you trust.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        val submit = { onAdd(newUrl); newUrl = "" }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = newUrl,
                onValueChange = { newUrl = it },
                placeholder = { Text("https://github.com/owner/repo") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (newUrl.isNotBlank()) submit() }),
                modifier = Modifier.weight(1f),
            )
            Button(onClick = submit, enabled = newUrl.isNotBlank()) { Text("Add") }
        }

        if (state.sources.isEmpty()) {
            Text(
                text = "No sources yet. Add a repo above to install and update VSIX extensions from it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            state.sources.forEach { url ->
                SourceCard(
                    url = url,
                    release = state.releases[url],
                    refreshing = state.refreshing,
                    installedFromSource = installed.firstOrNull { state.attribution[it.id] == url },
                    busy = busy,
                    onInstall = { onInstall(url) },
                    onRemove = { onRemove(url) },
                )
            }
        }
    }
}

@Composable
private fun SourceCard(
    url: String,
    release: ProviderRelease?,
    refreshing: Boolean,
    installedFromSource: InstalledExtension?,
    busy: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit,
) {
    val repo = ProviderReleaseFetcher.parseRepo(url) ?: url
    ManagerSectionCard(title = repo, description = url) {
        when {
            release != null -> {
                val installedVersion = installedFromSource?.version
                val status = when {
                    installedVersion == null -> "Latest release: v${release.version}"
                    isUpdateAvailable(release.version, installedVersion) ->
                        "Installed v$installedVersion · update available: v${release.version}"
                    else -> "Installed v$installedVersion · up to date"
                }
                Text(status, style = MaterialTheme.typography.bodySmall)
                Text(
                    text = release.assetName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            refreshing -> Text(
                "Checking for releases…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> Text(
                "No .vsix release found in this repo.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (release != null) {
                val label = when {
                    installedFromSource == null -> "Install"
                    isUpdateAvailable(release.version, installedFromSource.version) -> "Update"
                    else -> "Reinstall"
                }
                FilledTonalButton(onClick = onInstall, enabled = !busy) { Text(label) }
            }
            TextButton(onClick = onRemove) { Text("Remove") }
        }
    }
}

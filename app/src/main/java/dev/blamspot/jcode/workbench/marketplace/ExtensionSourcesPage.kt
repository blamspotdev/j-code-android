package dev.blamspot.jcode.workbench.marketplace

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.ProviderRelease
import dev.blamspot.jcode.ProviderReleaseFetcher
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.ManagerGroupHeader
import dev.blamspot.jcode.design.ManagerItemStatus
import dev.blamspot.jcode.design.ManagerStatusChip
import dev.blamspot.jcode.design.ManagerSummaryRow
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.feature.marketplace.ExtensionType
import dev.blamspot.jcode.feature.marketplace.InstalledExtension
import dev.blamspot.jcode.feature.marketplace.isUpdateAvailable
import dev.blamspot.jcode.workbench.ExtensionSourcesState

/**
 * The **Extension Sources** editor page: manage custom repos whose releases publish `.vsix` files.
 * Each source is a GitHub repo (e.g. OpenChamber); JCode resolves its newest `.vsix` release and can
 * install or update the extension from it, so a VSIX extension stays current inside JCode without
 * relying on its own updater (which an embedded host doesn't run). Update badges for extensions
 * installed from a source also appear in the main Extensions list.
 *
 * Styled with the shared manager kit ([ManagerStatusChip], [ManagerSummaryRow], the compact buttons,
 * the `surfaceVariant` cards) so it reads as part of the Extensions surface, not a bolt-on.
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
    val submit = {
        val trimmed = newUrl.trim()
        if (trimmed.isNotEmpty()) {
            onAdd(trimmed)
            newUrl = ""
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(Space.md),
        verticalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        ManagerGroupHeader("Add a source")
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompactUrlField(
                value = newUrl,
                onValueChange = { newUrl = it },
                placeholder = "github.com/owner/repo",
                onImeAction = submit,
                modifier = Modifier.weight(1f),
            )
            CompactFilledButton(text = "Add", onClick = submit, enabled = newUrl.isNotBlank())
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = jcIcon(JCodeIcon.Lock),
                contentDescription = null,
                modifier = Modifier.size(13.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "Installs as unsigned third-party code — only add sources you trust.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        ManagerGroupHeader(
            title = "Sources",
            trailing = {
                if (state.refreshing) {
                    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(15.dp), strokeWidth = 2.dp)
                    }
                } else {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.Refresh),
                            contentDescription = "Refresh sources",
                            modifier = Modifier.size(IconSize.md),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
        )

        if (state.sources.isEmpty()) {
            Text(
                text = "No sources yet. Add a repo above to install and update VSIX extensions from it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            state.sources.forEach { url ->
                val release = state.releases[url]
                SourceCard(
                    url = url,
                    release = release,
                    refreshing = state.refreshing,
                    // Attributed to this source by a previous install, or — for a copy imported by
                    // hand — matched to the id the repo's own manifest declares.
                    installedFromSource = installed.firstOrNull { state.attribution[it.id] == url }
                        ?: release?.extensionId?.let { id -> installed.firstOrNull { it.id == id } },
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
    val updatable = installedFromSource != null && release != null &&
        isUpdateAvailable(release.version, installedFromSource.version)
    val status = when {
        installedFromSource == null -> ManagerItemStatus.NotInstalled
        updatable -> ManagerItemStatus.UpdateAvailable
        else -> ManagerItemStatus.Installed
    }
    val actionLabel = when {
        installedFromSource == null -> "Install"
        updatable -> "Update"
        else -> "Reinstall"
    }

    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.16f),
        shape = RoundedCornerShape(Radius.xxl),
        border = BorderStroke(StrokeWidth.hairline, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
    ) {
        Column(
            modifier = Modifier.padding(Space.md),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.md),
            ) {
                val extensionName = installedFromSource?.name ?: release?.displayName
                if (installedFromSource != null || release?.iconUrl != null) {
                    ExtensionIcon(
                        type = installedFromSource?.type ?: ExtensionType.App,
                        name = extensionName ?: repo,
                        iconFile = installedFromSource?.iconFile,
                        iconUrl = release?.iconUrl,
                        size = 38.dp,
                    )
                } else {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(Radius.xl),
                        modifier = Modifier.size(38.dp),
                    ) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = jcIcon(JCodeIcon.Sources),
                                contentDescription = null,
                                modifier = Modifier.size(IconSize.lg),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(Space.hairline),
                ) {
                    Text(
                        text = repo,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = extensionName ?: "github.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ManagerStatusChip(status = status, checking = refreshing && release == null)
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            when {
                release != null -> {
                    ManagerSummaryRow("Latest release", "v${release.version}")
                    installedFromSource?.version?.let { ManagerSummaryRow("Installed", "v$it") }
                    ManagerSummaryRow("Asset", release.assetName)
                }
                refreshing -> ManagerSummaryRow("Latest release", "Checking…")
                else -> ManagerSummaryRow("Latest release", "No .vsix release found")
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (release != null) {
                    CompactFilledButton(text = actionLabel, onClick = onInstall, enabled = !busy)
                }
                CompactOutlinedButton(text = "Remove", onClick = onRemove)
            }
        }
    }
}

/**
 * A compact URL input matching [dev.blamspot.jcode.design.CompactSearchField]'s surface (bordered rounded box,
 * 36dp, `bodySmall`) but without its search glyph — this field takes a repo URL, not a query.
 */
@Composable
private fun CompactUrlField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onImeAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(Radius.xl),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
        border = BorderStroke(StrokeWidth.thin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = modifier,
    ) {
        Box(
            modifier = Modifier
                .heightIn(min = 36.dp)
                .padding(horizontal = Space.ms),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { onImeAction() }),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

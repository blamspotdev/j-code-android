package dev.jcode.workbench.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon
import dev.jcode.design.LocalExtensionSettingsUi
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.ManagerListRow
import dev.jcode.design.ManagerPanelHeader
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.SettingsDropdownRow
import dev.jcode.design.SettingsTextFieldRow
import dev.jcode.feature.marketplace.CodeSample
import dev.jcode.feature.marketplace.ExtensionActivation
import dev.jcode.feature.marketplace.ExtensionDeps
import dev.jcode.feature.marketplace.ExtensionType
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.hasWebUi
import dev.jcode.feature.marketplace.MarketplaceEntry
import dev.jcode.feature.marketplace.isUpdateAvailable
import dev.jcode.feature.marketplace.otherAuthors
import dev.jcode.feature.marketplace.primaryAuthor
import androidx.compose.runtime.collectAsState
import dev.jcode.workbench.ExtensionWebViewPage
import dev.jcode.workbench.ScmHostWebView
import dev.jcode.workbench.ScmWebViewHolder
import kotlinx.coroutines.flow.SharedFlow
import java.io.File

@Composable
internal fun ExtensionsPanel(
    installed: List<InstalledExtension>,
    available: List<MarketplaceEntry>,
    busy: Boolean,
    installPhases: Map<String, String>,
    onRefreshMarketplace: () -> Unit,
    onOpenDetail: (String) -> Unit,
    onOpenPermissions: () -> Unit,
    pendingReloadNames: List<String> = emptyList(),
    onReloadPending: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(Unit) { if (available.isEmpty()) onRefreshMarketplace() }
    var query by remember { mutableStateOf("") }
    var searchActive by remember { mutableStateOf(false) }
    val rows = remember(available, installed, query) { buildExtensionRows(available, installed, query) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ManagerPanelHeader(
            title = "Extensions",
            installedCount = installed.size,
            onRefresh = onRefreshMarketplace,
            busy = busy,
            searchActive = searchActive,
            onToggleSearch = { searchActive = !searchActive; if (!searchActive) query = "" },
            query = query,
            onQueryChange = { query = it },
            searchPlaceholder = "Search extensions",
            onManage = onOpenPermissions,
            manageContentDescription = "Extension settings",
        )

        if (pendingReloadNames.isNotEmpty()) {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.14f),
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = if (pendingReloadNames.size == 1) {
                            "Updated ${pendingReloadNames.first()} — reload to apply"
                        } else {
                            "${pendingReloadNames.size} extensions updated — reload to apply"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp),
                    )
                    TextButton(
                        onClick = onReloadPending,
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                    ) {
                        Text("Reload", style = MaterialTheme.typography.labelMedium)
                    }
                }
            }
        }

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        ) {
            if (rows.isEmpty()) {
                Text(
                    text = when {
                        busy && available.isEmpty() -> "Loading marketplace…"
                        query.isNotBlank() -> "No extensions match “$query”."
                        else -> "Refresh to load installable extensions."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp),
                )
            } else {
                Column {
                    rows.forEachIndexed { index, row ->
                        if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                        val phase = installPhases[row.id]
                        ManagerListRow(
                            name = row.name,
                            description = listOfNotNull(authorLabel(row.primaryAuthor, row.otherAuthors), row.description).joinToString(" · "),
                            status = row.status,
                            onClick = { onOpenDetail(row.id) },
                            checking = phase != null,
                            checkingLabel = phase ?: "Checking…",
                            leading = {
                                ExtensionIcon(type = row.type, name = row.name, iconFile = row.iconFile, iconUrl = row.iconUrl)
                            },
                        )
                    }
                }
            }
        }

    }
}

/** True when a database-manager client extension (e.g. SQL Client) is installed, so the left-drawer
 *  "DB Managers" tool should be shown. */
internal fun List<InstalledExtension>.hasDbManagerClient(): Boolean =
    any { it.type == ExtensionType.DbManager }

/**
 * Left-drawer "DB Managers" panel. With several DB-manager clients installed (e.g. SQL Client +
 * Postgres Client) it shows a **list of clients** first; tapping one drills into that client's
 * embedded web frontend (with a Back header to return to the list). With a single client installed
 * it opens that client directly. Each client's frontend is wired to the Linux runtime via the
 * Extension API; a database tapped inside it opens that client's studio as an editor tab.
 */
@Composable
internal fun DbManagerPanel(
    installed: List<InstalledExtension>,
    onExec: suspend (command: String, timeoutMs: Long) -> String,
    onApiRequest: suspend (extensionId: String, envelopeJson: String) -> String,
    events: SharedFlow<Pair<String, String>>?,
    modifier: Modifier = Modifier,
) {
    val dbExtensions = installed.filter { it.type == ExtensionType.DbManager && it.hasWebUi }
    if (dbExtensions.isEmpty()) {
        Column(
            modifier = modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("DB Managers", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Install a database-manager extension (e.g. SQL Client) from Extensions to browse databases here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    // Which client's frontend is open. null = show the client list; a lone client opens directly
    // (nothing to choose). The selection resets only if the installed set changes.
    val idsKey = dbExtensions.joinToString(",") { it.id }
    var selectedId by rememberSaveable(idsKey) {
        mutableStateOf(if (dbExtensions.size == 1) dbExtensions.first().id else null)
    }
    val selected = dbExtensions.firstOrNull { it.id == selectedId }

    if (selected == null) {
        // Master view: the list of installed DB clients; tap one to open its frontend.
        Column(modifier = modifier.fillMaxSize()) {
            Text(
                "DB Managers",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 6.dp),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            dbExtensions.forEachIndexed { index, ext ->
                if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = ext.id }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ExtensionIcon(type = ext.type, name = ext.name, iconFile = ext.iconFile, size = 30.dp)
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                        Text(
                            ext.name,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Text(
                            ext.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    } else {
        // Detail view: the selected client's embedded frontend, with a Back header (shown only when
        // there are other clients to return to). Keyed by id so each client owns its WebView.
        Column(modifier = modifier.fillMaxSize()) {
            if (dbExtensions.size > 1) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { selectedId = null }
                        .padding(horizontal = 8.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back to DB Managers",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(selected.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            }
            key(selected.id) {
                ExtensionWebViewPage(
                    extension = selected,
                    onExec = onExec,
                    onApiRequest = { envelope -> onApiRequest(selected.id, envelope) },
                    events = events,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                )
            }
        }
    }
}

/** True when a source-control client extension (e.g. Source Control) is installed, so the left-drawer
 *  "SCM" tool should be shown. */
internal fun List<InstalledExtension>.hasScmClient(): Boolean =
    any { it.type == ExtensionType.Scm }

/**
 * Left-drawer "Source Control" panel: embeds the installed SCM extension's web frontend directly
 * (VS Code SCM-sidebar style), wired to the Linux runtime so it can drive git via the Extension API.
 */
@Composable
internal fun ScmPanel(
    installed: List<InstalledExtension>,
    onExec: suspend (command: String, timeoutMs: Long) -> String,
    onApiRequest: suspend (extensionId: String, envelopeJson: String) -> String,
    events: SharedFlow<Pair<String, String>>?,
    projectKey: Any? = null,
    onOpenConfig: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val ext = installed.firstOrNull { it.type == ExtensionType.Scm && it.hasWebUi }
    if (ext == null) {
        PanelEmptyState(
            icon = jcIcon(JCodeIcon.Scm),
            title = "Source Control",
            message = "Install a Source Control extension to manage Git here.",
            modifier = modifier,
        )
        return
    }
    // No project open (no bind target): the background SCM host only boots for an open project, so its
    // holder entry never arrives and the decorations branch below would render a permanently-blank Box.
    // Show guidance instead.
    if (projectKey == null) {
        PanelEmptyState(
            icon = jcIcon(JCodeIcon.Scm),
            title = "Source Control",
            message = "Open a folder with a Git repository to manage changes — or set up your Git identity now.",
            modifier = modifier,
        ) {
            FilledTonalButton(onClick = { onOpenConfig(ext.id) }) {
                Text("Configure Git")
            }
        }
        return
    }
    // A decorations-contributing extension lives in the persistent background host's WebView (one
    // instance per project): attaching it keeps panel state across drawer switches and avoids a
    // second status-computing instance. `generation` re-checks after the host (re)creates an entry —
    // never fall back to a throwaway page WebView for these, or the extension boots twice.
    val holderGeneration by ScmWebViewHolder.generation.collectAsState()
    val holderEntry = remember(ext.id, projectKey, holderGeneration) {
        ScmWebViewHolder.get(ext.id)?.takeIf { it.projectKey == projectKey?.toString() }
    }
    if (holderEntry != null) {
        key(ext.id, projectKey) { ScmHostWebView(holderEntry, modifier.fillMaxSize()) }
        return
    }
    if (ext.contributes.explorerDecorations && LocalExtensionActivation.current.modeFor(ext.id) != ExtensionActivation.Manual) {
        // Host hasn't (re)created the entry yet (project switch in flight); the generation bump
        // recomposes this into the attach branch moments later. Manual activation falls through to
        // the panel-owned WebView below instead — there is no background host to wait for.
        Box(modifier = modifier.fillMaxSize())
        return
    }
    // Key by (extension id, open project) so the panel owns one WebView for the installed SCM extension
    // and, critically, re-creates it — re-running the extension's boot()/repo detection — whenever the
    // selected project changes. Without the project in the key, opening a folder while this panel is
    // already showing leaves it stuck on the stale "Open a project" screen until a manual Refresh.
    key(ext.id, projectKey) {
        ExtensionWebViewPage(
            extension = ext,
            onExec = onExec,
            onApiRequest = { envelope -> onApiRequest(ext.id, envelope) },
            events = events,
            modifier = modifier.fillMaxSize(),
        )
    }
}

/**
 * Compact centered empty state for a left-drawer panel: a tinted rounded icon, a title, one concise
 * line of guidance, and an optional action. Replaces the sparse top-aligned text blocks these panels
 * used to show, and keeps them consistent with the editor's "No file open" state.
 */
@Composable
private fun PanelEmptyState(
    icon: ImageVector,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Box(modifier = modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            action?.invoke()
        }
    }
}

/** True when a VM-manager extension (e.g. VM Manager) is installed, so the left-drawer "VM" tool shows. */
internal fun List<InstalledExtension>.hasVmManagerClient(): Boolean =
    any { it.type == ExtensionType.Vm }

/**
 * Left-drawer "VM" panel: embeds the installed VM-manager extension's web frontend directly (VM list +
 * create + a QEMU-availability check), wired to the Linux runtime via the Extension API. Tapping a VM
 * opens its console as an editor tab (workbench.openView).
 */
@Composable
internal fun VmPanel(
    installed: List<InstalledExtension>,
    onExec: suspend (command: String, timeoutMs: Long) -> String,
    onApiRequest: suspend (extensionId: String, envelopeJson: String) -> String,
    events: SharedFlow<Pair<String, String>>?,
    modifier: Modifier = Modifier,
) {
    val ext = installed.firstOrNull { it.type == ExtensionType.Vm && it.hasWebUi }
    if (ext == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("VM Manager", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "Install a VM Manager extension from Extensions to run virtual machines here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }
    key(ext.id) {
        ExtensionWebViewPage(
            extension = ext,
            onExec = onExec,
            onApiRequest = { envelope -> onApiRequest(ext.id, envelope) },
            events = events,
            modifier = modifier.fillMaxSize(),
        )
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
    installPhase: String?,
    onInstall: (MarketplaceEntry) -> Unit,
    onUninstall: (String) -> Unit,
    onOpenApp: (String) -> Unit,
    onOpenExtensionDetail: (String) -> Unit,
    onOpenSdkDetail: (String) -> Unit,
    onOpenLspDetail: (String) -> Unit,
    onOpenDebugEngineDetail: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val id = entry?.id ?: installed?.id ?: return
    val status = marketStatus(entry, installed)
    val subtitle = buildString {
        val primary = entry?.primaryAuthor ?: installed?.primaryAuthor
        val others = entry?.otherAuthors ?: installed?.otherAuthors ?: emptyList()
        primary?.let { append(authorDetail(it, others)) }
        val type = entry?.type ?: installed?.type
        type?.let { if (isNotEmpty()) append(" · "); append(it.name.lowercase()) }
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
        busyLabel = installPhase,
        actionsEnabled = !busy,
        showVerify = false,
        onInstall = { if (hasDeps) showDeps = true else entry?.let(onInstall) },
        onUpdate = { entry?.let(onInstall) },
        onUninstall = { onUninstall(id) },
        onVerify = {},
        // The Source Control UI is embedded in the left-drawer SCM panel, so a full-page "Manage" tab
        // would just duplicate it. (Other web-UI extensions still get Manage.)
        onManage = if (installed != null && installed.hasWebUi && installed.type != ExtensionType.Scm) {
            { onOpenApp(installed.id) }
        } else {
            null
        },
        manageLabel = "Manage",
        modifier = modifier,
        leading = {
            ExtensionIcon(
                type = entry?.type ?: installed?.type ?: ExtensionType.Unknown,
                name = entry?.name ?: installed?.name ?: id,
                iconFile = installed?.iconFile,
                iconUrl = entry?.iconUrl,
                size = 56.dp,
            )
        },
        extra = {
            if (samples.isNotEmpty()) {
                ManagerSectionCard(title = "Samples", description = "Example usage.") {
                    samples.forEach { sample -> SampleBlock(sample) }
                }
            }
            // Prefer the installed manifest's deps (authoritative once installed); fall back to the
            // marketplace entry so the section also shows before install.
            val requires = installed?.requires ?: entry?.requires ?: ExtensionDeps.EMPTY
            val suggests = installed?.suggests ?: entry?.suggests ?: ExtensionDeps.EMPTY
            if (!requires.isEmpty || !suggests.isEmpty) {
                ManagerSectionCard(
                    title = "Requirements",
                    description = "Toolchains and extensions this extension needs or suggests.",
                ) {
                    RequirementList(
                        "Required", requires, available, installedIds, autoInstalled = true,
                        onOpenExtensionDetail, onOpenSdkDetail, onOpenLspDetail, onOpenDebugEngineDetail,
                    )
                    RequirementList(
                        "Suggested", suggests, available, installedIds, autoInstalled = false,
                        onOpenExtensionDetail, onOpenSdkDetail, onOpenLspDetail, onOpenDebugEngineDetail,
                    )
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

/**
 * Full-width **Extension Settings** page (opened from the Extensions-list gear): one card per installed
 * extension holding its own settings (from the manifest `settings:` block) plus its permissions —
 * activation mode, API-capability grants, and keep-alive. Replaces the per-extension section that used
 * to live in App Settings.
 */
@Composable
internal fun ExtensionPermissionsPage(
    installed: List<InstalledExtension>,
    onOpenConfig: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = "Per-extension settings and permissions. Activation controls when an extension turns " +
                "on; Manual disables it.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (installed.isEmpty()) {
            Text(
                text = "No extensions installed yet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            installed.sortedBy { it.name.lowercase() }.forEach { ext ->
                ManagerSectionCard(
                    title = ext.name,
                    description = listOfNotNull(
                        authorLabel(ext.primaryAuthor, ext.otherAuthors),
                        ext.type.name.lowercase(),
                    ).joinToString(" · "),
                    collapsible = true,
                    defaultExpanded = false,
                ) {
                    ExtensionSettingsControls(extensionId = ext.id)
                    if (ext.type == ExtensionType.Scm && ext.hasWebUi) {
                        Text(
                            "Set up your Git identity (name/email) and authentication for this device.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilledTonalButton(onClick = { onOpenConfig(ext.id) }, modifier = Modifier.fillMaxWidth()) {
                            Text("Configure Git…")
                        }
                    }
                    ActivationSelector(extensionId = ext.id)
                    if (ext.apiCapabilities.isNotEmpty()) {
                        CapabilityToggles(extensionId = ext.id, capabilities = ext.apiCapabilities)
                    }
                    if (ext.hasWebUi) {
                        KeepAliveToggle(extensionId = ext.id)
                    }
                }
            }
        }
    }
}

/**
 * The extension's declared settings (manifest `settings:` block), rendered inline in its Extension
 * Settings card. Reads/writes through [LocalExtensionSettingsUi]; renders nothing when the extension
 * declares no settings.
 */
@Composable
private fun ExtensionSettingsControls(extensionId: String) {
    val ui = LocalExtensionSettingsUi.current
    val group = ui.groups.firstOrNull { it.extensionId == extensionId } ?: return
    if (group.specs.isEmpty()) return
    Text(
        text = "Settings",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    group.specs.forEach { spec ->
        val current = ui.valueOf(extensionId, spec.key)
        when (spec.type) {
            "bool" -> Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(spec.label, style = MaterialTheme.typography.bodyMedium)
                    spec.description?.takeIf { it.isNotBlank() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(
                    checked = current == "true" || current == "1",
                    onCheckedChange = { ui.onChange(extensionId, spec.key, it.toString()) },
                )
            }

            // More than two options standardizes to a dropdown; two options stay a radio pair.
            "enum" -> if (spec.options.size > 2) {
                SettingsDropdownRow(
                    label = spec.label,
                    supporting = spec.description,
                    options = spec.options,
                    selected = current,
                    onSelect = { ui.onChange(extensionId, spec.key, it) },
                    optionLabel = { it.replaceFirstChar { c -> c.uppercaseChar() } },
                )
            } else Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(spec.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                spec.description?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                spec.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { ui.onChange(extensionId, spec.key, option) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = current == option,
                            onClick = { ui.onChange(extensionId, spec.key, option) },
                        )
                        Text(
                            option.replaceFirstChar { c -> c.uppercaseChar() },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            else -> {
                // Buffer edits locally and persist on focus loss — the async DataStore round-trip would
                // otherwise clobber fast typing, and per-keystroke writes cause a save/reload storm.
                var text by remember(extensionId, spec.key) { mutableStateOf(current) }
                LaunchedEffect(current) { if (current != text) text = current }
                SettingsTextFieldRow(
                    label = spec.label,
                    supporting = spec.description,
                    value = text,
                    onValueChange = { text = it },
                    placeholder = spec.default,
                    onCommit = { if (text != current) ui.onChange(extensionId, spec.key, text) },
                )
            }
        }
    }
}

/** Per-capability grant switches for extensions that declare Extension-API capabilities. */
@Composable
private fun CapabilityToggles(extensionId: String, capabilities: List<String>) {
    val grants = LocalExtensionCapabilities.current
    Text(
        text = "API capabilities",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    capabilities.forEach { capability ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(capability, style = MaterialTheme.typography.bodyMedium)
                Text(
                    text = when (capability) {
                        "exec" -> "Run commands in the Linux runtime (as root)"
                        "fs" -> "Read and write project files"
                        "workbench" -> "Open files/URLs, show notices, read the focused file"
                        else -> "Extension-defined capability"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = grants.grantedFor(extensionId, capability),
                onCheckedChange = { grants.onSetGranted(extensionId, capability, it) },
            )
        }
    }
}

/** "Keep running in background" switch for extensions with a web UI (e.g. the OpenChamber chat
 *  keeps its agent session alive when its right-drawer panel is closed). */
@Composable
private fun KeepAliveToggle(extensionId: String) {
    val keepAlive = LocalExtensionKeepAlive.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Keep running in background", style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "Keep the panel alive (agent session, unsent input) when you close or switch away from it",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = keepAlive.enabledFor(extensionId),
            onCheckedChange = { keepAlive.onSetEnabled(extensionId, it) },
        )
    }
}

/** Per-extension activation-mode selector (auto-start / on-demand / manual). Three options, so it
 *  follows the app's >2-options-become-a-dropdown standard; the current mode's blurb sits below. */
@Composable
private fun ActivationSelector(extensionId: String) {
    val activation = LocalExtensionActivation.current
    val mode = activation.modeFor(extensionId)
    SettingsDropdownRow(
        label = "Activation",
        options = ExtensionActivation.entries.map { it.name },
        selected = mode.name,
        onSelect = { activation.onChange(extensionId, ExtensionActivation.valueOf(it)) },
        optionLabel = { ExtensionActivation.valueOf(it).label },
    )
    Text(
        text = mode.blurb,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

private val ExtensionActivation.label: String
    get() = when (this) {
        ExtensionActivation.AutoStart -> "Auto-start"
        ExtensionActivation.OnDemand -> "On-demand"
        ExtensionActivation.Manual -> "Manual"
    }

private val ExtensionActivation.blurb: String
    get() = when (this) {
        ExtensionActivation.AutoStart -> "Active from launch — always on."
        ExtensionActivation.OnDemand -> "Activates when you open a file this extension supports."
        ExtensionActivation.Manual -> "Disabled — this extension's features stay off until you switch modes."
    }

/** Compact author line for list rows: "by X", "by X, Y", or "by X +N" when the co-author list is long. */
private fun authorLabel(primary: String, others: List<String>): String {
    if (others.isEmpty()) return "by $primary"
    val inline = (listOf(primary) + others).joinToString(", ")
    return if (others.size <= 2 && inline.length <= 28) "by $inline" else "by $primary +${others.size}"
}

/** Full author line for the detail page: "by X" plus a clearly-labelled co-author list. */
private fun authorDetail(primary: String, others: List<String>): String =
    if (others.isEmpty()) "by $primary" else "by $primary · with ${others.joinToString(", ")}"

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

/** One row of the unified extensions list (marketplace entries + installed-only extensions). */
private data class ExtensionRow(
    val id: String,
    val name: String,
    val primaryAuthor: String,
    val otherAuthors: List<String>,
    val description: String,
    val type: ExtensionType,
    val status: ManagerItemStatus,
    val installed: Boolean,
    val iconFile: File?,
    val iconUrl: String?,
)

/** Merge marketplace + installed into one list, filtered by [query] and sorted installed-first. */
private fun buildExtensionRows(
    available: List<MarketplaceEntry>,
    installed: List<InstalledExtension>,
    query: String,
): List<ExtensionRow> {
    val installedById = installed.associateBy { it.id }
    val availableIds = available.map { it.id }.toSet()
    val fromMarket = available.map { e ->
        val inst = installedById[e.id]
        ExtensionRow(
            id = e.id,
            name = e.name,
            primaryAuthor = e.primaryAuthor,
            otherAuthors = e.otherAuthors,
            description = e.description ?: entrySubtitle(e),
            type = e.type,
            status = marketStatus(e, inst),
            installed = inst != null,
            iconFile = inst?.iconFile,
            iconUrl = e.iconUrl,
        )
    }
    val installedOnly = installed.filter { it.id !in availableIds }.map { ext ->
        ExtensionRow(
            id = ext.id,
            name = ext.name,
            primaryAuthor = ext.primaryAuthor,
            otherAuthors = ext.otherAuthors,
            description = ext.description.ifBlank { ext.type.name.lowercase() },
            type = ext.type,
            status = ManagerItemStatus.Installed,
            installed = true,
            iconFile = ext.iconFile,
            iconUrl = null,
        )
    }
    return (fromMarket + installedOnly)
        .filter { r ->
            query.isBlank() ||
                r.name.contains(query, ignoreCase = true) ||
                r.primaryAuthor.contains(query, ignoreCase = true) ||
                r.otherAuthors.any { it.contains(query, ignoreCase = true) } ||
                r.description.contains(query, ignoreCase = true)
        }
        .sortedWith(compareByDescending<ExtensionRow> { it.installed }.thenBy { it.name.lowercase() })
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
    autoInstalled: Boolean,
    onOpenExtensionDetail: (String) -> Unit,
    onOpenSdkDetail: (String) -> Unit,
    onOpenLspDetail: (String) -> Unit,
    onOpenDebugEngineDetail: (String) -> Unit,
) {
    if (deps.isEmpty) return
    Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    val pendingSuffix = if (autoInstalled) "installs automatically" else "optional"
    deps.extensions.forEach { id ->
        val name = available.firstOrNull { it.id == id }?.name ?: id
        DependencyRow(
            name = name,
            kind = if (id in installedIds) "extension · installed" else "extension · $pendingSuffix",
            onClick = { onOpenExtensionDetail(id) },
        )
    }
    deps.sdks.forEach { id -> DependencyRow(name = id, kind = "toolchain · $pendingSuffix", onClick = { onOpenSdkDetail(id) }) }
    deps.lsps.forEach { id -> DependencyRow(name = id, kind = "language server · $pendingSuffix", onClick = { onOpenLspDetail(id) }) }
    deps.dbg.forEach { id -> DependencyRow(name = id, kind = "debugger · $pendingSuffix", onClick = { onOpenDebugEngineDetail(id) }) }
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
                if (!entry.requires.isEmpty) {
                    Text(
                        "Required items are installed automatically; if any of them fails, the " +
                            "extension isn't installed.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                RequiredGroup(entry.requires, available, installedIds)
                SuggestedGroup(entry.suggests, available, installedIds, busy, onInstall)
            }
        },
        confirmButton = { TextButton(onClick = onProceed, enabled = !busy) { Text("Install ${entry.name}") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun RequiredGroup(
    deps: ExtensionDeps,
    available: List<MarketplaceEntry>,
    installedIds: Set<String>,
) {
    if (deps.isEmpty) return
    Text("Required", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
    val status: @Composable (installed: Boolean) -> Unit = { installed ->
        Text(
            if (installed) "Installed" else "Auto-install",
            style = MaterialTheme.typography.labelMedium,
            color = if (installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    deps.extensions.forEach { id ->
        val depEntry = available.firstOrNull { it.id == id }
        DependencyRow(name = depEntry?.name ?: id, kind = "extension") { status(id in installedIds) }
    }
    deps.sdks.forEach { id -> DependencyRow(name = id, kind = "toolchain") { status(false) } }
    deps.lsps.forEach { id -> DependencyRow(name = id, kind = "language server") { status(false) } }
    deps.dbg.forEach { id -> DependencyRow(name = id, kind = "debugger") { status(false) } }
}

@Composable
private fun SuggestedGroup(
    deps: ExtensionDeps,
    available: List<MarketplaceEntry>,
    installedIds: Set<String>,
    busy: Boolean,
    onInstall: (MarketplaceEntry) -> Unit,
) {
    if (deps.isEmpty) return
    Text("Suggested", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
    deps.sdks.forEach { id -> DependencyRow(name = id, kind = "toolchain · install via Toolchains") {} }
    deps.lsps.forEach { id -> DependencyRow(name = id, kind = "language server · install via Toolchains") {} }
    deps.dbg.forEach { id -> DependencyRow(name = id, kind = "debugger · install via Toolchains") {} }
}

@Composable
private fun DependencyRow(
    name: String,
    kind: String,
    onClick: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyMedium)
            Text(kind, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing()
        if (onClick != null) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}


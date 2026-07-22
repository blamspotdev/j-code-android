package dev.jcode.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** Install state shown across the SDK / LSP / Extension managers. */
enum class ManagerItemStatus { NotInstalled, Installed, UpdateAvailable }

/** One installable version in a detail-page picker: the clean [value] used for install plus an optional
 *  presentational [tag] (e.g. "LTS Jod") shown as a badge. */
data class VersionOption(val value: String, val tag: String? = null)

/** Compact status chip used in the manager list rows and detail headers. */
@Composable
fun ManagerStatusChip(
    status: ManagerItemStatus,
    checking: Boolean = false,
    checkingLabel: String = "Checking…",
    modifier: Modifier = Modifier,
    /** Show a small progress ring alongside the label while [checking] (detail header only). */
    spinner: Boolean = false,
) {
    val (text, active) = when {
        checking -> checkingLabel to false
        status == ManagerItemStatus.UpdateAvailable -> "Update available" to true
        status == ManagerItemStatus.Installed -> "Installed" to true
        else -> "Not installed" to false
    }
    Surface(
        modifier = modifier,
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (checking && spinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(11.dp),
                    strokeWidth = 1.5.dp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Shared header for the Extensions / SDK / LSP manager panels: a title with icon-only Search and
 * Refresh buttons, an "N installed" count, and (when Search is toggled on) a filter field. The
 * panel owns the [query]/[searchActive] state and does the actual filtering + installed-first sort.
 */
@Composable
fun ManagerPanelHeader(
    title: String,
    installedCount: Int,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    searchActive: Boolean = false,
    onToggleSearch: () -> Unit = {},
    query: String = "",
    onQueryChange: (String) -> Unit = {},
    searchPlaceholder: String = "Search",
    onManage: (() -> Unit)? = null,
    manageContentDescription: String = "Manage",
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            if (onManage != null) {
                HeaderIconButton(
                    icon = LocalIconBundle.current[JCodeIcon.Settings],
                    contentDescription = manageContentDescription,
                    onClick = onManage,
                )
            }
            HeaderIconButton(
                icon = LocalIconBundle.current[JCodeIcon.Search],
                contentDescription = "Search",
                onClick = onToggleSearch,
                active = searchActive,
            )
            if (busy) {
                Box(modifier = Modifier.size(36.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            } else {
                HeaderIconButton(
                    icon = LocalIconBundle.current[JCodeIcon.Refresh],
                    contentDescription = "Refresh",
                    onClick = onRefresh,
                )
            }
        }
        Text(
            text = "$installedCount installed",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (searchActive) {
            ManagerSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = searchPlaceholder,
            )
        }
    }
}

/** Compact single-line search field (~36dp) — the default OutlinedTextField's 56dp min height and
 *  padding are too bulky for the dense manager panels. */
@Composable
private fun ManagerSearchField(
    query: String,
    onQueryChange: (String) -> Unit,
    placeholder: String,
) {
    CompactSearchField(
        query = query,
        onQueryChange = onQueryChange,
        placeholder = placeholder,
        autoFocus = true,
    )
}

/** Compact filter pill for manager panels — matches the tab-pill styling, denser than M3 FilterChip. */
@Composable
fun ManagerFilterChip(
    selected: Boolean,
    label: String,
    onClick: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        } else {
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.32f)
        },
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
        )
    }
}

@Composable
private fun HeaderIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    active: Boolean = false,
) {
    JcTooltip(contentDescription) {
        IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(18.dp),
                tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A compact, clickable manager list row: name + one-line description + trailing status chip.
 * Actions live on the detail page, so the drawer stays dense.
 */
@Composable
fun ManagerListRow(
    name: String,
    description: String,
    status: ManagerItemStatus,
    onClick: () -> Unit,
    checking: Boolean = false,
    checkingLabel: String = "Checking…",
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        leading?.invoke()
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        ManagerStatusChip(status = status, checking = checking, checkingLabel = checkingLabel)
    }
}

/** A titled section/header card used in the manager panels. When [collapsible] is set, the header
 *  toggles a chevron and hides its body; collapse state is session-only, keyed by [title], and starts
 *  from [defaultExpanded]. */
@Composable
fun ManagerSectionCard(
    title: String,
    description: String,
    collapsible: Boolean = false,
    defaultExpanded: Boolean = true,
    content: @Composable () -> Unit,
) {
    var expanded by rememberSaveable(title) { mutableStateOf(defaultExpanded) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (collapsible) Modifier.clickable { expanded = !expanded } else Modifier),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(text = title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = description,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (collapsible) {
                    Icon(
                        imageVector = jcIcon(if (expanded) JCodeIcon.ChevronUp else JCodeIcon.ChevronDown),
                        contentDescription = if (expanded) "Collapse" else "Expand",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            if (collapsible) {
                AnimatedVisibility(visible = expanded) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
                }
            } else {
                content()
            }
        }
    }
}

@Composable
fun ManagerSummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
fun ManagerNoticeCard(title: String, message: String) {
    Surface(color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.55f)) {
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
            Text(text = message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
fun CompactFilledButton(
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
fun CompactOutlinedButton(
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

/**
 * Shared detail-page body for a manager item: header (title + subtitle + status), description,
 * an optional [extra] slot (e.g. Extensions' samples / requirements), and the
 * install/update/uninstall actions. Rendered full-width as an in-editor page. Command output is not
 * shown here — every install/verify runs in the shared right-drawer Setup terminal.
 */
@Composable
fun ManagerDetailScreen(
    title: String,
    subtitle: String,
    description: String,
    status: ManagerItemStatus,
    busy: Boolean,
    actionsEnabled: Boolean,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onVerify: () -> Unit,
    modifier: Modifier = Modifier,
    busyLabel: String? = null,
    showActions: Boolean = true,
    showVerify: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    onManage: (() -> Unit)? = null,
    manageLabel: String = "Manage",
    /** Installable versions, newest first (index 0 is treated as "latest"), each with an optional tag
     *  (e.g. "LTS Jod"). Empty = no version picker. */
    availableVersions: List<VersionOption> = emptyList(),
    /** Currently-installed versions, newest first. For [multiVersion] the first is the default (on PATH). */
    installedVersions: List<String> = emptyList(),
    /** When true, several versions coexist and each is removable independently. */
    multiVersion: Boolean = false,
    /** Whether the available-versions list is still being fetched (shows a spinner in the picker). */
    versionsLoading: Boolean = false,
    onInstallVersion: (String) -> Unit = {},
    onUninstallVersion: (String) -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    val hasVersions = availableVersions.isNotEmpty() || versionsLoading
    var selectedVersion by remember(availableVersions) {
        mutableStateOf(availableVersions.firstOrNull()?.value ?: "latest")
    }
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (subtitle.isNotBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ManagerStatusChip(status = status, checking = busy, checkingLabel = busyLabel ?: "Checking…", spinner = true)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (description.isNotBlank()) {
            Text(text = description, style = MaterialTheme.typography.bodyMedium)
        }

        extra()

        if (showActions && hasVersions) {
            VersionSection(
                multiVersion = multiVersion,
                availableVersions = availableVersions,
                installedVersions = installedVersions,
                selectedVersion = selectedVersion,
                loading = versionsLoading,
                enabled = actionsEnabled,
                onSelectVersion = { selectedVersion = it },
                onUninstallVersion = onUninstallVersion,
            )
        }

        if (onManage != null) {
            CompactFilledButton(manageLabel, onClick = onManage, enabled = actionsEnabled, modifier = Modifier.fillMaxWidth())
        }

        if (showActions) {
            val installed = status == ManagerItemStatus.Installed || status == ManagerItemStatus.UpdateAvailable
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasVersions) {
                    val versionInstalled = selectedVersion in installedVersions
                    CompactFilledButton(
                        text = (if (versionInstalled) "Reinstall " else "Install ") + shortVersionLabel(selectedVersion),
                        onClick = { onInstallVersion(selectedVersion) },
                        enabled = actionsEnabled && !versionsLoading,
                        modifier = Modifier.weight(1f),
                    )
                } else if (!installed) {
                    CompactFilledButton("Install", onClick = onInstall, enabled = actionsEnabled, modifier = Modifier.weight(1f))
                } else {
                    CompactFilledButton(
                        text = if (status == ManagerItemStatus.UpdateAvailable) "Update" else "Reinstall",
                        onClick = onUpdate,
                        enabled = actionsEnabled,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (showVerify) {
                    CompactOutlinedButton("Verify", onClick = onVerify, enabled = actionsEnabled, modifier = Modifier.weight(1f))
                }
                // Multi-version tools are removed per-version in the list above; single-version keeps a global Uninstall.
                if (!(hasVersions && multiVersion)) {
                    CompactOutlinedButton("Uninstall", onClick = onUninstall, enabled = installed && actionsEnabled, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

/** Newest-first version chosen from a picker, plus the installed-versions list for [multiVersion] tools. */
@Composable
private fun VersionSection(
    multiVersion: Boolean,
    availableVersions: List<VersionOption>,
    installedVersions: List<String>,
    selectedVersion: String,
    loading: Boolean,
    enabled: Boolean,
    onSelectVersion: (String) -> Unit,
    onUninstallVersion: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            text = if (multiVersion) "Versions" else "Version",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (multiVersion && installedVersions.isNotEmpty()) {
            installedVersions.forEachIndexed { index, version ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text = version, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (index == 0) {
                        Text(
                            text = "default",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    CompactOutlinedButton("Remove", onClick = { onUninstallVersion(version) }, enabled = enabled)
                }
            }
            Text(
                text = "Install another version",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VersionDropdown(
            versions = availableVersions,
            selected = selectedVersion,
            installedVersions = installedVersions,
            loading = loading,
            enabled = enabled,
            onSelect = onSelectVersion,
        )
    }
}

@Composable
private fun VersionDropdown(
    versions: List<VersionOption>,
    selected: String,
    installedVersions: List<String>,
    loading: Boolean,
    enabled: Boolean,
    onSelect: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val latest = versions.firstOrNull()?.value
    val selectedTag = versions.firstOrNull { it.value == selected }?.tag
    Box {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier
                    .clickable(enabled = enabled && !loading && versions.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (loading) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    Text(
                        text = "Loading versions…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                } else {
                    Text(
                        text = versionLabel(selected, latest),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (selectedTag != null) VersionBadge(selectedTag)
                    Spacer(modifier = Modifier.weight(1f))
                    Text(text = "▾", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            versions.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(versionLabel(option.value, latest))
                            if (option.tag != null) VersionBadge(option.tag)
                            if (option.value in installedVersions) {
                                Text(
                                    text = "installed",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    },
                    onClick = {
                        onSelect(option.value)
                        expanded = false
                    },
                )
            }
        }
    }
}

/** Small accent pill for a version tag such as "LTS Jod". */
@Composable
private fun VersionBadge(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

private fun versionLabel(version: String, latest: String?): String =
    if (latest != null && version == latest) "$version · latest" else version

private fun shortVersionLabel(version: String): String =
    if (version.length > 14) version.take(13) + "…" else version

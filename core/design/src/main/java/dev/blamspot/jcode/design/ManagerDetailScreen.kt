package dev.blamspot.jcode.design

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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
    /**
     * Whether *acquiring* is possible — Install, Update and per-version installs. Removal is not
     * gated by it: something already installed must stay removable even when it can no longer be
     * installed, which is exactly the case for an extension this app version has outgrown.
     */
    installEnabled: Boolean = true,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    modifier: Modifier = Modifier,
    busyLabel: String? = null,
    showActions: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    /** Installable versions, newest first (index 0 is treated as "latest"), each with an optional tag
     *  (e.g. "LTS Jod"). Empty = no version picker. */
    availableVersions: List<VersionOption> = emptyList(),
    /** Currently-installed versions, newest first. */
    installedVersions: List<String> = emptyList(),
    /** The installed version currently on PATH. Null = the newest installed one is active. */
    activeVersion: String? = null,
    /** When true, several versions coexist and each is removable independently. */
    multiVersion: Boolean = false,
    /** Whether this tool can switch which installed version is active (`nvm use` and friends). False
     *  for tools whose versions are all usable at once, which have nothing to switch. */
    canUseVersion: Boolean = false,
    /** Whether the available-versions list is still being fetched (shows a spinner in the picker). */
    versionsLoading: Boolean = false,
    /** 0..100 reported by the running install itself; null while it reports nothing (the chip then
     *  keeps its indeterminate spinner). Only shown while [busy]. */
    progressPercent: Int? = null,
    /** What the install is doing at [progressPercent], e.g. "Downloading Android platform". */
    progressLabel: String? = null,
    onInstallVersion: (String) -> Unit = {},
    onUninstallVersion: (String) -> Unit = {},
    onUseVersion: (String) -> Unit = {},
    extra: @Composable () -> Unit = {},
) {
    // Everything installed has to be selectable, not just what is installable today: a listing script
    // typically offers the newest release of each line, so an older installed patch appears in neither
    // list and would have no way to be switched to or removed.
    val versionOptions = remember(availableVersions, installedVersions) {
        val offered = availableVersions.mapTo(mutableSetOf()) { it.value }
        availableVersions + installedVersions.filterNot { it in offered }.map { VersionOption(it) }
    }
    val hasVersions = versionOptions.isNotEmpty() || versionsLoading
    var selectedVersion by remember(versionOptions) {
        mutableStateOf(versionOptions.firstOrNull()?.value ?: "latest")
    }
    val activeInstalled = activeVersion ?: installedVersions.firstOrNull()
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.lg),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            leading?.invoke()
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Text(text = title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.ms)) {
                    if (subtitle.isNotBlank()) {
                        Text(text = subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    ManagerStatusChip(status = status, checking = busy, checkingLabel = busyLabel ?: "Checking…", spinner = true)
                }
            }
        }

        if (busy && progressPercent != null) {
            ManagerProgress(percent = progressPercent, label = progressLabel)
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Actions sit directly under the header: what you came to do, before what it is. The version
        // picker travels with them because it is what the install button acts on — leaving it below
        // would put "Install v26.7.0" above the control that chooses the version.
        if (showActions && hasVersions) {
            VersionSection(
                multiVersion = multiVersion,
                versions = versionOptions,
                installedVersions = installedVersions,
                activeVersion = activeInstalled,
                selectedVersion = selectedVersion,
                loading = versionsLoading,
                enabled = actionsEnabled,
                onSelectVersion = { selectedVersion = it },
            )
        }

        if (showActions) {
            val installed = status == ManagerItemStatus.Installed || status == ManagerItemStatus.UpdateAvailable
            // Sized to their labels, like every other action pair in the app (a source card's
            // Install/Remove, a version row's Remove). Stretching two buttons across the page put a
            // 900px "Install" on a landscape tablet. `fill = false` keeps them intrinsic while still
            // capping each at half the row, so a long version label cannot overflow a narrow phone.
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (hasVersions) {
                    val versionInstalled = selectedVersion in installedVersions
                    // Picking an installed version you are not on means you want to switch to it, so
                    // that becomes the filled action and re-installing steps back to an outlined one.
                    // Exactly one button is ever filled, and it is the likely intent.
                    val switchable = canUseVersion && versionInstalled && selectedVersion != activeInstalled
                    if (switchable) {
                        CompactFilledButton(
                            text = "Use",
                            onClick = { onUseVersion(selectedVersion) },
                            enabled = actionsEnabled && !versionsLoading,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    // Unversioned: the picker directly above already names what these act on.
                    val reinstallLabel = if (versionInstalled) "Reinstall" else "Install"
                    if (switchable) {
                        CompactOutlinedButton(
                            text = reinstallLabel,
                            onClick = { onInstallVersion(selectedVersion) },
                            enabled = actionsEnabled && !versionsLoading,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    } else {
                        CompactFilledButton(
                            text = reinstallLabel,
                            onClick = { onInstallVersion(selectedVersion) },
                            enabled = actionsEnabled && !versionsLoading,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                    // The one way to delete a single version now that they are not listed separately.
                    // Named with its version so it cannot be read as the whole-toolchain Uninstall.
                    if (versionInstalled && multiVersion) {
                        CompactOutlinedButton(
                            text = "Remove",
                            onClick = { onUninstallVersion(selectedVersion) },
                            enabled = actionsEnabled && !versionsLoading,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                    }
                } else if (!installed) {
                    CompactFilledButton(
                        text = "Install",
                        onClick = onInstall,
                        enabled = actionsEnabled && installEnabled,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                } else {
                    CompactFilledButton(
                        text = if (status == ManagerItemStatus.UpdateAvailable) "Update" else "Reinstall",
                        onClick = onUpdate,
                        enabled = actionsEnabled && installEnabled,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                // Kept for multi-version tools too. Individual versions are removed in the list above,
                // but "Uninstall" means the whole toolchain — which for something like the Android
                // SDK is more than its versions: removing every platform would still leave the
                // command-line tools, build-tools and Gradle behind with no way to get rid of them.
                CompactOutlinedButton(
                    text = "Uninstall",
                    onClick = onUninstall,
                    enabled = installed && actionsEnabled,
                    modifier = Modifier.weight(1f, fill = false),
                )
            }
        }

        if (description.isNotBlank()) {
            // Reflowed, not drawn as written: these are Markdown paragraphs hard-wrapped at about
            // 80 columns by whoever authored them, and a phone wraps at far fewer. See
            // [reflowDescription] for which breaks survive.
            val prose = remember(description) { reflowDescription(description) }
            Text(text = prose, style = MaterialTheme.typography.bodyMedium)
        }

        extra()
    }
}

/**
 * Determinate progress for a running install: the percentage the script reported, what it is doing,
 * and a bar. The same numbers are also printed into the Setup terminal, so the two agree.
 */
@Composable
private fun ManagerProgress(percent: Int, label: String?) {
    val clamped = percent.coerceIn(0, 100)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.s)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
            Text(
                text = label?.takeIf { it.isNotBlank() } ?: "Working…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$clamped%",
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        LinearProgressIndicator(
            progress = { clamped / 100f },
            modifier = Modifier.fillMaxWidth().height(4.dp),
        )
    }
}

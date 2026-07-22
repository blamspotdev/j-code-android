package dev.jcode.feature.sdkmanager

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.core.distro.SdkCatalogAction
import dev.jcode.core.distro.SdkCatalogEntry
import dev.jcode.core.distro.SdkCatalogState
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus
import dev.jcode.design.VersionOption

/**
 * SDK detail page (install/verify/remove one SDK). Browsing lives in the merged Toolchains panel;
 * this feature keeps only the full-width in-editor page.
 */
object SdkManagerFeature {

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
        onInstallVersion: (String, String) -> Unit = { _, _ -> },
        onUninstallVersion: (String, String) -> Unit = { _, _ -> },
        modifier: Modifier = Modifier,
    ) {
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true
        val running = state.runningEntryId == entry.id
        val versioned = entry.versionsScript.isNotBlank()
        ManagerDetailScreen(
            title = entry.name,
            subtitle = entry.category.label,
            description = entry.description,
            status = statusOf(entry.id, state),
            busy = state.checking || running,
            busyLabel = when (state.runningAction.takeIf { running }) {
                SdkCatalogAction.Install -> "Installing…"
                SdkCatalogAction.Verify -> "Verifying…"
                SdkCatalogAction.Uninstall -> "Removing…"
                null -> "Checking…"
            },
            actionsEnabled = environmentReady && state.runningEntryId == null && !state.checking,
            onInstall = { onInstall(entry.id) },
            onUpdate = { onUpdate(entry.id) },
            onUninstall = { onUninstall(entry.id) },
            onVerify = { onVerify(entry.id) },
            availableVersions = if (versioned) {
                state.availableVersions[entry.id].orEmpty().map { VersionOption(it.version, it.tag.ifBlank { null }) }
            } else {
                emptyList()
            },
            installedVersions = if (versioned) state.installedVersions[entry.id].orEmpty() else emptyList(),
            multiVersion = entry.multiVersion,
            versionsLoading = versioned && state.versionsLoadingEntryId == entry.id,
            onInstallVersion = { version -> onInstallVersion(entry.id, version) },
            onUninstallVersion = { version -> onUninstallVersion(entry.id, version) },
            modifier = modifier,
        )
    }

    private fun statusOf(id: String, state: SdkCatalogState): ManagerItemStatus = when {
        id in state.updatableEntryIds -> ManagerItemStatus.UpdateAvailable
        id in state.installedEntryIds -> ManagerItemStatus.Installed
        else -> ManagerItemStatus.NotInstalled
    }
}

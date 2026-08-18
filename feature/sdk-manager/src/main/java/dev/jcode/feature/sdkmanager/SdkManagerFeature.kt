package dev.jcode.feature.sdkmanager

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jcode.core.distro.CatalogProgress
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
        progress: CatalogProgress? = null,
        /** The entry the user pressed Install on, when that is this one and a *required* toolchain is
         *  what is currently running. Lets this page own the progress for the whole chain. */
        requestedEntryId: String? = null,
        onInstall: (String) -> Unit,
        onUpdate: (String) -> Unit,
        onUninstall: (String) -> Unit,
        onInstallVersion: (String, String) -> Unit = { _, _ -> },
        onUninstallVersion: (String, String) -> Unit = { _, _ -> },
        onUseVersion: (String, String) -> Unit = { _, _ -> },
        modifier: Modifier = Modifier,
    ) {
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true
        val running = state.runningEntryId == entry.id
        // This page is also "busy" while a toolchain it requires is being installed on its behalf.
        val prerequisite = !running && requestedEntryId == entry.id && state.runningEntryId != null
        val prerequisiteName = state.entries.firstOrNull { it.id == state.runningEntryId }?.name
        val versioned = entry.versionsScript.isNotBlank()
        ManagerDetailScreen(
            title = entry.name,
            subtitle = entry.category.label,
            description = entry.description,
            status = statusOf(entry.id, state),
            busy = state.checking || running || prerequisite,
            busyLabel = when {
                prerequisite -> "Installing ${prerequisiteName ?: "required tools"}…"
                else -> when (state.runningAction.takeIf { running }) {
                    SdkCatalogAction.Install -> "Installing…"
                    SdkCatalogAction.Uninstall -> "Removing…"
                    SdkCatalogAction.Use -> "Switching…"
                    null -> "Checking…"
                }
            },
            // A status sweep across the whole catalog is background work — it must not freeze the
            // page's actions. Only an action actually running does that, and the service serializes
            // on its own lock, so anything started during a check simply queues behind it.
            actionsEnabled = environmentReady && state.runningEntryId == null,
            onInstall = { onInstall(entry.id) },
            onUpdate = { onUpdate(entry.id) },
            onUninstall = { onUninstall(entry.id) },
            availableVersions = if (versioned) {
                state.availableVersions[entry.id].orEmpty().map { VersionOption(it.version, it.tag.ifBlank { null }) }
            } else {
                emptyList()
            },
            installedVersions = if (versioned) state.installedVersions[entry.id].orEmpty() else emptyList(),
            activeVersion = if (versioned) state.activeVersions[entry.id] else null,
            multiVersion = entry.multiVersion,
            canUseVersion = entry.useVersionScript.isNotBlank(),
            versionsLoading = versioned && state.versionsLoadingEntryId == entry.id,
            progressPercent = progress?.percent.takeIf { running || prerequisite },
            progressLabel = progress?.label,
            onInstallVersion = { version -> onInstallVersion(entry.id, version) },
            onUninstallVersion = { version -> onUninstallVersion(entry.id, version) },
            onUseVersion = { version -> onUseVersion(entry.id, version) },
            modifier = modifier,
        )
    }

    private fun statusOf(id: String, state: SdkCatalogState): ManagerItemStatus = when {
        id in state.updatableEntryIds -> ManagerItemStatus.UpdateAvailable
        id in state.installedEntryIds -> ManagerItemStatus.Installed
        else -> ManagerItemStatus.NotInstalled
    }
}

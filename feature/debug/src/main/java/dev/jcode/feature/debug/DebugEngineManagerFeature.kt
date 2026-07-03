package dev.jcode.feature.debug

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.jcode.core.distro.DebugEngineCatalogState
import dev.jcode.core.distro.DebugEngineEntry
import dev.jcode.core.distro.DistroEnvironmentState
import dev.jcode.design.ManagerDetailScreen
import dev.jcode.design.ManagerItemStatus

/**
 * Debug engine detail page (install/verify/remove one per-language debug adapter). Browsing lives in
 * the merged Toolchains panel; this feature keeps only the full-width in-editor page.
 */
object DebugEngineManagerFeature {

    /** Full-width detail page for one debug engine, opened as an in-editor page tab. */
    @Composable
    fun DetailPage(
        entry: DebugEngineEntry,
        state: DebugEngineCatalogState,
        environmentState: DistroEnvironmentState,
        onInstall: (String) -> Unit,
        onUpdate: (String) -> Unit,
        onUninstall: (String) -> Unit,
        onVerify: (String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        val environmentReady = environmentState.distroInstalled == true && environmentState.jcodeUserReady == true
        ManagerDetailScreen(
            title = entry.name,
            subtitle = entry.category,
            description = entry.description,
            status = statusOf(entry.id, state),
            busy = state.checking || state.runningEntryId == entry.id,
            actionsEnabled = environmentReady && state.runningEntryId == null && !state.checking,
            onInstall = { onInstall(entry.id) },
            onUpdate = { onUpdate(entry.id) },
            onUninstall = { onUninstall(entry.id) },
            onVerify = { onVerify(entry.id) },
            modifier = modifier,
        )
    }

    private fun statusOf(id: String, state: DebugEngineCatalogState): ManagerItemStatus = when {
        id in state.updatableEntryIds -> ManagerItemStatus.UpdateAvailable
        id in state.installedEntryIds -> ManagerItemStatus.Installed
        else -> ManagerItemStatus.NotInstalled
    }
}

package dev.blamspot.jcode.workbench

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.feature.marketplace.InstalledExtension
import dev.blamspot.jcode.feature.marketplace.isVsix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Runtime access for extension-backed right-drawer tabs, provided as a CompositionLocal (JCodeShell
 * ART-limit convention).
 */
class ExtensionDrawerActions(
    val extensions: StateFlow<List<InstalledExtension>>? = null,
    val exec: suspend (command: String, timeoutMs: Long) -> String = { _, _ -> "{}" },
    val apiRequest: suspend (ext: InstalledExtension, envelopeJson: String) -> String = { _, _ -> "{}" },
    val events: SharedFlow<Pair<String, String>>? = null,
    /** Reap all long-lived runtime services (e.g. the opencode agent) on app teardown. */
    val onStopAllServices: () -> Unit = {},
    /** Whether the extension may keep running while its drawer tab is not on screen (per-extension
     *  permission). The shell reads this to decide what to tear down when the selection changes. */
    val keepAliveFor: (extensionId: String) -> Boolean = { true },
    /** Starts a long-lived process in the Linux runtime, which is what runs an imported `.vsix`. */
    val spawnProcess: ((command: String) -> Process?)? = null,
    /** Surface a webview panel the extension created as an editor tab (`createWebviewPanel`). */
    val onOpenPanel: (extensionId: String, handle: String, title: String) -> Unit = { _, _, _ -> },
)

val LocalExtensionDrawerActions = compositionLocalOf { ExtensionDrawerActions() }

// Stable fallback so [installedVsixExtensions]'s collectAsState is always called unconditionally.
private val NoExtensions = MutableStateFlow<List<InstalledExtension>>(emptyList())

/**
 * The imported `.vsix` extensions, each of which gets its own right-drawer tab.
 *
 * A VS Code extension's whole purpose is a view, so every one that installs earns a tab rather than
 * having to be recognised by id — which is what the old hardcoded OpenChamber slot did, and why
 * importing the same extension as a `.vsix` could not fill it.
 */
@Composable
internal fun installedVsixExtensions(): List<InstalledExtension> {
    val actions = LocalExtensionDrawerActions.current
    val extensions by (actions.extensions ?: NoExtensions).collectAsState()
    return extensions.filter { it.isVsix }
}

/**
 * A `.vsix` extension's view, hosted in the right drawer.
 *
 * The session lives in [VsixViewHolder], so closing the drawer or switching tabs only detaches the
 * WebView and the extension keeps running. Tearing down a session whose "keep running in background"
 * permission is off is the shell's job, driven by which drawer tab is selected — this composable's
 * own lifetime is the wrong signal, because a rotation rebuilds it into the other layout and would
 * read as the user navigating away.
 */
@Composable
internal fun VsixDrawerContent(extension: InstalledExtension, modifier: Modifier = Modifier) {
    val actions = LocalExtensionDrawerActions.current
    val spawn = actions.spawnProcess
    if (spawn == null) {
        VsixDrawerPlaceholder(
            "${extension.name} needs the Linux runtime, and it isn't available yet.",
            modifier,
        )
        return
    }
    VsixExtensionView(
        extension = extension,
        spawnProcess = spawn,
        onApiRequest = { envelope -> actions.apiRequest(extension, envelope) },
        onOpenPanel = { handle, title -> actions.onOpenPanel(extension.id, handle, title) },
        modifier = modifier,
    )
}

@Composable
private fun VsixDrawerPlaceholder(text: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Space.lg),
        verticalArrangement = Arrangement.spacedBy(Space.ms),
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

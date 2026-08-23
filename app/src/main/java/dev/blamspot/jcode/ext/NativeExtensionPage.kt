package dev.blamspot.jcode.ext

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import dev.blamspot.jcode.WorkbenchNotices
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.ext.api.JCodeNativeExtension
import dev.blamspot.jcode.feature.marketplace.InstalledExtension
import java.io.File

/**
 * Hosts one native extension's page inside an editor tab.
 *
 * The plugin's composition is spliced straight into this one — that is the point of the native
 * contract, and the reason [NativeExtensionLoader] resolves it parent-first against JCode's own
 * Compose.
 *
 * A plugin that will not load is reported to the **Issues pane** rather than left as an empty tab.
 * A blank rectangle is the worst outcome available here: the user toggled to a designer and got
 * nothing, with no way to find out why. See [WorkbenchNotices].
 */
@Composable
internal fun NativeExtensionPage(
    extension: InstalledExtension,
    /** The file this surface was opened for; null for a surface that is not about one file. */
    file: File?,
    projectDir: File?,
    /**
     * Which of the plugin's surfaces to draw — [JCodeNativeExtension.Params.SURFACE_PANEL] for the
     * drawer, a plugin-defined route for one of its pages, null for the file-claim page.
     */
    view: String? = null,
    dark: Boolean,
    onSnackbar: (String) -> Unit,
    onShowSource: () -> Unit,
    readFile: (String) -> String?,
    writeFile: (String, String) -> Unit,
    /** Sends one extension-API envelope for this extension and returns the reply. */
    request: suspend (String) -> String,
    /** The workbench's extension events, already shared with the WebView hosts. */
    events: kotlinx.coroutines.flow.Flow<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val noticeSource = "Extension: ${extension.name}"

    // Loading is a plain function call, not a suspend one — a DexClassLoader over a local archive is
    // milliseconds — so it is remembered per extension rather than run in a LaunchedEffect, which
    // would flash an empty frame first.
    val resolved = remember(extension.id, extension.version) {
        runCatching { NativeExtensionLoader.resolve(context, extension) }
    }

    val failure = resolved.exceptionOrNull()
    LaunchedEffect(noticeSource, failure) {
        WorkbenchNotices.set(
            noticeSource,
            failure?.let { listOf(WorkbenchNotices.Notice(it.message ?: "The extension could not be loaded.")) }
                .orEmpty(),
        )
    }
    // The tab is going away, and with it the only thing that could clear this.
    DisposableEffect(noticeSource) {
        onDispose { WorkbenchNotices.set(noticeSource, emptyList()) }
    }

    val loaded = resolved.getOrNull()
    if (loaded == null) {
        Column(
            modifier = modifier.fillMaxSize().padding(Space.lg),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Text(
                text = "${extension.name} could not be loaded",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            Text(
                text = failure?.message ?: "Unknown error.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val (plugin: JCodeNativeExtension, _) = loaded
    val scope = rememberCoroutineScope()
    val host = remember(file?.path, view, scope) {
        NativeHostBridge(
            scope = scope,
            request = request,
            events = events,
            readFileText = readFile,
            writeFileText = writeFile,
            projectDirPath = { projectDir?.absolutePath },
            onSnackbar = onSnackbar,
            onIssues = { messages ->
                WorkbenchNotices.set(noticeSource, messages.map { WorkbenchNotices.Notice(it) })
            },
            onShowSource = onShowSource,
        )
    }

    val params = remember(file?.path, projectDir?.path, dark, view) {
        buildMap {
            file?.let { put(JCodeNativeExtension.Params.FILE, it.absolutePath) }
            projectDir?.let { put(JCodeNativeExtension.Params.PROJECT_DIR, it.absolutePath) }
            put(JCodeNativeExtension.Params.THEME, if (dark) "dark" else "light")
            view?.let { put(JCodeNativeExtension.Params.VIEW, it) }
        }
    }

    androidx.compose.foundation.layout.Box(modifier = modifier.fillMaxSize()) {
        plugin.Content(host, params)
    }
}

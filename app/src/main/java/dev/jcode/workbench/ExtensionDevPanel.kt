package dev.jcode.workbench

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jcode.design.JCodeTheme
import dev.jcode.feature.marketplace.ExtensionManifestValidator
import dev.jcode.feature.marketplace.InstalledExtension
import dev.jcode.feature.marketplace.ManifestIssue
import java.io.File
import kotlinx.coroutines.delay

private enum class DevPane(val label: String) { Inspector("Inspector"), Validator("Validator"), Log("Log") }

/**
 * The right-drawer Extension Dev tools (Developer options). Lists the **dev** (unsigned sideloaded)
 * extensions and, for the selected one, an Inspector (what the host resolved from the manifest), a
 * manifest Validator, and a live Log (its API/exec calls, host events, and WebView console). While
 * this panel is open it polls the dev extensions' manifests and auto-reloads on change, so an
 * external make-tool rebuild lands without a manual refresh. Reads [LocalExtensionDevState] +
 * [ExtensionDevLog] (which live elsewhere in the tree), mirroring [DevtoolsSidebarContent].
 */
@Composable
fun ExtensionDevSidebarContent(modifier: Modifier = Modifier) {
    val state = LocalExtensionDevState.current
    val exts = state.extensions
    var pane by remember { mutableStateOf(DevPane.Inspector) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    val selected = exts.firstOrNull { it.id == selectedId } ?: exts.firstOrNull()

    // Auto-reload: poll manifest mtimes while the panel is open; a change (e.g. a make-tool rebuild
    // that re-sideloaded the package) triggers a re-scan. Keyed on the id set so a reload that keeps
    // the same extensions doesn't restart the loop.
    LaunchedEffect(exts.map { it.id }) {
        if (exts.isEmpty()) return@LaunchedEffect
        fun snapshot() = exts.associate { it.id to File(it.dir, "extension.yaml").lastModified() }
        var last = snapshot()
        while (true) {
            delay(2000)
            val now = snapshot()
            if (now != last) {
                last = now
                state.onReload()
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DevPane.entries.forEach { p ->
                PillTab(label = p.label, selected = p == pane) { pane = p }
            }
            Box(Modifier.weight(1f))
            ActionText("Load") { state.onLoad() }
            ActionText("Reload") { state.onReload() }
            if (pane == DevPane.Log) ActionText("Clear") { ExtensionDevLog.clear() }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))

        if (exts.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                EmptyDevHint(
                    "No dev extensions loaded.\n\nTap “Load” to sideload an unsigned .jext — only unsigned " +
                        "packages are debuggable. Signed packages install but can't be inspected here.",
                )
            }
            return
        }

        // Extension selector (usually just one or two).
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            exts.forEach { e ->
                PillTab(label = e.name, selected = e.id == selected?.id) { selectedId = e.id }
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.28f))

        val target = selected
        if (target == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { EmptyDevHint("Select an extension.") }
            return
        }
        when (pane) {
            DevPane.Inspector -> InspectorPane(target, Modifier.weight(1f))
            DevPane.Validator -> ValidatorPane(target, state.hostApiVersion, Modifier.weight(1f))
            DevPane.Log -> LogPane(target.id, Modifier.weight(1f))
        }
    }
}

@Composable
private fun InspectorPane(ext: InstalledExtension, modifier: Modifier = Modifier) {
    val c = ext.contributes
    Column(modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        Field("id", ext.id)
        Field("version", ext.version ?: "—")
        Field("type", ext.type.name)
        Field("API", "min v${ext.apiMinVersion}${if (ext.apiCapabilities.isEmpty()) "" else "  ·  ${ext.apiCapabilities.joinToString(", ")}"}")
        ext.webUiEntry?.let { Field("web UI", it) }
        if (ext.languages.isNotEmpty()) {
            Field("languages", ext.languages.joinToString(", ") { "${it.languageId} (${it.fileExtensions.joinToString(" ")})" })
        }
        if (ext.templates.isNotEmpty()) Field("templates", ext.templates.joinToString(", ") { it.id })
        val deps = ext.requires.sdks + ext.requires.lsps + ext.requires.dbg
        if (deps.isNotEmpty()) Field("requires", deps.joinToString(", "))

        val contribs = buildList {
            if (c.editorStartActions.isNotEmpty()) add("editorStartActions: ${c.editorStartActions.joinToString(", ") { it.id }}")
            if (c.drawerActions.isNotEmpty()) add("drawerActions: ${c.drawerActions.joinToString(", ") { it.id }}")
            if (c.editorContextActions.isNotEmpty()) add("editorContextActions: ${c.editorContextActions.joinToString(", ") { it.id }}")
            if (c.explorerContextActions.isNotEmpty()) add("explorerContextActions: ${c.explorerContextActions.joinToString(", ") { it.id }}")
            if (c.explorerDecorations) add("explorerDecorations: true")
            if (c.runConfigPresets.isNotEmpty()) add("runConfigPresets: ${c.runConfigPresets.joinToString(", ") { it.id }}")
        }
        SectionLabel("contributes")
        if (contribs.isEmpty()) {
            Text("— none resolved —", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            contribs.forEach {
                Text(it, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
        }
    }
}

@Composable
private fun ValidatorPane(ext: InstalledExtension, hostApiVersion: Int, modifier: Modifier = Modifier) {
    // Re-validate whenever the manifest changes on disk (auto-reload rewrites it) — keying on its
    // mtime picks up edits even when the version string is unchanged, without re-reading every frame.
    val manifestMtime = File(ext.dir, "extension.yaml").lastModified()
    val issues = remember(ext.id, manifestMtime, hostApiVersion) {
        ExtensionManifestValidator.validate(ext, hostApiVersion)
    }
    if (issues.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text("No problems found ✓", style = MaterialTheme.typography.bodyMedium,
                    color = JCodeTheme.semanticColors.success)
            }
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize().padding(vertical = 4.dp)) {
        items(issues) { issue ->
            val color = when (issue.severity) {
                ManifestIssue.Severity.Error -> MaterialTheme.colorScheme.error
                ManifestIssue.Severity.Warning -> JCodeTheme.semanticColors.warning
                ManifestIssue.Severity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            val tag = when (issue.severity) {
                ManifestIssue.Severity.Error -> "ERROR"
                ManifestIssue.Severity.Warning -> "WARN"
                ManifestIssue.Severity.Info -> "INFO"
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(tag, color = color, fontFamily = FontFamily.Monospace, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Column(Modifier.weight(1f)) {
                    Text(issue.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface)
                    issue.path?.let {
                        Text(it, fontFamily = FontFamily.Monospace, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun LogPane(extId: String, modifier: Modifier = Modifier) {
    val all = ExtensionDevLog.entries
    // This extension's own request/response/console lines + broadcast events (which carry no id).
    val shown = all.filter { it.extId == extId || it.extId.isEmpty() }
    if (shown.isEmpty()) {
        Box(modifier.fillMaxSize()) {
            EmptyDevHint("This extension's API/exec calls, host events, and console.log appear here as it runs.")
        }
        return
    }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(shown.size) {
        if (shown.isNotEmpty()) listState.scrollToItem(shown.size - 1)
    }
    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        items(shown) { e ->
            val color = when (e.kind) {
                ExtensionDevLogEntry.Kind.Error -> MaterialTheme.colorScheme.error
                ExtensionDevLogEntry.Kind.Request -> MaterialTheme.colorScheme.primary
                ExtensionDevLogEntry.Kind.Response -> MaterialTheme.colorScheme.onSurfaceVariant
                ExtensionDevLogEntry.Kind.Event -> JCodeTheme.semanticColors.warning
                ExtensionDevLogEntry.Kind.Console -> MaterialTheme.colorScheme.onSurface
            }
            val prefix = when (e.kind) {
                ExtensionDevLogEntry.Kind.Event -> "event "
                ExtensionDevLogEntry.Kind.Console -> "console "
                else -> ""
            }
            Text(
                text = prefix + e.message,
                color = color,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
            )
        }
    }
}

@Composable
private fun Field(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("$label:", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 1.dp))
        Text(value, fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
}

@Composable
private fun PillTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 4.dp),
    )
}

@Composable
private fun EmptyDevHint(text: String) {
    Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

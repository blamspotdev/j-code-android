package dev.jcode.run

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jcode.core.config.RunConfig
import dev.jcode.core.config.RunConfigTerminal
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.SettingsTextFieldRow

/**
 * Structured editor for a project's build/run configuration (`.jcode/run.yaml`), opened as an
 * in-editor page. Edits a [RunConfig]: a display name, the port to open in the browser, and a list
 * of terminals (label + bash command). [suggestions] are detected/extension presets whose "Use"
 * prefills the form (never auto-saved). [onSave] persists the form to `run.yaml`. Fields use the
 * app's compact [SettingsTextFieldRow] so the page matches the rest of JCode.
 */
@Composable
fun RunConfigPage(
    initial: RunConfig,
    onSave: (RunConfig) -> Unit,
    modifier: Modifier = Modifier,
    suggestions: List<ProjectRunner.RunSuggestion> = emptyList(),
) {
    var name by remember { mutableStateOf(initial.name) }
    var port by remember { mutableStateOf(initial.readyPort.takeIf { it > 0 }?.toString().orEmpty()) }
    val terminals = remember { mutableStateListOf<RunConfigTerminal>().apply { addAll(initial.terminals) } }
    var dirty by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }
    var showPresets by remember { mutableStateOf(false) }

    fun buildConfig() = RunConfig(
        name = name.ifBlank { "Run" },
        readyPort = port.trim().toIntOrNull() ?: 0,
        terminals = terminals
            .filter { it.label.isNotBlank() || it.command.isNotBlank() }
            .map { it.copy(label = it.label.ifBlank { "Run" }) },
    )

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Build & Run configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Stored in this project's .jcode/run.yaml.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        if (suggestions.isNotEmpty()) {
            CompactOutlinedButton(
                text = "Presets (${suggestions.size})",
                onClick = { showPresets = true },
                modifier = Modifier.fillMaxWidth(),
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        SettingsTextFieldRow(
            label = "Name",
            value = name,
            onValueChange = { name = it; dirty = true },
        )
        SettingsTextFieldRow(
            label = "Ready port",
            supporting = "Opened in the browser when the run is ready — blank for none.",
            value = port,
            onValueChange = { port = it.filter(Char::isDigit); dirty = true },
            placeholder = "e.g. 5173",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Text("Terminals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Each terminal runs its bash command in its own tab, in start order.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        terminals.forEachIndexed { index, terminal ->
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Terminal ${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        CompactOutlinedButton(text = "Remove", onClick = { terminals.removeAt(index); dirty = true })
                    }
                    SettingsTextFieldRow(
                        label = "Label (tab name)",
                        value = terminal.label,
                        onValueChange = { terminals[index] = terminal.copy(label = it); dirty = true },
                    )
                    SettingsTextFieldRow(
                        label = "Command (bash)",
                        value = terminal.command,
                        onValueChange = { terminals[index] = terminal.copy(command = it); dirty = true },
                        singleLine = false,
                        minLines = 4,
                        monospace = true,
                    )
                }
            }
        }

        CompactOutlinedButton(
            text = "Add terminal",
            onClick = { terminals.add(RunConfigTerminal("Run", "")); dirty = true },
            modifier = Modifier.fillMaxWidth(),
        )

        CompactFilledButton(
            text = if (savedOnce && !dirty) "Saved" else "Save",
            onClick = { onSave(buildConfig()); savedOnce = true; dirty = false },
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (showPresets) {
        PresetsDialog(
            suggestions = suggestions,
            onUse = { suggestion ->
                name = suggestion.config.name
                port = suggestion.config.readyPort.takeIf { it > 0 }?.toString().orEmpty()
                terminals.clear()
                terminals.addAll(suggestion.config.terminals)
                dirty = true
                showPresets = false
            },
            onDismiss = { showPresets = false },
        )
    }
}

/** Modal listing the detected/extension run presets; picking one prefills the form and closes. */
@Composable
private fun PresetsDialog(
    suggestions: List<ProjectRunner.RunSuggestion>,
    onUse: (ProjectRunner.RunSuggestion) -> Unit,
    onDismiss: () -> Unit,
) {
    // Cap the scrollable list to ~half the viewport (never past 340dp) so the header + Close button
    // stay on-screen even in short phone-landscape windows.
    val listMaxHeight = (LocalConfiguration.current.screenHeightDp * 0.5f).coerceIn(160f, 340f).dp
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Presets", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "Detected from this project's files (some from installed extensions). " +
                        "Use fills the form — review, then Save.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(
                    modifier = Modifier
                        .heightIn(max = listMaxHeight)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    suggestions.forEach { suggestion ->
                        PresetRow(
                            label = suggestion.label,
                            source = suggestion.source,
                            onUse = { onUse(suggestion) },
                        )
                    }
                }
                CompactOutlinedButton(
                    text = "Close",
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End),
                )
            }
        }
    }
}

/** A detected/extension run preset: label + provenance, with a compact "Use" to prefill the form. */
@Composable
private fun PresetRow(
    label: String,
    source: String,
    onUse: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f),
        shape = RoundedCornerShape(10.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable(onClick = onUse)
                .padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    source,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CompactFilledButton(text = "Use", onClick = onUse)
        }
    }
}

package dev.jcode.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jcode.core.config.RunConfig
import dev.jcode.core.config.RunConfigTerminal
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.SettingsTextFieldRow

/**
 * Structured editor for a project's run configuration (`.jcode/run.yaml`), opened as an in-editor
 * page. Edits a [RunConfig]: a display name, the port to open in the browser, and a list of
 * terminals (label + bash command). [onSave] persists the form to `run.yaml`. Preset/trigger
 * selection happens up front via the "Add run config" dialog, not here. Fields use the app's compact
 * [SettingsTextFieldRow] so the page matches the rest of JCode.
 */
@Composable
fun RunConfigPage(
    initial: RunConfig,
    onSave: (RunConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    var port by remember { mutableStateOf(initial.readyPort.takeIf { it > 0 }?.toString().orEmpty()) }
    // A run config is a single command. (Legacy multi-terminal configs seed from the first command;
    // the auto-detected full-stack recipes keep their side-by-side terminals — they aren't edited here.)
    var command by remember { mutableStateOf(initial.terminals.firstOrNull()?.command.orEmpty()) }
    var dirty by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }

    fun buildConfig() = RunConfig(
        name = name.ifBlank { "Run" },
        readyPort = port.trim().toIntOrNull() ?: 0,
        debugEntry = initial.debugEntry,
        // One command → one guest process whose PID the run binds to for running/done/killed status.
        terminals = listOf(RunConfigTerminal(label = name.ifBlank { "Run" }, command = command.trim())),
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
        SettingsTextFieldRow(
            label = "Command (bash)",
            supporting = "The one command this config runs. It executes verbosely in a terminal; the run " +
                "tracks its process — running until it exits (done) or you stop it (killed).",
            value = command,
            onValueChange = { command = it; dirty = true },
            placeholder = "e.g. dotnet run",
            singleLine = false,
            minLines = 4,
            monospace = true,
        )

        CompactFilledButton(
            text = if (savedOnce && !dirty) "Saved" else "Save",
            onClick = { onSave(buildConfig()); savedOnce = true; dirty = false },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

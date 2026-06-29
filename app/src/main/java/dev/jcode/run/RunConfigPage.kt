package dev.jcode.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.jcode.core.config.RunConfig
import dev.jcode.core.config.RunConfigTerminal

/**
 * Structured editor for a project's build/run configuration (`.jcode/run.yaml`), opened as an
 * in-editor page. Edits a [RunConfig]: a display name, the port to open in the browser, and a list
 * of terminals (label + bash command). [onSave] persists the form to `run.yaml`.
 */
@Composable
fun RunConfigPage(
    initial: RunConfig,
    onSave: (RunConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    var port by remember { mutableStateOf(initial.readyPort.takeIf { it > 0 }?.toString().orEmpty()) }
    val terminals = remember { mutableStateListOf<RunConfigTerminal>().apply { addAll(initial.terminals) } }
    var dirty by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }

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

        OutlinedTextField(
            value = name,
            onValueChange = { name = it; dirty = true },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter(Char::isDigit); dirty = true },
            label = { Text("Ready port — opened in the browser (blank = none)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Terminals", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "Each terminal runs its bash command in its own tab, in start order.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        terminals.forEachIndexed { index, terminal ->
            Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.12f)) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Terminal ${index + 1}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { terminals.removeAt(index); dirty = true }) { Text("Remove") }
                    }
                    OutlinedTextField(
                        value = terminal.label,
                        onValueChange = { terminals[index] = terminal.copy(label = it); dirty = true },
                        label = { Text("Label (tab name)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = terminal.command,
                        onValueChange = { terminals[index] = terminal.copy(command = it); dirty = true },
                        label = { Text("Command (bash)") },
                        minLines = 4,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        OutlinedButton(
            onClick = { terminals.add(RunConfigTerminal("Run", "")); dirty = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Add terminal") }

        FilledTonalButton(
            onClick = { onSave(buildConfig()); savedOnce = true; dirty = false },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (savedOnce && !dirty) "Saved" else "Save") }
    }
}

package dev.jcode.run

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp
import dev.jcode.core.config.BuildConfig
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.SettingsTextFieldRow

/**
 * Structured editor for one of a project's build tasks (part of `.jcode/run.yaml`), opened as an
 * in-editor page. A build task is a display name + a single bash command run in its own terminal
 * (e.g. `dotnet publish -c Release -o out`, `npm run build`). [onSave] persists it.
 */
@Composable
fun BuildConfigPage(
    initial: BuildConfig,
    onSave: (BuildConfig) -> Unit,
    modifier: Modifier = Modifier,
) {
    var name by remember { mutableStateOf(initial.name) }
    var command by remember { mutableStateOf(initial.command) }
    var dirty by remember { mutableStateOf(false) }
    var savedOnce by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Build configuration", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
        Text(
            "A build task for publish/deployment. Stored in this project's .jcode/run.yaml.",
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
            label = "Command (bash)",
            supporting = "Runs in a dedicated terminal — e.g. dotnet publish -c Release -o \"\$HOME/.jcode-build/out\".",
            value = command,
            onValueChange = { command = it; dirty = true },
            singleLine = false,
            minLines = 6,
            monospace = true,
        )

        CompactFilledButton(
            text = if (savedOnce && !dirty) "Saved" else "Save",
            onClick = {
                onSave(BuildConfig(name = name.ifBlank { "Build" }, command = command))
                savedOnce = true
                dirty = false
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

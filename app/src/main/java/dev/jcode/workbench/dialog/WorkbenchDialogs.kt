package dev.jcode.workbench.dialog

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import dev.jcode.MainViewModel
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.ScaffoldState

@Composable
internal fun OpenFolderTypeDialog(
    folderName: String,
    onDismiss: () -> Unit,
    onConfirm: (isWorkspace: Boolean) -> Unit,
) {
    var isWorkspace by rememberSaveable { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Set folder type") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "\"$folderName\" has no .jcode config yet. Is it a Project or a Workspace?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TypeOption(
                        label = "Project",
                        selected = !isWorkspace,
                        onSelect = { isWorkspace = false },
                        modifier = Modifier.weight(1f),
                    )
                    TypeOption(
                        label = "Workspace",
                        selected = isWorkspace,
                        onSelect = { isWorkspace = true },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(isWorkspace) }) {
                Text("Open")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun NewItemDialog(
    templates: List<ProjectTemplate>,
    scaffoldState: ScaffoldState,
    installedToolchains: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (MainViewModel.NewItemRequest) -> Unit,
) {
    if (scaffoldState.templateId != null) {
        ScaffoldProgressDialog(state = scaffoldState, onDismiss = onDismiss)
        return
    }

    var isWorkspace by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedTemplateId by rememberSaveable(templates) {
        mutableStateOf(templates.firstOrNull()?.id)
    }

    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

    // Left column: folder type + name. Right column (Project): template list; (Workspace): a hint.
    val primarySection: @Composable () -> Unit = {
        Text("Folder type", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TypeOption(
                label = "Project",
                selected = !isWorkspace,
                onSelect = { isWorkspace = false },
                modifier = Modifier.weight(1f),
            )
            TypeOption(
                label = "Workspace",
                selected = isWorkspace,
                onSelect = { isWorkspace = true },
                modifier = Modifier.weight(1f),
            )
        }
        TextField(
            value = name,
            onValueChange = { name = it },
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (isWorkspace) "Workspace name" else "Project name") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )
    }
    val detailSection: @Composable () -> Unit = {
        if (isWorkspace) {
            Text("Workspace", style = MaterialTheme.typography.labelLarge)
            Text(
                "A workspace is a container folder that holds multiple projects.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text("Template", style = MaterialTheme.typography.labelLarge)
            templates.forEach { template ->
                TemplateOption(
                    template = template,
                    selected = template.id == selectedTemplateId,
                    installedToolchains = installedToolchains,
                    onSelect = { selectedTemplateId = template.id },
                )
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = if (isLandscape) Modifier.fillMaxWidth(0.82f).widthIn(max = 760.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !isLandscape),
        title = { Text("New") },
        text = {
            if (isLandscape) {
                // Two columns side by side so the (tall) template list doesn't overflow the short
                // landscape dialog height.
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        primarySection()
                    }
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(max = 260.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        detailSection()
                    }
                }
            } else {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    primarySection()
                    detailSection()
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(
                        MainViewModel.NewItemRequest(
                            name = name,
                            isWorkspace = isWorkspace,
                            templateId = if (isWorkspace) null else selectedTemplateId,
                        ),
                    )
                },
                enabled = name.isNotBlank(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
private fun TypeOption(
    label: String,
    selected: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(label)
    }
}

@Composable
private fun TemplateOption(
    template: ProjectTemplate,
    selected: Boolean,
    installedToolchains: Set<String>,
    onSelect: () -> Unit,
) {
    val missing = template.requires.filter { it !in installedToolchains }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onSelect)
            .padding(end = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Column(modifier = Modifier.padding(top = 12.dp)) {
            Text(template.name, style = MaterialTheme.typography.bodyLarge)
            if (template.description.isNotBlank()) {
                Text(
                    template.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (template.requires.isNotEmpty()) {
                val requires = "Requires: ${template.requires.joinToString(", ")}"
                Text(
                    if (missing.isEmpty()) requires
                    else "$requires — install ${missing.joinToString(", ")} via SDK Manager",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ScaffoldProgressDialog(
    state: ScaffoldState,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (state.finished) onDismiss() },
        title = {
            Text(
                when {
                    state.errorMessage != null -> "Scaffold failed"
                    state.finished -> "Project ready"
                    else -> "Scaffolding…"
                },
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.running) {
                    if (state.totalSteps > 0) {
                        LinearProgressIndicator(
                            progress = { state.currentStep.toFloat() / state.totalSteps.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                    val counter = if (state.totalSteps > 0) " (${state.currentStep}/${state.totalSteps})" else ""
                    Text(
                        "${state.currentLabel ?: "Working…"}$counter",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                state.errorMessage?.let { message ->
                    Text(
                        message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (state.logLines.isNotEmpty()) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            text = state.logLines.takeLast(120).joinToString("\n"),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (state.finished) "Close" else "Hide")
            }
        },
    )
}

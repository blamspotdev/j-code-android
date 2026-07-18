package dev.jcode.workbench.dialog

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.jcode.ImportPhase
import dev.jcode.ImportProgress
import dev.jcode.MainViewModel
import dev.jcode.feature.marketplace.ProjectTemplate
import dev.jcode.feature.marketplace.TemplateInput
import kotlinx.coroutines.flow.StateFlow

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

/**
 * Collects [progress] and renders [ImportProgressDialog] in its own small scope, so the per-file
 * progress ticks recompose only this host — not the large JCodeApp body that would otherwise read
 * the flow.
 */
@Composable
internal fun ImportProgressHost(progress: StateFlow<ImportProgress?>) {
    val current by progress.collectAsStateWithLifecycle()
    current?.let { ImportProgressDialog(it) }
}

/**
 * Non-dismissable modal shown while an off-ext4 folder is scanned then copied into /sources. The scan
 * phase has no known total (indeterminate bar); the copy phase reports done/total files.
 */
@Composable
internal fun ImportProgressDialog(progress: ImportProgress) {
    Dialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(
                modifier = Modifier.widthIn(min = 280.dp, max = 360.dp).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (progress.phase == ImportPhase.Scanning) "Scanning folder" else "Importing folder",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "\"${progress.label}\"",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (progress.phase == ImportPhase.Copying && progress.total > 0) {
                    val fraction = (progress.done.toFloat() / progress.total).coerceIn(0f, 1f)
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "${progress.done} / ${progress.total} files",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        text = "Reading contents…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * After cloning while a User Workspace is open: open the cloned folder as the active project, or keep it
 * added to the workspace's project list to open later.
 */
@Composable
internal fun PostCloneDialog(
    projectName: String,
    onOpen: () -> Unit,
    onAdd: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAdd,
        title = { Text("Cloned '$projectName'") },
        text = {
            Text(
                "It was added to this workspace. Open it now, or keep it in the workspace to open later?",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Open folder") } },
        dismissButton = { TextButton(onClick = onAdd) { Text("Add to workspace") } },
    )
}

@Composable
internal fun NewItemDialog(
    templates: List<ProjectTemplate>,
    installedToolchains: Set<String>,
    onDismiss: () -> Unit,
    onConfirm: (MainViewModel.NewItemRequest) -> Unit,
    resolveDynamicOptions: suspend (String) -> List<String> = { emptyList() },
) {
    var isWorkspace by rememberSaveable { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var selectedTemplateId by rememberSaveable(templates) {
        mutableStateOf(templates.firstOrNull()?.id)
    }
    // 0 = pick folder type / name / template; 1 = configure the template's inputs.
    var step by rememberSaveable { mutableStateOf(0) }

    val selectedTemplate = if (isWorkspace) null else templates.firstOrNull { it.id == selectedTemplateId }
    val hasInputs = selectedTemplate?.inputs?.isNotEmpty() == true
    // Live values for the selected template's inputs, seeded from each input's default.
    val inputValues = remember(selectedTemplateId) {
        mutableStateMapOf<String, String>().apply {
            selectedTemplate?.inputs?.forEach { put(it.id, it.defaultValue) }
        }
    }
    // Inputs with an optionsCommand get their dropdown filled live from the runtime (e.g. installed
    // .NET SDKs); resolved async and merged over the static options, which stay the offline fallback.
    val dynamicOptions = remember(selectedTemplateId) { mutableStateMapOf<String, List<String>>() }
    LaunchedEffect(selectedTemplateId) {
        val template = selectedTemplate ?: return@LaunchedEffect
        for (input in template.inputs) {
            if (input.optionsCommand.isBlank()) continue
            val resolved = resolveDynamicOptions(input.optionsCommand)
            if (resolved.isNotEmpty()) {
                dynamicOptions[input.id] = resolved
                // Re-seed the value when the static default isn't among the live options.
                if ((inputValues[input.id] ?: "") !in resolved) inputValues[input.id] = resolved.first()
            }
        }
    }
    if (!hasInputs && step != 0) step = 0

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
        modifier = if (isLandscape && step == 0) Modifier.fillMaxWidth(0.82f).widthIn(max = 760.dp) else Modifier,
        properties = DialogProperties(usePlatformDefaultWidth = !(isLandscape && step == 0)),
        title = { Text(if (step == 1 && selectedTemplate != null) selectedTemplate.name else "New") },
        text = {
            if (step == 1 && selectedTemplate != null) {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        "Configure this project, then create it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    selectedTemplate.inputs.forEach { input ->
                        TemplateInputField(
                            input = input,
                            options = dynamicOptions[input.id] ?: input.options,
                            value = inputValues[input.id] ?: input.defaultValue,
                            onValue = { inputValues[input.id] = it },
                        )
                    }
                }
            } else if (isLandscape) {
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
                    if (step == 0 && hasInputs) {
                        step = 1
                    } else {
                        onConfirm(
                            MainViewModel.NewItemRequest(
                                name = name,
                                isWorkspace = isWorkspace,
                                templateId = if (isWorkspace) null else selectedTemplateId,
                                inputs = if (hasInputs) inputValues.toMap() else emptyMap(),
                            ),
                        )
                    }
                },
                enabled = name.isNotBlank(),
            ) {
                Text(if (step == 0 && hasInputs) "Next" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = { if (step == 1) step = 0 else onDismiss() }) {
                Text(if (step == 1) "Back" else "Cancel")
            }
        },
    )
}

@Composable
private fun TemplateInputField(
    input: TemplateInput,
    options: List<String>,
    value: String,
    onValue: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(input.label, style = MaterialTheme.typography.labelLarge)
        if (input.type == "select" && options.isNotEmpty()) {
            var expanded by remember { mutableStateOf(false) }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(value.ifBlank { "Select" }, modifier = Modifier.weight(1f))
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
                }
                DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                    options.forEach { option ->
                        DropdownMenuItem(
                            text = { Text(option) },
                            onClick = {
                                onValue(option)
                                expanded = false
                            },
                        )
                    }
                }
            }
        } else {
            OutlinedTextField(
                value = value,
                onValueChange = onValue,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
    }
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
                    else "$requires — ${missing.joinToString(", ")} will be installed during setup",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (missing.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}


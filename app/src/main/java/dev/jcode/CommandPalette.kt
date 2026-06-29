package dev.jcode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jcode.design.CommandRegistry
import dev.jcode.design.CommandSpec

/**
 * Ctrl/Cmd+Shift+P command palette. Sources its entries from the shared [CommandRegistry] (populated
 * by the workbench shell) and fuzzy-filters them. Renders as a centered dialog on large screens and
 * a bottom sheet on compact ones.
 */
@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun CommandPalette(
    visible: Boolean,
    compact: Boolean,
    onDismiss: () -> Unit,
) {
    if (!visible) return

    var query by rememberSaveable { mutableStateOf("") }
    val commands = remember(query) {
        CommandRegistry.all().filter { command ->
            command.isEnabled() && fuzzyMatches(query, "${command.group} ${command.title}")
        }
    }
    val focusRequester = remember { FocusRequester() }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            TextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
                placeholder = { Text("Type a command") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    commands.firstOrNull()?.action?.invoke()
                    onDismiss()
                }),
                singleLine = true,
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(commands, key = CommandSpec::id) { command ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(18.dp))
                            .clickable {
                                command.action()
                                onDismiss()
                            },
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f),
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Text(command.title, fontWeight = FontWeight.SemiBold)
                            Text(
                                text = command.group,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }

    if (compact) {
        ModalBottomSheet(onDismissRequest = onDismiss) {
            content()
        }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.widthIn(max = 560.dp),
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                content()
            }
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

private fun fuzzyMatches(query: String, candidate: String): Boolean {
    if (query.isBlank()) return true
    val needle = query.lowercase()
    val haystack = candidate.lowercase()
    var index = 0
    needle.forEach { ch ->
        index = haystack.indexOf(ch, startIndex = index)
        if (index < 0) return false
        index += 1
    }
    return true
}

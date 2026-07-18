package dev.jcode

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.jcode.design.CommandRegistry
import dev.jcode.design.CommandSpec
import dev.jcode.design.CompactSearchField
import dev.jcode.design.JCodeIcon
import dev.jcode.design.jcIcon

/**
 * Ctrl/Cmd+Shift+P command palette. Sources entries from the shared [CommandRegistry] (populated by
 * the workbench shell) and fuzzy-filters them. Uses JCode's compact vocabulary — the shared
 * [CompactSearchField] plus dense icon+title rows — so it reads like the manager panels and context
 * menus. Renders as a bottom sheet on compact windows and a centered dialog on large ones.
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
    // CommandRegistry.version is Compose state — reading it here re-filters when the shell (re)registers
    // commands, including the first population after a process-restore that reopened the palette.
    val commands = remember(query, CommandRegistry.version) {
        CommandRegistry.all().filter { command ->
            command.isEnabled() && fuzzyMatches(query, "${command.group} ${command.title}")
        }
    }

    fun run(command: CommandSpec) {
        command.action()
        onDismiss()
    }

    val content: @Composable () -> Unit = {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CompactSearchField(
                query = query,
                onQueryChange = { query = it },
                placeholder = "Search commands",
                onImeAction = {
                    // Only act on a real query — an empty-field Go must not fire whatever command
                    // happens to sit first in the registry.
                    if (query.isNotBlank()) commands.firstOrNull()?.let(::run) else onDismiss()
                },
            )
            if (commands.isEmpty()) {
                Text(
                    text = "No matching commands",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
                )
            } else {
                // Cap the list so the sheet/dialog stays compact instead of filling the screen.
                LazyColumn(modifier = Modifier.heightIn(max = 340.dp)) {
                    items(commands, key = CommandSpec::id) { command ->
                        CommandRow(command, onClick = { run(command) })
                    }
                }
            }
        }
    }

    if (compact) {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            content()
        }
    } else {
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                modifier = Modifier.widthIn(max = 520.dp),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
            ) {
                content()
            }
        }
    }
}

/** A single dense palette row: leading command glyph, title, and a trailing dim group label. */
@Composable
private fun CommandRow(command: CommandSpec, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 40.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(
            imageVector = jcIcon(command.icon ?: JCodeIcon.CommandPalette),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = command.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = command.group,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
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

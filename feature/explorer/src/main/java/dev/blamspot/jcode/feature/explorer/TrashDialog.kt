package dev.blamspot.jcode.feature.explorer

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactDestructiveButton
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JcTooltip
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.design.trashRetentionLabel
import dev.blamspot.jcode.fs.Trash
import dev.blamspot.jcode.fs.TrashEntry
import kotlinx.coroutines.launch

/**
 * The bin, and what can be done with what is in it.
 *
 * A dialog rather than a tab: this is a place to go looking for one thing that was deleted by
 * mistake, not a place to work, and a phone-sized modal reaches it in one tap from the toolbar the
 * delete was issued from.
 *
 * The list is every project's, not this one's — there is one bin — so each row says where its entry
 * came from. Restoring puts it back where it was, which may be a project other than the open one.
 */
@Composable
internal fun TrashDialog(
    trash: Trash,
    retentionDays: Int,
    onDismiss: () -> Unit,
    onSnackbar: (String) -> Unit,
    onRestored: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val entries = remember { mutableStateListOf<TrashEntry>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var confirmEmpty by remember { mutableStateOf(false) }

    suspend fun reload() {
        entries.clear()
        entries.addAll(trash.list())
    }

    LaunchedEffect(Unit) {
        // Sweeping on open as well as at startup: a session left running for days would otherwise
        // show entries the retention setting says are already gone.
        trash.sweep(retentionDays)
        reload()
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Trash") },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = when {
                        loading -> "Reading the Trash…"
                        entries.isEmpty() -> "Nothing here. Deleted files and folders are kept for " +
                            trashRetentionLabel(retentionDays).lowercase() + "."
                        else -> entries.size.toString() + " item" + (if (entries.size == 1) "" else "s") +
                            " · " + formatSize(entries.sumOf { it.sizeBytes }) +
                            " · kept for " + trashRetentionLabel(retentionDays).lowercase()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (entries.isEmpty()) return@Column
                HorizontalDivider(
                    modifier = Modifier.padding(top = Space.sm),
                    thickness = StrokeWidth.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp)) {
                    items(entries, key = { it.id }) { entry ->
                        TrashRow(
                            entry = entry,
                            enabled = !busy,
                            onRestore = {
                                busy = true
                                scope.launch {
                                    runCatching { trash.restore(context, entry) }
                                        .onSuccess { where ->
                                            onSnackbar(
                                                "Restored '" + entry.name + "'" +
                                                    if (where.substringAfterLast('/') != entry.name) {
                                                        " as '" + where.substringAfterLast('/') + "'"
                                                    } else {
                                                        ""
                                                    },
                                            )
                                            onRestored()
                                        }
                                        .onFailure { onSnackbar("Restore failed: " + it.message) }
                                    reload()
                                    busy = false
                                }
                            },
                            onPurge = {
                                busy = true
                                scope.launch {
                                    trash.purge(entry.id)
                                    reload()
                                    busy = false
                                }
                            },
                        )
                    }
                }
            }
        },
        // Close is the primary action and sits where a reflexive tap lands; emptying the bin is the
        // one thing in here that cannot be undone, so it is kept out of that slot.
        confirmButton = { CompactFilledButton(text = "Close", onClick = onDismiss) },
        dismissButton = {
            if (entries.isNotEmpty()) {
                CompactDestructiveButton(
                    text = "Empty Trash",
                    onClick = { confirmEmpty = true },
                    enabled = !busy,
                )
            }
        },
    )

    if (confirmEmpty) {
        val count = entries.size
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the Trash?") },
            text = {
                Text(
                    "This permanently destroys " + count + " item" + (if (count == 1) "" else "s") +
                        " (" + formatSize(entries.sumOf { it.sizeBytes }) + "). It cannot be undone.",
                )
            },
            confirmButton = {
                CompactDestructiveButton(
                    text = "Empty",
                    onClick = {
                        confirmEmpty = false
                        busy = true
                        scope.launch {
                            trash.empty()
                            reload()
                            busy = false
                            onSnackbar("Trash emptied")
                        }
                    },
                )
            },
            dismissButton = { CompactFilledButton(text = "Cancel", onClick = { confirmEmpty = false }) },
        )
    }
}

@Composable
private fun TrashRow(
    entry: TrashEntry,
    enabled: Boolean,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = Space.xxs),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        // The tree's own two glyphs and tints, so a row here reads as the thing that left it.
        Icon(
            imageVector = jcIcon(if (entry.isDirectory) JCodeIcon.Folder else JCodeIcon.Output),
            contentDescription = null,
            modifier = Modifier.size(IconSize.md),
            tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOf(
                    entry.location.ifBlank { "—" },
                    formatSize(entry.sizeBytes),
                    ago(entry.deletedAtMillis),
                ).joinToString(" · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        JcTooltip("Restore") {
            IconButton(onClick = onRestore, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.Restore),
                    contentDescription = "Restore",
                    modifier = Modifier.size(IconSize.md),
                    tint = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        JcTooltip("Delete forever") {
            IconButton(onClick = onPurge, enabled = enabled, modifier = Modifier.size(32.dp)) {
                Icon(
                    imageVector = jcIcon(JCodeIcon.Delete),
                    contentDescription = "Delete forever",
                    modifier = Modifier.size(IconSize.md),
                    tint = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** How long ago, at the coarseness a bin is read at — nobody restores by the second. */
private fun ago(millis: Long): String {
    val minutes = (System.currentTimeMillis() - millis) / 60_000L
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> minutes.toString() + "m ago"
        minutes < 60 * 24 -> (minutes / 60).toString() + "h ago"
        else -> (minutes / (60 * 24)).toString() + "d ago"
    }
}

private fun formatSize(bytes: Long): String {
    val kb = 1024L
    val mb = kb * 1024
    val gb = mb * 1024
    return when {
        bytes < kb -> bytes.toString() + " B"
        bytes < mb -> (bytes / kb).toString() + " KB"
        bytes < gb -> (bytes / mb).toString() + " MB"
        else -> {
            val tenths = bytes * 10 / gb
            (tenths / 10).toString() + "." + (tenths % 10).toString() + " GB"
        }
    }
}

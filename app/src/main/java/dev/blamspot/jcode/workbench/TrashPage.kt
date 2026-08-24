package dev.blamspot.jcode.workbench

import android.graphics.BitmapFactory
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactDestructiveButton
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.IconSize
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.JcTooltip
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth
import dev.blamspot.jcode.design.jcIcon
import dev.blamspot.jcode.design.trashRetentionLabel
import dev.blamspot.jcode.feature.explorer.LocalExplorerScmUi
import dev.blamspot.jcode.fs.Trash
import dev.blamspot.jcode.fs.TrashChild
import dev.blamspot.jcode.fs.TrashEntry
import dev.blamspot.jcode.fs.TrashListing
import dev.blamspot.jcode.humanSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Trash, as an editor page.
 *
 * A page rather than the dialog it started as, because the question the bin actually answers is "is
 * this the one I want back" — and that needs the file's contents, not its name. The list picks; the
 * pane beside it shows what is in the thing picked, including the files inside a trashed folder.
 *
 * The two panes sit side by side where there is width for both and take turns where there is not: a
 * 280dp list against a preview is a preview nobody can read on a phone in portrait.
 */
@Composable
fun TrashPage(
    trash: Trash,
    retentionDays: Int,
    onSnackbar: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scmUi = LocalExplorerScmUi.current
    val entries = remember { mutableStateListOf<TrashEntry>() }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var confirmEmpty by remember { mutableStateOf(false) }

    suspend fun reload() {
        val fresh = trash.list()
        entries.clear()
        entries.addAll(fresh)
        if (fresh.none { it.id == selectedId }) selectedId = fresh.firstOrNull()?.id
    }

    LaunchedEffect(retentionDays) {
        // Sweeping on open as well as at startup: a session left running for days would otherwise
        // show entries the retention setting says are already gone.
        trash.sweep(retentionDays)
        reload()
        loading = false
    }

    val selected = entries.firstOrNull { it.id == selectedId }

    fun restore(entry: TrashEntry) {
        busy = true
        scope.launch {
            runCatching { trash.restore(context, entry) }
                .onSuccess { where ->
                    val landed = where.substringAfterLast('/')
                    onSnackbar(
                        "Restored '" + entry.name + "'" +
                            if (landed != entry.name) " as '" + landed + "'" else "",
                    )
                    scmUi.onFsActivity?.invoke()
                }
                .onFailure { onSnackbar("Restore failed: " + it.message) }
            reload()
            busy = false
        }
    }

    fun purge(entry: TrashEntry) {
        busy = true
        scope.launch {
            trash.purge(entry.id)
            reload()
            busy = false
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        TrashBar(
            count = entries.size,
            bytes = entries.sumOf { it.sizeBytes },
            retentionDays = retentionDays,
            loading = loading,
            enabled = !busy && entries.isNotEmpty(),
            onEmpty = { confirmEmpty = true },
        )
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        if (entries.isEmpty()) {
            EmptyBin(loading, retentionDays, Modifier.weight(1f))
            return@Column
        }
        BoxWithConstraints(modifier = Modifier.weight(1f)) {
            val sideBySide = maxWidth >= SideBySideWidth
            if (sideBySide) {
                Row(modifier = Modifier.fillMaxSize()) {
                    EntryList(
                        entries = entries,
                        selectedId = selectedId,
                        onSelect = { selectedId = it.id },
                        modifier = Modifier.width(ListPaneWidth),
                    )
                    VerticalDivider(
                        thickness = StrokeWidth.hairline,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                    Box(modifier = Modifier.weight(1f)) {
                        selected?.let { entry ->
                            Preview(
                                trash = trash,
                                entry = entry,
                                busy = busy,
                                onBack = null,
                                onRestore = { restore(entry) },
                                onPurge = { purge(entry) },
                            )
                        }
                    }
                }
            } else {
                // Narrow: the list until something is picked, then the preview with a way back. Both
                // at once would leave the preview a few lines tall, which is not a preview.
                var showing by remember { mutableStateOf(false) }
                val entry = selected
                if (showing && entry != null) {
                    Preview(
                        trash = trash,
                        entry = entry,
                        busy = busy,
                        onBack = { showing = false },
                        onRestore = { restore(entry); showing = false },
                        onPurge = { purge(entry); showing = false },
                    )
                } else {
                    EntryList(
                        entries = entries,
                        selectedId = null,
                        onSelect = { selectedId = it.id; showing = true },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    if (confirmEmpty) {
        val count = entries.size
        AlertDialog(
            onDismissRequest = { confirmEmpty = false },
            title = { Text("Empty the Trash?") },
            text = {
                Text(
                    "This permanently destroys " + count + " item" + (if (count == 1) "" else "s") +
                        " (" + humanSize(entries.sumOf { it.sizeBytes }) + "). It cannot be undone.",
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

/** What the bin holds, and the one action that applies to all of it. */
@Composable
private fun TrashBar(
    count: Int,
    bytes: Long,
    retentionDays: Int,
    loading: Boolean,
    enabled: Boolean,
    onEmpty: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = Space.lg, vertical = Space.sm),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = "Trash", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = when {
                    loading -> "Reading the Trash…"
                    count == 0 -> "Empty"
                    else -> count.toString() + " item" + (if (count == 1) "" else "s") + " · " +
                        humanSize(bytes) + " · kept for " + trashRetentionLabel(retentionDays).lowercase()
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (count > 0) {
            CompactDestructiveButton(text = "Empty Trash", onClick = onEmpty, enabled = enabled)
        }
    }
}

@Composable
private fun EmptyBin(loading: Boolean, retentionDays: Int, modifier: Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = if (loading) {
                "Reading the Trash…"
            } else {
                "Nothing here. Deleted files and folders are kept for " +
                    trashRetentionLabel(retentionDays).lowercase() + "."
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Space.lg),
        )
    }
}

@Composable
private fun EntryList(
    entries: List<TrashEntry>,
    selectedId: String?,
    onSelect: (TrashEntry) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize()) {
        items(entries, key = { it.id }) { entry ->
            val active = entry.id == selectedId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (active) MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
                        else MaterialTheme.colorScheme.surface,
                    )
                    .clickable { onSelect(entry) }
                    .padding(horizontal = Space.lg, vertical = Space.sm),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.sm),
            ) {
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
                            humanSize(entry.sizeBytes),
                            ago(entry.deletedAtMillis),
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

// --- the preview -------------------------------------------------------------------------------

/** What one entry turned out to hold. */
private sealed interface Peek {
    data object Loading : Peek
    data class Text(val text: String, val truncated: Boolean) : Peek
    data class Picture(val bitmap: android.graphics.Bitmap) : Peek
    data class Folder(val listing: TrashListing) : Peek
    data class Opaque(val reason: String) : Peek
}

@Composable
private fun Preview(
    trash: Trash,
    entry: TrashEntry,
    busy: Boolean,
    onBack: (() -> Unit)?,
    onRestore: () -> Unit,
    onPurge: () -> Unit,
) {
    // The file being shown: the entry itself, or one inside it when the entry is a folder.
    var inside by remember(entry.id) { mutableStateOf<String?>(null) }
    var peek by remember(entry.id, inside) { mutableStateOf<Peek>(Peek.Loading) }

    LaunchedEffect(entry.id, inside) {
        peek = load(trash, entry, inside)
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.md, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            // One back affordance, whichever level it applies to: out of a file inside a folder
            // first, and only then out of the preview itself.
            val back: (() -> Unit)? = if (inside != null) ({ inside = null }) else onBack
            if (back != null) {
                JcTooltip("Back") {
                    IconButton(onClick = back, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = jcIcon(JCodeIcon.ArrowBack),
                            contentDescription = "Back",
                            modifier = Modifier.size(IconSize.md),
                        )
                    }
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = inside?.substringAfterLast('/') ?: entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    // Where it came from as the user names it, not the app-private absolute path,
                    // which is both meaningless to read and too long to finish.
                    text = inside?.let { entry.name + "/" + it }
                        ?: entry.location.ifBlank { entry.originalPath },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // The actions restore or destroy the whole entry, so they stay out of a file inside it:
            // "Restore" while looking at one file of a folder would read as restoring that file.
            if (inside == null) {
                CompactOutlinedButton(text = "Restore", onClick = onRestore, enabled = !busy)
                CompactDestructiveButton(text = "Delete forever", onClick = onPurge, enabled = !busy)
            }
        }
        HorizontalDivider(
            thickness = StrokeWidth.hairline,
            color = MaterialTheme.colorScheme.outlineVariant,
        )
        Box(modifier = Modifier.weight(1f)) {
            when (val state = peek) {
                is Peek.Loading -> Note("Reading…")
                is Peek.Opaque -> Note(state.reason)
                is Peek.Text -> TextPeek(state)
                is Peek.Picture -> Box(
                    modifier = Modifier.fillMaxSize().padding(Space.md),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.foundation.Image(
                        bitmap = state.bitmap.asImageBitmap(),
                        contentDescription = entry.name,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                is Peek.Folder -> FolderPeek(state.listing) { inside = it }
            }
        }
    }
}

@Composable
private fun TextPeek(state: Peek.Text) {
    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        SelectionContainer {
            Text(
                text = state.text,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(Space.md),
            )
        }
        if (state.truncated) {
            Text(
                text = "… preview truncated; the whole file is restored intact.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
            )
        }
    }
}

@Composable
private fun FolderPeek(listing: TrashListing, onOpen: (String) -> Unit) {
    if (listing.children.isEmpty()) {
        Note("This folder is empty.")
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(listing.children, key = { it.path }) { child ->
            ChildRow(child, onOpen)
        }
        if (listing.truncated) {
            item {
                Text(
                    text = "… and " + (listing.total - listing.children.size) + " more.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Space.md, vertical = Space.sm),
                )
            }
        }
    }
}

@Composable
private fun ChildRow(child: TrashChild, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onOpen(child.path) }
            .padding(horizontal = Space.md, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Icon(
            imageVector = jcIcon(JCodeIcon.Output),
            contentDescription = null,
            modifier = Modifier.size(IconSize.sm),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = child.path,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = humanSize(child.sizeBytes),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Note(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(Space.lg),
        )
    }
}

/**
 * Decide what a trashed thing is, and read enough of it to show.
 *
 * By content rather than by name: an extension is a claim about a file, and the one case that
 * matters here — is this text I can read — is answered by whether the bytes contain a NUL.
 */
private suspend fun load(trash: Trash, entry: TrashEntry, inside: String?): Peek =
    withContext(Dispatchers.IO) {
        if (entry.isDirectory && inside == null) return@withContext Peek.Folder(trash.listInside(entry))
        val bytes = trash.read(entry, inside)
            ?: return@withContext Peek.Opaque("This file is no longer in the Trash.")
        if (bytes.isEmpty()) return@withContext Peek.Text("", truncated = false)
        val name = inside ?: entry.name
        if (name.substringAfterLast('.', "").lowercase() in PICTURE_TYPES) {
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
            if (bitmap != null) return@withContext Peek.Picture(bitmap)
        }
        if (bytes.any { it == 0.toByte() }) {
            return@withContext Peek.Opaque("Binary file — nothing to show, but it restores intact.")
        }
        val whole = if (inside == null) entry.sizeBytes else -1L
        Peek.Text(
            text = String(bytes, Charsets.UTF_8),
            truncated = whole > bytes.size || bytes.size >= PREVIEW_LIMIT,
        )
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

/** Narrower than this and the two panes take turns instead of sharing. */
private val SideBySideWidth = 640.dp
private val ListPaneWidth = 280.dp

/** Matches [Trash.read]'s own default, so "did it all fit" can be answered here. */
private const val PREVIEW_LIMIT = 512 * 1024

private val PICTURE_TYPES = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

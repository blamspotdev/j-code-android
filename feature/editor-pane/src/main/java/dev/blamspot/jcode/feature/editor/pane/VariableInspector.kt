package dev.blamspot.jcode.feature.editor.pane

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.jcIcon

/**
 * One value the debugger resolved, in editor-local terms.
 *
 * Deliberately not a DAP type: this module has no debug dependency, and the editor has no business
 * knowing the protocol. [reference] is an opaque handle the host trades back for children — treat it
 * as "there is more behind this", nothing else.
 */
data class InspectedValue(
    val name: String,
    val value: String,
    val type: String? = null,
    val reference: Int = 0,
) {
    val expandable: Boolean get() = reference > 0
}

/** A resolved long-press variable inspection: the word, its value, and the press position. */
internal data class VariableInspection(
    val word: String,
    val resolved: InspectedValue,
    val xPx: Float,
    val yPx: Float,
)

/** Lines past this and the peek card stops being a peek. */
private const val PEEK_MAX_LINES = 8

/**
 * The small "name = value" card shown when a variable is long-pressed while the debugger is stopped.
 *
 * It stays a *peek*: a glance at the value without losing your place in the file. Anything it cannot
 * show — a value longer than [PEEK_MAX_LINES], or an object whose fields live behind a reference — is
 * one tap from the full view rather than silently clipped, which is what an ellipsis with nothing
 * behind it used to be.
 */
@Composable
internal fun VariableInspectPopup(
    inspection: VariableInspection,
    expand: ((Int, (List<InspectedValue>) -> Unit) -> Unit)?,
    onDismiss: () -> Unit,
) {
    var detail by remember(inspection) { mutableStateOf(false) }
    val resolved = inspection.resolved
    val lineCount = remember(resolved.value) { resolved.value.count { it == '\n' } + 1 }
    val clipped = lineCount > PEEK_MAX_LINES || resolved.value.length > 400
    val hasMore = clipped || resolved.expandable

    if (detail) {
        VariableDetailDialog(root = resolved, expand = expand, onDismiss = onDismiss)
        return
    }

    val positionProvider = remember(inspection.xPx, inspection.yPx) {
        object : PopupPositionProvider {
            override fun calculatePosition(
                anchorBounds: IntRect,
                windowSize: IntSize,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
            ): IntOffset {
                val x = (anchorBounds.left + inspection.xPx.toInt())
                    .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
                val below = anchorBounds.top + inspection.yPx.toInt() + 24
                val y = if (below + popupContentSize.height <= windowSize.height) {
                    below
                } else {
                    (anchorBounds.top + inspection.yPx.toInt() - popupContentSize.height - 12).coerceAtLeast(0)
                }
                return IntOffset(x, y)
            }
        }
    }
    Popup(
        popupPositionProvider = positionProvider,
        properties = PopupProperties(focusable = false, dismissOnBackPress = true, dismissOnClickOutside = true),
        onDismissRequest = onDismiss,
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 8.dp,
            modifier = Modifier.widthIn(min = 120.dp, max = 420.dp),
        ) {
            Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = inspection.word,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    resolved.type?.let { t ->
                        Text(
                            text = "  $t",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Text(
                    text = resolved.value,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = PEEK_MAX_LINES,
                    overflow = TextOverflow.Ellipsis,
                )
                if (hasMore) {
                    TextButton(onClick = { detail = true }, modifier = Modifier.padding(top = 2.dp)) {
                        Text(
                            text = if (resolved.expandable) "Inspect object" else "Show full value",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The full view of an inspected value: its complete text, and — when the adapter says it has any —
 * its fields as a lazily-loaded tree.
 *
 * Children are fetched per node on first expand and cached, so opening a deep object costs one
 * request per level actually opened rather than walking the whole graph up front — which for a
 * cyclic object graph would not terminate at all.
 */
@Composable
private fun VariableDetailDialog(
    root: InspectedValue,
    expand: ((Int, (List<InspectedValue>) -> Unit) -> Unit)?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    // reference -> children, populated on first expand. Absent = not fetched yet.
    val childrenByRef = remember { mutableStateMapOf<Int, List<InspectedValue>>() }
    val openRefs = remember { mutableStateMapOf<Int, Boolean>() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(root.name, style = MaterialTheme.typography.titleSmall)
                root.type?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.heightIn(max = 420.dp)) {
                // The value verbatim, no maxLines. A long toString() or a JSON blob is the whole
                // reason someone opened this.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = if (root.expandable) 140.dp else 380.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp))
                        .verticalScroll(rememberScrollState())
                        .padding(8.dp),
                ) {
                    Text(
                        text = root.value,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                    )
                }
                if (root.expandable && expand != null) {
                    LaunchedEffect(root.reference) {
                        if (root.reference !in childrenByRef) {
                            expand(root.reference) { childrenByRef[root.reference] = it }
                        }
                        openRefs[root.reference] = true
                    }
                    Text(
                        text = "Fields",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
                    )
                    val rows = flatten(root.reference, childrenByRef, openRefs, depth = 0)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(1.dp),
                    ) {
                        items(rows, key = { it.key }) { row ->
                            VariableTreeRow(
                                row = row,
                                onToggle = {
                                    val open = openRefs[row.value.reference] == true
                                    openRefs[row.value.reference] = !open
                                    if (!open && row.value.reference !in childrenByRef) {
                                        expand(row.value.reference) { childrenByRef[row.value.reference] = it }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { clipboard.setText(AnnotatedString(root.value)) }) { Text("Copy") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

/** One rendered tree row: the value, how deep it sits, and whether it is currently open. */
private data class TreeRow(val key: String, val value: InspectedValue, val depth: Int, val open: Boolean)

/** Depth-first walk of the fetched children, emitting only what is currently expanded. */
private fun flatten(
    reference: Int,
    childrenByRef: Map<Int, List<InspectedValue>>,
    openRefs: Map<Int, Boolean>,
    depth: Int,
    keyPrefix: String = "",
): List<TreeRow> = buildList {
    childrenByRef[reference].orEmpty().forEachIndexed { index, child ->
        val key = keyPrefix + reference + "/" + index + "/" + child.name
        val open = child.expandable && openRefs[child.reference] == true
        add(TreeRow(key, child, depth, open))
        if (open) addAll(flatten(child.reference, childrenByRef, openRefs, depth + 1, key + "."))
    }
}

@Composable
private fun VariableTreeRow(row: TreeRow, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (row.value.expandable) Modifier.clickable(onClick = onToggle) else Modifier)
            .padding(start = (row.depth * 14).dp, top = 2.dp, bottom = 2.dp),
    ) {
        if (row.value.expandable) {
            Icon(
                painter = rememberVectorPainter(
                    jcIcon(if (row.open) JCodeIcon.ChevronDown else JCodeIcon.ChevronRight),
                ),
                contentDescription = if (row.open) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(14.dp),
            )
        } else {
            Box(modifier = Modifier.size(14.dp))
        }
        Text(
            text = row.value.name,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp),
        )
        Text(
            text = row.value.value,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

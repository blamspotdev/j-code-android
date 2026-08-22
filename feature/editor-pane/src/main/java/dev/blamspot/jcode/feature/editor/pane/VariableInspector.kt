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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.AlertDialog
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.JCodeIcon
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.jcIcon
import kotlinx.coroutines.launch

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
    /** How many elements, when the adapter says this is an indexed value (array/list). 0 otherwise. */
    val elementCount: Int = 0,
) {
    val expandable: Boolean get() = reference > 0

    /**
     * The text inside `{...}` when the value is nothing but a wrapped bare token, else null.
     *
     * A real composite value — Python's `{'a': 1}`, a JSON blob — carries quotes/colons/commas/spaces
     * and deliberately fails this test, so it is never rewritten.
     */
    private val braceInner: String?
        get() = value.trim().takeIf { it.length > 2 && it.startsWith("{") && it.endsWith("}") }
            ?.drop(1)?.dropLast(1)
            ?.takeIf { inner -> inner.none { it.isWhitespace() || it in "'\":,;=" } }

    /**
     * True when the adapter rendered the value as nothing but the type it already reports — a
     * `List<int>` whose value is `{System.Collections.Generic.List<int>}`. Says nothing about the
     * contents, so the host takes it as the cue to go find a size.
     */
    val valueEchoesType: Boolean get() = braceInner?.let { type == null || it == type } == true

    /**
     * What to actually show for the value: the element count for a container that would otherwise
     * repeat its own type, the bare dimension for an array (`{int[4]}` → `int[4]`, where the
     * dimension IS the useful part), and anything else exactly as the adapter rendered it.
     */
    val displayValue: String
        get() {
            val inner = braceInner
            return when {
                elementCount > 0 && valueEchoesType ->
                    if (elementCount == 1) "1 item" else "$elementCount items"
                inner != null -> inner
                else -> value
            }
        }
}

/** A resolved long-press variable inspection: the word and its value. */
internal data class VariableInspection(
    val word: String,
    val resolved: InspectedValue,
)

/** Lines past this and the peek stops being a peek. */
private const val PEEK_MAX_LINES = 8

/**
 * The debugger's variable peek, rendered as the header of the editor's long-press menu (see
 * [dev.blamspot.jcode.design.CompactContextMenu]'s `header` slot) rather than a floating card. It
 * sits above the normal editor actions so a long-press on a variable gives one card: the value at a
 * glance, plus everything you'd otherwise reach from the context menu.
 *
 * It stays a *peek*: the value up to [PEEK_MAX_LINES]. Inspect opens the full [VariableDetailDialog]
 * and appears only when there is more than the peek could show — a longer value, or an object whose
 * fields live behind a reference — rather than silently clipping. Copy value is always offered.
 */
@Composable
internal fun VariableInspectHeader(
    inspection: VariableInspection,
    expand: ChildExpander?,
    onInspect: () -> Unit,
    onCopied: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copying by remember(inspection) { mutableStateOf(false) }
    val resolved = inspection.resolved
    val lineCount = remember(resolved.value) { resolved.value.count { it == '\n' } + 1 }
    val clipped = lineCount > PEEK_MAX_LINES || resolved.value.length > 400
    val hasMore = clipped || resolved.expandable

    Column(
        modifier = Modifier
            .widthIn(min = 180.dp, max = 420.dp)
            .padding(horizontal = Space.md, vertical = Space.sm),
    ) {
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
            text = resolved.displayValue,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = PEEK_MAX_LINES,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = Space.xxs),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.xs),
            modifier = Modifier.padding(top = Space.xs),
        ) {
            // Inspect only when there is something the peek could not show; Copy value always, since
            // reading a value off the screen and retyping it is the thing a peek most invites.
            if (hasMore) {
                CompactOutlinedButton(text = "Inspect", onClick = onInspect, icon = JCodeIcon.Search)
            }
            // A container copies as JSON — walking it costs a round-trip per level, hence the
            // wait — and a scalar as its own text. Never the peek's rendering: "3 items" describes
            // a list, it isn't one.
            CompactOutlinedButton(
                text = if (copying) "Copying…" else "Copy value",
                icon = JCodeIcon.Copy,
                enabled = !copying,
                onClick = {
                    copying = true
                    scope.launch {
                        clipboard.setText(AnnotatedString(resolved.toJson(expand)))
                        copying = false
                        onCopied()
                    }
                },
            )
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
internal fun VariableDetailDialog(
    root: InspectedValue,
    expand: ChildExpander?,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var copying by remember(root) { mutableStateOf(false) }
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
                        .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(Radius.md))
                        .verticalScroll(rememberScrollState())
                        .padding(Space.sm),
                ) {
                    Text(
                        text = root.displayValue,
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
                        modifier = Modifier.padding(top = Space.ms, bottom = Space.xxs),
                    )
                    val rows = flatten(root.reference, childrenByRef, openRefs, depth = 0)
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 220.dp),
                        verticalArrangement = Arrangement.spacedBy(Space.hairline),
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
            // Same copy as the peek's, so the two never disagree about what this value is.
            CompactFilledButton(
                text = if (copying) "Copying…" else "Copy",
                enabled = !copying,
                onClick = {
                    copying = true
                    scope.launch {
                        clipboard.setText(AnnotatedString(root.toJson(expand)))
                        copying = false
                    }
                },
            )
        },
        dismissButton = { CompactOutlinedButton(text = "Close", onClick = onDismiss) },
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
            .padding(start = (row.depth * 14).dp, top = Space.xxs, bottom = Space.xxs),
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
            modifier = Modifier.padding(start = Space.xs),
        )
        Text(
            text = row.value.displayValue,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Space.sm),
        )
    }
}

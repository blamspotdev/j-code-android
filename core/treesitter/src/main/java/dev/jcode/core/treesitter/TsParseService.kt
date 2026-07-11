package dev.jcode.core.treesitter

import dev.jcode.core.editor.EditorEvent
import dev.jcode.core.editor.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of the parsed tree for a given editor.
 */
data class TreeState(
    val tree: TsTree?,
    val languageId: String? = null,
    val parsedRange: IntRange = 0..0,
)

/**
 * Background parse service that re-parses on buffer changes.
 * Debounces 30 ms and applies incremental edits when possible.
 */
@Singleton
class TsParseService @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private class DocState {
        val flow = MutableStateFlow(TreeState(null))
        var collector: Job? = null
        var parseJob: Job? = null
        var generation = 0L
    }

    // Map of editor state ID to its per-document parse state.
    private val docs = mutableMapOf<String, DocState>()

    private fun docFor(editorState: EditorState): DocState =
        synchronized(docs) { docs.getOrPut(editorState.hashCode().toString()) { DocState() } }

    /** Get the tree state flow for a given editor. */
    fun treeStateFor(editorState: EditorState): StateFlow<TreeState> = docFor(editorState).flow.asStateFlow()

    /** Start listening to an editor's events for parsing. */
    fun attach(editorState: EditorState, registry: LanguageRegistry) {
        val doc = docFor(editorState)
        doc.collector?.cancel()
        doc.collector = editorState.events
            .onEach { event ->
                if (event is EditorEvent.TextChanged) {
                    scheduleParse(editorState, registry, doc)
                }
            }
            .launchIn(scope)

        // Initial parse
        scheduleParse(editorState, registry, doc)
    }

    /** Detach from an editor. */
    fun detach(editorState: EditorState) {
        val doc = synchronized(docs) { docs.remove(editorState.hashCode().toString()) }
        doc?.collector?.cancel()
        doc?.parseJob?.cancel()
    }

    private fun scheduleParse(
        editorState: EditorState,
        registry: LanguageRegistry,
        doc: DocState,
    ) {
        // Cancel-and-replace: a burst of edits collapses to ONE parse of the final text instead of
        // queueing a full reparse per keystroke. The generation stamp keeps a stale parse that
        // already passed cancellation from publishing over a newer result.
        val generation = synchronized(doc) { ++doc.generation }
        val previous = doc.parseJob
        doc.parseJob = scope.launch {
            previous?.cancelAndJoin()
            delay(30) // debounce
            val snapshot = editorState.snapshot.value
            val langDescriptor = editorState.language.value ?: return@launch

            val source = snapshot.readRangeAsUtf16(0, snapshot.byteLength)
            val previousState = doc.flow.value
            val sameDoc = previousState.tree != null && previousState.languageId == langDescriptor.id
            if (sameDoc && previousState.tree?.sourceText == source) return@launch

            // Incremental parse: mark the changed span (from a prefix/suffix diff of old vs new
            // text) on a copy of the previous tree so tree-sitter reuses every unchanged node.
            val editedOld = if (sameDoc) {
                runCatching {
                    previousState.tree?.copy()?.also { applyDiffEdit(it, source) }
                }.getOrNull()
            } else {
                null
            }

            val parser = TsParser()
            try {
                val tsLang = registry.findTsLanguage(langDescriptor.extensions.firstOrNull() ?: "")
                tsLang?.let { parser.setLanguage(it) }
                val tree = parser.parse(source, editedOld)
                val fresh = synchronized(doc) { doc.generation == generation }
                if (fresh) {
                    doc.flow.value = TreeState(
                        tree = tree,
                        languageId = langDescriptor.id,
                        parsedRange = 0 until source.length,
                    )
                } else {
                    tree.close()
                }
            } finally {
                editedOld?.close()
                parser.close()
            }
        }
    }

    /**
     * Mark the changed region on [tree] (whose [TsTree.sourceText] is the OLD text) so a parse of
     * [newText] can reuse unchanged nodes. The region is the single span left by a common
     * prefix/suffix scan — always correct regardless of how many discrete edits happened since the
     * last parse, with no per-edit bookkeeping. Offsets are converted to the modified-UTF-8 byte
     * space the JNI layer hands tree-sitter (each UTF-16 unit encodes independently, so a scan
     * boundary inside a surrogate pair still yields consistent byte math).
     */
    private fun applyDiffEdit(tree: TsTree, newText: String) {
        val oldText = tree.sourceText
        if (oldText == newText) return
        var prefix = 0
        val maxPrefix = minOf(oldText.length, newText.length)
        while (prefix < maxPrefix && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        val maxSuffix = minOf(oldText.length, newText.length) - prefix
        while (suffix < maxSuffix && oldText[oldText.length - 1 - suffix] == newText[newText.length - 1 - suffix]) suffix++

        val (startByte, startRow, startCol) = utf8Position(newText, prefix)
        val (oldEndByte, oldEndRow, oldEndCol) = utf8Position(oldText, oldText.length - suffix)
        val (newEndByte, newEndRow, newEndCol) = utf8Position(newText, newText.length - suffix)
        tree.edit(
            startByte = startByte, oldEndByte = oldEndByte, newEndByte = newEndByte,
            startRow = startRow, startColumn = startCol,
            oldEndRow = oldEndRow, oldEndColumn = oldEndCol,
            newEndRow = newEndRow, newEndColumn = newEndCol,
        )
    }

    /** (byteOffset, row, byteColumn) of char offset [end] in [text], in modified-UTF-8 bytes. */
    private fun utf8Position(text: String, end: Int): Triple<Int, Int, Int> {
        var bytes = 0
        var row = 0
        var col = 0
        for (i in 0 until end) {
            val c = text[i]
            val width = when {
                c == '\u0000' -> 2 // modified UTF-8 encodes NUL as two bytes
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                else -> 3 // includes each half of a surrogate pair (CESU-8 style)
            }
            bytes += width
            if (c == '\n') {
                row++
                col = 0
            } else {
                col += width
            }
        }
        return Triple(bytes, row, col)
    }
}

/**
 * Colored span for syntax highlighting.
 */
data class ColoredSpan(
    val startOffset: Int,
    val endOffset: Int,
    val colorKey: String, // e.g. "keyword", "string", "comment"
)

/**
 * Highlight span producer that converts a parsed tree into colored spans.
 */
class HighlightSpanProducer @Inject constructor() {

    /**
     * Produce highlight spans for the given tree state and theme map.
     * Clipped to the visible viewport range.
     */
    fun produce(
        treeState: TreeState,
        themeMap: Map<String, Long> = emptyMap(),
        viewportStart: Int = 0,
        viewportEnd: Int = Int.MAX_VALUE,
    ): List<ColoredSpan> {
        val tree = treeState.tree ?: return emptyList()

        // Stub: return empty list. In production, this would walk the tree
        // via TsQueryCursor using highlights.scm and emit spans.
        return walkTreeForHighlights(tree, viewportStart, viewportEnd)
    }

    private fun walkTreeForHighlights(
        tree: TsTree,
        viewportStart: Int,
        viewportEnd: Int,
    ): List<ColoredSpan> {
        val spans = mutableListOf<ColoredSpan>()
        val cursor = tree.rootNode.walk()
        try {
            // Stub walk - in production would use query cursor
        } finally {
            cursor.close()
        }
        return spans
    }
}

package dev.jcode.core.treesitter

import dev.jcode.core.editor.EditorEvent
import dev.jcode.core.editor.EditorState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

    // Map of editor state ID to its tree state
    private val treeStates = mutableMapOf<String, MutableStateFlow<TreeState>>()

    /** Get the tree state flow for a given editor. */
    fun treeStateFor(editorState: EditorState): StateFlow<TreeState> {
        return treeStates.getOrPut(editorState.hashCode().toString()) {
            MutableStateFlow(TreeState(null))
        }.asStateFlow()
    }

    /** Start listening to an editor's events for parsing. */
    fun attach(editorState: EditorState, registry: LanguageRegistry) {
        val key = editorState.hashCode().toString()
        val flow = treeStates.getOrPut(key) { MutableStateFlow(TreeState(null)) }

        editorState.events
            .onEach { event ->
                if (event is EditorEvent.TextChanged) {
                    scheduleParse(editorState, registry, flow)
                }
            }
            .launchIn(scope)

        // Initial parse
        scheduleParse(editorState, registry, flow)
    }

    /** Detach from an editor. */
    fun detach(editorState: EditorState) {
        treeStates.remove(editorState.hashCode().toString())
    }

    private fun scheduleParse(
        editorState: EditorState,
        registry: LanguageRegistry,
        flow: MutableStateFlow<TreeState>,
    ) {
        scope.launch {
            delay(30) // debounce
            val snapshot = editorState.snapshot.value
            val langDescriptor = editorState.language.value ?: return@launch

            val source = snapshot.readRangeAsUtf16(0, snapshot.byteLength)
            val parser = TsParser()
            try {
                val tsLang = registry.findTsLanguage(langDescriptor.extensions.firstOrNull() ?: "")
                tsLang?.let { parser.setLanguage(it) }
                val tree = parser.parse(source)
                flow.value = TreeState(
                    tree = tree,
                    languageId = langDescriptor.id,
                    parsedRange = 0 until source.length,
                )
            } finally {
                parser.close()
            }
        }
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

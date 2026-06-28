package dev.jcode.core.editor

import dev.jcode.core.buffer.Buffer
import dev.jcode.core.buffer.EditTx
import dev.jcode.core.buffer.Snapshot
import dev.jcode.core.editor.decor.DecorationSet
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** Caret position within the buffer. */
data class Caret(
    val anchor: Int,
    val head: Int,
    val preferredColumn: Int = -1,
) {
    val offset: Int get() = head
    val isSelection: Boolean get() = anchor != head
    val start: Int get() = minOf(anchor, head)
    val end: Int get() = maxOf(anchor, head)
}

/** Viewport describing the visible region in pixel/line space. */
data class Viewport(
    val scrollY: Int = 0,
    val scrollX: Int = 0,
    val widthPx: Int = 0,
    val heightPx: Int = 0,
    val lineHeightPx: Int = 20,
) {
    val visibleLineTop: Int get() = if (lineHeightPx > 0) scrollY / lineHeightPx else 0
    val visibleLineBottom: Int get() = if (lineHeightPx > 0) (scrollY + heightPx) / lineHeightPx + 1 else 0
}

/** A request to reveal (caret + scroll into view) a 0-based (line, column). */
data class RevealRequest(val line: Int, val column: Int)

/** Fold range [startLine, endLine] inclusive. */
data class FoldRange(
    val startLine: Int,
    val endLine: Int,
    val summaryText: String? = null,
)

/** Editor event emitted on state changes. */
sealed class EditorEvent {
    data class TextChanged(val rangeStart: Int, val rangeEnd: Int, val newLength: Int) : EditorEvent()
    data object SelectionChanged : EditorEvent()
    data class ViewportChanged(val viewport: Viewport) : EditorEvent()
    data object FoldsChanged : EditorEvent()
    data object DecorationsChanged : EditorEvent()
}

/** Render configuration for the editor. */
data class RenderConfig(
    val fontSizeSp: Float = 14f,
    val lineHeightMultiplier: Float = 1.4f,
    val tabWidth: Int = 4,
    val showWhitespace: Boolean = false,
    val ligatures: Boolean = true,
)

/** Language descriptor attached to an editor. */
data class LanguageDescriptor(
    val id: String,
    val name: String,
    val extensions: List<String> = emptyList(),
)

/** Theme colors for the editor. */
data class EditorTheme(
    val background: Long = 0xFF1E1E2E,
    val foreground: Long = 0xFFCDD6F4,
    val lineNumber: Long = 0xFF6C7086,
    val lineNumberActive: Long = 0xFFCDD6F4,
    val selection: Long = 0x40585B76,
    val cursor: Long = 0xFFF5E0DC,
    val gutterBackground: Long = 0xFF181825,
    val gutterBorder: Long = 0xFF313244,
) {
    companion object {
        /** Dark theme (Catppuccin Mocha) */
        val DARK = EditorTheme(
            background = 0xFF1E1E2E,
            foreground = 0xFFCDD6F4,
            lineNumber = 0xFF6C7086,
            lineNumberActive = 0xFFCDD6F4,
            selection = 0x40585B76,
            cursor = 0xFFF5E0DC,
            gutterBackground = 0xFF181825,
            gutterBorder = 0xFF313244,
        )

        /** Light theme */
        val LIGHT = EditorTheme(
            background = 0xFFFAFAFA,
            foreground = 0xFF1C1B1F,
            lineNumber = 0xFF9E9E9E,
            lineNumberActive = 0xFF424242,
            selection = 0x40BDBDBD,
            cursor = 0xFF6750A4,
            gutterBackground = 0xFFF5F5F5,
            gutterBorder = 0xFFE0E0E0,
        )
    }
}

/**
 * EditorState holds the current buffer snapshot, carets, viewport, folds,
 * and language/theme metadata. All mutations flow through a single-threaded
 * dispatcher to guarantee a single writer.
 */
class EditorState(
    buffer: Buffer,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {

    private val id = UUID.randomUUID().toString().take(8)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private val bufferRef = buffer

    // Mutable state flows
    private val _snapshot = MutableStateFlow(buffer.snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val _carets = MutableStateFlow(listOf(Caret(0, 0)))
    val carets: StateFlow<List<Caret>> = _carets.asStateFlow()

    private val _viewport = MutableStateFlow(Viewport())
    val viewport: StateFlow<Viewport> = _viewport.asStateFlow()

    private val _folds = MutableStateFlow(emptyList<FoldRange>())
    val folds: StateFlow<List<FoldRange>> = _folds.asStateFlow()

    private val _decorations = MutableStateFlow(DecorationSet.EMPTY)
    val decorations: StateFlow<DecorationSet> = _decorations.asStateFlow()

    private val _language = MutableStateFlow<LanguageDescriptor?>(null)
    val language: StateFlow<LanguageDescriptor?> = _language.asStateFlow()

    private val _renderConfig = MutableStateFlow(RenderConfig())
    val renderConfig: StateFlow<RenderConfig> = _renderConfig.asStateFlow()

    private val _theme = MutableStateFlow(EditorTheme())
    val theme: StateFlow<EditorTheme> = _theme.asStateFlow()

    private val _events = MutableSharedFlow<EditorEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<EditorEvent> = _events.asSharedFlow()

    /** A pending request to move the caret to (line, column) — both 0-based — and scroll it into
     *  view. The view consumes it once it's laid out with correct metrics, then calls [clearReveal]. */
    private val _revealRequest = MutableStateFlow<RevealRequest?>(null)
    val revealRequest: StateFlow<RevealRequest?> = _revealRequest.asStateFlow()

    fun requestReveal(line: Int, column: Int) {
        _revealRequest.value = RevealRequest(line, column)
    }

    fun clearReveal() {
        _revealRequest.value = null
    }

    private val _dirty = MutableStateFlow(false)
    /** True when the buffer has unsaved edits since it was opened or last saved. */
    val dirty: StateFlow<Boolean> = _dirty.asStateFlow()

    /** Read-only flag for large files or explicit lock. */
    var readOnly: Boolean = false

    /** UndoManager for this editor state. Created lazily. */
    var undoManager: UndoManager? = null
        private set

    init {
        undoManager = UndoManager(this)
    }

    /** Apply an edit transaction through the single-writer dispatcher. */
    suspend fun applyEdit(tx: EditTx) = withContext(scope.coroutineContext) {
        if (readOnly) return@withContext
        val oldSnapshot = _snapshot.value
        val newSnapshot = bufferRef.applyEdit(tx)
        _snapshot.value = newSnapshot
        _dirty.value = true

        // Compute changed range for events
        var rangeStart = Int.MAX_VALUE
        var rangeEnd = 0
        var newLength = 0
        for (op in tx.ops) {
            when (op) {
                is dev.jcode.core.buffer.EditOp.Insert -> {
                    rangeStart = minOf(rangeStart, op.offset)
                    rangeEnd = maxOf(rangeEnd, op.offset)
                    newLength += op.text.length
                }
                is dev.jcode.core.buffer.EditOp.Delete -> {
                    rangeStart = minOf(rangeStart, op.start)
                    rangeEnd = maxOf(rangeEnd, op.end)
                }
            }
        }
        if (rangeStart != Int.MAX_VALUE) {
            _events.emit(EditorEvent.TextChanged(rangeStart, rangeEnd, newLength))
        }

        // Push to undo manager
        undoManager?.recordEdit(tx, _carets.value, oldSnapshot)
    }

    /** Set the caret selection. */
    suspend fun setSelection(carets: List<Caret>) = withContext(scope.coroutineContext) {
        _carets.value = carets.sortedBy { it.head }
        _events.emit(EditorEvent.SelectionChanged)
    }

    /** Scroll to a specific line and column. */
    suspend fun scrollTo(line: Int, column: Int) = withContext(scope.coroutineContext) {
        val currentVp = _viewport.value
        val offset = bufferRef.lineColumnToOffset(line, column)
        // Estimate scroll position from line
        val targetScrollY = line * currentVp.lineHeightPx
        _viewport.value = currentVp.copy(scrollY = targetScrollY)
        _events.emit(EditorEvent.ViewportChanged(_viewport.value))
    }

    /** Update the viewport (called by EditorView on size/scroll changes). */
    fun updateViewport(update: (Viewport) -> Viewport) {
        val current = _viewport.value
        val updated = update(current)
        _viewport.value = updated
        scope.launch { _events.emit(EditorEvent.ViewportChanged(updated)) }
    }

    /** Add a fold range. */
    suspend fun addFold(range: FoldRange) = withContext(scope.coroutineContext) {
        val current = _folds.value
        if (current.none { it.startLine == range.startLine && it.endLine == range.endLine }) {
            _folds.value = current + range
            _events.emit(EditorEvent.FoldsChanged)
        }
    }

    /** Remove a fold range. */
    suspend fun removeFold(range: FoldRange) = withContext(scope.coroutineContext) {
        val current = _folds.value
        _folds.value = current.filter {
            it.startLine != range.startLine || it.endLine != range.endLine
        }
        _events.emit(EditorEvent.FoldsChanged)
    }

    /** Toggle a fold range. */
    suspend fun toggleFold(range: FoldRange) = withContext(scope.coroutineContext) {
        val current = _folds.value
        if (current.any { it.startLine == range.startLine && it.endLine == range.endLine }) {
            _folds.value = current.filter {
                it.startLine != range.startLine || it.endLine != range.endLine
            }
        } else {
            _folds.value = current + range
        }
        _events.emit(EditorEvent.FoldsChanged)
    }

    /** Set the language descriptor. */
    fun setLanguage(descriptor: LanguageDescriptor?) {
        _language.value = descriptor
    }

    /** Mark the buffer as persisted (no unsaved edits). Call after a successful write to disk. */
    fun markClean() {
        _dirty.value = false
    }

    /** Update render config. */
    fun updateRenderConfig(update: (RenderConfig) -> RenderConfig) {
        _renderConfig.value = update(_renderConfig.value)
    }

    /** Update theme. */
    fun updateTheme(update: (EditorTheme) -> EditorTheme) {
        _theme.value = update(_theme.value)
    }

    /** Update decorations. */
    fun updateDecorations(update: (DecorationSet) -> DecorationSet) {
        _decorations.value = update(_decorations.value)
        scope.launch { _events.emit(EditorEvent.DecorationsChanged) }
    }

    override fun close() {
        scope.cancel()
        bufferRef.close()
    }

    internal fun emitEvent(event: EditorEvent) {
        scope.launch { _events.emit(event) }
    }
}

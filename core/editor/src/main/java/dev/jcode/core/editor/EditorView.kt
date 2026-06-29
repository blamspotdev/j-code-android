package dev.jcode.core.editor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.jcode.core.buffer.EditTx
import dev.jcode.core.resource.ResourceManagerLocator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.math.max
import kotlin.math.min

/** A long-press context request: pixel position and the selected word (empty if none). */
data class EditorContextRequest(val xPx: Float, val yPx: Float, val word: String)

/** Language-aware context actions (resolved by the host; semantic ones need a language server). */
enum class EditorLanguageAction(val label: String) {
    GoToDefinition("Go to Definition"),
    FindReferences("Find References"),
    RenameSymbol("Rename Symbol"),
    FormatDocument("Format Document"),
}

/**
 * Custom Android View that renders a code editor.
 * Hosts the buffer snapshot, renders visible content via Canvas,
 * and provides an InputConnection for IME.
 */
class EditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : View(context, attrs, defStyleAttr) {

    private var editorState: EditorState? = null
    private var renderer: Renderer? = null
    private var inputConnection: EditorInputConnection? = null
    private var observationScope: CoroutineScope? = null

    private var typeface: Typeface = Typeface.MONOSPACE

    // sp→px factor; RenderConfig.fontSizeSp is in sp but Paint.textSize / layout math need px.
    private val density: Float get() = resources.displayMetrics.density

    /** Invoked on long-press after the word under the finger is selected, so the host can show a
     *  context menu. [EditorContextRequest.word] is empty when the press wasn't on a word. */
    var onContextRequest: ((EditorContextRequest) -> Unit)? = null

    /** Invoked when the user requests a save (Ctrl+S); the host writes the buffer to disk. */
    var onSaveRequest: (() -> Unit)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // Tapping the editor focuses it and raises the soft keyboard so the file is editable
                // (placing the caret happens on ACTION_DOWN in onTouchEvent).
                this@EditorView.requestFocus()
                this@EditorView.showKeyboard()
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val offset = offsetAt(e.x, e.y) ?: return
                val word = wordAt(offset)
                if (word != null) {
                    runBlocking { editorState?.setSelection(listOf(Caret(word.first, word.second))) }
                }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onContextRequest?.invoke(EditorContextRequest(e.x, e.y, word?.third ?: ""))
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setWillNotDraw(false)
    }

    /** Attach an EditorState to this view. */
    fun attach(editorState: EditorState) {
        if (this.editorState === editorState && renderer != null) {
            return
        }
        observationScope?.cancel()
        this.editorState = editorState
        val resourceManager = runCatching { ResourceManagerLocator.resourceManager(context) }.getOrNull()
        this.renderer = Renderer(typeface, density, resourceManager)
        val scope = MainScope()
        observationScope = scope

        // Seed the new state's viewport with the current view size. onSizeChanged won't fire when a
        // new EditorState is attached to an already-laid-out view (tab switch), so without this the
        // viewport keeps heightPx=0 and only ~1-2 lines are considered visible.
        if (width > 0 && height > 0) {
            editorState.updateViewport { it.copy(widthPx = width, heightPx = height) }
        }

        // Observe state changes
        editorState.snapshot.onEach { invalidate() }.launchIn(scope)
        editorState.viewport.onEach { invalidate() }.launchIn(scope)
        // Keep the viewport's line height in sync with the render config so the visible-line range
        // (and scroll/caret math) matches what the renderer actually draws; otherwise the default
        // 20px is used and only a couple lines are considered "visible".
        editorState.renderConfig.onEach { cfg ->
            val lh = (cfg.fontSizeSp * density * cfg.lineHeightMultiplier).toInt().coerceAtLeast(1)
            if (editorState.viewport.value.lineHeightPx != lh) {
                editorState.updateViewport { it.copy(lineHeightPx = lh) }
            }
            invalidate()
        }.launchIn(scope)
        editorState.decorations.onEach { invalidate() }.launchIn(scope)
        editorState.carets.onEach { invalidate() }.launchIn(scope)
        // A pending "reveal (line,col)" request (e.g. opening a file at a location tapped in the
        // terminal): place the caret there and scroll it into view.
        editorState.revealRequest.onEach { req -> if (req != null) revealLineColumn(req.line, req.column) }
            .launchIn(scope)
    }

    /** Move the caret to a 0-based (line, column), scroll it into view, and focus the editor. */
    private fun revealLineColumn(line: Int, column: Int) {
        val state = editorState ?: return
        val snapshot = state.snapshot.value
        val targetLine = line.coerceIn(0, max(0, snapshot.lineCount - 1))
        val offset = snapshot.lineColumnToOffset(targetLine, column.coerceAtLeast(0))
        // Compute line height from the render config (the viewport's may not be set yet on a fresh tab).
        val cfg = state.renderConfig.value
        val lineHeight = (cfg.fontSizeSp * density * cfg.lineHeightMultiplier).toInt().coerceAtLeast(1)
        runBlocking { state.setSelection(listOf(Caret(offset, offset))) }
        // Park the target a couple of lines below the top so there's context above it.
        val targetScrollY = (targetLine * lineHeight - lineHeight * 2).coerceAtLeast(0)
        state.updateViewport { it.copy(scrollY = targetScrollY) }
        state.clearReveal()
        requestFocus()
        invalidate()
    }

    /** Detach the current EditorState. */
    fun detach() {
        observationScope?.cancel()
        observationScope = null
        editorState = null
        renderer = null
        inputConnection = null
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val height = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        editorState?.updateViewport { vp ->
            vp.copy(
                widthPx = w,
                heightPx = h,
            )
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val state = editorState ?: return
        val renderer = renderer ?: return

        val snapshot = state.snapshot.value
        val viewport = state.viewport.value
        val config = state.renderConfig.value
        val carets = state.carets.value
        val theme = state.theme.value
        val decorations = state.decorations.value

        // Draw background
        canvas.drawColor(theme.background.toInt())

        renderer.draw(canvas, snapshot, viewport, config, carets, decorations = decorations, theme = theme)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            val offset = offsetAt(event.x, event.y)
            if (offset != null) {
                runBlocking { editorState?.setSelection(listOf(Caret(offset, offset))) }
                // The caret moved out from under the IME; restart input so it re-reads the new
                // cursor (via onCreateInputConnection's initialSel) and drops any stale composing
                // region. Without this the IME edits against a stale model and corrupts the buffer.
                imm().restartInput(this)
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    /** Buffer offset at a pixel position, or null if outside the text. */
    private fun offsetAt(x: Float, y: Float): Int? {
        val state = editorState ?: return null
        val snapshot = state.snapshot.value
        val config = state.renderConfig.value
        val lineHeightPx = (config.fontSizeSp * density * config.lineHeightMultiplier).toInt().coerceAtLeast(1)
        val gutterWidth = computeGutterWidth(snapshot, config)
        val lineIndex = state.viewport.value.visibleLineTop +
            ((y - state.viewport.value.scrollY % lineHeightPx) / lineHeightPx).toInt()
        if (lineIndex < 0 || lineIndex >= snapshot.lineCount) return null
        val (lineStart, lineEnd) = snapshot.lineAt(lineIndex)
        val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)
        val xInText = x - gutterWidth - 8f
        var col = 0
        var measured = 0f
        val paint = android.text.TextPaint().apply {
            textSize = config.fontSizeSp * density
            typeface = this@EditorView.typeface
        }
        for (i in lineText.indices) {
            val charWidth = paint.measureText(lineText[i].toString())
            if (measured + charWidth > xInText) break
            measured += charWidth
            col++
        }
        return snapshot.lineColumnToOffset(lineIndex, col)
    }

    /** The identifier word at an offset: (startOffset, endOffset, text), or null. */
    private fun wordAt(offset: Int): Triple<Int, Int, String>? {
        val state = editorState ?: return null
        val snapshot = state.snapshot.value
        val (line, col) = snapshot.offsetToLineColumn(offset)
        val (lineStart, lineEnd) = snapshot.lineAt(line)
        val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)
        if (lineText.isEmpty()) return null
        fun isWord(c: Char) = c.isLetterOrDigit() || c == '_'
        var start = col.coerceIn(0, lineText.length)
        if (start >= lineText.length || !isWord(lineText[start])) {
            if (start > 0 && isWord(lineText[start - 1])) start -= 1 else return null
        }
        var end = start
        while (start > 0 && isWord(lineText[start - 1])) start--
        while (end < lineText.length && isWord(lineText[end])) end++
        if (end <= start) return null
        return Triple(
            snapshot.lineColumnToOffset(line, start),
            snapshot.lineColumnToOffset(line, end),
            lineText.substring(start, end),
        )
    }

    // --- clipboard / selection actions (used by the context menu) -------------------------------

    private fun selectionRange(): Pair<Int, Int>? {
        val caret = editorState?.carets?.value?.firstOrNull() ?: return null
        val s = min(caret.anchor, caret.head)
        val e = max(caret.anchor, caret.head)
        return if (e > s) s to e else null
    }

    private fun clipboard(): ClipboardManager? =
        context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

    fun selectAll() {
        val state = editorState ?: return
        runBlocking { state.setSelection(listOf(Caret(0, state.snapshot.value.byteLength))) }
    }

    fun copySelection() {
        val state = editorState ?: return
        val (s, e) = selectionRange() ?: return
        val text = state.snapshot.value.readRangeAsUtf16(s, e)
        clipboard()?.setPrimaryClip(ClipData.newPlainText("text", text))
    }

    fun cutSelection() {
        val state = editorState ?: return
        val (s, e) = selectionRange() ?: return
        copySelection()
        runBlocking {
            state.applyEdit(EditTx.delete(s, e))
            state.setSelection(listOf(Caret(s, s)))
        }
    }

    fun pasteClipboard() {
        val state = editorState ?: return
        val text = clipboard()?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
            ?: return
        val range = selectionRange()
        runBlocking {
            val insertAt = if (range != null) {
                state.applyEdit(EditTx.delete(range.first, range.second))
                range.first
            } else {
                state.carets.value.firstOrNull()?.head ?: 0
            }
            state.applyEdit(EditTx.insert(insertAt, text))
            val after = insertAt + text.toByteArray(Charsets.UTF_8).size
            state.setSelection(listOf(Caret(after, after)))
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val state = editorState ?: return super.dispatchKeyEvent(event)

        // Ctrl+Z / Ctrl+Shift+Z for undo/redo
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                if (event.isShiftPressed) state.undoManager?.redo() else state.undoManager?.undo()
            }
            return true
        }

        // Ctrl+S to save the buffer to disk
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_S) {
            if (event.action == KeyEvent.ACTION_DOWN) onSaveRequest?.invoke()
            return true
        }

        // Editing keys arrive as key events (the soft IME sends them via sendKeyEvent), so they must
        // be handled here — commitText only covers printable characters.
        if (event.keyCode in HANDLED_KEYS || (event.unicodeChar != 0 && !event.isCtrlPressed && !event.isAltPressed)) {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> deleteAtCaret(state, forward = false)
                KeyEvent.KEYCODE_FORWARD_DEL -> deleteAtCaret(state, forward = true)
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> insertAtCaret(state, "\n")
                KeyEvent.KEYCODE_TAB -> insertAtCaret(state, "    ")
                KeyEvent.KEYCODE_DPAD_LEFT -> moveCaret(state, -1)
                KeyEvent.KEYCODE_DPAD_RIGHT -> moveCaret(state, 1)
                KeyEvent.KEYCODE_DPAD_UP -> moveCaretLine(state, -1)
                KeyEvent.KEYCODE_DPAD_DOWN -> moveCaretLine(state, 1)
                else -> insertAtCaret(state, event.unicodeChar.toChar().toString())
            }
            return true
        }

        return super.dispatchKeyEvent(event)
    }

    private fun caretOrNull(state: EditorState): Caret? = state.carets.value.firstOrNull()

    /** UTF-8-aware: the start offset of the code point ending at [offset]. */
    private fun prevCharStart(snapshot: dev.jcode.core.buffer.Snapshot, offset: Int): Int {
        if (offset <= 0) return 0
        val from = max(0, offset - 4)
        val bytes = snapshot.readRange(from, offset)
        var i = bytes.size - 1
        while (i > 0 && (bytes[i].toInt() and 0xC0) == 0x80) i--
        return from + i
    }

    /** UTF-8-aware: the end offset of the code point starting at [offset]. */
    private fun nextCharEnd(snapshot: dev.jcode.core.buffer.Snapshot, offset: Int): Int {
        val len = snapshot.byteLength
        if (offset >= len) return len
        val bytes = snapshot.readRange(offset, min(len, offset + 4))
        var i = 1
        while (i < bytes.size && (bytes[i].toInt() and 0xC0) == 0x80) i++
        return offset + i
    }

    private fun insertAtCaret(state: EditorState, text: String) {
        val caret = caretOrNull(state) ?: return
        runBlocking {
            if (caret.isSelection) state.applyEdit(EditTx.delete(caret.start, caret.end))
            val at = min(caret.start, caret.end)
            state.applyEdit(EditTx.insert(at, text))
            val newPos = at + text.toByteArray(Charsets.UTF_8).size
            state.setSelection(listOf(Caret(newPos, newPos)))
        }
        invalidate()
        updateImeCursor()
    }

    private fun deleteAtCaret(state: EditorState, forward: Boolean) {
        val caret = caretOrNull(state) ?: return
        val snapshot = state.snapshot.value
        runBlocking {
            if (caret.isSelection) {
                state.applyEdit(EditTx.delete(caret.start, caret.end))
                state.setSelection(listOf(Caret(caret.start, caret.start)))
            } else if (forward) {
                val end = nextCharEnd(snapshot, caret.head)
                if (end > caret.head) state.applyEdit(EditTx.delete(caret.head, end))
            } else {
                val start = prevCharStart(snapshot, caret.head)
                if (start < caret.head) {
                    state.applyEdit(EditTx.delete(start, caret.head))
                    state.setSelection(listOf(Caret(start, start)))
                }
            }
        }
        invalidate()
        updateImeCursor()
    }

    private fun moveCaret(state: EditorState, dir: Int) {
        val caret = caretOrNull(state) ?: return
        val snapshot = state.snapshot.value
        val newPos = if (dir < 0) prevCharStart(snapshot, caret.head) else nextCharEnd(snapshot, caret.head)
        runBlocking { state.setSelection(listOf(Caret(newPos, newPos))) }
        invalidate()
        updateImeCursor()
    }

    private fun moveCaretLine(state: EditorState, dir: Int) {
        val caret = caretOrNull(state) ?: return
        val snapshot = state.snapshot.value
        val (line, col) = snapshot.offsetToLineColumn(caret.head)
        val targetLine = (line + dir).coerceIn(0, max(0, snapshot.lineCount - 1))
        val newPos = snapshot.lineColumnToOffset(targetLine, col)
        runBlocking { state.setSelection(listOf(Caret(newPos, newPos))) }
        invalidate()
        updateImeCursor()
    }

    // Without this the IME framework treats the view as non-editable and never opens an input
    // connection, so the system soft keyboard does nothing.
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // VISIBLE_PASSWORD makes the system IME commit each keystroke directly (no autocorrect, no
        // composing region) — essential for a code editor, since composing edits against the IME's
        // own stale cursor model corrupt the buffer.
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE

        val state = editorState ?: return super.onCreateInputConnection(outAttrs)
        // Seed the IME with the real caret (byte offsets ≈ UTF-16 indices for ASCII); otherwise it
        // assumes 0 and composes against the wrong position.
        val sel = state.carets.value.firstOrNull()
        outAttrs.initialSelStart = sel?.let { min(it.anchor, it.head) } ?: 0
        outAttrs.initialSelEnd = sel?.let { max(it.anchor, it.head) } ?: 0

        val conn = EditorInputConnection(this, state)
        inputConnection = conn
        return conn
    }

    /** Tell the IME the current caret/selection + composing region so its model stays in sync. */
    fun updateImeCursor(composingStart: Int = -1, composingEnd: Int = -1) {
        val caret = editorState?.carets?.value?.firstOrNull() ?: return
        imm().updateSelection(
            this,
            min(caret.anchor, caret.head),
            max(caret.anchor, caret.head),
            composingStart,
            composingEnd,
        )
    }

    /** Get the InputMethodManager for this view. */
    fun imm(): InputMethodManager {
        return context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    }

    /** Raise the soft keyboard for this editor (call after the view has focus). */
    fun showKeyboard() {
        imm().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun computeGutterWidth(snapshot: dev.jcode.core.buffer.Snapshot, config: RenderConfig): Int {
        val lineCount = snapshot.lineCount
        val digits = lineCount.toString().length.coerceAtLeast(3)
        val sample = "9".repeat(digits)
        val paint = android.text.TextPaint().apply {
            textSize = config.fontSizeSp * density
            typeface = this@EditorView.typeface
        }
        return (paint.measureText(sample) + 24).toInt()
    }

    private companion object {
        val HANDLED_KEYS = setOf(
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_FORWARD_DEL,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_TAB,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_DOWN,
        )
    }
}

/**
 * Custom InputConnection for the EditorView.
 * Handles text input, composing regions, and IME communication.
 */
class EditorInputConnection(
    private val view: EditorView,
    private val state: EditorState,
) : InputConnection {

    private var composingRegion: IntRange? = null
    private var composingText: String = ""
    private var batchEditDepth = 0

    override fun beginBatchEdit(): Boolean {
        batchEditDepth++
        return true
    }

    override fun endBatchEdit(): Boolean {
        batchEditDepth--
        if (batchEditDepth <= 0) {
            batchEditDepth = 0
            view.invalidate()
        }
        return true
    }

    override fun getExtractedText(request: android.view.inputmethod.ExtractedTextRequest, flags: Int): android.view.inputmethod.ExtractedText? {
        return null // We don't support extracted text mode
    }

    override fun getCursorCapsMode(reqModes: Int): Int {
        val snapshot = state.snapshot.value
        val carets = state.carets.value
        if (carets.isEmpty()) return 0
        val offset = carets.first().head
        if (offset == 0) return android.text.TextUtils.CAP_MODE_CHARACTERS

        val before = snapshot.readRangeAsUtf16(max(0, offset - 10), offset)
        var mode = 0
        if (reqModes and android.text.TextUtils.CAP_MODE_CHARACTERS != 0) mode = android.text.TextUtils.CAP_MODE_CHARACTERS
        if (reqModes and android.text.TextUtils.CAP_MODE_WORDS != 0) {
            if (before.isBlank() || before.endsWith(' ') || before.endsWith('\n')) {
                mode = mode or android.text.TextUtils.CAP_MODE_WORDS
            }
        }
        return mode
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence? {
        val snapshot = state.snapshot.value
        val carets = state.carets.value
        if (carets.isEmpty()) return ""
        val offset = carets.first().head
        val start = max(0, offset - n)
        return snapshot.readRangeAsUtf16(start, offset)
    }

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence? {
        val snapshot = state.snapshot.value
        val carets = state.carets.value
        if (carets.isEmpty()) return ""
        val offset = carets.first().head
        val end = min(snapshot.byteLength, offset + n)
        return snapshot.readRangeAsUtf16(offset, end)
    }

    override fun getSelectedText(flags: Int): CharSequence? {
        val carets = state.carets.value
        if (carets.isEmpty() || !carets.first().isSelection) return ""
        val caret = carets.first()
        val snapshot = state.snapshot.value
        return snapshot.readRangeAsUtf16(caret.start, caret.end)
    }

    override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val newText = text?.toString() ?: ""
        state.undoManager?.beginComposing()

        // Anchor at the existing composing region start (so each keystroke replaces it in place),
        // else the caret. Re-using the caret directly would drift, because the caret advances past
        // the inserted text while the IME keeps editing the same composing region.
        val anchor = composingRegion?.first ?: (state.carets.value.firstOrNull()?.head ?: return false)

        composingRegion?.let { region ->
            runBlocking { state.applyEdit(EditTx.delete(region.first, region.last + 1)) }
        }

        if (newText.isNotEmpty()) {
            runBlocking { state.applyEdit(EditTx.insert(anchor, newText)) }
            val endByte = anchor + newText.toByteArray(Charsets.UTF_8).size
            composingRegion = anchor until endByte
            val caretPos = if (newCursorPosition > 0) endByte else anchor
            runBlocking { state.setSelection(listOf(Caret(caretPos, caretPos))) }
            composingText = newText
            view.invalidate()
            view.updateImeCursor(anchor, endByte)
        } else {
            composingRegion = null
            composingText = ""
            runBlocking { state.setSelection(listOf(Caret(anchor, anchor))) }
            view.invalidate()
            view.updateImeCursor()
        }
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        // [start, end) is half-open (Android convention); store it the same way the composing-text
        // path does so the exclusive-end delete (region.last + 1) removes exactly this range.
        composingRegion = start until end
        return true
    }

    override fun finishComposingText(): Boolean {
        state.undoManager?.endComposing()
        composingRegion = null
        composingText = ""
        view.invalidate()
        view.updateImeCursor()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val newText = text?.toString() ?: ""
        state.undoManager?.endComposing()

        // Replace the composing region if present, else insert at the caret.
        val anchor = composingRegion?.first ?: (state.carets.value.firstOrNull()?.head ?: return false)
        composingRegion?.let { region ->
            runBlocking { state.applyEdit(EditTx.delete(region.first, region.last + 1)) }
            composingRegion = null
        }

        val caretPos = if (newText.isNotEmpty()) {
            runBlocking { state.applyEdit(EditTx.insert(anchor, newText)) }
            anchor + newText.toByteArray(Charsets.UTF_8).size
        } else {
            anchor
        }
        runBlocking { state.setSelection(listOf(Caret(caretPos, caretPos))) }

        composingText = ""
        view.invalidate()
        view.updateImeCursor()
        return true
    }

    override fun commitCompletion(text: android.view.inputmethod.CompletionInfo?): Boolean {
        return false
    }

    override fun commitCorrection(correctionInfo: android.view.inputmethod.CorrectionInfo?): Boolean {
        return false
    }

    override fun setSelection(start: Int, end: Int): Boolean {
        runBlocking {
            state.setSelection(listOf(Caret(start, end)))
        }
        view.invalidate()
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSurroundingTextImpl(beforeLength, afterLength)
    }

    private fun deleteSurroundingTextImpl(beforeLength: Int, afterLength: Int): Boolean {
        val currentCaret = state.carets.value.firstOrNull() ?: return false
        val snapshot = state.snapshot.value
        val offset = currentCaret.head

        // Convert codepoint lengths to byte offsets (approximate for now)
        val beforeBytes = beforeLength.coerceAtMost(offset)
        val afterBytes = min(afterLength, snapshot.byteLength - offset)

        if (beforeBytes > 0 || afterBytes > 0) {
            val deleteTx = EditTx.delete(offset - beforeBytes, offset + afterBytes)
            runBlocking { state.applyEdit(deleteTx) }
            runBlocking {
                state.setSelection(listOf(Caret(offset - beforeBytes, offset - beforeBytes)))
            }
        }
        view.invalidate()
        view.updateImeCursor()
        return true
    }

    override fun sendKeyEvent(event: KeyEvent?): Boolean {
        if (event != null) {
            return view.dispatchKeyEvent(event)
        }
        return false
    }

    override fun performEditorAction(actionCode: Int): Boolean {
        return false
    }

    override fun performContextMenuAction(id: Int): Boolean {
        return false
    }

    override fun reportFullscreenMode(enabled: Boolean): Boolean {
        return false
    }

    override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
        return true
    }

    override fun closeConnection() {
        composingRegion = null
        composingText = ""
    }

    override fun clearMetaKeyStates(states: Int): Boolean {
        return false
    }

    override fun commitContent(
        inputContentInfo: android.view.inputmethod.InputContentInfo,
        flags: Int,
        opts: android.os.Bundle?,
    ): Boolean {
        return false
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        return deleteSurroundingTextImpl(beforeLength, afterLength)
    }

    override fun getHandler(): android.os.Handler? {
        return null
    }

    override fun performPrivateCommand(action: String?, data: android.os.Bundle?): Boolean {
        return false
    }
}

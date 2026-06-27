package dev.jcode.core.editor

import android.content.Context
import android.graphics.Canvas
import android.graphics.Typeface
import android.util.AttributeSet
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

        // Observe state changes
        editorState.snapshot.onEach { invalidate() }.launchIn(scope)
        editorState.viewport.onEach { invalidate() }.launchIn(scope)
        editorState.renderConfig.onEach { invalidate() }.launchIn(scope)
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

        // Draw background
        canvas.drawColor(theme.background.toInt())

        renderer.draw(canvas, snapshot, viewport, config, carets, theme = theme)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            // Simple tap-to-place-caret
            val state = editorState ?: return false
            val snapshot = state.snapshot.value
            val config = state.renderConfig.value
            val lineHeightPx = (config.fontSizeSp * density * config.lineHeightMultiplier).toInt().coerceAtLeast(1)
            val gutterWidth = computeGutterWidth(snapshot, config)

            val lineIndex = state.viewport.value.visibleLineTop +
                ((event.y - state.viewport.value.scrollY % lineHeightPx) / lineHeightPx).toInt()

            if (lineIndex >= 0 && lineIndex < snapshot.lineCount) {
                val (lineStart, lineEnd) = snapshot.lineAt(lineIndex)
                val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)
                val xInText = event.x - gutterWidth - 8f
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
                val offset = snapshot.lineColumnToOffset(lineIndex, col)
                runBlocking {
                    state.setSelection(listOf(Caret(offset, offset)))
                }
            }
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val state = editorState ?: return super.dispatchKeyEvent(event)

        // Ctrl+Z / Ctrl+Shift+Z for undo/redo
        if (event.isCtrlPressed && event.keyCode == KeyEvent.KEYCODE_Z) {
            if (event.isShiftPressed) {
                state.undoManager?.redo()
                return true
            } else {
                state.undoManager?.undo()
                return true
            }
        }

        return super.dispatchKeyEvent(event)
    }

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_CLASS_TEXT or
            EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0

        val state = editorState ?: return super.onCreateInputConnection(outAttrs)
        val conn = EditorInputConnection(this, state)
        inputConnection = conn
        return conn
    }

    /** Get the InputMethodManager for this view. */
    fun imm(): InputMethodManager {
        return context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
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

        // Remove previous composing text if any
        val currentCaret = state.carets.value.firstOrNull() ?: return false
        val snapshot = state.snapshot.value

        if (composingRegion != null) {
            val region = composingRegion!!
            val deleteTx = EditTx.delete(region.first, region.last)
            runBlocking { state.applyEdit(deleteTx) }
        }

        if (newText.isNotEmpty()) {
            val insertTx = EditTx.insert(currentCaret.head, newText)
            runBlocking { state.applyEdit(insertTx) }
            composingRegion = currentCaret.head until (currentCaret.head + newText.toByteArray(Charsets.UTF_8).size)
        } else {
            composingRegion = null
        }

        composingText = newText
        view.invalidate()
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        composingRegion = start..end
        return true
    }

    override fun finishComposingText(): Boolean {
        state.undoManager?.endComposing()
        composingRegion = null
        composingText = ""
        view.invalidate()
        return true
    }

    override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
        val newText = text?.toString() ?: ""
        state.undoManager?.endComposing()

        // Remove composing region if present
        if (composingRegion != null) {
            val region = composingRegion!!
            runBlocking { state.applyEdit(EditTx.delete(region.first, region.last)) }
            composingRegion = null
        }

        if (newText.isNotEmpty()) {
            val currentCaret = state.carets.value.firstOrNull() ?: return false
            runBlocking { state.applyEdit(EditTx.insert(currentCaret.head, newText)) }
            runBlocking {
                val newOffset = currentCaret.head + newText.toByteArray(Charsets.UTF_8).size
                state.setSelection(listOf(Caret(newOffset, newOffset)))
            }
        }

        composingText = ""
        view.invalidate()
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

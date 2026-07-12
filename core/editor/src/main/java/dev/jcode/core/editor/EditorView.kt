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
import android.widget.OverScroller
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.jcode.core.buffer.EditTx
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

/**
 * The identifier prefix being typed at the caret, with the byte range it occupies and the pixel
 * position (relative to this view) to anchor a completion popup. Null means "no active prefix".
 */
data class CompletionAnchor(
    val prefix: String,
    val replaceStart: Int,
    val caret: Int,
    val xPx: Float,
    val yPx: Float,
)

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

    // JetBrains Mono ships in-module (res/font) for programming ligatures. Its ligature glyphs keep
    // per-character advances, so the uniform-advance measurement math below stays exact whether
    // ligatures render or not.
    private var typeface: Typeface = runCatching { resources.getFont(R.font.jetbrains_mono_regular) }
        .getOrDefault(Typeface.MONOSPACE)

    // Suppresses the post-edit completion-anchor refresh while we programmatically accept a completion
    // (otherwise inserting the accepted text would immediately re-open the popup on the new word).
    private var suppressAnchorUpdate = false

    // Whether a completion anchor was last delivered (popup likely open) — lets a caret-follow scroll
    // re-anchor the popup without spuriously re-opening it after a completion is accepted.
    private var completionAnchorActive = false

    /** When true (app setting), a one-finger drag moves the caret (view follows) instead of scrolling. */
    var dragMovesCursor: Boolean = false

    /** Drag-to-cursor sensitivity per axis: 1 (slow/precise) … 5 (fast). */
    var cursorDragVerticalLevel: Int = 2
    var cursorDragHorizontalLevel: Int = 2

    // Sub-cell drag accumulators for the "drag moves cursor" gesture; reset at the start of each drag.
    private var dragAccumX = 0f
    private var dragAccumY = 0f

    // Sub-pixel scroll remainders (onScroll consumes whole pixels); reset at the start of each drag.
    private var scrollRemX = 0f
    private var scrollRemY = 0f

    // sp→px factor; RenderConfig.fontSizeSp is in sp but Paint.textSize / layout math need px.
    private val density: Float get() = resources.displayMetrics.density

    // One shared measurement paint for hit-testing / layout math, reconfigured only when the text size
    // or typeface changes. Allocating a TextPaint per tap/measure showed up on the hit-test hot path.
    private val measurePaint = android.text.TextPaint()
    private var measurePaintSize = -1f
    private var measurePaintTypeface: Typeface? = null
    private var measurePaintLigatures: Boolean? = null

    private fun measurePaintFor(config: RenderConfig): android.text.TextPaint {
        val size = config.fontSizeSp * density
        if (measurePaintSize != size || measurePaintTypeface !== typeface || measurePaintLigatures != config.ligatures) {
            measurePaint.textSize = size
            measurePaint.typeface = typeface
            // Kept in sync with Renderer's textPaint so measurement always matches what is drawn.
            measurePaint.fontFeatureSettings = if (config.ligatures) null else FONT_FEATURES_NO_LIGATURES
            measurePaintSize = size
            measurePaintTypeface = typeface
            measurePaintLigatures = config.ligatures
            measureAdvanceSize = -1f // typeface/size changed: force the advance cache to recompute
        }
        return measurePaint
    }

    // Monospace ASCII advance for the current measure-paint size; measureText("M") is exact for every
    // ASCII glyph in a monospace font, so an all-ASCII line's column is a single division (mirrors
    // Renderer.asciiAdvance).
    private var measureAdvanceSize = -1f
    private var measureAdvance = 0f

    private fun asciiAdvance(paint: android.text.TextPaint): Float {
        if (measureAdvanceSize != paint.textSize) {
            measureAdvance = paint.measureText("M")
            measureAdvanceSize = paint.textSize
        }
        return measureAdvance
    }

    /** Swap the editor font. Propagates to the renderer's paints and the measure paint, invalidating
     *  the advance caches (which key on textSize alone), then repaints. Safe before [attach] — the
     *  fresh Renderer picks up [typeface] at construction. */
    fun setEditorTypeface(tf: Typeface) {
        if (typeface === tf) return
        typeface = tf
        // Force measurePaintFor to reconfigure (it also resets measureAdvanceSize); belt-and-suspenders.
        measurePaintTypeface = null
        measureAdvanceSize = -1f
        renderer?.setTypeface(tf)
        invalidate()
    }

    /** Invoked on long-press after the word under the finger is selected, so the host can show a
     *  context menu. [EditorContextRequest.word] is empty when the press wasn't on a word. */
    var onContextRequest: ((EditorContextRequest) -> Unit)? = null

    /** Focus reporting for the extra-keys row: the host points the row at the surface owning the IME. */
    var onFocusStateChanged: ((Boolean) -> Unit)? = null

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: android.graphics.Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        onFocusStateChanged?.invoke(gainFocus)
    }

    /** Invoked when the user requests a save (Ctrl+S); the host writes the buffer to disk. */
    var onSaveRequest: (() -> Unit)? = null

    /** Invoked after edits/caret moves with the current completion prefix anchor (null = dismiss). */
    var onCompletionAnchorChanged: ((CompletionAnchor?) -> Unit)? = null

    /** Invoked with the 0-based line when the gutter (left margin) is tapped — used to toggle breakpoints. */
    var onGutterTap: ((line: Int) -> Unit)? = null

    /** Invoked on long-press over a word BEFORE selection/context-menu, with the word and the press's
     *  view-relative pixel position. Return true to consume the press (no selection, no menu) — used by
     *  the debugger to inspect a variable while stopped; return false to fall through. */
    var onWordLongPress: ((word: String, xPx: Float, yPx: Float) -> Boolean)? = null

    private val gestureDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                // A tap in the gutter toggles a breakpoint (no caret move, no keyboard).
                val gutterLine = gutterLineAt(e.x, e.y)
                if (gutterLine != null) {
                    onGutterTap?.invoke(gutterLine)
                    return true
                }
                // Tapping focuses the editor, raises the keyboard, and places the caret. Caret placement
                // lives here (not on ACTION_DOWN) so dragging to scroll never moves the caret or pops the
                // completion list.
                this@EditorView.requestFocus()
                this@EditorView.showKeyboard()
                val offset = offsetAt(e.x, e.y)
                if (offset != null) {
                    runBlocking { editorState?.setSelection(listOf(Caret(offset, offset))) }
                    // Restart input so the IME re-reads the new cursor (via onCreateInputConnection's
                    // initialSel) and drops any stale composing region; otherwise it corrupts the buffer.
                    imm().restartInput(this@EditorView)
                    updateCompletionAnchor()
                }
                return true
            }

            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
                val state = editorState ?: return false
                if (dragMovesCursor) {
                    moveCaretByDrag(state, distanceX, distanceY)
                    return true
                }
                val vp = state.viewport.value
                // Accumulate float deltas and consume whole pixels so slow drags don't lose the
                // sub-pixel remainder every event (which reads as sluggish tracking).
                scrollRemX += distanceX
                scrollRemY += distanceY
                val dx = scrollRemX.toInt()
                val dy = scrollRemY.toInt()
                scrollRemX -= dx
                scrollRemY -= dy
                val newScrollY = (vp.scrollY + dy).coerceIn(0, maxScrollY(state))
                // The X bound never undercuts the current offset: maxScrollX tracks only the VISIBLE
                // window, and a vertical drag past the widest line must not yank scrollX back to 0.
                val newScrollX = (vp.scrollX + dx).coerceIn(0, maxOf(maxScrollX(state), vp.scrollX))
                if (newScrollY != vp.scrollY || newScrollX != vp.scrollX) {
                    state.updateViewport { it.copy(scrollX = newScrollX, scrollY = newScrollY) }
                    return true
                }
                return false
            }

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val state = editorState ?: return false
                if (dragMovesCursor) return false
                val vp = state.viewport.value
                scroller.forceFinished(true)
                scroller.fling(
                    vp.scrollX, vp.scrollY,
                    -velocityX.toInt(), -velocityY.toInt(),
                    0, maxOf(maxScrollX(state), vp.scrollX), 0, maxScrollY(state),
                )
                postOnAnimation(flingRunnable)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                if (gutterLineAt(e.x, e.y) != null) return
                val offset = offsetAt(e.x, e.y) ?: return
                val word = wordAt(offset)
                if (word != null && onWordLongPress?.invoke(word.third, e.x, e.y) == true) {
                    performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    return
                }
                if (word != null) {
                    runBlocking { editorState?.setSelection(listOf(Caret(word.first, word.second))) }
                }
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onContextRequest?.invoke(EditorContextRequest(e.x, e.y, word?.third ?: ""))
            }
        },
    )

    // Fling animation: the OverScroller decays the lift-off velocity and each frame publishes the new
    // offsets to the viewport (whose observer invalidates) until it settles, a touch lands, or the
    // state detaches. Without this the content stops dead the instant the finger lifts.
    private val scroller = OverScroller(context)
    private val flingRunnable = object : Runnable {
        override fun run() {
            val state = editorState ?: return
            if (!scroller.computeScrollOffset()) return
            val vp = state.viewport.value
            // Re-clamp Y each frame against the DOCUMENT max so an edit that removes lines mid-coast
            // can't strand the viewport past the content. X is NOT window-re-clamped here — that would
            // yank an established offset left whenever the widest line coasts out of view.
            val x = scroller.currX.coerceAtLeast(0)
            val y = scroller.currY.coerceIn(0, maxScrollY(state))
            if (x != vp.scrollX || y != vp.scrollY) {
                state.updateViewport { it.copy(scrollX = x, scrollY = y) }
            }
            postOnAnimation(this)
        }
    }

    private fun cancelFling() {
        scroller.forceFinished(true)
        removeCallbacks(flingRunnable)
    }

    private fun lineHeightPx(state: EditorState): Int {
        val cfg = state.renderConfig.value
        return (cfg.fontSizeSp * density * cfg.lineHeightMultiplier).toInt().coerceAtLeast(1)
    }

    private fun maxScrollY(state: EditorState): Int {
        val vp = state.viewport.value
        return (state.snapshot.value.lineCount * lineHeightPx(state) - vp.heightPx).coerceAtLeast(0)
    }

    /** Scroll the viewport by [lines] text lines (positive = toward the end of the file). Drives
     *  external scroll bindings (e.g. volume keys); no-op when no state is attached. */
    fun scrollLines(lines: Int) {
        val state = editorState ?: return
        val vp = state.viewport.value
        val newY = (vp.scrollY + lines * lineHeightPx(state)).coerceIn(0, maxScrollY(state))
        if (newY != vp.scrollY) state.updateViewport { it.copy(scrollY = newY) }
    }

    /** How far the widest VISIBLE line allows scrolling right. Cheap (one batched line read) and
     *  stable while dragging; longer off-screen lines extend the range as they scroll into view. */
    private fun maxScrollX(state: EditorState): Int {
        val vp = state.viewport.value
        val snapshot = state.snapshot.value
        val cfg = state.renderConfig.value
        val top = vp.visibleLineTop
        val count = (snapshot.lineCount.coerceAtMost(vp.visibleLineBottom + 1) - top).coerceAtLeast(0)
        if (count == 0 || vp.widthPx <= 0) return 0
        val window = snapshot.readLines(top, count)
        val paint = measurePaintFor(cfg)
        val advance = asciiAdvance(paint)
        var maxWidth = 0f
        for (line in top until top + count) {
            if (!window.contains(line)) break
            val text = window.text(line)
            var allAscii = true
            for (i in text.indices) {
                if (text[i].code !in 0x20..0x7E) { allAscii = false; break }
            }
            val w = if (allAscii) text.length * advance else paint.measureText(text)
            if (w > maxWidth) maxWidth = w
        }
        val textArea = vp.widthPx - computeGutterWidth(snapshot, cfg) - 8
        return (maxWidth + advance * 2 - textArea).toInt().coerceAtLeast(0)
    }

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
        cancelFling()
        // A caret-visibility debounce armed under the previous state must not fire against this one
        // (the view is reused across tab switches).
        removeCallbacks(ensureCaretAfterResize)
        observationScope?.cancel()
        this.editorState = editorState
        this.renderer = Renderer(typeface, density)
        val scope = MainScope()
        observationScope = scope

        // Seed the new state's viewport with the current view size. onSizeChanged won't fire when a
        // new EditorState is attached to an already-laid-out view (tab switch), so without this the
        // viewport keeps heightPx=0 and only ~1-2 lines are considered visible.
        if (width > 0 && height > 0) {
            editorState.updateViewport { it.copy(widthPx = width, heightPx = height) }
        }

        // Observe state changes. An edit that removes lines (or a smaller reloaded file) can leave
        // scrollY past the new content; pull it back so the viewport never shows a stranded void.
        editorState.snapshot.onEach {
            val vp = editorState.viewport.value
            val clampedY = vp.scrollY.coerceIn(0, maxScrollY(editorState))
            if (clampedY != vp.scrollY) editorState.updateViewport { it.copy(scrollY = clampedY) }
            invalidate()
        }.launchIn(scope)
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
        // Follow the caret while typing: keep a collapsed cursor in view as it moves. Selections don't
        // auto-scroll, so select-all / drag-select don't yank the viewport around.
        editorState.carets.onEach { carets ->
            if (carets.firstOrNull()?.isSelection == false) ensureCaretVisible()
            invalidate()
        }.launchIn(scope)
        // A pending "reveal (line,col)" request (e.g. opening a file at a location tapped in the
        // terminal): place the caret there and scroll it into view.
        editorState.revealRequest.onEach { req -> if (req != null) revealLineColumn(req.line, req.column) }
            .launchIn(scope)
    }

    /** Move the caret to a 0-based (line, column), scroll it into view, and focus the editor. */
    private fun revealLineColumn(line: Int, column: Int) {
        val state = editorState ?: return
        // A coasting fling would overwrite the reveal on its next frame.
        cancelFling()
        val snapshot = state.snapshot.value
        val targetLine = line.coerceIn(0, max(0, snapshot.lineCount - 1))
        val offset = snapshot.lineColumnToOffset(targetLine, column.coerceAtLeast(0))
        // Compute line height from the render config (the viewport's may not be set yet on a fresh tab).
        val cfg = state.renderConfig.value
        val lineHeight = (cfg.fontSizeSp * density * cfg.lineHeightMultiplier).toInt().coerceAtLeast(1)
        runBlocking { state.setSelection(listOf(Caret(offset, offset))) }
        // Park the target a couple of lines below the top so there's context above it.
        val targetScrollY = (targetLine * lineHeight - lineHeight * 2).coerceIn(0, maxScrollY(state))
        state.updateViewport { it.copy(scrollX = 0, scrollY = targetScrollY) }
        state.clearReveal()
        requestFocus()
        invalidate()
    }

    /**
     * Scroll the caret back into the visible viewport when it sits outside it — e.g. the soft keyboard
     * just shrank the editor and the cursor ended up hidden behind it. Keeps one line of margin from the
     * edge it crossed; no-op when the caret is already comfortably visible.
     */
    private fun ensureCaretVisible() {
        val state = editorState ?: return
        val vp = state.viewport.value
        if (vp.heightPx <= 0) return
        val caret = state.carets.value.firstOrNull() ?: return
        val snapshot = state.snapshot.value
        val cfg = state.renderConfig.value
        val lineHeight = (cfg.fontSizeSp * density * cfg.lineHeightMultiplier).toInt().coerceAtLeast(1)
        val (caretLine, _) = snapshot.offsetToLineColumn(caret.head)
        val caretTop = caretLine * lineHeight
        val caretBottom = caretTop + lineHeight
        // Breathing room around the caret line, capped so the line itself still fully fits a short
        // (keyboard-squeezed) viewport instead of being clipped at an edge.
        val margin = ((vp.heightPx - lineHeight) / 3).coerceIn(0, lineHeight)
        val newScrollY = when {
            caretBottom + margin > vp.scrollY + vp.heightPx -> caretBottom + margin - vp.heightPx
            caretTop - margin < vp.scrollY -> caretTop - margin
            else -> vp.scrollY
        }.coerceIn(0, maxScrollY(state))

        // Horizontal: follow the caret past either edge of the text area (typing along a long line).
        val (lineStart, _) = snapshot.lineAt(caretLine)
        val prefix = snapshot.readRangeAsUtf16(lineStart, caret.head)
        val paint = measurePaintFor(cfg)
        var allAscii = true
        for (i in prefix.indices) {
            if (prefix[i].code !in 0x20..0x7E) { allAscii = false; break }
        }
        val caretX = if (allAscii) prefix.length * asciiAdvance(paint) else paint.measureText(prefix)
        val textArea = (vp.widthPx - computeGutterWidth(snapshot, cfg) - 8).coerceAtLeast(1)
        val xMargin = asciiAdvance(paint) * 2
        val newScrollX = when {
            caretX - vp.scrollX > textArea - xMargin -> (caretX - textArea + xMargin).toInt()
            caretX < vp.scrollX + xMargin -> (caretX - xMargin).toInt()
            else -> vp.scrollX
        }.coerceAtLeast(0)

        if (newScrollY != vp.scrollY || newScrollX != vp.scrollX) {
            // Cancel only when actually moving, so a no-op caret emission can't kill a live fling;
            // without the cancel, the fling's next frame would overwrite this follow-scroll.
            cancelFling()
            state.updateViewport { it.copy(scrollX = newScrollX, scrollY = newScrollY) }
            invalidate()
            // The follow-scroll moved the text under an open completion popup; re-anchor it.
            if (completionAnchorActive) updateCompletionAnchor()
        }
    }

    /** Detach the current EditorState. */
    fun detach() {
        cancelFling()
        removeCallbacks(ensureCaretAfterResize)
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

    private val ensureCaretAfterResize = Runnable { if (hasFocus()) ensureCaretVisible() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        editorState?.updateViewport { vp ->
            vp.copy(
                widthPx = w,
                heightPx = h,
            )
        }
        // The IME (or any layout change) shrank the editor: keep the caret above the keyboard.
        // The keyboard animates over many frames, so debounce to one scroll at the settled height
        // instead of measuring the caret line on every frame.
        if (h in 1 until oldh && hasFocus()) {
            removeCallbacks(ensureCaretAfterResize)
            postDelayed(ensureCaretAfterResize, 80)
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
        // Consume ACTION_DOWN so the gesture detector gets the rest of the stream (drag-scroll + tap).
        // Caret placement happens in the detector's onSingleTapUp, so dragging only scrolls.
        if (event.action == MotionEvent.ACTION_DOWN) {
            requestFocus()
            // A touch grabs a coasting fling, exactly like every scrolling surface on Android.
            cancelFling()
            dragAccumX = 0f
            dragAccumY = 0f
            scrollRemX = 0f
            scrollRemY = 0f
            // Claim the whole gesture: a touch that starts inside the editor belongs to the editor
            // (drag-scroll, or drag-to-move-caret when enabled). Otherwise an ancestor — the navigation
            // drawer's swipe-to-open — steals the left/right drag and pops the drawer mid-scroll.
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        return super.onTouchEvent(event)
    }

    /** The 0-based line if the tap is in the gutter (left margin), else null. */
    private fun gutterLineAt(x: Float, y: Float): Int? {
        val state = editorState ?: return null
        val snapshot = state.snapshot.value
        val config = state.renderConfig.value
        val gutterWidth = computeGutterWidth(snapshot, config)
        if (x >= gutterWidth) return null
        val lineHeightPx = (config.fontSizeSp * density * config.lineHeightMultiplier).toInt().coerceAtLeast(1)
        // Screen y of line L is (L - visibleLineTop)*lineHeight - scrollY % lineHeight (see Renderer).
        val lineIndex = state.viewport.value.visibleLineTop +
            ((y + state.viewport.value.scrollY % lineHeightPx) / lineHeightPx).toInt()
        if (lineIndex < 0 || lineIndex >= snapshot.lineCount) return null
        return lineIndex
    }

    /** Buffer offset at a pixel position, or null if outside the text. */
    private fun offsetAt(x: Float, y: Float): Int? {
        val state = editorState ?: return null
        val snapshot = state.snapshot.value
        val config = state.renderConfig.value
        val lineHeightPx = (config.fontSizeSp * density * config.lineHeightMultiplier).toInt().coerceAtLeast(1)
        val gutterWidth = computeGutterWidth(snapshot, config)
        val lineIndex = state.viewport.value.visibleLineTop +
            ((y + state.viewport.value.scrollY % lineHeightPx) / lineHeightPx).toInt()
        if (lineIndex < 0 || lineIndex >= snapshot.lineCount) return null
        val (lineStart, lineEnd) = snapshot.lineAt(lineIndex)
        val lineText = snapshot.readRangeAsUtf16(lineStart, lineEnd)
        val xInText = x - gutterWidth - 8f + state.viewport.value.scrollX
        val paint = measurePaintFor(config)
        // Fast path: an all-printable-ASCII line advances by a constant amount in a monospace font, so
        // the column is a single division. Non-ASCII (tabs, wide glyphs) falls back to per-char measure.
        var allAscii = true
        for (i in lineText.indices) {
            if (lineText[i].code !in 0x20..0x7E) { allAscii = false; break }
        }
        val col = if (allAscii) {
            val advance = asciiAdvance(paint)
            if (advance <= 0f) 0 else (xInText / advance).toInt().coerceIn(0, lineText.length)
        } else {
            var c = 0
            var measured = 0f
            for (i in lineText.indices) {
                val charWidth = paint.measureText(lineText[i].toString())
                if (measured + charWidth > xInText) break
                measured += charWidth
                c++
            }
            c
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

    // --- completion popup support ---------------------------------------------------------------

    /** Recompute the identifier prefix at the caret and notify the host (null when there's none). */
    fun updateCompletionAnchor() {
        val cb = onCompletionAnchorChanged ?: return
        val state = editorState ?: return
        fun dismiss() {
            completionAnchorActive = false
            cb(null)
        }
        val caretObj = state.carets.value.firstOrNull()
        if (caretObj == null || caretObj.isSelection) { dismiss(); return }
        val caret = caretObj.head
        val snapshot = state.snapshot.value
        val (line, _) = snapshot.offsetToLineColumn(caret)
        val (lineStart, _) = snapshot.lineAt(line)
        val before = snapshot.readRangeAsUtf16(lineStart, caret)
        var i = before.length
        while (i > 0 && (before[i - 1].isLetterOrDigit() || before[i - 1] == '_')) i--
        val prefix = before.substring(i)
        if (prefix.isEmpty()) { dismiss(); return }
        val replaceStart = caret - prefix.toByteArray(Charsets.UTF_8).size
        val pixel = anchorPixel(replaceStart, line) ?: run { dismiss(); return }
        completionAnchorActive = true
        cb(CompletionAnchor(prefix, replaceStart, caret, pixel.first, pixel.second))
    }

    /** View-relative pixel (x at [offset], y at the bottom of [line]) to anchor a popup under the word. */
    private fun anchorPixel(offset: Int, line: Int): Pair<Float, Float>? {
        val state = editorState ?: return null
        val snapshot = state.snapshot.value
        val config = state.renderConfig.value
        val lineHeight = (config.fontSizeSp * density * config.lineHeightMultiplier).toInt().coerceAtLeast(1)
        val (lineStart, _) = snapshot.lineAt(line)
        val prefixText = snapshot.readRangeAsUtf16(lineStart, offset)
        val gutter = computeGutterWidth(snapshot, config)
        val x = gutter + 8f + measurePaintFor(config).measureText(prefixText) - state.viewport.value.scrollX
        val y = ((line + 1) * lineHeight - state.viewport.value.scrollY).toFloat()
        return x to y
    }

    /** Replace [start, end) (byte offsets) with [text], placing the caret at [caretAfter]; accepts a completion. */
    fun replaceRange(start: Int, end: Int, text: String, caretAfter: Int) {
        val state = editorState ?: return
        runBlocking {
            state.applyEdit(EditTx.replace(start, end, text))
            val len = state.snapshot.value.byteLength
            val c = caretAfter.coerceIn(0, len)
            state.setSelection(listOf(Caret(c, c)))
        }
        invalidate()
        // Keep the anchor refresh suppressed through the whole sync sequence (updateImeCursor +
        // restartInput + dismiss), so accepting a completion can't transiently re-open the popup.
        suppressAnchorUpdate = true
        updateImeCursor()
        imm().restartInput(this)
        completionAnchorActive = false
        onCompletionAnchorChanged?.invoke(null)
        suppressAnchorUpdate = false
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
                KeyEvent.KEYCODE_MOVE_HOME -> moveCaretLineEdge(state, toStart = true)
                KeyEvent.KEYCODE_MOVE_END -> moveCaretLineEdge(state, toStart = false)
                KeyEvent.KEYCODE_PAGE_UP -> moveCaretLine(state, -visiblePageLines(state))
                KeyEvent.KEYCODE_PAGE_DOWN -> moveCaretLine(state, visiblePageLines(state))
                KeyEvent.KEYCODE_ESCAPE -> collapseSelection(state)
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

    private fun moveCaretLineEdge(state: EditorState, toStart: Boolean) {
        val caret = caretOrNull(state) ?: return
        val snapshot = state.snapshot.value
        val (line, _) = snapshot.offsetToLineColumn(caret.head)
        val newPos = if (toStart) {
            snapshot.lineColumnToOffset(line, 0)
        } else {
            // Line end = the byte before the next line's start (the newline), or EOF on the last line.
            if (line + 1 < snapshot.lineCount) {
                max(snapshot.lineColumnToOffset(line, 0), snapshot.lineColumnToOffset(line + 1, 0) - 1)
            } else {
                snapshot.byteLength
            }
        }
        runBlocking { state.setSelection(listOf(Caret(newPos, newPos))) }
        invalidate()
        updateImeCursor()
    }

    /** One "page" for PgUp/PgDn caret moves = the count of fully visible lines. */
    private fun visiblePageLines(state: EditorState): Int {
        val vp = state.viewport.value
        return (vp.visibleLineBottom - vp.visibleLineTop).coerceAtLeast(1)
    }

    private fun collapseSelection(state: EditorState) {
        val caret = caretOrNull(state) ?: return
        if (!caret.isSelection) return
        runBlocking { state.setSelection(listOf(Caret(caret.head, caret.head))) }
        invalidate()
        updateImeCursor()
    }

    /**
     * "Drag moves cursor" gesture: translate a one-finger drag into caret movement (lines vertically,
     * columns horizontally) and collapse to a single caret. The carets observer scrolls the view to
     * follow, so the cursor can be dragged anywhere in the document. [updateImeCursor] keeps the IME in
     * sync; long-press selection is handled separately and is unaffected.
     */
    // Drag distance (in line-heights / char-widths) needed to advance the caret one step. Higher level
    // = less drag per step = faster cursor. Level 3 is the raw 1:1 mapping; default 2 is calmer.
    private fun dragStepScale(level: Int): Float = when (level.coerceIn(1, 5)) {
        1 -> 3.0f
        2 -> 2.0f
        3 -> 1.0f
        4 -> 0.7f
        else -> 0.5f
    }

    private fun moveCaretByDrag(state: EditorState, distanceX: Float, distanceY: Float) {
        val cfg = state.renderConfig.value
        val lineHeight = (cfg.fontSizeSp * density * cfg.lineHeightMultiplier).toInt().coerceAtLeast(1)
        val charWidth = measurePaintFor(cfg).measureText("0").coerceAtLeast(1f)
        val vStep = (lineHeight * dragStepScale(cursorDragVerticalLevel)).coerceAtLeast(1f)
        val hStep = (charWidth * dragStepScale(cursorDragHorizontalLevel)).coerceAtLeast(1f)
        // onScroll distance is (previous - current); negate so a down/right drag advances the caret.
        dragAccumY -= distanceY
        dragAccumX -= distanceX
        val dLines = (dragAccumY / vStep).toInt()
        val dCols = (dragAccumX / hStep).toInt()
        if (dLines == 0 && dCols == 0) return
        dragAccumY -= dLines * vStep
        dragAccumX -= dCols * hStep

        val caret = caretOrNull(state) ?: return
        val snapshot = state.snapshot.value
        val (line, col) = snapshot.offsetToLineColumn(caret.head)
        val targetLine = (line + dLines).coerceIn(0, max(0, snapshot.lineCount - 1))
        val targetCol = (col + dCols).coerceAtLeast(0)
        val newPos = snapshot.lineColumnToOffset(targetLine, targetCol)
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
        // Every edit/caret move funnels through here, so refresh the completion popup anchor too.
        if (!suppressAnchorUpdate) updateCompletionAnchor()
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
        return (measurePaintFor(config).measureText(sample) + 24).toInt()
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
            KeyEvent.KEYCODE_MOVE_HOME,
            KeyEvent.KEYCODE_MOVE_END,
            KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_ESCAPE,
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

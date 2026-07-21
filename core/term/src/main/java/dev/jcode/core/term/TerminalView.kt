package dev.jcode.core.term

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import kotlinx.coroutines.*
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Terminal view with full VT100/xterm emulation support.
 * Uses native VT parser for high-performance terminal rendering.
 */
class TerminalView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    // The session this view renders. The session owns the PTY, the VT parser, and a background reader;
    // this view is a pure renderer + input surface bound to it. It does NOT own/close the parser.
    private var boundSession: TerminalSessionManager.Session? = null
    private var pty: PtyProcess? = null
    private var vtParser: VtParser? = null

    // Terminal dimensions
    private var cols = 80
    private var rows = 24

    // Cell dimensions (calculated from font metrics)
    private var cellWidth = 0f
    private var cellHeight = 0f

    // Selection state ("Select Text" in the context menu shows draggable handles over it). Rows are
    // LOGICAL — relative to the live screen top, negative rows in scrollback — so the highlight and
    // handles track the content while scrolled. Cleared on new output, resize, or session rebind.
    private var isSelecting = false

    // Invoked on the main thread with the contiguous token under a confirmed single tap, so the host
    // can open it as a URL (browser) or file path (editor).
    var onTapToken: ((String) -> Unit)? = null
    // Invoked (with the touch point in view pixels) when a long-press requests the action menu, so the
    // host can render the shared compact context menu instead of a native PopupMenu.
    var onContextMenu: ((Float, Float) -> Unit)? = null
    // Invoked on paste when the clipboard holds an image: the host saves it into the active project and
    // returns the guest path to paste (a raw terminal can't render an image). Null / null-return = skip.
    var onPasteImage: ((android.net.Uri) -> String?)? = null
    // Focus reporting for the extra-keys row: the host points the row at whichever surface owns the IME.
    var onFocusStateChanged: ((Boolean) -> Unit)? = null
    // Invoked when the user presses Ctrl+Shift+W; the host closes this terminal tab. Ctrl+W itself is
    // left to the shell (readline word-erase / vim window prefix). Null = fall through to terminal input.
    var onCloseTabRequest: (() -> Unit)? = null
    // Invoked when the user presses Ctrl+Shift+S; the host saves every open editor tab (instead of the
    // shell receiving XOFF/0x13). Null = fall through to normal terminal input.
    var onSaveAllRequest: (() -> Unit)? = null

    // One-shot sticky modifiers armed by the extra-keys row (Termux-style): applied to the NEXT
    // typed character, then cleared. CTRL turns a letter into its control byte; ALT prefixes ESC.
    var pendingCtrl = false
    var pendingAlt = false
    // Notified after an armed modifier is consumed (or discarded) by typed input, so the row can
    // un-highlight its CTRL/ALT chips.
    var onPendingModifiersConsumed: (() -> Unit)? = null
    // Kept normalized: (startRow, startCol) <= (endRow, endCol) row-major; end column is inclusive.
    private var selectionStartRow = 0
    private var selectionStartCol = 0
    private var selectionEndRow = 0
    private var selectionEndCol = 0

    // Handle-drag state (mirrors EditorView's selection handles): dragging one teardrop moves that
    // selection end while the other stays pinned at dragFixed*.
    private var draggingHandle = false
    private var dragFixedRow = 0
    private var dragFixedCol = 0
    private var dragPointerId = -1
    private var dragXBias = 0f
    // The long-press cell, content-anchored (see onLongPress), consumed by beginTextSelection().
    private var pressLogicalRow = 0
    private var pressCol = 0
    private var pressScrollback = 0
    private var handleRadiusPx = 0f
    private var startHandleCx = Float.NaN
    private var startHandleCy = Float.NaN
    private var endHandleCx = Float.NaN
    private var endHandleCy = Float.NaN

    // Scrollback view state
    private var scrollOffset = 0        // lines scrolled up from the live bottom (0 = follow output)
    private var scrollAccumY = 0f       // sub-cell scroll accumulator for smooth panning
    // Keeps the scrolled-up view stable as history grows: the parser's monotonic pushed-line count
    // (NOT scrollback size, which stops growing once the ring is full — anchoring on size made a
    // scrolled-back view drift once 2000 lines of history had accumulated).
    private var lastScrollbackPushed = 0L

    // Gesture detector: long-press selection, vertical pan to scroll history, tap to focus.
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Request the action menu. With a selection active it offers Copy; a handle drag never
            // reaches this detector, so no in-drag guard is needed. The press cell is captured
            // content-anchored (logical row + scrollback size) so "Select Text" still targets what
            // was under the finger even after output shifts rows while the menu is open.
            if (cellWidth > 0f && cellHeight > 0f) {
                pressLogicalRow = (e.y / cellHeight).toInt().coerceIn(0, rows - 1) - scrollOffset
                pressCol = (e.x / cellWidth).toInt().coerceIn(0, cols - 1)
                pressScrollback = vtParser?.scrollbackSize ?: 0
            }
            onContextMenu?.invoke(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (cellHeight <= 0f) return false
            // Dragging down (distanceY < 0) scrolls back into history; up returns toward the bottom.
            scrollAccumY -= distanceY
            val lines = (scrollAccumY / cellHeight).toInt()
            if (lines != 0) {
                scrollAccumY -= lines * cellHeight
                dispatchScroll(lines, e2.x, e2.y, gesture = true)
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Defer to onSingleTapConfirmed (opens a link/path) or onDoubleTap (shows the keyboard).
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // With a selection active, a tap elsewhere dismisses it (and must not also open a
            // link). With mouse tracking on, the tap belongs to the TUI (Claude Code's fullscreen
            // menus, htop's columns) as a click, not to the token-open shortcut. Otherwise a
            // confirmed single tap opens a link/path under the finger.
            val modes = currentInputModes()
            if (isSelecting) {
                clearSelection()
            } else if (VtParser.modeMouseMode(modes) != VtParser.MOUSE_OFF && appConsumesMouse(modes)) {
                sendMouseClick(e.x, e.y)
            } else {
                handleTokenTap(e.x, e.y)
            }
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            // Double tap is the deliberate gesture that focuses + shows the keyboard.
            requestFocus()
            showSoftKeyboard()
            return true
        }
    })

    // Rendering
    private val textPaint = Paint().apply {
        color = Color.WHITE
        textSize = 30f
        typeface = Typeface.MONOSPACE
        isAntiAlias = true
        isSubpixelText = true
    }

    private val bgPaint = Paint().apply {
        color = Color.BLACK
    }

    private val cursorPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    // Hollow cursor drawn when the view is NOT focused (like a real terminal).
    private val cursorOutlinePaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = 2f
        isAntiAlias = true
    }

    // Blinking-cursor state. The block toggles on/off while focused; any input or output resets it to
    // "on" so the cursor is solid while you type and resumes blinking when idle.
    private val blinkHandler = Handler(Looper.getMainLooper())
    private var cursorBlinkOn = true
    // Whether this view is the visible/active session (vs a background session still reading its PTY).
    private var isActiveSession = false
    private val cursorBlinkIntervalMs = 530L
    private val blinkRunnable = object : Runnable {
        override fun run() {
            cursorBlinkOn = !cursorBlinkOn
            invalidate()
            blinkHandler.postDelayed(this, cursorBlinkIntervalMs)
        }
    }

    private val selectionColorArgb = Color.argb(100, 59, 130, 246) // Blue selection highlight
    private val selectionBgPaint = Paint().apply {
        color = selectionColorArgb
        style = Paint.Style.FILL
    }
    // Teardrop selection anchors: the opaque hue of the selection tint.
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(59, 130, 246)
    }
    // Reused per background/selection run so a row of same-colored cells is one drawRect.
    private val runBgPaint = Paint().apply { style = Paint.Style.FILL }

    // 256-color palette (standard xterm colors). The 0-15 values and cube levels match xterm's
    // defaults — the previous fully-saturated Android constants made colors 4/12 and 7/15
    // identical, losing the normal-vs-bright distinction TUIs rely on.
    private val colorPalette = IntArray(256).apply {
        // Standard colors (0-7)
        this[0] = Color.rgb(0, 0, 0)
        this[1] = Color.rgb(205, 0, 0)
        this[2] = Color.rgb(0, 205, 0)
        this[3] = Color.rgb(205, 205, 0)
        this[4] = Color.rgb(0, 0, 238)
        this[5] = Color.rgb(205, 0, 205)
        this[6] = Color.rgb(0, 205, 205)
        this[7] = Color.rgb(229, 229, 229)

        // Bright colors (8-15)
        this[8] = Color.rgb(127, 127, 127)
        this[9] = Color.rgb(255, 0, 0)
        this[10] = Color.rgb(0, 255, 0)
        this[11] = Color.rgb(255, 255, 0)
        this[12] = Color.rgb(92, 92, 255)
        this[13] = Color.rgb(255, 0, 255)
        this[14] = Color.rgb(0, 255, 255)
        this[15] = Color.rgb(255, 255, 255)

        // 216 color cube (16-231): xterm levels 0/95/135/175/215/255, not a linear ramp.
        val levels = intArrayOf(0, 95, 135, 175, 215, 255)
        for (i in 0 until 216) {
            val r = levels[(i / 36) % 6]
            val g = levels[(i / 6) % 6]
            val b = levels[i % 6]
            this[16 + i] = Color.rgb(r, g, b)
        }

        // Grayscale (232-255)
        for (i in 0 until 24) {
            val gray = 8 + i * 10
            this[232 + i] = Color.rgb(gray, gray, gray)
        }
    }

    // Reused scratch buffer for drawing a single glyph (BMP = 1 char, supplementary pair = 2) — avoids
    // allocating a String per non-blank cell per frame in onDraw.
    private val glyphBuf = CharArray(2)

    // Reused row buffer for VtParser.readRow (main-thread only): one JNI call per row instead of six
    // per cell. Layout: VtParser.CELL_STRIDE ints per cell.
    private var rowBuf = IntArray(0)

    private fun rowBufFor(parser: VtParser): IntArray {
        val needed = parser.cols * VtParser.CELL_STRIDE
        if (rowBuf.size < needed) rowBuf = IntArray(needed)
        return rowBuf
    }

    // Reused whole-grid buffer for VtParser.readScreen (main-thread only): one JNI call per
    // re-recorded frame instead of one per visible row.
    private var screenBuf = IntArray(0)

    private fun screenBufFor(rowCount: Int, cols: Int): IntArray {
        val needed = rowCount * cols * VtParser.CELL_STRIDE
        if (screenBuf.size < needed) screenBuf = IntArray(needed)
        return screenBuf
    }

    // Reused buffer for a batched glyph run: consecutive same-colour/same-attr ASCII cells are drawn
    // with one canvas.drawText(char[]) call instead of one per cell.
    private var runGlyphs = CharArray(0)

    // The cell grid is recorded into this RenderNode and only re-recorded when content actually
    // changes (new output, scroll, selection, resize). Cursor blink and focus changes replay the
    // cached node instead of re-running the whole cell loop + readRow JNI + per-cell drawText — which
    // is what made an idle, focused terminal burn ~12% of a core twice a second.
    private val gridRenderNode = RenderNode("jcode-terminal-grid")
    private var gridDirty = true

    /** Mark the cell grid stale so the next draw re-records it; use for any content change. */
    private fun invalidateGrid() {
        gridDirty = true
        invalidate()
    }

    // Coalesces a burst of parser updates (e.g. fast build output) into at most one repaint per frame.
    private val repaintPending = java.util.concurrent.atomic.AtomicBoolean(false)

    init {
        setWillNotDraw(false)
        isFocusable = true
        isFocusableInTouchMode = true
        isLongClickable = true
        
        // Calculate cell dimensions
        cellWidth = textPaint.measureText("M")
        cellHeight = textPaint.fontSpacing
        
        // Handle touch events for selection-handle drags, history scrolling, and keyboard.
        setOnTouchListener { v, event ->
            // A grab on a selection handle owns the whole gesture: it must never reach the gesture
            // detector, or the same drag would also scroll history / re-open the menu. The guard
            // includes draggingHandle so a drag whose selection is force-cleared mid-gesture (new
            // output) still swallows its tail events instead of leaking a DOWN-less stream into
            // the detector, and still resets cleanly on UP/CANCEL.
            if (isSelecting || draggingHandle) {
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        draggingHandle = false // stale-drag safety: a fresh gesture starts clean
                        val anchorX = if (isSelecting) grabHandleAt(event.x, event.y) else null
                        if (anchorX != null) {
                            draggingHandle = true
                            // Finger offset from the grabbed end's text anchor, subtracted on every
                            // move so the selection edge tracks the grab point, not the fingertip.
                            dragXBias = event.x - anchorX
                            dragPointerId = event.getPointerId(0)
                            v.parent?.requestDisallowInterceptTouchEvent(true)
                            return@setOnTouchListener true
                        }
                    }
                    MotionEvent.ACTION_MOVE -> if (draggingHandle) {
                        if (isSelecting) {
                            val idx = event.findPointerIndex(dragPointerId)
                            if (idx >= 0) dragHandleTo(event.getX(idx), event.getY(idx))
                        }
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_POINTER_UP -> if (draggingHandle) {
                        // Invalidate the pointer but keep owning the gesture until UP/CANCEL:
                        // releasing here would leak the remaining finger's DOWN-less stream into
                        // the gesture detector, whose stale focus point then jump-scrolls history.
                        if (event.getPointerId(event.actionIndex) == dragPointerId) dragPointerId = -1
                        return@setOnTouchListener true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (draggingHandle) {
                        draggingHandle = false
                        return@setOnTouchListener true
                    }
                }
                if (draggingHandle) return@setOnTouchListener true
            }
            val gestureHandled = gestureDetector.onTouchEvent(event)
            if (event.action == MotionEvent.ACTION_DOWN) {
                // Fresh gesture: leftover wheel-travel fractions must not bleed into this drag.
                wheelAccum = 0
                // Claim the gesture: a touch starting in the terminal belongs to it (scroll / text
                // selection), so the nav drawer's swipe-to-open can't steal a drag and pop the drawer.
                v.parent?.requestDisallowInterceptTouchEvent(true)
                true
            } else {
                gestureHandled
            }
        }

        // Calculate terminal size on first layout
        viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                if (width > 0 && height > 0) {
                    val newCols = (width / cellWidth).toInt().coerceAtLeast(1)
                    val newRows = (height / cellHeight).toInt().coerceAtLeast(1)
                    if (newCols != cols || newRows != rows) {
                        resizeTerminal(newCols, newRows)
                    }
                    viewTreeObserver.removeOnGlobalLayoutListener(this)
                }
            }
        })
    }

    /** Select the token (contiguous non-space run) under the last long-press and show the
     *  draggable selection handles; a blank cell falls back to the row's trimmed extent. No-op on
     *  a blank row. The pressed cell was captured in onLongPress and is shifted by any scrollback
     *  growth since, so output arriving while the menu was open doesn't retarget the selection. */
    fun beginTextSelection() {
        val parser = vtParser ?: return
        if (!parser.isOpen || cellWidth <= 0f || cellHeight <= 0f) return
        val grown = (parser.scrollbackSize - pressScrollback).coerceAtLeast(0)
        val logicalRow = (pressLogicalRow - grown).coerceIn(-parser.scrollbackSize, rows - 1)
        val col = pressCol
        val line = rowText(parser, logicalRow) ?: return
        var start: Int
        var end: Int
        if (col < line.length && line[col] != ' ') {
            start = col
            while (start > 0 && line[start - 1] != ' ') start--
            end = col
            while (end + 1 < line.length && line[end + 1] != ' ') end++
        } else {
            start = line.indexOfFirst { it != ' ' }
            end = line.indexOfLast { it != ' ' }
            if (start < 0) return
        }
        isSelecting = true
        selectionStartRow = logicalRow
        selectionStartCol = start
        selectionEndRow = logicalRow
        selectionEndCol = end
        invalidateGrid()
    }

    private fun clearSelection() {
        if (!isSelecting) return
        isSelecting = false
        invalidateGrid()
    }

    /** If the touch grabs a handle, pins the OTHER selection end (into dragFixed*) and returns the
     *  CENTER x of the grabbed end's cell for drag bias — computed from the selection fields, not
     *  the drawn bulb (which may be edge-clamped), and mid-cell so dragHandleTo's floor mapping
     *  round-trips instead of growing the selection a column on the first jittered MOVE. Null when
     *  no handle is hit; nearest handle wins when the touch targets overlap. */
    private fun grabHandleAt(x: Float, y: Float): Float? {
        val slop = max(handleRadiusPx * 1.6f, 20f * resources.displayMetrics.density)
        val dStart = if (startHandleCx.isNaN()) Float.MAX_VALUE else hypot(x - startHandleCx, y - startHandleCy)
        val dEnd = if (endHandleCx.isNaN()) Float.MAX_VALUE else hypot(x - endHandleCx, y - endHandleCy)
        return when {
            dStart <= slop && dStart <= dEnd -> {
                dragFixedRow = selectionEndRow
                dragFixedCol = selectionEndCol
                (selectionStartCol + 0.5f) * cellWidth
            }
            dEnd <= slop -> {
                dragFixedRow = selectionStartRow
                dragFixedCol = selectionStartCol
                (selectionEndCol + 0.5f) * cellWidth
            }
            else -> null
        }
    }

    /** Move the dragged selection end to the cell under the finger, keeping the other end pinned.
     *  The bulb hangs below the text anchor, so the mapped point is biased back up onto it
     *  (dragXBias horizontally, one bulb + half a cell vertically). */
    private fun dragHandleTo(x: Float, y: Float) {
        if (cellWidth <= 0f || cellHeight <= 0f) return
        val hotspotDy = handleRadiusPx + cellHeight * 0.5f
        val viewRow = ((y - hotspotDy) / cellHeight).toInt().coerceIn(0, rows - 1)
        val col = ((x - dragXBias) / cellWidth).toInt().coerceIn(0, cols - 1)
        if (setNormalizedSelection(dragFixedRow, dragFixedCol, viewRow - scrollOffset, col)) {
            invalidateGrid()
        }
    }

    /** Store the pair ordered row-major (start <= end), so the dragged end may freely cross the
     *  pinned one. Returns true when the stored selection actually changed. */
    private fun setNormalizedSelection(r1: Int, c1: Int, r2: Int, c2: Int): Boolean {
        val swap = r2 < r1 || (r2 == r1 && c2 < c1)
        val sr = if (swap) r2 else r1
        val sc = if (swap) c2 else c1
        val er = if (swap) r1 else r2
        val ec = if (swap) c1 else c2
        if (sr == selectionStartRow && sc == selectionStartCol &&
            er == selectionEndRow && ec == selectionEndCol
        ) return false
        selectionStartRow = sr
        selectionStartCol = sc
        selectionEndRow = er
        selectionEndCol = ec
        return true
    }

    /** Returns true only when text was actually written to the clipboard. */
    private fun copySelectionToClipboard(): Boolean {
        val text = getSelectedText()
        if (text.isBlank()) return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Selection", text))
        return true
    }

    /** Actions for the host-rendered long-press menu (see [onContextMenu]). */
    fun hasSelection(): Boolean = isSelecting

    fun contextCopy() {
        // The selection may have been dismissed (new output) while the menu was open; the stored
        // rows then point at shifted content, so copying them would grab the wrong text.
        if (!isSelecting) return
        val copied = copySelectionToClipboard()
        clearSelection()
        toast(if (copied) "Copied" else "Nothing to copy")
    }

    fun contextSelectAll() = selectAllAndCopy()

    fun contextPaste() = pasteFromClipboard()

    fun contextClear() = clearScreen()

    /** Paste the clipboard into the terminal. An image is saved into the project and its (shell-quoted)
     *  path is pasted — a raw terminal can't render an image, and CLIs accept an image file path.
     *  Otherwise the clipboard text is written to the PTY as keyboard input. */
    private fun pasteFromClipboard() {
        pasteFromClipboard(retriesLeft = 8)
    }

    private fun pasteFromClipboard(retriesLeft: Int) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }
        if (clip == null) {
            // Clipboard reads require window focus; a dismissing context-menu popup can still hold
            // it for a few frames — retry briefly instead of silently dropping the paste.
            if (retriesLeft > 0) postDelayed({ pasteFromClipboard(retriesLeft - 1) }, 50L)
            return
        }
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val isImage = type?.startsWith("image/") == true || clip.description?.hasMimeType("image/*") == true
            if (isImage) {
                val guestPath = onPasteImage?.invoke(uri)
                if (guestPath != null) sendPaste(shellQuote(guestPath) + " ") else toast("Couldn't paste image")
                return
            }
        }
        val text = item.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) sendPaste(text)
    }

    /** Write pasted text to the PTY the way xterm does: newlines become CR, and when the app
     *  requested bracketed paste (?2004) the text is wrapped in ESC[200~ / ESC[201~ so multi-line
     *  pastes don't execute line-by-line (shells) or get misread as typed keys (TUIs). Any end
     *  marker embedded in the pasted text is stripped — it would terminate the bracket early and
     *  let the remainder run as keyboard input. */
    private fun sendPaste(text: String) {
        val normalized = text.replace("\r\n", "\r").replace("\n", "\r")
        val modes = currentInputModes()
        if ((modes and VtParser.MODE_BRACKETED_PASTE) != 0) {
            sendInput("\u001B[200~" + normalized.replace("\u001B[201~", "") + "\u001B[201~")
        } else {
            sendInput(normalized)
        }
    }

    /** Single-quote a path for the shell so any spaces or specials in it are treated literally. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Select the whole visible screen and copy it to the clipboard. */
    private fun selectAllAndCopy() {
        val parser = vtParser ?: return
        if (!parser.isOpen) return
        clearSelection()
        selectionStartRow = -scrollOffset
        selectionStartCol = 0
        selectionEndRow = (rows - 1).coerceAtLeast(0) - scrollOffset
        selectionEndCol = (cols - 1).coerceAtLeast(0)
        toast(if (copySelectionToClipboard()) "Copied" else "Nothing to copy")
    }

    /** Clear the screen via Ctrl-L (the shell/readline redraws a fresh prompt). */
    private fun clearScreen() {
        sendInput(byteArrayOf(0x0C))
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** The logical row's text with blank cells as spaces (BMP-truncated: for boundary scans only,
     *  extraction goes through [getSelectedText]), or null when it can't be read. */
    private fun rowText(parser: VtParser, logicalRow: Int): String? {
        val maxCol = min(cols, parser.cols)
        if (maxCol <= 0) return null
        val buf = rowBufFor(parser)
        val filled = parser.readRow(logicalRow, buf)
        val sb = StringBuilder(maxCol)
        for (c in 0 until min(maxCol, filled)) {
            val cp = buf[c * VtParser.CELL_STRIDE]
            sb.append(if (cp == 0 || cp == ' '.code) ' ' else cp.toChar())
        }
        return sb.toString()
    }

    /** Extract the contiguous non-space token under a tap and hand it to [onTapToken] (URL/path open). */
    private fun handleTokenTap(x: Float, y: Float) {
        val parser = vtParser ?: return
        if (!parser.isOpen || cellWidth <= 0f || cellHeight <= 0f) return
        val viewRow = (y / cellHeight).toInt()
        val col = (x / cellWidth).toInt()
        if (viewRow < 0 || col < 0) return
        val line = rowText(parser, viewRow - scrollOffset) ?: return
        if (col >= line.length || line[col] == ' ') return
        var start = col
        while (start > 0 && line[start - 1] != ' ') start--
        var end = col
        while (end < line.length && line[end] != ' ') end++
        val token = line.substring(start, end).trim()
        if (token.isNotBlank()) onTapToken?.invoke(token)
    }

    private fun getSelectedText(): String {
        // A persistent selection can outlive the session: the parser may have been closed (reaped)
        // while this view is still bound, and readRow on a closed parser throws.
        val parser = vtParser?.takeIf { it.isOpen } ?: return ""
        val sb = StringBuilder()
        val buf = rowBufFor(parser)

        for (row in selectionStartRow..selectionEndRow) {
            val filled = parser.readRow(row, buf)
            val rowStartCol = if (row == selectionStartRow) selectionStartCol else 0
            val rowEndCol = if (row == selectionEndRow) selectionEndCol else cols - 1

            for (col in rowStartCol..rowEndCol.coerceAtMost(filled - 1)) {
                val cp = buf[col * VtParser.CELL_STRIDE]
                if (cp != 0) {
                    sb.appendCodePoint(cp)
                }
            }
            if (row < selectionEndRow) {
                sb.append('\n')
            }
        }

        return sb.toString()
    }

    /**
     * Bind this view to a terminal session. The view renders the session's parser and writes input to
     * its PTY; the session's own background reader (in TerminalSessionManager) keeps feeding the parser
     * whether or not this view is attached, so content survives the panel being hidden.
     *
     * NOTE: focus/keyboard are NOT taken here — only the active session should, via [setActive].
     */
    fun bind(session: TerminalSessionManager.Session) {
        if (boundSession === session) return
        unbind()
        boundSession = session
        pty = session.pty
        vtParser = session.parser
        cols = session.cols
        rows = session.rows
        scrollOffset = 0
        scrollAccumY = 0f
        isSelecting = false
        lastScrollbackPushed = session.parser.scrollbackPushed
        gridDirty = true  // new session's content must be re-recorded, not the previous grid replayed
        // Repaint whenever the session parses new output. Called off the main thread by the session
        // reader, so hop to main; only the visible session repaints, and a scrolled-back view stays
        // anchored as new lines push into scrollback.
        session.onUpdate = {
            // Called off the main thread by the session reader. Coalesce to at most one repaint per
            // frame so a process emitting many small writes doesn't flood the main looper with post()s.
            if (isActiveSession && repaintPending.compareAndSet(false, true)) {
                postOnAnimation {
                    repaintPending.set(false)
                    if (boundSession === session && session.parser.isOpen) {
                        // Size + pushed must be one consistent pair: the reader mutates both under
                        // the session lock during feed, and a torn read near the top of history
                        // would mis-anchor the view by the missed lines.
                        val state = synchronized(session) {
                            if (session.parser.isOpen) {
                                session.parser.scrollbackSize to session.parser.scrollbackPushed
                            } else null
                        }
                        if (state != null) {
                            val (sb, pushed) = state
                            val pushedDelta = (pushed - lastScrollbackPushed).coerceAtLeast(0L)
                            lastScrollbackPushed = pushed
                            val offsetBefore = scrollOffset
                            var anchorClamped = false
                            if (scrollOffset > 0 && pushedDelta > 0) {
                                // Long math: a session streaming for hours in the background can
                                // accumulate a delta that overflows Int and would wrap the offset
                                // negative (blank screen).
                                val want = scrollOffset.toLong() + pushedDelta
                                scrollOffset = want.coerceAtMost(sb.toLong()).toInt()
                                anchorClamped = scrollOffset.toLong() != want
                            }
                            if (scrollOffset > sb) {
                                // Scrollback can SHRINK (ED 3 / reset) — an offset past the new
                                // end would render blank rows.
                                scrollOffset = sb
                                anchorClamped = true
                            }
                            // While the viewport sat entirely inside scrollback (append-only) both
                            // BEFORE and after the shift — the cached frame may contain live rows
                            // otherwise — and the anchor absorbed the shift without clamping,
                            // streaming output below changes no visible pixels: skip the full grid
                            // re-record that made reading history during a build/claude stream
                            // cost a frame of work per output chunk. The cursor is hidden while
                            // scrolled back, so blink state needs no reset either.
                            if (!isSelecting && !anchorClamped && offsetBefore >= rows) {
                                return@postOnAnimation
                            }
                            // New output shifts rows under a persistent selection — drop it (the
                            // terminal's equivalent of the editor's text-change dismissal) rather
                            // than highlight the wrong cells.
                            clearSelection()
                            invalidateGrid()
                            resetBlink()
                        }
                    }
                }
            }
        }
        invalidateGrid()
    }

    /** Stop rendering the bound session (without killing it — the session keeps running). */
    fun unbind() {
        boundSession?.let { if (it.onUpdate != null) it.onUpdate = null }
        boundSession = null
        pty = null
        vtParser = null
    }

    /**
     * Mark this view as the visible/active session. Only the active session takes input focus (and
     * raises the keyboard); inactive sessions keep reading their PTY in the background but must not
     * hold focus, or keystrokes would go to the wrong terminal.
     */
    fun setActive(active: Boolean) {
        if (active == isActiveSession) return
        isActiveSession = active
        // A backgrounded session's output doesn't reach the onUpdate dismissal above, so a
        // selection left behind on tab switch could point at stale rows — drop it on transition.
        clearSelection()
        if (active) {
            // Post so focus is requested after this view is laid out/attached — requesting it
            // synchronously right after creation (new session tab) is too early and silently fails,
            // which would leave input going to whatever held focus before. We only take focus (so
            // output/scroll work); the keyboard waits for a deliberate double tap so it never pops up
            // just from opening/switching a terminal.
            post {
                requestFocus()
            }
        } else {
            clearFocus()
        }
        invalidate()
    }

    /**
     * Show the soft keyboard for terminal input. Uses an explicit (non-implicit) request so a
     * deliberate double tap re-raises it even after the user dismissed it with the Back button
     * (which suppresses SHOW_IMPLICIT until the next explicit show).
     */
    private fun showSoftKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? android.view.inputmethod.InputMethodManager
        imm?.showSoftInput(this, 0)
    }

    /**
     * Detach from the current session. The session (PTY + parser + reader) keeps running; this view
     * just stops rendering it.
     */
    fun detach() {
        isSelecting = false
        draggingHandle = false
        unbind()
    }

    /**
     * Send input to the terminal.
     */
    fun sendInput(text: String) {
        val p = pty
        if (p?.isOpen == true) {
            scrollToBottom()
            p.write(text)
            resetBlink()
        }
    }

    /**
     * Send input bytes to the terminal.
     */
    fun sendInput(data: ByteArray) {
        val p = pty
        if (p?.isOpen == true) {
            scrollToBottom()
            p.write(data)
            resetBlink()
        }
    }

    /** Clamp and apply a new scrollback offset (lines scrolled up from the live bottom). */
    private fun setScrollOffset(offset: Int) {
        val parser = vtParser ?: return
        val clamped = offset.coerceIn(0, parser.scrollbackSize)
        if (clamped != scrollOffset) {
            scrollOffset = clamped
            invalidateGrid()
        }
    }

    /** The reader-published DEC-mode snapshot of the bound session (0 when unbound). Reading the
     *  snapshot instead of the native parser keeps modes + alt-screen mutually consistent and
     *  never races the reader's feed or a session close. */
    private fun currentInputModes(): Int = boundSession?.inputModesSnapshot ?: 0

    /** A latched mouse mode (TUI killed without resetting) must not swallow taps/scrolls at the
     *  shell prompt: only route mouse reports while a program is plausibly consuming them — on
     *  the alternate screen, or with a foreground program reported via shell integration. */
    private fun appConsumesMouse(modes: Int): Boolean =
        (modes and VtParser.MODE_ALT_SCREEN) != 0 || boundSession?.foreground != null

    // Touch travel accumulated toward the next wheel event (reset per gesture): TUIs scroll
    // several rows per wheel notch (htop: 10), so one event per cell of finger travel reads as
    // wildly amplified. One event per 3 cells tracks content roughly 1:1.
    private var wheelAccum = 0
    private val wheelCellsPerEvent = 3

    /**
     * Route a scroll of [lines] (positive = toward earlier content) the way a desktop terminal
     * routes its wheel: to the application as wheel events when it enabled mouse tracking (Claude
     * Code's fullscreen TUI, htop), as arrow keys on the alternate screen under ?1007
     * alternate-scroll (default on — vim/less), and into local scrollback otherwise. [x]/[y]
     * locate the pointer for the wheel report; [gesture] rate-divides touch travel.
     */
    private fun dispatchScroll(lines: Int, x: Float, y: Float, gesture: Boolean = false) {
        val modes = currentInputModes()
        val onAltScreen = (modes and VtParser.MODE_ALT_SCREEN) != 0
        // ?9 (X10) tracking reports presses only — wheel events don't exist there.
        val wheelToApp = VtParser.modeMouseMode(modes) >= VtParser.MOUSE_NORMAL && appConsumesMouse(modes)
        val steps = min(kotlin.math.abs(lines), rows.coerceAtLeast(1))
        when {
            wheelToApp -> {
                var events = lines
                if (gesture) {
                    wheelAccum += lines
                    events = wheelAccum / wheelCellsPerEvent
                    wheelAccum -= events * wheelCellsPerEvent
                }
                if (events != 0) {
                    val button = if (events > 0) 64 else 65
                    repeat(min(kotlin.math.abs(events), rows.coerceAtLeast(1))) {
                        sendMouseEvent(button, x, y, press = true, modes = modes)
                    }
                }
            }
            onAltScreen && (modes and VtParser.MODE_ALT_SCROLL) != 0 -> {
                val app = (modes and VtParser.MODE_APP_CURSOR_KEYS) != 0
                val seq = when {
                    lines > 0 -> if (app) "\u001BOA" else "\u001B[A"
                    else -> if (app) "\u001BOB" else "\u001B[B"
                }
                sendInput(seq.repeat(steps))
            }
            else -> setScrollOffset(scrollOffset + lines)
        }
    }

    /** Physical mouse / external scroll wheel: Android delivers wheel notches as ACTION_SCROLL
     *  generic-motion events, not touch gestures, so they bypass the GestureDetector. Route them
     *  through the same logic as a touch scroll so a mouse wheel scrolls a foreground TUI (Claude
     *  Code), an alt-screen pager, or local scrollback correctly — instead of falling through to the
     *  surrounding view, which scrolled the panel like a document. */
    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_SCROLL) {
            val vscroll = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
            if (vscroll != 0f) {
                // AXIS_VSCROLL > 0 = wheel up = toward earlier content. gesture=true reuses the
                // per-notch accumulation so one wheel notch is one app wheel-event but ~3 lines of
                // local scrollback, matching a desktop terminal.
                dispatchScroll(if (vscroll > 0) 3 else -3, event.x, event.y, gesture = true)
                return true
            }
        }
        return super.onGenericMotionEvent(event)
    }

    /** Report a tap as a mouse press (+ release when the tracking level includes releases). */
    private fun sendMouseClick(x: Float, y: Float) {
        val modes = currentInputModes()
        sendMouseEvent(button = 0, x = x, y = y, press = true, modes = modes)
        if (VtParser.modeMouseMode(modes) >= VtParser.MOUSE_NORMAL) {
            sendMouseEvent(button = 0, x = x, y = y, press = false, modes = modes)
        }
    }

    /** Write a synthetic terminal report (mouse/focus) to the PTY without [sendInput]'s keystroke
     *  side effects — a report must never snap a scrolled-back view to the live bottom. */
    private fun sendReport(text: String) {
        pty?.takeIf { it.isOpen }?.write(text)
    }

    private fun sendReport(data: ByteArray) {
        pty?.takeIf { it.isOpen }?.write(data)
    }

    /** Encode one mouse event at view position ([x], [y]) in the negotiated encoding. Wheel
     *  events (buttons 64/65) are press-only. */
    private fun sendMouseEvent(button: Int, x: Float, y: Float, press: Boolean, modes: Int) {
        if (cellWidth <= 0f || cellHeight <= 0f) return
        val col = ((x / cellWidth).toInt() + 1).coerceIn(1, cols)
        val row = ((y / cellHeight).toInt() + 1).coerceIn(1, rows)
        when (VtParser.modeMouseEncoding(modes)) {
            VtParser.MOUSE_ENC_SGR ->
                sendReport("\u001B[<$button;$col;$row${if (press) 'M' else 'm'}")
            VtParser.MOUSE_ENC_URXVT ->
                sendReport("\u001B[${(if (press) button else 3) + 32};$col;${row}M")
            else -> {
                // Legacy X10 bytes (also the ?1005 fallback): 32+button / 32+coord, undefined past
                // coordinate 223 — such events are dropped, matching Termux.
                if (col > 223 || row > 223) return
                val b = (if (press) button else 3) + 32
                sendReport(byteArrayOf(0x1B, '['.code.toByte(), 'M'.code.toByte(), b.toByte(), (32 + col).toByte(), (32 + row).toByte()))
            }
        }
    }

    /** Scroll by [delta] lines (positive = up into history, negative = toward the live bottom).
     *  Drives external scroll bindings (e.g. volume keys); routed like a touch scroll, so a TUI
     *  with mouse tracking sees wheel events at the screen center. */
    fun scrollByLines(delta: Int) {
        dispatchScroll(delta, width / 2f, height / 2f)
    }

    /** Jump back to the live bottom of the terminal (following new output). */
    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            scrollAccumY = 0f
            invalidateGrid()
        }
    }

    /** The xterm control byte for Ctrl+[ch], or null when the char has no control mapping. */
    private fun ctrlByteFor(ch: Char): Byte? {
        val c = ch.uppercaseChar()
        return when {
            c in '@'..'_' -> (c.code and 0x1F).toByte()
            ch == ' ' -> 0
            ch == '/' -> 0x1F
            else -> null
        }
    }

    /**
     * Send a key event to the terminal.
     * Supports TUI apps (vim, htop, nano, mc) with special key combinations.
     */
    fun sendKey(keyCode: Int, event: KeyEvent?): Boolean {
        val isCtrl = event?.isCtrlPressed == true
        val isAlt = event?.isAltPressed == true
        val isShift = event?.isShiftPressed == true

        // Workbench shortcuts intercepted before the shell sees them: Ctrl+Shift+W closes this terminal
        // tab and Ctrl+Shift+S saves all editor tabs. Ctrl+W is deliberately NOT taken — it stays the
        // shell's readline word-erase / vim's window-navigation prefix. Only intercept when the host
        // wired a handler, so a bare terminal keeps the keys.
        if (isCtrl && isShift && !isAlt) {
            if (keyCode == KeyEvent.KEYCODE_W) onCloseTabRequest?.let { it(); return true }
            if (keyCode == KeyEvent.KEYCODE_S) onSaveAllRequest?.let { it(); return true }
        }
        // DECCKM: cursor/Home/End keys switch to the SS3 form while an app holds application
        // cursor-keys mode (vim, less, Claude Code's fullscreen TUI). Modified (Alt/Ctrl)
        // sequences stay CSI, matching xterm.
        val appCursor = (currentInputModes() and VtParser.MODE_APP_CURSOR_KEYS) != 0

        // Ctrl+<printable> -> the xterm control byte (Ctrl+C=0x03, Ctrl+R=0x12, Ctrl+L=0x0C, ...).
        // Covers every readline/TUI binding rather than a hand-picked few, and matches the on-screen
        // sticky-Ctrl chip. Keys with no printable base (arrows, F-keys) report unicodeChar 0 here
        // and fall through to the table below. Ctrl+L now sends raw 0x0C so the running program
        // (readline, vim, Claude Code) decides what to do, instead of us injecting a clear sequence.
        if (isCtrl && !isAlt) {
            val base = event?.getUnicodeChar(0) ?: 0
            if (base != 0) {
                ctrlByteFor(base.toChar())?.let { sendInput(byteArrayOf(it)); return true }
            }
        }

        val bytes = when {
            // Ctrl+Left/Right -> word-wise cursor motion (xterm modifier form).
            isCtrl && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> "[1;5D".toByteArray()
            isCtrl && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> "[1;5C".toByteArray()
            // Alt+arrow keys (TUI navigation in vim, mc, etc.)
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_UP -> "\u001B\u001B[A".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B\u001B[B".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B\u001B[C".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B\u001B[D".toByteArray()
            // Alt+F1-F12 (function keys with Alt modifier)
            isAlt && keyCode == KeyEvent.KEYCODE_F1 -> "\u001B\u001BOP".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_F2 -> "\u001B\u001BOQ".toByteArray()
            // Standard keys
            keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER -> byteArrayOf(0x0D)
            keyCode == KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> (if (appCursor) "\u001BOA" else "\u001B[A").toByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> (if (appCursor) "\u001BOB" else "\u001B[B").toByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> (if (appCursor) "\u001BOC" else "\u001B[C").toByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> (if (appCursor) "\u001BOD" else "\u001B[D").toByteArray()
            isShift && keyCode == KeyEvent.KEYCODE_TAB -> "[Z".toByteArray()
            keyCode == KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
            keyCode == KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
            // Function keys (F1-F12)
            keyCode == KeyEvent.KEYCODE_F1 -> "\u001BOP".toByteArray()
            keyCode == KeyEvent.KEYCODE_F2 -> "\u001BOQ".toByteArray()
            keyCode == KeyEvent.KEYCODE_F3 -> "\u001BOR".toByteArray()
            keyCode == KeyEvent.KEYCODE_F4 -> "\u001BOS".toByteArray()
            keyCode == KeyEvent.KEYCODE_F5 -> "\u001B[15~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F6 -> "\u001B[17~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F7 -> "\u001B[18~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F8 -> "\u001B[19~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F9 -> "\u001B[20~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F10 -> "\u001B[21~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F11 -> "\u001B[23~".toByteArray()
            keyCode == KeyEvent.KEYCODE_F12 -> "\u001B[24~".toByteArray()
            // Home/End/PageUp/PageDown
            keyCode == KeyEvent.KEYCODE_MOVE_HOME -> (if (appCursor) "\u001BOH" else "\u001B[H").toByteArray()
            keyCode == KeyEvent.KEYCODE_MOVE_END -> (if (appCursor) "\u001BOF" else "\u001B[F").toByteArray()
            keyCode == KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~".toByteArray()
            keyCode == KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~".toByteArray()
            // Insert/Delete
            keyCode == KeyEvent.KEYCODE_INSERT -> "\u001B[2~".toByteArray()
            keyCode == KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~".toByteArray()
            else -> return false
        }
        sendInput(bytes)
        return true
    }

    /**
     * Handle hardware/physical keyboard input (and `adb input`) directly. Soft-keyboard input is
     * handled separately via [onCreateInputConnection]. Printable characters are sent as UTF-8;
     * control/navigation keys go through [sendKey].
     */
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (pty?.isOpen != true) return super.onKeyDown(keyCode, event)
        // Control/navigation keys first: some hardware keyboards report a unicodeChar for Enter
        // (e.g. '\n'), which must not be sent verbatim — Enter must always resolve to CR (0x0D) via
        // sendKey so a TUI like Claude Code submits the line instead of inserting a newline.
        if (sendKey(keyCode, event)) {
            // A handled special key still consumes the one-shot sticky Ctrl/Alt chip so it can't
            // leak onto the next keystroke (e.g. sticky Ctrl + Tab must not turn the next letter
            // into a control byte).
            if (pendingCtrl || pendingAlt) {
                pendingCtrl = false
                pendingAlt = false
                onPendingModifiersConsumed?.invoke()
            }
            return true
        }
        if (!event.isCtrlPressed && !event.isAltPressed) {
            val uch = event.unicodeChar
            if (uch != 0) {
                val text = uch.toChar().toString()
                if (!sendWithPendingModifiers(text)) sendInput(text)
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Set the terminal font size.
     */
    fun setFontSize(size: Float) {
        textPaint.textSize = size
        cellWidth = textPaint.measureText("M")
        cellHeight = textPaint.fontSpacing
        if (width > 0 && height > 0) {
            val newCols = (width / cellWidth).toInt().coerceAtLeast(1)
            val newRows = (height / cellHeight).toInt().coerceAtLeast(1)
            resizeTerminal(newCols, newRows)
        } else {
            invalidateGrid()
        }
    }

    /**
     * Get the current font size.
     */
    fun getFontSize(): Float = textPaint.textSize

    /**
     * Set the terminal font (typeface). Re-measures the monospace cell metrics and re-records the grid.
     */
    fun setTypeface(tf: Typeface) {
        if (textPaint.typeface === tf) return
        textPaint.typeface = tf
        cellWidth = textPaint.measureText("M")
        cellHeight = textPaint.fontSpacing
        if (width > 0 && height > 0) {
            val newCols = (width / cellWidth).toInt().coerceAtLeast(1)
            val newRows = (height / cellHeight).toInt().coerceAtLeast(1)
            resizeTerminal(newCols, newRows)
        }
        // Unconditionally re-record: resizeTerminal early-returns when cols/rows are unchanged (common
        // for a same-metrics font swap), which would otherwise replay the old font from the cached node.
        invalidateGrid()
    }

    /**
     * Resize the terminal.
     */
    fun resizeTerminal(cols: Int, rows: Int) {
        if (cols == this.cols && rows == this.rows) return

        this.cols = cols
        this.rows = rows

        // Resize the bound session's PTY + parser together (reflows content through scrollback,
        // anchored to the bottom). The session owns these so background sessions stay sized too.
        boundSession?.resize(cols, rows)

        // The reflow moves content across rows, so a persistent selection would highlight the
        // wrong cells.
        clearSelection()

        // Snap to the live bottom: after a resize (e.g. the keyboard opening/closing) the scrollback
        // size changed under us, so any prior scroll offset would point at the wrong content.
        scrollOffset = 0
        scrollAccumY = 0f
        lastScrollbackPushed = vtParser?.scrollbackPushed ?: 0L

        invalidateGrid()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The IME animation resizes this view every frame; re-recording the whole grid each time
        // (readRow JNI + per-run drawText) is the main keyboard-toggle jank. On SHRINK keep the
        // cached node and just clamp its bounds — RenderNode.clipToBounds (default true) then clips
        // the replay, so no re-record per frame. Only a GROW leaves the node covering less than the
        // view. The debounced resizeTerminal ends in invalidateGrid() for the final cell-grid change
        // either way.
        if (w > oldw || h > oldh) {
            gridDirty = true
        } else if (gridRenderNode.hasDisplayList()) {
            gridRenderNode.setPosition(0, 0, w, h)
        }
        if (w > 0 && h > 0 && cellWidth > 0f && cellHeight > 0f) {
            val newCols = (w / cellWidth).toInt().coerceAtLeast(1)
            val newRows = (h / cellHeight).toInt().coerceAtLeast(1)
            scheduleResize(newCols, newRows)
        }
    }

    // The soft keyboard animates open/closed over many frames, firing a flurry of onSizeChanged calls.
    // Resizing on each one sends a SIGWINCH storm to the shell (bash redraws its prompt every time),
    // leaving a stack of duplicate prompts. Debounce so a keyboard toggle applies a single final resize.
    private var pendingResize: Runnable? = null
    private fun scheduleResize(cols: Int, rows: Int) {
        // Always cancel first: a size oscillation that returns to the current grid within the window
        // must drop the stale pending resize, not leave it to fire under an already-correct view.
        pendingResize?.let {
            blinkHandler.removeCallbacks(it)
            pendingResize = null
        }
        if (cols == this.cols && rows == this.rows) return
        val r = Runnable {
            pendingResize = null
            resizeTerminal(cols, rows)
        }
        pendingResize = r
        blinkHandler.postDelayed(r, 80)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val parser = vtParser ?: return
        // The bound session may have been reaped/closed (its parser freed on the main thread) between a
        // queued invalidate and this draw — bail rather than touch a destroyed native parser.
        if (!parser.isOpen) return

        // Cache the grid in a RenderNode: cursor-blink and focus repaints replay it (one GPU op)
        // instead of re-running the whole cell loop + readRow JNI + per-cell drawText — the work that
        // made an idle, focused terminal burn ~12% of a core twice a second. Software canvases (the
        // app exposes a hardware-acceleration toggle) can't replay a RenderNode, so draw directly.
        if (canvas.isHardwareAccelerated) {
            if (gridDirty || !gridRenderNode.hasDisplayList()) {
                gridRenderNode.setPosition(0, 0, width, height)
                val recording = gridRenderNode.beginRecording()
                try {
                    drawGrid(recording, parser)
                } finally {
                    gridRenderNode.endRecording()
                }
                gridDirty = false
            }
            canvas.drawRenderNode(gridRenderNode)
        } else {
            drawGrid(canvas, parser)
        }

        drawCursor(canvas, parser)
        // Outside the grid RenderNode so cursor-blink replays stay one GPU op; the handles cost
        // two circles + two rects per frame at most.
        drawSelectionHandles(canvas)
    }

    /** Draw the two teardrop anchors (circle + square corner pointing at the text) under the
     *  selection ends. Endpoints scrolled out of the viewport are skipped; their geometry is
     *  invalidated for hit-testing. */
    private fun drawSelectionHandles(canvas: Canvas) {
        startHandleCx = Float.NaN
        startHandleCy = Float.NaN
        endHandleCx = Float.NaN
        endHandleCy = Float.NaN
        if (!isSelecting) return
        val r = 8f * resources.displayMetrics.density
        handleRadiusPx = r

        val startViewRow = selectionStartRow + scrollOffset
        if (startViewRow in 0 until rows) {
            // Bulb below-left of the anchor, clamped fully on-screen — a column-0 selection start
            // (common in a terminal) would otherwise put the whole teardrop past the left edge.
            val cx = (selectionStartCol * cellWidth - r).coerceAtLeast(r)
            val y = (startViewRow + 1) * cellHeight
            canvas.drawCircle(cx, y + r, r, handlePaint)
            canvas.drawRect(cx, y, cx + r, y + r, handlePaint)
            startHandleCx = cx
            startHandleCy = y + r
        }
        val endViewRow = selectionEndRow + scrollOffset
        if (endViewRow in 0 until rows) {
            // Bulb below-right of the anchor, clamped on-screen for a last-column selection end.
            val cx = ((selectionEndCol + 1) * cellWidth + r).coerceAtMost(width - r)
            val y = (endViewRow + 1) * cellHeight
            canvas.drawCircle(cx, y + r, r, handlePaint)
            canvas.drawRect(cx - r, y, cx, y + r, handlePaint)
            endHandleCx = cx
            endHandleCy = y + r
        }
    }

    /** Draw the cell grid (backgrounds + glyphs), batching same-attribute cells into single ops. */
    private fun drawGrid(canvas: Canvas, parser: VtParser) {
        // Draw background
        bgPaint.color = Color.BLACK
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // One JNI crossing for the whole visible grid (readScreen) instead of one per row: the
        // per-row crossings + per-cell resolution were ~50 calls per re-recorded frame.
        val stride = VtParser.CELL_STRIDE
        val parserCols = parser.cols
        val visRows = min(rows, parser.rows)
        val buf = screenBufFor(visRows, parserCols)
        parser.readScreen(-scrollOffset, visRows, buf)
        var rowBase = 0

        // Raw SGR colors of a cell (before inverse/selection): bg 0 = default (transparent over
        // the black canvas), fg defaults to white.
        fun sgrBg(base: Int): Int = when (VtParser.metaBgMode(buf[base + 3])) {
            1 -> buf[base + 2].let { if (it in 0..255) colorPalette[it] else Color.BLACK }
            2 -> buf[base + 2].let { Color.rgb((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
            else -> 0
        }

        fun sgrFg(base: Int): Int = when (VtParser.metaFgMode(buf[base + 3])) {
            1 -> buf[base + 1].let { if (it in 0..255) colorPalette[it] else Color.WHITE }
            2 -> buf[base + 1].let { Color.rgb((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
            else -> Color.WHITE
        }

        // Effective glyph color after SGR 7 inverse and SGR 2 dim. htop's selection bar, less's
        // status line and vim's visual highlight are inverse-video — without the swap they render
        // as plain text and the highlight is invisible.
        fun effFg(base: Int): Int {
            val attrs = VtParser.metaAttrs(buf[base + 3])
            var color = if ((attrs and VtParser.ATTR_INVERSE) != 0) {
                sgrBg(base).let { if (it == 0) Color.BLACK else it }
            } else {
                sgrFg(base)
            }
            if ((attrs and VtParser.ATTR_DIM) != 0) {
                color = Color.rgb(Color.red(color) * 2 / 3, Color.green(color) * 2 / 3, Color.blue(color) * 2 / 3)
            }
            return color
        }

        // Selection rows are logical, so the highlight tracks through scrollback.
        fun isSelected(logicalRow: Int, col: Int): Boolean =
            isSelecting && logicalRow in selectionStartRow..selectionEndRow &&
                (logicalRow != selectionStartRow || col >= selectionStartCol) &&
                (logicalRow != selectionEndRow || col <= selectionEndCol)

        // Effective background of a cell (0 = none): selection tint wins, then inverse swaps in
        // the fg color.
        fun cellBg(logicalRow: Int, col: Int): Int {
            if (isSelected(logicalRow, col)) return selectionColorArgb
            val base = rowBase + col * stride
            return if ((VtParser.metaAttrs(buf[base + 3]) and VtParser.ATTR_INVERSE) != 0) {
                sgrFg(base)
            } else {
                sgrBg(base)
            }
        }

        // Selected cells keep their RAW fg: the translucent tint over black plus an inverse cell's
        // swapped-in (dark) fg would render htop/less/vim highlights unreadable when selected.
        fun glyphFg(logicalRow: Int, col: Int): Int {
            val base = rowBase + col * stride
            return if (isSelected(logicalRow, col)) sgrFg(base) else effFg(base)
        }

        // Draw cells. When scrolled back, view row N shows logical row (N - scrollOffset);
        // negative logical rows come from the scrollback buffer.
        for (row in 0 until visRows) {
            val logicalRow = row - scrollOffset
            rowBase = row * parserCols * stride
            val colBound = min(cols, parserCols)
            val yTop = row * cellHeight

            // Background / selection runs: merge adjacent cells sharing an effective colour.
            var c = 0
            while (c < colBound) {
                val bg = cellBg(logicalRow, c)
                if (bg == 0) { c++; continue }
                var e = c + 1
                while (e < colBound && cellBg(logicalRow, e) == bg) e++
                runBgPaint.color = bg
                canvas.drawRect(c * cellWidth, yTop, e * cellWidth, yTop + cellHeight, runBgPaint)
                c = e
            }

            // Glyph runs: consecutive printable-ASCII cells with the same fg colour + attrs draw with
            // one drawText(char[]) call (monospace advance == cellWidth, so positions stay exact). Any
            // non-ASCII / wide cell draws on its own at its exact x, matching the prior per-cell path.
            val baseY = yTop + cellHeight * 0.8f
            val runs = runGlyphsFor(colBound)
            c = 0
            while (c < colBound) {
                val base = rowBase + c * stride
                val cp = buf[base]
                // cp == 0 is the continuation cell of a wide character: its background was drawn
                // in the bg pass (it carries the lead's colors); the lead's glyph spans it.
                if (cp == 0 || cp == ' '.code) { c++; continue }
                val attrs = VtParser.metaAttrs(buf[base + 3])
                if ((attrs and VtParser.ATTR_HIDDEN) != 0) { c++; continue }
                val fg = glyphFg(logicalRow, c)
                if (cp in 0x20..0x7E) {
                    var e = c
                    var len = 0
                    while (e < colBound) {
                        val b2 = rowBase + e * stride
                        val cp2 = buf[b2]
                        if (cp2 !in 0x20..0x7E || glyphFg(logicalRow, e) != fg || VtParser.metaAttrs(buf[b2 + 3]) != attrs) break
                        runs[len++] = cp2.toChar()
                        e++
                    }
                    applyGlyphPaint(fg, attrs)
                    canvas.drawText(runs, 0, len, c * cellWidth, baseY, textPaint)
                    drawRunDecorations(canvas, attrs, c * cellWidth, e * cellWidth, yTop)
                    c = e
                } else {
                    applyGlyphPaint(fg, attrs)
                    val x = c * cellWidth
                    // A wide character's decorations span both its cells.
                    val cellsWide = if (c + 1 < colBound && buf[rowBase + (c + 1) * stride] == 0) 2 else 1
                    val glyphLen = Character.toChars(cp, glyphBuf, 0)
                    canvas.drawText(glyphBuf, 0, glyphLen, x, baseY, textPaint)
                    drawRunDecorations(canvas, attrs, x, x + cellWidth * cellsWide, yTop)
                    c += cellsWide
                }
            }
        }
    }

    private fun applyGlyphPaint(fg: Int, attrs: Int) {
        textPaint.color = fg
        textPaint.isFakeBoldText = (attrs and VtParser.ATTR_BOLD) != 0
        textPaint.textSkewX = if ((attrs and VtParser.ATTR_ITALIC) != 0) -0.25f else 0f
    }

    private fun drawRunDecorations(canvas: Canvas, attrs: Int, xStart: Float, xEnd: Float, yTop: Float) {
        if ((attrs and VtParser.ATTR_UNDERLINE) != 0) {
            canvas.drawLine(xStart, yTop + cellHeight * 0.9f, xEnd, yTop + cellHeight * 0.9f, textPaint)
        }
        if ((attrs and VtParser.ATTR_STRIKETHROUGH) != 0) {
            canvas.drawLine(xStart, yTop + cellHeight * 0.5f, xEnd, yTop + cellHeight * 0.5f, textPaint)
        }
    }

    private fun runGlyphsFor(cols: Int): CharArray {
        if (runGlyphs.size < cols) runGlyphs = CharArray(cols)
        return runGlyphs
    }

    /** Resolve a packed cell color channel ([VtParser.readRow] encoding) to ARGB. */
    private fun resolveCellColor(value: Int, mode: Int, default: Int): Int = when (mode) {
        1 -> if (value in 0..255) colorPalette[value] else default
        2 -> Color.rgb((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
        else -> default
    }

    private fun drawCursor(canvas: Canvas, parser: VtParser) {
        // Draw cursor (only at the live bottom; hidden while scrolled back into history).
        // Focused: a solid block that blinks (with the glyph under it inverted so it stays readable).
        // Unfocused: a hollow outline, like a real terminal.
        if (parser.isCursorVisible && !isSelecting && scrollOffset == 0) {
            val cursorRow = parser.cursorRow
            val cursorCol = parser.cursorCol
            if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
                // Cursor colors derive from the cell's EFFECTIVE colors: an inverse-video cell
                // already renders fg/bg-swapped, so a hardcoded white block over it would be
                // pixel-identical to the cell and the cursor would vanish (vim visual mode).
                val buf = rowBufFor(parser)
                val filled = parser.readRow(cursorRow, buf)
                var cp = ' '.code
                var blockColor = Color.WHITE
                var glyphColor = Color.BLACK
                var blockCells = 1
                if (cursorCol < filled) {
                    val base = cursorCol * VtParser.CELL_STRIDE
                    cp = buf[base]
                    val meta = buf[base + 3]
                    val rawFg = resolveCellColor(buf[base + 1], VtParser.metaFgMode(meta), Color.WHITE)
                    val rawBg = resolveCellColor(buf[base + 2], VtParser.metaBgMode(meta), Color.BLACK)
                    val inverse = (VtParser.metaAttrs(meta) and VtParser.ATTR_INVERSE) != 0
                    blockColor = if (inverse) rawBg else rawFg
                    glyphColor = if (inverse) rawFg else rawBg
                    // On a wide character's lead cell the block covers both cells.
                    if (cursorCol + 1 < filled && buf[(cursorCol + 1) * VtParser.CELL_STRIDE] == 0) {
                        blockCells = 2
                    }
                }
                val x = cursorCol * cellWidth
                val y = cursorRow * cellHeight
                val blockW = cellWidth * blockCells
                if (!hasFocus()) {
                    // Unfocused: hollow rectangle.
                    cursorOutlinePaint.color = blockColor
                    canvas.drawRect(
                        x + 1f, y + 1f, x + blockW - 1f, y + cellHeight - 1f, cursorOutlinePaint,
                    )
                } else if (cursorBlinkOn) {
                    // Focused + blink "on": solid block, then redraw the underlying glyph in the
                    // cell's effective background colour so it remains visible.
                    cursorPaint.color = blockColor
                    canvas.drawRect(x, y, x + blockW, y + cellHeight, cursorPaint)
                    if (cp != ' '.code && cp != 0) {
                        val saved = textPaint.color
                        val savedBold = textPaint.isFakeBoldText
                        val savedSkew = textPaint.textSkewX
                        textPaint.color = glyphColor
                        textPaint.isFakeBoldText = false
                        textPaint.textSkewX = 0f
                        val glyphLen = Character.toChars(cp, glyphBuf, 0)
                        canvas.drawText(glyphBuf, 0, glyphLen, x, y + cellHeight * 0.8f, textPaint)
                        textPaint.color = saved
                        textPaint.isFakeBoldText = savedBold
                        textPaint.textSkewX = savedSkew
                    }
                }
                // Focused + blink "off": draw nothing so the cell shows through.
            }
        }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        outAttrs.inputType = EditorInfo.TYPE_NULL
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE
        
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                // A terminal's Enter is carriage return (0x0D), but IMEs commit it as a newline (LF).
                // cooked-mode shells accept LF, but raw-mode TUIs (opencode, vim, htop) only recognise
                // CR as Enter — so map every newline form to CR, matching the hardware-key path.
                text?.toString()?.let {
                    val mapped = it.replace("\r\n", "\r").replace("\n", "\r")
                    if (!sendWithPendingModifiers(mapped)) sendInput(mapped)
                }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    when {
                        event.keyCode == KeyEvent.KEYCODE_ENTER -> sendInput(byteArrayOf(0x0D))
                        event.keyCode == KeyEvent.KEYCODE_DEL -> sendInput(byteArrayOf(0x7F))
                        event.unicodeChar != 0 -> {
                            val ch = event.unicodeChar.toChar()
                            val mapped = if (ch == '\n' || ch == '\r') "\r" else ch.toString()
                            if (!sendWithPendingModifiers(mapped)) sendInput(mapped)
                        }
                    }
                }
                return true
            }
            
            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                for (i in 0 until beforeLength) {
                    sendInput(byteArrayOf(0x7F))
                }
                return true
            }
        }
    }

    // --- Cursor blink ---

    /** Begin (or restart) blinking with the cursor solid. No-op unless the view is focused. */
    private fun startBlink() {
        blinkHandler.removeCallbacks(blinkRunnable)
        cursorBlinkOn = true
        invalidate()
        if (hasFocus()) {
            blinkHandler.postDelayed(blinkRunnable, cursorBlinkIntervalMs)
        }
    }

    /** Stop blinking (used when focus is lost / the view detaches). */
    private fun stopBlink() {
        blinkHandler.removeCallbacks(blinkRunnable)
        invalidate()
    }

    /** Reset the blink phase to "on" on any activity (typing or output) so the cursor stays solid. */
    private fun resetBlink() {
        if (hasFocus()) startBlink()
    }

    override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
        if (gainFocus) startBlink() else stopBlink()
        // ?1004 focus reporting: apps that asked for it (Claude Code pauses its spinner, vim
        // ends insert mode) get FocusIn/FocusOut as the view gains/loses input focus.
        if ((currentInputModes() and VtParser.MODE_FOCUS_EVENTS) != 0) {
            sendReport(if (gainFocus) "\u001B[I" else "\u001B[O")
        }
        onFocusStateChanged?.invoke(gainFocus)
    }

    /**
     * Applies (and clears) the one-shot [pendingCtrl]/[pendingAlt] modifiers to typed input.
     * Returns true when the transformed bytes were sent; false when the caller should send [text]
     * unmodified. The modifiers are consumed either way — they are one-shot, like Termux's.
     */
    private fun sendWithPendingModifiers(text: String): Boolean {
        if (!pendingCtrl && !pendingAlt) return false
        val ctrl = pendingCtrl
        val alt = pendingAlt
        pendingCtrl = false
        pendingAlt = false
        onPendingModifiersConsumed?.invoke()
        if (text.length != 1) return false
        val ch = text[0]
        val ctrlByte: Byte? = if (ctrl) ctrlByteFor(ch) else null
        if (ctrl && ctrlByte == null && !alt) return false
        val body = ctrlByte?.let { byteArrayOf(it) } ?: text.toByteArray(Charsets.UTF_8)
        sendInput(if (alt) byteArrayOf(0x1B) + body else body)
        return true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (hasFocus()) startBlink()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        blinkHandler.removeCallbacks(blinkRunnable)
        pendingResize?.let { blinkHandler.removeCallbacks(it) }
        pendingResize = null
        // Stop rendering but DO NOT close the parser/PTY — the session keeps running in the background
        // (e.g. the terminal panel was hidden). The session is only torn down via closeSession().
        unbind()
    }
}

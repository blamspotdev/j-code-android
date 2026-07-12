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

    // Selection state
    private var isSelecting = false
    // Set by the "Select text" menu action so the next touch-drag begins a selection.
    private var selectionArmed = false

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

    // One-shot sticky modifiers armed by the extra-keys row (Termux-style): applied to the NEXT
    // typed character, then cleared. CTRL turns a letter into its control byte; ALT prefixes ESC.
    var pendingCtrl = false
    var pendingAlt = false
    // Notified after an armed modifier is consumed (or discarded) by typed input, so the row can
    // un-highlight its CTRL/ALT chips.
    var onPendingModifiersConsumed: (() -> Unit)? = null
    private var selectionStartRow = 0
    private var selectionStartCol = 0
    private var selectionEndRow = 0
    private var selectionEndCol = 0

    // Scrollback view state
    private var scrollOffset = 0        // lines scrolled up from the live bottom (0 = follow output)
    private var scrollAccumY = 0f       // sub-cell scroll accumulator for smooth panning
    private var lastScrollbackSize = 0  // keeps the scrolled-up view stable as history grows

    // Gesture detector: long-press selection, vertical pan to scroll history, tap to focus.
    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            // Request the action menu. "Select text" arms drag-selection for the next touch; while
            // armed or already selecting, a long-press shouldn't re-open the menu.
            if (isSelecting || selectionArmed) return
            onContextMenu?.invoke(e.x, e.y)
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (isSelecting || cellHeight <= 0f) return false
            // Dragging down (distanceY < 0) scrolls back into history; up returns toward the bottom.
            scrollAccumY -= distanceY
            val lines = (scrollAccumY / cellHeight).toInt()
            if (lines != 0) {
                scrollAccumY -= lines * cellHeight
                setScrollOffset(scrollOffset + lines)
            }
            return true
        }

        override fun onSingleTapUp(e: MotionEvent): Boolean {
            // Defer to onSingleTapConfirmed (opens a link/path) or onDoubleTap (shows the keyboard).
            return false
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // A confirmed single tap opens a link/path under the finger (never the keyboard).
            handleTokenTap(e.x, e.y)
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
    // Reused per background/selection run so a row of same-colored cells is one drawRect.
    private val runBgPaint = Paint().apply { style = Paint.Style.FILL }

    // 256-color palette (standard xterm colors)
    private val colorPalette = IntArray(256).apply {
        // Standard colors (0-7)
        this[0] = Color.BLACK
        this[1] = Color.RED
        this[2] = Color.GREEN
        this[3] = Color.YELLOW
        this[4] = Color.BLUE
        this[5] = Color.MAGENTA
        this[6] = Color.CYAN
        this[7] = Color.WHITE
        
        // Bright colors (8-15)
        this[8] = Color.rgb(128, 128, 128)
        this[9] = Color.rgb(255, 0, 0)
        this[10] = Color.rgb(0, 255, 0)
        this[11] = Color.rgb(255, 255, 0)
        this[12] = Color.rgb(0, 0, 255)
        this[13] = Color.rgb(255, 0, 255)
        this[14] = Color.rgb(0, 255, 255)
        this[15] = Color.WHITE
        
        // 216 color cube (16-231)
        for (i in 0 until 216) {
            val r = (i / 36) % 6
            val g = (i / 6) % 6
            val b = i % 6
            this[16 + i] = Color.rgb(r * 51, g * 51, b * 51)
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
        
        // Handle touch events for selection, history scrolling, and keyboard.
        setOnTouchListener { v, event ->
            val gestureHandled = gestureDetector.onTouchEvent(event)
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Claim the gesture: a touch starting in the terminal belongs to it (scroll / text
                    // selection), so the nav drawer's swipe-to-open can't steal a drag and pop the drawer.
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    // After "Select text" from the menu, the next touch-drag selects.
                    if (selectionArmed) startSelection(event.x, event.y)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isSelecting) {
                        updateSelectionEnd(event.x, event.y)
                        true
                    } else {
                        gestureHandled
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (isSelecting) {
                        copySelectionToClipboard()
                        isSelecting = false
                        selectionArmed = false
                        invalidateGrid()
                        true
                    } else {
                        selectionArmed = false
                        gestureHandled
                    }
                }
                else -> gestureHandled
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

    private fun startSelection(x: Float, y: Float) {
        val row = (y / cellHeight).toInt().coerceIn(0, rows - 1)
        val col = (x / cellWidth).toInt().coerceIn(0, cols - 1)
        isSelecting = true
        selectionStartRow = row
        selectionStartCol = col
        selectionEndRow = row
        selectionEndCol = col
        invalidateGrid()
    }

    private fun updateSelectionEnd(x: Float, y: Float) {
        val row = (y / cellHeight).toInt().coerceIn(0, rows - 1)
        val col = (x / cellWidth).toInt().coerceIn(0, cols - 1)
        selectionEndRow = row
        selectionEndCol = col
        invalidateGrid()
    }

    private fun copySelectionToClipboard() {
        val text = getSelectedText()
        if (text.isNotBlank()) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Terminal Selection", text))
        }
    }

    /** Actions for the host-rendered long-press menu (see [onContextMenu]). */
    fun contextArmSelection() {
        selectionArmed = true
        toast("Drag to select text")
    }

    fun contextSelectAll() = selectAllAndCopy()

    fun contextPaste() = pasteFromClipboard()

    fun contextClear() = clearScreen()

    /** Paste the clipboard into the terminal. An image is saved into the project and its (shell-quoted)
     *  path is pasted — a raw terminal can't render an image, and CLIs accept an image file path.
     *  Otherwise the clipboard text is written to the PTY as keyboard input. */
    private fun pasteFromClipboard() {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = clipboard?.primaryClip?.takeIf { it.itemCount > 0 } ?: return
        val item = clip.getItemAt(0)
        val uri = item.uri
        if (uri != null) {
            val type = runCatching { context.contentResolver.getType(uri) }.getOrNull()
            val isImage = type?.startsWith("image/") == true || clip.description?.hasMimeType("image/*") == true
            if (isImage) {
                val guestPath = onPasteImage?.invoke(uri)
                if (guestPath != null) sendInput(shellQuote(guestPath) + " ") else toast("Couldn't paste image")
                return
            }
        }
        val text = item.coerceToText(context)?.toString()
        if (!text.isNullOrEmpty()) sendInput(text)
    }

    /** Single-quote a path for the shell so any spaces or specials in it are treated literally. */
    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /** Select the whole visible screen and copy it to the clipboard. */
    private fun selectAllAndCopy() {
        val parser = vtParser ?: return
        if (!parser.isOpen) return
        selectionStartRow = 0
        selectionStartCol = 0
        selectionEndRow = (rows - 1).coerceAtLeast(0)
        selectionEndCol = (cols - 1).coerceAtLeast(0)
        copySelectionToClipboard()
        toast("Copied")
    }

    /** Clear the screen via Ctrl-L (the shell/readline redraws a fresh prompt). */
    private fun clearScreen() {
        sendInput(byteArrayOf(0x0C))
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    /** Extract the contiguous non-space token under a tap and hand it to [onTapToken] (URL/path open). */
    private fun handleTokenTap(x: Float, y: Float) {
        val parser = vtParser ?: return
        if (!parser.isOpen || cellWidth <= 0f || cellHeight <= 0f) return
        val viewRow = (y / cellHeight).toInt()
        val col = (x / cellWidth).toInt()
        if (viewRow < 0 || col < 0) return
        val logicalRow = viewRow - scrollOffset
        val maxCol = min(cols, parser.cols)
        if (col >= maxCol) return
        val buf = rowBufFor(parser)
        val filled = parser.readRow(logicalRow, buf)
        val sb = StringBuilder(maxCol)
        for (c in 0 until min(maxCol, filled)) {
            val cp = buf[c * VtParser.CELL_STRIDE]
            sb.append(if (cp == 0 || cp == ' '.code) ' ' else cp.toChar())
        }
        val line = sb.toString()
        if (col >= line.length || line[col] == ' ') return
        var start = col
        while (start > 0 && line[start - 1] != ' ') start--
        var end = col
        while (end < line.length && line[end] != ' ') end++
        val token = line.substring(start, end).trim()
        if (token.isNotBlank()) onTapToken?.invoke(token)
    }

    private fun getSelectedText(): String {
        val parser = vtParser ?: return ""
        
        val startRow = min(selectionStartRow, selectionEndRow)
        val endRow = max(selectionStartRow, selectionEndRow)
        val startCol = if (selectionStartRow <= selectionEndRow) selectionStartCol else selectionEndCol
        val endCol = if (selectionStartRow <= selectionEndRow) selectionEndCol else selectionStartCol
        
        val sb = StringBuilder()
        val buf = rowBufFor(parser)

        for (row in startRow..endRow) {
            val filled = parser.readRow(row, buf)
            val rowStartCol = if (row == startRow) startCol else 0
            val rowEndCol = if (row == endRow) endCol else cols - 1

            for (col in rowStartCol..rowEndCol.coerceAtMost(filled - 1)) {
                val cp = buf[col * VtParser.CELL_STRIDE]
                if (cp != 0) {
                    sb.appendCodePoint(cp)
                }
            }
            if (row < endRow) {
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
        lastScrollbackSize = session.parser.scrollbackSize
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
                        val sb = session.parser.scrollbackSize
                        if (scrollOffset > 0 && sb > lastScrollbackSize) {
                            scrollOffset = (scrollOffset + (sb - lastScrollbackSize)).coerceAtMost(sb)
                        }
                        lastScrollbackSize = sb
                        invalidateGrid()
                        resetBlink()
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

    /** Jump back to the live bottom of the terminal (following new output). */
    fun scrollToBottom() {
        if (scrollOffset != 0) {
            scrollOffset = 0
            scrollAccumY = 0f
            invalidateGrid()
        }
    }

    /**
     * Send a key event to the terminal.
     * Supports TUI apps (vim, htop, nano, mc) with special key combinations.
     */
    fun sendKey(keyCode: Int, event: KeyEvent?): Boolean {
        val isCtrl = event?.isCtrlPressed == true
        val isAlt = event?.isAltPressed == true

        val bytes = when {
            // Ctrl+C → SIGINT (interrupt)
            isCtrl && keyCode == KeyEvent.KEYCODE_C -> byteArrayOf(0x03)
            // Ctrl+Z → SIGTSTP (suspend)
            isCtrl && keyCode == KeyEvent.KEYCODE_Z -> byteArrayOf(0x1A)
            // Ctrl+D → EOF
            isCtrl && keyCode == KeyEvent.KEYCODE_D -> byteArrayOf(0x04)
            // Ctrl+L → Clear screen (VT sequence)
            isCtrl && keyCode == KeyEvent.KEYCODE_L -> "\u001B[H\u001B[2J".toByteArray()
            // Ctrl+U → Clear line
            isCtrl && keyCode == KeyEvent.KEYCODE_U -> byteArrayOf(0x15)
            // Ctrl+W → Delete word
            isCtrl && keyCode == KeyEvent.KEYCODE_W -> byteArrayOf(0x17)
            // Ctrl+A → Home
            isCtrl && keyCode == KeyEvent.KEYCODE_A -> byteArrayOf(0x01)
            // Ctrl+E → End
            isCtrl && keyCode == KeyEvent.KEYCODE_E -> byteArrayOf(0x05)
            // Alt+arrow keys (TUI navigation in vim, mc, etc.)
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_UP -> "\u001B\u001B[A".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B\u001B[B".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B\u001B[C".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B\u001B[D".toByteArray()
            // Alt+F1-F12 (function keys with Alt modifier)
            isAlt && keyCode == KeyEvent.KEYCODE_F1 -> "\u001B\u001BOP".toByteArray()
            isAlt && keyCode == KeyEvent.KEYCODE_F2 -> "\u001B\u001BOQ".toByteArray()
            // Standard keys
            keyCode == KeyEvent.KEYCODE_ENTER -> byteArrayOf(0x0D)
            keyCode == KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
            keyCode == KeyEvent.KEYCODE_DPAD_UP -> "\u001B[A".toByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> "\u001B[B".toByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> "\u001B[C".toByteArray()
            keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> "\u001B[D".toByteArray()
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
            keyCode == KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H".toByteArray()
            keyCode == KeyEvent.KEYCODE_MOVE_END -> "\u001B[F".toByteArray()
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
        if (!event.isCtrlPressed && !event.isAltPressed) {
            val uch = event.unicodeChar
            if (uch != 0) {
                val text = uch.toChar().toString()
                if (!sendWithPendingModifiers(text)) sendInput(text)
                return true
            }
        }
        if (sendKey(keyCode, event)) return true
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

        // Snap to the live bottom: after a resize (e.g. the keyboard opening/closing) the scrollback
        // size changed under us, so any prior scroll offset would point at the wrong content.
        scrollOffset = 0
        scrollAccumY = 0f
        lastScrollbackSize = vtParser?.scrollbackSize ?: 0

        invalidateGrid()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // The cached grid node is sized to the old bounds; force a re-record even when the cell
        // grid (cols/rows) is unchanged, or the replay would clip to the previous size.
        gridDirty = true
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
        if (cols == this.cols && rows == this.rows) return
        pendingResize?.let { blinkHandler.removeCallbacks(it) }
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
    }

    /** Draw the cell grid (backgrounds + glyphs), batching same-attribute cells into single ops. */
    private fun drawGrid(canvas: Canvas, parser: VtParser) {
        // Draw background
        bgPaint.color = Color.BLACK
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // Calculate selection bounds
        val selStartRow = min(selectionStartRow, selectionEndRow)
        val selEndRow = max(selectionStartRow, selectionEndRow)
        val selStartCol = if (selectionStartRow <= selectionEndRow) selectionStartCol else selectionEndCol
        val selEndCol = if (selectionStartRow <= selectionEndRow) selectionEndCol else selectionStartCol
        val selectionActive = scrollOffset == 0 && isSelecting

        val buf = rowBufFor(parser)
        val stride = VtParser.CELL_STRIDE

        // Effective background of a cell (0 = none): selection tint wins, else the SGR background.
        fun cellBg(row: Int, col: Int): Int {
            if (selectionActive && row in selStartRow..selEndRow &&
                (row != selStartRow || col >= selStartCol) && (row != selEndRow || col <= selEndCol)
            ) {
                return selectionColorArgb
            }
            val base = col * stride
            return when (VtParser.metaBgMode(buf[base + 3])) {
                1 -> buf[base + 2].let { if (it in 0..255) colorPalette[it] else Color.BLACK }
                2 -> buf[base + 2].let { Color.rgb((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
                else -> 0
            }
        }

        fun cellFg(base: Int): Int = when (VtParser.metaFgMode(buf[base + 3])) {
            1 -> buf[base + 1].let { if (it in 0..255) colorPalette[it] else Color.WHITE }
            2 -> buf[base + 1].let { Color.rgb((it shr 16) and 0xFF, (it shr 8) and 0xFF, it and 0xFF) }
            else -> Color.WHITE
        }

        // Draw cells. When scrolled back, view row N shows logical row (N - scrollOffset);
        // negative logical rows come from the scrollback buffer.
        for (row in 0 until min(rows, parser.rows)) {
            val logicalRow = row - scrollOffset
            val filled = parser.readRow(logicalRow, buf)
            val colBound = min(min(cols, filled), parser.cols)
            val yTop = row * cellHeight

            // Background / selection runs: merge adjacent cells sharing an effective colour.
            var c = 0
            while (c < colBound) {
                val bg = cellBg(row, c)
                if (bg == 0) { c++; continue }
                var e = c + 1
                while (e < colBound && cellBg(row, e) == bg) e++
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
                val base = c * stride
                val cp = buf[base]
                if (cp == 0 || cp == ' '.code) { c++; continue }
                val fg = cellFg(base)
                val attrs = VtParser.metaAttrs(buf[base + 3])
                if (cp in 0x20..0x7E) {
                    var e = c
                    var len = 0
                    while (e < colBound) {
                        val b2 = e * stride
                        val cp2 = buf[b2]
                        if (cp2 !in 0x20..0x7E || cellFg(b2) != fg || VtParser.metaAttrs(buf[b2 + 3]) != attrs) break
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
                    val glyphLen = Character.toChars(cp, glyphBuf, 0)
                    canvas.drawText(glyphBuf, 0, glyphLen, x, baseY, textPaint)
                    drawRunDecorations(canvas, attrs, x, x + cellWidth, yTop)
                    c++
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

    private fun drawCursor(canvas: Canvas, parser: VtParser) {
        // Draw cursor (only at the live bottom; hidden while scrolled back into history).
        // Focused: a solid block that blinks (with the glyph under it inverted so it stays readable).
        // Unfocused: a hollow outline, like a real terminal.
        if (parser.isCursorVisible && !isSelecting && scrollOffset == 0) {
            val cursorRow = parser.cursorRow
            val cursorCol = parser.cursorCol
            if (cursorRow in 0 until rows && cursorCol in 0 until cols) {
                val x = cursorCol * cellWidth
                val y = cursorRow * cellHeight
                if (!hasFocus()) {
                    // Unfocused: hollow rectangle.
                    canvas.drawRect(
                        x + 1f, y + 1f, x + cellWidth - 1f, y + cellHeight - 1f, cursorOutlinePaint,
                    )
                } else if (cursorBlinkOn) {
                    // Focused + blink "on": solid block, then redraw the underlying glyph in the
                    // background colour so a character beneath the cursor remains visible.
                    canvas.drawRect(x, y, x + cellWidth, y + cellHeight, cursorPaint)
                    val cp = parser.getCellCodePoint(cursorRow, cursorCol)
                    if (cp != ' '.code && cp != 0) {
                        val saved = textPaint.color
                        val savedBold = textPaint.isFakeBoldText
                        val savedSkew = textPaint.textSkewX
                        textPaint.color = Color.BLACK
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
        val ctrlByte: Byte? = if (ctrl) {
            val c = ch.uppercaseChar()
            when {
                c in '@'..'_' -> (c.code and 0x1F).toByte()
                ch == ' ' -> 0
                ch == '/' -> 0x1F
                else -> null
            }
        } else null
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

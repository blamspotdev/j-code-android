package dev.jcode.vdevice

import android.content.Context
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.FrameLayout

/**
 * The device's keyboard: when it appears, what field it is typing into, and where it sits.
 *
 * The keys themselves belong to [KeyboardApp], which is an ordinary guest. This is the part a phone
 * calls the input-method *manager* — the half that watches for a field taking the focus, asks that
 * field for an `InputConnection`, and puts the keyboard on the screen over the app.
 *
 * ### Why the container has to do the watching
 *
 * A guest's views live in a windowless [android.view.SurfaceControlViewHost] hierarchy, and the
 * platform's IME machinery starts at a *window* gaining focus. There is no window here to gain any,
 * so `InputMethodManager.showSoftInput` from inside a guest returns false before it reaches a
 * binder: the view is not "served", because nothing ever served it. That is what left the device
 * borrowing the phone's keyboard, which is JCode's chrome — invisible to `screencap`, absent from
 * `uiautomator dump`, and unreachable by `input tap`.
 *
 * What the platform is really watching for is public and observable from here: a view that answers
 * [View.onCheckIsTextEditor] taking the focus, and [View.onCreateInputConnection] to talk to it.
 * `TextView` builds that connection itself, with no IME session involved — so the container asks the
 * same two questions the framework would and gets the same object a real keyboard is handed.
 *
 * ### What falls out of the keyboard being a child of the guest's container
 *
 * | | Because |
 * |---|---|
 * | `screencap` shows it | [EmbeddedGuest.capture] draws the container |
 * | `uiautomator dump` lists every key | [EmbeddedGuest.dump] walks it, and the keys are real views |
 * | `input tap` presses a key | Taps arrive through [EmbeddedGuest.touch], which dispatches into it |
 *
 * The same property [VirtualStatusBar] has, for the same reason: what an agent screenshots is where
 * its taps land.
 */
internal class VirtualKeyboard(
    private val host: Context,
    private val container: FrameLayout,
    /** Called when the keyboard has appeared or gone, so the guest's window can be re-divided. */
    private val onSpaceChanged: () -> Unit,
    /** A key the keyboard could not express through an `InputConnection` — see [KeyboardApp.MSG_KEY]. */
    private val onKey: (Int) -> Unit,
) {

    private val messages = Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            KeyboardApp.MSG_HIDE -> {
                dismiss()
                true
            }

            KeyboardApp.MSG_KEY -> {
                onKey(message.arg1)
                true
            }

            else -> false
        }
    }

    private var app: KeyboardApp? = null

    /** The field being typed into, and the connection it handed over. */
    private var target: View? = null
    private var connection: InputConnection? = null
    private var editor: EditorInfo? = null

    /**
     * The field whose keyboard the person put away with the hide key.
     *
     * Held so that it stays away: the field keeps the focus, so without this the very next refresh
     * would decide a focused text field means an open keyboard and put it straight back. Tapping
     * that field again is what brings it back, which is what a phone does.
     */
    private var dismissed: View? = null

    /** Where the last press landed, so "tapped the field again" can be told from "still focused". */
    private var pressedAt: FloatArray? = null

    var isShowing = false
        private set

    /** How much of the device's screen the keyboard is taking, measured rather than guessed. */
    var height = 0
        private set

    // --- what the container asks -----------------------------------------------------------------

    /**
     * Re-reads which field has the focus and opens or closes the keyboard to match.
     *
     * [roots] is the container and every child window the guest has open, each with the frame it
     * sits at — the same list [EmbeddedGuest.dump] walks, because "what is on the screen" has one
     * answer and this is a question about it.
     */
    fun refresh(roots: List<Pair<View, Rect>>) {
        val found = focusedEditor(roots)
        if (found == null) {
            finish()
            return
        }
        val (field, frame) = found
        if (field === target && isShowing) return
        // Still the field the person put the keyboard away on: it takes another press *on that
        // field* to bring it back, which is what a phone does. Anything else — a different field
        // taking the focus — reopens it.
        if (field === dismissed && !pressedInside(field, frame)) return
        dismissed = null
        start(field)
    }

    /** Told about every press so that tapping a field the keyboard was dismissed on reopens it. */
    fun press(x: Float, y: Float) {
        pressedAt = floatArrayOf(x, y)
    }

    /** The toolbar's keyboard button and `adb shell ime show`: open it on whatever has the focus. */
    fun show(roots: List<Pair<View, Rect>>): Boolean {
        dismissed = null
        refresh(roots)
        return isShowing
    }

    /** `adb shell ime hide`, and the device's Back — the field keeps its focus, as on a phone. */
    fun dismiss() {
        dismissed = target
        finish()
    }

    /**
     * True while [x], [y] is over the keyboard, which is what decides who gets a touch.
     *
     * Falls back to the measured height for the frame between the keyboard being put on the screen
     * and the layout pass that gives it real bounds — a window a fast finger, and every
     * `input tap`, can land inside.
     */
    fun contains(x: Float, y: Float): Boolean {
        val view = app?.view?.takeIf { isShowing } ?: return false
        if (view.height > 0) {
            return x >= view.left && x < view.right && y >= view.top && y < view.bottom
        }
        return height > 0 && y >= container.height - height
    }

    fun view(): View? = app?.view?.takeIf { isShowing }

    /**
     * Types [text] into the focused field, for `adb shell input text`.
     *
     * Through the keyboard's own connection rather than as key events, which is the whole
     * improvement: `KeyCharacterMap` has no key for `é` and drops it, and no key at all for an emoji.
     * False when there is nothing focused to type into, so the caller can fall back.
     */
    fun type(text: String): Boolean {
        val connection = connection ?: return false
        return runCatching { connection.commitText(text, 1) }.getOrDefault(false)
    }

    /** What `adb shell ime status` reports: enough to tell a closed keyboard from a missing one. */
    fun status(): String = buildString {
        append("keyboard: ").append(if (app == null) "not loaded" else KeyboardApp.PACKAGE).append('\n')
        append("showing: ").append(isShowing).append('\n')
        append("height: ").append(height).append("px\n")
        val field = target
        if (field == null) {
            append("field: none focused\n")
            return@buildString
        }
        append("field: ").append(field.javaClass.name)
        val id = runCatching { field.resources.getResourceName(field.id) }.getOrNull()
        if (id != null) append(" (").append(id).append(')')
        append('\n')
        editor?.let {
            append("inputType: 0x").append(Integer.toHexString(it.inputType)).append('\n')
            append("imeOptions: 0x").append(Integer.toHexString(it.imeOptions)).append('\n')
        }
    }

    /** Re-adds the keyboard over an activity that has just gone in below it. */
    fun raise() {
        val view = app?.view?.takeIf { isShowing } ?: return
        attach(view)
    }

    fun release() {
        finish()
        // Cleared explicitly: this is a strong reference to one of the guest's own views, and the
        // guest is going away.
        dismissed = null
        app = null
    }

    // --- deciding ---------------------------------------------------------------------------------

    /**
     * The focused text editor, searched from the topmost window down.
     *
     * `findFocus` answers per view root, so a dialog over an activity is asked first — its field is
     * the one a person is looking at. The keyboard's own views are never in the running: they are
     * built non-focusable precisely so that the field they are typing into keeps the focus, without
     * which its connection would stop being the live one.
     */
    private fun focusedEditor(roots: List<Pair<View, Rect>>): Pair<View, Rect>? =
        roots.asReversed().firstNotNullOfOrNull { (root, frame) ->
            root.findFocus()
                ?.takeIf { it.isEnabled && it.onCheckIsTextEditor() }
                ?.let { it to frame }
        }

    private fun pressedInside(field: View, frame: Rect): Boolean {
        val at = pressedAt ?: return false
        val on = IntArray(2)
        field.getLocationInWindow(on)
        val left = on[0] + frame.left
        val top = on[1] + frame.top
        return at[0] >= left && at[0] < left + field.width && at[1] >= top && at[1] < top + field.height
    }

    private fun start(field: View) {
        val app = app ?: KeyboardApp.load(host, messages)?.also { app = it } ?: return
        val info = EditorInfo()
        // A view can answer onCheckIsTextEditor and still refuse a connection — a disabled or
        // read-only TextView does exactly that — and opening a keyboard onto nothing is worse than
        // not opening one.
        val connection = runCatching { field.onCreateInputConnection(info) }
            .onFailure { Log.w(TAG, "${field.javaClass.name} threw asking for its input connection", it) }
            .getOrNull() ?: return
        finishComposing()
        target = field
        this.connection = connection
        editor = info
        app.startInput(connection, info)
        attach(app.view)
        height = measure(app.view)
        isShowing = true
        onSpaceChanged()
        Log.i(TAG, "keyboard open on ${field.javaClass.name} inputType=0x${Integer.toHexString(info.inputType)}")
    }

    private fun finish() {
        if (!isShowing) return
        app?.finishInput()
        finishComposing()
        target = null
        connection = null
        editor = null
        pressedAt = null
        app?.view?.let { view -> (view.parent as? ViewGroup)?.removeView(view) }
        isShowing = false
        height = 0
        onSpaceChanged()
    }

    /**
     * Leaves the field's text as it stands.
     *
     * A connection abandoned mid-composition leaves the underline and the half-finished word behind
     * it in the app's own `Editable`, where nothing will ever come back to resolve them.
     */
    private fun finishComposing() {
        connection?.let { open -> runCatching { open.finishComposingText() } }
    }

    private fun attach(view: View) {
        if (view.parent === container) {
            // Already here, but a new activity's decor was added after it — bring it back to the
            // front, or the app is drawn over the keyboard and takes its touches with it.
            container.removeView(view)
        } else {
            (view.parent as? ViewGroup)?.removeView(view)
        }
        container.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
    }

    /**
     * How tall the keyboard is, asked before it has been laid out.
     *
     * The guest's window has to be re-divided in the same pass that puts the keyboard on the screen
     * — otherwise the app lays out for the full height once, under the keyboard, and only corrects
     * itself on the frame after. So the view is measured here rather than read back later.
     */
    private fun measure(view: View): Int {
        val width = container.width.coerceAtLeast(1)
        val available = container.height.coerceAtLeast(1)
        view.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST),
        )
        return view.measuredHeight.coerceIn(0, available)
    }
}

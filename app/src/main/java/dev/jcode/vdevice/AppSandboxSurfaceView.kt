package dev.jcode.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.os.IBinder
import android.os.SystemClock
import android.text.InputType
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import android.view.SurfaceView
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager

/**
 * The tab's window onto the guest.
 *
 * A SurfaceView, never a TextureView: the guest's views are composited by SurfaceFlinger from a
 * `SurfaceControl` the `:guest` process owns, and only a SurfaceView can adopt one
 * ([SurfaceView.setChildSurfacePackage]). It also has to be hardware accelerated, which J Code's
 * window is not by default — the page checks that before ever creating this.
 *
 * Input is forwarded by hand. The embedded hierarchy is registered with no host input token, so the
 * system delivers it nothing; the events that arrive here are already in this view's coordinates,
 * which are the guest's, so they can be relayed unchanged.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
internal class AppSandboxSurfaceView(
    context: Context,
    private val session: AppSandboxSession,
    private val onSized: (Int, Int) -> Unit,
) : SurfaceView(context) {

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        // A raw listener, not a Compose gesture: the guest expects real pointer ids and pressures,
        // and Compose's pointer input reports neither.
        setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                // Otherwise the navigation drawer's swipe-to-open steals any drag starting here.
                parent?.requestDisallowInterceptTouchEvent(true)
                requestFocus()
            }
            session.touch(event)
            true
        }
    }

    fun adopt(surface: SurfaceControlViewHost.SurfacePackage) {
        setChildSurfacePackage(surface)
    }

    /**
     * The token the window manager parents the guest's input channel to.
     *
     * Non-SDK, and there is no public equivalent before `getInputTransferToken` in API 35. Without
     * it the guest cannot be embedded at all, so a null here is what turns the tab into its
     * full-screen fallback.
     */
    fun hostToken(): IBinder? = runCatching {
        SurfaceView::class.java.getDeclaredMethod("getHostToken")
            .apply { isAccessible = true }
            .invoke(this) as? IBinder
    }.getOrNull()

    fun showKeyboard() {
        requestFocus()
        inputMethodManager()?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        inputMethodManager()?.hideSoftInputFromWindow(windowToken, 0)
    }

    override fun onSizeChanged(width: Int, height: Int, oldWidth: Int, oldHeight: Int) {
        super.onSizeChanged(width, height, oldWidth, oldHeight)
        if (width > 0 && height > 0) onSized(width, height)
    }

    override fun onCheckIsTextEditor(): Boolean = true

    /**
     * The guest cannot raise a keyboard of its own — its fields live in a hierarchy with no window
     * for the IME to bind to — so this view holds the connection and the container replays what is
     * typed as key events.
     */
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password keeps autocorrect and suggestions off, which would otherwise rewrite text
        // on its way to an app that has its own idea of what the field contains.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                text?.toString()?.takeIf { it.isNotEmpty() }?.let { session.text(it) }
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { press(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength) { press(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                session.key(event)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forward(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forward(keyCode, event) || super.onKeyUp(keyCode, event)

    /** BACK deliberately stays with J Code — the toolbar sends the guest its own Back. */
    private fun forward(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        session.key(event)
        return true
    }

    private fun press(keyCode: Int) {
        val now = SystemClock.uptimeMillis()
        session.key(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        session.key(KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0))
    }

    private fun inputMethodManager(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
}

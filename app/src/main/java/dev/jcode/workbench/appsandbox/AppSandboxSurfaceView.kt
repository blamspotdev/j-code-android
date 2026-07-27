package dev.jcode.workbench.appsandbox

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import dev.jcode.display.Protocol

/**
 * The sandbox's video output and input surface.
 *
 * A SurfaceView, never a TextureView: a TextureView draws nothing when the window is not hardware
 * accelerated, and JCode ships with `hardwareAccelerated="false"` in the manifest (MainActivity turns
 * it on from the user's preference), so that is a state this really reaches.
 */
@SuppressLint("ClickableViewAccessibility", "ViewConstructor")
internal class AppSandboxSurfaceView(context: Context) : SurfaceView(context), SurfaceHolder.Callback {

    var session: AppSandboxSession? = null
        set(value) {
            // Only on a real change: this is written from an AndroidView update block, and rebuilding
            // the decoder on every recomposition would restart the stream constantly.
            if (field === value) return
            field = value
            if (holder.surface.isValid) value?.attachSurface(holder.surface)
        }

    /** Stream dimensions, for mapping view coordinates onto the guest display. */
    var videoSize: Size = Size(1, 1)

    init {
        holder.addCallback(this)
        isFocusable = true
        isFocusableInTouchMode = true
        // A raw listener, not a Compose gesture: the guest needs real pointer ids and pressures, and
        // Compose's pointer input reports neither.
        setOnTouchListener { view, event -> onPointer(view, event) }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        session?.attachSurface(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        session?.detachSurface()
    }

    fun showKeyboard() {
        requestFocus()
        inputMethodManager()?.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }

    fun hideKeyboard() {
        inputMethodManager()?.hideSoftInputFromWindow(windowToken, 0)
    }

    /**
     * Copies what is actually on this surface. `screencap -d <virtualDisplayId>` writes a 0-byte file
     * for the server's display on this device, so the pixels have to come from our own side.
     */
    fun screenshot(onResult: (Bitmap?) -> Unit) {
        if (width <= 0 || height <= 0 || !holder.surface.isValid) {
            onResult(null)
            return
        }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        runCatching {
            PixelCopy.request(this, bitmap, { result ->
                onResult(if (result == PixelCopy.SUCCESS) bitmap else null)
            }, Handler(Looper.getMainLooper()))
        }.onFailure { onResult(null) }
    }

    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password keeps autocorrect and suggestions off, which would otherwise rewrite text
        // on its way to an app that has its own idea of what the field contains.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        outAttrs.imeOptions = EditorInfo.IME_ACTION_NONE or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_FULLSCREEN
        return object : BaseInputConnection(this, false) {
            override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
                val value = text?.toString().orEmpty()
                if (value.isNotEmpty()) session?.sendText(value)
                return true
            }

            override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
                repeat(beforeLength) { session?.sendKeyPress(KeyEvent.KEYCODE_DEL) }
                repeat(afterLength) { session?.sendKeyPress(KeyEvent.KEYCODE_FORWARD_DEL) }
                return true
            }

            override fun sendKeyEvent(event: KeyEvent): Boolean {
                session?.sendKey(event.action, event.keyCode, event.repeatCount, event.metaState)
                return true
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(keyCode, event) || super.onKeyDown(keyCode, event)

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        forwardKey(keyCode, event) || super.onKeyUp(keyCode, event)

    /** BACK deliberately stays with JCode — the toolbar sends the guest's own Back. */
    private fun forwardKey(keyCode: Int, event: KeyEvent): Boolean {
        val target = session ?: return false
        if (keyCode == KeyEvent.KEYCODE_BACK) return false
        target.sendKey(event.action, keyCode, event.repeatCount, event.metaState)
        return true
    }

    private fun onPointer(view: View, event: MotionEvent): Boolean {
        val target = session ?: return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Without this the navigation drawer's swipe-to-open steals any drag that starts here.
            view.parent?.requestDisallowInterceptTouchEvent(true)
            requestFocus()
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN ->
                sendPointer(target, Protocol.TOUCH_DOWN, event, event.actionIndex)
            MotionEvent.ACTION_MOVE ->
                repeat(event.pointerCount) { sendPointer(target, Protocol.TOUCH_MOVE, event, it) }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP ->
                sendPointer(target, Protocol.TOUCH_UP, event, event.actionIndex)
            MotionEvent.ACTION_CANCEL ->
                repeat(event.pointerCount) { sendPointer(target, Protocol.TOUCH_CANCEL, event, it) }
            else -> return false
        }
        return true
    }

    private fun sendPointer(session: AppSandboxSession, action: Int, event: MotionEvent, index: Int) {
        val pointerId = event.getPointerId(index)
        if (pointerId >= Protocol.MAX_POINTERS || width <= 0 || height <= 0) return
        val x = (event.getX(index) / width * videoSize.width).toInt().coerceIn(0, videoSize.width - 1)
        val y = (event.getY(index) / height * videoSize.height).toInt().coerceIn(0, videoSize.height - 1)
        session.sendTouch(action, pointerId, x, y, event.getPressure(index), event.buttonState)
    }

    private fun inputMethodManager(): InputMethodManager? =
        context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
}

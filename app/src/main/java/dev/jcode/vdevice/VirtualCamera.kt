package dev.jcode.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * The virtual device's camera app — the screen a guest gets when it asks for a photo.
 *
 * The device's camera used to be a declaration and nothing else: `Simulated` gave a guest the camera
 * feature and the permission, and then no frame ever arrived. An app that checks for a camera and
 * then uses one — which is most of them — got past the check and stopped at the use.
 *
 * This is the other half. `MediaStore.ACTION_IMAGE_CAPTURE` is how an app that wants *a photo*
 * rather than *a camera pipeline* asks for one, and on a phone it is answered by the camera app
 * rather than by the requester: the app never touches the camera, it receives a picture. That is
 * exactly the shape this device can answer, so it answers it.
 *
 * **Device content, not IDE chrome** — a real `View` added to [EmbeddedGuest]'s container as its
 * topmost child, the same as [VirtualFilePicker] and [VirtualStatusBar], so `screencap` shows the
 * viewfinder, `uiautomator dump` lists the shutter, and `input tap` presses it. An agent can take a
 * photo on this device.
 *
 * The viewfinder is [SimulatedCamera] redrawn on every frame, so what the shutter captures is what
 * the person was looking at, including the horizon rolling with whatever the hardware bench is
 * doing to the device's attitude.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class VirtualCamera(
    context: Context,
    private val title: String,
    /** Null is a cancel — `RESULT_CANCELED`, which every caller of the capture contract handles. */
    private val onDone: (File?) -> Unit,
) : LinearLayout(context) {

    private val viewfinder = Viewfinder(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(Color.BLACK)
        isClickable = true
        isFocusable = true

        addView(header(), LinearLayout.LayoutParams(MATCH, WRAP))
        addView(viewfinder, LinearLayout.LayoutParams(MATCH, 0, 1f))
        addView(shutterRow(), LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** Back, and the Cancel button. */
    fun cancel() = onDone(null)

    private fun header(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(10))
        addView(label(15f, FOREGROUND).apply { text = title }, LinearLayout.LayoutParams(MATCH, WRAP))
        addView(
            label(11f, MUTED).apply {
                text = "This camera is simulated — the picture is a test image, not the phone's camera."
            },
            LinearLayout.LayoutParams(MATCH, WRAP),
        )
    }

    private fun shutterRow(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(8), dp(6), dp(8), dp(10))
        addView(button("Cancel", MUTED) { cancel() })
        addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(button("Take photo", ACCENT) { capture() })
    }

    /**
     * Captures the frame and answers with the file.
     *
     * The still is taken by [SimulatedCamera.capture] rather than by grabbing the viewfinder's
     * bitmap: the viewfinder is the size of the tab and a photograph is the size of a sensor, and an
     * app that reads `EXIF`/bounds off a 400 px "photo" is being told something untrue about the
     * device.
     */
    private fun capture() {
        val file = runCatching { SimulatedCamera.capture(context) }.getOrNull()
        onDone(file)
    }

    private fun button(text: String, colour: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextColor(colour)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = text
            setOnClickListener { onClick() }
        }

    private fun label(size: Float, colour: Int): TextView = TextView(context).apply {
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * The live picture. Invalidated from its own `onDraw`, which is what a viewfinder is: there is no
     * frame to wait for, only the next evaluation of a function of the clock.
     */
    private class Viewfinder(context: Context) : View(context) {
        override fun onDraw(canvas: Canvas) {
            val sample = SimulatedHardware.sample(context)
            SimulatedCamera.draw(canvas, width, height, sample, SystemClock.elapsedRealtime())
            postInvalidateOnAnimation()
        }
    }

    internal companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val FOREGROUND = Color.argb(0xFF, 0xE6, 0xE8, 0xEF)
        private val MUTED = Color.argb(0xFF, 0x9A, 0xA0, 0xB0)
        private val ACCENT = Color.argb(0xFF, 0x8A, 0xB4, 0xF8)
    }
}

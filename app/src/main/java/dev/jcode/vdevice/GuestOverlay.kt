package dev.jcode.vdevice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** How long the full-screen controls stay up once left alone — the tab's toolbar uses the same. */
private const val IDLE_COLLAPSE_MS = 4_000L

private const val PILL_COLOR = 0xFF2B2F36.toInt()
private const val ON_PILL_COLOR = 0xFFE3E6EB.toInt()

/** The tab tints Stop with the theme's error colour; this is that colour on a dark surface. */
private const val STOP_COLOR = 0xFFFFB4AB.toInt()

/**
 * The collapsed handle, matching the device tab's own: a fifth of the width, sized like a sheet
 * grabber, at the same 55% the tab tints its. The numbers are repeated rather than shared because
 * the tab's are Compose `dp` in the IDE process and this runs in `:guest` with no theme to read.
 */
private const val HANDLE_COLOR = 0x8CE3E6EB.toInt()
private const val HANDLE_WIDTH_FRACTION = 0.2f
private const val HANDLE_THICKNESS_DP = 4
private const val HANDLE_TOUCH_HEIGHT_DP = 24

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

/**
 * The way out of a guest running full screen.
 *
 * Full screen is a real activity in its own task, so J Code draws nothing over it and the only
 * control left is the system Back gesture — which the guest is free to consume. This puts the tab's
 * controls on that window too. `Activity.addContentView` is public, adds into `android.R.id.content` on
 * top of whatever the guest put there, and — installed after `onCreate` — lands after the
 * `setContentView` that would otherwise have cleared it.
 *
 * Plain views, no Compose: this runs in `:guest`, and starting a second Compose runtime inside the
 * process under test is the kind of interference the container exists to avoid. Colours are literal
 * for the same reason — by this point the activity's resources are the guest's, so J Code's theme is
 * not reachable through it.
 */
internal object GuestOverlay {

    fun install(activity: Activity) {
        runCatching {
            activity.addContentView(
                Controls(activity),
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP or Gravity.CENTER_HORIZONTAL,
                ),
            )
        }.onFailure { Log.w(TAG, "${activity.javaClass.name} has no way back from full screen", it) }
    }
}

/** The handle and the bar it opens into, in one slot so neither moves the other. */
@SuppressLint("ViewConstructor")
private class Controls(private val activity: Activity) : FrameLayout(activity) {

    private val collapse = Runnable { show(expanded = false) }

    private val handle = Handle(activity).apply {
        layoutParams = LayoutParams(
            (resources.displayMetrics.widthPixels * HANDLE_WIDTH_FRACTION).toInt(),
            context.dp(HANDLE_TOUCH_HEIGHT_DP),
        )
        contentDescription = "Show the device controls"
        setOnClickListener { show(expanded = true) }
    }

    /**
     * What a full-screen guest can be told to do, and no two of them the same thing.
     *
     * The bar used to offer "Back" and "Close", which for a single-screen app are the same key —
     * and "Close" only ever finished the *top* activity, so a guest that had pushed a screen was
     * left running under it. These three are the device tab's, in the tab's order and with the same
     * destructive one last: move inside the app, start it over, or take it off the device.
     */
    private val bar = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutParams = LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )
        background = pillBackground(context)
        addView(action("Back") {
            @Suppress("DEPRECATION")
            activity.onBackPressed()
        })
        addView(action("Restart") { GuestRuntime.restartGuest(activity) })
        // Leaves the whole task, however many screens the guest pushed onto it, and lands back in
        // J Code rather than on the phone's launcher.
        addView(action("Stop", STOP_COLOR) { GuestRuntime.leaveGuest(activity) })
    }

    init {
        val margin = context.dp(4)
        setPadding(margin, margin, margin, margin)
        addView(handle)
        addView(bar)
        show(expanded = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(collapse)
        super.onDetachedFromWindow()
    }

    private fun show(expanded: Boolean) {
        handle.visibility = if (expanded) GONE else VISIBLE
        bar.visibility = if (expanded) VISIBLE else GONE
        removeCallbacks(collapse)
        if (expanded) postDelayed(collapse, IDLE_COLLAPSE_MS)
    }

    private fun action(
        label: String,
        color: Int = ON_PILL_COLOR,
        onClick: () -> Unit,
    ): TextView = TextView(activity).apply {
        text = label
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        minHeight = context.dp(44)
        gravity = Gravity.CENTER
        setPadding(context.dp(16), 0, context.dp(16), 0)
        isClickable = true
        setOnClickListener {
            show(expanded = true)
            onClick()
        }
    }
}

private fun pillBackground(context: Context) = GradientDrawable().apply {
    cornerRadius = context.dp(16).toFloat()
    setColor(PILL_COLOR)
}

/**
 * The collapsed controls: a grabber line, not a button — the same one the device tab shows, drawn
 * rather than inflated.
 *
 * It sits over whatever the guest is drawing, so it stays deliberately small: the touch target is
 * the view, and the line it shows is a few pixels in the middle of it.
 */
private class Handle(context: Context) : View(context) {

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HANDLE_COLOR }
    private val bar = RectF()

    override fun onDraw(canvas: Canvas) {
        val thickness = context.dp(HANDLE_THICKNESS_DP).toFloat()
        val top = (height - thickness) / 2f
        bar.set(0f, top, width.toFloat(), top + thickness)
        canvas.drawRoundRect(bar, thickness / 2f, thickness / 2f, fill)
    }
}

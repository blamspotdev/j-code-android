package dev.jcode.vdevice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
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

private fun Context.dp(value: Int): Int =
    (value * resources.displayMetrics.density).toInt().coerceAtLeast(1)

/**
 * The way out of a guest running full screen.
 *
 * Full screen is a real activity in its own task, so J Code draws nothing over it and the only
 * control left is the system Back gesture — which the guest is free to consume. This puts the tab's
 * pill on that window too. `Activity.addContentView` is public, adds into `android.R.id.content` on
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

/** The pill and the bar it opens into, in one slot so neither moves the other. */
@SuppressLint("ViewConstructor")
private class Controls(private val activity: Activity) : FrameLayout(activity) {

    private val collapse = Runnable { show(expanded = false) }

    private val pill = Chevron(activity).apply {
        val side = context.dp(44)
        layoutParams = LayoutParams(side, side)
        background = pillBackground(context)
        setOnClickListener { show(expanded = true) }
    }

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
        addView(action("Close") { activity.finish() })
    }

    init {
        val margin = context.dp(4)
        setPadding(margin, margin, margin, margin)
        addView(pill)
        addView(bar)
        show(expanded = true)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(collapse)
        super.onDetachedFromWindow()
    }

    private fun show(expanded: Boolean) {
        pill.visibility = if (expanded) GONE else VISIBLE
        bar.visibility = if (expanded) VISIBLE else GONE
        removeCallbacks(collapse)
        if (expanded) postDelayed(collapse, IDLE_COLLAPSE_MS)
    }

    private fun action(label: String, onClick: () -> Unit): TextView = TextView(activity).apply {
        text = label
        setTextColor(ON_PILL_COLOR)
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

/** The same chevron the workbench's restore pill wears, drawn rather than inflated. */
private class Chevron(context: Context) : View(context) {

    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ON_PILL_COLOR
        style = Paint.Style.STROKE
        strokeWidth = context.dp(2).toFloat()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        val arm = width * 0.16f
        val centreX = width / 2f
        val centreY = height / 2f
        path.reset()
        path.moveTo(centreX - arm, centreY - arm / 2)
        path.lineTo(centreX, centreY + arm / 2)
        path.lineTo(centreX + arm, centreY - arm / 2)
        canvas.drawPath(path, stroke)
    }
}

package dev.jcode.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/**
 * The virtual device's status bar and notification shade.
 *
 * **No clock and no battery, on purpose.** Those belong to the phone, and the phone's own status bar
 * is right above this one — a second copy would be either a lie or a duplicate. What the device has
 * that the phone's bar cannot show is the state of the app *inside* it, so that is all this carries:
 * what is running, and what it has posted.
 *
 * ### Why it lives in the guest's hierarchy
 *
 * It is added to [EmbeddedGuest]'s container, above the guest's decor view, rather than composed
 * over the tab by the IDE. That single decision is what makes it behave like part of the device
 * rather than part of J Code:
 *
 * | | Falls out of being a child of the container |
 * |---|---|
 * | `screencap` shows it | `EmbeddedGuest.capture` draws the container |
 * | `uiautomator dump` lists it | `EmbeddedGuest.dump` walks the container, and these are real views with real text |
 * | A finger and `input tap` reach it | Both arrive through `EmbeddedGuest.touch`, which dispatches into the container |
 *
 * The same property the launcher has, for the same reason: what an agent screenshots is where its
 * taps land. The IDE's own affordances — the control bar and its pill — stay composed over the
 * surface and stay out of captures, which is what keeps the two tellable apart.
 */
@SuppressLint("ViewConstructor")
internal class VirtualStatusBar(
    context: Context,
    private val appLabel: String,
) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    private val status = TextView(context).apply {
        text = appLabel
        setTextColor(FOREGROUND)
        textSize = 11f
        isSingleLine = true
        ellipsize = android.text.TextUtils.TruncateAt.END
    }

    private val summary = TextView(context).apply {
        setTextColor(FOREGROUND)
        textSize = 11f
        isSingleLine = true
    }

    private val bar = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BAR_BACKGROUND)
        setPadding(dp(10f), 0, dp(10f), 0)
        addView(status, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(summary, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    private val shadeList = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
    }

    private val clearAll = TextView(context).apply {
        text = "Clear all"
        setTextColor(ACCENT)
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(dp(12f), dp(10f), dp(12f), dp(12f))
        setOnClickListener {
            VirtualNotifications.clear()
            collapse()
        }
    }

    private val shade = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(SHADE_BACKGROUND)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat())
        }
        visibility = GONE
        addView(shadeList, LayoutParams(MATCH, WRAP))
        addView(clearAll, LayoutParams(MATCH, WRAP))
    }

    /** The grabbable strip: the bar itself plus a little slack below, so a drag is easy to start. */
    private val grabHeight = dp(GRAB_DP)

    private var downY = 0f
    private var dragging = false

    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, dp(BAR_DP)))
            addView(shade, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        addView(column, LayoutParams(MATCH, WRAP))
        refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // Posted rather than called inline: the hook that posts a notification is on the guest's own
        // thread, and this touches views.
        VirtualNotifications.observe { post { refresh() } }
    }

    override fun onDetachedFromWindow() {
        VirtualNotifications.observe(null)
        super.onDetachedFromWindow()
    }

    /** Redraws the bar's summary and rebuilds the shade's rows from what is posted now. */
    fun refresh() {
        val posted = VirtualNotifications.list()
        status.text = appLabel
        summary.text = when {
            posted.isEmpty() -> ""
            posted.size == 1 -> "1 notification"
            else -> "${posted.size} notifications"
        }

        shadeList.removeAllViews()
        if (posted.isEmpty()) {
            shadeList.addView(row("No notifications", "", muted = true))
        } else {
            posted.forEach { shadeList.addView(row(it.title, it.text, muted = false)) }
        }
        clearAll.visibility = if (posted.isEmpty()) GONE else VISIBLE
    }

    private fun row(title: String, text: String, muted: Boolean): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
            addView(
                TextView(context).apply {
                    this.text = title
                    setTextColor(if (muted) MUTED else FOREGROUND)
                    textSize = 13f
                    isSingleLine = true
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
            if (text.isNotBlank()) {
                addView(
                    TextView(context).apply {
                        this.text = text
                        setTextColor(MUTED)
                        textSize = 12f
                        maxLines = 2
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
            }
        }

    val isOpen: Boolean get() = shade.visibility == VISIBLE

    fun collapse() {
        shade.visibility = GONE
    }

    private fun expand() {
        refresh()
        shade.visibility = VISIBLE
    }

    /**
     * A downward drag anywhere in the top strip opens the shade; an upward one closes it.
     *
     * Intercepted rather than handled on the bar itself, because the strip has to win the gesture
     * from the guest underneath it — which is drawing full-bleed under the bar and would otherwise
     * take the first touch. Only vertical movement is claimed: a horizontal swipe that happens to
     * start at the top of the screen is the guest's, and pagers live there.
     */
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                dragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                val startedInGrab = downY <= grabHeight || isOpen
                if (!startedInGrab) return false
                if (abs(dy) < dp(TOUCH_SLOP_DP)) return false
                dragging = true
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                // Claim the strip so the gesture can finish here; anything lower is the guest's.
                return downY <= grabHeight || isOpen
            }

            MotionEvent.ACTION_MOVE -> return dragging

            MotionEvent.ACTION_UP -> {
                val dy = event.y - downY
                if (dy > dp(TOUCH_SLOP_DP)) expand() else if (dy < -dp(TOUCH_SLOP_DP)) collapse()
                // A tap below an open shade closes it, the way tapping outside one does on a phone.
                else if (isOpen && event.y > grabHeight) collapse()
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                dragging = false
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /**
     * Not private: [VirtualLauncher] draws the same bar onto the device's home screen with a canvas
     * rather than views, and the two must be the same bar. One palette and one height, so the strip
     * does not change shape the moment an app starts.
     */
    internal companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        const val BAR_DP = 22f
        const val TEXT_DP = 11f
        const val GRAB_DP = 30f
        const val TOUCH_SLOP_DP = 8f

        val BAR_BACKGROUND = Color.argb(0xCC, 0x12, 0x14, 0x1A)
        val SHADE_BACKGROUND = Color.argb(0xF2, 0x1B, 0x1E, 0x27)
        val FOREGROUND = Color.argb(0xFF, 0xE6, 0xE8, 0xEF)
        val MUTED = Color.argb(0xFF, 0x9A, 0xA0, 0xB0)
        val ACCENT = Color.argb(0xFF, 0x8A, 0xB4, 0xF8)
    }
}

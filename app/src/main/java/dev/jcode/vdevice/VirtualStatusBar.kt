package dev.jcode.vdevice

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
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
 * rather than part of JCode:
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
internal class VirtualStatusBar(context: Context) : FrameLayout(context) {

    private val density = resources.displayMetrics.density
    private fun dp(value: Float) = (value * density).toInt()

    /**
     * One small icon per app that has posted something, on the left where a phone puts them.
     *
     * The app's *name* is deliberately not here. It was, and it was the wrong place for it: an app
     * already says what it is in its own app bar, and a second copy in the status bar is either a
     * duplicate of the title or — for an app whose bar says something else, which is most of them
     * once you are past the first screen — a contradiction of it.
     */
    private val icons = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
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
        addView(icons, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(summary, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** What the foreground app has asked the bar to look like — see [GuestWindow.statusBarStyleOf]. */
    private var ink = FOREGROUND
    private var inkMuted = MUTED

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
            if (VirtualNotifications.count() == 0) collapse()
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
    private var downX = 0f
    private var dragging = false

    /** Where the pane was when the drag started, and how far it can go — see [onTouchEvent]. */
    private var dragFrom = 0
    private var dragTo = 0

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

    /**
     * Dresses the bar the way the foreground app asked for — see [GuestWindow.statusBarStyleOf].
     *
     * A phone's status bar takes the colour of the app under it and switches its icons to dark when
     * that colour is light, so the same bar has to do both or it is a bar from a different device
     * sitting on top of this one.
     */
    fun apply(style: GuestWindow.StatusBarStyle) {
        bar.setBackgroundColor(style.background)
        ink = if (style.lightBackground) ON_LIGHT else FOREGROUND
        inkMuted = if (style.lightBackground) ON_LIGHT_MUTED else MUTED
        summary.setTextColor(ink)
        refresh()
    }

    /** Redraws the bar's icons and summary and rebuilds the shade's rows from what is posted now. */
    fun refresh() {
        val posted = VirtualNotifications.list()
        // One icon per app rather than one per notification: the device runs a handful of packages,
        // not a phone's hundred, so what is worth saying at a glance is *who* is asking rather than
        // how many times. The count says the rest.
        val byApp = posted.distinctBy { it.packageName }
        icons.removeAllViews()
        byApp.take(MAX_ICONS).forEach { entry ->
            iconFor(entry)?.let { drawable ->
                icons.addView(
                    ImageView(context).apply { setImageDrawable(drawable) },
                    LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply {
                        marginEnd = dp(4f)
                    },
                )
            }
        }
        summary.text = when {
            posted.isEmpty() -> ""
            posted.size == 1 -> "1 notification"
            else -> "${posted.size} notifications"
        }

        shadeList.removeAllViews()
        if (posted.isEmpty()) {
            shadeList.addView(row(null, "No notifications", "", emptyList(), dim = true))
        } else {
            posted.forEach { shadeList.addView(card(it)) }
        }
        clearAll.visibility = if (VirtualNotifications.anyClearable()) VISIBLE else GONE
    }

    /**
     * The notification's own small icon, loaded against the **guest's** resources.
     *
     * An `Icon` posted by a guest carries a resource id from the guest's table and a package name the
     * real `PackageManager` has never heard of, so loading it with JCode's context resolves either
     * nothing or — worse — whatever JCode happens to have at that id. The guest's own context is the
     * only one that can read it, and the app icon is the honest fallback when there is no small icon
     * or it will not load.
     */
    private fun iconFor(entry: VirtualNotifications.Posted): Drawable? {
        val guest = GuestLoader.forPackage(entry.packageName) ?: return null
        val guestContext = runCatching { guest.appContext }.getOrNull() ?: return null
        entry.icon?.let { icon ->
            runCatching { icon.loadDrawable(guestContext) }.getOrNull()?.let { return it }
        }
        return runCatching { guest.resources.getDrawable(guest.applicationInfo.icon, null) }
            .getOrNull()
    }

    /**
     * One notification, as something that can be thrown off the screen.
     *
     * A shade whose entries can only be cleared all at once is a list, not a shade. Horizontal is
     * the axis a phone uses and the axis nothing else here wants: [onInterceptTouchEvent] claims
     * only vertical movement, so a sideways drag reaches the card untouched while an up-or-down one
     * still opens and closes the pane over the top of it.
     *
     * An ongoing notification does not go. It follows the finger a little so the gesture is
     * answered rather than ignored, then springs back — the app is still running, and the shade is
     * not the place to argue with it.
     */
    private fun card(entry: VirtualNotifications.Posted): View {
        val view = row(iconFor(entry), entry.title, entry.text, entry.actions, dim = false)
        var startX = 0f
        var slid = false
        view.setOnTouchListener { self, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    slid = false
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    if (!slid && abs(dx) > dp(TOUCH_SLOP_DP)) slid = true
                    if (slid) {
                        // Resistance rather than refusal: an ongoing card moves a fraction of the
                        // finger, which reads as "this one is pinned" without going nowhere at all.
                        self.translationX = if (entry.ongoing) dx * PINNED_DRAG else dx
                        self.alpha = 1f - (abs(self.translationX) / width).coerceIn(0f, 0.7f)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val far = abs(self.translationX) > width * DISMISS_FRACTION
                    if (!entry.ongoing && far) {
                        VirtualNotifications.cancel(entry.packageName, entry.tag, entry.id)
                    } else {
                        self.animate().translationX(0f).alpha(1f).setDuration(SETTLE_MS).start()
                    }
                    slid
                }

                else -> false
            }
        }
        return view
    }

    private fun row(
        icon: Drawable?,
        title: String,
        text: String,
        actions: List<VirtualNotifications.Act>,
        dim: Boolean,
    ): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(14f), dp(10f), dp(14f), dp(10f))
        if (icon != null) {
            addView(
                ImageView(context).apply { setImageDrawable(icon) },
                LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply {
                    marginEnd = dp(10f)
                    topMargin = dp(2f)
                },
            )
        }
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(
                    TextView(context).apply {
                        this.text = title
                        setTextColor(if (dim) inkMuted else ink)
                        textSize = 13f
                        isSingleLine = true
                        ellipsize = android.text.TextUtils.TruncateAt.END
                    },
                )
                if (text.isNotBlank()) {
                    addView(
                        TextView(context).apply {
                            this.text = text
                            setTextColor(inkMuted)
                            textSize = 12f
                            maxLines = 2
                            ellipsize = android.text.TextUtils.TruncateAt.END
                        },
                    )
                }
                actions.filter { it.intent != null }.takeIf { it.isNotEmpty() }?.let { usable ->
                    addView(actionRow(usable), LinearLayout.LayoutParams(MATCH, WRAP))
                }
            },
            LinearLayout.LayoutParams(0, WRAP, 1f),
        )
    }

    /**
     * A notification's buttons, which are the whole point of one for a media player or a download.
     *
     * Firing is all a shade may do with a `PendingIntent`, and the token itself is real: it was
     * minted under JCode's package by [GuestActivityManagerHook], so the system honours it.
     *
     * **Where it stops, measured.** A button whose intent names one of the guest's *own* components
     * does nothing, and cannot be made to from here. `PendingIntent.send` marshals the token to the
     * real activity manager rather than calling anything this process can stand in front of — traced
     * across a tap, a wrapped `IIntentSender` sees `asBinder` and never `send` — and the intent
     * inside it cannot be recovered either, because `PendingIntent.mTarget` is **blocked** at
     * `targetSdk` 33 (`NoSuchFieldException: No field mTarget`). So the component goes out to a
     * system that has never heard of the package, resolves to nothing, and reports no error.
     *
     * A button aimed anywhere the real system can reach works normally. What is lost is an app's
     * buttons on its own screens, which for a media player is its transport controls — the app's own
     * UI still has them. A cancelled intent is the app having moved on, not an error worth showing.
     */
    private fun actionRow(actions: List<VirtualNotifications.Act>): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6f), 0, 0)
            actions.forEach { action ->
                addView(
                    TextView(context).apply {
                        text = action.title.ifBlank { "Action" }.uppercase()
                        setTextColor(ACCENT)
                        textSize = 12f
                        isSingleLine = true
                        setPadding(0, dp(4f), dp(18f), dp(4f))
                        setOnClickListener {
                            runCatching { action.intent?.send() }
                                .onFailure { Log.i(TAG, "notification action went nowhere", it) }
                            collapse()
                        }
                    },
                    LinearLayout.LayoutParams(WRAP, WRAP),
                )
            }
        }

    val isOpen: Boolean get() = shade.visibility == VISIBLE && shadeHeight() > 0

    fun collapse() {
        settle(0)
    }

    private fun expand() {
        refresh()
        settle(fullShadeHeight())
    }

    /**
     * How tall the shade wants to be, measured rather than remembered.
     *
     * Its content changes under it — a notification arrives, a card is thrown away — so a height
     * cached at open time would be the height of a different shade by the time the finger lifts.
     */
    private fun fullShadeHeight(): Int {
        val width = width.takeIf { it > 0 } ?: return 0
        shade.measure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
        )
        return shade.measuredHeight
    }

    private fun shadeHeight(): Int = shade.layoutParams?.height?.takeIf { it >= 0 } ?: 0

    /** Notes where the pane is and how far it may go, so the drag has fixed ends to work between. */
    private fun beginDrag() {
        dragging = true
        dragFrom = shadeHeight()
        if (dragFrom == 0) refresh()
        dragTo = fullShadeHeight()
    }

    /** Reveals exactly [height] of the shade, which is what makes the pane follow a finger. */
    private fun setShadeHeight(height: Int) {
        shade.visibility = if (height <= 0) GONE else VISIBLE
        shade.layoutParams = (shade.layoutParams as LinearLayout.LayoutParams).apply {
            this.height = height.coerceAtLeast(0)
        }
    }

    /** Animates the pane the rest of the way, so releasing mid-drag lands somewhere deliberate. */
    private fun settle(to: Int) {
        val from = shadeHeight()
        if (from == to) {
            setShadeHeight(to)
            return
        }
        ValueAnimator.ofInt(from, to).apply {
            duration = SETTLE_MS
            addUpdateListener { setShadeHeight(it.animatedValue as Int) }
            start()
        }
    }

    /**
     * A downward drag anywhere in the top strip pulls the shade open behind the finger; an upward
     * one pushes it back.
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
                downX = event.x
                dragging = false
                return false
            }

            MotionEvent.ACTION_MOVE -> {
                val dy = event.y - downY
                val startedInGrab = downY <= grabHeight || isOpen
                if (!startedInGrab) return false
                if (abs(dy) < dp(TOUCH_SLOP_DP)) return false
                // Only claim a *vertical* drag. A sideways one belongs to whatever is under the
                // finger — a card being thrown away, or the guest's own pager.
                if (abs(event.x - downX) > abs(dy)) return false
                if (!dragging) beginDrag()
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = event.y
                downX = event.x
                // Claim the strip so the gesture can finish here; anything lower is the guest's.
                return downY <= grabHeight || isOpen
            }

            MotionEvent.ACTION_MOVE -> {
                // The drag has to be able to *start* here as well as in onInterceptTouchEvent: once
                // this view has claimed the gesture in ACTION_DOWN it is the target, and a target is
                // never asked to intercept its own events.
                val dy = event.y - downY
                if (!dragging) {
                    if (abs(dy) < dp(TOUCH_SLOP_DP)) return true
                    if (abs(event.x - downX) > abs(dy)) return true
                    beginDrag()
                }
                // The pane is exactly as far open as the finger has pulled it, which is the whole
                // difference between a shade and a panel that appears.
                setShadeHeight((dragFrom + dy).toInt().coerceIn(0, dragTo))
                return true
            }

            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    // Past a third of the way is a commitment; short of it the finger changed its
                    // mind, and either way the pane finishes the journey rather than jumping.
                    settle(if (shadeHeight() > dragTo / 3) dragTo else 0)
                } else if (isOpen && event.y > grabHeight) {
                    // A tap below an open shade closes it, the way tapping outside one does.
                    collapse()
                }
                dragging = false
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(if (shadeHeight() > dragTo / 3) dragTo else 0)
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
        const val ICON_DP = 14f

        /** How far across a card has to be thrown before it counts as thrown. */
        const val DISMISS_FRACTION = 0.35f

        /** What an ongoing card gives to the finger: enough to answer it, not enough to leave. */
        const val PINNED_DRAG = 0.18f

        const val SETTLE_MS = 160L

        /** Past this the row is wider than the label beside it; the count carries the rest. */
        const val MAX_ICONS = 4

        // The device's colours, not this file's — see [VirtualPalette] for why the bar, the
        // wallpaper and the prompt each having their own near-miss grey made one machine look like
        // three.
        val BAR_BACKGROUND = VirtualPalette.BAR
        val SHADE_BACKGROUND = VirtualPalette.SHADE
        val FOREGROUND = VirtualPalette.TEXT
        val MUTED = VirtualPalette.MUTED
        val ACCENT = VirtualPalette.ACCENT

        /** For when the app has tinted the bar a light colour and dark markings are what read. */
        val ON_LIGHT = Color.argb(0xFF, 0x14, 0x16, 0x1C)
        val ON_LIGHT_MUTED = Color.argb(0xFF, 0x4A, 0x4F, 0x5A)
    }
}

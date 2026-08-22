package dev.blamspot.jcode.vdevice

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
import dev.blamspot.jcode.R
import kotlin.math.abs

/**
 * The virtual device's status bar and notification shade.
 *
 * **No clock and no battery, on purpose.** Those belong to the phone, and the phone's own status bar
 * is right above this one — a second copy would be either a lie or a duplicate. What this carries is
 * what the phone's bar *cannot* say: what the app inside has posted, and what the device's own
 * radios are doing. Wi-Fi is the clearest case of the difference — the icon up here is the device's
 * Wi-Fi, which the shade can switch off while the phone stays online, and which is the whole reason
 * an app can be taken offline without disconnecting the machine being worked on.
 *
 * ### The shade
 *
 * Quick actions above notifications, as on a phone: one tile per radio the device was built with,
 * switching it on and off where the app is rather than three screens away in Settings. And it can be
 * pulled from anywhere — over a dialog, over a popup, over an app that has taken the whole screen —
 * because a shade that only works on some screens is not the device's shade. See
 * [EmbeddedGuest.deviceUiUnder] for how it wins a touch from a guest's own window, and [immersive]
 * for what is left to pull when the strip itself is not drawn.
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

    /**
     * The device's radios, on the right where a phone puts its system icons.
     *
     * Which ones are drawn is the whole point: a radio the device was built without has no icon at
     * all, and one the device has but has switched off has none either — the same two states a
     * phone shows, and the two an app is written to tell apart. What is up there is what
     * `ConnectivityManager` will answer with, so a person looking at the device can see why an app
     * thinks it is offline without opening Settings.
     */
    private val radios = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private val bar = LinearLayout(context).apply {
        id = R.id.vdevice_status_bar
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundColor(BAR_BACKGROUND)
        setPadding(dp(10f), 0, dp(10f), 0)
        addView(icons, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(summary, LinearLayout.LayoutParams(WRAP, WRAP))
        addView(Space(context), LinearLayout.LayoutParams(0, 1, 1f))
        addView(radios, LinearLayout.LayoutParams(WRAP, WRAP))
    }

    /** What the foreground app has asked the bar to look like — see [GuestWindow.statusBarStyleOf]. */
    private var ink = FOREGROUND
    private var inkMuted = MUTED

    /**
     * The shade's quick actions: one tile per radio the device *has*, switching it on and off.
     *
     * These are the device's own switches rather than a shortcut into its Settings app, and that is
     * the point of putting them here. Seeing what an app does when it goes offline is one of the
     * things this device is for, and until now it took opening Settings, finding the row and coming
     * back — by which time the app had usually decided. Two taps from anywhere, without leaving the
     * app, is the difference between testing that and reading about it.
     *
     * A radio the device was built without gets no tile, for the same reason it gets no status-bar
     * icon: the bench decides what hardware exists, and the shade only decides whether it is on.
     */
    private val quickActions = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(dp(10f), dp(12f), dp(10f), dp(4f))
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
            if (VirtualNotifications.count() == 0) collapse()
        }
    }

    private val shade = LinearLayout(context).apply {
        id = R.id.vdevice_shade
        orientation = LinearLayout.VERTICAL
        background = GradientDrawable().apply {
            setColor(SHADE_BACKGROUND)
            cornerRadii = floatArrayOf(0f, 0f, 0f, 0f, dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat(), dp(14f).toFloat())
        }
        visibility = GONE
        // Quick actions above the notifications, which is where a phone puts them and which is also
        // the order they are wanted in: the tiles are reachable after a short pull, and reading a
        // notification is what the rest of the pull is for.
        addView(quickActions, LayoutParams(MATCH, WRAP))
        addView(shadeList, LayoutParams(MATCH, WRAP))
        addView(clearAll, LayoutParams(MATCH, WRAP))
    }

    /**
     * True while the foreground app has taken the whole screen.
     *
     * The bar is not drawn then — an app that asked for the screen means it — but it is still
     * *there*, because a phone in immersive mode does not take the shade away with the strip. What
     * is left is an edge to pull from, and it is deliberately narrower than the ordinary grab: a
     * game with something in its top corner should keep as much of it as a reveal gesture can spare.
     */
    private var immersive = false

    /** The grabbable strip: the bar itself plus a little slack below, so a drag is easy to start. */
    private val grabHeight: Int get() = dp(if (immersive) IMMERSIVE_GRAB_DP else GRAB_DP)

    private var downY = 0f
    private var downX = 0f
    private var dragging = false

    /** Where the pane was when the drag started, and how far it can go — see [onTouchEvent]. */
    private var dragFrom = 0
    private var dragTo = 0

    /**
     * The strip and the shade, held at the top of a view that is **the whole screen tall**.
     *
     * The height is what makes an open shade dismissable. This view was only as tall as the two
     * things it draws, so a touch below them was never delivered to it at all — the container gave
     * it to the guest — and the one gesture everybody tries on a shade did nothing. Anything a view
     * is going to close itself on has to be something the view is given.
     *
     * It costs nothing while the shade is shut: [onTouchEvent] refuses a press below the grab strip,
     * so the container moves on to the app underneath and the app never knows this is here.
     */
    init {
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(bar, LinearLayout.LayoutParams(MATCH, dp(BAR_DP)))
            addView(shade, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        addView(column, LayoutParams(MATCH, WRAP, Gravity.TOP))
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
        immersive = style.hidden
        showStrip()
        refresh()
    }

    /**
     * Whether the strip is drawn, which is not the same question as whether it is there.
     *
     * A full-screen app gets its screen: the strip is `INVISIBLE`, so nothing is painted over the
     * app — but the view keeps its height, and height is what makes the touches arrive. Pulling the
     * shade brings the strip back with it, because a shade hanging off nothing reads as a panel that
     * appeared rather than as the device's own bar being pulled down.
     */
    private fun showStrip() {
        bar.visibility = if (immersive && !dragging && shadeHeight() == 0) INVISIBLE else VISIBLE
    }

    /**
     * True while [x], [y] is the bar's rather than the app's — see [EmbeddedGuest.deviceUiUnder],
     * which asks so that the shade can be pulled over a guest's *dialog*, whose window would
     * otherwise take every touch on the screen.
     *
     * An open shade owns everything: a tap anywhere below it closes it, the way tapping outside one
     * does on a phone.
     */
    fun ownsTouchAt(x: Float, y: Float): Boolean {
        if (visibility != VISIBLE) return false
        if (isOpen) return true
        return y >= 0 && y <= grabHeight && x >= 0 && x <= width
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

        refreshRadios()

        shadeList.removeAllViews()
        if (posted.isEmpty()) {
            shadeList.addView(row(null, "No notifications", "", emptyList(), dim = true))
        } else {
            posted.forEach { shadeList.addView(card(it)) }
        }
        clearAll.visibility = if (VirtualNotifications.anyClearable()) VISIBLE else GONE
    }

    /**
     * Rebuilds the status bar's radio icons and the shade's tiles from what the device's policy says
     * right now.
     *
     * Read rather than remembered, because this is not the only thing that writes it: the device's
     * Settings app is an ordinary guest changing the same policy file through the settings provider,
     * so a cached answer here would be a bar that disagrees with the screen the person just left.
     * [VirtualDevicePolicy] re-stats the file behind a short window, so asking often is cheap.
     */
    private fun refreshRadios() {
        radios.removeAllViews()
        quickActions.removeAllViews()
        RADIOS.forEach { radio ->
            // Absent hardware, not switched-off hardware. A device built without Wi-Fi has no icon
            // and no tile, because there is nothing there to switch.
            if (VirtualDevicePolicy.mode(context, radio.hardware) == HardwareMode.Off) return@forEach
            val on = VirtualDevicePolicy.switchedOn(context, radio.hardware)
            if (on) radios.addView(statusIcon(radio), LinearLayout.LayoutParams(dp(ICON_DP), dp(ICON_DP)).apply {
                marginStart = dp(5f)
            })
            quickActions.addView(tile(radio, on), LinearLayout.LayoutParams(0, WRAP, 1f).apply {
                marginStart = dp(3f)
                marginEnd = dp(3f)
            })
        }
    }

    /** One radio's icon in the strip, described the way a person would read it out. */
    private fun statusIcon(radio: Radio): ImageView = ImageView(context).apply {
        setImageResource(radio.icon)
        imageTintList = android.content.res.ColorStateList.valueOf(ink)
        contentDescription = radio.describe(context)
    }

    /**
     * One quick-action tile: the radio's name, lit when it is on.
     *
     * Filled rather than outlined for "on", because a row of pills that differ only in text colour
     * is a row nobody can read at a glance — and glancing is the entire job of a shade.
     */
    private fun tile(radio: Radio, on: Boolean): View = LinearLayout(context).apply {
        id = radio.id
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            setColor(if (on) VirtualPalette.ACCENT else VirtualPalette.CHIP)
            cornerRadius = dp(14f).toFloat()
        }
        setPadding(dp(6f), dp(10f), dp(6f), dp(10f))
        isClickable = true
        contentDescription = radio.describe(context)
        addView(
            ImageView(context).apply {
                setImageResource(radio.icon)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    if (on) VirtualPalette.SURFACE else VirtualPalette.TEXT,
                )
            },
            LinearLayout.LayoutParams(dp(20f), dp(20f)),
        )
        addView(
            TextView(context).apply {
                text = radio.hardware.label
                setTextColor(if (on) VirtualPalette.SURFACE else VirtualPalette.TEXT)
                textSize = 11f
                isSingleLine = true
                setPadding(0, dp(4f), 0, 0)
            },
            LinearLayout.LayoutParams(WRAP, WRAP),
        )
        setOnClickListener {
            VirtualDevicePolicy.setSwitchedOn(context, radio.hardware, !on)
            VirtualDeviceLog.append(
                context,
                'I',
                TAG,
                "quick action: ${radio.hardware.label} switched ${if (on) "off" else "on"}",
            )
            // The whole shade, not just this tile: switching Wi-Fi off takes its icon out of the
            // strip above, and a tile that updated while the bar did not would be two answers.
            refresh()
        }
    }

    /** A radio the device's status bar and shade know how to show. */
    private class Radio(val hardware: VirtualHardware, val id: Int, val icon: Int) {

        /**
         * What this radio is doing, for `uiautomator dump` and for anything reading the screen
         * aloud. Wi-Fi says which network, because "Wi-Fi is on" and "Wi-Fi is connected to
         * something" are different claims and only the second one explains an app's behaviour.
         */
        fun describe(context: Context): String {
            val on = VirtualDevicePolicy.switchedOn(context, hardware)
            if (!on) return "${hardware.label}, off"
            if (hardware != VirtualHardware.WiFi) return "${hardware.label}, on"
            val network = runCatching { VirtualRadios.connected(context) }.getOrNull()
                ?: return "${hardware.label}, on, not connected"
            return "${hardware.label}, ${network.ssid}, ${signalLabel(network.level)}"
        }
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

    /** The bottom edge of what is actually drawn — the strip plus however far the shade is pulled. */
    private fun panelBottom(): Int = dp(BAR_DP) + shadeHeight()

    /** Notes where the pane is and how far it may go, so the drag has fixed ends to work between. */
    private fun beginDrag() {
        dragging = true
        showStrip()
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
        // Here rather than at each call site: this is the one function every path to the shade
        // moving goes through — a finger, a settle animation, `collapse()` — so it is the one place
        // that cannot forget to bring the strip with it over a full-screen app.
        showStrip()
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
                } else if (isOpen && event.y > panelBottom()) {
                    // A press that was not a drag, landing past the bottom of the panel: that is
                    // somebody reaching for the app behind an open shade, and on a phone it puts the
                    // shade away. Measured against the *panel* rather than the grab strip, because
                    // the strip's edge is inside the shade — tapping a notification's own card would
                    // otherwise dismiss the thing being read.
                    collapse()
                }
                dragging = false
                showStrip()
                return true
            }

            MotionEvent.ACTION_CANCEL -> {
                if (dragging) settle(if (shadeHeight() > dragTo / 3) dragTo else 0)
                dragging = false
                showStrip()
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

        /**
         * The strip a full-screen app leaves for the shade.
         *
         * Narrower than [GRAB_DP] on purpose. Over an ordinary app the top of the screen is the
         * bar's anyway; over a full-screen one it is the *app's*, and every pixel claimed here is a
         * pixel a game's pause button cannot have. This is the same bargain a phone strikes in
         * immersive mode — the edge is reserved, and it is reserved narrowly.
         */
        const val IMMERSIVE_GRAB_DP = 16f
        const val TOUCH_SLOP_DP = 8f
        const val ICON_DP = 14f

        /** How far across a card has to be thrown before it counts as thrown. */
        const val DISMISS_FRACTION = 0.35f

        /** What an ongoing card gives to the finger: enough to answer it, not enough to leave. */
        const val PINNED_DRAG = 0.18f

        const val SETTLE_MS = 160L

        /** Past this the row is wider than the label beside it; the count carries the rest. */
        const val MAX_ICONS = 4

        /**
         * The radios the bar shows and the shade switches, in a phone's order — the one that changes
         * least often on the outside.
         *
         * Not every [VirtualHardware] entry: a camera is not something a status bar reports or a
         * shade toggles, and the ones that are, are exactly the ones the bench calls radios.
         */
        private val RADIOS = listOf(
            Radio(VirtualHardware.Bluetooth, R.id.vdevice_quick_bluetooth, R.drawable.ic_vdevice_bluetooth),
            Radio(VirtualHardware.WiFi, R.id.vdevice_quick_wifi, R.drawable.ic_vdevice_wifi),
            Radio(VirtualHardware.Cellular, R.id.vdevice_quick_cellular, R.drawable.ic_vdevice_cellular),
        )

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

package dev.blamspot.jcode.vdevice

import android.graphics.Insets
import android.view.WindowInsets

/**
 * The window insets a guest is told about: **the device's** chrome, never the phone's.
 *
 * A window that stops where the status bar starts is enough for an app that lays itself out inside
 * the window it is given, and that is what [EmbeddedGuest]'s top margin already does. It is not
 * enough for an app that asked to draw behind the bar. Edge-to-edge is not a style — it is a
 * *bargain*: the app takes the whole screen and undertakes to inset its own content by what the
 * insets say. Give it the screen and no insets and it keeps its half of a bargain nobody kept the
 * other half of, so its toolbar comes out underneath the device's clock.
 *
 * There was no honest inset to give, either. The hierarchy is windowless, so what a `ViewRootImpl`
 * dispatches into it is a statement about **JCode's** window — the phone's status bar, the phone's
 * gesture area — which is the same category of leak as a guest reading the phone's sensors. So the
 * container substitutes its own on the way in ([EmbeddedGuest]'s root overrides
 * `dispatchApplyWindowInsets`), and every guest view below it, `fitsSystemWindows` and
 * `ActionBarOverlayLayout` included, is measuring against the device it is actually on.
 *
 * ### What is reported, and what is deliberately not
 *
 * | | |
 * |---|---|
 * | An opaque status bar | **0.** The window already starts below it — an inset as well would push the app down twice |
 * | A transparent or translucent one | **the bar's height.** The app has the screen and owes its own padding |
 * | Full-screen | **0.** There is no bar |
 * | The keyboard | **0**, always — see below |
 *
 * The keyboard is not reported because the guest's window is *shortened* by it instead, which is
 * what `adjustResize` means and what every app copes with however old it is. Reporting the inset as
 * well would pad a modern app twice over — once for the space the window no longer has, and again
 * for the keyboard it is no longer under.
 *
 * There is no navigation bar, and reporting one would be a lie about the device rather than a
 * conservative default: Back is a control on JCode's own toolbar, not a strip of the device's
 * screen, so nothing down there is out of an app's reach.
 */
internal object GuestInsets {

    /**
     * @param covered pixels of the guest's window the status bar is over **now** — zero whenever the
     *   window already stops short of it
     * @param wouldCover what it would be over if the bar were shown, which differs from [covered]
     *   exactly when the window is full height: an app that has taken the screen still wants to know
     *   how much of it a bar would want back
     * @param shown whether the device's bar is on the screen at all
     *
     * The three are separate because apps ask all three, and collapsing them gets an answer wrong.
     * Deriving visibility from the inset — "covered is zero, so there is no bar" — reported the bar
     * **hidden** for the ordinary case of an app sitting below a perfectly visible one, which is a
     * lie an app is entitled to act on: it is the signal for "the user has swiped the bar away, put
     * your immersive affordance back".
     */
    fun of(covered: Int, wouldCover: Int, shown: Boolean): WindowInsets =
        WindowInsets.Builder()
            .setInsets(WindowInsets.Type.statusBars(), Insets.of(0, covered, 0, 0))
            .setInsetsIgnoringVisibility(
                WindowInsets.Type.statusBars(),
                Insets.of(0, wouldCover, 0, 0),
            )
            .setVisible(WindowInsets.Type.statusBars(), shown)
            .setInsets(WindowInsets.Type.navigationBars(), Insets.NONE)
            .setInsetsIgnoringVisibility(WindowInsets.Type.navigationBars(), Insets.NONE)
            .setVisible(WindowInsets.Type.navigationBars(), false)
            .setInsets(WindowInsets.Type.ime(), Insets.NONE)
            .setVisible(WindowInsets.Type.ime(), false)
            .build()
}

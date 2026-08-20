package dev.blamspot.jcode.vdevice

import android.graphics.Color

/**
 * The virtual device's own colours, in one place.
 *
 * The device draws itself out of two halves that cannot share code. The container draws its wallpaper,
 * its launcher, its status bar and its permission prompt; its **apps** — Settings, Camera, Files, the
 * browser, the keyboard — are separate APKs in their own class loaders, built by plain `javac`
 * without so much as a shared library. Each of those carries a `Ui` class with these same values
 * written down again, which is the one duplication in the device that cannot be removed.
 *
 * What can be removed is the container having three of its own. The status bar, the wallpaper and the
 * prompt each grew a private set of near-misses — `#12141A`, `#2B2D31`, `#151B24`, three greys nobody
 * chose to be different — so a device that is meant to read as one machine had a home screen, a bar
 * and a dialog from three. These are the values the device's apps use, so the container now agrees
 * with them rather than approximating them.
 *
 * Dark rather than theme-aware, and that is deliberate: this is the *device's* look, not JCode's. A
 * phone does not change what its wallpaper is because the laptop next to it went light.
 */
internal object VirtualPalette {

    /** The device's ground: what the screen is when nothing has been drawn on it. */
    const val BACKGROUND = 0xFF0B0F14.toInt()

    /** A card, a sheet, a dialog — one step off the ground. */
    const val SURFACE = 0xFF151B24.toInt()

    /** A raised element on a surface: a key, an icon chip, a pill. */
    const val CHIP = 0xFF1E2733.toInt()

    /** A hairline. Depth comes from tone rather than from shadows a dark theme cannot show. */
    const val OUTLINE = 0xFF232C3A.toInt()

    const val TEXT = 0xFFE8ECF4.toInt()
    const val MUTED = 0xFF97A2B6.toInt()
    const val ACCENT = 0xFF8AB4F8.toInt()

    /**
     * The status bar: translucent, because a strip over an app should let a little of the app
     * through — that is what says it is over the app rather than part of it.
     */
    val BAR = translucent(BACKGROUND, 0xCC)

    /**
     * The shade: **opaque**, and the difference from [BAR] is the difference between a strip and a
     * surface with reading on it.
     *
     * It was translucent to match, and a hair of an app showing through a 22dp strip is texture
     * while the same hair through a full pane is a second layer of words: a notification's title
     * competing with whatever button the app happens to have underneath it. Measured on the hardware
     * fixture, whose buttons are pale and full-width — "REQUEST CAMERA, MIC AND LOCATION" ghosted
     * straight through "No notifications", and the shade read as a rendering fault rather than as a
     * design. A phone's shade is opaque for the same reason.
     */
    const val SHADE = SURFACE

    /** Behind a modal: dark enough that the app reads as out of reach, light enough to still see it. */
    val SCRIM = translucent(Color.BLACK, 0xB3)

    /** The same colour at [alpha], which is how every translucent tone here is derived from a solid one. */
    fun translucent(colour: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(colour), Color.green(colour), Color.blue(colour))
}

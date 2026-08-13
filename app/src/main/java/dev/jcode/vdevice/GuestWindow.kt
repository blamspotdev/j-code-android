package dev.jcode.vdevice

import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.util.DisplayMetrics
import android.util.Log
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Makes an embedded guest a **windowed** app rather than a full-screen one that happens to be drawn
 * small.
 *
 * The tab is a window the size of a tab, and until this ran a guest inside it was told otherwise:
 * its `Resources` were built from J Code's configuration, so `screenWidthDp`, `orientation` and
 * every `-sw600dp`/`-land` resource qualifier described **the whole phone**, while the surface it
 * actually drew into was the editor pane. An app that trusts that — and a layout system is nothing
 * but an app that trusts that — lays itself out for a screen it does not have.
 *
 * Two things are needed, and they are needed together:
 *
 * 1. **A configuration that matches the surface.** [applySize] rewrites the guest's own
 *    `Configuration` and `DisplayMetrics` to the tab's real size, so measurement, resource
 *    qualifiers and anything reading `LocalConfiguration` agree with where the pixels go.
 * 2. **Permission to be that size.** [makeResizable] sets the activity's `resizeMode` and
 *    `FLAG_RESIZEABLE_FOR_SCREENS`, and drops any fixed `screenOrientation`. An activity that
 *    declares `resizeableActivity="false"` or pins itself to portrait is telling the framework it
 *    cannot cope with an arbitrary window — which in the tab is the only kind on offer, so the
 *    declaration has to go rather than be honoured.
 *
 * Neither reaches for a hidden member that is not already in [HiddenApi]'s ledger:
 * `Resources.updateConfiguration` and `ActivityInfo.screenOrientation` are public SDK, and
 * `resizeMode` is greylisted and guarded.
 */
internal object GuestWindow {

    /** `ActivityInfo.RESIZE_MODE_RESIZEABLE`, which is not in the SDK. */
    private const val RESIZE_MODE_RESIZEABLE = 2

    /**
     * Tells [guest] it is [widthPx] × [heightPx], in the units every part of the framework asks in.
     *
     * `updateConfiguration` is deprecated rather than hidden, and it is deprecated for apps changing
     * their *own* configuration behind the framework's back. Here it is the container doing to the
     * guest exactly what the framework would do to an app whose window changed size — which is what
     * has just happened.
     */
    fun applySize(guest: LoadedGuest, widthPx: Int, heightPx: Int) {
        if (widthPx <= 0 || heightPx <= 0) return
        runCatching {
            val resources = guest.resources
            val metrics = DisplayMetrics().apply {
                setTo(resources.displayMetrics)
                widthPixels = widthPx
                heightPixels = heightPx
            }
            val density = metrics.density.takeIf { it > 0f } ?: 1f
            val widthDp = (widthPx / density).roundToInt()
            val heightDp = (heightPx / density).roundToInt()

            val configuration = Configuration(resources.configuration).apply {
                screenWidthDp = widthDp
                screenHeightDp = heightDp
                smallestScreenWidthDp = min(widthDp, heightDp)
                orientation =
                    if (widthPx >= heightPx) Configuration.ORIENTATION_LANDSCAPE
                    else Configuration.ORIENTATION_PORTRAIT
                screenLayout = sizeBucket(widthDp, heightDp) or
                    (screenLayout and Configuration.SCREENLAYOUT_SIZE_MASK.inv())
            }
            @Suppress("DEPRECATION")
            resources.updateConfiguration(configuration, metrics)
            Log.i(TAG, "guest window is ${widthDp}x${heightDp}dp (${widthPx}x$heightPx px)")
        }.onFailure { Log.w(TAG, "cannot size the guest's window", it) }
    }

    /**
     * The `screenLayout` size bucket for a window this big, by the same thresholds the platform uses.
     * Getting it wrong is not cosmetic: it is what selects `layout-large`, and an app handed the
     * wrong bucket inflates a layout built for a different device.
     */
    private fun sizeBucket(widthDp: Int, heightDp: Int): Int {
        val longest = maxOf(widthDp, heightDp)
        val shortest = min(widthDp, heightDp)
        return when {
            longest >= 960 && shortest >= 720 -> Configuration.SCREENLAYOUT_SIZE_XLARGE
            longest >= 640 && shortest >= 480 -> Configuration.SCREENLAYOUT_SIZE_LARGE
            longest >= 470 -> Configuration.SCREENLAYOUT_SIZE_NORMAL
            else -> Configuration.SCREENLAYOUT_SIZE_SMALL
        }
    }

    /**
     * Strips an activity's refusal to be resized or re-oriented, for the embedded path only.
     *
     * A full-screen guest keeps its declarations — there it has a real window and the system is
     * entitled to honour them. In the tab there is exactly one window shape available, so an
     * activity that pins itself to portrait would otherwise be laid out for a screen the tab is not.
     */
    fun makeResizable(info: ActivityInfo) {
        info.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        // The public "this app copes with any screen" bit lives on ApplicationInfo, not on the
        // activity; the activity's own answer is resizeMode, which is greylisted.
        info.applicationInfo?.let { app ->
            app.flags = app.flags or ApplicationInfo.FLAG_RESIZEABLE_FOR_SCREENS
        }
        HiddenApi.field(ActivityInfo::class.java, "resizeMode")?.let { field ->
            runCatching { field.setInt(info, RESIZE_MODE_RESIZEABLE) }
                .onFailure { Log.w(TAG, "cannot mark ${info.name} resizeable", it) }
        }
    }
}

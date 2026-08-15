package dev.jcode.vdevice

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Where everything the virtual device forgets lives.
 *
 * The device's apps, their data, its internal storage, its policy, its log, its last screenshot —
 * all of it is emptied on every JCode start, by design, because a device that remembered would be
 * one where a file outlived the app that wrote it and waited to be found by whatever was installed
 * under that package name next.
 *
 * ### Why `cacheDir` and not `filesDir`
 *
 * Because that is what this data **is**. `filesDir` is for things an app would be sorry to lose;
 * `cacheDir` is for things it can rebuild, and the platform is entitled to reclaim it under storage
 * pressure or when somebody taps **Clear cache**. Every one of those is already true here — the tree
 * is deleted on the next start regardless — so keeping it in `filesDir` was claiming a durability
 * the device does not have and does not want. Moving it makes the phone's own storage screen tell
 * the truth about JCode: this is cache, it is safe to clear, and clearing it costs a session of a
 * sandbox rather than any of the user's work.
 *
 * The one thing that is **not** here is the device's external volume, which lives in the workspace
 * and is meant to survive — see [VirtualStorage].
 *
 * ### It can go away underneath us
 *
 * That is the trade, and it is handled rather than hoped about: the platform can clear a cache while
 * the app is running. Every directory here is created on demand, and [VirtualDeviceApps.apk]
 * reinstalls a built-in whose APK has vanished, so a cache cleared mid-session leaves a device that
 * is *empty* rather than one that is broken — which is the same state it is in every morning.
 *
 * ### One place that says where
 *
 * Six files used to compute `filesDir/vdevice/…` for themselves. That is not a tidiness point: it
 * meant a change of mind about where the device lives had six places to be right, and a wipe that
 * missed one would leave a device that was half its previous self.
 */
object VirtualDeviceFiles {

    private const val ROOT = "vdevice"

    /** The whole volatile tree. Everything the device owns hangs off this. */
    fun root(context: Context): File =
        File(context.applicationContext.cacheDir, ROOT).apply { if (!isDirectory) mkdirs() }

    /** One path inside it, created lazily like everything else here. */
    fun file(context: Context, relative: String): File = File(root(context), relative)

    /** A directory inside it, which the caller is about to write into. */
    fun directory(context: Context, relative: String): File =
        file(context, relative).apply { if (!isDirectory) mkdirs() }

    /**
     * Removes the tree from where it used to live, under `filesDir`.
     *
     * Nothing writes there any more, and nothing in it was ever meant to outlive a session — but a
     * device that had been run before this moved has a whole previous session sitting in the app's
     * *data*, where the phone's storage screen counts it as something the user would be sorry to
     * lose and where no amount of clearing the cache would ever reach it. Deleted once, quietly.
     */
    fun forgetLegacyLocation(context: Context) {
        val legacy = File(context.applicationContext.filesDir, ROOT)
        if (!legacy.exists()) return
        if (legacy.deleteRecursively()) {
            Log.i(TAG, "removed the virtual device's old home at $legacy; it lives in the cache now")
        }
    }

    private const val TAG = "VDEVICE"
}

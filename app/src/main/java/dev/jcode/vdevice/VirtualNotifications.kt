package dev.jcode.vdevice

import android.app.Notification
import android.os.SystemClock
import android.util.Log

/**
 * What the virtual device has been told to show in its status bar.
 *
 * A guest's notifications cannot go to the real one, and the reason is worth stating plainly: every
 * binder call a guest makes goes out under **J Code's** uid and package, so a guest that posts a
 * notification would put it in the user's own shade, attributed to J Code, and leave it there after
 * the device was emptied. The point of the virtual device is that an app can be tried without
 * touching the phone, and the notification shade is part of the phone.
 *
 * So [GuestNotificationHook] answers the guest's notification manager here instead, and the device
 * grows a status bar of its own to show the result — see [VirtualStatusBar].
 *
 * Lives in `:guest` and dies with it, which is the same lifetime the device's screen has: stopping
 * an app takes its process, and a stopped app's notifications with it.
 */
internal object VirtualNotifications {

    /** One posted notification, reduced to what a status bar and a shade actually draw. */
    internal data class Posted(
        val packageName: String,
        val id: Int,
        val tag: String?,
        val title: String,
        val text: String,
        val ongoing: Boolean,
        val postedAt: Long,
    ) {
        /** `tag`+`id` is the identity the framework cancels by, so it is the identity here too. */
        val key: String get() = "$packageName|${tag.orEmpty()}|$id"
    }

    private val posted = LinkedHashMap<String, Posted>()

    /** Bumped on every change, so a status bar can tell "nothing new" from "redraw". */
    @Volatile
    var revision: Int = 0
        private set

    private var onChanged: (() -> Unit)? = null

    /** The status bar listens while it is attached; nothing else ever does. */
    @Synchronized
    fun observe(listener: (() -> Unit)?) {
        onChanged = listener
    }

    @Synchronized
    fun list(): List<Posted> = posted.values.sortedByDescending { it.postedAt }

    @Synchronized
    fun count(): Int = posted.size

    /**
     * Records a notification the guest posted.
     *
     * The title and text are read out of `Notification.extras`, which is where every builder — the
     * framework's, AndroidX's, and the compat shims in between — leaves them. A notification with
     * neither is still worth a line in the shade, so it falls back to naming itself.
     */
    @Synchronized
    fun post(packageName: String, tag: String?, id: Int, notification: Notification?) {
        val extras = notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val entry = Posted(
            packageName = packageName,
            id = id,
            tag = tag,
            title = title.ifBlank { packageName },
            text = text,
            ongoing = notification?.flags?.and(Notification.FLAG_ONGOING_EVENT) != 0,
            postedAt = SystemClock.uptimeMillis(),
        )
        posted[entry.key] = entry
        changed()
        Log.i(TAG, "notification ${entry.key}: ${entry.title}")
    }

    @Synchronized
    fun cancel(packageName: String, tag: String?, id: Int) {
        if (posted.remove(Posted(packageName, id, tag, "", "", false, 0L).key) != null) changed()
    }

    @Synchronized
    fun cancelAll(packageName: String) {
        if (posted.keys.removeAll { it.startsWith("$packageName|") }) changed()
    }

    /** The shade's "Clear all" — the user dismissing what the device is showing. */
    @Synchronized
    fun clear() {
        if (posted.isEmpty()) return
        posted.clear()
        changed()
    }

    private fun changed() {
        revision++
        onChanged?.invoke()
    }
}

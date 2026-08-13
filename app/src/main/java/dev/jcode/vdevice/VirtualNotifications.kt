package dev.jcode.vdevice

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Icon
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
        /** The notification's own small icon, in the *guest's* resources — see [VirtualStatusBar]. */
        val icon: Icon? = null,
        val actions: List<Act> = emptyList(),
    ) {
        /** `tag`+`id` is the identity the framework cancels by, so it is the identity here too. */
        val key: String get() = "$packageName|${tag.orEmpty()}|$id"
    }

    /**
     * One of a notification's buttons.
     *
     * The `PendingIntent` is kept rather than the intent inside it, because firing one is the only
     * thing a shade is allowed to do with it — and because a guest's was minted under J Code's
     * package by [GuestActivityManagerHook], so it is a real token the system will honour.
     */
    internal data class Act(val title: String, val intent: PendingIntent?)

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
    /**
     * Records a notification the guest posted.
     *
     * [foregroundService] forces [Posted.ongoing]: a notification handed to `startForeground` *is*
     * the running service, and the platform treats it as ongoing whether or not the app also said
     * `setOngoing(true)` — most do not, because on a phone they never had to. Measured on NewPipe,
     * whose player notification arrives with no flags at all and would otherwise have been sweepable
     * out from under a playing video.
     */
    @Synchronized
    fun post(
        packageName: String,
        tag: String?,
        id: Int,
        notification: Notification?,
        foregroundService: Boolean = false,
    ) {
        val extras = notification?.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val flags = notification?.flags ?: 0
        val entry = Posted(
            packageName = packageName,
            id = id,
            tag = tag,
            title = title.ifBlank { packageName },
            text = text,
            // Both flags mean the same thing to a shade: this one is not the user's to sweep away.
            // A media player sets ONGOING while it plays, and a download sets NO_CLEAR while it runs.
            ongoing = foregroundService ||
                flags and (Notification.FLAG_ONGOING_EVENT or Notification.FLAG_NO_CLEAR) != 0,
            postedAt = SystemClock.uptimeMillis(),
            icon = runCatching { notification?.smallIcon }.getOrNull(),
            actions = notification?.actions.orEmpty().map { action ->
                Act(action.title?.toString().orEmpty(), action.actionIntent)
            },
        )
        posted[entry.key] = entry
        changed()
        Log.i(
            TAG,
            "notification ${entry.key}: ${entry.title} " +
                "ongoing=${entry.ongoing} icon=${entry.icon != null} " +
                "actions=${notification?.actions?.size ?: -1}->${entry.actions.size}",
        )
    }

    @Synchronized
    fun cancel(packageName: String, tag: String?, id: Int) {
        if (posted.remove("$packageName|${tag.orEmpty()}|$id") != null) changed()
    }

    @Synchronized
    fun cancelAll(packageName: String) {
        if (posted.keys.removeAll { it.startsWith("$packageName|") }) changed()
    }

    /** True when "Clear all" would remove anything — an all-ongoing shade has nothing to sweep. */
    @Synchronized
    fun anyClearable(): Boolean = posted.values.any { !it.ongoing }

    /**
     * The shade's "Clear all" — the user dismissing what the device is showing.
     *
     * An ongoing notification survives it, the way it does on a phone. That is not decoration: a
     * media player's notification *is* its transport controls and a download's is its progress, so
     * sweeping them away would take a running app's only handle with it while the app kept running.
     * Only the app itself takes those down, by cancelling or by stopping.
     */
    @Synchronized
    fun clear() {
        if (posted.values.removeAll { !it.ongoing }) changed()
    }

    /** Everything, ongoing included — the device being emptied rather than the user tidying up. */
    @Synchronized
    fun clearAll() {
        if (posted.isEmpty()) return
        posted.clear()
        changed()
    }

    private fun changed() {
        revision++
        onChanged?.invoke()
    }
}

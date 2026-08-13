package dev.jcode.vdevice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.util.Log

/**
 * Puts a full-screen guest's notifications on the **phone's** shade, for as long as it is full
 * screen.
 *
 * The device's own status bar is a view inside the embedded guest's container — see
 * [VirtualStatusBar] — so it exists only while the guest is in the tab. A full-screen guest has
 * taken the whole screen and left the tab behind, and with it the only surface the device had to
 * show a notification on. Posting nowhere would mean an app that behaves correctly appears not to,
 * which is the failure mode the virtual device is meant to remove.
 *
 * So while a guest is full screen its notifications are mirrored onto the host, and **taken back
 * down when it exits**. That bound is what keeps this from being the thing
 * [GuestNotificationHook] exists to prevent: notifications are still never *left* on the user's
 * phone, they are only borrowed while there is nowhere else to put them. Each carries the guest's
 * own label so it is obvious which app inside the device is talking.
 *
 * Lives in `:guest`, like everything else the container runs.
 */
internal object HostNotificationMirror {

    private const val CHANNEL = "jcode-vdevice-guest"
    private const val CHANNEL_NAME = "Virtual device apps"

    /** Host notification ids in use, so exiting full screen can take exactly these back down. */
    private val posted = LinkedHashMap<String, Int>()
    private var nextId = 7_100

    private var context: Context? = null
    private var label: String = ""

    @Volatile
    var isMirroring: Boolean = false
        private set

    /** A full-screen guest is on the screen; its notifications belong on the phone until it is not. */
    @Synchronized
    fun enable(context: Context, appLabel: String) {
        if (isMirroring) return
        this.context = context.applicationContext
        this.label = appLabel
        isMirroring = true
        ensureChannel()
        sync()
        Log.i(TAG, "mirroring $appLabel's notifications to the host while it is full screen")
    }

    /** Full screen is over: everything borrowed goes back. */
    @Synchronized
    fun disable() {
        if (!isMirroring) return
        isMirroring = false
        val manager = manager()
        GuestNotificationHook.asHost {
            posted.values.forEach { id -> runCatching { manager?.cancel(id) } }
        }
        posted.clear()
        context = null
        Log.i(TAG, "returned the host's notification shade")
    }

    /**
     * Brings the host's shade in line with what the device is showing: post what is new, and cancel
     * what the guest has withdrawn since.
     */
    @Synchronized
    fun sync() {
        if (!isMirroring) return
        val manager = manager() ?: return
        val current = VirtualNotifications.list()
        val live = current.map { it.key }.toSet()

        // Every call below has to reach the *real* notification service; without this the container's
        // own hook would catch them and put them straight back into the device they came from.
        GuestNotificationHook.asHost {
            posted.keys.toList().forEach { key ->
                if (key !in live) posted.remove(key)?.let { id -> runCatching { manager.cancel(id) } }
            }
            current.forEach { entry ->
                val id = posted.getOrPut(entry.key) { nextId++ }
                runCatching { manager.notify(id, build(entry)) }
                    .onFailure { Log.w(TAG, "cannot mirror ${entry.key}", it) }
            }
        }
    }

    /**
     * The guest's notification, rebuilt as J Code's.
     *
     * Rebuilt rather than forwarded: the original was addressed to a package the phone's notification
     * manager has never heard of, and its small icon is a resource id in the guest's table which the
     * host cannot resolve. Only the text is the guest's; everything the system has to understand is
     * J Code's.
     */
    private fun build(entry: VirtualNotifications.Posted): Notification {
        val host = context!!
        return Notification.Builder(host, CHANNEL)
            .setContentTitle(entry.title)
            .setContentText(entry.text)
            .setSubText(label.ifBlank { entry.packageName })
            .setSmallIcon(host.applicationInfo.icon)
            .setOngoing(false)
            // A guest's notification has nowhere to send anyone: its content intent belongs to a
            // package the system cannot start. Tapping it dismisses, which is the honest behaviour.
            .setAutoCancel(true)
            .build()
    }

    private fun ensureChannel() {
        val manager = manager() ?: return
        GuestNotificationHook.asHost {
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, CHANNEL_NAME, NotificationManager.IMPORTANCE_LOW),
                )
            }.onFailure { Log.w(TAG, "cannot create the mirror channel", it) }
        }
    }

    private fun manager(): NotificationManager? =
        context?.getSystemService(NotificationManager::class.java)
}

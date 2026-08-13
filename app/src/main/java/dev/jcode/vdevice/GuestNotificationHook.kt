package dev.jcode.vdevice

import android.app.Notification
import android.app.NotificationManager
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * The virtual device's notification service: keeps a guest's notifications on the device instead of
 * in the user's own shade.
 *
 * Without this a guest's `notify()` goes out over binder under **J Code's** uid and package, so it
 * lands in the phone's real notification shade, attributed to J Code, and survives the device being
 * emptied. That is the one thing the virtual device exists to avoid — an app should be triable
 * without leaving anything behind on the phone.
 *
 * `INotificationManager` is an interface, so the same [Proxy] shape as the other hooks works:
 * posting and cancelling are answered into [VirtualNotifications] and never reach the system, while
 * everything the guest merely *asks* — whether notifications are enabled, what channels exist — is
 * answered as a permissive yes so the guest goes on to post rather than giving up early.
 *
 * Calls the container does not model still go through to the real service, because most of them are
 * harmless reads and a notification manager that throws is worse than one that over-answers.
 */
internal object GuestNotificationHook {

    @Volatile
    private var installed = false

    /**
     * Set while [HostNotificationMirror] is posting J Code's own copy of a guest's notification.
     *
     * Without it the mirror's calls would be caught by this very hook and fed straight back into the
     * device they came from — a loop, and no notification on the phone. A thread local rather than a
     * flag because a guest can post from any thread while the mirror is working on the main one.
     */
    private val delivering = ThreadLocal.withInitial { false }

    /** Runs [block]'s notification calls as J Code's, past this hook. */
    fun <T> asHost(block: () -> T): T {
        delivering.set(true)
        return try {
            block()
        } finally {
            delivering.set(false)
        }
    }

    /**
     * Replaces the process-wide `INotificationManager`. False when the platform will not give it up,
     * in which case a guest's notifications go to the phone's shade as they did before.
     */
    @Synchronized
    fun install(): Boolean {
        if (installed) return true
        return try {
            val iface = HiddenApi.classOrNull("android.app.INotificationManager") ?: return false
            val field = HiddenApi.field(NotificationManager::class.java, "sService") ?: return false
            // getService() is what fills the field in; without it there is nothing yet to replace.
            HiddenApi.method(NotificationManager::class.java, "getService")?.invoke(null)
            val real = field.get(null) ?: return false
            if (Proxy.isProxyClass(real.javaClass)) return true.also { installed = true }

            val proxy = Proxy.newProxyInstance(
                GuestNotificationHook::class.java.classLoader,
                arrayOf(iface),
                Handler(real),
            )
            field.set(null, proxy)
            installed = true
            Log.i(TAG, "notification hook installed")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot install the notification hook; guest notifications go to the phone", t)
            false
        }
    }

    private class Handler(private val real: Any) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val guest = GuestRuntime.activePackage()
            if (guest != null && !delivering.get()) {
                answer(guest, method, args ?: emptyArray())?.let { answered ->
                    // The device changed; a full-screen guest has no status bar of its own, so the
                    // phone's shade stands in for it until it exits. A no-op otherwise.
                    HostNotificationMirror.sync()
                    return answered.value
                }
            }
            return try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        /**
         * Arguments are found by type rather than position: these signatures have gained and moved a
         * `userId`, an `opPkg` and a callback across releases, and the container only ever needs the
         * tag, the id and the notification itself.
         */
        private fun answer(guest: String, method: Method, args: Array<Any?>): Box? = when (method.name) {
            "enqueueNotificationWithTag", "enqueueNotificationWithTagPriority" -> {
                val id = args.filterIsInstance<Int>().firstOrNull() ?: 0
                val tag = args.filterIsInstance<String>().getOrNull(TAG_ARG)
                VirtualNotifications.post(guest, tag, id, args.firstNotNullOfOrNull { it as? Notification })
                Box(null)
            }

            "cancelNotificationWithTag" -> {
                val id = args.filterIsInstance<Int>().firstOrNull() ?: 0
                VirtualNotifications.cancel(guest, args.filterIsInstance<String>().getOrNull(TAG_ARG), id)
                Box(null)
            }

            "cancelAllNotifications" -> {
                VirtualNotifications.cancelAll(guest)
                Box(null)
            }

            // Asked before posting; a "no" here is a guest that never posts at all.
            "areNotificationsEnabled", "areNotificationsEnabledForPackage" -> Box(true)
            "getImportance", "getPackageImportance" -> Box(IMPORTANCE_DEFAULT)
            // Channels are bookkeeping the device does not keep: accepting them silently is what
            // lets an O+ guest reach its notify() call, which is the part that matters.
            "createNotificationChannels", "createNotificationChannelGroups",
            "deleteNotificationChannel", "deleteNotificationChannelGroup",
            -> Box(null)

            else -> null
        }
    }

    /**
     * The tag is the *third* string in `enqueueNotificationWithTag(pkg, opPkg, tag, …)`, and it is
     * the only one of the three the container wants — the two before it are J Code's package name,
     * which is what the guest's calls go out under.
     */
    private const val TAG_ARG = 2

    /** `NotificationManager.IMPORTANCE_DEFAULT`, answered without reaching for the real service. */
    private const val IMPORTANCE_DEFAULT = 3

    /** Distinguishes "answered with null" from "not the container's call". */
    private class Box(val value: Any?)
}

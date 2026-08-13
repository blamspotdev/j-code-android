package dev.jcode.vdevice

import android.app.Notification
import android.content.ComponentName
import android.content.Intent
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Lets a guest build a `PendingIntent`.
 *
 * `PendingIntent` is the one place a guest's *name* is checked rather than merely reported. Creating
 * one goes to `IActivityManager.getIntentSender(…, packageName, …)`, and the activity manager
 * verifies that the package belongs to the calling uid before it will mint one:
 *
 * ```
 * java.lang.SecurityException: Permission Denial: getIntentSender() from pid=26873, uid=10211,
 *     (need uid=-1) is not allowed to send as package org.zwanoo.android.speedtest
 * ```
 *
 * Everywhere else [GuestContext] answers with the guest's identity, which is the whole point of it.
 * Here that is exactly what fails, so this is the one call where the **host's** package has to go
 * out instead — the case the security notes describe as `packageName must match the calling uid`.
 *
 * The substitution costs the guest nothing it could otherwise have had. A `PendingIntent` is a token
 * the system hands to somebody else to act on the app's behalf, and a guest is not a package the
 * system can act on behalf of in the first place; what it gets back is a working token owned by
 * JCode, which is the identity every other binder call it makes already goes out under.
 *
 * Scoped to `getIntentSender` on purpose. Rewriting the package on every call would be a much
 * larger claim about what a guest is allowed to do under JCode's name, and only this one was
 * measured to need it.
 */
internal object GuestActivityManagerHook {

    @Volatile
    private var installed = false

    /** Replaces the process-wide `IActivityManager`. False leaves `PendingIntent` broken, nothing else. */
    @Synchronized
    fun install(hostPackage: String): Boolean {
        if (installed) return true
        return try {
            val manager = HiddenApi.classOrNull("android.app.ActivityManager") ?: return false
            val iface = HiddenApi.classOrNull("android.app.IActivityManager") ?: return false
            val singleton = HiddenApi.field(manager, "IActivityManagerSingleton")?.get(null) ?: return false
            val singletonClass = HiddenApi.classOrNull("android.util.Singleton") ?: return false
            // Let the singleton create the real proxy first, or its own create() would overwrite the
            // replacement the first time anything asks.
            HiddenApi.method(singletonClass, "get")?.invoke(singleton)
            val instanceField = HiddenApi.field(singletonClass, "mInstance") ?: return false
            val real = instanceField.get(singleton) ?: return false
            if (Proxy.isProxyClass(real.javaClass)) return true.also { installed = true }

            val proxy = Proxy.newProxyInstance(
                GuestActivityManagerHook::class.java.classLoader,
                arrayOf(iface),
                Handler(real, hostPackage),
            )
            instanceField.set(singleton, proxy)
            installed = true
            Log.i(TAG, "activity manager hook installed")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot install the activity manager hook; guests cannot build PendingIntents", t)
            false
        }
    }

    private class Handler(private val real: Any, private val hostPackage: String) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            if (args != null && method.name.startsWith(INTENT_SENDER)) {
                for (index in args.indices) {
                    val name = args[index] as? String ?: continue
                    if (GuestLoader.forPackage(name) != null) args[index] = hostPackage
                }
            }
            // Sending one is the other half, and the half that decides *what runs*. A PendingIntent
            // aimed at a guest's own activity is sent through here rather than through the activity
            // task manager, so it never meets the hook that redirects a guest's `startActivity` —
            // and the system then resolves the component against the phone's own copy of that
            // package, where one is installed. Measured on ES-DE: its ConfiguratorActivity opened
            // the *installed* app over the top of JCode, which is both the wrong application and
            // outside the device entirely.
            if (args != null && method.name == SEND_INTENT_SENDER) {
                for (index in args.indices) {
                    val intent = args[index] as? Intent ?: continue
                    if (deliverToGuestReceiver(intent)) return zero(method.returnType)
                    GuestRuntime.redirectForGuest(intent)?.let { args[index] = it }
                }
            }
            if (method.name == SERVICE_FOREGROUND && args != null && takeForeground(args)) return null
            // Where `Context.checkSelfPermission` ends up, and so where almost every permission
            // question a guest asks is decided — including AndroidX's, which routes through it. The
            // device answers for its own hardware and stays out of the way for everything else; see
            // GuestPermissions.
            if (method.name == CHECK_PERMISSION && args != null) {
                args.filterIsInstance<String>().firstNotNullOfOrNull { GuestPermissions.answer(it) }
                    ?.let { return it }
            }
            val result = try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            return result
        }
    }


    /**
     * Delivers a `PendingIntent` aimed at one of the guest's own receivers, in-process.
     *
     * A guest builds a notification's buttons with `PendingIntent.getBroadcast`, and the token comes
     * back owned by JCode — but the *component* inside it still names a package the real system has
     * never heard of, so firing it resolves nothing and fails silently.
     *
     * This is the half that can be caught: a broadcast that comes through the activity manager. The
     * other half cannot — see the note on [VirtualStatusBar.actionRow].
     */
    private fun deliverToGuestReceiver(intent: Intent): Boolean {
        val component = intent.component ?: return false
        val guest = GuestLoader.forPackage(component.packageName) ?: return false
        if (!guest.receivers.containsKey(component.className)) return false
        return runCatching { guest.components.sendBroadcast(guest.appContext, intent) > 0 }
            .onFailure { Log.w(TAG, "cannot deliver $component from a notification action", it) }
            .getOrDefault(false)
    }

    /**
     * What a consumed call hands back — *success*, not emptiness: `sendIntentSender` returns an int
     * the caller reads as a result code, and 0 is the one that means the send happened.
     */
    private fun zero(type: Class<*>): Any? = when (type) {
        Int::class.javaPrimitiveType -> 0
        Boolean::class.javaPrimitiveType -> true
        Long::class.javaPrimitiveType -> 0L
        else -> null
    }

    /**
     * Takes a guest's foreground-service notification onto the device instead of to the server.
     *
     * Now that a hosted service is attached with a real `IActivityManager`, `startForeground` gets
     * this far — but the server is asked to promote a service in a package it has never heard of, and
     * refuses. The device already has somewhere for a notification to go, so it goes there: the same
     * status bar and shade [GuestNotificationHook] posts into, which is where a phone would have put
     * it too.
     *
     * True when it took the call, false when this is not a guest's service and the real activity
     * manager should see it unchanged. The component and the notification are matched by type; the
     * id is the call's first `int`, which is the one part of this signature that has to be read
     * positionally.
     */
    private fun takeForeground(args: Array<Any?>): Boolean {
        val component = args.filterIsInstance<ComponentName>().firstOrNull() ?: return false
        if (GuestLoader.forPackage(component.packageName) == null) return false
        val id = args.filterIsInstance<Int>().firstOrNull() ?: 0
        when (val notification = args.filterIsInstance<Notification>().firstOrNull()) {
            null -> VirtualNotifications.cancel(component.packageName, component.className, id)
            else -> VirtualNotifications.post(
                component.packageName,
                component.className,
                id,
                notification,
                foregroundService = true,
            )
        }
        return true
    }

    /** Covers `getIntentSender` and the `WithFeature` variant later platforms added beside it. */
    private const val INTENT_SENDER = "getIntentSender"

    /** What `Service.startForeground`/`stopForeground` reach, and where a media player lives or dies. */
    private const val SERVICE_FOREGROUND = "setServiceForeground"

    /** The activity manager's own send path. Kept because some callers do come through it. */
    private const val SEND_INTENT_SENDER = "sendIntentSender"

    /** Where `Context.checkSelfPermission` lands, by way of `PermissionManager`. */
    private const val CHECK_PERMISSION = "checkPermission"
}

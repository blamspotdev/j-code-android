package dev.jcode.vdevice

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
 * J Code, which is the identity every other binder call it makes already goes out under.
 *
 * Scoped to `getIntentSender` on purpose. Rewriting the package on every call would be a much
 * larger claim about what a guest is allowed to do under J Code's name, and only this one was
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
            return try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }
    }

    /** Covers `getIntentSender` and the `WithFeature` variant later platforms added beside it. */
    private const val INTENT_SENDER = "getIntentSender"
}

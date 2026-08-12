package dev.jcode.vdevice

import android.content.ComponentName
import android.content.pm.PackageManager
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * Makes the framework's own `PackageManager` able to answer questions about a guest.
 *
 * A guest asks the package manager about *itself* far more often than it looks. `androidx.startup`
 * reads its `InitializationProvider`'s `<meta-data>` through `getProviderInfo` — and so, transitively,
 * do WorkManager, Firebase, `emoji2` and ProfileInstaller, none of which start if that call fails.
 * AppCompat looks its own activity up through `getActivityInfo`. Analytics libraries read
 * `getPackageInfo(…).versionName`. Every one of those goes out under J Code's uid to a package
 * manager that has never heard of the guest, and comes back `NameNotFoundException`.
 *
 * Measured on NewPipe before this existed:
 * ```
 * androidx.startup.StartupException: PackageManager$NameNotFoundException:
 *     ComponentInfo{org.newpipex/androidx.startup.InitializationProvider}
 *     at androidx.startup.AppInitializer.discoverAndInitialize(AppInitializer.java:208)
 * ```
 * — the provider was hosted and running, and still could not read the metadata that says what to
 * initialise.
 *
 * ### Why the binder rather than the `PackageManager`
 *
 * `PackageManager` is an abstract class with a couple of hundred abstract members, so a wrapper that
 * delegates the rest is not a thing that can be written by hand. `IPackageManager` is an *interface*,
 * which is exactly what [Proxy] needs, and it sits underneath every `ApplicationPackageManager`
 * method — so one proxy covers every entry point at once. It is the same shape as the
 * `IActivityTaskManager` hook in [GuestHooks], for the same reason.
 *
 * Only queries naming a loaded guest are answered here; everything else is passed straight through,
 * so J Code's own package manager behaves exactly as it did. And like every other hook, this one is
 * guarded end to end: a platform that puts `sPackageManager` out of reach loses guest package
 * queries and nothing else.
 */
internal object GuestPackageHook {

    @Volatile
    private var installed = false

    /**
     * Replaces the process-wide `IPackageManager` proxy. Returns false when the platform will not
     * give it up, which costs a guest its own package metadata and leaves everything else working.
     */
    @Synchronized
    fun install(hostPackageManager: PackageManager): Boolean {
        if (installed) return true
        return try {
            val activityThread = HiddenApi.classOrNull("android.app.ActivityThread") ?: return false
            val iface = HiddenApi.classOrNull("android.content.pm.IPackageManager") ?: return false
            val field = HiddenApi.field(activityThread, "sPackageManager") ?: return false
            val real = field.get(null) ?: return false
            if (Proxy.isProxyClass(real.javaClass)) return true.also { installed = true }

            val proxy = Proxy.newProxyInstance(
                GuestPackageHook::class.java.classLoader,
                arrayOf(iface),
                Handler(real),
            )
            field.set(null, proxy)
            // ApplicationPackageManager caches the interface at construction, so the singleton alone
            // is not enough — the instance every Context already hands out holds its own reference.
            HiddenApi.field(hostPackageManager.javaClass, "mPM")?.set(hostPackageManager, proxy)
            installed = true
            Log.i(TAG, "package manager hook installed")
            true
        } catch (t: Throwable) {
            Log.w(TAG, "cannot install the package manager hook; guests cannot query themselves", t)
            false
        }
    }

    /**
     * Answers the handful of queries a guest makes about itself, and forwards everything else.
     *
     * Arguments are found by *type* rather than position. These binder signatures gained a `userId`
     * and widened `flags` from `int` to `long` across releases, and a hook pinned to one arrangement
     * of them would break on the next platform for no reason worth breaking on.
     */
    private class Handler(private val real: Any) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            answer(method, args)?.let { return it.value }
            return try {
                method.invoke(real, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        /** Null when this is not a guest's question; a box — possibly of null — when it is. */
        private fun answer(method: Method, args: Array<Any?>?): Box? {
            if (args == null) return null
            val component = args.filterIsInstance<ComponentName>().firstOrNull()
            val guest = component?.let { GuestLoader.forPackage(it.packageName) }
                ?: args.filterIsInstance<String>().firstNotNullOfOrNull { GuestLoader.forPackage(it) }
                ?: return resolveProvider(method, args)

            return when (method.name) {
                "getActivityInfo" -> Box(guest.activities[component?.className])
                "getServiceInfo" -> Box(guest.services[component?.className])
                "getReceiverInfo" -> Box(guest.receivers[component?.className])
                "getProviderInfo" ->
                    Box(guest.providers.firstOrNull { it.name == component?.className })

                "getPackageInfo" -> Box(guest.packageInfo)
                "getApplicationInfo" -> Box(guest.applicationInfo)
                "getApplicationEnabledSetting" -> Box(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                "getComponentEnabledSetting" -> Box(PackageManager.COMPONENT_ENABLED_STATE_DEFAULT)
                else -> null
            }
        }

        /**
         * `resolveContentProvider` takes an authority, not a package, so it cannot be matched the
         * way the rest are — but it is how a `ContentResolver` finds a provider at all.
         */
        private fun resolveProvider(method: Method, args: Array<Any?>): Box? {
            if (method.name != "resolveContentProvider") return null
            val authority = args.filterIsInstance<String>().firstOrNull() ?: return null
            val found = GuestLoader.providerFor(authority) ?: return null
            return Box(found)
        }
    }

    /** Distinguishes "the guest's answer is null" from "not the guest's question". */
    private class Box(val value: Any?)
}

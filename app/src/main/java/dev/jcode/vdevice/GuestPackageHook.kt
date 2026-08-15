package dev.jcode.vdevice

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Process
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
 * `getPackageInfo(…).versionName`. Every one of those goes out under JCode's uid to a package
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
 * so JCode's own package manager behaves exactly as it did. And like every other hook, this one is
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
                val cause = e.targetException
                // The safety net for everything not modelled above. A guest is not in the package
                // database, so any query the container forgot lands on "Unknown package" — an
                // IllegalArgumentException, thrown across the binder, which kills whatever asked.
                // Measured: Crashlytics calling getInstallerPackageName took Firebase's whole
                // InitProvider down with it, and Speedtest lost analytics before its first frame.
                //
                // Converting exactly that failure into "nothing to report" is not a guess about
                // semantics — for a package that genuinely is not installed, nothing *is* the
                // honest answer, and it is the one a caller is written to handle.
                if (namesGuest(args) && cause.isUnknownPackage()) return emptyValue(method.returnType)
                throw cause
            }
        }

        private fun namesGuest(args: Array<Any?>?): Boolean = args != null && args.any { arg ->
            when (arg) {
                is ComponentName -> GuestLoader.forPackage(arg.packageName) != null
                is String -> GuestLoader.forPackage(arg) != null
                else -> false
            }
        }

        private fun Throwable.isUnknownPackage(): Boolean =
            this is IllegalArgumentException && message?.startsWith(UNKNOWN_PACKAGE) == true

        /** Null when this is not a guest's question; a box — possibly of null — when it is. */
        private fun answer(method: Method, args: Array<Any?>?): Box? {
            if (args == null) return null
            // What hardware the device has is a question about the *device*, so it carries no
            // package to recognise it by — and it is the question a careful app asks before it
            // reaches for a camera. See GuestPermissions.
            if (method.name == "hasSystemFeature") {
                args.filterIsInstance<String>()
                    .firstNotNullOfOrNull { GuestPermissions.feature(it) }
                    ?.let { return Box(it) }
            }
            // "Is there an app that can do this?" is also a question about the device, and it is
            // the one a careful app asks before it offers a camera or an attach button. Left to the
            // system it is answered from the *phone's* installed apps — so an app either hid a
            // button the device could have answered, or offered one that opened the user's own
            // camera over their own storage. See DeviceIntents.
            resolution(method, args)?.let { return it }
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
                // Null is what a sideloaded app sees on a real phone, and a sideloaded app is
                // exactly what a guest is. Answering it at all is the point: the real package
                // manager throws for a package it has never heard of.
                "getInstallerPackageName", "getInstallSourceInfo" -> Box(null)
                // Every binder call the guest makes already goes out under JCode's uid, so this is
                // the truthful answer rather than a flattering one.
                "getPackageUid" -> Box(Process.myUid())
                "isPackageAvailable" -> Box(true)
                "getTargetSdkVersion" -> Box(guest.applicationInfo.targetSdkVersion)
                // A permission check that reached the server would ask about the wrong package: the
                // guest is not one the server has heard of, and the uid behind it is JCode's. So
                // the device answers — from the user's own policy for the hardware it governs, and
                // with the granted the container has always given for everything else.
                "checkPermission" -> Box(
                    args.filterIsInstance<String>()
                        .firstNotNullOfOrNull { GuestPermissions.answer(it) }
                        ?: PackageManager.PERMISSION_GRANTED,
                )
                // The server refuses to change the state of a component in a package it does not
                // have — "Attempt to change component state" — and WorkManager does exactly this to
                // enable its own JobService. Accepting it is honest here: the container decides what
                // a guest's components do, so there is nothing for the system to enable.
                "setComponentEnabledSetting", "setApplicationEnabledSetting" -> Box(null)
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

        /**
         * Answers "which app handles this intent?" from the **device's** apps.
         *
         * Only intents the device has an app for are answered; everything else falls through to the
         * system, which is right — an app asking whether the device can dial a phone number should
         * be told no by something that knows, and this device has no dialler.
         *
         * `resolveIntent` answers a single [ResolveInfo]; `queryIntentActivities` answers a list,
         * and at this API level that list crosses the binder wrapped in `ParceledListSlice`. The
         * wrapper is built by reflection because it is `@hide`, and a failure to build it answers
         * null rather than throwing — the caller then gets the system's answer, which is the
         * behaviour that existed before this hook and is survivable.
         */
        private fun resolution(method: Method, args: Array<Any?>): Box? {
            val single = method.name == "resolveIntent"
            if (!single && method.name != "queryIntentActivities") return null
            val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return null
            val component = DeviceIntents.resolve(intent) ?: return null
            val guest = GuestLoader.forPackage(component.packageName) ?: return null
            val info = ResolveInfo().apply {
                activityInfo = guest.activities[component.className] ?: return null
            }
            return if (single) Box(info) else sliceOf(info)?.let { Box(it) }
        }

        /** `new ParceledListSlice<>(List)` — the shape `queryIntentActivities` returns over binder. */
        private fun sliceOf(info: ResolveInfo): Any? = runCatching {
            Class.forName("android.content.pm.ParceledListSlice")
                .getConstructor(List::class.java)
                .newInstance(listOf(info))
        }.onFailure { Log.w(TAG, "cannot answer queryIntentActivities for the device's apps", it) }
            .getOrNull()
    }

    /** Distinguishes "the guest's answer is null" from "not the guest's question". */
    private class Box(val value: Any?)

    /** How the package manager says a package is not installed, across every service that says it. */
    private const val UNKNOWN_PACKAGE = "Unknown package"
}

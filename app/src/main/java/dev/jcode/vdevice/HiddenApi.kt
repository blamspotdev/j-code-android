package dev.jcode.vdevice

import android.util.Log
import java.lang.reflect.Field
import java.lang.reflect.Method

internal const val TAG = "VDEVICE"

/**
 * Reflection helpers for the framework internals the container is built on.
 *
 * Everything the virtual device does — swapping `ActivityThread.mInstrumentation`, reading
 * `ActivityThread.mH`, building a bare `AssetManager`, replacing the `IActivityTaskManager` binder
 * proxy — lives on non-SDK ("hidden") members, so **this is coupled to `targetSdk`.**
 *
 * Verified on Android 13 with J Code's `targetSdk = 33`: the members the full-screen path touches
 * are all on the `unsupported` greylist, which carries no `maxTargetSdk`, so they are *allowed* (the
 * runtime logs a warning per access and nothing more). The following are not, and each is designed
 * around rather than bypassed:
 *
 *  - `ContextThemeWrapper.mTheme` is `max-target-p`, so it cannot be cleared — see
 *    [GuestRuntime.onLaunchActivity], which keeps it from ever being created.
 *  - `ActivityThread.startActivityNow` does not appear in its class's declared members at all, which
 *    is what a *denied* member looks like from here. The device-sandbox tab uses the public
 *    `Instrumentation.newActivity` instead.
 *  - **Every** non-SDK member of `SurfaceControlViewHost` is denied, its `SurfacePackage` included:
 *    the class carries no `@UnsupportedAppUsage` at all. [EmbeddedWindows] therefore reaches the
 *    host's view root through the container's `getParent()` and its root layer through the
 *    `SurfacePackage`'s own `Parcelable` contract, both public.
 *  - `Activity.performStart`/`performResume` are denied the same way, and so is
 *    `Activity.mActivityLifecycleCallbacks` — the list those two dispatch to, and the one AndroidX's
 *    `ReportFragment` registers on. `Application.mActivityLifecycleCallbacks` *is* greylisted, so
 *    [GuestRuntime.resumeEmbedded] dispatches that one and drives the guest's own `LifecycleRegistry`
 *    for what the other would have reached.
 *
 * There is no way to lift that: `VMRuntime.setHiddenApiExemptions` — the usual escape hatch, reached
 * by double reflection — is itself `blocked` from Android 10 on, confirmed denied on this device.
 * So if a future platform demotes any of the greylisted members above, the fix is a real one (a
 * different hook point), not a bypass.
 *
 * None of this runs in the IDE process; the container only ever loads in `:guest`.
 */
internal object HiddenApi {

    fun classOrNull(name: String): Class<*>? =
        runCatching { Class.forName(name) }.getOrElse {
            Log.w(TAG, "no class $name", it)
            null
        }

    fun field(owner: Class<*>, name: String): Field? =
        runCatching { owner.getDeclaredField(name).apply { isAccessible = true } }.getOrElse {
            Log.w(TAG, "no field ${owner.name}#$name", it)
            null
        }

    fun method(owner: Class<*>, name: String, vararg params: Class<*>): Method? =
        runCatching { owner.getDeclaredMethod(name, *params).apply { isAccessible = true } }.getOrElse {
            Log.w(TAG, "no method ${owner.name}#$name", it)
            null
        }

    /** Writes a `static final` field. ART honours `setAccessible` for these; the JVM would not. */
    fun setStaticFinal(owner: Class<*>, name: String, value: Any?): Boolean {
        val target = field(owner, name) ?: return false
        return runCatching { target.set(null, value) }
            .onFailure { Log.w(TAG, "cannot write ${owner.simpleName}.$name", it) }
            .isSuccess
    }
}

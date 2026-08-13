package dev.jcode.vdevice

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import java.lang.reflect.Modifier

/**
 * The [Instrumentation] the container installs in place of `ActivityThread.mInstrumentation`.
 *
 * Both overrides are public SDK API and sit at exactly the points the container needs: `newActivity`
 * decides which class gets instantiated, and `callActivityOnCreate` brackets the guest's `onCreate` —
 * before it the activity is attached but has run no code of its own, so its context can be replaced;
 * after it the activity has a content view to hang the full-screen controls on.
 */
internal class GuestInstrumentation(base: Instrumentation) : Instrumentation() {

    init {
        // ActivityThread configured the original instance (its `mThread` back-reference above all);
        // carry that state across so everything we do not override keeps working after the swap.
        Instrumentation::class.java.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) }
            .forEach { field ->
                runCatching {
                    field.isAccessible = true
                    field.set(this, field.get(base))
                }
            }
    }

    override fun newActivity(cl: ClassLoader, className: String, intent: Intent?): Activity =
        GuestRuntime.newActivity(intent) ?: super.newActivity(cl, className, intent)

    override fun callActivityOnCreate(activity: Activity, icicle: Bundle?) {
        GuestRuntime.bind(activity)
        super.callActivityOnCreate(activity, icicle)
        GuestRuntime.created(activity)
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
    ) {
        GuestRuntime.bind(activity)
        super.callActivityOnCreate(activity, icicle, persistentState)
        GuestRuntime.created(activity)
    }

    /** Public SDK, and the counterpart to [callActivityOnCreate]: it is where a full-screen guest's
     *  borrowed place in the host's notification shade is given back. */
    override fun callActivityOnDestroy(activity: Activity) {
        super.callActivityOnDestroy(activity)
        GuestRuntime.destroyed(activity)
    }
}

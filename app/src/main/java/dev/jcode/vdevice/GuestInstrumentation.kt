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
 * Both overrides are public SDK API and sit at exactly the two points the container needs:
 * `newActivity` decides which class gets instantiated, and `callActivityOnCreate` is the last moment
 * before the guest's own code runs — the activity is attached, so its context can be replaced, but
 * `onCreate` has not been called yet.
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
    }

    override fun callActivityOnCreate(
        activity: Activity,
        icicle: Bundle?,
        persistentState: PersistableBundle?,
    ) {
        GuestRuntime.bind(activity)
        super.callActivityOnCreate(activity, icicle, persistentState)
    }
}

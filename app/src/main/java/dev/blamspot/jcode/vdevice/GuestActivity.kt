package dev.blamspot.jcode.vdevice

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * A declared activity that exists only to be an `ActivityInfo`.
 *
 * A guest activity belongs to a package the real `PackageManager` has never heard of, so there is no
 * `ActivityInfo` to build one from — and `Instrumentation.newActivity` needs one. This is that
 * template: [GuestRuntime.embed] looks it up, hands it to `newActivity`, and answers with the guest's
 * class instead. Its theme, `windowSoftInputMode` and `configChanges` are what a guest inherits
 * before its own manifest values are applied over the top.
 *
 * **Nothing launches it.** Reaching [onCreate] means the system started it as itself, which the
 * container never asks for; finishing immediately is the only sensible answer.
 */
class GuestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.w(TAG, "${javaClass.name} was launched as itself; it is a template, not a screen")
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

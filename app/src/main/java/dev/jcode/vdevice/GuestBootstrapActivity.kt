package dev.jcode.vdevice

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * Entry point into the `:guest` process, and the reason a cold start does not flash.
 *
 * The container's hooks can only be installed from inside the guest process, but the very first
 * activity that process runs is created before they exist. This one takes that hit: it is
 * translucent and finishes immediately, so it installs the hooks, loads the guest and starts the
 * real activity without ever being seen.
 */
class GuestBootstrapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val apkPath = intent.getStringExtra(GuestRuntime.EXTRA_APK)
        runCatching {
            GuestRuntime.install(this)
            GuestRuntime.startGuest(
                from = this,
                apkPath = apkPath ?: throw VirtualDeviceException("no APK in the launch intent"),
                activityClass = intent.getStringExtra(GuestRuntime.EXTRA_ACTIVITY),
            )
        }.onFailure { Log.e(TAG, "virtual device failed to start $apkPath", it) }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}

package dev.jcode.vdevice

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * A stand-in the system can legally launch on behalf of an activity it has never heard of.
 *
 * These are declared in J Code's manifest in the `:guest` process and are never meant to run as
 * themselves: [GuestRuntime] rewrites the launch transaction and answers `newActivity` with the
 * guest's class, so the instance the system builds is the guest activity, not one of these.
 *
 * Reaching [onCreate] therefore means the hooks were not in place when the system created us — the
 * platform restarting a killed `:guest` process straight into a stub, for instance. Install them and
 * bounce the same intent through once more; the second pass produces the guest.
 */
open class GuestActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wasInstalled = GuestRuntime.isInstalled
        runCatching { GuestRuntime.install(this) }
            .onFailure { Log.e(TAG, "cannot install container hooks", it) }

        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)

        if (!wasInstalled && GuestRuntime.isInstalled) {
            startActivity(Intent(intent).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } else {
            Log.e(TAG, "${javaClass.name} was not replaced by its guest activity; giving up")
        }
    }

    internal companion object {
        private val STUBS = arrayOf(
            GuestActivity0::class.java,
            GuestActivity1::class.java,
            GuestActivity2::class.java,
            GuestActivity3::class.java,
        )

        fun stub(slot: Int): Class<out GuestActivity> = STUBS[slot % STUBS.size]
    }
}

class GuestActivity0 : GuestActivity()

class GuestActivity1 : GuestActivity()

class GuestActivity2 : GuestActivity()

class GuestActivity3 : GuestActivity()

package dev.blamspot.jcode.core.distro.adb

import android.content.Context

/**
 * Process-wide [AdbBridge].
 *
 * The bridge MUST be a singleton: it binds a loopback ServerSocket, so a second instance would bind a
 * SECOND relay port and mint a second device serial — one the guest's `ANDROID_SERIAL` and the
 * persisted serial would both disagree with.
 */
object AdbBridgeLocator {
    @Volatile
    private var adbBridge: AdbBridge? = null

    fun adbBridge(context: Context): AdbBridge {
        return adbBridge ?: synchronized(this) {
            adbBridge ?: AdbBridge(context.applicationContext).also {
                adbBridge = it
            }
        }
    }
}

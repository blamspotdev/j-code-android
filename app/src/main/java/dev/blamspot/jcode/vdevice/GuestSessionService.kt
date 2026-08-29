package dev.blamspot.jcode.vdevice

import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.util.Log
import dev.blamspot.jcode.ext.NativeExtensionLoader
import dev.blamspot.jcode.ext.api.JCodeVirtualDeviceGuest
import dev.blamspot.jcode.feature.marketplace.MarketplaceServiceLocator
import dev.blamspot.jcode.feature.marketplace.nativeGuestModule

/**
 * The `:guest` process, and nothing that runs in it.
 *
 * This is a **stub**. The container it used to be — the swapped `Instrumentation`, the rewritten
 * `Build`, the loaded guest APK — ships in the Android Dev Pack now; what cannot ship there is the
 * `android:process=":guest"` that gives all of it a heap of its own, because an extension cannot
 * contribute to `AndroidManifest.xml`. So the process stays declared here and its contents are
 * fetched at bind time.
 *
 * **Everything happens in [onBind], not [onCreate].** The pack is resolved from app-private storage,
 * which `:guest` can read because it is the same uid; but resolving it means opening a
 * multi-megabyte archive, and a service that did that on creation would pay for it every time the
 * system brought the process up for any reason. Binding is the point at which somebody actually
 * wants a device.
 *
 * With no pack installed, [onBind] answers null. `bindService` then simply never calls
 * `onServiceConnected`, which the tab already treats as a device that would not start.
 */
class GuestSessionService : Service() {

    private var guest: JCodeVirtualDeviceGuest? = null

    override fun onBind(intent: Intent?): IBinder? {
        val loaded = guest ?: load() ?: return null
        guest = loaded
        return runCatching { loaded.bind(intent) }
            .onFailure { Log.e(TAG, "the virtual device would not bind", it) }
            .getOrNull()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        runCatching { guest?.unbind() }
            .onFailure { Log.w(TAG, "the virtual device would not unbind cleanly", it) }
        return false
    }

    /**
     * Load the pack's guest half and let it hook this process.
     *
     * A pack that declares a guest entry but cannot install its hooks is a pack running on a
     * platform that moved under it — a hidden member now denied, a hook point gone. That is reported
     * as a device that will not start rather than left as a half-hooked process pretending to be one,
     * because the second is far harder to diagnose from the guest app's side.
     */
    private fun load(): JCodeVirtualDeviceGuest? {
        // This is a different process from the workbench, so the flag it pushes into the loader is
        // not set here — every unsigned pack was refused with developer options on, and the device
        // opened only to say it could not start its guest.
        NativeExtensionLoader.adoptAllowUnsigned(this)
        val extension = runCatching {
            MarketplaceServiceLocator.extensionInstaller(this).installed()
                .firstOrNull { it.nativeGuestModule() != null }
        }.getOrNull() ?: run {
            Log.i(TAG, "no installed extension provides a virtual device")
            return null
        }
        val (instance, resources) = runCatching { NativeExtensionLoader.resolveGuest(this, extension) }
            .onFailure { Log.e(TAG, "cannot load the virtual device from ${extension.id}", it) }
            .getOrNull() ?: return null

        val installed = runCatching { instance.install(this, resources) }
            .onFailure { Log.e(TAG, "the container's framework hooks would not install", it) }
            .getOrDefault(false)
        if (!installed) {
            Log.e(TAG, "${extension.id} could not install its container hooks; no device")
            return null
        }
        return instance
    }
}

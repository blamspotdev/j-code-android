package dev.jcode.vdevice

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * What the device says when a guest asks whether it is online, or whether Bluetooth is on.
 *
 * Left alone, both questions are answered by the **phone**: its Wi-Fi, its connection, its radio.
 * That is wrong in the ordinary way everything else in this container was wrong before it was fixed
 * — the device is supposed to be a device — and it is wrong in a way that costs something concrete.
 * *An app that has never been run without a network is an app whose offline path has never been
 * run*, and a developer cannot get an app into that state from here without turning the phone's own
 * Wi-Fi off, which also disconnects the IDE they are working in.
 *
 * ### The seam, and why it is a different one from the camera's
 *
 * [HiddenSeams] measures both. `ICameraService.Stub.asInterface` is **denied** at `targetSdk` 33, so
 * nothing can be put in front of the camera service. `IConnectivityManager`, `IWifiManager` and
 * `IBluetooth` all answer `asInterface=true`, which is the same seam [GuestLocation] goes through:
 * replace the entry in `ServiceManager`'s cache before anything reads it, and every manager built
 * afterwards is built on top of the replacement.
 *
 * ### Everything not answered here is passed through
 *
 * The proxies are **delegating**: a method this device has an opinion about is answered, and every
 * other one goes to the real binder untouched. That is the same shape as [GuestPackageHook] and it
 * is what makes this safe to install at all — `ConnectivityManager` has upwards of a hundred binder
 * methods, most of them things no guest asks and none of them things this container understands, and
 * a proxy that tried to be a complete implementation would be a much larger promise than the one
 * being made. The promise being made is: *the answers about this device are the device's.*
 *
 * ### `WifiManager` is not replaced either, and for a sharper reason
 *
 * It was, and it worked, and then it did not: `getSystemService(WIFI_SERVICE)` came back **null**.
 * `WifiManager`'s construction calls more of `IWifiManager` than a proxy built from the three
 * methods reflection exposes can answer, so the manager sometimes failed to build at all — and an
 * app that assumes `getSystemService` is non-null, which is nearly all of them, crashes on that.
 * A wrong answer to "is Wi-Fi on" is a much smaller failure than no manager, so the question is left
 * to the phone and the one that matters — *am I online* — is answered by `ConnectivityManager`,
 * which does work.
 *
 * ### Bluetooth is **not** stood in for
 *
 * The seam looked open. `BluetoothAdapter` is `final` and so cannot be substituted, but its
 * `mService` field is reachable and `IBluetooth.Stub.asInterface` is not blocked — which is the
 * shape that works for connectivity. Both directions were built and measured, and neither works:
 *
 * - **Clearing `mService`** left `isEnabled()` answering **true** with the phone's radio on.
 * - **Wrapping it** in a proxy answering `getState` never saw the call. `IBluetooth`'s visible
 *   method set on Android 13 is `asBinder, fetchRemoteUuids, getAddress, getConnectionState,
 *   getDeviceType, getRemoteAlias, getSocketOpt, getTwsPlusPeerAddress, isBroadcastActive,
 *   isTwsPlusDevice, setBondingInitiatedLocally, setSocketOpt, updateQuietModeStatus` — and
 *   `getState` is not in it, so `Proxy` does not implement it and the adapter's own call lands
 *   somewhere else entirely (it comes back as a wrapped `TimeoutException`).
 *
 * So the adapter's state does not travel through anything this container can reach. What the
 * device *can* govern is the part that goes through the package manager: whether it declares
 * `FEATURE_BLUETOOTH` at all, and whether an app is allowed `BLUETOOTH_CONNECT` and
 * `BLUETOOTH_SCAN`. That is [VirtualHardware.Bluetooth], and it is real — it is just less than
 * the label suggests, which is why the label says so.
 */
internal object GuestNetwork {

    private const val TAG = "VDEVICE"

    /** The services replaced, and the interface each one is reached through. */
    private const val CONNECTIVITY = "connectivity"

    private lateinit var host: Context

    /**
     * Installs the stand-ins. Returns what was replaced, for the line [GuestRuntime] logs.
     *
     * Must run before any guest `Context` exists: a manager is built once per context and caches its
     * binder, so a replacement made later is a replacement nothing is looking at.
     */
    fun install(context: Context): String {
        host = context.applicationContext
        val done = buildList {
            if (replace(CONNECTIVITY, "android.net.IConnectivityManager", ::connectivity)) add("net")
        }
        return if (done.isEmpty()) "none" else done.joinToString("+")
    }

    /** True while the device says it is on a network — see [VirtualHardware.WiFi]. */
    private fun online(): Boolean =
        runCatching { VirtualDevicePolicy.mode(host, VirtualHardware.WiFi) != HardwareMode.Off }
            .getOrDefault(true)

    /**
     * Answers the questions `ConnectivityManager` asks on an app's behalf.
     *
     * Only the "is there a network" family is answered, and only in the negative. With the device's
     * Wi-Fi on, every one of these falls through to the phone — the connection genuinely is the
     * phone's, an app really does fetch the URL, and reporting anything else would be a lie the app
     * could catch. With it off, the device has no active network, no default network and no
     * capabilities to describe, which is exactly what an app checks before it decides it is offline.
     */
    private fun connectivity(real: Any, method: Method, args: Array<Any?>?): Any? {
        if (online()) return Skip
        return when (method.name) {
            "getActiveNetwork",
            "getActiveNetworkForUid",
            "getActiveNetworkInfo",
            "getActiveNetworkInfoForUid",
            "getActiveLinkProperties",
            "getNetworkCapabilities",
            "getLinkProperties",
            -> null

            "getAllNetworks" -> emptyNetworks()
            "getAllNetworkInfo" -> arrayOfNulls<android.net.NetworkInfo>(0)
            "getNetworkInfo", "getNetworkInfoForUid" -> null
            "isActiveNetworkMetered" -> false
            "isDefaultNetworkActive" -> false
            else -> Skip
        }
    }

    private fun emptyNetworks(): Any = java.lang.reflect.Array.newInstance(android.net.Network::class.java, 0)

    /**
     * Puts a delegating proxy in `ServiceManager`'s cache under [name].
     *
     * The binder handed back is a local one carrying the interface as its *local* interface, so
     * `Stub.asInterface` returns the proxy directly rather than building a remote one — the trick
     * [GuestLocation] documents. Nothing leaves the process.
     */
    private fun replace(
        name: String,
        interfaceName: String,
        answer: (Any, Method, Array<Any?>?) -> Any?,
    ): Boolean = runCatching {
        val serviceManager = HiddenApi.classOrNull("android.os.ServiceManager") ?: return false
        val iface = HiddenApi.classOrNull(interfaceName) ?: return false
        val stub = HiddenApi.classOrNull("$interfaceName\$Stub") ?: return false
        val asInterface = stub.getMethod("asInterface", IBinder::class.java)
        val getService = HiddenApi.method(serviceManager, "getService", String::class.java) ?: return false
        val cache = HiddenApi.field(serviceManager, "sCache") ?: return false

        val realBinder = getService.invoke(null, name) as? IBinder ?: return false
        val realService = asInterface.invoke(null, realBinder) ?: return false
        val descriptor = runCatching { realBinder.interfaceDescriptor }.getOrNull() ?: interfaceName

        val proxy = Proxy.newProxyInstance(
            GuestNetwork::class.java.classLoader,
            arrayOf(iface),
        ) { _, method, args ->
            val decided = runCatching { answer(realService, method, args) }
                .onFailure { Log.w(TAG, "$name: cannot answer ${method.name}", it) }
                .getOrDefault(Skip)
            if (decided !== Skip) return@newProxyInstance decided
            try {
                method.invoke(realService, *(args ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
        }

        @Suppress("UNCHECKED_CAST")
        val map = cache.get(null) as MutableMap<String, IBinder>
        map[name] = Binder().apply { attachInterface(proxy as android.os.IInterface, descriptor) }
        true
    }.onFailure { Log.w(TAG, "cannot stand in for $name; a guest sees the phone's", it) }
        .getOrDefault(false)

    /** Returned by an answer that has no opinion, so the real service is asked instead. */
    private object Skip

    private const val BLUETOOTH_STATE_ON = 12
}

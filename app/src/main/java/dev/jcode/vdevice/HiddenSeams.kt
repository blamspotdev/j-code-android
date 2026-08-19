package dev.jcode.vdevice

import android.content.Context
import android.util.Log

/**
 * Measures what this container could stand in for, and writes the answer into the device's log.
 *
 * Every piece of hardware the device simulates was reached through a *seam* — a member the platform
 * has not withdrawn — and each one was found by trying, because the hidden-API policy refuses
 * silently. A blocked interface still has a `Class`; what marks it unusable is `getMethods()` coming
 * back with only `asBinder` on it, which is how the location work discovered that
 * `ILocationListener` could not be called by reflection at all.
 *
 * So the survey is code rather than a note in a document. It costs a few lines in the log the first
 * time a guest asks for a service, and it turns "cannot" into something a person can check on a
 * platform this was never run on — including the case that matters most, a future release quietly
 * opening something back up.
 */
internal object HiddenSeams {

    private const val TAG = "VDEVICE"

    private val reported = HashSet<String>()

    private class Step(val name: String, val run: () -> String)

    /**
     * Everything a Camera2 stand-in would have to reach, in the order it would have to reach it.
     *
     * The answer, measured at `targetSdk` 33 on Android 13: the seam exists and everything behind it
     * is shut. See the app-sandbox specification §7k.
     */
    private val camera2 = listOf(
        Step("ServiceManager.sCache") { serviceCache() },
        Step("media.camera binder") { service("media.camera") },
        Step("ICameraService") { describe("android.hardware.ICameraService") },
        Step("ICameraDeviceUser") { describe("android.hardware.camera2.ICameraDeviceUser") },
        Step("ICameraDeviceCallbacks") { describe("android.hardware.camera2.ICameraDeviceCallbacks") },
        Step("CameraMetadataNative") {
            val type = HiddenApi.classOrNull("android.hardware.camera2.impl.CameraMetadataNative")
                ?: return@Step "class is blocked"
            val ctor = runCatching { type.getConstructor() }.getOrNull()
            val set = type.methods.firstOrNull { it.name == "set" }
            "class ok, no-arg ctor=${ctor != null}, set()=${set != null}"
        },
        Step("CameraCharacteristics(metadata)") {
            val metadata = HiddenApi.classOrNull("android.hardware.camera2.impl.CameraMetadataNative")
                ?: return@Step "no CameraMetadataNative to build one from"
            val ctor = runCatching {
                android.hardware.camera2.CameraCharacteristics::class.java.getDeclaredConstructor(metadata)
            }.getOrNull()
            if (ctor == null) "ctor is blocked" else "reachable"
        },
        Step("StreamConfigurationMap") {
            describe("android.hardware.camera2.params.StreamConfigurationMap")
        },
        Step("SubmitInfo") { describe("android.hardware.camera2.utils.SubmitInfo") },
        Step("CaptureResultExtras") { describe("android.hardware.camera2.impl.CaptureResultExtras") },
    )

    /**
     * Everything a connectivity stand-in could go through.
     *
     * Two routes, and they fail differently. **Under** the manager is its binder — the same place
     * the location stand-in got in — and **at** the manager is a subclass handed to the guest from
     * `getSystemService`, which is how the sensors were done. The second needs a reachable
     * constructor and a non-final class; `BluetoothAdapter` is final, so for that one only the first
     * route could ever work.
     */
    private val network = listOf(
        Step("IConnectivityManager") { describe("android.net.IConnectivityManager") },
        Step("ConnectivityManager(ctx, svc)") {
            constructor(android.net.ConnectivityManager::class.java, "android.net.IConnectivityManager")
        },
        Step("ConnectivityManager is final") {
            java.lang.reflect.Modifier.isFinal(android.net.ConnectivityManager::class.java.modifiers).toString()
        },
        Step("IWifiManager") { describe("android.net.wifi.IWifiManager") },
        Step("WifiManager(ctx, svc, looper)") {
            val type = HiddenApi.classOrNull("android.net.wifi.WifiManager")
                ?: return@Step "class is blocked"
            val ctors = runCatching { type.declaredConstructors.size }.getOrDefault(-1)
            val final = java.lang.reflect.Modifier.isFinal(type.modifiers)
            "declaredCtors=$ctors final=$final"
        },
        Step("BluetoothAdapter") {
            val type = HiddenApi.classOrNull("android.bluetooth.BluetoothAdapter")
                ?: return@Step "class is blocked"
            val final = java.lang.reflect.Modifier.isFinal(type.modifiers)
            val service = HiddenApi.field(type, "mService")
            "final=$final mService=${service != null}"
        },
        Step("IBluetooth / bluetooth_manager") {
            "${describe("android.bluetooth.IBluetooth")} | binder ${service("bluetooth_manager")}"
        },
        Step("Settings.Global put") {
            val put = runCatching {
                android.provider.Settings.Global::class.java.getMethod(
                    "putInt",
                    android.content.ContentResolver::class.java,
                    String::class.java,
                    Int::class.javaPrimitiveType,
                )
            }.getOrNull()
            if (put == null) "blocked" else "reachable (needs WRITE_SETTINGS, which JCode has not)"
        },
    )

    private fun serviceCache(): String {
        val serviceManager = HiddenApi.classOrNull("android.os.ServiceManager")
            ?: return "no android.os.ServiceManager"
        val cache = HiddenApi.field(serviceManager, "sCache") ?: return "sCache is blocked"
        val map = cache.get(null) as? Map<*, *> ?: return "sCache is not a Map"
        return "reachable, ${map.size} services cached"
    }

    private fun service(name: String): String {
        val serviceManager = HiddenApi.classOrNull("android.os.ServiceManager") ?: return "no ServiceManager"
        val get = HiddenApi.method(serviceManager, "getService", String::class.java)
            ?: return "getService is blocked"
        return if (get.invoke(null, name) == null) "absent" else "present"
    }

    /** Whether [owner] has a constructor taking a context and the named service interface. */
    private fun constructor(owner: Class<*>, serviceInterface: String): String {
        val service = HiddenApi.classOrNull(serviceInterface) ?: return "no $serviceInterface"
        val ctor = runCatching {
            owner.getDeclaredConstructor(Context::class.java, service)
        }.getOrNull() ?: return "blocked"
        return "reachable (accessible=${runCatching { ctor.isAccessible = true; true }.getOrDefault(false)})"
    }

    /**
     * Counts the members a class offers a guest.
     *
     * The count is the point rather than the presence: an interface reporting one method is
     * reporting `asBinder`, and that is the fingerprint of a member the policy has withdrawn.
     */
    private fun describe(name: String): String {
        val type = HiddenApi.classOrNull(name) ?: return "class is blocked"
        val methods = runCatching { type.methods.size }.getOrDefault(-1)
        val declared = runCatching { type.declaredMethods.size }.getOrDefault(-1)
        val stub = HiddenApi.classOrNull("$name\$Stub")
        val asInterface = stub?.let {
            runCatching { it.getMethod("asInterface", android.os.IBinder::class.java) }.getOrNull()
        }
        return "methods=$methods declared=$declared Stub=${stub != null} asInterface=${asInterface != null}"
    }

    fun reportCamera2(context: Context) = report(context, "Camera2", camera2)

    fun reportNetwork(context: Context) = report(context, "connectivity", network)

    private fun report(context: Context, subject: String, steps: List<Step>) {
        synchronized(reported) { if (!reported.add(subject)) return }
        val lines = steps.joinToString("\n") { step ->
            val answer = runCatching { step.run() }
                .getOrElse { "threw ${it.javaClass.simpleName}: ${it.message}" }
            "  ${step.name.padEnd(34)} $answer"
        }
        VirtualDeviceLog.append(context, 'I', TAG, "$subject stand-in survey:\n$lines")
        Log.i(TAG, "$subject stand-in survey:\n$lines")
    }
}

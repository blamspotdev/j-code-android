package dev.jcode.vdevice

import android.content.Context
import android.util.Log

/**
 * Measures, once, exactly how much of Camera2 this container could stand in for.
 *
 * The device answers `ACTION_IMAGE_CAPTURE` with its own Camera app, which covers an app that wants
 * *a picture*. An app that wants a *camera pipeline* — `CameraManager.openCamera`, a capture
 * session, frames in its own `Surface` — is a different question, and the honest answer to it has
 * been "not from here" backed by reasoning rather than by measurement.
 *
 * This is the measurement. Standing in would mean replacing `ServiceManager`'s `media.camera` entry
 * the way [GuestLocation] replaces `location`, then implementing `ICameraService` and
 * `ICameraDeviceUser` and building `CameraCharacteristics` out of `CameraMetadataNative`. Each of
 * those is a member the hidden-API policy may refuse, and the ones that matter refuse *silently* —
 * `getMethods()` on a blocked interface returns almost nothing, which is how the location work
 * discovered that `ILocationListener` could not be called by reflection at all.
 *
 * So each piece is asked about by name and the answer written into the device's log. It costs one
 * line per guest process and it turns "cannot" into something a person can check.
 */
internal object Camera2Probe {

    private const val TAG = "VDEVICE"

    @Volatile
    private var reported = false

    /** Every step a Camera2 stand-in would have to take, in the order it would have to take them. */
    private val steps = listOf(
        Step("ServiceManager.sCache") {
            val serviceManager = HiddenApi.classOrNull("android.os.ServiceManager")
                ?: return@Step "no android.os.ServiceManager"
            val cache = HiddenApi.field(serviceManager, "sCache")
                ?: return@Step "sCache is blocked"
            val map = cache.get(null) as? Map<*, *> ?: return@Step "sCache is not a Map"
            "reachable, ${map.size} services cached"
        },
        Step("media.camera binder") {
            val serviceManager = HiddenApi.classOrNull("android.os.ServiceManager")
                ?: return@Step "no ServiceManager"
            val get = HiddenApi.method(serviceManager, "getService", String::class.java)
                ?: return@Step "getService is blocked"
            if (get.invoke(null, "media.camera") == null) "the phone has no media.camera" else "present"
        },
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
        Step("CameraCharacteristics(CameraMetadataNative)") {
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

    private class Step(val name: String, val run: () -> String)

    /**
     * Counts the members a class offers a guest.
     *
     * The count is the point rather than the presence: a blocked interface still has a `Class`, and
     * what marks it as unusable is `getMethods()` coming back with only `asBinder` on it. That
     * asymmetry is what the location work had to learn by hand.
     */
    private fun describe(name: String): String {
        val type = HiddenApi.classOrNull(name) ?: return "class is blocked"
        val methods = runCatching { type.methods.size }.getOrDefault(-1)
        val declared = runCatching { type.declaredMethods.size }.getOrDefault(-1)
        val stub = HiddenApi.classOrNull("$name\$Stub")
        val asInterface = stub?.let { runCatching { it.getMethod("asInterface", android.os.IBinder::class.java) }.getOrNull() }
        return "methods=$methods declared=$declared Stub=${stub != null} asInterface=${asInterface != null}"
    }

    fun report(context: Context) {
        if (reported) return
        reported = true
        val lines = steps.joinToString("\n") { step ->
            val answer = runCatching { step.run() }.getOrElse { "threw ${it.javaClass.simpleName}: ${it.message}" }
            "  ${step.name.padEnd(34)} $answer"
        }
        VirtualDeviceLog.append(context, 'I', TAG, "Camera2 stand-in survey:\n$lines")
        Log.i(TAG, "Camera2 stand-in survey:\n$lines")
    }
}

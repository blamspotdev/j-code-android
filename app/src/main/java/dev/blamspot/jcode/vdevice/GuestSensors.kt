package dev.blamspot.jcode.vdevice

import android.content.Context
import android.hardware.GuestSensorManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.util.Log

/**
 * Hands a guest the sensors the user has given it — see [GuestSensorManager], which has to live in
 * `android.hardware` to be able to extend `SensorManager` at all, and so is kept to that job alone.
 */
internal object GuestSensors {

    /**
     * The manager this guest should be handed, or the phone's own when one cannot be built.
     *
     * The fallback is the behaviour that was there before any of this, not a failure: the guest
     * keeps the sensors it has always had, and the log says the device could not govern them. That
     * is the honest degradation for a container built on members the platform may withdraw — but it
     * is also the *permissive* direction, which is why it is proved here rather than discovered
     * inside an app's own `onCreate`.
     */
    fun forGuest(context: Context, guest: LoadedGuest, host: SensorManager): SensorManager =
        guest.sensors ?: runCatching {
            GuestSensorManager(context.applicationContext, host, guest.packageName)
                // One call through the vtable: if any of the hidden abstract members failed to link,
                // this is where AbstractMethodError lands, and it lands here.
                .also { it.getSensorList(Sensor.TYPE_ALL) }
        }.onFailure {
            Log.w(TAG, "cannot govern ${guest.packageName}'s sensors; it gets the phone's", it)
        }.getOrDefault(host).also { guest.sensors = it }
}

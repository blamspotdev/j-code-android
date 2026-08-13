package android.hardware

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.jcode.vdevice.HardwareMode
import dev.jcode.vdevice.HardwareSample
import dev.jcode.vdevice.SimulatedHardware
import dev.jcode.vdevice.TAG
import dev.jcode.vdevice.VirtualDevicePolicy
import dev.jcode.vdevice.VirtualHardware

/** The sampling period is honoured within these bounds; a simulated device is not worth 400 Hz. */
private const val MIN_PERIOD_MS = 20L
private const val MAX_PERIOD_MS = 200L

/**
 * How often a registration looks up while the phone's own sensor is doing the reporting. It is only
 * watching for the device to be rewired, and a quarter-second of lag on that is below noticing.
 */
private const val WATCH_MS = 250L

/**
 * The `SensorManager` a guest on J Code's virtual device is handed instead of the phone's.
 *
 * Motion sensors are the one piece of hardware Android has never put behind a permission: any app
 * may read the accelerometer, the magnetometer and the gyroscope without asking anybody. That is
 * fine on a phone, where the app is one the user chose to install. It is not fine here, where the
 * app is somebody else's build being run inside an IDE — and until this class existed there was no
 * way to say no, because the container handed guests the host's own manager and every guest could
 * feel the user's hand.
 *
 * So each of the three families in `VirtualHardware` is resolved per app, at the moment it is asked
 * for:
 *
 *  - **Off** — the sensor is not in the list, and a registration for one is refused. An app that
 *    checks properly finds the device has no such hardware.
 *  - **Real** — the phone's, forwarded through a listener of our own so that turning it off later
 *    actually stops the stream rather than leaving it running until the app unregisters.
 *  - **Simulated** — the device's own: it is lying flat, face up, pointing north, and not moving.
 *    Delivered on a ticker at the rate the app asked for.
 *
 * Everything else the phone has — light, proximity, pressure, heart rate — is passed through
 * untouched. This manager governs what the device claims as *its* hardware, and inventing a policy
 * for the rest would be a larger claim than the one being made.
 *
 * ### Why this class is in `android.hardware`
 *
 * Not for access to anything private. `SensorManager`'s constructor is **package-private in the SDK
 * stub** — metalava writes one for every abstract class an app is not meant to subclass — while the
 * class the runtime actually loads has the ordinary public default constructor of a public class. So
 * the restriction is a compile-time one and only a compile-time one, and sharing the package is what
 * satisfies it. Nothing here reaches for a member the platform hides; see below for what does.
 *
 * ### Why the overrides carry no `override`
 *
 * Every abstract member of `SensorManager` is `@hide`, so none of them is in the stub this compiles
 * against and none of them can be written with `override` — the compiler would be overriding
 * something it cannot see. They are declared as ordinary methods with the same name and descriptor,
 * which is what the runtime matches on when it links the vtable, so the platform's concrete
 * `getSensorList`, `getDefaultSensor` and `registerListener` land on them.
 *
 * A member this misses stays abstract and would throw `AbstractMethodError` if a guest reached it.
 * That is why the class is *proved* before a guest is given one — `GuestSensors.forGuest` calls
 * through it once and falls back to the phone's manager if anything at all goes wrong, so a platform
 * that changes any of this costs the device its sensor policy and nothing else.
 */
@Suppress("unused", "DEPRECATION")
class GuestSensorManager(
    private val context: Context,
    private val host: SensorManager,
    private val packageName: String,
) : SensorManager() {

    private val registrations = HashMap<SensorEventListener, MutableList<Registration>>()

    // ------------------------------------------------------------------ the hidden abstract members

    fun getFullSensorList(): List<Sensor> = host.getSensorList(Sensor.TYPE_ALL).filter { sensor ->
        when (modeOf(sensor.type)) {
            HardwareMode.Off -> false
            HardwareMode.Real -> true
            // Nothing is offered as simulated on a platform that will not let a SensorEvent be
            // built: a sensor in the list that can never report is worse for an app than one that
            // is not there at all.
            HardwareMode.Simulated -> GuestSensorEvents.available
        }
    }

    fun getFullDynamicSensorList(): List<Sensor> = emptyList()

    fun registerListenerImpl(
        listener: SensorEventListener?,
        sensor: Sensor?,
        delayUs: Int,
        handler: Handler?,
        maxBatchReportLatencyUs: Int,
        reservedFlags: Int,
    ): Boolean {
        if (listener == null || sensor == null) return false
        // Refused outright when the device does not have it — the same answer a phone gives for a
        // sensor that is not there, and the reason it stays refused rather than resuming later is
        // that the sensor was not in the list the app read either.
        if (modeOf(sensor.type) == HardwareMode.Off) return false
        val registration = Registration(
            listener = listener,
            sensor = sensor,
            delayUs = delayUs,
            maxBatchUs = maxBatchReportLatencyUs,
            handler = handler ?: Handler(Looper.getMainLooper()),
        )
        synchronized(this) { registrations.getOrPut(listener) { ArrayList() } += registration }
        registration.start()
        return true
    }

    fun unregisterListenerImpl(listener: SensorEventListener?, sensor: Sensor?) {
        if (listener == null) return
        synchronized(this) {
            registrations[listener]?.let { open ->
                open.filter { sensor == null || it.sensor == sensor }.forEach { it.stop() }
                open.removeAll { sensor == null || it.sensor == sensor }
                if (open.isEmpty()) registrations.remove(listener)
            }
        }
    }

    /** A simulated stream is never behind, so there is nothing of it to flush. */
    fun flushImpl(listener: SensorEventListener?): Boolean {
        val target = listener ?: return true
        val open = synchronized(this) { registrations[target]?.firstOrNull { it.forwarding() } }
            ?: return true
        return open.flush()
    }

    /**
     * One-shot sensors — significant motion, and the tilt detector beside it. A simulated device is
     * not moving, so the request is accepted and simply never fires, which is exactly what happens
     * to an app on a phone that is sitting on a desk.
     */
    fun requestTriggerSensorImpl(listener: TriggerEventListener?, sensor: Sensor?): Boolean {
        if (listener == null || sensor == null) return false
        return when (modeOf(sensor.type)) {
            HardwareMode.Off -> false
            HardwareMode.Real -> host.requestTriggerSensor(listener, sensor)
            HardwareMode.Simulated -> true
        }
    }

    fun cancelTriggerSensorImpl(
        listener: TriggerEventListener?,
        sensor: Sensor?,
        disable: Boolean,
    ): Boolean {
        if (listener == null || sensor == null) return false
        if (modeOf(sensor.type) != HardwareMode.Real) return true
        return host.cancelTriggerSensor(listener, sensor)
    }

    /** Injection is a platform-build facility, and this device is not one. */
    fun initDataInjectionImpl(enable: Boolean): Boolean = false

    fun injectSensorDataImpl(
        sensor: Sensor?,
        values: FloatArray?,
        accuracy: Int,
        timestamp: Long,
    ): Boolean = false

    fun setOperationParameterImpl(parameter: SensorAdditionalInfo?): Boolean = false

    fun registerDynamicSensorCallbackImpl(
        callback: SensorManager.DynamicSensorCallback?,
        handler: Handler?,
    ) = Unit

    fun unregisterDynamicSensorCallbackImpl(callback: SensorManager.DynamicSensorCallback?) = Unit

    // ------------------------------------------------------------------------------- the two routes

    /**
     * One app's registration on one sensor, for as long as it holds it — however the device is
     * rewired underneath.
     *
     * **The two routes are one object because switching between them has to be seamless.** They were
     * two, and the seam showed: a simulated stream stopped itself the moment the sensor was switched
     * to Real and nothing took over, while a Real one went on being forwarded and never became
     * simulated. Either way the app was left holding a registration that had quietly stopped
     * reporting, with no error and nothing to re-register in response to. A sensor changing what it
     * is wired to is not a reason for an app to stop hearing from it.
     *
     * So the registration owns a ticker of its own and decides on every tick. Simulated, it ticks at
     * the rate the app asked for and delivers. Real, it holds a forwarder against the host and ticks
     * slowly, watching for the mode to move — the events themselves arrive from the sensor service in
     * between. Off, it holds nothing and waits, because the device may be given the hardware back.
     */
    private inner class Registration(
        private val listener: SensorEventListener,
        val sensor: Sensor,
        private val delayUs: Int,
        private val maxBatchUs: Int,
        private val handler: Handler,
    ) : Runnable {

        private val periodMs = (delayUs / 1_000L).coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
        private val event: SensorEvent? = GuestSensorEvents.newEvent(valueCount(sensor.type))?.also {
            it.sensor = sensor
            it.accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        }

        /** Registered with the host while the sensor is the phone's; null otherwise. */
        private var forwarder: SensorEventListener? = null
        private var wiredTo: HardwareMode? = null

        @Volatile
        private var running = true

        fun start() = handler.post(this)

        fun stop() {
            running = false
            handler.removeCallbacks(this)
            detach()
        }

        fun forwarding(): Boolean = forwarder != null

        fun flush(): Boolean =
            forwarder?.let { runCatching { host.flush(it) }.getOrDefault(true) } ?: true

        override fun run() {
            if (!running) return
            val mode = modeOf(sensor.type)
            if (mode != wiredTo) {
                rewire(mode)
                wiredTo = mode
            }
            if (mode == HardwareMode.Simulated) deliver()
            // Fast enough to be the sensor while it is simulated; slow enough to be free while the
            // phone's own is doing the reporting and this is only watching for a change.
            handler.postDelayed(this, if (mode == HardwareMode.Simulated) periodMs else WATCH_MS)
        }

        private fun rewire(mode: HardwareMode) {
            detach()
            if (mode == HardwareMode.Real) attach() else if (mode == HardwareMode.Simulated) {
                // A sensor that has just started reporting says how good it is, the way one coming
                // out of a calibration does.
                runCatching {
                    listener.onAccuracyChanged(sensor, SensorManager.SENSOR_STATUS_ACCURACY_HIGH)
                }
            }
        }

        private fun attach() {
            val relay = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    // Checked per event as well as per tick: the mode can move between the two, and
                    // "Off" has to mean off from the next event rather than the next tick.
                    if (modeOf(sensor.type) == HardwareMode.Real) listener.onSensorChanged(event)
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                    if (modeOf(this@Registration.sensor.type) == HardwareMode.Real) {
                        listener.onAccuracyChanged(sensor, accuracy)
                    }
                }
            }
            if (host.registerListener(relay, sensor, delayUs, maxBatchUs, handler)) forwarder = relay
        }

        private fun detach() {
            forwarder?.let { runCatching { host.unregisterListener(it, sensor) } }
            forwarder = null
        }

        /**
         * One [SensorEvent] per registration, refilled in place and handed out again — which is what
         * the platform does too, and why an app is documented not to keep the object it is given.
         */
        private fun deliver() {
            val event = event ?: return
            simulate(sensor.type, SimulatedHardware.sample(context))?.let { values ->
                values.copyInto(event.values)
                event.timestamp = SystemClock.elapsedRealtimeNanos()
                runCatching { listener.onSensorChanged(event) }
                    .onFailure { Log.w(TAG, "$packageName threw on a simulated ${sensor.stringType}", it) }
            }
        }
    }

    private fun modeOf(type: Int): HardwareMode {
        // Anything the device does not claim as its own is the phone's, exactly as it was before
        // this manager existed.
        val hardware = VirtualHardware.bySensorType(type) ?: return HardwareMode.Real
        return VirtualDevicePolicy.mode(context, hardware)
    }

    /**
     * What the simulated device reads on [type] at this instant — or null for a sensor whose honest
     * simulated behaviour is to report nothing at all.
     *
     * Every value comes out of one [HardwareSample], so the accelerometer, the compass and the
     * rotation vector are three views of the same attitude rather than three unrelated constants.
     * With nothing set that attitude is a device lying flat, face up, pointing north and still —
     * which is where all of this started.
     */
    private fun simulate(type: Int, now: HardwareSample): FloatArray? = when (type) {
        Sensor.TYPE_ACCELEROMETER -> now.accelerometer
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED -> now.accelerometer.withNoBias()
        Sensor.TYPE_GRAVITY -> now.gravity
        // What is left of the accelerometer once gravity is taken out: the shaking, and nothing else.
        Sensor.TYPE_LINEAR_ACCELERATION -> floatArrayOf(
            now.accelerometer[0] - now.gravity[0],
            now.accelerometer[1] - now.gravity[1],
            now.accelerometer[2] - now.gravity[2],
        )
        Sensor.TYPE_MAGNETIC_FIELD -> now.magnetic
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED -> now.magnetic.withNoBias()
        Sensor.TYPE_ORIENTATION -> now.orientation
        Sensor.TYPE_GYROSCOPE -> now.gyroscope
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED -> now.gyroscope.withNoBias()
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        -> now.rotationVector
        Sensor.TYPE_STEP_COUNTER -> floatArrayOf(0f)
        // Step detection and significant motion are one-shot: a device that never moves never fires
        // them, and saying so by sending nothing is more truthful than inventing a step.
        else -> null
    }

    /** The uncalibrated form of a reading: the value, then the estimated bias, which here is none. */
    private fun FloatArray.withNoBias(): FloatArray =
        floatArrayOf(this[0], this[1], this[2], 0f, 0f, 0f)

    /** How many values [type] carries, which the event is allocated for before any are read. */
    private fun valueCount(type: Int): Int = when (type) {
        Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
        Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
        Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
        -> 6
        Sensor.TYPE_ROTATION_VECTOR,
        Sensor.TYPE_GAME_ROTATION_VECTOR,
        Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        -> 5
        Sensor.TYPE_STEP_COUNTER -> 1
        else -> 3
    }
}

/**
 * The one thing simulated sensors need from the platform, resolved once and reported honestly when
 * it is not there.
 *
 * `SensorEvent` cannot be constructed from outside its package — the framework pools them, and the
 * constructor is genuinely package-private in the *runtime* class rather than only in the stub, so
 * sharing the package is not enough and reflection is the only route. If it is ever closed off,
 * simulation is not possible on that platform and the sensors involved are dropped from the guest's
 * list rather than offered as hardware that never reports.
 */
internal object GuestSensorEvents {

    private val constructor = runCatching {
        SensorEvent::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
    }.onFailure {
        Log.w(TAG, "this platform will not build a SensorEvent; the device cannot simulate sensors", it)
    }.getOrNull()

    val available: Boolean get() = constructor != null

    fun newEvent(valueCount: Int): SensorEvent? = constructor?.let {
        runCatching { it.newInstance(valueCount) as SensorEvent }.getOrNull()
    }
}

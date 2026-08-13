package dev.jcode.vdevice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.util.Properties

/** What one piece of the virtual device's hardware is wired to, for one app. */
internal enum class HardwareMode(val label: String) {
    /** The device does not have it: not declared, not permitted, and no data. */
    Off("Off"),

    /** The device has one of its own, and it is not the phone's. */
    Simulated("Simulated"),

    /** The phone's, passed straight through. */
    Real("Real"),
}

/**
 * The hardware J Code's virtual device can be given, and what each piece is allowed to be.
 *
 * The asymmetry between these is not a matter of taste. A guest runs under J Code's uid and holds
 * J Code's permissions, so what the container can offer is bounded by what the *IDE* is allowed to
 * do — and by what can be synthesised convincingly enough to be worth offering at all.
 *
 *  - **Camera and location have no [HardwareMode.Real].** Not because the plumbing is hard, but
 *    because handing a guest APK the user's viewfinder or their real coordinates is the one thing
 *    this device exists to avoid. A dev tool that runs somebody else's build must not be the way
 *    that build learns where its user is.
 *  - **The three motion sensors carry no permission at all.** Android has never gated them, which is
 *    why a guest has been getting the phone's real accelerometer, magnetometer and gyroscope since
 *    the day the device could run an app — with nothing anywhere able to say no. That is what
 *    [HardwareMode.Off] is for, and it is the reason this whole file exists.
 *  - **The microphone is the only one whose Real needs something of J Code**, namely `RECORD_AUDIO`,
 *    which is asked for at the moment an app is switched to it and never before.
 *
 * [features] is what the device *declares* — the answers [GuestPackageHook] gives `hasSystemFeature`,
 * so an app that checks whether the hardware exists before reaching for it gets a straight answer.
 * [permissions] is what it *permits*. [sensorTypes] is the family [GuestSensorManager] governs: the
 * derived types go with the sensor they are computed from, or turning the accelerometer off would
 * leave the device's motion readable through `TYPE_GRAVITY` anyway.
 */
@Suppress("DEPRECATION")
internal enum class VirtualHardware(
    val id: String,
    val label: String,
    val summary: String,
    val modes: List<HardwareMode>,
    val fallback: HardwareMode,
    val permissions: List<String> = emptyList(),
    val features: List<String> = emptyList(),
    val sensorTypes: List<Int> = emptyList(),
) {
    Camera(
        id = "camera",
        label = "Camera",
        summary = "Simulated declares a camera and permits it; no frames ever arrive, because the " +
            "phone's camera is never lent to a guest.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated),
        fallback = HardwareMode.Off,
        permissions = listOf(Manifest.permission.CAMERA),
        features = listOf(
            PackageManager.FEATURE_CAMERA,
            PackageManager.FEATURE_CAMERA_ANY,
            PackageManager.FEATURE_CAMERA_FRONT,
        ),
    ),

    Microphone(
        id = "microphone",
        label = "Microphone",
        summary = "Simulated declares a microphone that records nothing. Real is the phone's, and " +
            "asks J Code for permission to record the first time you choose it.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated, HardwareMode.Real),
        fallback = HardwareMode.Off,
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        features = listOf(PackageManager.FEATURE_MICROPHONE),
    ),

    Location(
        id = "location",
        label = "Location",
        summary = "A fixed fix you set below, reported as GPS. The phone's own location is never " +
            "offered — an app on this device cannot learn where you are.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated),
        fallback = HardwareMode.Off,
        permissions = listOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_BACKGROUND_LOCATION,
        ),
        features = listOf(
            PackageManager.FEATURE_LOCATION,
            PackageManager.FEATURE_LOCATION_GPS,
            PackageManager.FEATURE_LOCATION_NETWORK,
        ),
    ),

    Accelerometer(
        id = "accelerometer",
        label = "Accelerometer",
        summary = "Simulated reports a device lying flat and still. Real is the phone's, so the app " +
            "feels every time you move it.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated, HardwareMode.Real),
        fallback = HardwareMode.Simulated,
        features = listOf(PackageManager.FEATURE_SENSOR_ACCELEROMETER),
        sensorTypes = listOf(
            Sensor.TYPE_ACCELEROMETER,
            Sensor.TYPE_ACCELEROMETER_UNCALIBRATED,
            Sensor.TYPE_GRAVITY,
            Sensor.TYPE_LINEAR_ACCELERATION,
            Sensor.TYPE_SIGNIFICANT_MOTION,
            Sensor.TYPE_STEP_COUNTER,
            Sensor.TYPE_STEP_DETECTOR,
        ),
    ),

    Compass(
        id = "compass",
        label = "Compass",
        summary = "Simulated points north and stays there. Real is the phone's magnetometer.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated, HardwareMode.Real),
        fallback = HardwareMode.Simulated,
        features = listOf(PackageManager.FEATURE_SENSOR_COMPASS),
        sensorTypes = listOf(
            Sensor.TYPE_MAGNETIC_FIELD,
            Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED,
            Sensor.TYPE_ORIENTATION,
            Sensor.TYPE_GEOMAGNETIC_ROTATION_VECTOR,
        ),
    ),

    Gyroscope(
        id = "gyroscope",
        label = "Gyroscope",
        summary = "Simulated reports a device that is not turning. Real is the phone's.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated, HardwareMode.Real),
        fallback = HardwareMode.Simulated,
        features = listOf(PackageManager.FEATURE_SENSOR_GYROSCOPE),
        sensorTypes = listOf(
            Sensor.TYPE_GYROSCOPE,
            Sensor.TYPE_GYROSCOPE_UNCALIBRATED,
            Sensor.TYPE_ROTATION_VECTOR,
            Sensor.TYPE_GAME_ROTATION_VECTOR,
        ),
    );

    /**
     * Whether [HardwareMode.Real] can actually be honoured here, which is a question about the
     * *phone*: a compass the host does not have cannot be passed through to anybody, and the
     * microphone is the phone's only once the user has let J Code record.
     */
    fun realAvailable(context: Context): Boolean = when {
        !modes.contains(HardwareMode.Real) -> false
        this == Microphone -> context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        else -> hostSensor(context) != null
    }

    /**
     * Whether [HardwareMode.Real] is worth putting in front of the user.
     *
     * Not the same question as [realAvailable]. The microphone is offered even while J Code holds no
     * `RECORD_AUDIO`, because choosing it is what asks for it — but a compass the phone does not
     * have is not a choice, it is a dead end, so it is not shown.
     */
    fun realOffered(context: Context): Boolean = when {
        !modes.contains(HardwareMode.Real) -> false
        this == Microphone -> true
        else -> hostSensor(context) != null
    }

    /** The phone's own sensor behind this entry, or null when it has none. */
    fun hostSensor(context: Context): Sensor? {
        val type = sensorTypes.firstOrNull() ?: return null
        return runCatching {
            context.getSystemService(SensorManager::class.java)?.getDefaultSensor(type)
        }.getOrNull()
    }

    companion object {
        fun byId(id: String): VirtualHardware? = entries.firstOrNull { it.id == id }

        /** The entry that governs [permission], or null for one this device has no opinion about. */
        fun byPermission(permission: String): VirtualHardware? =
            entries.firstOrNull { it.permissions.contains(permission) }

        /** The entry that governs [feature], or null for one this device has no opinion about. */
        fun byFeature(feature: String): VirtualHardware? =
            entries.firstOrNull { it.features.contains(feature) }

        /** The entry that governs a sensor of [type], or null for one left alone entirely. */
        fun bySensorType(type: Int): VirtualHardware? =
            entries.firstOrNull { it.sensorTypes.contains(type) }
    }
}

/**
 * What each app installed on the virtual device is allowed to reach, and what the device's simulated
 * hardware reports.
 *
 * ### Why a file rather than preferences
 *
 * Two processes disagree about `SharedPreferences`. The launcher that writes this lives in the IDE
 * and the container that acts on it lives in `:guest`, and a preferences file is cached in memory
 * per process from the moment it is first read — so `:guest` would keep answering with whatever the
 * policy said when the guest started, and a permission the user revoked while an app was on the
 * screen would go on being granted until the process died. `MODE_MULTI_PROCESS` has been deprecated
 * and unreliable since API 11, so there is nothing to turn on.
 *
 * A plain properties file, written atomically and re-read whenever its timestamp moves, has none of
 * that ambiguity: the writer renames a complete file into place, and the reader notices. It is a
 * handful of lines of state — this is not a store worth building anything cleverer for.
 *
 * ### Why it does not survive a restart
 *
 * It lives inside `filesDir/vdevice/`, so [VirtualDeviceApps.resetOnStart] takes it with everything
 * else. That is deliberate rather than incidental: the device is wiped on every start, and a grant
 * that outlived the app it was granted to would be a permission attached to nothing, waiting to
 * apply itself to whatever was installed under that package name next.
 */
internal object VirtualDevicePolicy {

    private const val FILE = "policy"
    private const val BACKGROUND = "background"

    private const val LOCATION_MODE = "location.mode"
    private const val LATITUDE = "location.latitude"
    private const val LONGITUDE = "location.longitude"
    private const val TO_LATITUDE = "location.to.latitude"
    private const val TO_LONGITUDE = "location.to.longitude"
    private const val SPEED = "location.speed"
    private const val REPEAT = "location.repeat"
    private const val ROUTE_STARTED = "location.startedAt"

    private const val PITCH = "motion.pitch"
    private const val ROLL = "motion.roll"
    private const val AZIMUTH = "motion.azimuth"
    private const val LOOP = "motion.loop"
    private const val AMPLITUDE = "motion.amplitude"
    private const val PERIOD = "motion.period"
    private const val LOOP_STARTED = "motion.startedAt"
    private const val IMPULSE_UNTIL = "motion.impulseUntil"

    /** Where the emulator puts you when nobody has said otherwise, and so where this does too. */
    const val DEFAULT_LATITUDE = 37.4220
    const val DEFAULT_LONGITUDE = -122.0841

    /** 50 km/h — a car on a road, which is what a route is usually standing in for. */
    const val DEFAULT_SPEED_MPS = 13.9f

    /** Bumped on every write, so the sheet redraws. Snapshot state: its only reader is a composable. */
    val revision = mutableIntStateOf(0)

    private var cached: Properties? = null
    private var cachedAt = 0L
    private var checkedAt = 0L

    /**
     * How stale a reader is allowed to be before it looks at the file again.
     *
     * The sensors are sampled up to fifty times a second and each sample asks what the policy says,
     * so without this every reading costs a `stat`. A change made in the IDE therefore reaches a
     * running guest within a quarter of a second rather than instantly, which is below the threshold
     * of noticing and well above the cost of checking. The writing process is not throttled: [edit]
     * updates the copy in memory, so the tab sees its own changes at once.
     */
    private const val RESTAT_MS = 250L

    /** How [packageName] is wired to [hardware] — its [VirtualHardware.fallback] until asked otherwise. */
    fun mode(context: Context, packageName: String, hardware: VirtualHardware): HardwareMode {
        val stored = read(context).getProperty(key(packageName, hardware.id))
            ?: return hardware.fallback
        val mode = runCatching { HardwareMode.valueOf(stored) }.getOrNull() ?: hardware.fallback
        // A stored Real that the phone can no longer honour — permission revoked in system settings,
        // or a policy carried onto a device with no gyroscope — reads as Simulated rather than as a
        // passthrough that would quietly return nothing.
        return if (mode == HardwareMode.Real && !hardware.realAvailable(context)) {
            HardwareMode.Simulated
        } else {
            mode
        }
    }

    fun setMode(context: Context, packageName: String, hardware: VirtualHardware, mode: HardwareMode) {
        edit(context) { it.setProperty(key(packageName, hardware.id), mode.name) }
    }

    /**
     * Whether [packageName] may keep running once it is not the app on the screen.
     *
     * Off by default, and the default is the honest one: the device shows one app at a time, so
     * leaving an app is the closest thing it has to closing one. An app told otherwise keeps its
     * services and its notifications when it goes away — which is what a music player or a download
     * needs, and what nothing else should have.
     */
    fun backgroundAllowed(context: Context, packageName: String): Boolean =
        read(context).getProperty(key(packageName, BACKGROUND)).toBoolean()

    fun setBackgroundAllowed(context: Context, packageName: String, allowed: Boolean) {
        edit(context) { it.setProperty(key(packageName, BACKGROUND), allowed.toString()) }
    }

    /**
     * How the device's simulated hardware is set — one description for the whole device, because a
     * phone has one GPS and one set of sensors however many apps are reading them.
     *
     * Read rather than watched: [VirtualHardware] turns this into a reading for a given instant, and
     * both processes do that themselves. See its notes on why nothing is streamed.
     */
    fun hardware(context: Context): HardwareSettings {
        val stored = read(context)
        fun number(key: String, fallback: Double) = stored.getProperty(key)?.toDoubleOrNull() ?: fallback
        fun decimal(key: String, fallback: Float) = stored.getProperty(key)?.toFloatOrNull() ?: fallback
        fun stamp(key: String) = stored.getProperty(key)?.toLongOrNull() ?: 0L
        val loop = stored.getProperty(LOOP)?.let { name ->
            runCatching { MotionLoop.valueOf(name) }.getOrNull()
        } ?: MotionLoop.None
        return HardwareSettings(
            locationMode = stored.getProperty(LOCATION_MODE)
                ?.let { runCatching { LocationMode.valueOf(it) }.getOrNull() }
                ?: LocationMode.Fixed,
            latitude = number(LATITUDE, DEFAULT_LATITUDE),
            longitude = number(LONGITUDE, DEFAULT_LONGITUDE),
            toLatitude = number(TO_LATITUDE, DEFAULT_LATITUDE),
            toLongitude = number(TO_LONGITUDE, DEFAULT_LONGITUDE),
            speedMps = decimal(SPEED, DEFAULT_SPEED_MPS),
            repeat = stored.getProperty(REPEAT)
                ?.let { runCatching { RouteRepeat.valueOf(it) }.getOrNull() }
                ?: RouteRepeat.Once,
            routeStartedAt = stamp(ROUTE_STARTED),
            pitch = decimal(PITCH, 0f),
            roll = decimal(ROLL, 0f),
            azimuth = decimal(AZIMUTH, 0f),
            loop = loop,
            amplitude = decimal(AMPLITUDE, loop.defaultAmplitude),
            periodMs = stored.getProperty(PERIOD)?.toLongOrNull() ?: loop.defaultPeriodMs,
            loopStartedAt = stamp(LOOP_STARTED),
            impulseUntil = stamp(IMPULSE_UNTIL),
        )
    }

    /** Parks the device on one point — what typing coordinates means, so it also ends any route. */
    fun setFix(context: Context, latitude: Double, longitude: Double) {
        edit(context) {
            it.setProperty(LOCATION_MODE, LocationMode.Fixed.name)
            it.setProperty(LATITUDE, latitude.toString())
            it.setProperty(LONGITUDE, longitude.toString())
        }
    }

    /** Where a route ends, how fast it is walked, and what happens when it gets there. */
    fun setRoute(
        context: Context,
        toLatitude: Double,
        toLongitude: Double,
        speedMps: Float,
        repeat: RouteRepeat,
    ) {
        edit(context) {
            it.setProperty(TO_LATITUDE, toLatitude.toString())
            it.setProperty(TO_LONGITUDE, toLongitude.toString())
            it.setProperty(SPEED, speedMps.toString())
            it.setProperty(REPEAT, repeat.name)
        }
    }

    /**
     * Starts or stops the route. Starting stamps the clock it is measured from — the position is a
     * function of how long ago this happened, so this *is* the moving.
     */
    fun setRouteRunning(context: Context, running: Boolean, nowElapsed: Long) {
        edit(context) {
            it.setProperty(LOCATION_MODE, if (running) LocationMode.Route.name else LocationMode.Fixed.name)
            it.setProperty(ROUTE_STARTED, (if (running) nowElapsed else 0L).toString())
        }
    }

    /** The attitude the device is resting in, in degrees. */
    fun setAttitude(context: Context, pitch: Float, roll: Float, azimuth: Float) {
        edit(context) {
            it.setProperty(PITCH, pitch.toString())
            it.setProperty(ROLL, roll.toString())
            it.setProperty(AZIMUTH, SimulatedHardware.normalise(azimuth).toString())
        }
    }

    fun setLoop(context: Context, loop: MotionLoop, amplitude: Float, periodMs: Long, nowElapsed: Long) {
        edit(context) {
            it.setProperty(LOOP, loop.name)
            it.setProperty(AMPLITUDE, amplitude.toString())
            it.setProperty(PERIOD, periodMs.toString())
            it.setProperty(LOOP_STARTED, nowElapsed.toString())
        }
    }

    /** One swing that dies away, over by the time it is asked about again. */
    fun shakeOnce(context: Context, untilElapsed: Long) {
        edit(context) { it.setProperty(IMPULSE_UNTIL, untilElapsed.toString()) }
    }

    /** Drops everything remembered about [packageName] — the other half of an uninstall. */
    fun forget(context: Context, packageName: String) {
        edit(context) { properties ->
            properties.stringPropertyNames()
                .filter { it.startsWith("$packageName/") }
                .forEach(properties::remove)
        }
    }

    /**
     * Forgets the file the device just deleted.
     *
     * [VirtualDeviceApps.resetOnStart] wipes the whole `vdevice` tree, this file with it, and the
     * copy held in memory here would otherwise be handed straight back to the first caller after the
     * wipe — a device advertised as empty, still answering with the last session's grants.
     */
    @Synchronized
    fun reset() {
        cached = null
        cachedAt = 0L
        checkedAt = 0L
        revision.intValue++
    }

    private fun key(packageName: String, name: String) = "$packageName/$name"

    /**
     * The policy as it is on disk *now*.
     *
     * Re-read whenever the file's timestamp has moved, which is what makes a change in the IDE
     * visible to the container across the process boundary. A missing file is an empty policy, not
     * an error: that is the state a freshly wiped device is in.
     */
    @Synchronized
    private fun read(context: Context): Properties {
        val now = SystemClock.elapsedRealtime()
        cached?.takeIf { now - checkedAt < RESTAT_MS }?.let { return it }
        checkedAt = now
        val file = file(context)
        val stamp = file.lastModified()
        cached?.takeIf { stamp == cachedAt }?.let { return it }
        val properties = Properties()
        if (stamp != 0L) {
            runCatching { file.inputStream().use(properties::load) }
                .onFailure { Log.w(TAG, "cannot read the device policy; treating it as empty", it) }
        }
        cached = properties
        cachedAt = stamp
        return properties
    }

    /**
     * Applies [change] and puts the whole file back atomically.
     *
     * Written to a sibling and renamed, so a reader in the other process either sees the policy
     * before this call or the policy after it, never a file caught half-written — and the rename is
     * also what moves the timestamp that tells it to look again.
     */
    @Synchronized
    private fun edit(context: Context, change: (Properties) -> Unit) {
        val properties = Properties()
        properties.putAll(read(context))
        change(properties)
        val file = file(context)
        val staged = File(file.parentFile, "${file.name}.new")
        runCatching {
            file.parentFile?.mkdirs()
            staged.outputStream().use { properties.store(it, "J Code virtual device") }
            if (!staged.renameTo(file)) throw VirtualDeviceException("cannot store the device policy")
            cached = properties
            cachedAt = file.lastModified()
            checkedAt = SystemClock.elapsedRealtime()
        }.onFailure {
            staged.delete()
            Log.w(TAG, "cannot write the device policy", it)
        }
        revision.intValue++
    }

    private fun file(context: Context): File =
        File(context.applicationContext.filesDir, "vdevice/$FILE")
}

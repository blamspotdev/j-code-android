package dev.jcode.vdevice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.PermissionInfo
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.io.File
import java.util.Properties

/**
 * What the virtual device's camera has in front of it.
 *
 * Deliberately a short list of *cheap* things rather than a scene description language. The camera
 * exists so an app that asks for a picture gets one it can decode; what is in the picture only has
 * to be recognisable, obviously synthetic, and inexpensive to produce.
 */
internal enum class CameraScene(val id: String, val label: String, val summary: String) {
    PixelArt(
        "pixelart",
        "Pixel art",
        "Five frames on a one-second loop. The default: enough movement to prove the picture is " +
            "live, at five frames a second rather than sixty.",
    ),
    Slideshow(
        "slideshow",
        "Three photos",
        "Three colour-bar stills, one a second — a camera pointed at something that changes " +
            "slowly.",
    ),
    Still(
        "still",
        "One photo",
        "A single still. Nothing ever redraws it, so the viewfinder costs nothing at all once it " +
            "is on screen.",
    ),
}

/** What one piece of the virtual device's hardware is wired to. A property of the *device*. */
internal enum class HardwareMode(val label: String) {
    /** The device does not have it: not declared, and no data. */
    Off("Off"),

    /** The device has one of its own, and it is not the phone's. */
    Simulated("Simulated"),

    /** The phone's, passed straight through. */
    Real("Real"),
}

/**
 * What one app may do with one permission it declares. A property of the *app*.
 *
 * The same three states a phone has, and they mean the same things. [Ask] is not a third answer to
 * `checkSelfPermission` — Android has only two — it is the state of not having decided, which reads
 * as denied until the app asks and the user says otherwise.
 */
internal enum class PermissionRule(val label: String) {
    Allow("Allow"),
    Deny("Deny"),
    Ask("Ask"),
}

/**
 * The hardware JCode's virtual device can be given, and what each piece is allowed to be.
 *
 * The asymmetry between these is not a matter of taste. A guest runs under JCode's uid and holds
 * JCode's permissions, so what the container can offer is bounded by what the *IDE* is allowed to
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
 *  - **The microphone is the only one whose Real needs something of JCode**, namely `RECORD_AUDIO`,
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
    /**
     * For a radio, whether a device that has it starts with it switched on — see
     * [VirtualDevicePolicy.switchedOn]. A phone ships with Wi-Fi and mobile data on and Bluetooth
     * off, and starting anywhere else would be a device nobody recognises.
     */
    val switchedOnByDefault: Boolean = true,
) {
    Camera(
        id = "camera",
        label = "Camera",
        summary = "Simulated gives the device a camera of its own, showing whichever scene is " +
            "chosen below. The phone's camera is never lent to a guest.",
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
        summary = "Simulated gives the device a microphone that records nothing. Real is the " +
            "phone's, and asks JCode for permission to record the first time you choose it.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated, HardwareMode.Real),
        fallback = HardwareMode.Off,
        permissions = listOf(Manifest.permission.RECORD_AUDIO),
        features = listOf(PackageManager.FEATURE_MICROPHONE),
    ),

    Location(
        id = "location",
        label = "Location",
        summary = "A fix you set, reported as GPS, and a route it can walk. The phone's own " +
            "location is never offered — an app on this device cannot learn where you are.",
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

    /**
     * Whether the device has Wi-Fi hardware.
     *
     * **This is the outer of two switches, and the distinction is the point.** Here is where a
     * device is given a radio or built without one — the same question this bench asks about a
     * camera. Whether that radio is *switched on* is a thing the device decides about itself, in its
     * own Settings app, exactly as a person switches Wi-Fi off on a phone that certainly still has
     * Wi-Fi. Collapsing the two would mean a device that loses its hardware when somebody toggles a
     * setting, which is not what either control means.
     *
     * There is no Real. The bytes an app moves are genuinely the phone's — this container has never
     * pretended otherwise — but the *answers* about the network are the device's, and handing over
     * the phone's Wi-Fi state would put a guest back in the position this exists to get it out of.
     */
    WiFi(
        id = "wifi",
        label = "Wi-Fi",
        summary = "Whether the device has Wi-Fi at all. Switching it on and off is done on the " +
            "device, in Settings — which is how to see what an app does offline without " +
            "disconnecting the phone you are working on.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated),
        fallback = HardwareMode.Simulated,
        // The feature follows the bench, because the bench now means "does the device have Wi-Fi"
        // — and a device built without it should say so, as it does for a camera. That has a sharp
        // consequence worth knowing: withdrawing FEATURE_WIFI makes getSystemService(WIFI_SERVICE)
        // return **null**. That is the platform's own behaviour on a phone with no Wi-Fi rather
        // than something this container invents, so it is faithful — but an app that assumes the
        // manager is non-null will fall over, and the honest place to say so is here.
        //
        // No permissions, though. ACCESS_NETWORK_STATE and INTERNET are not about Wi-Fi: a device
        // with no Wi-Fi and a mobile radio is still on the network, and withdrawing them would deny
        // install-time permissions to every app because one radio is missing.
        features = listOf(PackageManager.FEATURE_WIFI),
    ),

    Bluetooth(
        id = "bluetooth",
        label = "Bluetooth",
        summary = "Whether the device has a Bluetooth adapter at all. Switching it on and off is " +
            "done on the device, in Settings. The phone's own radio is never handed to a guest.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated),
        fallback = HardwareMode.Simulated,
        permissions = listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
        ),
        features = listOf(
            PackageManager.FEATURE_BLUETOOTH,
            PackageManager.FEATURE_BLUETOOTH_LE,
        ),
        // A phone ships with Bluetooth off, and a device that starts with it on is one nobody
        // recognises — and one whose "is Bluetooth on" path never gets exercised.
        switchedOnByDefault = false,
    ),

    /**
     * A mobile radio, which is the third thing an app asks the network about and the one the device
     * had no answer for at all.
     *
     * It earns its place by being *different from Wi-Fi in a way apps behave differently about*: a
     * cellular connection is metered, and an app that defers a large download, drops to a lower
     * bitrate, or asks before syncing is doing it because of that bit. With Wi-Fi switched off and
     * this switched on, the device is online **and metered**, which is a state that otherwise takes
     * a second phone and a SIM to reproduce.
     */
    Cellular(
        id = "cellular",
        label = "Cellular",
        summary = "Whether the device has a mobile radio. Switched on in the device's Settings, " +
            "where it reports a metered connection — which is the state an app treats differently " +
            "from Wi-Fi, and the hard one to get a real phone into on purpose.",
        modes = listOf(HardwareMode.Off, HardwareMode.Simulated),
        fallback = HardwareMode.Simulated,
        features = listOf(PackageManager.FEATURE_TELEPHONY),
    ),

    Accelerometer(
        id = "accelerometer",
        label = "Accelerometer",
        summary = "Simulated reports the attitude and motion set on this bench. Real is the " +
            "phone's, so an app feels every time you move it.",
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
        summary = "Simulated follows the heading set on this bench. Real is the phone's magnetometer.",
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
        summary = "Simulated turns only when a loop is turning the device. Real is the phone's.",
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
     * microphone is the phone's only once the user has let JCode record.
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
     * Not the same question as [realAvailable]. The microphone is offered even while JCode holds no
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
    private const val CAMERA_SCENE = "camera.scene"

    private const val LOCATION_MODE = "location.mode"
    private const val LATITUDE = "location.latitude"
    private const val LONGITUDE = "location.longitude"
    private const val TO_LATITUDE = "location.to.latitude"
    private const val TO_LONGITUDE = "location.to.longitude"
    private const val SPEED = "location.speed"
    private const val REPEAT = "location.repeat"
    private const val ROUTE_STARTED = "location.startedAt"
    private const val TRAIL = "location.trail"

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

    /**
     * What the device's [hardware] is wired to — its [VirtualHardware.fallback] until asked
     * otherwise.
     *
     * One answer for the whole device, not one per app: a phone has one camera and one GPS however
     * many apps read them, and what each *app* may do with them is a separate question with a
     * separate answer — see [rule].
     */
    fun mode(context: Context, hardware: VirtualHardware): HardwareMode {
        val stored = read(context).getProperty("hardware/${hardware.id}") ?: return hardware.fallback
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

    fun setMode(context: Context, hardware: VirtualHardware, mode: HardwareMode) {
        val had = mode(context, hardware) != HardwareMode.Off
        edit(context) { it.setProperty("hardware/${hardware.id}", mode.name) }
        // Whether the device *has* the hardware is the one half an app is told once and can never be
        // told again — see AppSandbox.restartForHardware. Simulated against Real is not that: it
        // changes what the readings are, and every seam that reports one answers live.
        if (hardware.features.isNotEmpty() && had != (mode != HardwareMode.Off)) {
            AppSandbox.restartForHardware()
        }
    }

    /**
     * What [packageName] may do with [permission].
     *
     * The default is the platform's own: a permission the platform asks for at runtime starts at
     * [PermissionRule.Ask], and everything else — the install-time ones an app never prompts for —
     * starts at [PermissionRule.Allow], because "ask" for a permission nothing ever asks about is a
     * state that could never be left.
     */
    fun rule(context: Context, packageName: String, permission: String): PermissionRule {
        val stored = read(context).getProperty(key(packageName, "perm/$permission"))
        return stored?.let { runCatching { PermissionRule.valueOf(it) }.getOrNull() }
            ?: defaultRule(context, permission)
    }

    fun setRule(context: Context, packageName: String, permission: String, rule: PermissionRule) {
        edit(context) { it.setProperty(key(packageName, "perm/$permission"), rule.name) }
    }

    /**
     * Whether a radio the device **has** is currently **switched on** — the inner of the two
     * switches, and the one that belongs to the device rather than to the bench.
     *
     * A phone with Wi-Fi switched off still has Wi-Fi. The bench answers "does this device have the
     * hardware", which is a thing you decide when you build a device; this answers "is it on", which
     * is a thing the device's own Settings decides afterwards, and which an app can watch change
     * while it runs. Two switches because they are two questions, and because collapsing them would
     * mean a device that loses its radio every time somebody turns it off.
     *
     * False whenever the bench says the device has no such hardware, so a caller only ever has to
     * ask this one thing.
     */
    fun switchedOn(context: Context, hardware: VirtualHardware): Boolean {
        if (mode(context, hardware) == HardwareMode.Off) return false
        val stored = read(context).getProperty("switch/${hardware.id}")
        return stored?.toBooleanStrictOrNull() ?: hardware.switchedOnByDefault
    }

    fun setSwitchedOn(context: Context, hardware: VirtualHardware, on: Boolean) {
        edit(context) { it.setProperty("switch/${hardware.id}", on.toString()) }
    }

    /**
     * What the device's camera shows — a property of the device, set here and read by its Camera app.
     *
     * The choice is a **cost** as much as a picture. A still is drawn once and never again, so a
     * viewfinder showing one runs at nothing; an animated scene redraws at its own few frames a
     * second rather than the display's sixty. The first version of this camera drew the whole
     * picture procedurally on every frame, which made it the most expensive thing on an otherwise
     * idle device.
     */
    fun cameraScene(context: Context): CameraScene {
        val stored = read(context).getProperty(CAMERA_SCENE) ?: return CameraScene.PixelArt
        return runCatching { CameraScene.valueOf(stored) }.getOrDefault(CameraScene.PixelArt)
    }

    fun setCameraScene(context: Context, scene: CameraScene) {
        edit(context) { it.setProperty(CAMERA_SCENE, scene.name) }
    }

    /**
     * Whether the platform treats [permission] as one to ask about at runtime.
     *
     * Asked of the *phone's* package manager, which is the authority on the platform's own
     * permissions and has never heard of a permission a guest declares itself. An unknown one is
     * treated as install-time, which is what a custom permission is.
     */
    fun dangerous(context: Context, permission: String): Boolean = runCatching {
        val info = context.applicationContext.packageManager.getPermissionInfo(permission, 0)
        info.protection == PermissionInfo.PROTECTION_DANGEROUS
    }.getOrDefault(false)

    private fun defaultRule(context: Context, permission: String): PermissionRule =
        if (dangerous(context, permission)) PermissionRule.Ask else PermissionRule.Allow

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
            trailId = stored.getProperty(TRAIL) ?: TRAILS.first().id,
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

    /** Which of [TRAILS] the device walks when it is following one. */
    fun setTrail(context: Context, trailId: String) {
        edit(context) { it.setProperty(TRAIL, trailId) }
    }

    /**
     * Starts or stops the device moving, on [mode] — a straight line between two points, or one of
     * the trails. Starting stamps the clock it is measured from: the position is a function of how
     * long ago this happened, so this *is* the moving.
     */
    fun setMoving(context: Context, mode: LocationMode, nowElapsed: Long) {
        edit(context) {
            it.setProperty(LOCATION_MODE, mode.name)
            val moving = mode != LocationMode.Fixed
            it.setProperty(ROUTE_STARTED, (if (moving) nowElapsed else 0L).toString())
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
            staged.outputStream().use { properties.store(it, "JCode virtual device") }
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
        VirtualDeviceFiles.file(context, FILE)
}

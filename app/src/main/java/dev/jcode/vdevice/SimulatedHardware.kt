package dev.jcode.vdevice

import android.content.Context
import android.hardware.SensorManager
import android.os.SystemClock
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/** Gravity at the surface — what a device lying still on a table reads. */
private const val GRAVITY = SensorManager.GRAVITY_EARTH

/**
 * The geomagnetic field the simulated device sits in, in µT: nothing to the east, the horizontal
 * component pointing north, the dip angle pulling the rest downwards. Apps derive a heading from the
 * ratio between these, so the numbers have to be consistent with each other rather than merely
 * non-zero — and with the attitude, which is what makes a simulated compass turn.
 */
private const val FIELD_NORTH = 30f
private const val FIELD_DOWN = -40f

/** Metres per degree of latitude, near enough for a route a few kilometres long. */
private const val METRES_PER_DEGREE = 111_320.0

/** How long "Shake once" shakes for, and how many times it swings while it does. */
private const val IMPULSE_MS = 700L
private const val IMPULSE_SWINGS = 5

/** What the device's simulated GPS is doing. */
internal enum class LocationMode { Fixed, Route }

/** What a route does when it reaches the far end. */
internal enum class RouteRepeat(val label: String) {
    Once("Stop there"),
    Loop("Start over"),
    PingPong("Turn around"),
}

/** A repeating motion laid over the device's resting attitude. */
internal enum class MotionLoop(
    val label: String,
    /** What [HardwareSettings.amplitude] means for this loop, or null when it takes none. */
    val amplitudeLabel: String?,
    val defaultAmplitude: Float,
    val defaultPeriodMs: Long,
) {
    None("None", null, 0f, 0L),
    Shake("Shake", "Acceleration (m/s²)", 6f, 400L),
    Bounce("Bounce", "Acceleration (m/s²)", 6f, 600L),
    Tilt("Tilt", "Angle (°)", 20f, 2_000L),
    Spin("Spin", null, 0f, 4_000L),
}

/**
 * Everything the device's simulated hardware is set to — the *description*, not the values.
 *
 * This is what crosses between the two processes, and it is deliberately small and slow-changing:
 * a route is four coordinates, a speed and a start time, not a stream of positions. Both sides
 * evaluate it against [SystemClock.elapsedRealtime], which counts from the same boot in every
 * process on the device, so the tab's live readout and the guest's sensors agree without anything
 * being sent between them.
 */
internal class HardwareSettings(
    val locationMode: LocationMode,
    val latitude: Double,
    val longitude: Double,
    val toLatitude: Double,
    val toLongitude: Double,
    val speedMps: Float,
    val repeat: RouteRepeat,
    val routeStartedAt: Long,
    val pitch: Float,
    val roll: Float,
    val azimuth: Float,
    val loop: MotionLoop,
    val amplitude: Float,
    val periodMs: Long,
    val loopStartedAt: Long,
    val impulseUntil: Long,
)

/** What the device reports at one instant, derived from [HardwareSettings] and a clock reading. */
internal class HardwareSample(
    val latitude: Double,
    val longitude: Double,
    val bearing: Float,
    val speedMps: Float,
    /** False once a one-shot route has arrived — a parked car, not a moving one. */
    val moving: Boolean,
    /** What the accelerometer reads: gravity as the attitude tilts it, plus whatever is shaking it. */
    val accelerometer: FloatArray,
    /** Gravity alone — `TYPE_GRAVITY`, and what `TYPE_LINEAR_ACCELERATION` is the difference from. */
    val gravity: FloatArray,
    val magnetic: FloatArray,
    val gyroscope: FloatArray,
    /** Azimuth, pitch and roll in **degrees**, which is what `TYPE_ORIENTATION` reports. */
    val orientation: FloatArray,
    /** x, y, z, w, accuracy — the shape `TYPE_ROTATION_VECTOR` delivers. */
    val rotationVector: FloatArray,
)

/**
 * The device's simulated hardware: what it is set to, what it reads right now, and the tab that
 * lets a person change it.
 *
 * ### Why this is a function of time rather than a stream
 *
 * A moving location and a shaking accelerometer are values that change tens of times a second, and
 * the two things that need them are in different processes — the guest's `SensorManager` in
 * `:guest`, the live readout in the IDE. Sending the values would mean an IPC per sample, a policy
 * file rewritten at 50 Hz, and two readers that disagree whenever one is behind.
 *
 * So nothing is sent. The *description* is written once — "walk from here to there at 14 m/s,
 * starting at this clock reading" — and both sides work out where that puts the device now.
 * `elapsedRealtime` is the same number in every process, so the two answers are identical by
 * construction rather than by synchronisation, and a route that has been running for an hour costs
 * exactly what one that just started costs.
 */
internal object SimulatedHardware {

    /** Bumped on each open request so the shell can surface the tab — see [AppSandbox.revealSignal]. */
    val revealSignal = mutableIntStateOf(0)

    fun requestOpen() {
        revealSignal.intValue++
    }

    /** What the device reads now. */
    fun sample(context: Context): HardwareSample =
        sample(VirtualDevicePolicy.hardware(context), SystemClock.elapsedRealtime())

    /**
     * What [settings] describes at [nowElapsed].
     *
     * Pure, apart from its arguments, which is the whole point: the same two inputs produce the same
     * reading in the IDE and in `:guest`.
     */
    fun sample(settings: HardwareSettings, nowElapsed: Long): HardwareSample {
        val place = place(settings, nowElapsed)
        val motion = motion(settings, nowElapsed)
        val world = rotation(motion.azimuth, motion.pitch, motion.roll)
        val gravity = world.apply(0f, 0f, GRAVITY)
        val accelerometer = floatArrayOf(
            gravity[0] + motion.shakeX,
            gravity[1],
            gravity[2] + motion.shakeZ,
        )
        return HardwareSample(
            latitude = place.latitude,
            longitude = place.longitude,
            bearing = place.bearing,
            speedMps = place.speed,
            moving = place.speed > 0f,
            accelerometer = accelerometer,
            gravity = gravity,
            magnetic = world.apply(0f, FIELD_NORTH, FIELD_DOWN),
            gyroscope = floatArrayOf(motion.rateX, 0f, motion.rateZ),
            orientation = floatArrayOf(normalise(motion.azimuth), motion.pitch, motion.roll),
            rotationVector = quaternion(world),
        )
    }

    // --------------------------------------------------------------------------------- where it is

    private class Place(
        val latitude: Double,
        val longitude: Double,
        val bearing: Float,
        val speed: Float,
    )

    /**
     * Where the device is along its route, or the fixed point it is parked on.
     *
     * The interpolation is linear in latitude and longitude, with longitude degrees scaled by the
     * latitude so that a route reads the right length and bearing. That is a flat-earth
     * approximation, and it is the right one here: a simulated route is tens of kilometres at most,
     * where the error is metres, and a great circle would make "half way" mean something a person
     * reading two coordinates off the screen would not recognise.
     */
    private fun place(settings: HardwareSettings, nowElapsed: Long): Place {
        if (settings.locationMode == LocationMode.Fixed || settings.routeStartedAt <= 0L) {
            return Place(settings.latitude, settings.longitude, 0f, 0f)
        }
        val metres = distance(settings.latitude, settings.longitude, settings.toLatitude, settings.toLongitude)
        val speed = max(0.1f, settings.speedMps)
        val seconds = metres / speed
        if (seconds <= 0.0) return Place(settings.toLatitude, settings.toLongitude, 0f, 0f)

        val elapsed = (nowElapsed - settings.routeStartedAt).coerceAtLeast(0L) / 1000.0
        val laps = elapsed / seconds
        val (progress, forwards) = when (settings.repeat) {
            RouteRepeat.Once -> laps.coerceIn(0.0, 1.0) to true
            RouteRepeat.Loop -> (laps % 1.0) to true
            // Two laps make a there-and-back, so the second half of each pair runs in reverse.
            RouteRepeat.PingPong -> {
                val cycle = laps % 2.0
                if (cycle <= 1.0) cycle to true else (2.0 - cycle) to false
            }
        }
        val arrived = settings.repeat == RouteRepeat.Once && laps >= 1.0
        val bearing = bearing(settings, forwards)
        return Place(
            latitude = settings.latitude + (settings.toLatitude - settings.latitude) * progress,
            longitude = settings.longitude + (settings.toLongitude - settings.longitude) * progress,
            bearing = bearing,
            speed = if (arrived) 0f else speed,
        )
    }

    /** Metres between two coordinates, flat-earth — see [place]. */
    fun distance(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double {
        val north = (toLat - fromLat) * METRES_PER_DEGREE
        val east = (toLon - fromLon) * METRES_PER_DEGREE * cos(Math.toRadians((fromLat + toLat) / 2))
        return sqrt(north * north + east * east)
    }

    /** Degrees clockwise from north, which is what `Location.bearing` reports. */
    private fun bearing(settings: HardwareSettings, forwards: Boolean): Float {
        val fromLat = if (forwards) settings.latitude else settings.toLatitude
        val fromLon = if (forwards) settings.longitude else settings.toLongitude
        val toLat = if (forwards) settings.toLatitude else settings.latitude
        val toLon = if (forwards) settings.toLongitude else settings.longitude
        val north = toLat - fromLat
        val east = (toLon - fromLon) * cos(Math.toRadians((fromLat + toLat) / 2))
        if (north == 0.0 && east == 0.0) return 0f
        return normalise(Math.toDegrees(atan2(east, north)).toFloat())
    }

    // ------------------------------------------------------------------------------- how it is held

    private data class Motion(
        val azimuth: Float,
        val pitch: Float,
        val roll: Float,
        /** Linear acceleration added to the resting reading, in the device's own axes. */
        val shakeX: Float,
        val shakeZ: Float,
        /** Angular rate in rad/s, which is what the gyroscope reports. */
        val rateX: Float,
        val rateZ: Float,
    )

    /**
     * The attitude the device is in at [nowElapsed], and whatever the running loop is adding to it.
     *
     * A loop is a phase, not a position: `sin` of the elapsed time over the period. Nothing
     * accumulates, so a loop that has been running since the app started is in exactly the state a
     * loop started a moment ago would be — except [MotionLoop.Spin], where accumulating *is* the
     * behaviour.
     */
    private fun motion(settings: HardwareSettings, nowElapsed: Long): Motion {
        val resting = Motion(settings.azimuth, settings.pitch, settings.roll, 0f, 0f, 0f, 0f)
        val impulse = impulse(settings, nowElapsed)
        val period = settings.periodMs.coerceAtLeast(50L)
        val elapsed = (nowElapsed - settings.loopStartedAt).coerceAtLeast(0L)
        val phase = 2.0 * Math.PI * elapsed / period
        // Radians per second at the fastest point of the swing, for the gyroscope.
        val rate = (2.0 * Math.PI / (period / 1000.0)).toFloat()

        return when (settings.loop) {
            MotionLoop.None -> resting.plusShake(impulse)
            MotionLoop.Shake -> resting.copy(shakeX = settings.amplitude * sin(phase).toFloat())
                .plusShake(impulse)
            MotionLoop.Bounce -> resting.copy(shakeZ = settings.amplitude * sin(phase).toFloat())
                .plusShake(impulse)
            MotionLoop.Tilt -> {
                val swing = settings.amplitude * sin(phase).toFloat()
                resting.copy(
                    pitch = settings.pitch + swing,
                    // The derivative of the swing: fastest as it passes through the middle.
                    rateX = Math.toRadians((settings.amplitude * cos(phase)).toDouble()).toFloat() * rate,
                ).plusShake(impulse)
            }
            // Turning clockwise — east, then south — is a *negative* rotation about the device's own
            // Z axis, which points out of its screen.
            MotionLoop.Spin -> resting.copy(
                azimuth = settings.azimuth + 360f * (elapsed.toFloat() / period),
                rateZ = -rate,
            ).plusShake(impulse)
        }
    }

    /**
     * "Shake once": a swing that dies away, so the device ends up back where it was rather than
     * stopping mid-air. Zero once it is over, which is what makes it a one-shot rather than a mode.
     */
    private fun impulse(settings: HardwareSettings, nowElapsed: Long): Float {
        val remaining = settings.impulseUntil - nowElapsed
        if (remaining <= 0L || remaining > IMPULSE_MS) return 0f
        val progress = 1.0 - remaining.toDouble() / IMPULSE_MS
        val decay = exp(-3.0 * progress)
        return (GRAVITY * decay * sin(2.0 * Math.PI * IMPULSE_SWINGS * progress)).toFloat()
    }

    private fun Motion.plusShake(impulse: Float) =
        if (impulse == 0f) this else copy(shakeX = shakeX + impulse)

    // ------------------------------------------------------------------------------------ the maths

    /**
     * The world→device rotation for one attitude, row-major.
     *
     * Composed the way Android's own `getOrientation` decomposes one: heading about the world's
     * vertical, then pitch about the device's X, then roll about its Y. Applying it to "up" gives
     * what the accelerometer reads at rest, and to the geomagnetic field what the magnetometer
     * reads — which is what makes a simulated compass turn when the heading is changed, rather than
     * the two disagreeing about which way the device is facing.
     */
    private fun rotation(azimuthDeg: Float, pitchDeg: Float, rollDeg: Float): FloatArray {
        val a = Math.toRadians(azimuthDeg.toDouble())
        val p = Math.toRadians(pitchDeg.toDouble())
        val r = Math.toRadians(rollDeg.toDouble())
        val heading = floatArrayOf(
            cos(a).toFloat(), sin(a).toFloat(), 0f,
            -sin(a).toFloat(), cos(a).toFloat(), 0f,
            0f, 0f, 1f,
        )
        val tilt = floatArrayOf(
            1f, 0f, 0f,
            0f, cos(p).toFloat(), -sin(p).toFloat(),
            0f, sin(p).toFloat(), cos(p).toFloat(),
        )
        val lean = floatArrayOf(
            cos(r).toFloat(), 0f, -sin(r).toFloat(),
            0f, 1f, 0f,
            sin(r).toFloat(), 0f, cos(r).toFloat(),
        )
        return lean.times(tilt.times(heading))
    }

    private fun FloatArray.times(other: FloatArray): FloatArray {
        val out = FloatArray(9)
        for (row in 0..2) {
            for (column in 0..2) {
                var sum = 0f
                for (k in 0..2) sum += this[row * 3 + k] * other[k * 3 + column]
                out[row * 3 + column] = sum
            }
        }
        return out
    }

    private fun FloatArray.apply(x: Float, y: Float, z: Float): FloatArray = floatArrayOf(
        this[0] * x + this[1] * y + this[2] * z,
        this[3] * x + this[4] * y + this[5] * z,
        this[6] * x + this[7] * y + this[8] * z,
    )

    /**
     * The attitude as a rotation vector — the device→world rotation as a quaternion, which is what
     * `TYPE_ROTATION_VECTOR` carries and what a game engine reads instead of the accelerometer.
     *
     * Read off the same matrix the other two sensors are derived from rather than composed again
     * from the angles. Two derivations of one rotation are two chances to pick a different sign
     * convention, and an app that trusts the rotation vector would then disagree with an app that
     * trusts gravity about which way the same device is pointing.
     */
    private fun quaternion(world: FloatArray): FloatArray {
        // Transposed: the rotation vector describes device→world, and `world` is world→device.
        val m = floatArrayOf(
            world[0], world[3], world[6],
            world[1], world[4], world[7],
            world[2], world[5], world[8],
        )
        val trace = m[0] + m[4] + m[8]
        val q = FloatArray(4)
        when {
            trace > 0f -> {
                val s = sqrt(trace + 1f) * 2f
                q[3] = 0.25f * s
                q[0] = (m[7] - m[5]) / s
                q[1] = (m[2] - m[6]) / s
                q[2] = (m[3] - m[1]) / s
            }
            m[0] > m[4] && m[0] > m[8] -> {
                val s = sqrt(1f + m[0] - m[4] - m[8]) * 2f
                q[3] = (m[7] - m[5]) / s
                q[0] = 0.25f * s
                q[1] = (m[1] + m[3]) / s
                q[2] = (m[2] + m[6]) / s
            }
            m[4] > m[8] -> {
                val s = sqrt(1f + m[4] - m[0] - m[8]) * 2f
                q[3] = (m[2] - m[6]) / s
                q[0] = (m[1] + m[3]) / s
                q[1] = 0.25f * s
                q[2] = (m[5] + m[7]) / s
            }
            else -> {
                val s = sqrt(1f + m[8] - m[0] - m[4]) * 2f
                q[3] = (m[3] - m[1]) / s
                q[0] = (m[2] + m[6]) / s
                q[1] = (m[5] + m[7]) / s
                q[2] = 0.25f * s
            }
        }
        return floatArrayOf(q[0], q[1], q[2], q[3], 0f)
    }

    /** Degrees folded back into 0…360, the range a heading is read in. */
    fun normalise(degrees: Float): Float {
        val wrapped = degrees % 360f
        return if (wrapped < 0f) wrapped + 360f else wrapped
    }
}

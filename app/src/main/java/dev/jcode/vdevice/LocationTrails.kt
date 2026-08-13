package dev.jcode.vdevice

import kotlin.math.atan2
import kotlin.math.cos

/** One point on a trail, in degrees. */
internal class TrailPoint(val latitude: Double, val longitude: Double)

/** Where the device is on a trail, and which way it is going. */
internal class TrailFix(val latitude: Double, val longitude: Double, val bearing: Float)

/**
 * A path the device can be walked along, instead of the straight line between two typed points.
 *
 * ### These are sketches, and that is deliberate
 *
 * Every trail here is **hand-drawn, simplified and displaced**. A dozen or two points stand in for a
 * road that has hundreds; the whole shape is shifted a few hundred metres from where the real one
 * is; and [headingSkew] puts the reported compass a few degrees off the true bearing of travel.
 *
 * The reason is not subtlety. A tool that replays a faithful GPS trace of a real street, at a
 * realistic speed, with a matching compass, is a tool for making a fake journey look like a real one
 * — and that is worth nothing to the person testing a maps app and quite a lot to somebody
 * fabricating a delivery, a run, or an alibi. So the repository simply does not contain a faithful
 * trace to replay: the offsets are written down below rather than hidden, because the protection is
 * that there is no accurate data underneath them, not that the numbers are a secret.
 *
 * The outer guarantee is stronger still and belongs to the device rather than to this file: a
 * simulated fix is only ever answered to a guest inside JCode's virtual device. It does not mock
 * the phone's own location, no app outside the IDE can see it, and it is wiped on every restart.
 *
 * What survives the alteration is everything a developer actually needs — a plausible speed, a
 * plausible shape, and the heading changes each kind of road produces.
 */
internal class LocationTrail(
    val id: String,
    val name: String,
    val place: String,
    /** What this one is *for*: the shape of heading change it produces. */
    val summary: String,
    /** Degrees added to the bearing of travel before it is reported. */
    val headingSkew: Float,
    val points: List<TrailPoint>,
) {

    /** Metres along each segment, and the whole. Computed once: a trail never changes shape. */
    private val legs: List<Double> by lazy {
        points.zipWithNext { from, to ->
            SimulatedHardware.distance(from.latitude, from.longitude, to.latitude, to.longitude)
        }
    }

    val length: Double by lazy { legs.sum() }

    /**
     * Where [metres] along the trail puts the device, and the bearing it is travelling on.
     *
     * The bearing is the *segment's*, not a smoothed curve: a road that turns a corner turns it all
     * at once, which is what a compass on a real corner does and what an app that reacts to heading
     * needs to be tested against.
     */
    fun at(metres: Double): TrailFix {
        if (points.size < 2 || length <= 0.0) {
            val only = points.firstOrNull() ?: TrailPoint(0.0, 0.0)
            return TrailFix(only.latitude, only.longitude, 0f)
        }
        var remaining = metres.coerceIn(0.0, length)
        legs.forEachIndexed { index, leg ->
            if (remaining <= leg || index == legs.lastIndex) {
                val from = points[index]
                val to = points[index + 1]
                val progress = if (leg <= 0.0) 0.0 else (remaining / leg).coerceIn(0.0, 1.0)
                return TrailFix(
                    latitude = from.latitude + (to.latitude - from.latitude) * progress,
                    longitude = from.longitude + (to.longitude - from.longitude) * progress,
                    bearing = bearing(from, to),
                )
            }
            remaining -= leg
        }
        val last = points.last()
        return TrailFix(last.latitude, last.longitude, 0f)
    }

    private fun bearing(from: TrailPoint, to: TrailPoint): Float {
        val north = to.latitude - from.latitude
        val east = (to.longitude - from.longitude) *
            cos(Math.toRadians((from.latitude + to.latitude) / 2))
        if (north == 0.0 && east == 0.0) return 0f
        return SimulatedHardware.normalise(Math.toDegrees(atan2(east, north)).toFloat())
    }

    companion object {
        fun byId(id: String): LocationTrail? = TRAILS.firstOrNull { it.id == id }
    }
}

/** Builds a point list from latitude/longitude pairs, so the tables below read as coordinates. */
private fun trail(vararg degrees: Double): List<TrailPoint> =
    degrees.toList().chunked(2).map { TrailPoint(it[0], it[1]) }

/**
 * The trails the device ships with.
 *
 * Three, chosen for the three shapes of heading change a location app has to survive rather than for
 * scenery: a coast that turns continuously, a grid that turns in right angles, and switchbacks that
 * reverse. They also sit in three latitude bands and three time zones, which is the other thing worth
 * testing — a degree of longitude is 110 km at the equator and half that in Norway, and code that
 * treats the two the same only shows it up north.
 *
 * Each is displaced from the real place; see [LocationTrail].
 */
internal val TRAILS = listOf(
    LocationTrail(
        id = "dipolog-boulevard",
        name = "Sunset Boulevard",
        place = "Dipolog City, Philippines",
        summary = "A 3.5 km seafront that curves the whole way — the heading drifts a few degrees at " +
            "a time and never holds still, which is the hardest case for a compass that smooths.",
        // Displaced roughly 450 m seaward, which puts the whole trail in Dipolog Bay.
        headingSkew = 6f,
        points = trail(
            8.60820, 123.33200,
            8.60560, 123.33245,
            8.60290, 123.33310,
            8.60010, 123.33355,
            8.59730, 123.33375,
            8.59450, 123.33370,
            8.59170, 123.33345,
            8.58890, 123.33330,
            8.58610, 123.33345,
            8.58330, 123.33395,
            8.58050, 123.33480,
            8.57800, 123.33600,
            8.57620, 123.33720,
        ),
    ),
    LocationTrail(
        id = "eixample-grid",
        name = "The grid",
        place = "Eixample, Barcelona",
        summary = "A staircase across a grid set 45° off north: eight 230 m runs, each turning 90° " +
            "at the end. The heading jumps rather than sweeps, and settles between jumps.",
        // Displaced about 600 m south-west, onto blocks the route never actually crosses.
        headingSkew = -4f,
        points = trail(
            41.38600, 2.15800,
            41.38746, 2.15995,
            41.38600, 2.16190,
            41.38746, 2.16385,
            41.38600, 2.16580,
            41.38746, 2.16775,
            41.38600, 2.16970,
            41.38746, 2.17165,
            41.38600, 2.17360,
        ),
    ),
    LocationTrail(
        id = "trollstigen-hairpins",
        name = "The hairpins",
        place = "Trollstigen, Norway",
        summary = "Eight switchbacks, each reversing the heading by about 180°. At 62° north a " +
            "degree of longitude is half its width at the equator, which is where flat-earth " +
            "distance maths shows itself.",
        // Displaced about 700 m down the valley, off the road entirely.
        headingSkew = 9f,
        points = trail(
            62.45200, 7.66300,
            62.45260, 7.66950,
            62.45330, 7.66320,
            62.45400, 7.66980,
            62.45470, 7.66350,
            62.45540, 7.67010,
            62.45610, 7.66380,
            62.45680, 7.67040,
            62.45750, 7.66410,
        ),
    ),
)

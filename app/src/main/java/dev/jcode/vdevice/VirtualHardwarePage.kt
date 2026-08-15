package dev.jcode.vdevice

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.jcode.design.CompactFilledButton
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.ManagerFilterChip
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.ManagerSummaryRow
import dev.jcode.design.SettingsDropdownRow
import dev.jcode.design.SettingsTextFieldRow
import java.util.Locale
import kotlin.math.cos
import kotlinx.coroutines.delay

/** How often the readout re-reads the device. Fast enough to look live, slow enough to be free. */
private const val READOUT_MS = 150L

/** Two columns of tiles, which is what fits a phone in portrait without shrinking the labels. */
private const val TILE_COLUMNS = 2
private val TILE_HEIGHT = 82.dp

/** Tall enough for a trail to have a shape, short enough to leave its controls on the screen. */
private val MAP_HEIGHT = 200.dp
private val MAP_PADDING = 16.dp

/** An attitude worth reaching in one tap, as pitch and roll in degrees. */
private class Pose(val label: String, val pitch: Float, val roll: Float)

/**
 * The five attitudes worth a tap, named after the accelerometer readings they produce: flat is
 * (0, 0, g), upright is (0, g, 0), the two landscapes are (±g, 0, 0), and face down is (0, 0, −g).
 *
 * Two things about landscape. It is a *roll*, not a pitched-and-rolled upright — at a pitch of ±90°
 * the two rotations fall onto the same axis and the second one does nothing. And the arrow says
 * which way the top of the device points: the reading (+g, 0, 0) means the device's X axis, which
 * runs to the right of the screen, is pointing at the sky — so the screen's right edge is up and its
 * top edge is to the left.
 */
private val POSES = listOf(
    Pose("Flat", 0f, 0f),
    Pose("Upright", -90f, 0f),
    Pose("Landscape ◀", 0f, -90f),
    Pose("Landscape ▶", 0f, 90f),
    Pose("Face down", 0f, 180f),
)

/**
 * The virtual device's hardware bench: what the device has, and what it is doing.
 *
 * Opens on a grid of what the device is made of — one tile per piece of hardware, each showing what
 * it is wired to and what it is reporting — and each tile opens onto that piece's own controls. That
 * shape is the point: the six Off/Simulated/Real choices are properties of the *device*, and putting
 * them anywhere else made them look like properties of an app.
 *
 * A tab of its own rather than more of the device's own screen, for the reason every other piece of
 * JCode chrome is: what `screencap` answers with has to be the device, and a control panel drawn
 * over it would read as something the guest put there.
 *
 * What each *app* is allowed to do with any of this is the other half, in Manage permissions. Both
 * are required — an app cannot be given a camera the device does not have.
 */
@Composable
internal fun VirtualHardwarePage(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val revision = VirtualDevicePolicy.revision.intValue
    val settings = remember(revision) { VirtualDevicePolicy.hardware(context) }
    var opened by remember { mutableStateOf<VirtualHardware?>(null) }

    // The readout is computed, not received: the same function of the same clock the guest's own
    // sensors are running, so what this shows is what the app is being told — see SimulatedHardware.
    var now by remember { mutableStateOf(SimulatedHardware.sample(context)) }
    // Keyed on the revision, and it stops as soon as the device stops moving: a bench showing a
    // device that is sitting still is showing a constant, and re-deriving a constant several times a
    // second for as long as the tab is open is the shape of "hardware that never sleeps". Any edit
    // bumps the revision and starts it again, which is also how a shake or a route gets its ticks.
    LaunchedEffect(revision) {
        while (SimulatedHardware.changing(settings, SystemClock.elapsedRealtime())) {
            delay(READOUT_MS)
            now = SimulatedHardware.sample(context)
        }
        now = SimulatedHardware.sample(context)
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(opened = opened, onBack = { opened = null })
            val travelling = settings.locationMode != LocationMode.Fixed && settings.routeStartedAt > 0L
            when (val hardware = opened) {
                null -> HardwareGrid(revision = revision, now = now) { opened = it }
                VirtualHardware.Location -> {
                    Mode(hardware)
                    Inert(hardware, revision)
                    LocationTools(settings = settings, now = now)
                }
                VirtualHardware.Accelerometer,
                VirtualHardware.Compass,
                VirtualHardware.Gyroscope,
                -> {
                    Mode(hardware)
                    Inert(hardware, revision)
                    Mixed(revision)
                    MotionTools(settings = settings, travelling = travelling)
                    SensorReadout(hardware = hardware, now = now)
                }
                else -> {
                    Mode(hardware)
                    Inert(hardware, revision)
                }
            }
        }
    }
}

@Composable
private fun Header(opened: VirtualHardware?, onBack: () -> Unit) {
    Row(
        // Top-aligned: centring puts the arrow beside the middle of a three-line description rather
        // than beside the title it goes back from.
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (opened != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
                Icon(
                    Icons.AutoMirrored.Rounded.ArrowBack,
                    contentDescription = "Back to the device's hardware",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp),
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = opened?.label ?: "Hardware",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = opened?.summary
                    ?: "What ${VirtualIdentity.MODEL} is made of, and what it is doing. One setting " +
                    "for the device — what each app may do with it is in Manage permissions.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The device, as the things it is made of. */
@Composable
private fun HardwareGrid(revision: Int, now: HardwareSample, onOpen: (VirtualHardware) -> Unit) {
    val context = LocalContext.current
    // Weights rather than a width fraction: two halves plus the gap between them is wider than the
    // row, so a fraction wraps every tile onto a line of its own.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        maxItemsInEachRow = TILE_COLUMNS,
    ) {
        VirtualHardware.entries.forEach { hardware ->
            val mode = remember(revision, hardware) { VirtualDevicePolicy.mode(context, hardware) }
            Tile(
                hardware = hardware,
                mode = mode,
                detail = detail(hardware, mode, now),
                modifier = Modifier.weight(1f),
                onClick = { onOpen(hardware) },
            )
        }
    }
}

@Composable
private fun Tile(
    hardware: VirtualHardware,
    mode: HardwareMode,
    detail: String?,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        modifier = modifier
            .height(TILE_HEIGHT)
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = if (mode == HardwareMode.Off) 0.10f else 0.22f),
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = hardware.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = mode.label,
                style = MaterialTheme.typography.labelMedium,
                color = if (mode == HardwareMode.Off) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.primary
                },
            )
            detail?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** What a tile can say about itself beyond its mode — the reading, where there is one worth having. */
private fun detail(hardware: VirtualHardware, mode: HardwareMode, now: HardwareSample): String? =
    when {
        mode == HardwareMode.Off -> null
        hardware == VirtualHardware.Location ->
            "%.4f, %.4f".format(Locale.US, now.latitude, now.longitude) +
                if (now.moving) " · moving" else ""
        hardware == VirtualHardware.Accelerometer && mode == HardwareMode.Simulated ->
            "%+.1f, %+.1f, %+.1f".format(Locale.US, now.accelerometer[0], now.accelerometer[1], now.accelerometer[2])
        hardware == VirtualHardware.Compass && mode == HardwareMode.Simulated ->
            "%.0f° from north".format(Locale.US, now.orientation[0])
        hardware == VirtualHardware.Gyroscope && mode == HardwareMode.Simulated ->
            "%+.2f rad/s".format(Locale.US, now.gyroscope[2])
        mode == HardwareMode.Real -> "the phone's"
        else -> null
    }

/**
 * What one piece of hardware is wired to.
 *
 * The microphone is the only choice here that is not ours to make: real means the phone's, so the
 * user is asked for `RECORD_AUDIO` before the device is pointed at it, and a refusal leaves the
 * setting alone rather than half-applied.
 */
@Composable
private fun Mode(hardware: VirtualHardware) {
    val context = LocalContext.current
    val revision = VirtualDevicePolicy.revision.intValue
    val mode = remember(revision, hardware) { VirtualDevicePolicy.mode(context, hardware) }
    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) VirtualDevicePolicy.setMode(context, VirtualHardware.Microphone, HardwareMode.Real)
    }

    ManagerSectionCard(
        title = "Wired to",
        description = "Off means the device does not have it at all: not declared, and refused to " +
            "every app whatever its permissions say. An app is told what hardware a device has when " +
            "it starts and never again, so switching this on or off restarts the device.",
    ) {
        SettingsDropdownRow(
            // No supporting text: the header above has just said what this is, and saying it twice
            // on a screen this short reads as two different things that happen to match.
            label = hardware.label,
            options = hardware.modes
                .filter { it != HardwareMode.Real || hardware.realOffered(context) }
                .map { it.name },
            selected = mode.name,
            optionLabel = { HardwareMode.valueOf(it).label },
            onSelect = { chosen ->
                val next = HardwareMode.valueOf(chosen)
                if (hardware == VirtualHardware.Microphone &&
                    next == HardwareMode.Real &&
                    !hardware.realAvailable(context)
                ) {
                    microphone.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    VirtualDevicePolicy.setMode(context, hardware, next)
                }
            },
        )
    }

    if (hardware == VirtualHardware.Camera && mode != HardwareMode.Off) {
        CameraSceneCard(revision = revision)
    }
}

/**
 * What the device's camera is pointed at.
 *
 * A choice about cost as much as about the picture. The first version of the camera drew its whole
 * scene procedurally on every frame — colour bars, a horizon computed from the attitude, a compass
 * rose and a line of readouts — which made it the most expensive thing on an otherwise idle device,
 * to show numbers nobody reads off a viewfinder. A scene is a handful of frames now, and a still one
 * is drawn once and never again.
 */
@Composable
private fun CameraSceneCard(revision: Int) {
    val context = LocalContext.current
    val scene = remember(revision) { VirtualDevicePolicy.cameraScene(context) }
    ManagerSectionCard(
        title = "What it sees",
        description = scene.summary,
    ) {
        SettingsDropdownRow(
            label = "Scene",
            options = CameraScene.entries.map { it.name },
            selected = scene.name,
            optionLabel = { CameraScene.valueOf(it).label },
            onSelect = { VirtualDevicePolicy.setCameraScene(context, CameraScene.valueOf(it)) },
        )
    }
}

/**
 * Says when the tools below are running into a device that does not have the hardware.
 *
 * They keep working, and the readouts stay honest — what they report is what an app *would* be told.
 * It is simply that no app is being told it. Without this the two settings look like they disagree:
 * a route visibly walking a map while every app on the device is refused a fix.
 */
@Composable
private fun Inert(hardware: VirtualHardware, revision: Int) {
    val context = LocalContext.current
    val mode = remember(revision, hardware) { VirtualDevicePolicy.mode(context, hardware) }
    if (mode != HardwareMode.Off) return
    ManagerNoticeCard(
        title = "The device has no ${hardware.label.lowercase()}",
        message = "Everything below still runs and the readouts are what an app would be told — but " +
            "no app is being told it, because this is switched off above. Nothing here reaches a " +
            "guest until it is on.",
    )
}

/**
 * Says when the three motion sensors are not wired the same way.
 *
 * They are three views of one attitude, and an app is entitled to treat them that way:
 * `getRotationMatrix` takes gravity *and* the magnetic field together and derives one orientation
 * from the pair. Feed it the phone's gravity and the device's simulated field and it is being asked
 * about two devices at once — the answer is not wrong so much as meaningless.
 */
@Composable
private fun Mixed(revision: Int) {
    val context = LocalContext.current
    val motion = listOf(
        VirtualHardware.Accelerometer,
        VirtualHardware.Compass,
        VirtualHardware.Gyroscope,
    )
    val modes = remember(revision) { motion.map { VirtualDevicePolicy.mode(context, it) } }
    val mixed = modes.filter { it != HardwareMode.Off }.distinct().size > 1
    if (!mixed) return
    ManagerNoticeCard(
        title = "These three do not agree",
        message = motion.zip(modes).joinToString(", ") { "${it.first.label.lowercase()} ${it.second.label.lowercase()}" } +
            ". They are three views of one attitude, so an app that derives an orientation from " +
            "gravity and the magnetic field together is being handed two different devices. Wire " +
            "them the same way unless that mismatch is what you are testing.",
    )
}

@Composable
private fun LocationTools(settings: HardwareSettings, now: HardwareSample) {
    val context = LocalContext.current
    // Two different questions, and answering them with one flag was a conflict: *something* is
    // moving, which is what stops a coordinate being edited out from under it, and *this method* is
    // moving, which is what the map and the readout are about. The trail's map drew the device at
    // the position of a running point-to-point route otherwise — a marker nowhere near the trail
    // under it.
    val movingAny = settings.locationMode != LocationMode.Fixed && settings.routeStartedAt > 0L
    var method by remember(settings.locationMode) {
        mutableStateOf(settings.locationMode.takeIf { it != LocationMode.Fixed } ?: LocationMode.Route)
    }
    val movingHere = movingAny && settings.locationMode == method

    ManagerSectionCard(
        title = "How it moves",
        description = "Whatever it is doing, the device reports the bearing and speed a real " +
            "receiver would — which is what a navigation app reads rather than differencing " +
            "positions itself — and the compass turns to face the way it is going.",
    ) {
        SettingsDropdownRow(
            label = "Method",
            options = listOf(LocationMode.Route, LocationMode.Trail).map { it.name },
            selected = method.name,
            optionLabel = { LocationMode.valueOf(it).label },
            onSelect = { method = LocationMode.valueOf(it) },
        )
    }

    if (method == LocationMode.Trail) {
        TrailTools(settings = settings, now = now, movingHere = movingHere, movingAny = movingAny)
        return
    }

    ManagerSectionCard(
        title = "Point to point",
        description = "A straight line between two fixes, at the speed you set.",
    ) {
        Coordinate(
            label = "Latitude",
            value = settings.latitude,
            enabled = !movingAny,
            limit = 90.0,
        ) { VirtualDevicePolicy.setFix(context, it, settings.longitude) }
        Coordinate(
            label = "Longitude",
            value = settings.longitude,
            enabled = !movingAny,
            limit = 180.0,
        ) { VirtualDevicePolicy.setFix(context, settings.latitude, it) }

        Coordinate("To latitude", settings.toLatitude, !movingAny, limit = 90.0) {
            VirtualDevicePolicy.setRoute(context, it, settings.toLongitude, settings.speedMps, settings.repeat)
        }
        Coordinate("To longitude", settings.toLongitude, !movingAny, limit = 180.0) {
            VirtualDevicePolicy.setRoute(context, settings.toLatitude, it, settings.speedMps, settings.repeat)
        }
        Speed(settings = settings, enabled = !movingAny)
        SettingsDropdownRow(
            label = "At the far end",
            supporting = "What the device does when it arrives.",
            options = RouteRepeat.entries.map { it.name },
            selected = settings.repeat.name,
            optionLabel = { RouteRepeat.valueOf(it).label },
            onSelect = {
                VirtualDevicePolicy.setRoute(
                    context,
                    settings.toLatitude,
                    settings.toLongitude,
                    settings.speedMps,
                    RouteRepeat.valueOf(it),
                )
            },
        )

        val metres = remember(settings.latitude, settings.longitude, settings.toLatitude, settings.toLongitude) {
            SimulatedHardware.distance(
                settings.latitude,
                settings.longitude,
                settings.toLatitude,
                settings.toLongitude,
            )
        }
        ManagerSummaryRow(label = "Route", value = journey(metres, settings.speedMps))
        ManagerSummaryRow(
            label = "Device is at",
            value = when {
                movingHere -> "%s · %s".format(coordinates(now.latitude, now.longitude), heading(now))
                movingAny -> "following a trail"
                else -> coordinates(now.latitude, now.longitude)
            },
        )
        // Stop is offered whenever the device is moving at all, even on the other method's card: the
        // button is about the device, and one that said "start" while it was already moving would be
        // lying about which.
        Go(moving = movingAny, enabled = metres > 0.5, now = now, mode = LocationMode.Route)
    }
}

/**
 * Start and stop, for either method.
 *
 * Stopping leaves the device where it got to rather than snapping it back to the start: that is
 * where it was, and it is usually the point somebody is stopping in order to look at.
 */
@Composable
private fun Go(moving: Boolean, enabled: Boolean, now: HardwareSample, mode: LocationMode) {
    val context = LocalContext.current
    if (moving) {
        CompactOutlinedButton(
            text = "Stop here",
            onClick = { VirtualDevicePolicy.setFix(context, now.latitude, now.longitude) },
            modifier = Modifier.fillMaxWidth(),
        )
    } else {
        CompactFilledButton(
            text = "Start moving",
            enabled = enabled,
            onClick = { VirtualDevicePolicy.setMoving(context, mode, SystemClock.elapsedRealtime()) },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * The trail method: pick one of the device's paths, watch it walked on the map.
 *
 * The map is drawn from the trail's own points rather than fetched — there are no tiles here, no
 * network, and nothing to attribute. That is partly what makes it work offline, and partly the
 * point: these paths are sketches of real places, deliberately displaced, and a real map under them
 * would invite the comparison the displacement exists to prevent. See [LocationTrail].
 */
@Composable
private fun TrailTools(
    settings: HardwareSettings,
    now: HardwareSample,
    movingHere: Boolean,
    movingAny: Boolean,
) {
    val context = LocalContext.current
    val trail = remember(settings.trailId) {
        LocationTrail.byId(settings.trailId) ?: TRAILS.first()
    }

    ManagerSectionCard(
        title = trail.name,
        description = trail.summary,
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TRAILS.forEach { option ->
                ManagerFilterChip(selected = option.id == trail.id, label = option.place) {
                    VirtualDevicePolicy.setTrail(context, option.id)
                    // Picking a different trail while one is being walked starts the new one from
                    // its beginning rather than dropping the device somewhere along it — the two
                    // are nowhere near each other, and a tap that did nothing would be worse.
                    if (movingHere) {
                        VirtualDevicePolicy.setMoving(
                            context,
                            LocationMode.Trail,
                            SystemClock.elapsedRealtime(),
                        )
                    }
                }
            }
        }
        TrailMap(
            trail = trail,
            now = now,
            // Only when *this* trail is what is being walked. The arrow is drawn at the device's
            // coordinates, and those are somewhere else entirely while a point-to-point route runs.
            showDevice = movingHere,
            modifier = Modifier.fillMaxWidth().height(MAP_HEIGHT),
        )
        Text(
            text = "North is up. The arrow is the device, pointing the way it is going — which is " +
                "what the compass is reporting while it moves.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Speed(settings = settings, enabled = !movingAny)
        SettingsDropdownRow(
            label = "At the far end",
            supporting = "What the device does when it gets there.",
            options = RouteRepeat.entries.map { it.name },
            selected = settings.repeat.name,
            optionLabel = { RouteRepeat.valueOf(it).label },
            onSelect = {
                VirtualDevicePolicy.setRoute(
                    context,
                    settings.toLatitude,
                    settings.toLongitude,
                    settings.speedMps,
                    RouteRepeat.valueOf(it),
                )
            },
        )
        ManagerSummaryRow(label = "Trail", value = journey(trail.length, settings.speedMps))
        ManagerSummaryRow(
            label = "Device is at",
            value = when {
                movingHere -> "%s · %s".format(coordinates(now.latitude, now.longitude), heading(now))
                movingAny -> "on a point-to-point route, not this trail"
                else -> "not on the trail"
            },
        )
        Go(moving = movingAny, enabled = true, now = now, mode = LocationMode.Trail)
    }
    ManagerNoticeCard(
        title = "These are not survey data",
        message = "Every trail is hand-drawn, simplified and moved a few hundred metres from the " +
            "real place, and the compass is skewed a few degrees off the true bearing. A faithful " +
            "replay of a real street at a realistic speed is worth nothing to somebody testing a " +
            "maps app and a great deal to somebody faking a journey — so there is no faithful trace " +
            "here to replay. Nor does any of it leave the device: a simulated fix is answered to " +
            "guests inside JCode and never to the phone.",
    )
}

/**
 * The trail, and where the device is on it.
 *
 * Longitude is scaled by the cosine of the middle latitude before anything is fitted, or the
 * hairpins at 62° north would come out twice as wide as they are on the ground — which would make
 * the map disagree with the bearings the device is reporting from the same points.
 */
@Composable
private fun TrailMap(
    trail: LocationTrail,
    now: HardwareSample,
    showDevice: Boolean,
    modifier: Modifier = Modifier,
) {
    val line = MaterialTheme.colorScheme.primary
    val ends = MaterialTheme.colorScheme.onSurfaceVariant
    val device = MaterialTheme.colorScheme.error
    val background = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.22f)

    Canvas(modifier = modifier.clip(RoundedCornerShape(10.dp)).background(background)) {
        val points = trail.points
        if (points.size < 2) return@Canvas
        val midLatitude = cos(Math.toRadians(points.sumOf { it.latitude } / points.size))
        val xs = points.map { it.longitude * midLatitude }
        val ys = points.map { it.latitude }
        val spanX = (xs.max() - xs.min()).takeIf { it > 0 } ?: 1.0
        val spanY = (ys.max() - ys.min()).takeIf { it > 0 } ?: 1.0
        val pad = MAP_PADDING.toPx()
        val scale = minOf((size.width - pad * 2) / spanX, (size.height - pad * 2) / spanY)
        val originX = (size.width - spanX * scale) / 2
        val originY = (size.height - spanY * scale) / 2

        fun project(latitude: Double, longitude: Double) = Offset(
            x = (originX + (longitude * midLatitude - xs.min()) * scale).toFloat(),
            // Latitude grows northwards and the screen grows downwards.
            y = (originY + (ys.max() - latitude) * scale).toFloat(),
        )

        val path = Path()
        points.forEachIndexed { index, point ->
            val at = project(point.latitude, point.longitude)
            if (index == 0) path.moveTo(at.x, at.y) else path.lineTo(at.x, at.y)
        }
        drawPath(
            path = path,
            color = line,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
        )
        drawCircle(ends, radius = 4.dp.toPx(), center = project(points.first().latitude, points.first().longitude))
        drawCircle(ends, radius = 4.dp.toPx(), center = project(points.last().latitude, points.last().longitude), style = Stroke(2.dp.toPx()))

        if (!showDevice) return@Canvas
        val at = project(now.latitude, now.longitude)
        // Rotated to the heading the device is reporting, so the arrow and the compass are the same
        // number seen two ways.
        rotate(degrees = now.bearing, pivot = at) {
            val nose = 13.dp.toPx()
            val tail = 8.dp.toPx()
            val arrow = Path().apply {
                moveTo(at.x, at.y - nose)
                lineTo(at.x + tail, at.y + tail)
                lineTo(at.x, at.y + tail / 2)
                lineTo(at.x - tail, at.y + tail)
                close()
            }
            drawPath(arrow, color = device)
        }
    }
}

@Composable
private fun MotionTools(settings: HardwareSettings, travelling: Boolean) {
    val context = LocalContext.current

    ManagerSectionCard(
        title = "How it is held",
        description = "One attitude for the device, shared by the accelerometer, the compass and " +
            "the gyroscope — they are three views of it, so turning the heading turns all of them." +
            if (travelling) {
                " The device is moving, so its heading is the direction of travel until it stops."
            } else {
                ""
            },
    ) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            POSES.forEach { pose ->
                ManagerFilterChip(
                    selected = settings.pitch == pose.pitch && settings.roll == pose.roll,
                    label = pose.label,
                ) { VirtualDevicePolicy.setAttitude(context, pose.pitch, pose.roll, settings.azimuth) }
            }
        }
        Degrees("Pitch", settings.pitch, -180f..180f) {
            VirtualDevicePolicy.setAttitude(context, it, settings.roll, settings.azimuth)
        }
        Degrees("Roll", settings.roll, -180f..180f) {
            VirtualDevicePolicy.setAttitude(context, settings.pitch, it, settings.azimuth)
        }
        // Left visible but inert while travelling rather than hidden: it still says where the device
        // will be pointing when it stops, and a slider that silently did nothing would be the worse
        // of the two.
        Degrees(
            label = if (travelling) "Heading (the trail is steering)" else "Heading",
            value = settings.azimuth,
            range = 0f..359f,
            enabled = !travelling,
        ) {
            VirtualDevicePolicy.setAttitude(context, settings.pitch, settings.roll, it)
        }

        SettingsDropdownRow(
            label = "Loop",
            supporting = if (travelling && settings.loop == MotionLoop.Spin) {
                "A spin waits while the device is travelling — the heading is the direction of travel."
            } else {
                "A movement repeated for as long as it is selected."
            },
            options = MotionLoop.entries.map { it.name },
            selected = settings.loop.name,
            optionLabel = { MotionLoop.valueOf(it).label },
            onSelect = {
                val loop = MotionLoop.valueOf(it)
                VirtualDevicePolicy.setLoop(
                    context,
                    loop,
                    loop.defaultAmplitude,
                    loop.defaultPeriodMs,
                    SystemClock.elapsedRealtime(),
                )
            },
        )
        if (settings.loop != MotionLoop.None) {
            settings.loop.amplitudeLabel?.let { label ->
                Amount(label, settings.amplitude, 1f..20f) {
                    VirtualDevicePolicy.setLoop(
                        context,
                        settings.loop,
                        it,
                        settings.periodMs,
                        SystemClock.elapsedRealtime(),
                    )
                }
            }
            Amount(
                label = "Period (ms)",
                value = settings.periodMs.toFloat(),
                range = 100f..5_000f,
                decimals = 0,
            ) {
                VirtualDevicePolicy.setLoop(
                    context,
                    settings.loop,
                    settings.amplitude,
                    it.toLong(),
                    SystemClock.elapsedRealtime(),
                )
            }
        }
        CompactOutlinedButton(
            text = "Shake once",
            onClick = {
                VirtualDevicePolicy.shakeOnce(context, SystemClock.elapsedRealtime() + 700L)
            },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * What this one sensor is reporting at this moment.
 *
 * Worth having for its own sake — it is the only way to see the tools working without an app
 * installed to watch — but it is also the check that the two sides agree: these are the numbers a
 * guest's `SensorManager` is delivering, computed the same way from the same clock.
 */
@Composable
private fun SensorReadout(hardware: VirtualHardware, now: HardwareSample) {
    ManagerSectionCard(
        title = "Reporting now",
        description = "What an app with this switched on is being told, as it is told it.",
    ) {
        when (hardware) {
            VirtualHardware.Accelerometer -> {
                ManagerSummaryRow("Accelerometer", "${vector(now.accelerometer)} m/s²")
                ManagerSummaryRow("Gravity", "${vector(now.gravity)} m/s²")
            }
            VirtualHardware.Compass -> {
                ManagerSummaryRow("Magnetic field", "${vector(now.magnetic)} µT")
                ManagerSummaryRow("Heading", "%.0f° from north".format(Locale.US, now.orientation[0]))
            }
            else -> {
                ManagerSummaryRow("Gyroscope", "${vector(now.gyroscope)} rad/s")
                ManagerSummaryRow(
                    "Orientation",
                    "%.0f° · %.0f° · %.0f°".format(
                        Locale.US,
                        now.orientation[0],
                        now.orientation[1],
                        now.orientation[2],
                    ),
                )
            }
        }
    }
    ManagerNoticeCard(
        title = "Cleared when JCode restarts",
        message = "The device is wiped on every start, and this goes with it — a route still " +
            "running against an app that is no longer installed is nobody's idea of a clean room.",
    )
}

// ------------------------------------------------------------------------------------ small pieces

/**
 * A coordinate, held as text while it is being typed.
 *
 * Committed only when the text parses and lands on Earth, so a half-finished number does not move
 * the device.
 */
@Composable
private fun Coordinate(
    label: String,
    value: Double,
    enabled: Boolean,
    /** ±90 for a latitude, ±180 for a longitude — a place on Earth, and not one that is not. */
    limit: Double,
    onCommit: (Double) -> Unit,
) {
    var text by remember { mutableStateOf(value.toString()) }
    // Refilled only when the stored value is not what is already typed — a route stopping somewhere
    // else has to show, while "37.40" must not be rewritten to "37.4" under the cursor between one
    // keystroke and the next.
    LaunchedEffect(value) { if (text.trim().toDoubleOrNull() != value) text = value.toString() }
    SettingsTextFieldRow(
        label = label,
        value = text,
        onValueChange = { typed ->
            text = typed
            if (enabled) typed.trim().toDoubleOrNull()?.takeIf { it in -limit..limit }?.let(onCommit)
        },
        monospace = true,
    )
}

@Composable
private fun Speed(settings: HardwareSettings, enabled: Boolean) {
    val context = LocalContext.current
    var text by remember { mutableStateOf(settings.speedMps.toString()) }
    LaunchedEffect(settings.speedMps) {
        if (text.trim().toFloatOrNull() != settings.speedMps) text = settings.speedMps.toString()
    }
    SettingsTextFieldRow(
        label = "Speed (m/s)",
        supporting = "%.0f km/h".format(Locale.US, settings.speedMps * 3.6f),
        value = text,
        onValueChange = { typed ->
            text = typed
            if (enabled) {
                typed.trim().toFloatOrNull()?.takeIf { it > 0f && it < 400f }?.let {
                    VirtualDevicePolicy.setRoute(
                        context,
                        settings.toLatitude,
                        settings.toLongitude,
                        it,
                        settings.repeat,
                    )
                }
            }
        },
        monospace = true,
    )
}

/**
 * A slider over an angle.
 *
 * The value is stored when the finger lifts rather than on every frame: the policy is a file that
 * both processes read, and rewriting it sixty times a second to follow a drag would cost far more
 * than the quarter-second of lag it saves.
 */
@Composable
private fun Degrees(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    enabled: Boolean = true,
    onCommit: (Float) -> Unit,
) = Amount(
    label = label,
    value = value,
    range = range,
    decimals = 0,
    suffix = "°",
    enabled = enabled,
    onCommit = onCommit,
)

@Composable
private fun Amount(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int = 1,
    suffix: String = "",
    enabled: Boolean = true,
    onCommit: (Float) -> Unit,
) {
    var dragged by remember(value) { mutableStateOf(value) }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "%.${decimals}f%s".format(Locale.US, dragged, suffix),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
        }
        Slider(
            value = dragged.coerceIn(range.start, range.endInclusive),
            valueRange = range,
            enabled = enabled,
            onValueChange = { dragged = it },
            onValueChangeFinished = { onCommit(dragged) },
        )
    }
}

private fun coordinates(latitude: Double, longitude: Double): String =
    "%.5f, %.5f".format(Locale.US, latitude, longitude)

private fun heading(now: HardwareSample): String =
    "%.0f° · %.1f m/s".format(Locale.US, now.bearing, now.speedMps)

private fun vector(values: FloatArray): String =
    values.take(3).joinToString(", ") { "%+.2f".format(Locale.US, it) }

/** How far the route is and how long it takes, which is the thing a person actually wants to know. */
private fun journey(metres: Double, speedMps: Float): String {
    if (metres < 1.0) return "nowhere — the two points are the same"
    val seconds = (metres / speedMps.coerceAtLeast(0.1f)).toInt()
    val distance = if (metres >= 1_000) "%.2f km".format(Locale.US, metres / 1_000) else "%.0f m".format(Locale.US, metres)
    val duration = when {
        seconds >= 3_600 -> "%dh %02dm".format(seconds / 3_600, seconds % 3_600 / 60)
        seconds >= 60 -> "%dm %02ds".format(seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }
    return "$distance · $duration"
}

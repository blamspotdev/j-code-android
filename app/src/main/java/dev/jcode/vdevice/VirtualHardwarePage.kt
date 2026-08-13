package dev.jcode.vdevice

import android.Manifest
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import kotlinx.coroutines.delay

/** How often the readout re-reads the device. Fast enough to look live, slow enough to be free. */
private const val READOUT_MS = 150L

/** Two columns of tiles, which is what fits a phone in portrait without shrinking the labels. */
private const val TILE_COLUMNS = 2
private val TILE_HEIGHT = 82.dp

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
 * J Code chrome is: what `screencap` answers with has to be the device, and a control panel drawn
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
    LaunchedEffect(Unit) {
        while (true) {
            delay(READOUT_MS)
            now = SimulatedHardware.sample(context)
        }
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header(opened = opened, onBack = { opened = null })
            when (val hardware = opened) {
                null -> HardwareGrid(revision = revision, now = now) { opened = it }
                VirtualHardware.Location -> {
                    Mode(hardware)
                    LocationTools(settings = settings, now = now)
                }
                VirtualHardware.Accelerometer,
                VirtualHardware.Compass,
                VirtualHardware.Gyroscope,
                -> {
                    Mode(hardware)
                    MotionTools(settings = settings)
                    SensorReadout(hardware = hardware, now = now)
                }
                else -> Mode(hardware)
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
            "every app whatever its permissions say.",
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
}

@Composable
private fun LocationTools(settings: HardwareSettings, now: HardwareSample) {
    val context = LocalContext.current
    val running = settings.locationMode == LocationMode.Route && settings.routeStartedAt > 0L

    ManagerSectionCard(
        title = "Where it is",
        description = "Point to point walks between two fixes at the speed you set, reporting the " +
            "bearing and speed a real receiver would — which is what a navigation app reads rather " +
            "than differencing positions itself.",
    ) {
        Coordinate(
            label = "Latitude",
            value = settings.latitude,
            enabled = !running,
            limit = 90.0,
        ) { VirtualDevicePolicy.setFix(context, it, settings.longitude) }
        Coordinate(
            label = "Longitude",
            value = settings.longitude,
            enabled = !running,
            limit = 180.0,
        ) { VirtualDevicePolicy.setFix(context, settings.latitude, it) }

        Text(
            text = "Point to point",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Coordinate("To latitude", settings.toLatitude, !running, limit = 90.0) {
            VirtualDevicePolicy.setRoute(context, it, settings.toLongitude, settings.speedMps, settings.repeat)
        }
        Coordinate("To longitude", settings.toLongitude, !running, limit = 180.0) {
            VirtualDevicePolicy.setRoute(context, settings.toLatitude, it, settings.speedMps, settings.repeat)
        }
        Speed(settings = settings, enabled = !running)
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
            value = if (running) {
                "%s · %s".format(coordinates(now.latitude, now.longitude), heading(now))
            } else {
                coordinates(now.latitude, now.longitude)
            },
        )
        if (running) {
            CompactOutlinedButton(
                text = "Stop here",
                onClick = {
                    // Left where it got to rather than snapped back to the start: stopping a moving
                    // device should put it where it was, which is also the point a person is usually
                    // stopping in order to look at.
                    VirtualDevicePolicy.setFix(context, now.latitude, now.longitude)
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            CompactFilledButton(
                text = "Start moving",
                enabled = metres > 0.5,
                onClick = {
                    VirtualDevicePolicy.setRouteRunning(context, true, SystemClock.elapsedRealtime())
                },
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun MotionTools(settings: HardwareSettings) {
    val context = LocalContext.current

    ManagerSectionCard(
        title = "How it is held",
        description = "One attitude for the device, shared by the accelerometer, the compass and " +
            "the gyroscope — they are three views of it, so turning the heading turns all of them.",
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
        Degrees("Heading", settings.azimuth, 0f..359f) {
            VirtualDevicePolicy.setAttitude(context, settings.pitch, settings.roll, it)
        }

        SettingsDropdownRow(
            label = "Loop",
            supporting = "A movement repeated for as long as it is selected.",
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
        title = "Cleared when J Code restarts",
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
    onCommit: (Float) -> Unit,
) = Amount(label = label, value = value, range = range, decimals = 0, suffix = "°", onCommit = onCommit)

@Composable
private fun Amount(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    decimals: Int = 1,
    suffix: String = "",
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

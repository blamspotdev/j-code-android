package dev.jcode.vdevice

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.jcode.design.CompactOutlinedButton
import dev.jcode.design.ManagerNoticeCard
import dev.jcode.design.ManagerSectionCard
import dev.jcode.design.ManagerSummaryRow
import dev.jcode.design.SettingsDropdownRow
import java.util.Locale

/**
 * What one app installed on the virtual device is allowed to reach.
 *
 * This is the device's settings screen for an app, not the phone's — nothing here touches what J
 * Code itself may do, with the single exception of a real microphone, which needs the user's own
 * permission and asks for it here.
 *
 * Over the device's screen rather than on it, like [InstallSheet] and for the same reason: it is
 * J Code talking about the app, so it must not appear in what `screencap` answers with, where it
 * would read as something the guest drew.
 */
@Composable
internal fun AppPermissionsSheet(
    app: VirtualDeviceApp,
    onSnackbar: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    // Read so that every write below redraws the sheet: the policy lives in a file, not in state.
    val revision = VirtualDevicePolicy.revision.intValue

    // Held across the system's own prompt: the mode is only stored once recording is actually
    // allowed, so a refused prompt leaves the app exactly where it was rather than pointing it at a
    // microphone J Code cannot open.
    val microphone = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            VirtualDevicePolicy.setMode(
                context,
                app.packageName,
                VirtualHardware.Microphone,
                HardwareMode.Real,
            )
            onSnackbar("${app.label} can use the phone's microphone.")
        } else {
            // Deliberately unchanged rather than downgraded: the app is where the user left it, and
            // pointing it at a microphone J Code cannot open would be the one dishonest outcome.
            onSnackbar("J Code was not allowed to record, so ${app.label}'s microphone is unchanged.")
        }
    }

    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(
            modifier = Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "What ${app.label} may reach on ${VirtualIdentity.MODEL}. Simulated " +
                            "hardware belongs to the device and tells the app the same thing every " +
                            "time; real hardware is the phone's.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
                    Icon(
                        Icons.Rounded.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            ManagerSectionCard(
                title = "Hardware",
                description = "An app is told the device has only what is switched on here, and is " +
                    "refused the permission for anything that is off — both before it asks and when " +
                    "it does.",
            ) {
                VirtualHardware.entries.forEach { hardware ->
                    SettingsDropdownRow(
                        label = hardware.label,
                        supporting = hardware.summary,
                        options = hardware.modes
                            .filter { it != HardwareMode.Real || hardware.realOffered(context) }
                            .map { it.name },
                        selected = VirtualDevicePolicy.mode(context, app.packageName, hardware).name,
                        optionLabel = { HardwareMode.valueOf(it).label },
                        onSelect = { chosen ->
                            val mode = HardwareMode.valueOf(chosen)
                            // The one choice that is not ours to make: a real microphone is the
                            // phone's, so the user is asked for it before the app is pointed at it.
                            if (hardware == VirtualHardware.Microphone &&
                                mode == HardwareMode.Real &&
                                !hardware.realAvailable(context)
                            ) {
                                microphone.launch(Manifest.permission.RECORD_AUDIO)
                            } else {
                                VirtualDevicePolicy.setMode(context, app.packageName, hardware, mode)
                            }
                        },
                    )
                }
            }

            if (VirtualDevicePolicy.mode(context, app.packageName, VirtualHardware.Location) !=
                HardwareMode.Off
            ) {
                SimulatedFix()
            }

            ManagerSectionCard(
                title = "Running",
                description = "The device shows one app at a time, so leaving an app is the closest " +
                    "thing it has to closing one.",
            ) {
                var background by remember(revision) {
                    mutableStateOf(VirtualDevicePolicy.backgroundAllowed(context, app.packageName))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = "Runs in background",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            text = "Keep its services and notifications alive after you leave it — " +
                                "what a music player or a download needs, and what nothing else does.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = background,
                        onCheckedChange = {
                            background = it
                            VirtualDevicePolicy.setBackgroundAllowed(context, app.packageName, it)
                        },
                    )
                }
            }

            ManagerNoticeCard(
                title = "Cleared when J Code restarts",
                message = "The device is wiped on every start — apps, their data, and these " +
                    "permissions with them. A grant that outlived the app it was given to would be " +
                    "waiting for whatever was installed under that name next.",
            )
        }
    }
}

/**
 * Where the device's simulated GPS currently says it is.
 *
 * A reading rather than an editor: this is one fix for the whole device — a phone has one receiver
 * and every app on it reads the same coordinates — so it is set on the hardware bench along with
 * everything else that moves, and shown here because an app being given location is the moment
 * somebody wants to know where the device thinks it is.
 */
@Composable
private fun SimulatedFix() {
    val context = LocalContext.current
    val settings = VirtualDevicePolicy.hardware(context)
    val now = SimulatedHardware.sample(context)

    ManagerSectionCard(
        title = "Simulated location",
        description = "What every app on this device is told. Set it — and start it moving — on the " +
            "hardware bench.",
    ) {
        ManagerSummaryRow(
            label = if (settings.locationMode == LocationMode.Route) "Moving through" else "Parked at",
            value = "%.5f, %.5f".format(Locale.US, now.latitude, now.longitude),
        )
        CompactOutlinedButton(
            text = "Open hardware",
            onClick = { SimulatedHardware.requestOpen() },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

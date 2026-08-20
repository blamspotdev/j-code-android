package dev.blamspot.jcode.workbench

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.blamspot.jcode.core.distro.adb.AdbBridgeLocator
import dev.blamspot.jcode.core.distro.adb.AdbBridgeState
import dev.blamspot.jcode.core.distro.adb.AdbDevice
import dev.blamspot.jcode.core.distro.adb.AdbHostClient
import dev.blamspot.jcode.design.CompactFilledButton
import dev.blamspot.jcode.design.CompactOutlinedButton
import dev.blamspot.jcode.design.ManagerSectionCard
import dev.blamspot.jcode.design.ManagerSummaryRow
import dev.blamspot.jcode.design.SettingsTextFieldRow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch

/** Catalog id of the distro's adb client — the toolchain this wizard links to for step 1. */
internal const val ADB_CATALOG_ID = "adb"

/** Short label for an [AdbBridgeState], shared with the Settings row and the Run panel affordance. */
internal fun adbStatusLabel(state: AdbBridgeState): String = when (state) {
    is AdbBridgeState.Stopped -> "Not set up"
    is AdbBridgeState.Discovering -> "Connecting…"
    is AdbBridgeState.Ready -> "Connected"
    is AdbBridgeState.Degraded -> state.reason
    is AdbBridgeState.Failed -> state.message
}

/**
 * Pairing wizard for this phone's own adb, shown as a full-screen editor page: install the adb client,
 * turn on Wireless debugging, pair with a code, then connect through the relay. Once connected, plain
 * `adb`, `./gradlew installDebug` and `flutter run` inside the runtime all target this device.
 *
 * [onPair] runs `adb pair` in the guest as the DEFAULT runtime user — see MainViewModel.pairAdbDevice
 * for why it must never run as root.
 */
@Composable
internal fun AndroidDevicePage(
    adbInstalled: Boolean,
    onOpenAdbToolchain: () -> Unit,
    onPair: suspend (target: String, code: String) -> String?,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val bridge = remember(context) { AdbBridgeLocator.adbBridge(context) }
    val state by bridge.state.collectAsStateWithLifecycle()
    val serial by bridge.serial.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var developerOptionsOn by remember { mutableStateOf(developerOptionsEnabled(context)) }
    var wirelessDebuggingOn by remember { mutableStateOf(bridge.isWirelessDebuggingEnabled()) }
    // Both switches are flipped in Android Settings, i.e. outside this app — so the only moment they
    // can be re-read is the return trip.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, bridge) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                developerOptionsOn = developerOptionsEnabled(context)
                wirelessDebuggingOn = bridge.isWirelessDebuggingEnabled()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var pairingCode by rememberSaveable { mutableStateOf("") }
    var pairingPort by rememberSaveable { mutableStateOf("") }
    var pairingPortEdited by rememberSaveable { mutableStateOf(false) }
    var pairing by remember { mutableStateOf(false) }
    var pairError by remember { mutableStateOf<String?>(null) }
    var paired by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var devices by remember { mutableStateOf<List<AdbDevice>>(emptyList()) }
    var deviceError by remember { mutableStateOf<String?>(null) }
    var backendOverride by rememberSaveable { mutableStateOf("") }
    var overrideError by remember { mutableStateOf<String?>(null) }

    // adbd advertises its pairing port only while the system "Pair device with pairing code" dialog is
    // open, and re-randomises it each time — so the field fills itself the moment that dialog appears.
    LaunchedEffect(bridge) {
        bridge.pairingServices()
            .catch { error -> android.util.Log.w("AndroidDevicePage", "pairing discovery: ${error.message}") }
            .collect { endpoint -> if (!pairingPortEdited) pairingPort = endpoint.port.toString() }
    }

    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = Icons.Rounded.PhoneAndroid,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Android device", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "ADB bridge",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    AdbStatusChip(text = adbStatusLabel(state), active = state is AdbBridgeState.Ready)
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        Text(
            text = "Pair JCode with this phone's own adb. Once connected, adb, ./gradlew installDebug " +
                "and flutter run inside the runtime install and launch straight onto this device — no " +
                "computer, no cable.",
            style = MaterialTheme.typography.bodyMedium,
        )

        ManagerSectionCard(
            title = "1. Install adb",
            description = "The distro's native adb client, installed from the Toolchains manager.",
        ) {
            ManagerSummaryRow("adb", if (adbInstalled) "Installed" else "Not installed")
            CompactOutlinedButton(
                text = if (adbInstalled) "Open in Toolchains" else "Install adb…",
                onClick = onOpenAdbToolchain,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ManagerSectionCard(
            title = "2. Enable wireless debugging",
            description = "Developer options → Wireless debugging. It generally needs Wi-Fi, and Android " +
                "switches it back off on every reboot.",
        ) {
            ManagerSummaryRow("Developer options", if (developerOptionsOn) "On" else "Off")
            ManagerSummaryRow("Wireless debugging", if (wirelessDebuggingOn) "On" else "Off")
            if (!developerOptionsOn) {
                Text(
                    text = "Developer options are hidden. Open device info and tap Build number seven " +
                        "times, then come back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CompactFilledButton(
                    text = "Open device info",
                    onClick = { openSettings(context, Settings.ACTION_DEVICE_INFO_SETTINGS) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            CompactOutlinedButton(
                text = "Open developer options",
                onClick = { openSettings(context, Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ManagerSectionCard(
            title = "3. Pair",
            description = "Put JCode and Settings side by side in split-screen FIRST, then tap \"Pair " +
                "device with pairing code\" in Wireless debugging. Android stops listening for the " +
                "pairing the instant that dialog leaves the foreground, so simply switching back to " +
                "JCode to type the code cancels it — the code will always be reported as wrong. The " +
                "port fills itself in while the dialog is up; the code is used once and never stored.",
        ) {
            SettingsTextFieldRow(
                label = "Pairing code",
                value = pairingCode,
                onValueChange = { pairingCode = it.filter(Char::isDigit).take(PAIRING_CODE_LENGTH) },
                placeholder = "6 digits",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                monospace = true,
            )
            SettingsTextFieldRow(
                label = "Pairing port",
                value = pairingPort,
                onValueChange = { pairingPortEdited = true; pairingPort = it.filter(Char::isDigit).take(5) },
                supporting = "Shown under the pairing code, after the colon.",
                placeholder = "e.g. 41337",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                monospace = true,
            )
            val port = pairingPort.toIntOrNull()
            CompactFilledButton(
                text = if (pairing) "Pairing…" else "Pair",
                enabled = !pairing && pairingCode.length == PAIRING_CODE_LENGTH && port != null && port in 1..65_535,
                onClick = {
                    val target = "${AdbHostClient.LOOPBACK}:$pairingPort"
                    val code = pairingCode
                    pairing = true
                    pairError = null
                    paired = false
                    scope.launch {
                        val failure = onPair(target, code)
                        pairing = false
                        pairError = failure
                        paired = failure == null
                        // The code is single-use: adbd invalidates it the moment the dialog closes.
                        if (failure == null) pairingCode = ""
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            when {
                pairError != null -> ErrorText(pairError.orEmpty())
                paired -> Text(
                    text = "Paired. Continue to step 4.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }

        ManagerSectionCard(
            title = "4. Connect",
            description = "Starts the relay, finds this phone's adbd over mDNS and connects the guest's " +
                "adb to it.",
        ) {
            ManagerSummaryRow("Status", adbStatusLabel(state))
            (state as? AdbBridgeState.Ready)?.let { ready ->
                ManagerSummaryRow("Relay port", ready.relayPort.toString())
                ManagerSummaryRow("Device port", ready.backendPort.toString())
            }
            serial?.let { ManagerSummaryRow("Serial", it) }
            CompactFilledButton(
                text = if (connecting) "Connecting…" else "Connect",
                enabled = !connecting,
                onClick = {
                    connecting = true
                    deviceError = null
                    scope.launch {
                        bridge.ensureConnected()
                        devices = runCatching { bridge.devices() }
                            .onFailure { deviceError = it.message ?: "adb did not answer." }
                            .getOrDefault(emptyList())
                        connecting = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            when (val current = state) {
                is AdbBridgeState.Failed -> ErrorText(current.message)
                is AdbBridgeState.Degraded -> Text(
                    text = current.reason,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> Unit
            }
            deviceError?.let { ErrorText(it) }
            if (devices.isEmpty()) {
                Text(
                    text = "No devices listed yet.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                devices.forEach { device ->
                    ManagerSummaryRow(device.model ?: device.serial, device.state)
                }
            }
        }

        ManagerSectionCard(
            title = "Backend override",
            description = "Dial a fixed host:port instead of the one discovered over mDNS. Leave empty " +
                "unless discovery is failing.",
            collapsible = true,
            defaultExpanded = false,
        ) {
            SettingsTextFieldRow(
                label = "adbd endpoint",
                value = backendOverride,
                onValueChange = { backendOverride = it; overrideError = null },
                placeholder = "127.0.0.1:5555",
                monospace = true,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                CompactFilledButton(
                    text = "Apply",
                    onClick = {
                        overrideError = if (bridge.setManualBackend(backendOverride)) {
                            null
                        } else {
                            "Not a valid host:port (or bare port)."
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
                CompactOutlinedButton(
                    text = "Clear",
                    onClick = {
                        backendOverride = ""
                        overrideError = null
                        bridge.setManualBackend(null)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
            overrideError?.let { ErrorText(it) }
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Text(text = text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun AdbStatusChip(text: String, active: Boolean) {
    Surface(
        color = if (active) {
            MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
        } else {
            MaterialTheme.colorScheme.surface.copy(alpha = 0.65f)
        },
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            style = MaterialTheme.typography.labelSmall,
            color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private fun developerOptionsEnabled(context: Context): Boolean = runCatching {
    Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
}.getOrDefault(false)

private fun openSettings(context: Context, action: String) {
    runCatching {
        context.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private const val PAIRING_CODE_LENGTH = 6

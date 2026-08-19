package dev.blamspot.jcode.core.distro.adb

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import dev.blamspot.jcode.core.distro.DistroService
import dev.blamspot.jcode.core.distro.DistroServiceLocator
import dev.blamspot.jcode.core.distro.ExecResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Facade over the whole adb path: the loopback [AdbRelayServer], mDNS [AdbBackendDiscovery], and the
 * adb server that runs inside the distro and is spoken to through [AdbHostClient].
 *
 * The guest's adb always connects to the relay, so the device serial stays `127.0.0.1:<relayPort>`
 * even though adbd's own port changes every time Wireless debugging is toggled.
 *
 * Every distro command here runs as [DistroService]'s DEFAULT user and never as root: adb keeps its
 * identity in `$HOME/.android/adbkey`, so a root-run `adb start-server` would raise a SECOND server
 * holding a SECOND, unpaired key — the device would reject it, and the two servers would then fight
 * over port 5037.
 */
class AdbBridge(
    context: Context,
    private val distroService: DistroService = DistroServiceLocator.distroService(context),
) {
    private val appContext = context.applicationContext
    private val discovery = AdbBackendDiscovery(appContext)
    private val relay = AdbRelayServer(
        backend = { discovery.endpoint.value },
        rediscover = { discovery.refresh() },
    )
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    private val dataStore = PreferenceDataStoreFactory.create {
        appContext.preferencesDataStoreFile("adb-bridge.preferences_pb")
    }

    private val _state = MutableStateFlow<AdbBridgeState>(AdbBridgeState.Stopped)
    val state: StateFlow<AdbBridgeState> = _state.asStateFlow()

    private val _serial = MutableStateFlow<String?>(null)

    /** Serial of the device last connected through the relay, restored across app restarts. */
    val serial: StateFlow<String?> = _serial.asStateFlow()

    val client: AdbHostClient = AdbHostClient()

    init {
        scope.launch { _serial.value = dataStore.data.first()[PreferencesKeys.Serial] }
    }

    /**
     * Brings the whole path up and reports where it got to. Serialised: concurrent callers (a UI
     * refresh racing a terminal command) would otherwise each start a server and each issue a connect.
     */
    suspend fun ensureConnected(): AdbBridgeState = lock.withLock {
        _state.value = AdbBridgeState.Discovering

        val relayPort = runCatching { relay.start() }.getOrElse { error ->
            return@withLock publish(AdbBridgeState.Failed("Could not bind a local relay port: ${error.message}"))
        }

        val backend = discovery.await()
            ?: return@withLock publish(AdbBridgeState.Degraded(discoveryHint()))

        if (client.version() == null) {
            val started = distroService.exec("adb start-server", timeoutMs = START_SERVER_TIMEOUT_MS)
            if (client.version() == null) {
                return@withLock publish(AdbBridgeState.Failed(startServerFailure(started)))
            }
        }

        val serial = "${AdbHostClient.LOOPBACK}:$relayPort"
        if (deviceOf(serial)?.isOnline != true) {
            val reply = runCatching { client.connect(serial) }
                .getOrElse { error -> error.message ?: "adb connect failed" }
            android.util.Log.d(TAG, "connect $serial: ${reply.trim()}")
        }

        val device = deviceOf(serial)
        when {
            device?.isOnline == true -> {
                persistSerial(serial)
                publish(AdbBridgeState.Ready(relayPort = relayPort, backendPort = backend.port))
            }
            device != null -> publish(
                AdbBridgeState.Degraded("Device reported by adb as '${device.state}' — accept the debugging prompt or re-pair."),
            )
            else -> publish(AdbBridgeState.Failed("adb did not list $serial after connecting to $backend."))
        }
    }

    suspend fun devices(): List<AdbDevice> = client.devices()

    /** See [AdbBackendDiscovery.setManualOverride]. Returns false when [value] is malformed. */
    fun setManualBackend(value: String?): Boolean = discovery.setManualOverride(value)

    /** See [AdbBackendDiscovery.pairingServices]. */
    fun pairingServices(): Flow<AdbEndpoint> = discovery.pairingServices()

    /** Whether Wireless debugging is on — a hint for the UI, not proof the bridge can connect. */
    fun isWirelessDebuggingEnabled(): Boolean = discovery.isWirelessDebuggingEnabled()

    fun stop() {
        relay.stop()
        discovery.stop()
        _state.value = AdbBridgeState.Stopped
    }

    private suspend fun deviceOf(serial: String): AdbDevice? =
        runCatching { client.devices() }.getOrDefault(emptyList()).firstOrNull { it.serial == serial }

    private fun discoveryHint(): String = if (discovery.isWirelessDebuggingEnabled()) {
        "Wireless debugging is on but this device is not advertising adb yet."
    } else {
        "Turn on Wireless debugging in Developer options."
    }

    private fun startServerFailure(result: ExecResult): String {
        val detail = listOf(result.internalError, result.stderr, result.stdout)
            .firstOrNull { !it.isNullOrBlank() }
            ?.trim()
            ?.takeLast(MAX_FAILURE_DETAIL)
        return "Could not start the adb server in the distro" + if (detail.isNullOrEmpty()) "." else ": $detail"
    }

    private suspend fun persistSerial(value: String) {
        _serial.value = value
        dataStore.edit { prefs -> prefs[PreferencesKeys.Serial] = value }
    }

    private fun publish(state: AdbBridgeState): AdbBridgeState {
        _state.value = state
        return state
    }

    private object PreferencesKeys {
        val Serial = stringPreferencesKey("adb_serial")
    }

    companion object {
        private const val TAG = "AdbBridge"
        private const val START_SERVER_TIMEOUT_MS = 30_000L
        private const val MAX_FAILURE_DETAIL = 240
    }
}

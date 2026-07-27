package dev.jcode.core.distro.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull

/** A TCP endpoint on this device: adbd's wireless-debugging listener, or a manual override for it. */
data class AdbEndpoint(val host: String, val port: Int) {
    override fun toString(): String = "$host:$port"
}

/**
 * Finds the port that this device's own adbd is listening on for wireless debugging.
 *
 * adbd advertises that listener over mDNS as `_adb-tls-connect._tcp` and re-randomises the port on
 * every Wireless-debugging toggle, so it has to be re-resolved rather than remembered.
 *
 * There is deliberately no shortcut here. The system property `service.adb.tls.port` holds exactly the
 * number this class hunts for, but it is labelled `adbd_prop` and SELinux denies untrusted apps read
 * access — verified on-device, both `getprop` and `__system_property_get` come back empty. mDNS is the
 * only route an app has; do not "optimise" it away.
 */
class AdbBackendDiscovery(context: Context) {
    private val appContext = context.applicationContext
    private val nsdManager by lazy { appContext.getSystemService(Context.NSD_SERVICE) as NsdManager }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val resolveQueue = Channel<ResolveRequest>(Channel.UNLIMITED)
    private val resolvedNames = mutableMapOf<String, AdbEndpoint>()

    private val _endpoint = MutableStateFlow<AdbEndpoint?>(null)
    val endpoint: StateFlow<AdbEndpoint?> = _endpoint.asStateFlow()

    private var resolveWorker: Job? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    @Volatile
    private var manualOverride: AdbEndpoint? = null

    val override: AdbEndpoint?
        get() = manualOverride

    /** Starts (or keeps) mDNS discovery. A manual override is published instead and suppresses it. */
    @Synchronized
    fun start() {
        manualOverride?.let {
            _endpoint.value = it
            return
        }
        if (discoveryListener != null) return
        ensureResolveWorker()
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                android.util.Log.w(TAG, "discovery of $serviceType failed to start (error $errorCode)")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                android.util.Log.w(TAG, "discovery of $serviceType failed to stop (error $errorCode)")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                scope.launch {
                    val resolved = enqueueResolve(serviceInfo) ?: return@launch
                    synchronized(resolvedNames) { resolvedNames[name] = resolved }
                    _endpoint.value = resolved
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                // onServiceLost carries only the instance name, and adbd re-advertises under a fresh
                // name plus a fresh port on every toggle — so drop the cached endpoint rather than let
                // the relay keep dialling a port nothing is bound to any more.
                val lost = synchronized(resolvedNames) { resolvedNames.remove(serviceInfo.serviceName) }
                if (lost != null) _endpoint.compareAndSet(lost, null)
            }
        }
        discoveryListener = listener
        runCatching { nsdManager.discoverServices(SERVICE_TYPE_CONNECT, NsdManager.PROTOCOL_DNS_SD, listener) }
            .onFailure {
                discoveryListener = null
                android.util.Log.w(TAG, "discoverServices($SERVICE_TYPE_CONNECT) failed: ${it.message}")
            }
    }

    @Synchronized
    fun stop() {
        discoveryListener?.let { runCatching { nsdManager.stopServiceDiscovery(it) } }
        discoveryListener = null
        synchronized(resolvedNames) { resolvedNames.clear() }
    }

    /** Starts discovery if needed and waits for the first endpoint, or null if none shows up in time. */
    suspend fun await(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): AdbEndpoint? {
        start()
        _endpoint.value?.let { return it }
        return withTimeoutOrNull(timeoutMs) { endpoint.filterNotNull().first() }
    }

    /** Drops the cached endpoint and waits for a fresh advertisement — the relay's rediscovery hook. */
    suspend fun refresh(timeoutMs: Long = DISCOVERY_TIMEOUT_MS): AdbEndpoint? {
        manualOverride?.let { return it }
        stop()
        _endpoint.value = null
        return await(timeoutMs)
    }

    /**
     * Pins the backend to [value], given as `host:port` or as a bare port (loopback assumed). Null or
     * blank resumes auto-discovery. Returns false for a malformed value, leaving the override as it was.
     */
    @Synchronized
    fun setManualOverride(value: String?): Boolean {
        if (value.isNullOrBlank()) {
            manualOverride = null
            _endpoint.value = null
            return true
        }
        val parsed = parseEndpoint(value) ?: return false
        manualOverride = parsed
        stop()
        _endpoint.value = parsed
        return true
    }

    /**
     * Emits pairing endpoints advertised as `_adb-tls-pairing._tcp`. adbd only advertises those while
     * the system "Pair device with pairing code" dialog is open, so this is a short-lived subscription
     * driven by the UI and never part of the steady-state discovery above.
     */
    fun pairingServices(): Flow<AdbEndpoint> = callbackFlow {
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onDiscoveryStopped(serviceType: String) = Unit

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                android.util.Log.w(TAG, "pairing discovery failed to start (error $errorCode)")
                close()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                launch { enqueueResolve(serviceInfo)?.let { trySend(it) } }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit
        }
        ensureResolveWorker()
        nsdManager.discoverServices(SERVICE_TYPE_PAIRING, NsdManager.PROTOCOL_DNS_SD, listener)
        awaitClose { runCatching { nsdManager.stopServiceDiscovery(listener) } }
    }

    /**
     * Whether the user has Wireless debugging switched on. A UX hint only — adbd may still be a moment
     * away from advertising, and the toggle says nothing about whether this app's key is paired. The
     * `Settings.Global` constant for it is @hide, hence the raw key.
     */
    fun isWirelessDebuggingEnabled(): Boolean = runCatching {
        Settings.Global.getInt(appContext.contentResolver, SETTING_ADB_WIFI_ENABLED, 0) == 1
    }.getOrDefault(false)

    private class ResolveRequest(
        val info: NsdServiceInfo,
        val reply: CompletableDeferred<AdbEndpoint?>,
    )

    private suspend fun enqueueResolve(info: NsdServiceInfo): AdbEndpoint? {
        val request = ResolveRequest(info, CompletableDeferred())
        ensureResolveWorker()
        resolveQueue.send(request)
        return request.reply.await()
    }

    @Synchronized
    private fun ensureResolveWorker() {
        if (resolveWorker?.isActive == true) return
        resolveWorker = scope.launch {
            for (request in resolveQueue) {
                // NsdManager allows exactly ONE resolve in flight, and a ResolveListener instance may
                // never be reused: overlapping or repeated listeners come back as FAILURE_ALREADY_ACTIVE
                // (error 3) and the resolve is silently lost. Hence this queue — strictly one at a time,
                // a freshly allocated listener for each.
                request.reply.complete(runCatching { resolveOne(request.info) }.getOrNull())
            }
        }
    }

    private suspend fun resolveOne(info: NsdServiceInfo): AdbEndpoint? = withTimeoutOrNull(RESOLVE_TIMEOUT_MS) {
        val resolved = awaitResolve(info) ?: return@withTimeoutOrNull null
        val address = addressOf(resolved) ?: return@withTimeoutOrNull null
        ownEndpoint(address, resolved.port)
    }

    @Suppress("DEPRECATION")
    private suspend fun awaitResolve(info: NsdServiceInfo): NsdServiceInfo? = suspendCancellableCoroutine { cont ->
        val settled = AtomicBoolean(false)
        fun finish(resolved: NsdServiceInfo?) {
            if (settled.compareAndSet(false, true)) cont.resume(resolved)
        }
        if (Build.VERSION.SDK_INT >= 34) {
            var registered: NsdManager.ServiceInfoCallback? = null
            fun unregister() {
                registered?.let { runCatching { nsdManager.unregisterServiceInfoCallback(it) } }
                registered = null
            }
            val callback = object : NsdManager.ServiceInfoCallback {
                override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                    android.util.Log.w(TAG, "service info callback rejected (error $errorCode)")
                    finish(null)
                }

                override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                    unregister()
                    finish(serviceInfo)
                }

                override fun onServiceLost() {
                    unregister()
                    finish(null)
                }

                override fun onServiceInfoCallbackUnregistered() = Unit
            }
            registered = callback
            cont.invokeOnCancellation { unregister() }
            runCatching {
                nsdManager.registerServiceInfoCallback(info, Executor { it.run() }, callback)
            }.onFailure { finish(null) }
        } else {
            nsdManager.resolveService(
                info,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        android.util.Log.w(TAG, "resolve of ${serviceInfo.serviceName} failed (error $errorCode)")
                        finish(null)
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) = finish(serviceInfo)
                },
            )
        }
    }

    @Suppress("DEPRECATION")
    private fun addressOf(info: NsdServiceInfo): InetAddress? =
        if (Build.VERSION.SDK_INT >= 34) info.hostAddresses.firstOrNull() else info.host

    /**
     * Decides whether an advertisement belongs to THIS device, and where to dial it. Every other phone
     * on the same Wi-Fi advertises the same service type, and connecting to one of those would hand the
     * relay a device the user never asked for.
     */
    private fun ownEndpoint(address: InetAddress, port: Int): AdbEndpoint? {
        val advertisedIsLocal = address.isLoopbackAddress || address in localAddresses()
        // Tiebreak for advertisements the interface list cannot vouch for (some ROMs answer with an
        // alias that never appears in getNetworkInterfaces): if 127.0.0.1:<port> accepts a connection,
        // the adbd behind that advertisement is this device's own.
        val loopbackReachable = probeLoopback(port)
        if (!advertisedIsLocal && !loopbackReachable) return null
        // Prefer loopback where it answers: adbd binds every interface, and dialling 127.0.0.1 keeps
        // the relay's traffic off the LAN entirely.
        val host = if (loopbackReachable) AdbHostClient.LOOPBACK else address.hostAddress ?: return null
        return AdbEndpoint(host, port)
    }

    private fun localAddresses(): Set<InetAddress> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            .asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .toSet()
    }.getOrDefault(emptySet())

    private fun probeLoopback(port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(InetAddress.getLoopbackAddress(), port), LOOPBACK_PROBE_MS) }
        true
    }.getOrDefault(false)

    private fun parseEndpoint(value: String): AdbEndpoint? {
        val trimmed = value.trim()
        val separator = trimmed.lastIndexOf(':')
        val host = if (separator < 0) AdbHostClient.LOOPBACK else trimmed.substring(0, separator).trim()
        val port = (if (separator < 0) trimmed else trimmed.substring(separator + 1)).trim().toIntOrNull()
        if (host.isEmpty() || port == null || port !in 1..65_535) return null
        return AdbEndpoint(host, port)
    }

    companion object {
        const val SERVICE_TYPE_CONNECT: String = "_adb-tls-connect._tcp"
        const val SERVICE_TYPE_PAIRING: String = "_adb-tls-pairing._tcp"

        private const val TAG = "AdbBackendDiscovery"
        private const val SETTING_ADB_WIFI_ENABLED = "adb_wifi_enabled"
        private const val RESOLVE_TIMEOUT_MS = 5_000L
        private const val DISCOVERY_TIMEOUT_MS = 10_000L
        private const val LOOPBACK_PROBE_MS = 500
    }
}

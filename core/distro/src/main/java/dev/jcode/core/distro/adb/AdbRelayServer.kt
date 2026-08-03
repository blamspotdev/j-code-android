package dev.jcode.core.distro.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A transparent TCP pump that gives the distro ONE stable adb endpoint.
 *
 * adbd re-randomises its wireless-debugging port on every toggle of the setting, but the guest's
 * `adb connect 127.0.0.1:<relay>` — and the device serial derived from it — must survive that. So the
 * guest always talks to this fixed port and the relay re-points itself at whatever adbd is on now.
 *
 * It is a DUMB byte pump on purpose: the traffic is adb's TLS stream, terminated end to end between
 * the guest's adb client and adbd, so nothing here can (or may) inspect, buffer or reframe it.
 *
 * The listener binds [InetAddress.getLoopbackAddress] and NEVER the wildcard address. Only the proot
 * guest needs to reach it, and proot shares this process's network namespace, so loopback is already
 * the same interface for it — whereas a wildcard bind would republish adbd's TLS port to every device
 * on the LAN under a port the user never opened.
 */
class AdbRelayServer(
    private val backend: () -> AdbEndpoint?,
    private val rediscover: suspend () -> AdbEndpoint?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val openSockets = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    /** The bound port, or -1 while stopped. */
    val port: Int
        get() = server?.takeIf { !it.isClosed }?.localPort ?: UNBOUND

    /** Binds and starts accepting, or returns the port of the already-running listener. */
    suspend fun start(): Int = startLock.withLock {
        server?.takeIf { !it.isClosed }?.let { return@withLock it.localPort }
        val bound = withContext(Dispatchers.IO) { bind() }
        server = bound
        acceptJob = scope.launch {
            while (isActive) {
                val client = try {
                    bound.accept()
                } catch (e: IOException) {
                    if (!bound.isClosed) android.util.Log.w(TAG, "accept failed: ${e.message}")
                    return@launch
                }
                launch { serve(client) }
            }
        }
        bound.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        server?.let { runCatching { it.close() } }
        server = null
        // Cancelling the connection coroutines is not enough: each is parked in a blocking read that
        // only a close can interrupt.
        openSockets.forEach { runCatching { it.close() } }
        openSockets.clear()
    }

    private fun bind(): ServerSocket {
        // IPv4 loopback explicitly — see AdbDaemon.bind: getLoopbackAddress() resolves to ::1 on this
        // platform, which no adb client dialling 127.0.0.1 can reach.
        val loopback = InetAddress.getByName(AdbHostClient.LOOPBACK)
        for (candidate in PREFERRED_PORT..LAST_PORT) {
            val bound = runCatching { ServerSocket(candidate, BACKLOG, loopback) }.getOrNull()
            if (bound != null) return bound
        }
        throw IOException("No free relay port in $PREFERRED_PORT..$LAST_PORT")
    }

    private suspend fun serve(client: Socket) {
        val upstream = try {
            connectBackend()
        } catch (e: IOException) {
            android.util.Log.w(TAG, "no reachable adb backend: ${e.message}")
            runCatching { client.close() }
            return
        }
        client.tcpNoDelay = true
        openSockets += client
        openSockets += upstream
        val endSession = {
            runCatching { client.close() }
            runCatching { upstream.close() }
            Unit
        }
        try {
            coroutineScope {
                launch { pump(client.getInputStream(), upstream.getOutputStream(), endSession) }
                launch { pump(upstream.getInputStream(), client.getOutputStream(), endSession) }
            }
        } finally {
            openSockets -= client
            openSockets -= upstream
            runCatching { client.close() }
            runCatching { upstream.close() }
        }
    }

    private suspend fun pump(source: InputStream, sink: OutputStream, onEnd: () -> Unit) {
        val buffer = ByteArray(BUFFER_BYTES)
        try {
            while (coroutineContext.isActive) {
                val read = source.read(buffer)
                if (read < 0) break
                sink.write(buffer, 0, read)
                sink.flush()
            }
        } catch (e: IOException) {
            android.util.Log.d(TAG, "relay leg ended: ${e.message}")
        } finally {
            // One direction closing ends the session; without this the opposite pump would stay parked
            // in read() forever and the connection coroutine would never complete.
            onEnd()
        }
    }

    private suspend fun connectBackend(): Socket {
        val endpoint = backend() ?: rediscover() ?: throw IOException("adb backend endpoint unknown")
        return try {
            dial(endpoint)
        } catch (e: ConnectException) {
            // A refused connect means the cached port is stale (adbd moved on a Wireless-debugging
            // toggle), not that the device is gone — so re-resolve once, then give up.
            val fresh = rediscover() ?: throw e
            dial(fresh)
        }
    }

    private suspend fun dial(endpoint: AdbEndpoint): Socket = withContext(Dispatchers.IO) {
        Socket().apply {
            tcpNoDelay = true
            connect(InetSocketAddress(endpoint.host, endpoint.port), CONNECT_TIMEOUT_MS)
        }
    }

    companion object {
        const val PREFERRED_PORT: Int = 5580
        const val LAST_PORT: Int = 5599
        const val UNBOUND: Int = -1

        private const val TAG = "AdbRelayServer"
        private const val BACKLOG = 8
        private const val BUFFER_BYTES = 64 * 1024
        private const val CONNECT_TIMEOUT_MS = 3_000
    }
}

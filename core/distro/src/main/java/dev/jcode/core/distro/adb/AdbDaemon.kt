package dev.jcode.core.distro.adb

import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** One open adb stream, seen from the service that is answering it. */
interface AdbStream {
    /** The service string the client asked for, e.g. `shell:getprop ro.build.version.sdk`. */
    val service: String

    suspend fun write(payload: ByteArray)

    suspend fun write(text: String)

    /** The next chunk the client wrote, or null once it has finished writing. */
    suspend fun read(): ByteArray?
}

/**
 * Answers one adb service. Called on [Dispatchers.IO]; the daemon closes the stream when this
 * returns, so a handler that writes nothing produces an empty, successfully-opened service.
 */
fun interface AdbServiceHandler {
    suspend fun handle(stream: AdbStream)
}

/** The one line every service this daemon does not implement answers with. */
fun unsupportedService(service: String): String = "(jcode virtual device) unsupported: $service\n"

/**
 * Splits an adb command line the way the shell that would normally run it would: on whitespace, but
 * honouring single and double quotes. `adb install` sends `cmd package 'install' -S <n>` with the
 * subcommand quoted, so a plain `split(' ')` looks for a subcommand literally named `'install'`.
 */
fun adbCommandArgs(command: String): List<String> {
    val args = mutableListOf<String>()
    val current = StringBuilder()
    var quote: Char? = null
    var quoted = false
    for (char in command) {
        when {
            quote != null && char == quote -> quote = null
            quote != null -> current.append(char)
            char == '\'' || char == '"' -> {
                quote = char
                quoted = true
            }

            char.isWhitespace() -> {
                if (current.isNotEmpty() || quoted) args += current.toString()
                current.setLength(0)
                quoted = false
            }

            else -> current.append(char)
        }
    }
    if (current.isNotEmpty() || quoted) args += current.toString()
    return args
}

/**
 * An adb **daemon**: the device end of the adb transport protocol, so JCode's virtual device shows up
 * in `adb devices` and can be installed to like any other target.
 *
 * It represents the virtual device and nothing else. It never touches the host phone's adbd, needs no
 * Developer Options, and holds no privilege beyond JCode's own — everything it exposes is served by
 * the [AdbServiceHandler] it is given.
 *
 * **Why this listener is authenticated.** On Android every app shares one loopback interface, so a
 * plain bind is reachable by every other app on the device. This daemon exposes
 * `exec:cmd package install`, i.e. "run this APK inside JCode", which unauthenticated would be an
 * arbitrary-code-execution hole with JCode's uid and permissions. Hence: [InetAddress.getLoopbackAddress]
 * and never the wildcard, plus adb's own AUTH exchange against the keys [authorizedKeys] enrolls — see
 * [AdbAuth] for what "authenticated" means here.
 *
 * [banner] is adb's connection banner *without* its terminating NUL, e.g.
 * `device::ro.product.name=…;features=cmd,…`. The `cmd` feature is load-bearing: with it `adb install`
 * opens a single `exec:cmd package 'install' -S <n>` stream; without it the client falls back to
 * `push` + `pm install` and needs the whole `sync:` service.
 */
class AdbDaemon(
    private val banner: String,
    private val authorizedKeys: () -> List<String>,
    private val handler: AdbServiceHandler,
    private val log: (String) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val startLock = Mutex()
    private val connections = ConcurrentHashMap.newKeySet<Socket>()

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var acceptJob: Job? = null

    /** The bound port, or [UNBOUND] while stopped. `adb connect 127.0.0.1:<port>` reaches it. */
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
                    if (!bound.isClosed) log("accept failed: ${e.message}")
                    return@launch
                }
                launch { Connection(client).serve() }
            }
        }
        log("listening on ${bound.inetAddress.hostAddress}:${bound.localPort}")
        bound.localPort
    }

    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        server?.let { runCatching { it.close() } }
        server = null
        // Cancelling the connection coroutines is not enough: each is parked in a blocking read that
        // only a close can interrupt.
        connections.forEach { runCatching { it.close() } }
        connections.clear()
    }

    private fun bind(): ServerSocket {
        // IPv4 loopback explicitly, NOT InetAddress.getLoopbackAddress(): that resolves to ::1 here,
        // and an adb client dialling 127.0.0.1 then gets "Connection refused" against a listener that
        // /proc/net/tcp6 shows as perfectly healthy. Measured on-device.
        val loopback = InetAddress.getByName(AdbHostClient.LOOPBACK)
        for (candidate in PREFERRED_PORT..LAST_PORT) {
            val bound = runCatching { ServerSocket(candidate, BACKLOG, loopback) }.getOrNull()
            if (bound != null) return bound
        }
        throw IOException("No free adb daemon port in $PREFERRED_PORT..$LAST_PORT")
    }

    /** One connected adb server: its AUTH state, its open streams, and the reader that drives both. */
    private inner class Connection(private val socket: Socket) {
        private val transport = AdbTransport(socket.getInputStream(), socket.getOutputStream())
        private val streams = ConcurrentHashMap<Int, Stream>()
        private val nextStreamId = AtomicInteger(1)
        private val services = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private var authenticated = false
        private var token: ByteArray? = null
        private var tokensIssued = 0
        private var peerMaxPayload = AdbWire.MAX_PAYLOAD

        fun serve() {
            connections += socket
            try {
                socket.tcpNoDelay = true
                readLoop()
            } catch (e: IOException) {
                log("connection ended: ${e.message}")
            } finally {
                services.cancel()
                streams.values.forEach(Stream::terminate)
                streams.clear()
                connections -= socket
                runCatching { socket.close() }
            }
        }

        private fun readLoop() {
            while (true) {
                val message = transport.read() ?: return
                val keepGoing = if (authenticated) {
                    session(message)
                    true
                } else {
                    handshake(message)
                }
                if (!keepGoing) return
            }
        }

        /** Returns false when the peer must be disconnected. */
        private fun handshake(message: AdbMessage): Boolean = when (message.command) {
            AdbWire.CNXN -> {
                peerMaxPayload = message.arg1.coerceIn(AdbWire.MIN_PAYLOAD, AdbWire.MAX_PAYLOAD)
                sendAuthToken()
                true
            }

            AdbWire.AUTH -> when (message.arg0) {
                AdbWire.AUTH_SIGNATURE -> verifySignature(message.payload)
                AdbWire.AUTH_RSAPUBLICKEY -> {
                    // A real phone would ask the user to trust this key. There is no such prompt here,
                    // and no way to tell an app on this device apart from the person using it, so an
                    // unenrolled key is simply refused.
                    log("refusing an adb key that is not enrolled in the distro's ~/.android/adbkey.pub")
                    false
                }

                else -> false
            }

            else -> {
                log("unexpected ${AdbWire.name(message.command)} before authentication")
                false
            }
        }

        private fun verifySignature(signature: ByteArray): Boolean {
            val challenge = token ?: return false
            val keys = authorizedKeys()
            if (keys.isEmpty()) {
                log("no adb key is enrolled: run adb in the distro once so it writes ~/.android/adbkey.pub")
                return false
            }
            val accepted = keys.asSequence()
                .mapNotNull(AdbAuth::parsePublicKey)
                .any { key -> AdbAuth.verify(challenge, signature, key) }
            if (accepted) {
                authenticated = true
                transport.write(
                    AdbMessage(
                        AdbWire.CNXN,
                        AdbWire.VERSION,
                        AdbWire.MAX_PAYLOAD,
                        (banner + AdbWire.NUL).toByteArray(Charsets.UTF_8),
                    ),
                )
                log("client authenticated")
                return true
            }
            if (tokensIssued >= MAX_AUTH_ATTEMPTS) {
                log("no enrolled key matched after $tokensIssued attempts")
                return false
            }
            sendAuthToken()
            return true
        }

        private fun sendAuthToken() {
            val challenge = AdbAuth.newToken()
            token = challenge
            tokensIssued++
            transport.write(AdbMessage(AdbWire.AUTH, AdbWire.AUTH_TOKEN, 0, challenge))
        }

        private fun session(message: AdbMessage) {
            when (message.command) {
                AdbWire.OPEN -> open(remoteId = message.arg0, service = message.text())
                AdbWire.WRTE -> streams[message.arg1]?.deliver(message.payload)
                AdbWire.OKAY -> streams[message.arg1]?.acknowledge()
                AdbWire.CLSE -> streams.remove(message.arg1)?.terminate()
                else -> log("ignoring ${AdbWire.name(message.command)} on an open connection")
            }
        }

        private fun open(remoteId: Int, service: String) {
            val localId = nextStreamId.getAndIncrement()
            val stream = Stream(localId, remoteId, service)
            streams[localId] = stream
            transport.write(AdbMessage(AdbWire.OKAY, localId, remoteId))
            services.launch {
                try {
                    handler.handle(stream)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    log("service '$service' failed: $e")
                } finally {
                    streams.remove(localId)
                    stream.terminate()
                    runCatching { transport.write(AdbMessage(AdbWire.CLSE, localId, remoteId)) }
                }
            }
        }

        private inner class Stream(
            private val localId: Int,
            private val remoteId: Int,
            override val service: String,
        ) : AdbStream {
            // Unbounded on purpose. The peer already limits itself to one unacknowledged WRTE, so the
            // window is enforced by *when* read() sends OKAY, not by blocking the reader — which would
            // stall every other stream on this connection.
            private val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            private val writable = Channel<Unit>(
                capacity = 1,
                onBufferOverflow = BufferOverflow.DROP_LATEST,
            ).apply { trySend(Unit) }

            override suspend fun write(payload: ByteArray) {
                var offset = 0
                while (offset < payload.size) {
                    val end = minOf(offset + peerMaxPayload, payload.size)
                    writable.receiveCatching().getOrNull()
                        ?: throw AdbProtocolException("'$service' was closed while writing")
                    transport.write(
                        AdbMessage(AdbWire.WRTE, localId, remoteId, payload.copyOfRange(offset, end)),
                    )
                    offset = end
                }
            }

            override suspend fun write(text: String) = write(text.toByteArray(Charsets.UTF_8))

            override suspend fun read(): ByteArray? {
                val payload = incoming.receiveCatching().getOrNull() ?: return null
                transport.write(AdbMessage(AdbWire.OKAY, localId, remoteId))
                return payload
            }

            fun deliver(payload: ByteArray) {
                incoming.trySend(payload)
            }

            fun acknowledge() {
                writable.trySend(Unit)
            }

            fun terminate() {
                incoming.close()
                writable.close()
            }
        }
    }

    companion object {
        /** Clear of adb's emulator scan (5554-5585) and of [AdbRelayServer]'s range. */
        const val PREFERRED_PORT: Int = 5620
        const val LAST_PORT: Int = 5639
        const val UNBOUND: Int = -1

        private const val BACKLOG = 4
        private const val MAX_AUTH_ATTEMPTS = 8
    }
}

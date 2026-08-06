package dev.jcode.core.distro.adb

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Speaks the adb HOST protocol to a running adb server.
 *
 * The server itself lives inside the proot distro (`adb start-server`), but proot shares the app's
 * network namespace, so its 127.0.0.1:5037 listener is the app's 127.0.0.1:5037 — no bind, no tunnel.
 *
 * Wire format: a request is `%04x` of its UTF-8 byte length followed by the service string; the reply
 * is `OKAY` or `FAIL`, and every payload that follows carries its own 4-hex-digit length prefix.
 */
class AdbHostClient(
    private val host: String = LOOPBACK,
    private val port: Int = DEFAULT_SERVER_PORT,
) {
    /** adb's protocol version, or null when no server is listening — the "is it up?" probe. */
    suspend fun version(): Int? = withContext(Dispatchers.IO) {
        runCatching {
            session().use { session ->
                session.request("host:version")
                session.readMessage().trim().toIntOrNull(radix = 16)
            }
        }.getOrNull()
    }

    suspend fun devices(): List<AdbDevice> = withContext(Dispatchers.IO) {
        session().use { session ->
            session.request("host:devices-l")
            parseDevices(session.readMessage())
        }
    }

    /** Returns adb's own human-readable answer, which reports failure as text rather than as `FAIL`. */
    suspend fun connect(target: String): String = hostQuery("host:connect:$target")

    /**
     * Installs a port forward on [serial] without switching this socket's transport: `host-serial:`
     * addresses a device for a single host service, where `host:transport:` would rebind the socket
     * to the device for everything that follows.
     */
    suspend fun forward(serial: String, local: String, remote: String): Unit = withContext(Dispatchers.IO) {
        val service = "host-serial:$serial:forward:$local;$remote"
        session().use { session ->
            session.request(service)
            session.readTrailingStatus(service)
        }
    }

    /** Removes a forward installed by [forward]. Failures are swallowed: this is teardown, and a
     *  forward that is already gone is the outcome the caller wanted. */
    suspend fun killForward(serial: String, local: String): Unit = withContext(Dispatchers.IO) {
        val service = "host-serial:$serial:killforward:$local"
        runCatching {
            session().use { session ->
                session.request(service)
                session.readTrailingStatus(service)
            }
        }
        Unit
    }

    /**
     * Runs [cmd] over the framed shell v2 service, which separates stdout from stderr and ends with the
     * real exit code.
     *
     * The `,raw:` (no PTY) form is mandatory: with a PTY, the kernel line discipline rewrites every \n
     * into \r\n and echoes the command back, quietly corrupting anything parsed downstream.
     */
    suspend fun shellV2(
        serial: String,
        cmd: String,
        timeoutMs: Int = COMMAND_TIMEOUT_MS,
    ): AdbExec = withContext(Dispatchers.IO) {
        session(timeoutMs).use { session ->
            session.transport(serial)
            session.request("shell,v2,raw:$cmd")
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            var exitCode: Int? = null
            session.readShellV2 { id, payload ->
                when (id) {
                    ID_STDOUT -> stdout.write(payload)
                    ID_STDERR -> stderr.write(payload)
                    ID_EXIT -> exitCode = payload.firstOrNull()?.toInt()?.and(0xFF)
                }
            }
            AdbExec(
                stdout = stdout.toString(Charsets.UTF_8.name()),
                stderr = stderr.toString(Charsets.UTF_8.name()),
                exitCode = exitCode,
            )
        }
    }

    private suspend fun hostQuery(service: String): String = withContext(Dispatchers.IO) {
        session().use { session ->
            session.request(service)
            session.readMessage()
        }
    }

    private fun session(soTimeoutMs: Int = CONTROL_TIMEOUT_MS): AdbSession {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = soTimeoutMs
        return AdbSession(socket)
    }

    private fun parseDevices(payload: String): List<AdbDevice> = payload.lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .mapNotNull { line ->
            val fields = line.split(FIELD_SEPARATOR)
            val serial = fields.getOrNull(0) ?: return@mapNotNull null
            val state = fields.getOrNull(1) ?: return@mapNotNull null
            AdbDevice(
                serial = serial,
                state = state,
                model = fields.firstOrNull { it.startsWith(MODEL_PREFIX) }?.removePrefix(MODEL_PREFIX),
            )
        }
        .toList()

    companion object {
        const val LOOPBACK: String = "127.0.0.1"
        const val DEFAULT_SERVER_PORT: Int = 5037

        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val CONTROL_TIMEOUT_MS = 10_000
        private const val COMMAND_TIMEOUT_MS = 60_000
        private const val MODEL_PREFIX = "model:"
        private val FIELD_SEPARATOR = Regex("\\s+")
    }
}

private const val OKAY = "OKAY"
private const val FAIL = "FAIL"
private const val STATUS_BYTES = 4
private const val LENGTH_BYTES = 4
private const val HEADER_BYTES = 5
private const val ID_STDOUT = 1
private const val ID_STDERR = 2
private const val ID_EXIT = 3

/** Shell v2 payloads are chunks of a live stream; anything larger is a desynchronised socket. */
private const val MAX_FRAME_BYTES = 1 shl 20

/** One socket to the adb server, and the framing rules that apply to it. */
private class AdbSession(private val socket: Socket) : Closeable {
    private val input: InputStream = socket.getInputStream()
    private val output: OutputStream = socket.getOutputStream()

    fun request(service: String) {
        val payload = service.toByteArray(Charsets.UTF_8)
        output.write(String.format(Locale.ROOT, "%04x", payload.size).toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
        checkStatus(readExactly(STATUS_BYTES).toAscii(), service)
    }

    /**
     * Switches this socket to [serial]'s device channel. After the OKAY, the socket no longer belongs
     * to the host server: the NEXT [request] on it is delivered to the device and answers with its own,
     * SECOND OKAY. `host:` services, by contrast, answer once and close.
     */
    fun transport(serial: String) = request("host:transport:$serial")

    fun readMessage(): String {
        val length = readExactly(LENGTH_BYTES).toAscii().toIntOrNull(radix = 16)
            ?: throw AdbProtocolException("adb sent an unparseable length header")
        return String(readExactly(length), Charsets.UTF_8)
    }

    /**
     * `forward:` is answered TWICE — once for "service accepted", once for "forward installed" — unlike
     * every other host service. Servers that predate the second word simply close instead, so a clean
     * EOF here also means success.
     */
    fun readTrailingStatus(service: String) {
        val status = ByteArray(STATUS_BYTES)
        var filled = 0
        while (filled < STATUS_BYTES) {
            val read = input.read(status, filled, STATUS_BYTES - filled)
            if (read < 0) return
            filled += read
        }
        checkStatus(status.toAscii(), service)
    }

    suspend fun readShellV2(onFrame: suspend (id: Int, payload: ByteArray) -> Unit) {
        while (true) {
            val header = readHeader() ?: return
            val id = header[0].toInt() and 0xFF
            // The frame length is the one LITTLE-endian field in the whole adb protocol; every other
            // length on the wire is four ASCII hex digits.
            val length = (header[1].toInt() and 0xFF) or
                ((header[2].toInt() and 0xFF) shl 8) or
                ((header[3].toInt() and 0xFF) shl 16) or
                ((header[4].toInt() and 0xFF) shl 24)
            if (length !in 0..MAX_FRAME_BYTES) {
                throw AdbProtocolException("shell v2 frame claims $length bytes")
            }
            onFrame(id, readExactly(length))
            if (id == ID_EXIT) return
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    private fun readHeader(): ByteArray? {
        val header = ByteArray(HEADER_BYTES)
        var filled = 0
        while (filled < HEADER_BYTES) {
            val read = input.read(header, filled, HEADER_BYTES - filled)
            if (read < 0) return if (filled == 0) null else throw AdbProtocolException("truncated shell v2 header")
            filled += read
        }
        return header
    }

    private fun readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(bytes, filled, count - filled)
            if (read < 0) throw AdbProtocolException("adb closed the connection after $filled of $count bytes")
            filled += read
        }
        return bytes
    }

    private fun checkStatus(status: String, service: String) {
        when (status) {
            OKAY -> Unit
            FAIL -> throw AdbProtocolException("adb rejected '$service': ${readMessage()}")
            else -> throw AdbProtocolException("adb answered '$status' to '$service'")
        }
    }
}

private fun ByteArray.toAscii(): String = String(this, Charsets.US_ASCII)

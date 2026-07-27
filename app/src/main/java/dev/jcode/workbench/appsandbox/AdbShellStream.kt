package dev.jcode.workbench.appsandbox

import dev.jcode.core.distro.adb.AdbHostClient
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.Locale

/**
 * One `exec:` stream to a device, held open for as long as the command should keep running.
 *
 * [AdbHostClient] only offers run-to-completion commands, and the display server never completes: it
 * has to keep writing to a stream JCode is still reading. That is also its lifeline — adbd tears the
 * command down when this socket closes, which is precisely how the server learns JCode is gone.
 */
internal class AdbShellStream private constructor(private val socket: Socket) : Closeable {

    private val input: InputStream = socket.getInputStream()

    /** Stops the read timeout used while waiting for the command's first output. */
    fun clearReadTimeout() {
        runCatching { socket.soTimeout = 0 }
    }

    /** Next line of the folded stdout+stderr stream, or null at EOF. */
    fun readLine(): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            when {
                byte < 0 -> return if (line.size() == 0) null else line.toString(Charsets.UTF_8.name())
                byte == NEWLINE -> return line.toString(Charsets.UTF_8.name())
                byte != CARRIAGE_RETURN -> line.write(byte)
            }
        }
    }

    override fun close() {
        runCatching { socket.close() }
    }

    companion object {
        private const val NEWLINE = '\n'.code
        private const val CARRIAGE_RETURN = '\r'.code
        private const val CONNECT_TIMEOUT_MS = 2_000
        private const val OKAY = "OKAY"
        private const val FAIL = "FAIL"

        /** Starts [command] on [serial] and returns the live stream; close it to kill the command. */
        fun exec(serial: String, command: String, readTimeoutMs: Int): AdbShellStream {
            val socket = Socket()
            socket.tcpNoDelay = true
            socket.connect(
                InetSocketAddress(AdbHostClient.LOOPBACK, AdbHostClient.DEFAULT_SERVER_PORT),
                CONNECT_TIMEOUT_MS,
            )
            socket.soTimeout = readTimeoutMs
            return runCatching {
                val stream = AdbShellStream(socket)
                stream.request("host:transport:$serial")
                stream.request("exec:$command")
                stream
            }.getOrElse { error ->
                runCatching { socket.close() }
                throw error
            }
        }
    }

    private fun request(service: String) {
        val payload = service.toByteArray(Charsets.UTF_8)
        val output = socket.getOutputStream()
        output.write(String.format(Locale.ROOT, "%04x", payload.size).toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
        when (val status = String(readExactly(4), Charsets.US_ASCII)) {
            OKAY -> Unit
            FAIL -> {
                val length = String(readExactly(4), Charsets.US_ASCII).toIntOrNull(radix = 16) ?: 0
                throw IOException("adb rejected '$service': ${String(readExactly(length), Charsets.UTF_8)}")
            }
            else -> throw IOException("adb answered '$status' to '$service'")
        }
    }

    private fun readExactly(count: Int): ByteArray {
        val bytes = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(bytes, filled, count - filled)
            if (read < 0) throw IOException("adb closed the connection after $filled of $count bytes")
            filled += read
        }
        return bytes
    }
}

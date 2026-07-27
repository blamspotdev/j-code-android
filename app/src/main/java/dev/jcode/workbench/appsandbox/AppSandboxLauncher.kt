package dev.jcode.workbench.appsandbox

import android.content.Context
import android.util.Base64
import android.util.Size
import dev.jcode.core.distro.adb.AdbBridgeLocator
import dev.jcode.core.distro.adb.AdbBridgeState
import dev.jcode.core.distro.adb.AdbHostClient
import dev.jcode.display.Protocol
import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** How the guest app can be shown, given what this window and this device support. */
internal enum class AppSandboxTier {
    /** Streamed from a shell-owned virtual display into the tab. */
    Embedded,

    /** No embedding possible; the app can still be launched on the phone's own display. */
    DeviceDisplay,
}

/**
 * Brings a sandbox session up: ships the display server's dex to the device, starts it as the shell
 * user and connects the two channels it serves.
 *
 * The JCode uid cannot write `/data/local/tmp` and `app_process` (running as shell) cannot read
 * JCode's own files, so the dex has to travel base64 through the adb shell — there is no path either
 * side can reach directly.
 */
internal object AppSandboxLauncher {

    /**
     * A [AppSandboxTier.Embedded] sandbox decodes into a [android.view.SurfaceView], which needs the
     * window to be hardware accelerated. That is a real state here: the manifest ships
     * `hardwareAccelerated="false"` and MainActivity only turns it on from the user's preference.
     */
    fun tier(hardwareAccelerated: Boolean): AppSandboxTier =
        if (hardwareAccelerated) AppSandboxTier.Embedded else AppSandboxTier.DeviceDisplay

    suspend fun start(
        context: Context,
        packageName: String?,
        size: Size,
        densityDpi: Int,
        onProgress: (String) -> Unit,
    ): AppSandboxSession = withContext(Dispatchers.IO) {
        val bridge = AdbBridgeLocator.adbBridge(context)
        onProgress("Connecting to this device…")
        val state = bridge.ensureConnected()
        if (state !is AdbBridgeState.Ready) {
            throw IOException(
                when (state) {
                    is AdbBridgeState.Degraded -> state.reason
                    is AdbBridgeState.Failed -> state.message
                    else -> "The ADB bridge is not connected."
                },
            )
        }
        val serial = bridge.serial.value ?: throw IOException("No device serial — pair this phone first.")
        if (packageName != null && !PACKAGE_NAME.matches(packageName)) {
            throw IOException("\"$packageName\" is not a package name.")
        }

        // A server left behind by a crashed session still owns a virtual display and would keep
        // encoding into nothing.
        bridge.client.shellV2(serial, "pkill -f $PROCESS_NAME; pkill -f $MAIN_CLASS; true")

        onProgress("Installing the display server…")
        deployDex(context, bridge.client, serial)

        val token = randomToken()
        onProgress("Starting the display server…")
        val server = AdbShellStream.exec(serial, launchCommand(token, packageName, size, densityDpi), STARTUP_TIMEOUT_MS)
        val port = runCatching { awaitPort(server) }.getOrElse { error ->
            server.close()
            throw error
        }

        runCatching {
            val videoSocket = connect(port, token, Protocol.CHANNEL_VIDEO)
            val handshake = readHandshake(videoSocket)
            val controlSocket = connect(port, token, Protocol.CHANNEL_CONTROL)
            AppSandboxSession(
                packageName = packageName,
                displayId = handshake.displayId,
                video = videoSocket,
                control = controlSocket,
                server = server,
                initialCodecSpecificData = handshake.codecSpecificData,
                initialSize = handshake.size,
            )
        }.getOrElse { error ->
            server.close()
            throw error
        }
    }

    /** Launches [packageName] on the phone's own display — the fallback when embedding is unavailable. */
    suspend fun launchOnDevice(context: Context, packageName: String): String? = withContext(Dispatchers.IO) {
        if (!PACKAGE_NAME.matches(packageName)) return@withContext "\"$packageName\" is not a package name."
        val bridge = AdbBridgeLocator.adbBridge(context)
        if (bridge.ensureConnected() !is AdbBridgeState.Ready) return@withContext "The ADB bridge is not connected."
        val serial = bridge.serial.value ?: return@withContext "No device serial — pair this phone first."
        val result = bridge.client.shellV2(serial, "monkey -p '$packageName' -c android.intent.category.LAUNCHER 1")
        if (result.succeeded) null else "Could not launch $packageName."
    }

    private fun launchCommand(token: String, packageName: String?, size: Size, densityDpi: Int): String {
        val arguments = buildList {
            add("--port=0")
            add("--token=$token")
            add("--size=${size.width}x${size.height}")
            add("--dpi=$densityDpi")
            if (!packageName.isNullOrBlank()) add("--package=$packageName")
        }
        // --nice-name is what makes `pkill -f jcode-display-server` find an orphan: it rewrites argv[0],
        // and CLASSPATH (where the dex path lives) never shows up in /proc/<pid>/cmdline.
        return "CLASSPATH=$REMOTE_DEX app_process / --nice-name=$PROCESS_NAME $MAIN_CLASS " +
            arguments.joinToString(" ")
    }

    /** The server prints `PORT=<n>` once it is listening; everything before that is its own logging,
     *  which is the only diagnostic available if it dies on the way up. */
    private fun awaitPort(server: AdbShellStream): Int {
        val preamble = mutableListOf<String>()
        while (true) {
            val line = server.readLine()
                ?: throw IOException(
                    "The display server exited before it started listening." +
                        preamble.takeLast(PREAMBLE_LINES).joinToString("") { "\n$it" },
                )
            if (line.startsWith(PORT_PREFIX)) {
                return line.removePrefix(PORT_PREFIX).trim().toIntOrNull()
                    ?: throw IOException("The display server reported an unusable port: $line")
            }
            preamble += line
        }
    }

    private fun connect(port: Int, token: String, channel: Int): Socket {
        val socket = Socket()
        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(LOOPBACK, port), CONNECT_TIMEOUT_MS)
        socket.soTimeout = HANDSHAKE_TIMEOUT_MS
        return runCatching {
            // Both channels open on the one listening port with [32-byte token][u8 channel].
            socket.getOutputStream().apply {
                write(token.toByteArray(Charsets.US_ASCII))
                write(channel)
                flush()
            }
            socket
        }.getOrElse { error ->
            runCatching { socket.close() }
            throw error
        }
    }

    private class Handshake(val displayId: Int, val size: Size, val codecSpecificData: ByteArray)

    private fun readHandshake(socket: Socket): Handshake {
        val input = DataInputStream(socket.getInputStream())
        val magic = input.readInt()
        if (magic != Protocol.MAGIC) throw IOException("Not a display server on the other end.")
        val version = input.readInt()
        if (version != Protocol.VERSION) throw IOException("Display server speaks protocol $version, not ${Protocol.VERSION}.")
        val displayId = input.readInt()
        val width = input.readInt()
        val height = input.readInt()
        val codec = input.readInt()
        if (codec != Protocol.CODEC_H264) throw IOException("Unsupported video codec.")
        val csdLength = input.readInt()
        if (csdLength <= 0 || csdLength > MAX_CSD_BYTES) throw IOException("Display server sent no usable SPS/PPS.")
        val csd = ByteArray(csdLength)
        input.readFully(csd)
        // Frames are read with a long timeout from here on; the encoder is idle whenever nothing moves.
        socket.soTimeout = 0
        return Handshake(displayId, Size(width, height), csd)
    }

    /**
     * Writes the dex out in base64 chunks. Each chunk is a multiple of four base64 characters so it
     * decodes on its own and can simply be appended, and the heredoc terminator gets a line to itself
     * — anything after it on the same line lands inside the file.
     */
    private suspend fun deployDex(context: Context, client: AdbHostClient, serial: String) {
        val dex = context.assets.open(DEX_ASSET).use { it.readBytes() }
        val encoded = Base64.encodeToString(dex, Base64.NO_WRAP)
        client.shellV2(serial, "rm -f $REMOTE_DEX")
        var offset = 0
        while (offset < encoded.length) {
            val end = minOf(offset + CHUNK_CHARS, encoded.length)
            val chunk = encoded.substring(offset, end)
            val result = client.shellV2(serial, "base64 -d >> $REMOTE_DEX <<'$HEREDOC'\n$chunk\n$HEREDOC\n")
            if (!result.succeeded) {
                throw IOException("Could not write the display server to the device: ${result.stderr.trim()}")
            }
            offset = end
        }
        val size = client.shellV2(serial, "wc -c < $REMOTE_DEX").stdout.trim().toIntOrNull()
        if (size != dex.size) {
            throw IOException("The display server did not survive the trip to the device ($size of ${dex.size} bytes).")
        }
    }

    private fun randomToken(): String {
        val random = SecureRandom()
        return String(CharArray(Protocol.TOKEN_LENGTH) { TOKEN_ALPHABET[random.nextInt(TOKEN_ALPHABET.length)] })
    }

    private const val LOOPBACK = "127.0.0.1"
    private const val DEX_ASSET = "tools/jcode-display-server.dex"
    private const val REMOTE_DEX = "/data/local/tmp/jcode-display-server.dex"
    private const val PROCESS_NAME = "jcode-display-server"
    private const val MAIN_CLASS = "dev.jcode.display.DisplayServer"
    private const val PORT_PREFIX = "PORT="
    private const val HEREDOC = "JCODE_DEX_B64"

    /** A multiple of four, so every chunk is a whole number of base64 quanta that decodes on its own.
     *  Also well under adb's 4-hex-digit service length and the kernel's single-argument limit. */
    private const val CHUNK_CHARS = 32_000
    private const val PREAMBLE_LINES = 6
    private const val MAX_CSD_BYTES = 64 * 1024
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val HANDSHAKE_TIMEOUT_MS = 10_000
    private const val STARTUP_TIMEOUT_MS = 20_000
    private const val TOKEN_ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /** The package reaches the device inside a shell command line, so it has to be exactly a package. */
    private val PACKAGE_NAME = Regex("""[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)*""")
}

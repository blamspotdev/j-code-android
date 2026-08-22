package dev.blamspot.jcode.core.distro.adb

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** An adb peer refused a request, or sent something this code cannot parse. */
class AdbProtocolException(message: String) : IOException(message)

/**
 * adb's **transport** protocol — what an adb server speaks to a *device* — as opposed to the `host:`
 * service protocol [AdbHostClient] speaks to an adb *server*.
 *
 * Every message is a 24-byte header of six little-endian uint32s (`command, arg0, arg1, data_length,
 * data_check, magic`), optionally followed by `data_length` payload bytes. `magic` is `command` with
 * every bit flipped and is the only header field either side still validates.
 */
internal object AdbWire {
    const val CNXN: Int = 0x4E584E43
    const val OPEN: Int = 0x4E45504F
    const val OKAY: Int = 0x59414B4F
    const val CLSE: Int = 0x45534C43
    const val WRTE: Int = 0x45545257
    const val AUTH: Int = 0x48545541

    /**
     * The last protocol version that predates STARTTLS. Announcing anything newer invites the client
     * to negotiate TLS (`A_STLS`), which this daemon does not speak.
     */
    const val VERSION: Int = 0x0100_0000

    const val MAX_PAYLOAD: Int = 256 * 1024

    const val AUTH_TOKEN: Int = 1
    const val AUTH_SIGNATURE: Int = 2
    const val AUTH_RSAPUBLICKEY: Int = 3

    const val HEADER_BYTES: Int = 24

    /** Slack for a peer that ignores our advertised [MAX_PAYLOAD]; past this the socket is desynchronised. */
    const val MAX_INCOMING_PAYLOAD: Int = 1 shl 20

    /** Smallest maxdata any adb build has ever advertised — the floor when clamping a peer's value. */
    const val MIN_PAYLOAD: Int = 4096

    /** adb terminates banners and service names with this; Kotlin cannot spell it as a source literal. */
    val NUL: Char = Char.MIN_VALUE

    fun name(command: Int): String = when (command) {
        CNXN -> "CNXN"
        OPEN -> "OPEN"
        OKAY -> "OKAY"
        CLSE -> "CLSE"
        WRTE -> "WRTE"
        AUTH -> "AUTH"
        else -> "0x" + Integer.toHexString(command)
    }
}

private val EMPTY_PAYLOAD = ByteArray(0)

internal class AdbMessage(
    val command: Int,
    val arg0: Int,
    val arg1: Int,
    val payload: ByteArray = EMPTY_PAYLOAD,
) {
    /** The payload as the NUL-terminated string adb uses for service names and connection banners. */
    fun text(): String = String(payload, Charsets.UTF_8).trimEnd(AdbWire.NUL)
}

/** One adb transport socket. [write] is safe to call from any thread; [read] belongs to one reader. */
internal class AdbTransport(
    private val input: InputStream,
    private val output: OutputStream,
) {
    /** The next message, or null at a clean end of stream. */
    fun read(): AdbMessage? {
        val header = readFully(AdbWire.HEADER_BYTES) ?: return null
        val command = header.int(0)
        val magic = header.int(20)
        if (magic != command.inv()) {
            throw AdbProtocolException("adb header magic ${Integer.toHexString(magic)} does not match its command")
        }
        val length = header.int(12)
        if (length !in 0..AdbWire.MAX_INCOMING_PAYLOAD) {
            throw AdbProtocolException("adb ${AdbWire.name(command)} claims $length payload bytes")
        }
        val payload = if (length == 0) {
            EMPTY_PAYLOAD
        } else {
            readFully(length) ?: throw AdbProtocolException("adb closed mid-payload of ${AdbWire.name(command)}")
        }
        return AdbMessage(command, header.int(4), header.int(8), payload)
    }

    @Synchronized
    fun write(message: AdbMessage) {
        val header = ByteArray(AdbWire.HEADER_BYTES)
        header.putInt(0, message.command)
        header.putInt(4, message.arg0)
        header.putInt(8, message.arg1)
        header.putInt(12, message.payload.size)
        header.putInt(16, checksum(message.command, message.payload))
        header.putInt(20, message.command.inv())
        output.write(header)
        if (message.payload.isNotEmpty()) output.write(message.payload)
        output.flush()
    }

    private fun readFully(count: Int): ByteArray? {
        val bytes = ByteArray(count)
        var filled = 0
        while (filled < count) {
            val read = input.read(bytes, filled, count - filled)
            if (read < 0) return if (filled == 0) null else throw AdbProtocolException("adb sent a truncated header")
            filled += read
        }
        return bytes
    }
}

/**
 * adb's `data_check`. protocol.txt calls it a CRC32; it is in fact a plain sum of the payload bytes,
 * and current adb fills it in for CNXN/AUTH only — every other message carries 0. Nothing in a modern
 * adb verifies it, which is why the read path ignores it entirely.
 */
private fun checksum(command: Int, payload: ByteArray): Int =
    if (command == AdbWire.CNXN || command == AdbWire.AUTH) {
        payload.fold(0) { sum, byte -> sum + (byte.toInt() and 0xFF) }
    } else {
        0
    }

private fun ByteArray.int(offset: Int): Int =
    (this[offset].toInt() and 0xFF) or
        ((this[offset + 1].toInt() and 0xFF) shl 8) or
        ((this[offset + 2].toInt() and 0xFF) shl 16) or
        ((this[offset + 3].toInt() and 0xFF) shl 24)

private fun ByteArray.putInt(offset: Int, value: Int) {
    this[offset] = value.toByte()
    this[offset + 1] = (value ushr 8).toByte()
    this[offset + 2] = (value ushr 16).toByte()
    this[offset + 3] = (value ushr 24).toByte()
}

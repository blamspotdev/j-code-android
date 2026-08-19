package dev.jcode.adb

import dev.jcode.core.distro.adb.AdbStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The adb `sync:` service — what `adb pull`, `adb push` and `adb shell ls` on the host side are.
 *
 * Everything else this daemon serves is a command with an answer. `sync:` is the one service that is
 * a *session*: the client opens one stream and then sends framed requests down it until it sends
 * `QUIT`, and each request may be answered with an unbounded number of frames. So it is written as a
 * loop over [SyncReader] rather than as another `when` branch.
 *
 * **Version 1 on purpose.** A client uses `STA2`/`LST2` when the device's banner advertises
 * `stat_v2`/`ls_v2` and the plain `STAT`/`LIST` otherwise, so *not* advertising them is what keeps
 * this to four request types with fixed 16-byte headers instead of two parallel encodings with
 * 64-bit fields and errno mapping. A pull is a pull either way; nothing a driver can see is missing.
 *
 * Paths are the device's — `/sdcard/…` — and every one is resolved through
 * [dev.jcode.vdevice.VirtualStorage.resolve], which compares canonical paths, so a request cannot
 * walk out of the device's storage into JCode's own data directory.
 */
internal class AdbSync(private val resolve: (String) -> File?) {

    suspend fun serve(stream: AdbStream) {
        val reader = SyncReader(stream)
        while (true) {
            val id = reader.id() ?: return
            val length = reader.int() ?: return
            when (id) {
                STAT -> stat(stream, reader.text(length) ?: return)
                LIST -> list(stream, reader.text(length) ?: return)
                RECV -> recv(stream, reader.text(length) ?: return)
                SEND -> if (!send(stream, reader, reader.text(length) ?: return)) return
                QUIT -> return
                else -> {
                    stream.write(fail("unsupported sync request '${tag(id)}'"))
                    return
                }
            }
        }
    }

    /** `STAT`: mode, size and mtime, or three zeros for a path that is not there. */
    private suspend fun stat(stream: AdbStream, path: String) {
        val file = resolve(path)
        val exists = file != null && file.exists()
        stream.write(
            frame(STAT) {
                putInt(if (exists) modeOf(file!!) else 0)
                putInt(if (exists && file!!.isFile) file.length().toInt() else 0)
                putInt(if (exists) (file!!.lastModified() / 1000L).toInt() else 0)
            },
        )
    }

    /**
     * `LIST`: one `DENT` per child, then a `DONE`.
     *
     * The terminator has to be a **whole, empty `DENT`**, not a bare id. The client reads
     * `sizeof(sync_dent)` bytes and only then looks at the id, so a `DONE` written four bytes short
     * leaves it blocked on the rest of a struct that never arrives — a hang rather than an error,
     * and only on the requests that list a directory.
     */
    private suspend fun list(stream: AdbStream, path: String) {
        resolve(path)?.listFiles().orEmpty().forEach { child ->
            val name = child.name.toByteArray(Charsets.UTF_8)
            stream.write(
                frame(DENT, extra = name.size) {
                    putInt(modeOf(child))
                    putInt(if (child.isFile) child.length().toInt() else 0)
                    putInt((child.lastModified() / 1000L).toInt())
                    putInt(name.size)
                    put(name)
                },
            )
        }
        stream.write(frame(DONE) { putInt(0); putInt(0); putInt(0); putInt(0) })
    }

    /** `RECV` — the device end of `adb pull`: the file in `DATA` chunks, then `DONE`. */
    private suspend fun recv(stream: AdbStream, path: String) {
        val file = resolve(path)
        if (file == null || !file.isFile) {
            stream.write(fail("$path does not exist on the virtual device"))
            return
        }
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                stream.write(header(DATA, read) + buffer.copyOf(read))
            }
        }
        stream.write(header(DONE, (file.lastModified() / 1000L).toInt()))
    }

    /**
     * `SEND` — the device end of `adb push`. The path arrives with the mode after a comma, then the
     * content in `DATA` chunks, then `DONE` carrying the mtime to stamp the file with.
     *
     * Returns false when the stream ended mid-transfer, which ends the session: there is no way to
     * resynchronise a framed protocol from half a frame.
     */
    private suspend fun send(stream: AdbStream, reader: SyncReader, spec: String): Boolean {
        val path = spec.substringBeforeLast(',')
        val target = resolve(path)
        if (target == null) {
            stream.write(fail("$path is not on the virtual device"))
            return false
        }
        // A push to an existing directory means "into it", the way cp does and the way the client
        // assumes when it pushes a whole tree.
        val file = if (target.isDirectory) File(target, path.substringAfterLast('/')) else target
        file.parentFile?.mkdirs()

        var failure: String? = null
        val out = runCatching { file.outputStream() }.getOrNull()
        if (out == null) failure = "cannot write $path"
        try {
            while (true) {
                val id = reader.id() ?: return false
                val length = reader.int() ?: return false
                when (id) {
                    DATA -> {
                        val payload = reader.bytes(length) ?: return false
                        runCatching { out?.write(payload) }
                            .onFailure { failure = failure ?: "cannot write $path: ${it.message}" }
                    }

                    DONE -> {
                        // `length` is the mtime for this one frame, not a byte count.
                        runCatching { file.setLastModified(length * 1000L) }
                        break
                    }

                    else -> {
                        failure = failure ?: "unexpected '${tag(id)}' while receiving $path"
                        break
                    }
                }
            }
        } finally {
            runCatching { out?.close() }
        }
        failure?.let {
            file.delete()
            stream.write(fail(it))
            return true
        }
        stream.write(header(OKAY, 0))
        return true
    }

    /** `S_IFREG | 0644` or `S_IFDIR | 0755` — what a client reads to tell a file from a directory. */
    private fun modeOf(file: File): Int = if (file.isDirectory) 0x41ED else 0x81A4

    private fun fail(message: String): ByteArray {
        val body = message.toByteArray(Charsets.UTF_8)
        return header(FAIL, body.size) + body
    }

    private fun header(id: Int, value: Int): ByteArray =
        ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(id).putInt(value).array()

    /**
     * One framed reply: the request id, then whatever [body] writes, trimmed to what was written.
     *
     * The header is allocated at the largest a fixed frame gets ([HEADER]) rather than at each
     * caller's exact size, and trimmed afterwards. Sizing it per call is the shape of this that
     * invites an off-by-one: `DENT` carries a fourth `int` before its name that the other frames do
     * not, so an allocation of "16 plus the name" is four bytes short and throws — on `LIST` only,
     * which is the one request a `pull` of a single file never makes.
     */
    private inline fun frame(id: Int, extra: Int = 0, body: ByteBuffer.() -> Unit): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER + extra).order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(id)
        buffer.body()
        return buffer.array().copyOf(buffer.position())
    }

    private fun tag(id: Int): String =
        String(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(id).array(), Charsets.US_ASCII)

    /**
     * Reads exact byte counts off a stream that arrives in whatever sizes the transport chose.
     *
     * [AdbStream.read] hands back one `WRTE` payload at a time, and a sync frame has no relationship
     * to those boundaries — a 16-byte header can straddle two of them and a 64 KB chunk can arrive as
     * five. Null from any of these means the client stopped writing, which is the end of the session
     * rather than an error.
     */
    private class SyncReader(private val stream: AdbStream) {
        private var pending: ByteArray = ByteArray(0)
        private var offset = 0

        suspend fun bytes(count: Int): ByteArray? {
            val out = ByteArray(count)
            var filled = 0
            while (filled < count) {
                if (offset >= pending.size) {
                    pending = stream.read() ?: return null
                    offset = 0
                    continue
                }
                val take = minOf(count - filled, pending.size - offset)
                pending.copyInto(out, filled, offset, offset + take)
                filled += take
                offset += take
            }
            return out
        }

        suspend fun id(): Int? = int()

        suspend fun int(): Int? = bytes(4)
            ?.let { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).int }

        suspend fun text(length: Int): String? =
            bytes(length)?.toString(Charsets.UTF_8)
    }

    private companion object {
        /** `SYNC_DATA_MAX`, the largest payload the client will accept in one `DATA` frame. */
        const val CHUNK = 64 * 1024

        /** The longest fixed part of any frame: `DENT`'s id, mode, size, mtime and name length. */
        const val HEADER = 20

        val STAT = id("STAT")
        val LIST = id("LIST")
        val SEND = id("SEND")
        val RECV = id("RECV")
        val QUIT = id("QUIT")
        val DENT = id("DENT")
        val DONE = id("DONE")
        val DATA = id("DATA")
        val OKAY = id("OKAY")
        val FAIL = id("FAIL")

        /** The four-character request ids are little-endian ints on the wire. */
        fun id(name: String): Int =
            ByteBuffer.wrap(name.toByteArray(Charsets.US_ASCII)).order(ByteOrder.LITTLE_ENDIAN).int
    }
}

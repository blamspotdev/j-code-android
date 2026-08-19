package dev.blamspot.jcode.core.lsp

import java.io.Closeable

/**
 * Bidirectional byte transport for a language server. [read] blocks; it returns the number of bytes
 * read, 0 when idle, or a negative value at EOF.
 *
 * Backed by a child process's stdio PIPES, never a PTY: a PTY echoes everything written to it back
 * into the read stream, so the client would parse its own requests as server messages and the
 * JSON-RPC framing would never resynchronise. [dev.blamspot.jcode.core.debug.DapTransport] exists for the
 * same reason.
 */
interface LspTransport : Closeable {
    fun read(buffer: ByteArray): Int
    fun write(bytes: ByteArray)
}

/**
 * Adapts a child process's stdio pipes to [LspTransport] (blocking reads, no PTY echo).
 *
 * [onStderr] receives the server's stderr lines. Draining that pipe is not optional — a server that
 * logs steadily (jdtls, rust-analyzer) blocks forever once the pipe buffer fills.
 */
class ProcessLspTransport(
    private val process: Process,
    onStderr: ((String) -> Unit)? = null,
) : LspTransport {
    private val input = process.inputStream
    private val output = process.outputStream

    init {
        Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { onStderr?.invoke(it) } }
        }.apply { isDaemon = true }.start()
    }

    override fun read(buffer: ByteArray): Int = try { input.read(buffer) } catch (e: Exception) { -1 }

    override fun write(bytes: ByteArray) {
        try {
            output.write(bytes)
            output.flush()
        } catch (_: Exception) {
        }
    }

    override fun close() {
        runCatching { process.destroy() }
    }
}

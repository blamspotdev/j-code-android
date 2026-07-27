package dev.jcode.workbench.appsandbox

import android.media.MediaCodec
import android.media.MediaFormat
import android.util.Size
import android.view.Surface
import dev.jcode.display.Protocol
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Why a sandbox session is no longer streaming. Null while it is healthy. */
internal data class AppSandboxStopped(val reason: String)

/**
 * One live app sandbox: the two sockets to the on-device display server, the H.264 decoder that turns
 * its video channel into frames on a [Surface], and the control channel that carries input back.
 *
 * The guest app runs on the server's virtual display, not inside this view — so the surface coming and
 * going (a tab switch, the app going to the background) must never restart it. Losing the surface only
 * pauses the encoder and drops the decoder; getting one back reconfigures from the cached SPS/PPS and
 * asks for a fresh key frame.
 */
internal class AppSandboxSession(
    val packageName: String?,
    val displayId: Int,
    private val video: Socket,
    private val control: Socket,
    private val server: AdbShellStream,
    initialCodecSpecificData: ByteArray,
    initialSize: Size,
) {
    private val videoIn = DataInputStream(BufferedInputStream(video.getInputStream(), VIDEO_BUFFER_BYTES))
    private val controlIn = DataInputStream(BufferedInputStream(control.getInputStream()))
    private val controlOut = DataOutputStream(BufferedOutputStream(control.getOutputStream()))

    private val decoderLock = Any()
    private var decoder: Decoder? = null
    private var surface: Surface? = null

    @Volatile
    private var codecSpecificData: ByteArray = initialCodecSpecificData

    @Volatile
    private var closed = false

    private val _videoSize = MutableStateFlow(initialSize)

    /** Live stream dimensions. The handshake only seeds this: after a resize or rotate the real size
     *  arrives with the decoder's output-format change, driven by the new SPS. */
    val videoSize: StateFlow<Size> = _videoSize.asStateFlow()

    private val _stopped = MutableStateFlow<AppSandboxStopped?>(null)
    val stopped: StateFlow<AppSandboxStopped?> = _stopped.asStateFlow()

    private val _appGone = MutableStateFlow(false)

    /** The watched package no longer has a task on our display — it was closed, crashed or moved. */
    val appGone: StateFlow<Boolean> = _appGone.asStateFlow()

    private val _log = MutableStateFlow<List<String>>(emptyList())

    /** The server's own stderr, kept for the page's diagnostics strip. */
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var rotation = 0

    init {
        appendLog("display $displayId, ${initialSize.width}x${initialSize.height}")
        thread("jcode-sandbox-video", ::readVideo)
        thread("jcode-sandbox-control", ::readControl)
        thread("jcode-sandbox-server-log", ::readServerLog)
    }

    // --- Surface lifecycle ------------------------------------------------------------------------

    fun attachSurface(target: Surface) {
        synchronized(decoderLock) {
            if (closed) return
            surface = target
            decoder?.release()
            decoder = runCatching { Decoder(target, codecSpecificData, _videoSize.value) }
                .onFailure { error -> close("Could not start the video decoder: ${error.message}") }
                .getOrNull()
        }
        resume()
        requestKeyFrame()
    }

    fun detachSurface() {
        // The encoder keeps running for nothing otherwise: the guest app is unaffected either way.
        pause()
        synchronized(decoderLock) {
            decoder?.release()
            decoder = null
            surface = null
        }
    }

    // --- Control channel --------------------------------------------------------------------------

    fun sendTouch(action: Int, pointerId: Int, x: Int, y: Int, pressure: Float, buttons: Int) = send {
        write(Protocol.CONTROL_TOUCH)
        write(action)
        writeInt(pointerId)
        writeInt(x)
        writeInt(y)
        writeShort((pressure.coerceIn(0f, 1f) * Protocol.PRESSURE_SCALE).toInt())
        writeInt(buttons)
    }

    fun sendKey(action: Int, keyCode: Int, repeat: Int, metaState: Int) = send {
        write(Protocol.CONTROL_KEY)
        write(action)
        writeInt(keyCode)
        writeInt(repeat)
        writeInt(metaState)
    }

    fun sendKeyPress(keyCode: Int) {
        sendKey(Protocol.KEY_DOWN, keyCode, 0, 0)
        sendKey(Protocol.KEY_UP, keyCode, 0, 0)
    }

    fun sendText(text: String) = send {
        val bytes = text.toByteArray(Charsets.UTF_8)
        write(Protocol.CONTROL_TEXT)
        writeInt(bytes.size)
        write(bytes)
    }

    /** Swaps the display between portrait and landscape; the server expresses rotation as dimensions. */
    fun rotate() {
        rotation = (rotation + 1) and 3
        send {
            write(Protocol.CONTROL_ROTATE)
            write(rotation)
        }
        requestKeyFrame()
    }

    fun restartApp() {
        _appGone.value = false
        send { write(Protocol.CONTROL_RESTART) }
    }

    fun requestKeyFrame() = send { write(Protocol.CONTROL_KEYFRAME) }

    private fun pause() = send { write(Protocol.CONTROL_PAUSE) }

    private fun resume() = send { write(Protocol.CONTROL_RESUME) }

    /** Ends the session: asks the server to shut down, then tears everything on this side down. */
    fun close(reason: String = "Stopped.") {
        if (closed) return
        send { write(Protocol.CONTROL_STOP) }
        closed = true
        synchronized(decoderLock) {
            decoder?.release()
            decoder = null
            surface = null
        }
        runCatching { video.close() }
        runCatching { control.close() }
        server.close()
        _stopped.value = AppSandboxStopped(reason)
    }

    private inline fun send(block: DataOutputStream.() -> Unit) {
        if (closed) return
        synchronized(controlOut) {
            runCatching {
                controlOut.block()
                controlOut.flush()
            }
        }
    }

    // --- Reader threads ---------------------------------------------------------------------------

    /**
     * Video framing is `[u64 pts][u32 len][payload]`, with the config and key-frame flags packed into
     * the two high bits of the pts word.
     */
    private fun readVideo() {
        try {
            while (!closed) {
                val header = videoIn.readLong()
                val length = videoIn.readInt()
                if (length < 0 || length > MAX_PACKET_BYTES) {
                    throw IOException("video packet claims $length bytes")
                }
                val payload = ByteArray(length)
                videoIn.readFully(payload)
                val config = header and Protocol.PACKET_FLAG_CONFIG != 0L
                val keyFrame = header and Protocol.PACKET_FLAG_KEY_FRAME != 0L
                if (config) onCodecSpecificData(payload)
                synchronized(decoderLock) {
                    decoder?.submit(payload, header and Protocol.PACKET_PTS_MASK, config, keyFrame)
                }
            }
        } catch (e: IOException) {
            if (!closed) close("The display server stopped streaming (${e.message}).")
        }
    }

    /** A new SPS/PPS means the display was resized; the decoder has to be rebuilt around it. */
    private fun onCodecSpecificData(csd: ByteArray) {
        if (csd.contentEquals(codecSpecificData)) return
        codecSpecificData = csd
        synchronized(decoderLock) {
            val target = surface ?: return
            decoder?.release()
            decoder = runCatching { Decoder(target, csd, _videoSize.value) }.getOrNull()
        }
    }

    private fun readControl() {
        try {
            while (!closed) {
                when (val id = controlIn.read()) {
                    -1 -> return
                    Protocol.EVENT_PONG -> controlIn.readLong()
                    Protocol.EVENT_APP_GONE -> {
                        readEventString()
                        _appGone.value = true
                    }
                    Protocol.EVENT_ERROR -> appendLog(readEventString())
                    // The stream position is unrecoverable once an unknown id appears.
                    else -> throw IOException("unknown event $id")
                }
            }
        } catch (e: IOException) {
            if (!closed) close("The display server closed the control channel (${e.message}).")
        }
    }

    private fun readEventString(): String {
        val length = controlIn.readInt()
        if (length < 0 || length > MAX_EVENT_BYTES) throw IOException("event claims $length bytes")
        val bytes = ByteArray(length)
        controlIn.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun readServerLog() {
        runCatching {
            server.clearReadTimeout()
            while (!closed) appendLog(server.readLine() ?: break)
        }
        if (!closed) close("The display server exited.")
    }

    private fun appendLog(line: String) {
        if (line.isBlank()) return
        _log.update { (it + line).takeLast(MAX_LOG_LINES) }
    }

    private fun thread(name: String, body: () -> Unit) {
        Thread(body, name).apply { isDaemon = true }.start()
    }

    /** Decodes into [target]; one instance per surface and per SPS, torn down with either. */
    private inner class Decoder(target: Surface, csd: ByteArray, size: Size) {
        private val codec = MediaCodec.createDecoderByType(MIME)

        @Volatile
        private var running = true

        /** A decoder fed a P-frame before its first IDR produces nothing but errors. */
        private var awaitingKeyFrame = true

        init {
            val format = MediaFormat.createVideoFormat(MIME, size.width, size.height)
            format.setByteBuffer(CSD_0, ByteBuffer.wrap(csd))
            // Adaptive playback: the server can resize the display mid-stream, and without a ceiling
            // the codec would have to be torn down for every rotation.
            val longest = maxOf(size.width, size.height)
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, longest)
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, longest)
            format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            codec.configure(format, target, null, 0)
            codec.start()
            thread("jcode-sandbox-render", ::render)
        }

        fun submit(payload: ByteArray, presentationTimeUs: Long, config: Boolean, keyFrame: Boolean) {
            if (!running) return
            if (awaitingKeyFrame && !config && !keyFrame) return
            awaitingKeyFrame = false
            runCatching {
                val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (index < 0) return
                val buffer = codec.getInputBuffer(index) ?: return
                buffer.clear()
                buffer.put(payload)
                codec.queueInputBuffer(
                    index,
                    0,
                    payload.size,
                    presentationTimeUs,
                    if (config) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0,
                )
            }
        }

        fun release() {
            running = false
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }

        private fun render() {
            val info = MediaCodec.BufferInfo()
            while (running) {
                val index = runCatching { codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US) }
                    .getOrElse { return }
                when {
                    index >= 0 -> runCatching { codec.releaseOutputBuffer(index, true) }
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED ->
                        _videoSize.value = runCatching { codec.outputFormat.displaySize() }
                            .getOrDefault(_videoSize.value)
                }
            }
        }
    }

    private companion object {
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
        const val CSD_0 = "csd-0"
        const val VIDEO_BUFFER_BYTES = 128 * 1024
        const val MAX_PACKET_BYTES = 8 * 1024 * 1024
        const val MAX_EVENT_BYTES = 64 * 1024
        const val MAX_LOG_LINES = 60
        const val DEQUEUE_TIMEOUT_US = 100_000L
    }
}

/** Visible size, i.e. the crop rectangle when the codec reports one — encoders align dimensions up. */
private fun MediaFormat.displaySize(): Size {
    val width = if (containsKey(CROP_LEFT) && containsKey(CROP_RIGHT)) {
        getInteger(CROP_RIGHT) - getInteger(CROP_LEFT) + 1
    } else {
        getInteger(MediaFormat.KEY_WIDTH)
    }
    val height = if (containsKey(CROP_TOP) && containsKey(CROP_BOTTOM)) {
        getInteger(CROP_BOTTOM) - getInteger(CROP_TOP) + 1
    } else {
        getInteger(MediaFormat.KEY_HEIGHT)
    }
    return Size(width.coerceAtLeast(1), height.coerceAtLeast(1))
}

private const val CROP_LEFT = "crop-left"
private const val CROP_RIGHT = "crop-right"
private const val CROP_TOP = "crop-top"
private const val CROP_BOTTOM = "crop-bottom"

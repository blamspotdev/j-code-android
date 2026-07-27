package dev.jcode.display;

import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.util.Range;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Encodes whatever the virtual display renders into an H.264 elementary stream.
 *
 * <p>The encoder owns the input {@link Surface}: the caller hands it to the VirtualDisplay, and
 * every reconfiguration produces a new one, which is why {@link SurfaceListener} exists.
 */
public final class ScreenEncoder implements Runnable {

    public interface PacketSink {
        void onPacket(long presentationTimeUs, boolean config, boolean keyFrame, ByteBuffer payload);
    }

    /**
     * Called on the encoder thread every time the input surface changes. A {@code null} surface
     * means the previous one is about to be released and must be detached from the display first.
     */
    public interface SurfaceListener {
        void onInputSurface(Surface surface, int width, int height);
    }

    private static final String MIME = MediaFormat.MIMETYPE_VIDEO_AVC;
    private static final int FRAME_RATE = 60;
    private static final int I_FRAME_INTERVAL_SECONDS = 10;

    /**
     * With a static screen the encoder would otherwise emit nothing at all and the client could not
     * distinguish "idle" from "stalled". Repeating the last frame keeps the pipe warm.
     */
    private static final long REPEAT_PREVIOUS_FRAME_AFTER_US = 100_000L;

    private static final long DEQUEUE_TIMEOUT_US = 100_000L;

    private final SurfaceListener surfaceListener;
    private final Object codecLock = new Object();

    private MediaCodec codec;
    private Surface inputSurface;

    private int requestedWidth;
    private int requestedHeight;
    private int width;
    private int height;
    private int bitRate;

    private volatile PacketSink sink;
    private volatile byte[] codecSpecificData;
    private volatile boolean stopped;
    private volatile boolean resetRequested;
    private volatile boolean paused;

    private Thread thread;

    public ScreenEncoder(int width, int height, int bitRate, SurfaceListener surfaceListener) {
        this.requestedWidth = width;
        this.requestedHeight = height;
        this.bitRate = bitRate;
        this.surfaceListener = surfaceListener;
    }

    /**
     * Creates and configures the codec so the caller can read the aligned dimensions and the input
     * surface before the virtual display exists.
     */
    public void prepare() throws IOException {
        openCodec();
    }

    public Surface getInputSurface() {
        synchronized (codecLock) {
            return inputSurface;
        }
    }

    public int getWidth() {
        synchronized (codecLock) {
            return width;
        }
    }

    public int getHeight() {
        synchronized (codecLock) {
            return height;
        }
    }

    public byte[] getCodecSpecificData() {
        byte[] csd = codecSpecificData;
        return csd == null ? new byte[0] : csd;
    }

    public void start() {
        thread = new Thread(this, "jcode-encoder");
        thread.start();
    }

    /** Starts streaming to {@code sink}, replaying the cached SPS/PPS and forcing a key frame. */
    public void attach(PacketSink newSink) {
        this.sink = newSink;
        byte[] csd = codecSpecificData;
        if (newSink != null && csd != null) {
            newSink.onPacket(0L, true, false, ByteBuffer.wrap(csd));
        }
        requestKeyFrame();
    }

    public void requestKeyFrame() {
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
        setParameters(params);
    }

    public void setBitRate(int bitsPerSecond) {
        if (bitsPerSecond <= 0) {
            return;
        }
        synchronized (codecLock) {
            bitRate = bitsPerSecond;
        }
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, bitsPerSecond);
        setParameters(params);
    }

    public void setPaused(boolean value) {
        paused = value;
        Bundle params = new Bundle();
        params.putInt(MediaCodec.PARAMETER_KEY_SUSPEND, value ? 1 : 0);
        setParameters(params);
        if (!value) {
            requestKeyFrame();
        }
    }

    /** Re-opens the codec at a new size; the new input surface arrives via the listener. */
    public void reconfigure(int newWidth, int newHeight) {
        synchronized (codecLock) {
            requestedWidth = newWidth;
            requestedHeight = newHeight;
        }
        resetRequested = true;
    }

    public void stop() {
        stopped = true;
        resetRequested = true;
        if (thread != null) {
            try {
                thread.join(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        try {
            while (!stopped) {
                synchronized (codecLock) {
                    if (codec == null) {
                        openCodec();
                        surfaceListener.onInputSurface(inputSurface, width, height);
                    }
                    codec.start();
                }
                resetRequested = false;
                try {
                    pump();
                } finally {
                    closeCodec();
                }
            }
        } catch (Throwable t) {
            if (!stopped) {
                t.printStackTrace();
            }
        }
    }

    private void pump() {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (!resetRequested) {
            int index;
            try {
                index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US);
            } catch (IllegalStateException e) {
                return;
            }
            if (index < 0) {
                continue;
            }
            try {
                ByteBuffer buffer = codec.getOutputBuffer(index);
                if (buffer != null && info.size > 0) {
                    buffer.position(info.offset);
                    buffer.limit(info.offset + info.size);
                    boolean config = (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0;
                    boolean keyFrame = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                    if (config) {
                        byte[] csd = new byte[buffer.remaining()];
                        buffer.duplicate().get(csd);
                        codecSpecificData = csd;
                    }
                    PacketSink current = sink;
                    if (current != null && !paused) {
                        current.onPacket(info.presentationTimeUs, config, keyFrame, buffer);
                    }
                }
            } finally {
                codec.releaseOutputBuffer(index, false);
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                return;
            }
        }
    }

    private void openCodec() throws IOException {
        MediaCodec created = MediaCodec.createEncoderByType(MIME);
        MediaCodecInfo.VideoCapabilities caps =
                created.getCodecInfo().getCapabilitiesForType(MIME).getVideoCapabilities();
        int alignedWidth = align(requestedWidth, caps.getWidthAlignment(), caps.getSupportedWidths());
        int alignedHeight = align(requestedHeight, caps.getHeightAlignment(),
                supportedHeights(caps, alignedWidth));

        MediaFormat format = MediaFormat.createVideoFormat(MIME, alignedWidth, alignedHeight);
        format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        format.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);
        format.setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE);
        format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS);
        format.setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0);
        format.setInteger(MediaFormat.KEY_LATENCY, 1);
        format.setInteger(MediaFormat.KEY_PRIORITY, 0);
        format.setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, REPEAT_PREVIOUS_FRAME_AFTER_US);

        created.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
        codec = created;
        inputSurface = created.createInputSurface();
        width = alignedWidth;
        height = alignedHeight;
    }

    private void closeCodec() {
        Surface dying;
        synchronized (codecLock) {
            dying = inputSurface;
            inputSurface = null;
            if (codec != null) {
                try {
                    codec.stop();
                } catch (IllegalStateException ignored) {
                    // Already stopped or never started.
                }
                codec.release();
                codec = null;
            }
        }
        if (dying != null) {
            surfaceListener.onInputSurface(null, width, height);
            dying.release();
        }
    }

    private void setParameters(Bundle params) {
        synchronized (codecLock) {
            if (codec == null) {
                return;
            }
            try {
                codec.setParameters(params);
            } catch (IllegalStateException ignored) {
                // Codec is being reconfigured; the new one picks up the stored settings.
            }
        }
    }

    private static Range<Integer> supportedHeights(MediaCodecInfo.VideoCapabilities caps, int width) {
        try {
            return caps.getSupportedHeightsFor(width);
        } catch (IllegalArgumentException e) {
            return caps.getSupportedHeights();
        }
    }

    /** Rounds to the codec's alignment and clamps into its supported range without breaking it. */
    private static int align(int value, int alignment, Range<Integer> range) {
        int aligned = Math.max(alignment, (value / alignment) * alignment);
        if (aligned < range.getLower()) {
            aligned = ceilTo(range.getLower(), alignment);
        }
        if (aligned > range.getUpper()) {
            aligned = (range.getUpper() / alignment) * alignment;
        }
        return Math.max(alignment, aligned);
    }

    private static int ceilTo(int value, int alignment) {
        return ((value + alignment - 1) / alignment) * alignment;
    }
}

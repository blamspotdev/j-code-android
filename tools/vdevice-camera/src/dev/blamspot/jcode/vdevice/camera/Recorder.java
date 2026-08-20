package dev.blamspot.jcode.vdevice.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.Image;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.util.Log;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * Records the device's camera to an MP4.
 *
 * <p>The specification used to say the device "can draw a frame and cannot encode a film", and that
 * was true of nothing except the code not being written. A frame is a `Bitmap`; `MediaCodec` and
 * `MediaMuxer` are ordinary SDK API that any app may use; and the device already knows how to draw
 * as many frames as it is asked for. So `ACTION_VIDEO_CAPTURE` is answerable after all, and an app
 * that asks for a video gets one it can play.
 *
 * <h2>Why byte buffers and not an input surface</h2>
 *
 * <p>The fast way to encode is `MediaCodec.createInputSurface()` and draw onto it — but a codec's
 * input surface is a hardware buffer that `Surface.lockCanvas` refuses, so drawing to it means
 * OpenGL: a context, a shader, a texture upload per frame. For a test pattern at 15 fps that is a
 * great deal of machinery to avoid a colour conversion that costs a few milliseconds. `getInputImage`
 * hands back the planes directly and works on every device that has an H.264 encoder, which is all
 * of them.
 *
 * <p>`COLOR_FormatYUV420Flexible` is asked for rather than a specific layout for the same reason:
 * the `Image` says where its own planes are, including the pixel stride that tells NV12 and I420
 * apart, so the conversion reads the answer instead of guessing at it.
 */
final class Recorder {

    private static final String TAG = "VCAMERA";

    private static final String MIME = "video/avc";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;
    private static final int FPS = 15;
    private static final int BITRATE = 2_000_000;
    private static final int KEYFRAME_SECONDS = 1;
    private static final long TIMEOUT_US = 10_000L;

    /** What draws one frame — the same scene the viewfinder shows. */
    interface Renderer {
        void draw(Canvas canvas, int width, int height, long elapsedMs);
    }

    private Recorder() {
    }

    /**
     * Writes {@code seconds} of video to {@code out}. True when the file is playable.
     *
     * <p>A failure part-way leaves the half-written file deleted rather than in place: an app handed
     * a truncated MP4 fails somewhere much further from the cause than an app told the recording did
     * not happen.
     */
    static boolean record(File out, int seconds, Renderer renderer) {
        MediaCodec codec = null;
        MediaMuxer muxer = null;
        boolean muxing = false;
        int track = -1;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MIME, WIDTH, HEIGHT);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible);
            format.setInteger(MediaFormat.KEY_BIT_RATE, BITRATE);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, KEYFRAME_SECONDS);

            codec = MediaCodec.createEncoderByType(MIME);
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            codec.start();
            muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);

            Bitmap frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(frame);
            int[] pixels = new int[WIDTH * HEIGHT];
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();

            int total = seconds * FPS;
            for (int index = 0; index <= total; index++) {
                boolean last = index == total;
                int input = codec.dequeueInputBuffer(TIMEOUT_US);
                if (input >= 0) {
                    long timeUs = index * 1_000_000L / FPS;
                    if (last) {
                        codec.queueInputBuffer(input, 0, 0, timeUs,
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                    } else {
                        renderer.draw(canvas, WIDTH, HEIGHT, timeUs / 1000L);
                        frame.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT);
                        fill(codec.getInputImage(input), pixels);
                        codec.queueInputBuffer(input, 0, sizeOf(codec.getInputImage(input)), timeUs, 0);
                    }
                }
                // Drained every pass rather than at the end: the encoder holds a small number of
                // output buffers, and one that is never collected stalls the input side for good.
                int output;
                while ((output = codec.dequeueOutputBuffer(info, TIMEOUT_US)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        track = muxer.addTrack(codec.getOutputFormat());
                        muxer.start();
                        muxing = true;
                        continue;
                    }
                    if (output < 0) {
                        continue;
                    }
                    ByteBuffer encoded = codec.getOutputBuffer(output);
                    // The codec-config buffer is the parameter sets, which the muxer takes from the
                    // format rather than as a sample; writing it as one produces a file that plays
                    // nowhere.
                    if (encoded != null && muxing && info.size > 0
                        && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        encoded.position(info.offset);
                        encoded.limit(info.offset + info.size);
                        muxer.writeSampleData(track, encoded, info);
                    }
                    codec.releaseOutputBuffer(output, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        frame.recycle();
                        return finish(codec, muxer, muxing, out);
                    }
                }
            }
            frame.recycle();
            return finish(codec, muxer, muxing, out);
        } catch (Exception e) {
            Log.w(TAG, "cannot record " + out, e);
            release(codec, muxer, muxing);
            out.delete();
            return false;
        }
    }

    private static boolean finish(MediaCodec codec, MediaMuxer muxer, boolean muxing, File out) {
        release(codec, muxer, muxing);
        if (out.length() > 0) {
            return true;
        }
        out.delete();
        return false;
    }

    private static void release(MediaCodec codec, MediaMuxer muxer, boolean muxing) {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
                // Already stopped, or never started; either way it is about to be released.
            }
            codec.release();
        }
        if (muxer != null) {
            try {
                if (muxing) {
                    muxer.stop();
                }
            } catch (Exception ignored) {
                // A muxer with no samples refuses to stop; the file is deleted by the caller.
            }
            muxer.release();
        }
    }

    /** How many bytes of the image the planes actually occupy, which is what the codec is told. */
    private static int sizeOf(Image image) {
        return image == null ? 0 : image.getWidth() * image.getHeight() * 3 / 2;
    }

    /**
     * Converts one ARGB frame into the encoder's own YUV planes.
     *
     * <p>Read out of the `Image` rather than assumed: `pixelStride` is 1 for planar I420 and 2 for
     * semi-planar NV12, and a converter that picks one is a converter that produces green video on
     * half the devices in existence.
     */
    private static void fill(Image image, int[] pixels) {
        if (image == null) {
            return;
        }
        int width = image.getWidth();
        int height = image.getHeight();
        Image.Plane[] planes = image.getPlanes();

        ByteBuffer y = planes[0].getBuffer();
        int yStride = planes[0].getRowStride();
        for (int row = 0; row < height; row++) {
            int base = row * yStride;
            for (int column = 0; column < width; column++) {
                int pixel = pixels[row * width + column];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                y.put(base + column, (byte) clamp((66 * r + 129 * g + 25 * b + 128 >> 8) + 16));
            }
        }

        ByteBuffer u = planes[1].getBuffer();
        ByteBuffer v = planes[2].getBuffer();
        int uStride = planes[1].getRowStride();
        int vStride = planes[2].getRowStride();
        int uPixel = planes[1].getPixelStride();
        int vPixel = planes[2].getPixelStride();
        for (int row = 0; row < height / 2; row++) {
            for (int column = 0; column < width / 2; column++) {
                int pixel = pixels[row * 2 * width + column * 2];
                int r = (pixel >> 16) & 0xFF;
                int g = (pixel >> 8) & 0xFF;
                int b = pixel & 0xFF;
                u.put(row * uStride + column * uPixel,
                    (byte) clamp((-38 * r - 74 * g + 112 * b + 128 >> 8) + 128));
                v.put(row * vStride + column * vPixel,
                    (byte) clamp((112 * r - 94 * g - 18 * b + 128 >> 8) + 128));
            }
        }
    }

    private static int clamp(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}

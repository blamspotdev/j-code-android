package dev.jcode.display;

/**
 * Wire constants shared by the on-device server and the JCode client.
 *
 * <p>Deliberately dependency-free (no Android imports, no logic) so the Kotlin side can compile
 * this exact file from a shared source directory instead of duplicating the numbers.
 *
 * <p>Every multi-byte field on the wire is big-endian, i.e. straight
 * {@code DataInputStream}/{@code DataOutputStream} order on both ends.
 */
public final class Protocol {

    private Protocol() {
    }

    /** "JCDS". */
    public static final int MAGIC = 0x4A434453;

    public static final int VERSION = 1;

    /** Session token length in bytes; the client must send exactly this many, then a channel byte. */
    public static final int TOKEN_LENGTH = 32;

    /**
     * The server accepts two connections on the same listening socket. Each one opens with
     * {@code [32-byte token][u8 channel]}; the channel byte tells the server which role it plays,
     * so connection order does not matter.
     */
    public static final int CHANNEL_VIDEO = 0;
    public static final int CHANNEL_CONTROL = 1;

    /** "h264". */
    public static final int CODEC_H264 = 0x68323634;

    /**
     * Video framing is {@code [u64 pts][u32 len]} followed by {@code len} payload bytes.
     *
     * <p>The two high bits of the pts word carry flags rather than timestamp; the remaining 62 bits
     * still cover ~146,000 years of microseconds. Same layout scrcpy uses, on purpose: a future
     * swap to a scrcpy server keeps the client reader intact.
     */
    public static final long PACKET_FLAG_CONFIG = 1L << 63;
    public static final long PACKET_FLAG_KEY_FRAME = 1L << 62;
    public static final long PACKET_PTS_MASK = ~(PACKET_FLAG_CONFIG | PACKET_FLAG_KEY_FRAME);

    // Control messages, client -> server, each is [u8 id][payload].
    /** {@code [u8 action][i32 pointerId][i32 x][i32 y][u16 pressure][i32 buttons]}. */
    public static final int CONTROL_TOUCH = 0;
    /** {@code [u8 action][i32 keyCode][i32 repeat][i32 metaState]}. */
    public static final int CONTROL_KEY = 1;
    /** {@code [u32 byteLength][utf-8 bytes]}. */
    public static final int CONTROL_TEXT = 2;
    /** {@code [u8 surfaceRotation]} (0..3). */
    public static final int CONTROL_ROTATE = 3;
    /** {@code [i32 width][i32 height][i32 dpi]}, dpi <= 0 keeps the current density. */
    public static final int CONTROL_RESIZE = 4;
    /** {@code [i32 bitsPerSecond]}. */
    public static final int CONTROL_SET_BITRATE = 5;
    /** No payload. */
    public static final int CONTROL_PAUSE = 6;
    /** No payload. */
    public static final int CONTROL_RESUME = 7;
    /** No payload. */
    public static final int CONTROL_KEYFRAME = 8;
    /** {@code [u32 byteLength][utf-8 package name]}. */
    public static final int CONTROL_LAUNCH = 9;
    /** No payload. */
    public static final int CONTROL_RESTART = 10;
    /** No payload. */
    public static final int CONTROL_STOP = 11;
    /** {@code [i64 nonce]}, answered with {@link #EVENT_PONG}. */
    public static final int CONTROL_PING = 12;

    /** {@link #CONTROL_TOUCH} actions. The server derives ACTION_POINTER_DOWN/UP itself. */
    public static final int TOUCH_DOWN = 0;
    public static final int TOUCH_UP = 1;
    public static final int TOUCH_MOVE = 2;
    public static final int TOUCH_CANCEL = 3;

    /** {@link #CONTROL_KEY} actions, matching {@code KeyEvent.ACTION_DOWN}/{@code ACTION_UP}. */
    public static final int KEY_DOWN = 0;
    public static final int KEY_UP = 1;

    /** Pressure is sent as a 16-bit fraction of this value. */
    public static final int PRESSURE_SCALE = 0xFFFF;

    public static final int MAX_POINTERS = 10;

    // Events, server -> client, written back on the control channel as [u8 id][payload].
    /** {@code [u32 byteLength][utf-8 package name]}. */
    public static final int EVENT_APP_GONE = 0;
    /** {@code [u32 byteLength][utf-8 message]}. */
    public static final int EVENT_ERROR = 1;
    /** {@code [i64 nonce]}, echoing {@link #CONTROL_PING}. */
    public static final int EVENT_PONG = 2;
}

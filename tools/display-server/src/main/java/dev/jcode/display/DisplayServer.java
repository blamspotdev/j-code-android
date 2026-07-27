package dev.jcode.display;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.system.Os;
import android.view.Surface;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shell-uid server that owns a trusted virtual display, streams it as H.264 over loopback and
 * injects input back into it. Started by JCode through {@code adb shell app_process}.
 */
public final class DisplayServer {

    private static final String DISPLAY_NAME = "jcode-display";
    private static final long CSD_TIMEOUT_MS = 3000L;

    private final Options options;
    private final AtomicBoolean shutdown = new AtomicBoolean();
    private final byte[] writeScratch = new byte[64 * 1024];

    private ScreenEncoder encoder;
    private VirtualDisplaySession display;
    private Injector injector;
    private AppController app;
    private ServerSocket serverSocket;
    private Socket videoSocket;
    private Socket controlSocket;
    private DataOutputStream videoOut;
    private DataOutputStream eventOut;

    private DisplayServer(Options options) {
        this.options = options;
    }

    public static void main(String[] args) {
        DisplayServer server = null;
        try {
            server = new DisplayServer(Options.parse(args));
            server.run();
        } catch (Throwable t) {
            t.printStackTrace();
            if (server != null) {
                server.shutdown();
            }
            System.exit(1);
        }
        // Looper.prepareMainLooper() and the codec's internal threads keep the VM alive otherwise.
        System.exit(0);
    }

    private void run() throws Exception {
        Context context = Workarounds.shellContext();
        DisplayManager displayManager = Workarounds.displayManager(context);

        encoder = new ScreenEncoder(options.width, options.height, options.bitRate,
                this::onInputSurface);
        encoder.prepare();
        display = VirtualDisplaySession.create(displayManager, DISPLAY_NAME,
                encoder.getWidth(), encoder.getHeight(), options.dpi,
                encoder.getInputSurface(), options.decorations);
        log("display id=" + display.getDisplayId()
                + " size=" + encoder.getWidth() + "x" + encoder.getHeight()
                + " dpi=" + options.dpi
                + " flags=0x" + Integer.toHexString(display.getFlags()));

        injector = new Injector(display.getDisplayId());
        app = new AppController(display.getDisplayId(), this::onAppGone);
        encoder.start();

        if (options.packageName != null) {
            app.launch(options.packageName);
            log("launched " + options.packageName);
        }

        serverSocket = new ServerSocket(options.port, 2, InetAddress.getByName("127.0.0.1"));
        System.out.println("PORT=" + serverSocket.getLocalPort());
        System.out.flush();

        installTerminationHandlers();

        videoSocket = accept(Protocol.CHANNEL_VIDEO);
        videoOut = new DataOutputStream(new BufferedOutputStream(videoSocket.getOutputStream()));
        awaitCodecSpecificData();
        writeHandshake();
        encoder.attach(this::writePacket);
        requestRefresh();

        controlSocket = accept(Protocol.CHANNEL_CONTROL);
        eventOut = new DataOutputStream(new BufferedOutputStream(controlSocket.getOutputStream()));
        try {
            readControl(new DataInputStream(
                    new BufferedInputStream(controlSocket.getInputStream())));
        } finally {
            shutdown();
        }
    }

    private void onInputSurface(Surface surface, int width, int height) {
        VirtualDisplaySession current = display;
        if (current == null) {
            return;
        }
        if (surface == null) {
            current.setSurface(null);
            return;
        }
        current.resize(width, height, options.dpi);
        current.setSurface(surface);
    }

    private void onAppGone(String packageName) {
        log("app gone: " + packageName);
        sendEvent(Protocol.EVENT_APP_GONE, packageName);
    }

    /**
     * Accepts until a connection presents the right token for the requested channel. A wrong token
     * only costs that connection: the listening socket is loopback-only but still reachable by any
     * local app, and the token is what keeps them out of the injector.
     */
    private Socket accept(int channel) throws IOException {
        while (true) {
            Socket socket = serverSocket.accept();
            try {
                socket.setTcpNoDelay(true);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                byte[] token = new byte[Protocol.TOKEN_LENGTH];
                in.readFully(token);
                int requested = in.readUnsignedByte();
                if (constantTimeEquals(token, options.token) && requested == channel) {
                    return socket;
                }
                log("rejected connection (channel=" + requested + ")");
            } catch (IOException ignored) {
                // Fall through and close.
            }
            closeQuietly(socket);
        }
    }

    private void awaitCodecSpecificData() {
        long deadline = System.currentTimeMillis() + CSD_TIMEOUT_MS;
        while (encoder.getCodecSpecificData().length == 0
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private void writeHandshake() throws IOException {
        byte[] csd = encoder.getCodecSpecificData();
        videoOut.writeInt(Protocol.MAGIC);
        videoOut.writeInt(Protocol.VERSION);
        videoOut.writeInt(display.getDisplayId());
        videoOut.writeInt(encoder.getWidth());
        videoOut.writeInt(encoder.getHeight());
        videoOut.writeInt(Protocol.CODEC_H264);
        videoOut.writeInt(csd.length);
        videoOut.write(csd);
        videoOut.flush();
        log("handshake sent, csd=" + csd.length + " bytes");
    }

    private void writePacket(long presentationTimeUs, boolean config, boolean keyFrame,
                             ByteBuffer payload) {
        try {
            long header = (presentationTimeUs & Protocol.PACKET_PTS_MASK)
                    | (config ? Protocol.PACKET_FLAG_CONFIG : 0L)
                    | (keyFrame ? Protocol.PACKET_FLAG_KEY_FRAME : 0L);
            synchronized (writeScratch) {
                videoOut.writeLong(header);
                videoOut.writeInt(payload.remaining());
                while (payload.hasRemaining()) {
                    int chunk = Math.min(writeScratch.length, payload.remaining());
                    payload.get(writeScratch, 0, chunk);
                    videoOut.write(writeScratch, 0, chunk);
                }
                videoOut.flush();
            }
        } catch (IOException e) {
            log("video stream closed: " + e);
            shutdown();
        }
    }

    private void readControl(DataInputStream in) throws IOException {
        while (true) {
            int type = in.read();
            if (type < 0) {
                return;
            }
            switch (type) {
                case Protocol.CONTROL_TOUCH: {
                    int action = in.readUnsignedByte();
                    int pointerId = in.readInt();
                    int x = in.readInt();
                    int y = in.readInt();
                    float pressure = in.readUnsignedShort() / (float) Protocol.PRESSURE_SCALE;
                    int buttons = in.readInt();
                    injector.touch(action, pointerId, x, y, pressure, buttons);
                    break;
                }
                case Protocol.CONTROL_KEY: {
                    int action = in.readUnsignedByte();
                    int keyCode = in.readInt();
                    int repeat = in.readInt();
                    int metaState = in.readInt();
                    injector.key(action, keyCode, repeat, metaState);
                    break;
                }
                case Protocol.CONTROL_TEXT:
                    injector.text(readString(in));
                    break;
                case Protocol.CONTROL_ROTATE:
                    rotate(in.readUnsignedByte() & 3);
                    break;
                case Protocol.CONTROL_RESIZE: {
                    int width = in.readInt();
                    int height = in.readInt();
                    int dpi = in.readInt();
                    resize(width, height, dpi);
                    break;
                }
                case Protocol.CONTROL_SET_BITRATE:
                    encoder.setBitRate(in.readInt());
                    break;
                case Protocol.CONTROL_PAUSE:
                    encoder.setPaused(true);
                    break;
                case Protocol.CONTROL_RESUME:
                    encoder.setPaused(false);
                    break;
                case Protocol.CONTROL_KEYFRAME:
                    requestRefresh();
                    break;
                case Protocol.CONTROL_LAUNCH:
                    launch(readString(in));
                    break;
                case Protocol.CONTROL_RESTART:
                    restart();
                    break;
                case Protocol.CONTROL_STOP:
                    return;
                case Protocol.CONTROL_PING:
                    sendPong(in.readLong());
                    break;
                default:
                    // The stream position is unrecoverable once an unknown id appears.
                    throw new IOException("unknown control message " + type);
            }
        }
    }

    private void launch(String packageName) {
        try {
            injector.cancelAll();
            app.launch(packageName);
            requestRefresh();
        } catch (IOException e) {
            sendEvent(Protocol.EVENT_ERROR, "launch failed: " + e.getMessage());
        }
    }

    private void restart() {
        try {
            injector.cancelAll();
            app.restart();
            requestRefresh();
        } catch (IOException e) {
            sendEvent(Protocol.EVENT_ERROR, "restart failed: " + e.getMessage());
        }
    }

    private void requestRefresh() {
        encoder.requestKeyFrame();
        display.refresh(encoder.getInputSurface());
    }

    private void resize(int width, int height, int dpi) {
        if (width <= 0 || height <= 0) {
            return;
        }
        injector.cancelAll();
        if (dpi > 0) {
            options.dpi = dpi;
        }
        encoder.reconfigure(width, height);
    }

    /**
     * Orientation is expressed by swapping the display dimensions, which is what the guest app
     * actually reacts to.
     *
     * <p>{@code IWindowManager.freezeDisplayRotation(displayId, rotation)} is reachable from shell
     * and reports success, but on this device it leaves the virtual display permanently unable to
     * produce another frame - not even after detaching and reattaching the output surface. Do not
     * reintroduce it without re-testing that.
     */
    private void rotate(int rotation) {
        boolean landscape = rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270;
        int longSide = Math.max(encoder.getWidth(), encoder.getHeight());
        int shortSide = Math.min(encoder.getWidth(), encoder.getHeight());
        resize(landscape ? longSide : shortSide, landscape ? shortSide : longSide, 0);
    }

    private void sendPong(long nonce) {
        DataOutputStream out = eventOut;
        if (out == null) {
            return;
        }
        try {
            synchronized (out) {
                out.write(Protocol.EVENT_PONG);
                out.writeLong(nonce);
                out.flush();
            }
        } catch (IOException e) {
            log("event stream closed: " + e);
        }
    }

    private void sendEvent(int id, String payload) {
        DataOutputStream out = eventOut;
        if (out == null) {
            return;
        }
        byte[] bytes = payload == null
                ? new byte[0] : payload.getBytes(StandardCharsets.UTF_8);
        try {
            synchronized (out) {
                out.write(id);
                out.writeInt(bytes.length);
                out.write(bytes);
                out.flush();
            }
        } catch (IOException e) {
            log("event stream closed: " + e);
        }
    }

    private static String readString(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > 1 << 20) {
            throw new IOException("bad string length " + length);
        }
        byte[] bytes = new byte[length];
        in.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * The process runs attached to {@code adb shell}, so adbd disposes of it when JCode dies.
     *
     * <p>The signal itself cannot be caught: ART has no {@code sun.misc.Signal} and does not run
     * shutdown hooks for terminating signals. Watching the parent pid covers the same ground and
     * also handles the case where no controlling terminal exists, in which case no SIGHUP is sent
     * at all and the orphan would otherwise keep the display alive forever.
     */
    private void installTerminationHandlers() {
        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "jcode-shutdown"));
        int parentPid = Os.getppid();
        if (parentPid <= 1) {
            return;
        }
        Thread watchdog = new Thread(() -> {
            while (Os.getppid() == parentPid) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
            log("parent process " + parentPid + " gone");
            shutdown();
            System.exit(0);
        }, "jcode-parent-watch");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    private void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        if (encoder != null) {
            encoder.attach(null);
            encoder.stop();
        }
        if (display != null) {
            display.release();
        }
        if (app != null) {
            app.close();
            if (options.stopOnExit) {
                try {
                    app.forceStop();
                } catch (IOException e) {
                    log("force-stop failed: " + e);
                }
            }
        }
        closeQuietly(videoSocket);
        closeQuietly(controlSocket);
        closeQuietly(serverSocket);
        log("stopped");
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // Nothing useful to do while tearing down.
            }
        }
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length; i++) {
            diff |= a[i] ^ b[i];
        }
        return diff == 0;
    }

    private static void log(String message) {
        System.err.println("jcode-display: " + message);
        System.err.flush();
    }

    private static final class Options {
        int port;
        byte[] token;
        int width = 1080;
        int height = 1920;
        int dpi = 320;
        int bitRate = 8_000_000;
        String packageName;
        boolean decorations = true;
        boolean stopOnExit = true;

        static Options parse(String[] args) {
            Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if (!arg.startsWith("--")) {
                    throw new IllegalArgumentException("unexpected argument: " + arg);
                }
                String key;
                String value;
                int eq = arg.indexOf('=');
                if (eq >= 0) {
                    key = arg.substring(2, eq);
                    value = arg.substring(eq + 1);
                } else {
                    key = arg.substring(2);
                    value = i + 1 < args.length ? args[++i] : "";
                }
                switch (key) {
                    case "port":
                        options.port = Integer.parseInt(value);
                        break;
                    case "token":
                        options.token = value.getBytes(StandardCharsets.UTF_8);
                        break;
                    case "size": {
                        int x = value.indexOf('x');
                        options.width = Integer.parseInt(value.substring(0, x));
                        options.height = Integer.parseInt(value.substring(x + 1));
                        break;
                    }
                    case "dpi":
                        options.dpi = Integer.parseInt(value);
                        break;
                    case "bitrate":
                        options.bitRate = Integer.parseInt(value);
                        break;
                    case "package":
                        options.packageName = value.isEmpty() ? null : value;
                        break;
                    case "decorations":
                        options.decorations = parseBoolean(value);
                        break;
                    case "stop-on-exit":
                        options.stopOnExit = parseBoolean(value);
                        break;
                    default:
                        throw new IllegalArgumentException("unknown option: --" + key);
                }
            }
            if (options.token == null || options.token.length != Protocol.TOKEN_LENGTH) {
                throw new IllegalArgumentException(
                        "--token must be exactly " + Protocol.TOKEN_LENGTH + " bytes");
            }
            return options;
        }

        private static boolean parseBoolean(String value) {
            return value.isEmpty() || "true".equals(value) || "1".equals(value)
                    || "yes".equals(value);
        }
    }
}

package dev.jcode.display;

import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.view.Surface;

public final class VirtualDisplaySession {

    private static final int FLAG_PUBLIC = 1;
    private static final int FLAG_PRESENTATION = 1 << 1;
    private static final int FLAG_OWN_CONTENT_ONLY = 1 << 3;
    private static final int FLAG_SUPPORTS_TOUCH = 1 << 6;
    private static final int FLAG_ROTATES_WITH_CONTENT = 1 << 7;
    private static final int FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS = 1 << 9;
    private static final int FLAG_TRUSTED = 1 << 10;

    /**
     * VIRTUAL_DISPLAY_FLAG_SECURE (1 << 2) is deliberately absent: it needs a signature-level
     * permission that shell does not hold, and requesting it fails the whole creation call.
     */
    private static final int BASE_FLAGS = FLAG_PUBLIC | FLAG_PRESENTATION | FLAG_OWN_CONTENT_ONLY
            | FLAG_SUPPORTS_TOUCH | FLAG_ROTATES_WITH_CONTENT | FLAG_TRUSTED;

    private final VirtualDisplay virtualDisplay;
    private final int displayId;
    private final int flags;

    private int width;
    private int height;
    private int densityDpi;
    private boolean released;

    private VirtualDisplaySession(VirtualDisplay virtualDisplay, int flags,
                                  int width, int height, int densityDpi) {
        this.virtualDisplay = virtualDisplay;
        this.displayId = virtualDisplay.getDisplay().getDisplayId();
        this.flags = flags;
        this.width = width;
        this.height = height;
        this.densityDpi = densityDpi;
    }

    public static VirtualDisplaySession create(DisplayManager displayManager, String name,
                                               int width, int height, int densityDpi,
                                               Surface surface, boolean systemDecorations) {
        int flags = BASE_FLAGS | (systemDecorations ? FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS : 0);
        VirtualDisplay display =
                displayManager.createVirtualDisplay(name, width, height, densityDpi, surface, flags);
        if (display == null) {
            throw new IllegalStateException("createVirtualDisplay returned null");
        }
        return new VirtualDisplaySession(display, flags, width, height, densityDpi);
    }

    public int getDisplayId() {
        return displayId;
    }

    public int getFlags() {
        return flags;
    }

    public synchronized void resize(int newWidth, int newHeight, int newDensityDpi) {
        if (released) {
            return;
        }
        int dpi = newDensityDpi > 0 ? newDensityDpi : densityDpi;
        virtualDisplay.resize(newWidth, newHeight, dpi);
        width = newWidth;
        height = newHeight;
        densityDpi = dpi;
    }

    /** {@code null} detaches the current surface, which is required before releasing it. */
    public synchronized void setSurface(Surface surface) {
        if (!released) {
            virtualDisplay.setSurface(surface);
        }
    }

    /**
     * Forces SurfaceFlinger to composite the display again.
     *
     * <p>Needed because the encoder cannot emit anything - not even a requested sync frame - until
     * a buffer is queued into its input surface, and a display showing a static screen queues
     * nothing. KEY_REPEAT_PREVIOUS_FRAME_AFTER does not cover this: the framework repeats the last
     * frame only a bounded number of times, so an idle display goes silent within about a second.
     * Re-attaching the same surface is the cheapest way to demand a fresh frame.
     */
    public synchronized void refresh(Surface surface) {
        if (!released && surface != null) {
            virtualDisplay.setSurface(null);
            virtualDisplay.setSurface(surface);
        }
    }

    public synchronized void release() {
        if (!released) {
            released = true;
            virtualDisplay.setSurface(null);
            virtualDisplay.release();
        }
    }
}

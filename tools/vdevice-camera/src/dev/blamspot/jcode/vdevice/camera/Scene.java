package dev.blamspot.jcode.vdevice.camera;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

/**
 * What the virtual device's camera sees: a small set of frames, drawn once and then shown.
 *
 * <h2>Why this stopped being procedural</h2>
 *
 * <p>The first version drew the whole picture on every frame — colour bars, a horizon computed from
 * the attitude, a compass rose, and a line of text with the frame number and three angles in it. That
 * is a lot of work thirty times a second to show numbers nobody reads off a viewfinder, and it made
 * the camera the most expensive thing on an otherwise idle device.
 *
 * <p>So a scene is now **frames**: each one rendered once into a small bitmap and cached, and the
 * viewfinder blits whichever is current. A still scene has one frame, which means the viewfinder
 * draws it and stops — no timer, no invalidation, nothing running at all. An animated one has five
 * and redraws five times a second rather than thirty, and each of those redraws is a scaled blit
 * instead of a page of drawing commands.
 *
 * <p>The bitmaps are deliberately tiny — {@link #GRID_WIDTH}×{@link #GRID_HEIGHT} — and scaled up
 * with filtering off. That is what makes it pixel art rather than a blurry small picture, and it is
 * also why five frames cost almost nothing to hold.
 *
 * <h2>Still drawn to look drawn</h2>
 *
 * <p>The reason has not changed: nothing here could be mistaken for a photograph of a room, which is
 * what a camera quietly handing over <em>something</em> would invite. It is just cheaper about it.
 */
final class Scene {

    /** The pixel grid every frame is drawn on. Small on purpose — see the class docs. */
    private static final int GRID_WIDTH = 48;
    private static final int GRID_HEIGHT = 36;

    /** What each character in a frame means. Space is "leave the background alone". */
    private static final char SKY = '.';
    private static final char GROUND = ',';
    private static final char BODY = 'B';
    private static final char DARK = 'D';
    private static final char EYE = 'E';
    private static final char LIGHT = 'L';
    private static final char LENS = 'O';

    private static final int COLOUR_SKY = 0xFF1B2A4A;
    private static final int COLOUR_SKY_2 = 0xFF24406B;
    private static final int COLOUR_GROUND = 0xFF2E5B3A;
    private static final int COLOUR_BODY = 0xFFB8C4D9;
    private static final int COLOUR_DARK = 0xFF41506B;
    private static final int COLOUR_EYE = 0xFF101418;
    private static final int COLOUR_LENS = 0xFF8AB4F8;
    private static final int COLOUR_LIGHT_ON = 0xFFE6A23C;

    /**
     * The device's camera, pointed at a small robot that blinks and whose lamp sweeps.
     *
     * <p>Five frames, written as pixel rows so that what is drawn is legible in the source rather
     * than being a list of rectangle coordinates. The differences between frames are deliberately
     * small — an eye closing, the lamp moving one pixel — because the point of the animation is to
     * prove the picture is live, not to be a cartoon.
     */
    private static final String[][] PIXEL_ART = {
        robot(2, false),
        robot(1, false),
        robot(0, true),
        robot(-1, false),
        robot(-2, false),
    };

    /**
     * One frame of the robot: {@code lamp} shifts its lamp left or right, {@code blink} shuts its
     * eyes.
     */
    private static String[] robot(int lamp, boolean blink) {
        char eye = blink ? BODY : EYE;
        String[] rows = new String[12];
        rows[0] = pad("      " + lampAt(lamp) + "      ");
        rows[1] = pad("       DDD       ");
        rows[2] = pad("     DDDDDDD     ");
        rows[3] = pad("    DBBBBBBBD    ");
        rows[4] = pad("    DB" + eye + "BBB" + eye + "BD    ");
        rows[5] = pad("    DBBBBBBBD    ");
        rows[6] = pad("    DBBOOOBBD    ");
        rows[7] = pad("    DBBOOOBBD    ");
        rows[8] = pad("    DBBBBBBBD    ");
        rows[9] = pad("     DDDDDDD     ");
        rows[10] = pad("      DD DD      ");
        rows[11] = pad("      DD DD      ");
        return rows;
    }

    private static String lampAt(int offset) {
        StringBuilder row = new StringBuilder("     ");
        int index = 2 + offset;
        for (int i = 0; i < 5; i++) {
            row.setCharAt(i, i == index ? LIGHT : ' ');
        }
        return row.toString();
    }

    private static String pad(String row) {
        return row;
    }

    /** How long each frame of an animated scene is shown. Five of these make a one-second loop. */
    static final long FRAME_MS = 200L;

    private final Paint blit = new Paint();
    private final Paint fill = new Paint();
    private Bitmap[] frames;
    private String kind = "";

    Scene() {
        // Off, so scaling a 48-pixel-wide picture up to a whole screen stays pixel art rather than
        // becoming a blurred smear of it.
        blit.setFilterBitmap(false);
        blit.setAntiAlias(false);
    }

    /** How many frames this scene has. One means nothing needs to redraw it. */
    int frameCount() {
        return frames == null ? 1 : frames.length;
    }

    boolean animated() {
        return frameCount() > 1;
    }

    /**
     * Builds the frames for {@code kind}, if they are not already built.
     *
     * <table>
     *   <tr><td>{@code pixelart}</td><td>Five frames, a one-second loop — the default</td></tr>
     *   <tr><td>{@code slideshow}</td><td>Three stills, one a second</td></tr>
     *   <tr><td>{@code still}</td><td>One frame, and nothing ever redraws it</td></tr>
     * </table>
     */
    void prepare(String kind) {
        if (this.kind.equals(kind) && frames != null) {
            return;
        }
        this.kind = kind;
        recycle();
        if ("still".equals(kind)) {
            frames = new Bitmap[] {card(0)};
        } else if ("slideshow".equals(kind)) {
            frames = new Bitmap[] {card(0), card(1), card(2)};
        } else {
            frames = new Bitmap[PIXEL_ART.length];
            for (int i = 0; i < PIXEL_ART.length; i++) {
                frames[i] = art(PIXEL_ART[i]);
            }
        }
    }

    /** How long one frame of this scene lasts — a slideshow lingers, an animation does not. */
    long frameMs() {
        return "slideshow".equals(kind) ? 1_000L : FRAME_MS;
    }

    /** Draws the frame that belongs at {@code elapsedMs}, scaled to fill. */
    void draw(Canvas canvas, int width, int height, long elapsedMs) {
        if (frames == null || frames.length == 0) {
            return;
        }
        Bitmap frame = frames[(int) ((elapsedMs / frameMs()) % frames.length)];
        canvas.drawBitmap(frame, new Rect(0, 0, frame.getWidth(), frame.getHeight()),
            new Rect(0, 0, width, height), blit);
    }

    private void recycle() {
        if (frames == null) {
            return;
        }
        for (Bitmap frame : frames) {
            if (frame != null) {
                frame.recycle();
            }
        }
        frames = null;
    }

    /** One pixel-art frame: a sky, a ground, and the sprite centred on it. */
    private Bitmap art(String[] rows) {
        Bitmap bitmap = Bitmap.createBitmap(GRID_WIDTH, GRID_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        for (int y = 0; y < GRID_HEIGHT; y++) {
            fill.setColor(y < GRID_HEIGHT - 8
                ? (y < GRID_HEIGHT / 2 ? COLOUR_SKY : COLOUR_SKY_2)
                : COLOUR_GROUND);
            canvas.drawRect(0, y, GRID_WIDTH, y + 1, fill);
        }
        int left = (GRID_WIDTH - rows[0].length()) / 2;
        int top = (GRID_HEIGHT - rows.length) / 2 - 2;
        for (int y = 0; y < rows.length; y++) {
            String row = rows[y];
            for (int x = 0; x < row.length(); x++) {
                int colour = colourOf(row.charAt(x));
                if (colour == 0) {
                    continue;
                }
                fill.setColor(colour);
                canvas.drawRect(left + x, top + y, left + x + 1, top + y + 1, fill);
            }
        }
        return bitmap;
    }

    private int colourOf(char cell) {
        switch (cell) {
            case BODY: return COLOUR_BODY;
            case DARK: return COLOUR_DARK;
            case EYE: return COLOUR_EYE;
            case LENS: return COLOUR_LENS;
            case LIGHT: return COLOUR_LIGHT_ON;
            case SKY: return COLOUR_SKY;
            case GROUND: return COLOUR_GROUND;
            default: return 0;
        }
    }

    /**
     * One still: eight colour bars with a marker that moves between the three of them.
     *
     * <p>Bars because they are the thing an app can check it decoded — the wrong colour order or a
     * channel swap is visible at a glance — and a marker so that a slideshow is visibly three
     * pictures rather than one picture shown three times.
     */
    private Bitmap card(int index) {
        int[] bars = {
            0xFFC0C0C0, 0xFFC0C000, 0xFF00C0C0, 0xFF00C000,
            0xFFC000C0, 0xFFC00000, 0xFF0000C0, 0xFF101010,
        };
        Bitmap bitmap = Bitmap.createBitmap(GRID_WIDTH, GRID_HEIGHT, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        int barWidth = GRID_WIDTH / bars.length;
        for (int i = 0; i < bars.length; i++) {
            fill.setColor(bars[i]);
            canvas.drawRect(i * barWidth, 0, (i + 1) * barWidth, GRID_HEIGHT, fill);
        }
        fill.setColor(COLOUR_LENS);
        int markerWidth = GRID_WIDTH / 6;
        int left = index * markerWidth + markerWidth / 2;
        canvas.drawRect(left, GRID_HEIGHT - 8, left + markerWidth, GRID_HEIGHT - 4, fill);
        return bitmap;
    }
}

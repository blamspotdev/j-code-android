package dev.blamspot.jcode.vdevice.camera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The virtual device's camera app.
 *
 * <p>An app that wants a photo starts `ACTION_IMAGE_CAPTURE` and waits, and the careful ones call
 * `resolveActivity` first and hide their camera button when nothing answers. Until this existed the
 * device had no answer to that question — the container could draw a viewfinder, but a drawn screen
 * is not something `PackageManager` can find, and an app that asks before it reaches never got as
 * far as reaching.
 *
 * <p>It is an ordinary guest. No container privileges and no Camera2: the picture is a handful of
 * small frames the app draws once and then shows, and it is saved with ordinary file IO. That is
 * deliberate — the app that proves the device has a camera should not be the one app that needs
 * special help to run.
 *
 * <p>What it shows is chosen on JCode's hardware bench and read through the container's settings
 * provider — pixel art by default. See {@link Scene} for why frames beat drawing the picture again
 * thirty times a second.
 *
 * <h2>Two ways in</h2>
 *
 * <table>
 *   <tr><th>Started by</th><th>What it does</th></tr>
 *   <tr><td>The launcher</td><td>Viewfinder and a shutter; photos go to the device's DCIM/Camera</td></tr>
 *   <tr><td>`ACTION_IMAGE_CAPTURE`</td><td>The same, and answers the caller</td></tr>
 * </table>
 *
 * <p>The capture contract is honoured as written: with `EXTRA_OUTPUT` the full-size JPEG is written
 * to that URI and the result carries no data; without it the result carries a thumbnail under the
 * `"data"` extra. Either way the full-size file is kept in the device's own DCIM/Camera, because the
 * picture somebody just took should be somewhere they can find it — and here that is a path
 * `adb pull` takes.
 */
public class CameraActivity extends Activity {

    private static final String TAG = "VCAMERA";

    private static final String CAMERA = "android.permission.CAMERA";
    private static final int PERMISSION_CODE = 7101;

    /** The extra a capture with no `EXTRA_OUTPUT` answers under — `"data"`, by contract. */
    private static final String EXTRA_THUMBNAIL = "data";

    /** A thumbnail's longest side. The contract says "small"; a phone's is about this. */
    private static final int THUMBNAIL = 512;

    /** What a still comes out at. A 4:3 sensor, because that is what a phone's back camera is. */
    private static final int STILL_WIDTH = 1440;
    private static final int STILL_HEIGHT = 1080;

    private static final int JPEG_QUALITY = 90;

    /** How long a capture runs. Long enough to be a video, short enough to be a test fixture. */
    private static final int VIDEO_SECONDS = 3;

    /**
     * The device path this app answers with, which the container turns into a `content://` URI.
     *
     * The same contract the device's Files app uses — see `FilesActivity.EXTRA_DEVICE_PATH`. A video
     * is returned by URI rather than by value, so it needs one the caller can actually open, and the
     * encoding of that URI is the container's business rather than this app's.
     */
    private static final String EXTRA_DEVICE_PATH = "dev.blamspot.jcode.vdevice.DEVICE_PATH";

    // The same tones the device's other apps are built from — see the Files and Settings apps' Ui.
    // A camera is mostly viewfinder, so it takes four of them rather than the whole set: the bar is
    // nearly black on purpose, because anything lighter under a picture reads as part of the picture.
    private static final int BACKGROUND = 0xFF0B0F14;
    private static final int SURFACE = 0xFF1E2733;
    private static final int BAR = 0xF00B0F14;
    private static final int TEXT = 0xFFE8ECF4;
    private static final int MUTED = 0xFF97A2B6;
    private static final int ACCENT = 0xFF8AB4F8;

    private final Scene scene = new Scene();

    /** An activity gets one runtime permission request in this container; this is that one. */
    private boolean asked;

    private Viewfinder viewfinder;

    /** Non-null when another app is waiting for a picture. */
    private Uri output;
    private boolean answering;

    /** True when the caller asked for a video rather than a still. */
    private boolean recording;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String action = getIntent() == null ? null : getIntent().getAction();
        recording = MediaStore.ACTION_VIDEO_CAPTURE.equals(action);
        answering = recording
            || MediaStore.ACTION_IMAGE_CAPTURE.equals(action)
            || MediaStore.ACTION_IMAGE_CAPTURE_SECURE.equals(action);
        output = getIntent() == null ? null : getIntent().getParcelableExtra(MediaStore.EXTRA_OUTPUT);
        // Not allowed to ask yet — see refresh(mayAsk).
        refresh(false);
    }

    /**
     * Works out what this app should be showing, from scratch.
     *
     * <p><b>Called on every resume, not once at startup</b>, and that is the whole point on this
     * device. A phone's camera does not appear and disappear while an app is open; this one does —
     * switching it on is a control the person has, two taps away on the hardware bench, and the
     * scene it shows is another. An app that sampled either at `onCreate` would sit on "This device
     * has no camera" after the camera had been switched on, which is exactly what it did: the
     * refusal screen was correct when it was drawn and wrong within seconds, with nothing to
     * redraw it.
     *
     * <p>Coming back to an app now *resumes* it rather than rebuilding it — the container learned to
     * pause guests — so there is no longer an `onCreate` to rely on at all.
     */
    private void refresh(boolean mayAsk) {
        scene.prepare(DeviceScene.chosen(this));
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            viewfinder = null;
            setContentView(refusal("This device has no camera",
                "Switch the camera on in Device hardware, then come back."));
            return;
        }
        if (hasPermission()) {
            setContentView(camera());
            return;
        }
        if (asked) {
            setContentView(refusal("Camera access was denied",
                "Allow the camera for this app in Manage permissions, then come back."));
            return;
        }
        if (!mayAsk) {
            // onCreate. The device's dialog is raised on behalf of whichever activity is in front,
            // and an embedded activity is not in front until it has been resumed — so a request
            // made here is one the device cannot address to anybody, and it vanishes. Measured: the
            // screen sat on "Waiting for permission" with nothing in the device's log at all.
            setContentView(refusal("Camera", "Starting…"));
            return;
        }
        asked = true;
        viewfinder = null;
        setContentView(refusal("Waiting for permission",
            "This app asked the device for camera access."));
        requestPermissions(new String[] {CAMERA}, PERMISSION_CODE);
    }

    /**
     * Re-reads the device on every resume — see {@link #refresh}, and the permission note in it.
     *
     * <p>Asking for the camera at all is what a camera app does: the device's default rule for a
     * dangerous permission is Ask, so an app that only ever *checks* is refused for ever and looks
     * broken.
     */
    @Override
    protected void onResume() {
        super.onResume();
        refresh(true);
        if (viewfinder != null) {
            viewfinder.awake(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code == PERMISSION_CODE) {
            refresh(true);
        }
    }

    private boolean hasPermission() {
        return checkSelfPermission(CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Puts the camera to sleep.
     *
     * <p>A viewfinder told to stop is what stops a frame being drawn for a screen nobody is looking
     * at. A still scene has already stopped on its own — it draws once and never asks for another —
     * so this only has anything to do for an animated one.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (viewfinder != null) {
            viewfinder.awake(false);
        }
    }

    private View camera() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.BLACK);

        // No caption. It used to say that the camera is simulated and the picture is a test image,
        // which the picture says for itself — a pixel-art robot is not going to be mistaken for a
        // photograph of a room, and a viewfinder that explains itself is a viewfinder with less room
        // for the thing it is showing. The one line kept is the one carrying information rather than
        // a disclaimer: which app is waiting, and only when one is.
        if (answering) {
            column.addView(header(callerLabel()),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        viewfinder = new Viewfinder(this);
        viewfinder.awake(true);
        column.addView(viewfinder, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(shutterRow(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    /**
     * What the app says when it cannot show a viewfinder.
     *
     * On a surface of its own rather than on the black the viewfinder would have filled: a screen
     * that is *about* something reads as a message, and one that is black with text on it reads as a
     * camera that has broken.
     */
    private View refusal(String title, String detail) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        column.setBackgroundColor(BACKGROUND);
        column.setPadding(dp(28), dp(28), dp(28), dp(28));

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(SURFACE, dp(20)));
        card.setPadding(dp(22), dp(22), dp(22), dp(18));
        card.addView(text(title, 17f, TEXT));
        if (detail != null) {
            TextView note = text(detail, 13f, MUTED);
            note.setPadding(0, dp(8), 0, 0);
            note.setLineSpacing(dp(3), 1f);
            card.addView(note);
        }
        card.addView(pill("Close", ACCENT, new Runnable() {
            @Override
            public void run() {
                cancel();
            }
        }), spacedTop(dp(18)));
        column.addView(card, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    /** Who is waiting, as a line over the viewfinder rather than a bar taking room from it. */
    private View header(String title) {
        TextView label = text(title, 12f, TEXT);
        label.setBackground(rounded(0xCC1E2733, dp(999)));
        label.setPadding(dp(14), dp(7), dp(14), dp(7));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_HORIZONTAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(4));
        row.addView(label);
        return row;
    }

    /**
     * The bar under the viewfinder: leave on the left, a round shutter in the middle.
     *
     * The shutter is the one control on a camera that nobody has to be told about, and it was a text
     * button reading "Take photo". A ring with a filled centre is the same tap with the shape people
     * already know — red and square for a recording, which is the other shape they know.
     */
    private View shutterRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(BAR);
        row.setPadding(dp(18), dp(14), dp(18), dp(18));

        View leave = pill(answering ? "Cancel" : "Close", MUTED, new Runnable() {
            @Override
            public void run() {
                cancel();
            }
        });
        row.addView(leave);
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(shutter());
        // The same width as the button on the left, so the shutter sits in the middle of the bar
        // rather than in the middle of what is left over.
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        View balance = new View(this);
        balance.setVisibility(View.INVISIBLE);
        row.addView(balance, new LinearLayout.LayoutParams(dp(72), dp(1)));
        return row;
    }

    /** The ring, and what is inside it: a white circle for a photo, a red square for a recording. */
    private View shutter() {
        FrameLayout button = new FrameLayout(this);
        button.setBackground(ring());
        int size = dp(66);
        button.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        button.setContentDescription(recording ? "Record " + VIDEO_SECONDS + " seconds" : "Take photo");
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                if (recording) {
                    record();
                } else {
                    capture();
                }
            }
        });

        View core = new View(this);
        core.setBackground(recording
            ? rounded(0xFFE5484D, dp(7))
            : rounded(Color.WHITE, dp(999)));
        int inner = recording ? dp(26) : dp(50);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(inner, inner);
        params.gravity = Gravity.CENTER;
        button.addView(core, params);
        return button;
    }

    /** A text button with a shape, so a tap target looks like one before it is tapped. */
    private View pill(String label, int colour, final Runnable onClick) {
        TextView button = text(label, 14f, colour);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), dp(11), dp(18), dp(11));
        button.setBackground(rounded(SURFACE, dp(999)));
        button.setContentDescription(label);
        button.setClickable(true);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        return button;
    }

    private TextView text(String value, float size, int colour) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        return view;
    }

    private GradientDrawable rounded(int colour, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(radius);
        return shape;
    }

    private GradientDrawable ring() {
        GradientDrawable shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setColor(0x33FFFFFF);
        shape.setStroke(dp(3), Color.WHITE);
        return shape;
    }

    private LinearLayout.LayoutParams spacedTop(int margin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = margin;
        return params;
    }

    /** Sized against the screen's density, so the bar is the same size on a tablet as on a phone. */
    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Whoever is waiting, named the way a person would recognise them. */
    private String callerLabel() {
        String caller = getCallingPackage();
        String who = "An app";
        if (caller != null) {
            who = caller;
            try {
                who = getPackageManager().getApplicationLabel(
                    getPackageManager().getApplicationInfo(caller, 0)).toString();
            } catch (Exception e) {
                // The package name is a worse answer than the label and a much better one than none.
            }
        }
        return who + (recording ? " wants a video" : " wants a photo");
    }

    /**
     * Takes the picture.
     *
     * <p>The still is rendered at sensor size rather than grabbed from the viewfinder: the
     * viewfinder is the size of the window, and an app that reads the bounds of a 400 px "photo" is
     * being told something untrue about the device.
     */
    private void capture() {
        Bitmap still = render(STILL_WIDTH, STILL_HEIGHT);
        File file = new File(DeviceStorage.pictures(this), "IMG_" + SystemClock.elapsedRealtime() + ".jpg");
        boolean written = write(still, file);
        if (!written) {
            still.recycle();
            failed("could not write the photo to " + DeviceStorage.display(this, file));
            return;
        }
        Log.i(TAG, "took a photo: " + DeviceStorage.display(this, file) + " (" + file.length() + " bytes)");

        if (!answering) {
            still.recycle();
            Toast.makeText(this, "Saved to " + DeviceStorage.display(this, file), Toast.LENGTH_SHORT).show();
            return;
        }
        // With EXTRA_OUTPUT the caller gets the full-size image at its own URI and a bare RESULT_OK;
        // a failure there is answered as a *cancel* rather than as an OK with nothing behind it,
        // because an app told the capture succeeded and then reading an empty file is the harder
        // thing to debug.
        if (output != null) {
            still.recycle();
            if (copy(file, output)) {
                setResult(RESULT_OK, new Intent());
                finish();
            } else {
                failed("could not write the photo to " + output);
            }
            return;
        }
        Intent answer = new Intent();
        answer.putExtra(EXTRA_THUMBNAIL, thumbnail(still, file));
        still.recycle();
        setResult(RESULT_OK, answer);
        finish();
    }

    /**
     * Records the scene to an MP4 and answers with it.
     *
     * <p>On the calling thread, which is the main one, and deliberately: three seconds at 15 fps is
     * 45 frames of a test pattern, the encode takes well under a second, and a background thread
     * here would buy a spinner the recording does not last long enough to need. If the clip ever
     * grows, this is the line that has to change first.
     */
    private void record() {
        File file = new File(DeviceStorage.pictures(this),
            "VID_" + SystemClock.elapsedRealtime() + ".mp4");
        boolean recorded = Recorder.record(file, VIDEO_SECONDS, new Recorder.Renderer() {
            @Override
            public void draw(Canvas canvas, int width, int height, long elapsedMs) {
                scene.draw(canvas, width, height, elapsedMs);
            }
        });
        if (!recorded) {
            failed("could not record to " + DeviceStorage.display(this, file));
            return;
        }
        Log.i(TAG, "recorded " + DeviceStorage.display(this, file) + " (" + file.length() + " bytes)");
        if (!answering) {
            Toast.makeText(this, "Saved to " + DeviceStorage.display(this, file),
                Toast.LENGTH_SHORT).show();
            return;
        }
        // EXTRA_OUTPUT wins when the caller named a destination, exactly as it does for a still.
        if (output != null && !copy(file, output)) {
            failed("could not write the video to " + output);
            return;
        }
        Intent answer = new Intent();
        if (output == null) {
            answer.putExtra(EXTRA_DEVICE_PATH, DeviceStorage.display(this, file));
        }
        setResult(RESULT_OK, answer);
        finish();
    }

    private Bitmap render(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(bitmap), width, height, SystemClock.elapsedRealtime());
        return bitmap;
    }

    private boolean write(Bitmap bitmap, File file) {
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            return bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out);
        } catch (IOException e) {
            Log.w(TAG, "cannot write " + file, e);
            return false;
        } finally {
            close(out);
        }
    }

    private boolean copy(File from, Uri to) {
        InputStream in = null;
        OutputStream out = null;
        try {
            out = getContentResolver().openOutputStream(to);
            if (out == null) {
                return false;
            }
            in = new java.io.FileInputStream(from);
            byte[] buffer = new byte[8192];
            for (int read = in.read(buffer); read > 0; read = in.read(buffer)) {
                out.write(buffer, 0, read);
            }
            return true;
        } catch (Exception e) {
            Log.w(TAG, "cannot copy the photo to " + to, e);
            return false;
        } finally {
            close(in);
            close(out);
        }
    }

    /** The small bitmap the no-`EXTRA_OUTPUT` form of the contract answers with. */
    private Bitmap thumbnail(Bitmap still, File file) {
        Bitmap source = still;
        if (source == null || source.isRecycled()) {
            source = BitmapFactory.decodeFile(file.getAbsolutePath());
        }
        if (source == null) {
            return null;
        }
        float scale = (float) THUMBNAIL / Math.max(source.getWidth(), source.getHeight());
        return Bitmap.createScaledBitmap(source,
            Math.round(source.getWidth() * scale), Math.round(source.getHeight() * scale), true);
    }

    private void failed(String why) {
        Log.w(TAG, why);
        Toast.makeText(this, why, Toast.LENGTH_LONG).show();
        if (answering) {
            cancel();
        }
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    private static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a stream that will not close.
            }
        }
    }

    /**
     * The live picture. Invalidated from its own draw, which is what a viewfinder is: there is no
     * frame to wait for, only the next evaluation of the scene.
     *
     * <p><b>It stops when the camera is not open.</b> A loop that re-posts itself from `onDraw` has
     * no natural end — it ran for as long as the activity existed, which for a camera app is "until
     * something else needs the screen", and a device whose camera never switches off is a device
     * doing work nobody asked for. {@link #awake} is set from `onResume` and cleared from `onPause`,
     * so the last frame drawn is the last frame computed.
     *
     * <p>The rate is 30 fps rather than the display's, which is what the frame counter has always
     * claimed and is more than a test pattern needs; `postInvalidateOnAnimation` would run it at
     * 60 or 120.
     */
    private class Viewfinder extends View {

        private boolean awake;

        Viewfinder(Context context) {
            super(context);
        }

        void awake(boolean value) {
            if (awake == value) {
                return;
            }
            awake = value;
            if (value) {
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            scene.draw(canvas, getWidth(), getHeight(), SystemClock.elapsedRealtime());
            // A still scene is drawn once and then left alone — no timer, no invalidation, nothing
            // running at all. Only an animated one asks for another frame, and it asks at the
            // scene's own rate rather than the display's.
            if (awake && scene.animated()) {
                postInvalidateDelayed(scene.frameMs());
            }
        }
    }
}

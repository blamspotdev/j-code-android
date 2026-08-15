package dev.jcode.vdevice.camera;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
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
 * <p>It is an ordinary guest. No container privileges, no Camera2: the picture is drawn from the
 * device's own motion sensors, which is something any app may read, and saved with ordinary file
 * IO. That is deliberate — the app that proves the device has a camera should not be the one app
 * that needs special help to run.
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
public class CameraActivity extends Activity implements SensorEventListener {

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
    private static final String EXTRA_DEVICE_PATH = "dev.jcode.vdevice.DEVICE_PATH";

    private final Scene scene = new Scene();
    private final float[] gravity = new float[3];
    private final float[] geomagnetic = new float[3];
    private final float[] rotation = new float[9];
    private final float[] orientation = new float[3];

    private boolean haveGravity;
    private boolean haveGeomagnetic;

    /** An activity gets one runtime permission request in this container; this is that one. */
    private boolean asked;

    private SensorManager sensors;
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
        sensors = (SensorManager) getSystemService(SENSOR_SERVICE);

        // The device's own switch decides whether there is a camera at all, and this app is told
        // about it exactly as any other app would be. Saying so beats a viewfinder that cannot
        // produce anything.
        setContentView(getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)
            ? camera()
            : refusal("This device has no camera", "Switch the camera on in Device hardware."));
    }

    /**
     * Asks for the camera, once, from `onResume` rather than `onCreate`.
     *
     * <p>Asking at all is what a camera app does: the device's default rule for a dangerous
     * permission is Ask, so an app that only ever *checks* is refused for ever and looks broken.
     *
     * <p>Asking <em>here</em> is what makes it work inside the container. A request is answered by
     * the device's own dialog, raised on behalf of whichever activity is in front, and an embedded
     * activity is not in front until it has been resumed — so a request made from `onCreate` is one
     * the device cannot address to anybody, and it vanishes. Measured: the screen sat on "Waiting
     * for permission" with nothing in the device's log at all.
     */
    @Override
    protected void onResume() {
        super.onResume();
        listen(Sensor.TYPE_ACCELEROMETER);
        listen(Sensor.TYPE_MAGNETIC_FIELD);
        if (viewfinder != null) {
            viewfinder.awake(true);
        }
        if (!getPackageManager().hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
            return;
        }
        if (hasPermission() || asked) {
            return;
        }
        asked = true;
        setContentView(refusal("Waiting for permission",
            "This app asked the device for camera access."));
        requestPermissions(new String[] {CAMERA}, PERMISSION_CODE);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        if (code != PERMISSION_CODE) {
            return;
        }
        setContentView(hasPermission()
            ? camera()
            : refusal("Camera access was denied",
                "Allow the camera for this app in Manage permissions, then open it again."));
    }

    private boolean hasPermission() {
        return checkSelfPermission(CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Puts the camera to sleep.
     *
     * <p>Both halves matter and neither is automatic: an unregistered listener is what stops the
     * device's simulated sensors ticking for this app, and a viewfinder told to stop is what stops
     * a frame being computed thirty times a second for a screen nobody is looking at.
     */
    @Override
    protected void onPause() {
        super.onPause();
        if (sensors != null) {
            sensors.unregisterListener(this);
        }
        if (viewfinder != null) {
            viewfinder.awake(false);
        }
    }

    private void listen(int type) {
        if (sensors == null) {
            return;
        }
        Sensor sensor = sensors.getDefaultSensor(type);
        if (sensor != null) {
            sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
        }
    }

    private View camera() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.BLACK);

        column.addView(header(answering ? callerLabel() : "Camera",
                "This camera is simulated — the picture is a test image, not the phone's camera."),
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        viewfinder = new Viewfinder(this);
        viewfinder.awake(true);
        column.addView(viewfinder, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        column.addView(shutterRow(), new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return column;
    }

    private View refusal(String title, String detail) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.BLACK);
        column.addView(header(title, detail));
        column.addView(button("Close", 0xFF8AB4F8, new Runnable() {
            @Override
            public void run() {
                cancel();
            }
        }));
        return column;
    }

    private View header(String title, String detail) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(36, 32, 36, 24);
        TextView heading = new TextView(this);
        heading.setText(title);
        heading.setTextColor(0xFFE6E8EF);
        heading.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        TextView note = new TextView(this);
        note.setText(detail);
        note.setTextColor(0xFF9AA0B0);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        column.addView(heading);
        column.addView(note);
        return column;
    }

    private View shutterRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 12, 16, 20);
        row.addView(button(answering ? "Cancel" : "Close", 0xFF9AA0B0, new Runnable() {
            @Override
            public void run() {
                cancel();
            }
        }));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        row.addView(button(recording ? "Record " + VIDEO_SECONDS + "s" : "Take photo", 0xFF8AB4F8,
            new Runnable() {
                @Override
                public void run() {
                    if (recording) {
                        record();
                    } else {
                        capture();
                    }
                }
            }));
        return row;
    }

    private Button button(String label, int colour, final Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(colour);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setContentDescription(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        return button;
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
                scene.draw(canvas, width, height, degrees(0), degrees(1), degrees(2), elapsedMs);
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
        scene.draw(new Canvas(bitmap), width, height,
            degrees(0), degrees(1), degrees(2), SystemClock.elapsedRealtime());
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
     * The device's attitude in degrees: azimuth, pitch, roll.
     *
     * <p>`getRotationMatrix` + `getOrientation` from the accelerometer and the magnetometer, which
     * is the ordinary way an app reads its own attitude — and so is also a live check that the
     * container's simulated sensors are consistent with each other, since a wrong sign in either
     * feed shows up here as a horizon that tilts the wrong way.
     */
    private float degrees(int index) {
        if (!haveGravity || !haveGeomagnetic) {
            return 0f;
        }
        if (!SensorManager.getRotationMatrix(rotation, null, gravity, geomagnetic)) {
            return 0f;
        }
        SensorManager.getOrientation(rotation, orientation);
        float value = (float) Math.toDegrees(orientation[index]);
        return index == 0 && value < 0f ? value + 360f : value;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            System.arraycopy(event.values, 0, gravity, 0, 3);
            haveGravity = true;
        } else if (event.sensor.getType() == Sensor.TYPE_MAGNETIC_FIELD) {
            System.arraycopy(event.values, 0, geomagnetic, 0, 3);
            haveGeomagnetic = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // Nothing here depends on reported accuracy.
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

        private static final long FRAME_MS = 33L;

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
            scene.draw(canvas, getWidth(), getHeight(),
                degrees(0), degrees(1), degrees(2), SystemClock.elapsedRealtime());
            if (awake) {
                postInvalidateDelayed(FRAME_MS);
            }
        }
    }
}

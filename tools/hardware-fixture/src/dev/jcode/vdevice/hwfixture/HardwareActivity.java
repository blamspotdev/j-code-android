package dev.jcode.vdevice.hwfixture;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Says out loud what hardware a guest can actually see on JCode's virtual device.
 *
 * Every line is something the container has to get right for "Manage permissions" to mean anything:
 * what the device declares it has, what it says the app may use, which sensors are in the list,
 * what those sensors report, and where the device thinks it is. Off / Simulated / Real should each
 * produce a visibly different screen for the same app, without reinstalling it.
 *
 * The values matter as much as the presence. A simulated accelerometer reads (0, 0, 9.81) and holds
 * still; the phone's own never sits exactly on those numbers and twitches in the last decimal, so
 * the two are told apart at a glance rather than by trusting a label.
 */
public class HardwareActivity extends Activity implements SensorEventListener, LocationListener {

    private static final String TAG = "HWFIXTURE";
    private static final int REQUEST_CODE = 4321;
    private static final int PHOTO_CODE = 4322;

    private static final String[] DANGEROUS = {
        "android.permission.CAMERA",
        "android.permission.RECORD_AUDIO",
        "android.permission.ACCESS_FINE_LOCATION",
    };

    private static final String[] FEATURES = {
        "android.hardware.camera",
        "android.hardware.microphone",
        "android.hardware.location.gps",
        "android.hardware.sensor.accelerometer",
        "android.hardware.sensor.compass",
        "android.hardware.sensor.gyroscope",
    };

    private static final int[] SENSORS = {
        Sensor.TYPE_ACCELEROMETER,
        Sensor.TYPE_MAGNETIC_FIELD,
        Sensor.TYPE_GYROSCOPE,
    };

    private final Map<Integer, String> readings = new LinkedHashMap<>();
    private final Handler handler = new Handler(Looper.getMainLooper());

    private TextView out;
    private String lastRequest = "not asked yet";
    private String lastPhoto = "not asked yet";
    private String lastFix = "no update yet";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.parseColor("#101418"));
        column.setPadding(24, 24, 24, 24);

        Button ask = new Button(this);
        ask.setText("Request camera, mic and location");
        ask.setOnClickListener(v -> {
            lastRequest = "asked, waiting for the answer…";
            requestPermissions(DANGEROUS, REQUEST_CODE);
        });
        column.addView(ask, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ACTION_IMAGE_CAPTURE is how an app asks for a picture rather than for a camera pipeline,
        // and it is the form the device can answer completely — the container shows its own
        // viewfinder and hands back an image. Deliberately with no EXTRA_OUTPUT, so what comes back
        // is the contract's thumbnail: that exercises the whole round trip, including the result
        // arriving at an embedded activity at all, which is the part that used to be impossible.
        Button photo = new Button(this);
        photo.setText("Take a photo (ACTION_IMAGE_CAPTURE)");
        photo.setOnClickListener(v -> {
            lastPhoto = "asked, waiting for the answer…";
            startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), PHOTO_CODE);
        });
        column.addView(photo, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextColor(Color.parseColor("#D7E3EC"));
        out.setTextSize(12f);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(out);
        column.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        setContentView(column);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        Log.i(TAG, "SensorManager is " + (sensors == null ? "null" : sensors.getClass().getName()));
        if (sensors != null) {
            for (int type : SENSORS) {
                Sensor sensor = sensors.getDefaultSensor(type);
                if (sensor != null) {
                    sensors.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI);
                }
            }
        }

        LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
        Log.i(TAG, "LocationManager is " + (location == null ? "null" : location.getClass().getName()));
        if (location != null) {
            try {
                location.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000L, 0f, this);
            } catch (Throwable t) {
                lastFix = "requestLocationUpdates threw " + t.getClass().getSimpleName();
                Log.w(TAG, "cannot request updates", t);
            }
        }
        handler.post(refresh);
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refresh);
        SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        if (sensors != null) sensors.unregisterListener(this);
        LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (location != null) {
            try {
                location.removeUpdates(this);
            } catch (Throwable ignored) {
                // Nothing was registered, which is one of the answers this fixture is testing for.
            }
        }
    }

    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            out.setText(report());
            handler.postDelayed(this, 500L);
        }
    };

    private String report() {
        StringBuilder text = new StringBuilder();
        text.append("PERMISSIONS (checkSelfPermission)\n");
        for (String permission : DANGEROUS) {
            text.append("  ").append(shortName(permission)).append(" = ")
                .append(checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                    ? "GRANTED" : "denied")
                .append('\n');
        }
        text.append("  last requestPermissions: ").append(lastRequest).append('\n');
        text.append("  last photo: ").append(lastPhoto).append("\n\n");

        text.append("FEATURES (hasSystemFeature)\n");
        for (String feature : FEATURES) {
            text.append("  ").append(feature).append(" = ")
                .append(getPackageManager().hasSystemFeature(feature)).append('\n');
        }

        SensorManager sensors = (SensorManager) getSystemService(SENSOR_SERVICE);
        text.append("\nSENSORS (").append(sensors == null ? "no manager"
            : sensors.getSensorList(Sensor.TYPE_ALL).size() + " in the list").append(")\n");
        for (int type : SENSORS) {
            Sensor sensor = sensors == null ? null : sensors.getDefaultSensor(type);
            text.append("  ").append(name(type)).append(" = ")
                .append(sensor == null ? "ABSENT" : sensor.getName()).append('\n');
            text.append("      ").append(readings.containsKey(type)
                ? readings.get(type) : "no events").append('\n');
        }

        text.append("\nLOCATION\n");
        LocationManager location = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (location == null) {
            text.append("  no manager\n");
        } else {
            text.append("  providers = ").append(providers(location)).append('\n');
            text.append("  gps enabled = ").append(enabled(location)).append('\n');
            text.append("  last known = ").append(lastKnown(location)).append('\n');
            text.append("  updates = ").append(lastFix).append('\n');
        }
        return text.toString();
    }

    private String providers(LocationManager location) {
        try {
            List<String> all = location.getAllProviders();
            return all == null || all.isEmpty() ? "none" : all.toString();
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    private String enabled(LocationManager location) {
        try {
            return String.valueOf(location.isProviderEnabled(LocationManager.GPS_PROVIDER));
        } catch (Throwable t) {
            return t.getClass().getSimpleName();
        }
    }

    private String lastKnown(LocationManager location) {
        try {
            Location fix = location.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            return fix == null ? "null" : format(fix);
        } catch (Throwable t) {
            return t.getClass().getSimpleName() + ": " + t.getMessage();
        }
    }

    private static String format(Location fix) {
        return String.format(Locale.US, "%.5f, %.5f (%s, ±%.0fm)",
            fix.getLatitude(), fix.getLongitude(), fix.getProvider(), fix.getAccuracy());
    }

    private static String shortName(String permission) {
        return permission.substring(permission.lastIndexOf('.') + 1);
    }

    private static String name(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER: return "accelerometer";
            case Sensor.TYPE_MAGNETIC_FIELD: return "magnetometer ";
            case Sensor.TYPE_GYROSCOPE: return "gyroscope    ";
            default: return "sensor " + type;
        }
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        StringBuilder values = new StringBuilder();
        for (int i = 0; i < Math.min(event.values.length, 3); i++) {
            if (i > 0) values.append(", ");
            values.append(String.format(Locale.US, "%+.5f", event.values[i]));
        }
        readings.put(event.sensor.getType(), values.toString());
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        Log.i(TAG, "accuracy of " + sensor.getName() + " is " + accuracy);
    }

    @Override
    public void onLocationChanged(Location fix) {
        lastFix = format(fix);
    }

    /**
     * The other half of the camera: an app that asks for a picture has to be told it got one.
     *
     * The size is reported rather than the bitmap shown, because the number is the check — a
     * thumbnail with pixels in it means the device rendered a frame, wrote a JPEG, decoded it and
     * carried it back across a result path an embedded activity has no business having.
     */
    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != PHOTO_CODE) {
            return;
        }
        if (result != RESULT_OK) {
            lastPhoto = "cancelled (result " + result + ")";
        } else {
            Object thumbnail = data == null ? null : data.getExtras().get("data");
            lastPhoto = thumbnail instanceof Bitmap
                ? "got a " + ((Bitmap) thumbnail).getWidth() + "x"
                    + ((Bitmap) thumbnail).getHeight() + " thumbnail"
                : "RESULT_OK with no bitmap";
        }
        Log.i(TAG, "onActivityResult " + code + ": " + lastPhoto);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] permissions, int[] results) {
        List<String> answers = new ArrayList<>();
        for (int i = 0; i < permissions.length; i++) {
            answers.add(shortName(permissions[i]) + "="
                + (i < results.length && results[i] == PackageManager.PERMISSION_GRANTED
                    ? "granted" : "denied"));
        }
        lastRequest = answers.isEmpty() ? "answered with nothing" : String.join(" ", answers);
        Log.i(TAG, "onRequestPermissionsResult " + code + ": " + Arrays.toString(results));
    }
}

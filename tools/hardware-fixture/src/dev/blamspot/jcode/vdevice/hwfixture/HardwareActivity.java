package dev.blamspot.jcode.vdevice.hwfixture;

import android.app.ActionBar;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.bluetooth.BluetoothAdapter;
import android.hardware.camera2.CameraManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.wifi.WifiManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.InputStream;
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
    private static final int PICK_CODE = 4323;
    private static final int VIDEO_CODE = 4324;

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

    /** How the window is asking to be laid out around the device's status bar. */
    private static final int NORMAL = 1;
    private static final int BEHIND = 2;
    private static final int FULLSCREEN = 3;

    private static final int MENU_NORMAL = 101;
    private static final int MENU_BEHIND = 102;
    private static final int MENU_FULLSCREEN = 103;
    private static final int MENU_REFRESH = 104;

    private TextView out;
    private int statusBarMode = NORMAL;
    private String lastInsets = "nothing dispatched yet";
    private String lastRequest = "not asked yet";
    private String lastPhoto = "not asked yet";
    private String lastPick = "not asked yet";
    private String lastVideo = "not asked yet";
    private String lastFix = "no update yet";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(Color.parseColor("#101418"));
        column.setPadding(24, 24, 24, 24);

        // What the device says is covering this window, recorded as it is delivered rather than
        // asked for afterwards: `getRootWindowInsets` answers out of the view root, which in an
        // embedded hierarchy is a statement about the IDE's window. The dispatch is the thing the
        // container substitutes, so the dispatch is what this fixture reports.
        //
        // Nothing is padded here on purpose. The whole question is whether the *platform* lays this
        // app out correctly when it is handed the device's insets — the action bar sitting below
        // the device's status bar in edge-to-edge mode is the framework doing that, and a fixture
        // that padded itself by hand would hide exactly the thing it is meant to show.
        getWindow().getDecorView().setOnApplyWindowInsetsListener((view, insets) -> {
            lastInsets = String.format(Locale.US, "status %d, nav %d, ime %d (bar reported %s)",
                insets.getInsets(WindowInsets.Type.statusBars()).top,
                insets.getInsets(WindowInsets.Type.navigationBars()).bottom,
                insets.getInsets(WindowInsets.Type.ime()).bottom,
                insets.isVisible(WindowInsets.Type.statusBars()) ? "visible" : "hidden");
            return view.onApplyWindowInsets(insets);
        });
        statusBarMode(NORMAL);

        Button ask = new Button(this);
        ask.setText("Request camera, mic and location");
        ask.setOnClickListener(v -> {
            lastRequest = "asked, waiting for the answer…";
            requestPermissions(DANGEROUS, REQUEST_CODE);
        });
        column.addView(ask, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ACTION_IMAGE_CAPTURE is how an app asks for a picture rather than for a camera pipeline,
        // and it is the form the device can answer completely — its own Camera app opens and hands
        // back an image. Deliberately with no EXTRA_OUTPUT, so what comes back is the contract's
        // thumbnail: that exercises the whole round trip, including the result arriving at an
        // embedded activity at all, which is the part that used to be impossible.
        Button photo = new Button(this);
        photo.setText("Take a photo (ACTION_IMAGE_CAPTURE)");
        photo.setOnClickListener(v -> {
            lastPhoto = "asked, waiting for the answer…";
            startActivityForResult(new Intent(MediaStore.ACTION_IMAGE_CAPTURE), PHOTO_CODE);
        });
        column.addView(photo, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The other half of "does this device have the apps a device has". OPEN_DOCUMENT is
        // answered by the device's Files app, and what comes back is a content:// URI into the
        // device's own storage — so this reads the first bytes of whatever was picked, which is the
        // only way to tell a URI that resolves from one that merely looks right.
        Button pick = new Button(this);
        pick.setText("Pick a file (ACTION_OPEN_DOCUMENT)");
        pick.setOnClickListener(v -> {
            lastPick = "asked, waiting for the answer…";
            Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            open.addCategory(Intent.CATEGORY_OPENABLE);
            open.setType("*/*");
            startActivityForResult(open, PICK_CODE);
        });
        column.addView(pick, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The device draws frames, so it can encode them: ACTION_VIDEO_CAPTURE comes back as a
        // content:// URI, and reading its first bytes is the only way to tell a URI that resolves
        // from one that merely looks right.
        Button video = new Button(this);
        video.setText("Record a video (ACTION_VIDEO_CAPTURE)");
        video.setOnClickListener(v -> {
            lastVideo = "asked, waiting for the answer…";
            startActivityForResult(new Intent(MediaStore.ACTION_VIDEO_CAPTURE), VIDEO_CODE);
        });
        column.addView(video, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // "Is there a browser?" gets asked three ways and answered here two of them: the report
        // below says what `resolveActivity` finds, and this opens the link. Both matter — an app
        // that resolves nothing hides its button and never gets as far as opening anything.
        Button link = new Button(this);
        link.setText("Open a link (ACTION_VIEW)");
        link.setOnClickListener(v ->
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/"))));
        column.addView(link, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        out = new TextView(this);
        out.setTypeface(Typeface.MONOSPACE);
        out.setTextColor(Color.parseColor("#D7E3EC"));
        out.setTextSize(12f);
        column.addView(out, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // The whole column scrolls, rather than a report that scrolls inside a column that does not.
        //
        // That was the layout, and it does not survive the window this fixture actually runs in: the
        // device's screen is an editor tab, and five buttons at their natural height fill it, so a
        // `ScrollView` given "whatever is left" was given about twenty pixels and the report — the
        // entire point of the app — could not be reached at any size the tab comes in.
        ScrollView scroll = new ScrollView(this);
        scroll.addView(column, new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);
    }

    /**
     * The app bar's menu, built in code because this fixture has no resources to inflate one from.
     *
     * One item as a button and the rest in the overflow, which is deliberate: they are two different
     * pieces of machinery. The button is laid out by the action bar itself; the overflow opens a
     * `PopupWindow`, which is a **separate window** and needs a token the embedded activity was
     * given by the container rather than by the window manager.
     *
     * Reaching this method at all is the check. The framework builds an action bar's menu from a
     * pass it schedules on the decor view, after `onPostCreate`; a container that drives the
     * lifecycle by hand and skips that step gets a bar with a blank title and no menu, which looks
     * near enough to right to go unnoticed.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(Menu.NONE, MENU_REFRESH, 0, "Refresh")
            .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        menu.add(Menu.NONE, MENU_NORMAL, 1, "Status bar: sit below it");
        menu.add(Menu.NONE, MENU_BEHIND, 2, "Status bar: draw behind it");
        menu.add(Menu.NONE, MENU_FULLSCREEN, 3, "Status bar: take the screen");
        return true;
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        checkOnly(menu, MENU_NORMAL, statusBarMode == NORMAL);
        checkOnly(menu, MENU_BEHIND, statusBarMode == BEHIND);
        checkOnly(menu, MENU_FULLSCREEN, statusBarMode == FULLSCREEN);
        return true;
    }

    private static void checkOnly(Menu menu, int id, boolean checked) {
        MenuItem item = menu.findItem(id);
        if (item != null) {
            item.setCheckable(true);
            item.setChecked(checked);
        }
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_NORMAL: statusBarMode(NORMAL); return true;
            case MENU_BEHIND: statusBarMode(BEHIND); return true;
            case MENU_FULLSCREEN: statusBarMode(FULLSCREEN); return true;
            case MENU_REFRESH: out.setText(report()); return true;
            default: return super.onOptionsItemSelected(item);
        }
    }

    /**
     * Says how this window wants to be laid out around the device's status bar, in the three shapes
     * the container reads — and then says nothing else about it.
     *
     * That is the test. An app states a preference through its window and the *device* decides what
     * follows: whether the bar is there, what it is painted, whether this window starts below it,
     * and what insets this app is handed. Off / behind / full screen should each produce a visibly
     * different screen for the same app, with the report below saying which insets came with it.
     */
    private void statusBarMode(int mode) {
        statusBarMode = mode;
        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN
            | WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        switch (mode) {
            case BEHIND:
                // Transparent is the statement: an app that sets it is undertaking to inset its own
                // content by whatever the device says is over it.
                window.setStatusBarColor(Color.TRANSPARENT);
                break;
            case FULLSCREEN:
                window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
                break;
            default:
                window.setStatusBarColor(Color.parseColor("#FF16202B"));
                break;
        }
        invalidateOptionsMenu();
    }

    private String modeName() {
        switch (statusBarMode) {
            case BEHIND: return "draw behind it";
            case FULLSCREEN: return "take the screen";
            default: return "sit below it";
        }
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

        // The window this app was given, which is the other half of "does the device host an app
        // properly" — the half that is about screen furniture rather than hardware. A bar with an
        // empty title is what a container that skips onPostCreate produces, and it is close enough
        // to right that only reading the title out loud catches it.
        text.append("WINDOW\n");
        ActionBar bar = getActionBar();
        text.append("  action bar = ")
            .append(bar == null ? "none (the theme asked for one)"
                : (bar.isShowing() ? "showing" : "hidden"))
            .append('\n');
        text.append("  its title  = ")
            .append(bar == null || bar.getTitle() == null ? "EMPTY" : "\"" + bar.getTitle() + "\"")
            .append('\n');
        text.append("  asked for  = ").append(modeName()).append('\n');
        text.append("  insets     = ").append(lastInsets).append('\n');
        text.append("  content    = ")
            .append(findViewById(android.R.id.content).getWidth()).append('x')
            .append(findViewById(android.R.id.content).getHeight()).append("px\n\n");

        text.append("PERMISSIONS (checkSelfPermission)\n");
        for (String permission : DANGEROUS) {
            text.append("  ").append(shortName(permission)).append(" = ")
                .append(checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
                    ? "GRANTED" : "denied")
                .append('\n');
        }
        text.append("  last requestPermissions: ").append(lastRequest).append('\n');
        text.append("  last photo: ").append(lastPhoto).append('\n');
        text.append("  last pick:  ").append(lastPick).append('\n');
        text.append("  last video: ").append(lastVideo).append("\n\n");

        text.append("FEATURES (hasSystemFeature)\n");
        for (String feature : FEATURES) {
            text.append("  ").append(feature).append(" = ")
                .append(getPackageManager().hasSystemFeature(feature)).append('\n');
        }

        // What the device answers when an app asks whether it can do a thing, rather than asking it
        // to. This is the question that used to be answered from the *phone's* installed apps.
        text.append("\nRESOLVES TO (PackageManager)\n");
        resolves(text, "a link", new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.org/")));
        resolves(text, "a browser", new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
        resolves(text, "a web search", new Intent(Intent.ACTION_WEB_SEARCH));
        resolves(text, "a photo", new Intent(MediaStore.ACTION_IMAGE_CAPTURE));
        resolves(text, "a document", new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*"));
        resolves(text, "a video", new Intent(MediaStore.ACTION_VIDEO_CAPTURE));
        resolves(text, "settings", new Intent("android.settings.SETTINGS"));

        // What the device says about the network. An app's first question is almost always "am I
        // online?", and until the device answers it the answer is the phone's.
        text.append("\nNETWORK\n");
        ConnectivityManager connectivity =
            (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (connectivity == null) {
            text.append("  no ConnectivityManager\n");
        } else {
            Network active = connectivity.getActiveNetwork();
            NetworkCapabilities caps =
                active == null ? null : connectivity.getNetworkCapabilities(active);
            text.append("  active = ").append(active == null ? "none" : active.toString()).append('\n');
            text.append("  wifi = ").append(caps != null
                && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).append('\n');
            text.append("  validated = ").append(caps != null
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)).append('\n');
        }
        WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
        text.append("  wifi enabled = ")
            .append(wifi == null ? "no manager" : String.valueOf(wifi.isWifiEnabled())).append('\n');
        BluetoothAdapter bluetooth = BluetoothAdapter.getDefaultAdapter();
        text.append("  bluetooth = ").append(bluetooth == null ? "no adapter"
            : (bluetooth.isEnabled() ? "on" : "off")).append('\n');

        // What Camera2 offers a guest, which is a different question from whether the device has a
        // camera: ACTION_IMAGE_CAPTURE is answered by the device's Camera app, and this is the
        // pipeline underneath it. An empty id list is the honest answer; one naming the *phone's*
        // cameras would be a leak worth seeing.
        text.append("\nCAMERA2 (CameraManager)\n  ");
        try {
            CameraManager cameras = (CameraManager) getSystemService(CAMERA_SERVICE);
            text.append(cameras == null ? "no manager"
                : "ids = " + Arrays.toString(cameras.getCameraIdList()));
        } catch (Throwable t) {
            text.append("getCameraIdList threw ").append(t.getClass().getSimpleName());
        }
        text.append('\n');

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
        if (code == VIDEO_CODE) {
            lastVideo = describePick(result, data);
            Log.i(TAG, "onActivityResult " + code + ": " + lastVideo);
            return;
        }
        if (code == PICK_CODE) {
            lastPick = describePick(result, data);
            Log.i(TAG, "onActivityResult " + code + ": " + lastPick);
            return;
        }
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

    /**
     * What came back from the picker, and whether it can actually be opened.
     *
     * The read is the check. A `content://` URI that resolves to nothing looks identical to one that
     * works until something tries it, and "the picker returned a URI" was never the interesting
     * claim — "the app can read the file the person chose" is.
     */
    /**
     * What the package manager says handles {@code intent} — the device's app, or nothing.
     *
     * The package name is what makes it readable: `dev.blamspot.jcode.vdevice.browser` is the device
     * answering, anything else is the phone answering, and "none" is an app that would hide its
     * button.
     */
    private void resolves(StringBuilder text, String what, Intent intent) {
        ResolveInfo info = getPackageManager().resolveActivity(intent, 0);
        text.append("  ").append(what).append(" = ")
            .append(info == null || info.activityInfo == null
                ? "none" : info.activityInfo.packageName)
            .append('\n');
    }

    private String describePick(int result, Intent data) {
        if (result != RESULT_OK || data == null || data.getData() == null) {
            return "cancelled (result " + result + ")";
        }
        Uri uri = data.getData();
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                return uri + " — could not be opened";
            }
            byte[] head = new byte[64];
            int read = Math.max(in.read(head), 0);
            return uri.getLastPathSegment() + " — read " + read + " bytes";
        } catch (Exception e) {
            return uri + " — " + e.getClass().getSimpleName();
        }
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

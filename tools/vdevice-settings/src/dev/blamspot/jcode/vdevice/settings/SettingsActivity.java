package dev.blamspot.jcode.vdevice.settings;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * The virtual device's Settings app.
 *
 * <p>A device you can only configure from outside itself is a device with a piece missing. The
 * hardware bench and Manage permissions are JCode's screens, in JCode's window, reached by the
 * person driving the IDE — right for JCode, and no use to somebody looking at the device, to an
 * agent driving it through `input tap`, or to an app that sends `ACTION_MANAGE_APPLICATIONS` and
 * expects something to answer.
 *
 * <p>It changes <b>real</b> settings, through {@link DeviceSettings}: the same ones the bench writes,
 * in the same file, with the same effect on a running guest. Nothing here is a mock-up of a settings
 * screen.
 *
 * <h2>What each screen can honestly claim</h2>
 *
 * <p>This is the part worth reading. The device governs different amounts of different hardware, and
 * a settings app that presented them all as identical toggles would be lying about three of them:
 *
 * <ul>
 *   <li><b>Wi-Fi</b> is real and complete. Off genuinely takes the device off the network — an app
 *       sees no active network, no capabilities — while the phone you are working on stays online.
 *   <li><b>Bluetooth</b> governs the <em>declaration</em> only: whether the device says it has
 *       Bluetooth and whether an app is allowed the two permissions. Whether the adapter reports
 *       itself switched on is the phone's business, because the adapter's state does not travel
 *       through anything the container can reach. The screen says so rather than showing a toggle
 *       that appears to turn a radio on.
 *   <li><b>Camera, microphone, location and the motion sensors</b> are the bench's, and are shown
 *       here with the same modes and the same effect.
 *   <li><b>Sound</b> is the phone's. There is no audio stand-in, so the screen says what the device
 *       does control — its microphone — rather than offering volume sliders that move nothing.
 * </ul>
 */
public class SettingsActivity extends Activity {

    private static final int FOREGROUND = Ui.TEXT;
    private static final int MUTED = Ui.MUTED;
    private static final int ACCENT = Ui.ACCENT;
    private static final int WARNING = Ui.WARNING;

    /** Hardware the screens group by, so a person looks for a thing where a phone puts it. */
    private static final String[] NETWORK = {"wifi", "cellular", "bluetooth"};
    private static final String[] PRIVACY = {"camera", "microphone", "location"};
    private static final String[] MOTION = {"accelerometer", "compass", "gyroscope"};

    private DeviceSettings settings;
    private Bundle device;
    private LinearLayout content;
    private TextView heading;
    private TextView subheading;

    /** What Back returns to, innermost last. Empty means the root, and Back leaves. */
    private final List<Runnable> trail = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new DeviceSettings(this);
        setContentView(screen());
        openFor(getIntent() == null ? null : getIntent().getAction());
    }

    /**
     * Opens the screen the intent asked for.
     *
     * <p>An app that sends `ACTION_WIFI_SETTINGS` has already decided what it wants somebody to
     * change, and landing them on the root to find it themselves is the thing those intents exist to
     * avoid.
     */
    private void openFor(String action) {
        if (action == null) {
            showRoot();
            return;
        }
        switch (action) {
            case "android.settings.WIFI_SETTINGS":
            case "android.settings.WIRELESS_SETTINGS":
            case "android.settings.BLUETOOTH_SETTINGS":
                showRoot();
                showHardware("Network", NETWORK);
                break;
            case "android.settings.MANAGE_APPLICATIONS_SETTINGS":
            case "android.settings.APPLICATION_DETAILS_SETTINGS":
                showRoot();
                showApps();
                break;
            case "android.settings.SOUND_SETTINGS":
                showRoot();
                showSound();
                break;
            case "android.settings.INTERNAL_STORAGE_SETTINGS":
                showRoot();
                showStorage();
                break;
            case "android.settings.DEVICE_INFO_SETTINGS":
                showRoot();
                showAbout();
                break;
            default:
                showRoot();
                break;
        }
    }

    private View screen() {
        LinearLayout column = Ui.page(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 6));
        heading = Ui.text(this, "", 24f, FOREGROUND);
        subheading = Ui.text(this, "", 12f, MUTED);
        subheading.setPadding(0, Ui.dp(this, 4), 0, 0);
        header.addView(heading);
        header.addView(subheading);
        column.addView(header, Ui.wrap());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Ui.dp(this, 16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        column.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // A bar rather than a floating button: this app is one column deep and the way out of a
        // screen should sit where the thumb already is.
        LinearLayout actions = new LinearLayout(this);
        actions.setBackgroundColor(Ui.SURFACE);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 16), Ui.dp(this, 10));
        actions.addView(button("Back", ACCENT, new Runnable() {
            @Override
            public void run() {
                onBackPressed();
            }
        }));
        column.addView(actions, Ui.wrap());
        return column;
    }

    /** Which glyph and tint a piece of hardware carries wherever it appears. */
    private int iconFor(String id) {
        switch (id) {
            case "wifi": return R.drawable.ic_wifi;
            case "cellular": return R.drawable.ic_cellular;
            case "bluetooth": return R.drawable.ic_bluetooth;
            case "camera": return R.drawable.ic_camera;
            case "microphone": return R.drawable.ic_mic;
            case "location": return R.drawable.ic_location;
            default: return R.drawable.ic_motion;
        }
    }

    private int tintFor(String id) {
        switch (id) {
            case "wifi": case "cellular": case "bluetooth": return Ui.TINT_NETWORK;
            case "camera": case "microphone": case "location": return Ui.TINT_PRIVACY;
            default: return Ui.TINT_MOTION;
        }
    }

    // ------------------------------------------------------------------------------------ screens

    private void showRoot() {
        trail.clear();
        device = settings.device();
        title("Settings", device == null
            ? "The device's settings are out of reach"
            : name("about/model") + " · Android " + name("about/android"));
        content.removeAllViews();
        if (device == null) {
            content.addView(Ui.note(this, "This app could not reach the container that runs the "
                + "device, so there is nothing here it could honestly show.", WARNING));
            return;
        }

        LinearLayout hardware = Ui.card(this, content, "Hardware");
        hardware.addView(Ui.row(this, R.drawable.ic_wifi, Ui.TINT_NETWORK, "Network",
            summaryOfNetwork(), "Wi-Fi, cellular and Bluetooth",
            () -> showHardware("Network", NETWORK)));
        Ui.divider(this, hardware);
        hardware.addView(Ui.row(this, R.drawable.ic_camera, Ui.TINT_PRIVACY, "Privacy", null,
            "Camera, microphone and location", () -> showHardware("Privacy", PRIVACY)));
        Ui.divider(this, hardware);
        hardware.addView(Ui.row(this, R.drawable.ic_motion, Ui.TINT_MOTION, "Motion sensors", null,
            "Accelerometer, compass and gyroscope", () -> showHardware("Motion sensors", MOTION)));

        LinearLayout system = Ui.card(this, content, "This device");
        system.addView(Ui.row(this, R.drawable.ic_apps, Ui.TINT_APPS, "Apps", null,
            "What is installed, and what each one may use", this::showApps));
        Ui.divider(this, system);
        system.addView(Ui.row(this, R.drawable.ic_sound, Ui.TINT_PRIVACY, "Sound", null,
            "What this device does and does not control", this::showSound));
        Ui.divider(this, system);
        system.addView(Ui.row(this, R.drawable.ic_storage, Ui.TINT_STORAGE, "Storage", null,
            "Two volumes, and which one keeps things", this::showStorage));
        Ui.divider(this, system);
        system.addView(Ui.row(this, R.drawable.ic_info, Ui.TINT_STORAGE, "About", null,
            "What this device says it is", this::showAbout));
    }

    /** A one-line answer to "what is the network doing", for the row that leads to it. */
    private String summaryOfNetwork() {
        boolean wifi = device.getBoolean("hw/wifi/on");
        boolean cellular = device.getBoolean("hw/cellular/on");
        if (wifi) {
            // The network's name, the way a phone's own settings answer this — a row that said
            // "Wi-Fi" when it could say which one is a row with the answer left out.
            String ssid = name("wifi/ssid");
            return ssid == null || ssid.isEmpty() ? "Wi-Fi" : ssid;
        }
        return cellular ? "Cellular" : "Offline";
    }

    /** One group of hardware, each with the modes the container says it has. */
    private void showHardware(final String label, final String[] ids) {
        push(() -> showHardware(label, ids));
        title(label, "The device's own, not the phone's");
        content.removeAllViews();
        LinearLayout card = Ui.card(this, content, null);
        boolean first = true;
        for (final String id : ids) {
            String name = name("hw/" + id + "/label");
            if (name == null) {
                continue;
            }
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            boolean radio = device.getBoolean("hw/" + id + "/radio");
            boolean present = !"Off".equals(name("hw/" + id + "/mode"));
            if (radio && present) {
                final boolean on = device.getBoolean("hw/" + id + "/on");
                card.addView(Ui.row(this, iconFor(id), on ? tintFor(id) : Ui.CHIP, name,
                    on ? "On" : "Off", describeRadio(id, on), () -> {
                        apply("switch/" + id, String.valueOf(!on),
                            name("hw/" + id + "/label") + (on ? " off" : " on"));
                        trail.remove(trail.size() - 1);
                        showHardware(label, ids);
                    }));
            } else if (radio) {
                card.addView(Ui.row(this, iconFor(id), Ui.CHIP, name, "Not fitted",
                    "Add it on JCode's hardware bench", null));
            } else {
                card.addView(Ui.row(this, iconFor(id), tintFor(id), name,
                    name("hw/" + id + "/mode"), name("hw/" + id + "/summary"),
                    () -> showModes(id)));
            }
        }
        if (Arrays.equals(ids, NETWORK)) {
            showWifiNetworks(label, ids);
            showBluetoothDevices(label, ids);
            content.addView(Ui.note(this, "Bluetooth here governs whether the device declares it "
                + "and whether apps may use it. Whether the adapter reports itself switched on is "
                + "the phone's — that state does not travel through anything this device can "
                + "reach.", WARNING));
        }
    }

    /**
     * The networks this device can see, and which one it is on.
     *
     * A Wi-Fi screen whose only content is a switch is a screen that cannot answer the question it
     * exists for. These come from the container, which draws the device a set of neighbours when it
     * starts — so they are the device's own rather than the phone's, and nothing here is a name off
     * a real network the phone happens to be near.
     */
    private void showWifiNetworks(final String label, final String[] ids) {
        if (!device.getBoolean("hw/wifi/on") || "Off".equals(name("hw/wifi/mode"))) {
            return;
        }
        String[] networks = device.getStringArray("wifi/networks");
        if (networks == null || networks.length == 0) {
            return;
        }
        String current = name("wifi/ssid");
        LinearLayout card = Ui.card(this, content, "Networks in range");
        boolean first = true;
        for (String encoded : networks) {
            String[] parts = encoded.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            final String ssid = parts[0];
            boolean secured = Boolean.parseBoolean(parts[2]);
            boolean joined = ssid.equals(current);
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            card.addView(Ui.row(this, R.drawable.ic_wifi, joined ? Ui.TINT_NETWORK : Ui.CHIP, ssid,
                joined ? "Connected" : null,
                signal(parts[1]) + (secured ? " · secured" : " · open"), () -> {
                    apply("wifi/ssid", ssid, "Joined " + ssid);
                    trail.remove(trail.size() - 1);
                    showHardware(label, ids);
                }));
        }
        Ui.divider(this, card);
        card.addView(Ui.row(this, R.drawable.ic_wifi, Ui.CHIP, "Scan again", null,
            "Look for networks in range", () -> {
                apply("wifi/scan", "true", "Scanning");
                trail.remove(trail.size() - 1);
                showHardware(label, ids);
            }));
    }

    /** The same for Bluetooth, where the thing to change is what is paired rather than what is joined. */
    private void showBluetoothDevices(final String label, final String[] ids) {
        if (!device.getBoolean("hw/bluetooth/on") || "Off".equals(name("hw/bluetooth/mode"))) {
            return;
        }
        String[] devices = device.getStringArray("bluetooth/devices");
        if (devices == null || devices.length == 0) {
            return;
        }
        LinearLayout card = Ui.card(this, content, "Devices nearby");
        boolean first = true;
        for (String encoded : devices) {
            String[] parts = encoded.split("\\|");
            if (parts.length != 3) {
                continue;
            }
            final String name = parts[0];
            boolean paired = Boolean.parseBoolean(parts[2]);
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            card.addView(Ui.row(this, R.drawable.ic_bluetooth, paired ? Ui.TINT_NETWORK : Ui.CHIP,
                name, paired ? "Paired" : null, parts[1], () -> {
                    apply("bluetooth/pair", name, paired ? "Unpaired " + name : "Paired " + name);
                    trail.remove(trail.size() - 1);
                    showHardware(label, ids);
                }));
        }
        Ui.divider(this, card);
        card.addView(Ui.row(this, R.drawable.ic_bluetooth, Ui.CHIP, "Scan again", null,
            "Look for devices nearby, keeping what is paired", () -> {
                apply("bluetooth/scan", "true", "Scanning");
                trail.remove(trail.size() - 1);
                showHardware(label, ids);
            }));
    }

    /** A signal level as a phone words it, rather than as the 0–4 the platform counts in. */
    private String signal(String level) {
        switch (level) {
            case "4": return "Excellent";
            case "3": return "Good";
            case "2": return "Fair";
            case "1": return "Weak";
            default: return "Very weak";
        }
    }

    /**
     * What one piece of hardware is wired to — reported, not chosen.
     *
     * The two switches are not both this app's to throw. Whether the device *has* a camera is what it
     * was built with, and an app is told that once: the platform caches the answer for the life of a
     * process and gives nobody a way to invalidate it, so changing it restarts the device — which is
     * not something to do from a screen running on that device. It is set on JCode's hardware bench.
     * Whether hardware the device has is switched *on* is this app's, and stays a live switch.
     */
    private void showModes(final String id) {
        push(() -> showModes(id));
        title(name("hw/" + id + "/label"), name("hw/" + id + "/summary"));
        content.removeAllViews();
        String current = name("hw/" + id + "/mode");
        String[] modes = device.getStringArray("hw/" + id + "/modes");
        LinearLayout card = Ui.card(this, content, "Wired to");
        boolean first = true;
        for (final String mode : modes == null ? new String[0] : modes) {
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            boolean chosen = mode.equals(current);
            card.addView(Ui.row(this, iconFor(id), chosen ? tintFor(id) : Ui.CHIP, mode,
                chosen ? "✓" : null, describeMode(id, mode), null));
        }
        content.addView(Ui.note(this, "What the device is made of is set in JCode, on the Device "
            + "hardware tab. Changing it restarts the device, because an app is told what hardware "
            + "there is when it starts and never again.", MUTED));
    }

    /** What being on or off actually means for each radio, which differs enough to be worth saying. */
    private String describeRadio(String id, boolean on) {
        if ("wifi".equals(id)) {
            return on ? "On the network, carried by the phone's connection"
                : "Off the network — this is how to see what an app does offline";
        }
        if ("cellular".equals(id)) {
            return on ? "A mobile connection, reported to apps as metered"
                : "No mobile connection";
        }
        return on ? "The adapter is declared to apps" : "No Bluetooth offered to apps";
    }


    private String describeMode(String id, String mode) {
        if ("Off".equals(mode)) {
            return "wifi".equals(id)
                ? "The device has no network at all"
                : "Not declared, and refused to every app";
        }
        if ("Simulated".equals(mode)) {
            return "wifi".equals(id)
                ? "On the network, carried by the phone's connection"
                : "The device's own, set on the hardware bench";
        }
        return "The phone's own";
    }

    private void showApps() {
        push(this::showApps);
        Bundle apps = settings.apps();
        String[] packages = apps == null ? null : apps.getStringArray("packages");
        title("Apps", packages == null ? "None installed" : packages.length + " installed");
        content.removeAllViews();
        LinearLayout card = Ui.card(this, content, null);
        boolean first = true;
        for (final String packageName : packages == null ? new String[0] : packages) {
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            String label = apps.getString("app/" + packageName + "/label", packageName);
            boolean system = apps.getBoolean("app/" + packageName + "/system");
            card.addView(Ui.row(this, R.drawable.ic_apps,
                system ? Ui.TINT_STORAGE : Ui.TINT_APPS, label,
                system ? "System" : null, packageName, () -> showApp(packageName)));
        }
    }

    /** One app's permissions, each with the rule the device applies to it. */
    private void showApp(final String packageName) {
        push(() -> showApp(packageName));
        final Bundle app = settings.app(packageName);
        String[] permissions = app == null ? null : app.getStringArray("permissions");
        title(packageName, permissions == null || permissions.length == 0
            ? "Declares no permissions"
            : permissions.length + " permissions declared");
        content.removeAllViews();
        if (permissions == null) {
            return;
        }
        LinearLayout card = Ui.card(this, content, "Permissions");
        boolean first = true;
        for (final String permission : permissions) {
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            final String rule = app.getString("perm/" + permission + "/rule", "Allow");
            String label = app.getString("perm/" + permission + "/label", permission);
            boolean runtime = app.getBoolean("perm/" + permission + "/runtime");
            card.addView(Ui.row(this, R.drawable.ic_info, tintForRule(rule), label, rule,
                runtime ? "Asked for at run time" : "Granted at install", () -> {
                    apply("perm/" + packageName + "/" + permission, next(rule),
                        permission.substring(permission.lastIndexOf('.') + 1) + " → " + next(rule));
                    trail.remove(trail.size() - 1);
                    showApp(packageName);
                }));
        }
        content.addView(Ui.note(this, "Tap a permission to cycle Allow → Ask → Deny. Undeclared "
            + "permissions are refused whatever the rule says, exactly as the platform refuses "
            + "them.", MUTED));
    }

    /** Green for allowed, amber for undecided, grey for refused — readable before the word is. */
    private int tintForRule(String rule) {
        if ("Allow".equals(rule)) {
            return 0xFF15803D;
        }
        return "Ask".equals(rule) ? 0xFFB45309 : Ui.CHIP;
    }

    /** Allow → Ask → Deny → Allow, which is one tap per change rather than a dialog per change. */
    private String next(String rule) {
        if ("Allow".equals(rule)) {
            return "Ask";
        }
        return "Ask".equals(rule) ? "Deny" : "Allow";
    }

    private void showSound() {
        push(this::showSound);
        title("Sound", "What this device controls, and what it does not");
        content.removeAllViews();
        LinearLayout card = Ui.card(this, content, null);
        String mode = name("hw/microphone/mode");
        card.addView(Ui.row(this, R.drawable.ic_mic, Ui.TINT_PRIVACY, "Microphone", mode,
            name("hw/microphone/summary"), () -> showModes("microphone")));
        content.addView(Ui.note(this, "Output volume is the phone's. This device has no audio "
            + "stand-in, so there is nothing here that could change what an app hears — and a "
            + "slider that moved nothing would be worse than saying so.", MUTED));
    }

    private void showStorage() {
        push(this::showStorage);
        title("Storage", "Two volumes, with different lifetimes");
        content.removeAllViews();
        LinearLayout card = Ui.card(this, content, null);
        String[] volumes = device.getStringArray("volumes");
        boolean first = true;
        for (String volume : volumes == null ? new String[0] : volumes) {
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            String path = name("vol/" + volume + "/path");
            long used = device.getLong("vol/" + volume + "/used");
            long free = device.getLong("vol/" + volume + "/free");
            boolean keeps = device.getBoolean("vol/" + volume + "/keeps");
            card.addView(Ui.row(this, R.drawable.ic_storage,
                keeps ? Ui.TINT_MOTION : Ui.TINT_STORAGE,
                name("vol/" + volume + "/label"), bytes(used),
                path + " · " + bytes(free) + " free · "
                    + (keeps ? "kept in your workspace" : "emptied when JCode starts"), null));
        }
        content.addView(Ui.note(this, "Anything an app should still have tomorrow belongs on the "
            + "external volume — it is a folder in your workspace, so it is also visible in the "
            + "editor and in the Linux environment at /workspace/vDevice_ExtStorage.", MUTED));
    }

    private void showAbout() {
        push(this::showAbout);
        title("About", name("about/model"));
        content.removeAllViews();
        LinearLayout card = Ui.card(this, content, null);
        card.addView(Ui.row(this, R.drawable.ic_info, Ui.TINT_STORAGE, "Model",
            name("about/model"), "What this device reports itself as", null));
        Ui.divider(this, card);
        card.addView(Ui.row(this, R.drawable.ic_info, Ui.TINT_STORAGE, "Android version",
            name("about/android"), "API " + device.getInt("about/sdk"), null));
        Ui.divider(this, card);
        card.addView(Ui.row(this, R.drawable.ic_info, Ui.TINT_STORAGE, "Running on",
            null, name("about/host"), null));
        content.addView(Ui.note(this, "This device shares the phone's Android version because it "
            + "shares its runtime — it is a container, not an emulator. What it does not share is "
            + "its storage, its apps, its permissions or its sensors.", MUTED));
    }

    // ------------------------------------------------------------------------------------ plumbing

    private void apply(String key, String value, String said) {
        if (settings.set(key, value)) {
            device = settings.device();
            Toast.makeText(this, said, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "The device refused that change.", Toast.LENGTH_LONG).show();
        }
    }

    private String name(String key) {
        return device == null ? null : device.getString(key);
    }

    private void title(String title, String detail) {
        heading.setText(title);
        subheading.setText(detail == null ? "" : detail);
        subheading.setVisibility(detail == null || detail.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void push(Runnable screen) {
        trail.add(screen);
    }

    @Override
    public void onBackPressed() {
        if (trail.isEmpty()) {
            finish();
            return;
        }
        trail.remove(trail.size() - 1);
        if (trail.isEmpty()) {
            showRoot();
        } else {
            Runnable previous = trail.remove(trail.size() - 1);
            previous.run();
        }
    }

    private Button button(String label, int colour, final Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(colour);
        button.setBackground(Ui.ripple());
        button.setContentDescription(label);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });
        return button;
    }

    private static String bytes(long size) {
        if (size < 1024) {
            return size + " B";
        }
        String[] units = {"KB", "MB", "GB"};
        double value = size;
        int unit = -1;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

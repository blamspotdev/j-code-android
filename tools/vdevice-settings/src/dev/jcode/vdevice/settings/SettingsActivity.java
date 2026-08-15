package dev.jcode.vdevice.settings;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.TypedValue;
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

    private static final int FOREGROUND = 0xFFE6E8EF;
    private static final int MUTED = 0xFF9AA0B0;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int WARNING = 0xFFE6A23C;
    private static final int BACKGROUND = 0xFF101418;

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
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKGROUND);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(36, 32, 36, 20);
        heading = text(20f, FOREGROUND);
        subheading = text(11f, MUTED);
        header.addView(heading);
        header.addView(subheading);
        column.addView(header, wrap());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        column.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(16, 12, 16, 20);
        actions.addView(button("Back", MUTED, new Runnable() {
            @Override
            public void run() {
                onBackPressed();
            }
        }));
        column.addView(actions, wrap());
        return column;
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
            content.addView(note("This app could not reach the container that runs the device, so "
                + "there is nothing here it could honestly show.", WARNING));
            return;
        }
        add("Network", "Wi-Fi and Bluetooth", new Runnable() {
            @Override
            public void run() {
                showHardware("Network", NETWORK);
            }
        });
        add("Privacy", "Camera, microphone and location", new Runnable() {
            @Override
            public void run() {
                showHardware("Privacy", PRIVACY);
            }
        });
        add("Motion sensors", "Accelerometer, compass and gyroscope", new Runnable() {
            @Override
            public void run() {
                showHardware("Motion sensors", MOTION);
            }
        });
        add("Apps", "What is installed, and what each one may use", new Runnable() {
            @Override
            public void run() {
                showApps();
            }
        });
        add("Sound", "What this device does and does not control", new Runnable() {
            @Override
            public void run() {
                showSound();
            }
        });
        add("Storage", "Two volumes, and which one keeps things", new Runnable() {
            @Override
            public void run() {
                showStorage();
            }
        });
        add("About", "What this device says it is", new Runnable() {
            @Override
            public void run() {
                showAbout();
            }
        });
    }

    /** One group of hardware, each with the modes the container says it has. */
    private void showHardware(final String label, final String[] ids) {
        push(new Runnable() {
            @Override
            public void run() {
                showHardware(label, ids);
            }
        });
        title(label, "These are the device's own, not the phone's");
        content.removeAllViews();
        for (final String id : ids) {
            String name = name("hw/" + id + "/label");
            if (name == null) {
                continue;
            }
            // A radio the device has gets the switch a phone's Settings gives it — on or off, one
            // tap, no submenu. Whether the device has it at all is the bench's question, and the
            // row says so instead of offering a switch that could not do anything.
            boolean radio = device.getBoolean("hw/" + id + "/radio");
            boolean present = !"Off".equals(name("hw/" + id + "/mode"));
            if (radio && present) {
                final boolean on = device.getBoolean("hw/" + id + "/on");
                content.addView(row(name, on ? "On" : "Off", describeRadio(id, on), new Runnable() {
                    @Override
                    public void run() {
                        apply("switch/" + id, String.valueOf(!on),
                            name("hw/" + id + "/label") + (on ? " off" : " on"));
                        showHardware(label, ids);
                        trail.remove(trail.size() - 1);
                    }
                }));
                continue;
            }
            if (radio) {
                content.addView(row(name, "Not fitted",
                    "This device was built without it — add it on JCode's hardware bench", null));
                continue;
            }
            content.addView(row(name, name("hw/" + id + "/mode"), name("hw/" + id + "/summary"),
                new Runnable() {
                    @Override
                    public void run() {
                        showModes(id);
                    }
                }));
        }
        if (Arrays.equals(ids, NETWORK)) {
            content.addView(note("Bluetooth here governs whether the device declares it and whether "
                + "apps may use it. Whether the adapter reports itself switched on is the phone's — "
                + "that state does not travel through anything this device can reach.", WARNING));
        }
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

    /** The modes one piece of hardware has, and which is set. */
    private void showModes(final String id) {
        push(new Runnable() {
            @Override
            public void run() {
                showModes(id);
            }
        });
        final String label = name("hw/" + id + "/label");
        title(label, name("hw/" + id + "/summary"));
        content.removeAllViews();
        String current = name("hw/" + id + "/mode");
        String[] modes = device.getStringArray("hw/" + id + "/modes");
        for (final String mode : modes == null ? new String[0] : modes) {
            content.addView(row(mode.equals(current) ? mode + "  ✓" : mode,
                null, describeMode(id, mode), new Runnable() {
                    @Override
                    public void run() {
                        apply("hw/" + id, mode, label + " is now " + mode);
                        showModes(id);
                    }
                }));
        }
        if ("bluetooth".equals(id)) {
            content.addView(note("This switch governs whether the device declares Bluetooth and "
                + "whether apps may use it. Whether the adapter reports itself switched on is the "
                + "phone's — the adapter's state does not travel through anything this device can "
                + "reach.", WARNING));
        }
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
        push(new Runnable() {
            @Override
            public void run() {
                showApps();
            }
        });
        Bundle apps = settings.apps();
        String[] packages = apps == null ? null : apps.getStringArray("packages");
        title("Apps", packages == null ? "None installed" : packages.length + " installed");
        content.removeAllViews();
        for (final String packageName : packages == null ? new String[0] : packages) {
            String label = apps.getString("app/" + packageName + "/label", packageName);
            boolean system = apps.getBoolean("app/" + packageName + "/system");
            content.addView(row(label, system ? "System" : null, packageName, new Runnable() {
                @Override
                public void run() {
                    showApp(packageName);
                }
            }));
        }
    }

    /** One app's permissions, each with the rule the device applies to it. */
    private void showApp(final String packageName) {
        push(new Runnable() {
            @Override
            public void run() {
                showApp(packageName);
            }
        });
        final Bundle app = settings.app(packageName);
        String[] permissions = app == null ? null : app.getStringArray("permissions");
        title(packageName, permissions == null || permissions.length == 0
            ? "Declares no permissions"
            : permissions.length + " permissions declared");
        content.removeAllViews();
        if (permissions == null) {
            return;
        }
        for (final String permission : permissions) {
            final String rule = app.getString("perm/" + permission + "/rule", "Allow");
            String label = app.getString("perm/" + permission + "/label", permission);
            boolean runtime = app.getBoolean("perm/" + permission + "/runtime");
            content.addView(row(label, rule, runtime ? "Asked for at run time" : "Granted at install",
                new Runnable() {
                    @Override
                    public void run() {
                        apply("perm/" + packageName + "/" + permission, next(rule),
                            permission.substring(permission.lastIndexOf('.') + 1) + " → " + next(rule));
                        showApp(packageName);
                    }
                }));
        }
        content.addView(note("Tap a permission to cycle Allow → Ask → Deny. Undeclared permissions "
            + "are refused whatever the rule says, exactly as the platform refuses them.", MUTED));
    }

    /** Allow → Ask → Deny → Allow, which is one tap per change rather than a dialog per change. */
    private String next(String rule) {
        if ("Allow".equals(rule)) {
            return "Ask";
        }
        return "Ask".equals(rule) ? "Deny" : "Allow";
    }

    private void showSound() {
        push(new Runnable() {
            @Override
            public void run() {
                showSound();
            }
        });
        title("Sound", "What this device controls, and what it does not");
        content.removeAllViews();
        String mode = name("hw/microphone/mode");
        content.addView(row("Microphone", mode, name("hw/microphone/summary"), new Runnable() {
            @Override
            public void run() {
                showModes("microphone");
            }
        }));
        content.addView(note("Output volume is the phone's. This device has no audio stand-in, so "
            + "there is nothing here that could change what an app hears — and a slider that moved "
            + "nothing would be worse than saying so.", MUTED));
    }

    private void showStorage() {
        push(new Runnable() {
            @Override
            public void run() {
                showStorage();
            }
        });
        title("Storage", "Two volumes, with different lifetimes");
        content.removeAllViews();
        String[] volumes = device.getStringArray("volumes");
        for (String volume : volumes == null ? new String[0] : volumes) {
            String path = name("vol/" + volume + "/path");
            long used = device.getLong("vol/" + volume + "/used");
            long free = device.getLong("vol/" + volume + "/free");
            boolean keeps = device.getBoolean("vol/" + volume + "/keeps");
            content.addView(row(
                name("vol/" + volume + "/label"),
                bytes(used) + " used",
                path + " · " + bytes(free) + " free · "
                    + (keeps ? "kept in your workspace" : "emptied when JCode starts"),
                null));
        }
        content.addView(note("Anything an app should still have tomorrow belongs on the external "
            + "volume — it is a folder in your workspace, so it is also visible in the editor and "
            + "in the Linux environment at /workspace/vDevice_ExtStorage.", MUTED));
    }

    private void showAbout() {
        push(new Runnable() {
            @Override
            public void run() {
                showAbout();
            }
        });
        title("About", name("about/model"));
        content.removeAllViews();
        content.addView(row("Model", name("about/model"), "What this device reports itself as", null));
        content.addView(row("Android version", name("about/android"),
            "API " + device.getInt("about/sdk"), null));
        content.addView(row("Running on", name("about/host"),
            "The phone this device is a guest of", null));
        content.addView(note("This device shares the phone's Android version because it shares its "
            + "runtime — it is a container, not an emulator. What it does not share is its storage, "
            + "its apps, its permissions or its sensors.", MUTED));
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

    private void add(String label, String detail, Runnable onClick) {
        content.addView(row(label, null, detail, onClick));
    }

    /** One line of a settings list: what it is, what it is set to, and what that means. */
    private View row(String label, String value, String detail, final Runnable onClick) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(36, 18, 36, 18);
        column.setContentDescription(label);
        if (onClick != null) {
            column.setClickable(true);
            column.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    onClick.run();
                }
            });
        }

        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = text(15f, FOREGROUND);
        name.setText(label);
        line.addView(name, new LinearLayout.LayoutParams(0,
            ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (value != null) {
            TextView setting = text(13f, ACCENT);
            setting.setText(value);
            line.addView(setting);
        }
        column.addView(line, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        if (detail != null) {
            TextView note = text(11f, MUTED);
            note.setText(detail);
            column.addView(note);
        }
        return column;
    }

    private View note(String message, int colour) {
        TextView view = text(11f, colour);
        view.setText(message);
        view.setPadding(36, 20, 36, 24);
        return view;
    }

    private TextView text(float size, int colour) {
        TextView view = new TextView(this);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        return view;
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

package dev.jcode.vdevice.files;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * The virtual device's file explorer, which is also its file and folder picker.
 *
 * <p>Before this the device's storage could only be seen from outside it, with `adb ls`, and a
 * document request was answered by a screen the container drew. Neither is something
 * `PackageManager` can find, so an app that calls `resolveActivity` before offering "attach a file"
 * found nothing and offered nothing. This is an ordinary app with the ordinary filters, so the
 * question has an answer.
 *
 * <h2>What it is asked to do</h2>
 *
 * <table>
 *   <tr><th>Started by</th><th>Mode</th></tr>
 *   <tr><td>The launcher</td><td>Browse. Tapping a file says what it is; nothing is returned</td></tr>
 *   <tr><td>`OPEN_DOCUMENT`, `GET_CONTENT`</td><td>Pick one file</td></tr>
 *   <tr><td>`CREATE_DOCUMENT`</td><td>Choose a folder and type a name</td></tr>
 *   <tr><td>`OPEN_DOCUMENT_TREE`</td><td>Pick the folder you are looking at</td></tr>
 * </table>
 *
 * <h2>How an answer gets back</h2>
 *
 * <p>The device path is returned under {@link #EXTRA_DEVICE_PATH} and the container turns it into
 * the `content://` URI the requesting app receives. That split is deliberate: the URI belongs to
 * JCode's own documents provider, whose authority and document-id encoding are the container's
 * business, and an app that guessed at them would be coupled to a format it cannot see change. What
 * this app knows is which file the person chose, which is the part it is qualified to answer.
 */
public class FilesActivity extends Activity {

    private static final String TAG = "VFILES";

    /**
     * The device path this app chose, read by the container on the way back to the requester.
     *
     * <p>Public contract between the device's own picker and the device's own container.
     */
    public static final String EXTRA_DEVICE_PATH = "dev.jcode.vdevice.DEVICE_PATH";

    private static final int MODE_BROWSE = 0;
    private static final int MODE_PICK_FILE = 1;
    private static final int MODE_PICK_FOLDER = 2;
    private static final int MODE_CREATE = 3;

    private static final int FOREGROUND = 0xFFE6E8EF;
    private static final int MUTED = 0xFF9AA0B0;
    private static final int ACCENT = 0xFF8AB4F8;
    private static final int BACKGROUND = 0xFF101418;

    private int mode = MODE_BROWSE;
    private List<DeviceStorage.Volume> volumes;
    /** Null while the volume list is on screen, which is the top of this app's tree. */
    private File root;
    private File current;

    private TextView pathLabel;
    private LinearLayout listing;
    private EditText nameField;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mode = modeFor(getIntent() == null ? null : getIntent().getAction());
        volumes = DeviceStorage.volumes(this);
        setContentView(screen());
        // Straight into the only volume when there is only one, so a device with a single store
        // does not make somebody tap through a list of one.
        if (volumes.size() == 1) {
            root = volumes.get(0).directory;
            show(root);
        } else {
            showVolumes();
        }
    }

    private int modeFor(String action) {
        if (Intent.ACTION_OPEN_DOCUMENT.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {
            return MODE_PICK_FILE;
        }
        if (Intent.ACTION_OPEN_DOCUMENT_TREE.equals(action)) {
            return MODE_PICK_FOLDER;
        }
        if (Intent.ACTION_CREATE_DOCUMENT.equals(action)) {
            return MODE_CREATE;
        }
        return MODE_BROWSE;
    }

    private View screen() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKGROUND);

        column.addView(header(), wrap());

        listing = new LinearLayout(this);
        listing.setOrientation(LinearLayout.VERTICAL);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(listing);
        column.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (mode == MODE_CREATE) {
            column.addView(nameRow(), wrap());
        }
        column.addView(actions(), wrap());
        return column;
    }

    private View header() {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(36, 32, 36, 20);

        TextView title = new TextView(this);
        title.setText(titleFor());
        title.setTextColor(FOREGROUND);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);

        pathLabel = new TextView(this);
        pathLabel.setTextColor(MUTED);
        pathLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);

        column.addView(title);
        column.addView(pathLabel);
        return column;
    }

    private String titleFor() {
        switch (mode) {
            case MODE_PICK_FILE:
                return caller() + " wants a file";
            case MODE_PICK_FOLDER:
                return caller() + " wants a folder";
            case MODE_CREATE:
                return caller() + " wants to save a file";
            default:
                return "Files";
        }
    }

    private String caller() {
        String calling = getCallingPackage();
        return calling == null ? "An app" : calling;
    }

    private View nameRow() {
        nameField = new EditText(this);
        nameField.setTextColor(FOREGROUND);
        nameField.setHint("File name");
        nameField.setHintTextColor(MUTED);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT);
        nameField.setContentDescription("File name");
        nameField.setText(suggestedName());
        LinearLayout row = new LinearLayout(this);
        row.setPadding(36, 0, 36, 0);
        row.addView(nameField, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private String suggestedName() {
        String title = getIntent() == null ? null : getIntent().getStringExtra(Intent.EXTRA_TITLE);
        return title == null ? "" : title;
    }

    private View actions() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(16, 12, 16, 20);
        row.addView(button(mode == MODE_BROWSE ? "Close" : "Cancel", MUTED, new Runnable() {
            @Override
            public void run() {
                cancel();
            }
        }));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (mode == MODE_PICK_FOLDER) {
            row.addView(button("Use this folder", ACCENT, new Runnable() {
                @Override
                public void run() {
                    answer(current);
                }
            }));
        } else if (mode == MODE_CREATE) {
            row.addView(button("Save here", ACCENT, new Runnable() {
                @Override
                public void run() {
                    create();
                }
            }));
        }
        return row;
    }

    /** Lists {@code directory}, newest layout first: up, then folders, then files, each by name. */
    /**
     * The top of the tree: which store to look in.
     *
     * <p>The device has two and they behave differently — one is emptied every time JCode starts and
     * the other is a folder in the workspace that is still there tomorrow. Naming that difference
     * here is the point; a file explorer that hides which store you are writing into is one you
     * cannot trust with the answer.
     */
    private void showVolumes() {
        current = null;
        root = null;
        pathLabel.setText("This device");
        listing.removeAllViews();
        for (final DeviceStorage.Volume volume : volumes) {
            listing.addView(row(volume.label, describeVolume(volume), true, new Runnable() {
                @Override
                public void run() {
                    root = volume.directory;
                    show(root);
                }
            }));
        }
    }

    private String describeVolume(DeviceStorage.Volume volume) {
        return volume.deviceRoot + (volume.deviceRoot.equals("/sdcard")
            ? " — emptied when JCode starts"
            : " — kept in your workspace");
    }

    private void show(File directory) {
        current = directory;
        pathLabel.setText(DeviceStorage.display(volumes, directory));
        listing.removeAllViews();

        // Up from a volume's own root goes to the volume list, not nowhere.
        listing.addView(row("..", directory.equals(root) ? "All storage" : "Up one folder", true,
            new Runnable() {
                @Override
                public void run() {
                    up();
                }
            }));
        for (final File entry : sorted(directory)) {
            final boolean isDirectory = entry.isDirectory();
            listing.addView(row(entry.getName(), describe(entry), isDirectory, new Runnable() {
                @Override
                public void run() {
                    if (isDirectory) {
                        show(entry);
                    } else {
                        chose(entry);
                    }
                }
            }));
        }
        if (listing.getChildCount() == 0) {
            TextView empty = new TextView(this);
            empty.setText("This folder is empty.");
            empty.setTextColor(MUTED);
            empty.setPadding(36, 24, 36, 24);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            listing.addView(empty);
        }
    }

    private List<File> sorted(File directory) {
        File[] entries = directory.listFiles();
        List<File> all = new ArrayList<>();
        if (entries != null) {
            all.addAll(Arrays.asList(entries));
        }
        all.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return all;
    }

    private String describe(File entry) {
        if (entry.isDirectory()) {
            String[] names = entry.list();
            int count = names == null ? 0 : names.length;
            return count == 1 ? "1 item" : count + " items";
        }
        return bytes(entry.length());
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

    private View row(String name, String detail, boolean isDirectory, final Runnable onClick) {
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(36, 18, 36, 18);
        column.setClickable(true);
        column.setContentDescription(name);
        column.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                onClick.run();
            }
        });

        TextView label = new TextView(this);
        label.setText(isDirectory ? name + "/" : name);
        label.setTextColor(FOREGROUND);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);

        TextView note = new TextView(this);
        note.setText(detail);
        note.setTextColor(MUTED);
        note.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);

        column.addView(label);
        column.addView(note);
        return column;
    }

    /** A file was tapped: chosen when somebody is waiting for one, described when nobody is. */
    private void chose(File file) {
        if (mode == MODE_PICK_FILE) {
            answer(file);
            return;
        }
        if (mode == MODE_CREATE && nameField != null) {
            nameField.setText(file.getName());
            return;
        }
        Toast.makeText(this,
            DeviceStorage.display(volumes, file) + " — " + bytes(file.length()),
            Toast.LENGTH_SHORT).show();
    }

    /**
     * Creates the named file in the folder on screen and answers with it.
     *
     * <p>The file is created empty rather than left to the caller: `CREATE_DOCUMENT` promises a
     * document that exists, and an app that opens the returned URI for writing should not have to
     * discover that nothing is there.
     */
    private void create() {
        String name = nameField == null ? "" : nameField.getText().toString().trim();
        if (name.isEmpty()) {
            Toast.makeText(this, "Give the file a name first.", Toast.LENGTH_SHORT).show();
            return;
        }
        if (name.contains("/")) {
            Toast.makeText(this, "A file name cannot contain a slash.", Toast.LENGTH_SHORT).show();
            return;
        }
        File file = new File(current, name);
        try {
            if (!file.exists() && !file.createNewFile()) {
                Toast.makeText(this, "Could not create " + name, Toast.LENGTH_LONG).show();
                return;
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot create " + file, e);
            Toast.makeText(this, "Could not create " + name, Toast.LENGTH_LONG).show();
            return;
        }
        answer(file);
    }

    private void answer(File chosen) {
        String path = DeviceStorage.display(volumes, chosen);
        Log.i(TAG, "chose " + path);
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_DEVICE_PATH, path));
        finish();
    }

    private void cancel() {
        setResult(RESULT_CANCELED);
        finish();
    }

    /** Back walks up the tree before it leaves, which is what a file explorer's Back does. */
    @Override
    public void onBackPressed() {
        if (current != null) {
            up();
            return;
        }
        cancel();
    }

    /** One step towards the volume list, and out of the app once it is showing. */
    private void up() {
        if (current == null) {
            cancel();
            return;
        }
        if (current.equals(root)) {
            if (volumes.size() == 1) {
                cancel();
            } else {
                showVolumes();
            }
            return;
        }
        File parent = current.getParentFile();
        show(parent == null ? root : parent);
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

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

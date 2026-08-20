package dev.blamspot.jcode.vdevice.files;

import android.app.AlertDialog;
import android.content.Intent;
import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
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
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
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
 *   <tr><td>The launcher</td><td>Browse, and manage: new folder, rename, delete</td></tr>
 *   <tr><td>`OPEN_DOCUMENT`, `GET_CONTENT`</td><td>Pick one file</td></tr>
 *   <tr><td>`CREATE_DOCUMENT`</td><td>Choose a folder and type a name</td></tr>
 *   <tr><td>`OPEN_DOCUMENT_TREE`</td><td>Pick the folder you are looking at</td></tr>
 * </table>
 *
 * <p>The tools are the part that was missing. A file explorer that can only *look* is a listing, and
 * everything a person actually opens one to do — make a folder to put something in, rename the file
 * an app just wrote, delete the twenty test captures — meant leaving the device for a terminal.
 * Long-press a row for rename and delete; the toolbar makes folders and sorts.
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
    public static final String EXTRA_DEVICE_PATH = "dev.blamspot.jcode.vdevice.DEVICE_PATH";

    private static final int MODE_BROWSE = 0;
    private static final int MODE_PICK_FILE = 1;
    private static final int MODE_PICK_FOLDER = 2;
    private static final int MODE_CREATE = 3;

    /** How the listing is ordered. Folders always lead, whichever of these is chosen. */
    private static final int SORT_NAME = 0;
    private static final int SORT_SIZE = 1;
    private static final int SORT_RECENT = 2;
    private static final String[] SORT_LABELS = {"Name", "Largest", "Newest"};

    private int mode = MODE_BROWSE;
    private int sort = SORT_NAME;
    private List<DeviceStorage.Volume> volumes;

    /** Null while the volume list is on screen, which is the top of this app's tree. */
    private File root;
    private File current;

    private TextView heading;
    private TextView pathLabel;
    private LinearLayout content;
    private LinearLayout toolbar;
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

    // ------------------------------------------------------------------------------------- frame

    private View screen() {
        LinearLayout column = Ui.page(this);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(Ui.dp(this, 22), Ui.dp(this, 26), Ui.dp(this, 22), Ui.dp(this, 6));
        heading = Ui.text(this, titleFor(), 24f, Ui.TEXT);
        pathLabel = Ui.text(this, "", 12f, Ui.MUTED);
        pathLabel.setPadding(0, Ui.dp(this, 4), 0, 0);
        header.addView(heading);
        header.addView(pathLabel);
        column.addView(header, Ui.wrap());

        toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setPadding(Ui.dp(this, 18), Ui.dp(this, 6), Ui.dp(this, 18), Ui.dp(this, 4));
        column.addView(toolbar, Ui.wrap());

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, Ui.dp(this, 16));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        column.addView(scroll, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        if (mode == MODE_CREATE) {
            column.addView(nameRow(), Ui.wrap());
        }
        column.addView(actions(), Ui.wrap());
        return column;
    }

    private String titleFor() {
        switch (mode) {
            case MODE_PICK_FILE:
                return callerLabel() + " wants a file";
            case MODE_PICK_FOLDER:
                return callerLabel() + " wants a folder";
            case MODE_CREATE:
                return callerLabel() + " wants to save";
            default:
                return "Files";
        }
    }

    /** The label a person recognises, falling back to the package name. */
    private String callerLabel() {
        String calling = getCallingPackage();
        if (calling == null) {
            return "An app";
        }
        try {
            return getPackageManager().getApplicationLabel(
                getPackageManager().getApplicationInfo(calling, 0)).toString();
        } catch (Exception e) {
            return calling;
        }
    }

    private View nameRow() {
        nameField = new EditText(this);
        nameField.setTextColor(Ui.TEXT);
        nameField.setHint("File name");
        nameField.setHintTextColor(Ui.MUTED);
        nameField.setSingleLine(true);
        nameField.setInputType(InputType.TYPE_CLASS_TEXT);
        nameField.setContentDescription("File name");
        nameField.setBackground(Ui.rounded(Ui.CHIP, Ui.dp(this, 12)));
        nameField.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        String title = getIntent() == null ? null : getIntent().getStringExtra(Intent.EXTRA_TITLE);
        nameField.setText(title == null ? "" : title);

        LinearLayout row = new LinearLayout(this);
        row.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 18), Ui.dp(this, 6));
        row.addView(nameField, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private View actions() {
        LinearLayout row = new LinearLayout(this);
        row.setBackgroundColor(Ui.SURFACE);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(this, 8), Ui.dp(this, 6), Ui.dp(this, 12), Ui.dp(this, 10));
        row.addView(button(mode == MODE_BROWSE ? "Close" : "Cancel", Ui.MUTED, this::cancel));
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        if (mode == MODE_PICK_FOLDER) {
            row.addView(button("Use this folder", Ui.ACCENT, () -> answer(current)));
        } else if (mode == MODE_CREATE) {
            row.addView(button("Save here", Ui.ACCENT, this::create));
        }
        return row;
    }

    // ----------------------------------------------------------------------------------- screens

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
        toolbar.removeAllViews();
        content.removeAllViews();
        LinearLayout card = Ui.card(this, content, "Storage");
        boolean first = true;
        for (final DeviceStorage.Volume volume : volumes) {
            if (!first) {
                Ui.divider(this, card);
            }
            first = false;
            boolean keeps = !volume.deviceRoot.equals("/sdcard");
            card.addView(Ui.row(this,
                keeps ? R.drawable.ic_external : R.drawable.ic_internal,
                keeps ? Ui.TINT_EXTERNAL : Ui.TINT_INTERNAL,
                volume.label,
                null,
                volume.deviceRoot + (keeps ? " · kept in your workspace" : " · emptied when JCode starts"),
                () -> {
                    root = volume.directory;
                    show(root);
                }));
        }
    }

    /** Lists {@code directory}: up first, then folders, then files. */
    private void show(File directory) {
        current = directory;
        pathLabel.setText(DeviceStorage.display(volumes, directory));
        buildToolbar();
        content.removeAllViews();

        LinearLayout card = Ui.card(this, content, null);
        card.addView(Ui.row(this, R.drawable.ic_up, Ui.CHIP, "..",
            null, directory.equals(root) ? "All storage" : "Up one folder", this::up));

        List<File> entries = sorted(directory);
        for (final File entry : entries) {
            Ui.divider(this, card);
            final boolean isDirectory = entry.isDirectory();
            View row = Ui.row(this, iconFor(entry), tintFor(entry), entry.getName(),
                isDirectory ? null : bytes(entry.length()), describe(entry),
                () -> {
                    if (isDirectory) {
                        show(entry);
                    } else {
                        chose(entry);
                    }
                });
            // Long-press is where a file explorer keeps its verbs, which is where a person looks.
            row.setOnLongClickListener(v -> {
                manage(entry);
                return true;
            });
            card.addView(row);
        }
        if (entries.isEmpty()) {
            content.addView(Ui.note(this, "This folder is empty.", Ui.MUTED));
        }
    }

    /** New folder, and the sort order. Two things, because a third would be a menu. */
    private void buildToolbar() {
        toolbar.removeAllViews();
        toolbar.addView(pill(R.drawable.ic_newfolder, "New folder", this::newFolder));
        toolbar.addView(pill(0, "Sort: " + SORT_LABELS[sort], () -> {
            sort = (sort + 1) % SORT_LABELS.length;
            show(current);
        }));
    }

    private View pill(int iconRes, String label, Runnable onClick) {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackground(Ui.rounded(Ui.CHIP, Ui.dp(this, 20)));
        pill.setPadding(Ui.dp(this, 12), Ui.dp(this, 8), Ui.dp(this, 14), Ui.dp(this, 8));
        pill.setClickable(true);
        pill.setOnClickListener(v -> onClick.run());
        pill.setContentDescription(label);
        if (iconRes != 0) {
            android.widget.ImageView icon = new android.widget.ImageView(this);
            icon.setImageResource(iconRes);
            icon.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
            int size = Ui.dp(this, 16);
            icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
            pill.addView(icon);
        }
        TextView text = Ui.text(this, label, 12f, Ui.ACCENT);
        text.setPadding(iconRes == 0 ? 0 : Ui.dp(this, 8), 0, 0, 0);
        pill.addView(text);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, Ui.dp(this, 8), 0);
        pill.setLayoutParams(params);
        return pill;
    }

    // ------------------------------------------------------------------------------------- tools

    /** Rename, delete, and what the thing actually is. */
    private void manage(final File entry) {
        new AlertDialog.Builder(this)
            .setTitle(entry.getName())
            .setItems(new String[] {"Rename", "Delete", "Details"}, (dialog, which) -> {
                if (which == 0) {
                    rename(entry);
                } else if (which == 1) {
                    delete(entry);
                } else {
                    details(entry);
                }
            })
            .show();
    }

    private void newFolder() {
        prompt("New folder", "", name -> {
            if (!valid(name)) {
                return;
            }
            File folder = new File(current, name);
            if (folder.exists()) {
                Toast.makeText(this, "Already there.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (folder.mkdirs()) {
                show(current);
            } else {
                Toast.makeText(this, "Could not create " + name, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void rename(final File entry) {
        prompt("Rename", entry.getName(), name -> {
            if (!valid(name)) {
                return;
            }
            File target = new File(entry.getParentFile(), name);
            if (target.exists()) {
                Toast.makeText(this, "Something is already called that.", Toast.LENGTH_SHORT).show();
                return;
            }
            if (entry.renameTo(target)) {
                show(current);
            } else {
                Toast.makeText(this, "Could not rename " + entry.getName(), Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Deletes, after asking — and the asking says how much, because "delete" on a folder is a
     * different promise from "delete" on a file and the dialog is the last chance to notice.
     */
    private void delete(final File entry) {
        boolean directory = entry.isDirectory();
        int count = directory ? countUnder(entry) : 0;
        String message = directory
            ? "Delete this folder and the " + count + (count == 1 ? " item" : " items") + " in it?"
            : "Delete " + entry.getName() + "?";
        new AlertDialog.Builder(this)
            .setTitle(entry.getName())
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete", (dialog, which) -> {
                if (removeRecursively(entry)) {
                    show(current);
                } else {
                    Toast.makeText(this, "Could not delete " + entry.getName(),
                        Toast.LENGTH_LONG).show();
                }
            })
            .show();
    }

    private void details(File entry) {
        String when = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(new Date(entry.lastModified()));
        String what = entry.isDirectory()
            ? countUnder(entry) + " items"
            : bytes(entry.length());
        new AlertDialog.Builder(this)
            .setTitle(entry.getName())
            .setMessage(DeviceStorage.display(volumes, entry) + "\n\n" + what + "\nModified " + when)
            .setPositiveButton("Close", null)
            .show();
    }

    /** One text field and an OK, which is every naming dialog this app needs. */
    private void prompt(String title, String initial, Consumer onName) {
        final EditText field = new EditText(this);
        field.setSingleLine(true);
        field.setText(initial);
        field.setSelection(field.getText().length());
        field.setContentDescription(title);
        LinearLayout wrapper = new LinearLayout(this);
        int pad = Ui.dp(this, 20);
        wrapper.setPadding(pad, Ui.dp(this, 8), pad, 0);
        wrapper.addView(field, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(wrapper)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("OK", (dialog, which) -> onName.accept(field.getText().toString().trim()))
            .show();
    }

    /** Java 11 without a functional-interface import; one method is cheaper than the dependency. */
    private interface Consumer {
        void accept(String value);
    }

    private boolean valid(String name) {
        if (name.isEmpty()) {
            Toast.makeText(this, "Give it a name first.", Toast.LENGTH_SHORT).show();
            return false;
        }
        if (name.contains("/") || name.equals(".") || name.equals("..")) {
            Toast.makeText(this, "That is not a name this device can use.", Toast.LENGTH_SHORT).show();
            return false;
        }
        return true;
    }

    private int countUnder(File directory) {
        String[] names = directory.list();
        return names == null ? 0 : names.length;
    }

    private boolean removeRecursively(File entry) {
        if (entry.isDirectory()) {
            File[] children = entry.listFiles();
            if (children != null) {
                for (File child : children) {
                    removeRecursively(child);
                }
            }
        }
        return entry.delete();
    }

    // ------------------------------------------------------------------------------------ answers

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
        details(file);
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
        if (!valid(name)) {
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

    // ------------------------------------------------------------------------------------ listing

    private List<File> sorted(File directory) {
        File[] entries = directory.listFiles();
        List<File> all = new ArrayList<>();
        if (entries != null) {
            all.addAll(Arrays.asList(entries));
        }
        all.sort(new Comparator<File>() {
            @Override
            public int compare(File a, File b) {
                // Folders lead whatever the sort is: they are where you go, not what you are
                // looking at, and mixing them into a size order buries them.
                if (a.isDirectory() != b.isDirectory()) {
                    return a.isDirectory() ? -1 : 1;
                }
                if (sort == SORT_SIZE && !a.isDirectory()) {
                    return Long.compare(b.length(), a.length());
                }
                if (sort == SORT_RECENT) {
                    return Long.compare(b.lastModified(), a.lastModified());
                }
                return a.getName().compareToIgnoreCase(b.getName());
            }
        });
        return all;
    }

    private String describe(File entry) {
        String when = DateFormat.getDateInstance(DateFormat.MEDIUM)
            .format(new Date(entry.lastModified()));
        if (entry.isDirectory()) {
            int count = countUnder(entry);
            return (count == 1 ? "1 item" : count + " items") + " · " + when;
        }
        return when;
    }

    private int iconFor(File entry) {
        if (entry.isDirectory()) {
            return R.drawable.ic_folder;
        }
        String name = entry.getName().toLowerCase(Locale.US);
        boolean image = name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
            || name.endsWith(".webp") || name.endsWith(".gif") || name.endsWith(".mp4");
        return image ? R.drawable.ic_image : R.drawable.ic_file;
    }

    private int tintFor(File entry) {
        if (entry.isDirectory()) {
            return Ui.TINT_FOLDER;
        }
        return iconFor(entry) == R.drawable.ic_image ? Ui.TINT_IMAGE : Ui.TINT_FILE;
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

    private Button button(String label, int colour, final Runnable onClick) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(colour);
        button.setBackground(Ui.ripple());
        button.setContentDescription(label);
        button.setOnClickListener(v -> onClick.run());
        return button;
    }
}

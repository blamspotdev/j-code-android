package dev.blamspot.jcode.vdevice.files;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * The virtual device's storage volumes, found the way an app is allowed to find them.
 *
 * <p>`/sdcard` and `/storage/external` on this device are <em>presentation</em> paths — what `adb ls`
 * prints and what this app shows. The bytes live in JCode's own trees, and the container redirects
 * the `Context` storage APIs onto them. `Environment.getExternalStorageDirectory()` is <b>not</b>
 * among them: it is computed from a static the container has no seam into, so it still answers the
 * *phone's* path. An app here that opens `new File("/sdcard/…")` is therefore reading the user's real
 * storage — and a file explorer doing that would show somebody their own photos and call them the
 * device's.
 *
 * <p>So the roots are derived from paths that <em>are</em> redirected. `getExternalFilesDirs(null)`
 * answers one `<root>/Android/data/<pkg>/files` per volume, and four levels up from each is that
 * volume's root. That is a documented layout rather than a guess, and it is reached entirely through
 * supported API — including the count, which is why this app never has to be told how many volumes
 * the device has.
 */
final class DeviceStorage {

    /** `<root>/Android/data/<pkg>/files` — four names between the app's dir and the root. */
    private static final int DEPTH = 4;

    /** What each volume is called, in the order the platform hands them over. */
    private static final String[] LABELS = {"Internal storage", "External storage"};

    /** The device path each volume answers to, in the same order. */
    private static final String[] DEVICE_ROOTS = {"/sdcard", "/storage/external"};

    private DeviceStorage() {
    }

    /** One entry per volume the device has. */
    static final class Volume {
        final String label;
        final String deviceRoot;
        final File directory;

        Volume(String label, String deviceRoot, File directory) {
            this.label = label;
            this.deviceRoot = deviceRoot;
            this.directory = directory;
        }
    }

    static List<Volume> volumes(Context context) {
        List<Volume> found = new ArrayList<>();
        File[] own = context.getExternalFilesDirs(null);
        if (own == null) {
            return found;
        }
        for (int i = 0; i < own.length; i++) {
            File root = up(own[i]);
            if (root == null) {
                continue;
            }
            found.add(new Volume(
                i < LABELS.length ? LABELS[i] : root.getName(),
                i < DEVICE_ROOTS.length ? DEVICE_ROOTS[i] : root.getAbsolutePath(),
                root));
        }
        return found;
    }

    private static File up(File appDir) {
        if (appDir == null) {
            return null;
        }
        File candidate = appDir;
        for (int i = 0; i < DEPTH && candidate != null; i++) {
            candidate = candidate.getParentFile();
        }
        return candidate != null && new File(candidate, "Android").isDirectory() ? candidate : null;
    }

    /** The device path for a real one — what the container and `adb` both call it. */
    static String display(List<Volume> volumes, File file) {
        String path = file.getAbsolutePath();
        for (Volume volume : volumes) {
            String root = volume.directory.getAbsolutePath();
            if (path.equals(root)) {
                return volume.deviceRoot;
            }
            if (path.startsWith(root + File.separator)) {
                return volume.deviceRoot
                    + path.substring(root.length()).replace(File.separatorChar, '/');
            }
        }
        return path;
    }
}

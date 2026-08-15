package dev.jcode.vdevice.files;

import android.content.Context;

import java.io.File;

/**
 * Where the virtual device's shared storage is, found the way an app is allowed to find it.
 *
 * <p>`/sdcard` on this device is a <em>presentation</em> path — what `adb ls` prints and what this
 * app shows. The bytes live in JCode's app-private tree, and the container redirects the `Context`
 * storage APIs onto it. `Environment.getExternalStorageDirectory()` is <b>not</b> among them: it is
 * computed from a static the container has no seam into, so it still answers the *phone's* path. An
 * app here that opens `new File("/sdcard/…")` is therefore reading the user's real storage, which is
 * the one thing this device exists to prevent — and a file explorer doing it would show the user
 * their own photos and call them the device's.
 *
 * <p>So the root is derived from a path that <em>is</em> redirected. `getExternalFilesDir(null)`
 * answers `<root>/Android/data/<pkg>/files`, and four levels up from that is `<root>`. That is a
 * documented layout rather than a guess, and it is reached entirely through supported API.
 */
final class DeviceStorage {

    /** `<root>/Android/data/<pkg>/files` — four names between the app's dir and the root. */
    private static final int DEPTH = 4;

    /** What the device's storage is called everywhere a person can see it. */
    static final String DEVICE_ROOT = "/sdcard";

    private DeviceStorage() {
    }

    static File root(Context context) {
        File own = context.getExternalFilesDir(null);
        if (own == null) {
            return context.getFilesDir();
        }
        File candidate = own;
        for (int i = 0; i < DEPTH && candidate != null; i++) {
            candidate = candidate.getParentFile();
        }
        return candidate != null && new File(candidate, "Android").isDirectory() ? candidate : own;
    }

    /** The device path for a real one — what the container and `adb` both call it. */
    static String display(Context context, File file) {
        String root = root(context).getAbsolutePath();
        String path = file.getAbsolutePath();
        if (!path.startsWith(root)) {
            return path;
        }
        String relative = path.substring(root.length()).replace(File.separatorChar, '/');
        return relative.isEmpty() ? DEVICE_ROOT : DEVICE_ROOT + relative;
    }
}

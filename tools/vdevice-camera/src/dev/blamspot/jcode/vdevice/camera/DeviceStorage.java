package dev.blamspot.jcode.vdevice.camera;

import android.content.Context;

import java.io.File;

/**
 * Where the virtual device's shared storage is, found the way an app is allowed to find it.
 *
 * <p>`/sdcard` on this device is a <em>presentation</em> path — what `adb ls` prints and what the
 * device's own screens show. The bytes live in JCode's app-private tree, and the container redirects
 * the `Context` storage APIs onto it. `Environment.getExternalStorageDirectory()` is <b>not</b>
 * among them: it is computed from a static the container has no seam into, so it still answers the
 * *phone's* path. An app here that opens `new File("/sdcard/…")` is therefore writing into the
 * user's real storage, which is the one thing this device exists to prevent.
 *
 * <p>So the root is derived from a path that <em>is</em> redirected. `getExternalFilesDir(null)`
 * answers `<root>/Android/data/<pkg>/files`, and four levels up from that is `<root>`. That is a
 * documented layout rather than a guess, and it is reached entirely through supported API.
 *
 * <p>If the walk does not arrive somewhere plausible the app keeps its own external files dir and
 * saves there instead: a photo in an odd place beats a photo written outside the device.
 */
final class DeviceStorage {

    /** `<root>/Android/data/<pkg>/files` — four names between the app's dir and the root. */
    private static final int DEPTH = 4;

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

    /** The shared pictures directory, created if this is the first photo the device has taken. */
    static File pictures(Context context) {
        File directory = new File(root(context), "DCIM/Camera");
        directory.mkdirs();
        return directory;
    }

    /** What a path should be called when it is shown to a person or written into a log. */
    static String display(Context context, File file) {
        String root = root(context).getAbsolutePath();
        String path = file.getAbsolutePath();
        return path.startsWith(root) ? "/sdcard" + path.substring(root.length()) : path;
    }
}

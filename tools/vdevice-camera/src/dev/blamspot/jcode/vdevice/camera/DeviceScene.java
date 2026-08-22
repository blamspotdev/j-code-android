package dev.blamspot.jcode.vdevice.camera;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * Which scene the device's camera is set to show.
 *
 * <p>Read from the container's settings provider rather than decided here, because it is a property
 * of the *device* — it is set on JCode's hardware bench, alongside whether the device has a camera at
 * all — and an app that chose for itself would be disagreeing with the switch a person just moved.
 *
 * <p>This is a smaller copy of what the Settings app's `DeviceSettings` does, and the duplication is
 * across an APK boundary rather than within one: these are two separately built artifacts that share
 * no code, and the alternative is a library neither of them is big enough to want.
 */
final class DeviceScene {

    private static final String TAG = "VCAMERA";

    /** What the bench defaults to, and what this falls back to when the device cannot be asked. */
    private static final String DEFAULT = "pixelart";

    private DeviceScene() {
    }

    static String chosen(Context context) {
        try {
            Uri authority = Uri.parse("content://" + container(context) + ".vdevice.settings");
            Bundle device = context.getContentResolver().call(authority, "device", null, null);
            if (device != null) {
                String scene = device.getString("camera/scene");
                if (scene != null && !scene.isEmpty()) {
                    return scene;
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot ask the device which scene it is set to", e);
        }
        return DEFAULT;
    }

    /**
     * Which app this device is running inside.
     *
     * <p>Not `getPackageName()`: inside a guest that answers with the <em>guest's</em> package, which
     * is the whole point of the container and exactly wrong here. A guest runs in the container's own
     * process under its uid, so asking who owns this uid gets the right answer — and gets it without
     * this app knowing JCode ships under three different names.
     */
    private static String container(Context context) {
        try {
            String[] owners = context.getPackageManager().getPackagesForUid(Process.myUid());
            if (owners != null) {
                for (String owner : owners) {
                    if (!owner.equals(context.getPackageName())) {
                        return owner;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "cannot tell which app this device is running inside", e);
        }
        return "dev.blamspot.jcode";
    }
}

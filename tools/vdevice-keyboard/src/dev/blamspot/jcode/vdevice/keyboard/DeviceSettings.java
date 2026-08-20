package dev.blamspot.jcode.vdevice.keyboard;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * The device's settings, as this app sees them.
 *
 * <p>One {@code ContentResolver.call} per question, against the provider the container publishes.
 * The same class the device's Settings app carries, for the same reason: an app on this device has
 * no privileges of its own, so a setting is something it asks for rather than something it reaches.
 *
 * <p>The authority is derived from the container's package rather than written down, because JCode
 * ships as {@code dev.blamspot.jcode}, {@code dev.blamspot.jcode.debug} and {@code dev.blamspot.jcode.beta} and this app is
 * installed on all three.
 */
final class DeviceSettings {

    private static final String TAG = "VKEYBOARD";

    private static final String METHOD_DEVICE = "device";
    private static final String METHOD_SET = "set";
    private static final String EXTRA_VALUE = "value";
    private static final String EXTRA_APPLIED = "applied";

    private final Context context;
    private final Uri authority;

    DeviceSettings(Context context) {
        this.context = context;
        this.authority = Uri.parse("content://" + container(context) + ".vdevice.settings");
    }

    /**
     * Which app this device is running inside.
     *
     * <p>Not {@code getPackageName()}: inside a guest that answers with the <em>guest's</em> package,
     * which is the whole point of the container and exactly wrong here. A guest runs under the
     * container's own uid, so asking the package manager who owns this uid gets the right answer.
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

    Bundle device() {
        return call(METHOD_DEVICE, null, null);
    }

    KeyboardSettings keyboard() {
        return KeyboardSettings.from(device());
    }

    /** True when the container accepted the change. */
    boolean set(String key, String value) {
        Bundle extras = new Bundle();
        extras.putString(EXTRA_VALUE, value);
        Bundle answer = call(METHOD_SET, key, extras);
        return answer != null && answer.getBoolean(EXTRA_APPLIED);
    }

    private Bundle call(String method, String arg, Bundle extras) {
        try {
            ContentResolver resolver = context.getContentResolver();
            return resolver.call(authority, method, arg, extras);
        } catch (Exception e) {
            Log.w(TAG, "cannot reach the device's settings: " + method, e);
            return null;
        }
    }
}

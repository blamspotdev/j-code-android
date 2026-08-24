package dev.blamspot.jcode.vdevice.settings;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;

/**
 * The device's settings, as this app sees them.
 *
 * <p>A thin wrapper over one `ContentResolver.call` per question, and the thinness is the point: the
 * container owns what a setting <em>means</em> — which modes a piece of hardware has, what a
 * permission rule does, where a storage volume lives — and this app owns how it looks. Anything this
 * class decided for itself would be a second opinion about the device.
 *
 * <p>The authority is derived from the container's package rather than written down, because JCode
 * ships as `dev.blamspot.jcode`, `dev.blamspot.jcode.debug` and `dev.blamspot.jcode.beta` and this app is installed on all
 * three. Asking the {@link Context} which app it is running inside gets that right without this app
 * knowing there is more than one.
 */
final class DeviceSettings {

    private static final String TAG = "VSETTINGS";

    static final String METHOD_DEVICE = "device";
    static final String METHOD_APPS = "apps";
    static final String METHOD_APP = "app";
    static final String METHOD_SET = "set";

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
     * <p>Not `getPackageName()`, and not `getApplicationInfo()`: inside a guest both answer with the
     * <em>guest's</em> package, which is the whole point of the container and exactly wrong here. A
     * guest runs in the container's own process under the container's own uid, so asking the package
     * manager who owns this uid gets the right answer — and gets it without this app knowing that
     * JCode ships as three different package names.
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

    Bundle apps() {
        return call(METHOD_APPS, null, null);
    }

    Bundle app(String packageName) {
        return call(METHOD_APP, packageName, null);
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

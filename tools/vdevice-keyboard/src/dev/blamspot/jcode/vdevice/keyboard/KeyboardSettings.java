package dev.blamspot.jcode.vdevice.keyboard;

import android.os.Bundle;

/**
 * What the person has chosen about their keyboard.
 *
 * <p>Kept in the device's own policy and read back through the container's settings provider, which
 * is the same route the Camera app reads its scene by. Two things fall out of that, both wanted: the
 * device's Settings app can show these rows without this app publishing anything, and the choices
 * are wiped with the device, because they live in the volatile tree along with everything else the
 * device knows about itself.
 *
 * <p>Read fresh at the start of every input rather than cached. It costs one {@code call} per field
 * focused, and it means changing a setting in Settings takes effect on the next field rather than on
 * the next restart of the device.
 */
final class KeyboardSettings {

    static final String KEY_LAYOUT = "keyboard/layout";
    static final String KEY_PREVIEW = "keyboard/preview";
    static final String KEY_HAPTICS = "keyboard/haptics";
    static final String KEY_HEIGHT = "keyboard/height";

    static final String COMPACT = "Compact";
    static final String STANDARD = "Standard";
    static final String TALL = "Tall";

    final String layout;
    final String height;
    final boolean preview;
    final boolean haptics;

    /** What {@link #height} means to a row of keys. */
    final float heightScale;

    private KeyboardSettings(String layout, String height, boolean preview, boolean haptics) {
        this.layout = layout;
        this.height = height;
        this.preview = preview;
        this.haptics = haptics;
        this.heightScale = COMPACT.equals(height) ? 0.85f : TALL.equals(height) ? 1.18f : 1f;
    }

    static KeyboardSettings defaults() {
        return new KeyboardSettings(Layouts.QWERTY, STANDARD, true, true);
    }

    /** What the provider answered, with a default for anything a device has not been asked about. */
    static KeyboardSettings from(Bundle device) {
        if (device == null) {
            return defaults();
        }
        KeyboardSettings fallback = defaults();
        return new KeyboardSettings(
            value(device, KEY_LAYOUT, fallback.layout),
            value(device, KEY_HEIGHT, fallback.height),
            !"false".equals(device.getString(KEY_PREVIEW)),
            !"false".equals(device.getString(KEY_HAPTICS)));
    }

    private static String value(Bundle device, String key, String fallback) {
        String value = device.getString(key);
        return value == null || value.isEmpty() ? fallback : value;
    }

    static String[] layouts() {
        return new String[] {Layouts.QWERTY, Layouts.QWERTZ, Layouts.AZERTY};
    }

    static String[] heights() {
        return new String[] {COMPACT, STANDARD, TALL};
    }
}

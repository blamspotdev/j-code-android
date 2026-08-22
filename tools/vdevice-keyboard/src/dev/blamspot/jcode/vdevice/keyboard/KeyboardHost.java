package dev.blamspot.jcode.vdevice.keyboard;

import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * The one class the container talks to.
 *
 * <h2>Why reflection, and why this shape</h2>
 *
 * <p>The container and this app do not share a class loader. A guest's is parented to the <em>boot</em>
 * loader rather than to JCode's, deliberately — parent-first delegation would answer every library
 * the IDE also ships out of the IDE's dex and hand the guest the IDE's resource ids. So nothing
 * crosses between the two but framework types, and that is the constraint this contract is built
 * around: every parameter and return value below is {@link Context}, {@link Handler}, {@link View},
 * {@link InputConnection} or {@link EditorInfo}, all of them loaded by the boot loader and therefore
 * the same class on both sides.
 *
 * <p>That constraint turns out to be a gift rather than a tax. {@code InputConnection} is the
 * platform's own editing contract — the object {@code TextView} hands a real IME — so the container
 * does not have to invent a protocol for "type this": it passes the focused field's connection
 * straight through, and this app edits with it exactly as any IME would.
 *
 * <h2>The reverse direction</h2>
 *
 * <p>Two things have to travel the other way, and a {@link Handler} carries both because
 * {@link android.os.Message} is a framework type and an interface declared here would not be. The
 * container hands one in and reads {@code what}:
 *
 * <table>
 *   <tr><th>{@code what}</th><th>Means</th></tr>
 *   <tr><td>{@link #MSG_HIDE}</td><td>Put the keyboard away; the field keeps its focus</td></tr>
 *   <tr><td>{@link #MSG_KEY}</td><td>{@code arg1} is a key code to send the app the ordinary way</td></tr>
 * </table>
 */
public final class KeyboardHost {

    /** The person pressed the keyboard's own hide key. */
    public static final int MSG_HIDE = 1;

    /** {@code arg1} is a {@link android.view.KeyEvent} key code the container should deliver. */
    public static final int MSG_KEY = 2;

    private final KeyboardView view;
    private final DeviceSettings settings;

    public KeyboardHost(Context context, Handler host) {
        this.settings = new DeviceSettings(context);
        this.view = new KeyboardView(context, host);
    }

    /** The keyboard itself, for the container to put on the device's screen. */
    public View view() {
        return view;
    }

    /**
     * A field has taken the focus.
     *
     * <p>[info] is what the app filled in when the container called {@code onCreateInputConnection}
     * on it — the input type, the action it wants on the Enter key, its hint — so this is where the
     * keyboard finds out whether it is a keypad, an email field or a password.
     */
    public void startInput(InputConnection connection, EditorInfo info) {
        view.startInput(connection, info, settings.keyboard());
    }

    /** The field has gone: nothing to type into until the next {@link #startInput}. */
    public void finishInput() {
        view.finishInput();
    }
}

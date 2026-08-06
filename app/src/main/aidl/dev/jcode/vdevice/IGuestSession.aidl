package dev.jcode.vdevice;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import dev.jcode.vdevice.IGuestSessionCallback;

/**
 * The :guest process's side of an embedded app-sandbox tab.
 *
 * start() answers with a SurfaceControlViewHost.SurfacePackage (in a Bundle, so a failure can come
 * back as a message rather than an exception); the IDE hands it to a SurfaceView. hostToken is that
 * SurfaceView's input token, without which the window manager refuses to grant the embedded
 * hierarchy an input channel at all; when it cannot be obtained the guest is registered with no
 * input channel and the events below carry input across by hand instead.
 */
interface IGuestSession {
    Bundle start(String apkPath, String activityClass, int width, int height, IBinder hostToken,
            IGuestSessionCallback callback);

    /** A fresh surface package for the running guest: a SurfaceView releases the one it was given
     *  when it detaches, so switching editor tabs and back needs a new one rather than a restart. */
    Bundle surface();

    // Everything below is called from the IDE's UI thread, so none of it may block on the guest's.
    oneway void resize(int width, int height);

    oneway void touch(in MotionEvent event);

    oneway void key(in KeyEvent event);

    oneway void text(String text);

    /** Pops the embedded back stack, or sends Back to the only activity. */
    oneway void back();
}

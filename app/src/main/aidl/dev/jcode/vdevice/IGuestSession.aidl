package dev.jcode.vdevice;

import android.os.Bundle;
import android.view.KeyEvent;
import android.view.MotionEvent;
import dev.jcode.vdevice.IGuestSessionCallback;

/**
 * The :guest process's side of an embedded device-sandbox tab.
 *
 * start() answers with a SurfaceControlViewHost.SurfacePackage (in a Bundle, so a failure can come
 * back as a message rather than an exception); the IDE hands it to a SurfaceView. hostToken is that
 * SurfaceView's input token and is not optional: without it the window manager refuses to grant the
 * embedded hierarchy an input channel at all, so a null one is refused with a message rather than
 * embedded. Having the channel is still not the same as being fed by it, which is why the events
 * below carry input across by hand.
 */
interface IGuestSession {
    Bundle start(String apkPath, String activityClass, int width, int height, IBinder hostToken,
            IGuestSessionCallback callback);

    /** A fresh surface package for the running guest: a SurfaceView releases the one it was given
     *  when it detaches, so switching editor tabs and back needs a new one rather than a restart. */
    Bundle surface();

    /** Writes the guest's current screen to pngPath as a PNG. The two processes share a uid and a
     *  data directory, so a file beats a Bundle that a large screen would burst. */
    Bundle capture(String pngPath);

    /** Writes the guest's view tree to xmlPath as uiautomator-shaped XML, for `uiautomator dump`.
     *  A file for the same reason capture() uses one: a deep tree outgrows a Bundle. */
    Bundle dump(String xmlPath);

    // Everything below is called from the IDE's UI thread, so none of it may block on the guest's.
    oneway void resize(int width, int height);

    oneway void touch(in MotionEvent event);

    oneway void key(in KeyEvent event);

    oneway void text(String text);

    /** Pops the embedded back stack, or sends Back to the only activity. */
    oneway void back();

    /** The answer to one onPermissionRequest, in the order it asked. */
    oneway void permissionResult(int requestId, in boolean[] granted);

    /** Force-stop: ends everything the named guest is hosting and drops it from the loader. */
    oneway void forceStop(String packageName);

    /**
     * Ends the device: tears the guest down and takes the :guest process with it.
     *
     * Unbinding alone does not. Android keeps an emptied process around and rebinds into it, so
     * everything the container accumulated — loaded dex and class loaders, hosted services, the
     * swapped Instrumentation, the WebView data directory it claimed — outlives the tab that asked
     * for it. Closing the tab has to mean the device is off, not hidden.
     */
    oneway void shutdown();
}

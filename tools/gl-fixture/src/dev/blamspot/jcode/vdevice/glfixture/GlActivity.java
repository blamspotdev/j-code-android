package dev.blamspot.jcode.vdevice.glfixture;

import android.app.Activity;
import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Clears the screen to magenta, and says so.
 *
 * The point is to separate two failures that look identical from outside: a container that cannot
 * composite a `SurfaceView`, and an app that simply has not drawn anything yet. This draws
 * immediately and unconditionally, with no setup, no licence check and no first-run wizard, so:
 *
 *   magenta  -> GL compositing works in the tab
 *   black    -> it does not, and the log below says how far the pipeline got
 *
 * The label is a plain View sibling *over* the GL surface, so a third case is visible too: label
 * present but no colour means the activity rendered and only the surface is missing.
 */
public class GlActivity extends Activity {

    private static final String TAG = "GLFIXTURE";

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);

        FrameLayout root = new FrameLayout(this);

        GLSurfaceView gl = new GLSurfaceView(this);
        gl.setEGLContextClientVersion(2);
        gl.setRenderer(new GLSurfaceView.Renderer() {
            @Override
            public void onSurfaceCreated(GL10 unused, EGLConfig config) {
                Log.i(TAG, "onSurfaceCreated: GL is up, renderer="
                        + GLES20.glGetString(GLES20.GL_RENDERER));
            }

            @Override
            public void onSurfaceChanged(GL10 unused, int width, int height) {
                Log.i(TAG, "onSurfaceChanged: " + width + "x" + height);
                GLES20.glViewport(0, 0, width, height);
            }

            @Override
            public void onDrawFrame(GL10 unused) {
                GLES20.glClearColor(1f, 0f, 1f, 1f);
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            }
        });
        root.addView(gl, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        TextView label = new TextView(this);
        label.setText("GL fixture: expecting magenta");
        label.setTextColor(Color.WHITE);
        label.setBackgroundColor(Color.parseColor("#80000000"));
        label.setPadding(24, 16, 24, 16);
        FrameLayout.LayoutParams labelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.gravity = Gravity.TOP | Gravity.START;
        root.addView(label, labelParams);

        setContentView(root);
        Log.i(TAG, "onCreate done");
    }
}

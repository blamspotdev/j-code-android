package dev.blamspot.jcode.vdevice.lifecycle;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Reports every lifecycle callback it is given, to logcat and to its own screen.
 *
 * Two audiences, because the two questions are different. The log is what a test reads: the order of
 * the callbacks is the thing under test, and only a log preserves order. The screen is what a person
 * reads, and it answers a question the log cannot — whether the instance is the same one. A rebuilt
 * activity starts its list again from onCreate; a resumed one continues the list it had.
 */
public class LifecycleActivity extends Activity {

    static final String TAG = "VDEVICE-LIFECYCLE";

    /** Survives a rebuild, so the screen can say how many instances there have been. */
    private static int builds = 0;

    private static final String KEY_LOG = "log";
    private static final String KEY_MARK = "mark";

    private final StringBuilder log = new StringBuilder();
    private TextView view;

    /** Set only from saved state, so a screen showing it is a screen that was handed state back. */
    private String restoredMark = null;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        builds++;
        // Registered on the ACTIVITY, not on the Application, and that distinction is the test.
        // `Activity.registerActivityLifecycleCallbacks` is public SDK and the list behind it is a
        // non-SDK field, so a container that cannot reach that field has no way to dispatch to
        // whatever an app put in it — which is AndroidX's own ReportFragment on API 29+, and any
        // app that does its own book-keeping this way. Anything logged with an `own:` prefix below
        // came back through that list.
        registerActivityLifecycleCallbacks(new Watcher());
        if (state != null) {
            log.append(state.getString(KEY_LOG, ""));
            restoredMark = state.getString(KEY_MARK);
        }
        setContentView(page());
        // The bundle, not just its presence: an activity handed an empty bundle and one handed
        // nothing look identical from the outside, and they mean opposite things about the save.
        say("onCreate(" + (state == null ? "null" : "restored " + restoredMark) + ")");
    }

    @Override protected void onStart() { super.onStart(); say("onStart"); }
    @Override protected void onRestart() { super.onRestart(); say("onRestart"); }
    @Override protected void onResume() { super.onResume(); say("onResume"); }
    @Override protected void onPause() { super.onPause(); say("onPause"); }
    @Override protected void onStop() { super.onStop(); say("onStop"); }
    @Override protected void onDestroy() { super.onDestroy(); say("onDestroy"); }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        say("onNewIntent");
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        say("onWindowFocusChanged(" + hasFocus + ")");
    }

    /**
     * Never called on this activity if the container is right.
     *
     * It declares no configChanges, so a phone rebuilds it rather than telling it. Seeing this in
     * the log means the container resized in place something that expected to be relaunched.
     */
    @Override
    public void onConfigurationChanged(Configuration configuration) {
        super.onConfigurationChanged(configuration);
        say("onConfigurationChanged " + configuration.screenWidthDp + "x"
                + configuration.screenHeightDp + "dp @" + configuration.densityDpi);
    }

    /**
     * Writes something only this instance could have written.
     *
     * A mark rather than a flag: the point is not that a bundle came back but that *this* bundle
     * came back, and a counter cannot tell a restored value from a freshly initialised one.
     */
    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(KEY_MARK, "build-" + builds);
        out.putString(KEY_LOG, log.toString());
        say("onSaveInstanceState(build-" + builds + ")");
    }

    @Override
    protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        say("onRestoreInstanceState(" + state.getString(KEY_MARK) + ")");
    }

    private View page() {
        ScrollView page = new ScrollView(this);
        page.setBackgroundColor(0xFF101418);
        page.setFillViewport(true);

        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        column.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        Configuration configuration = getResources().getConfiguration();
        title.setText("Instance " + builds + "  -  " + configuration.screenWidthDp + "x"
                + configuration.screenHeightDp + "dp @" + configuration.densityDpi
                + (restoredMark == null ? "" : "  -  restored " + restoredMark));
        title.setTextColor(0xFF8AB4F8);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        column.addView(title);

        Button second = new Button(this);
        second.setText("Cover me");
        second.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startActivity(new Intent(LifecycleActivity.this, SecondActivity.class));
            }
        });
        column.addView(second);

        view = new TextView(this);
        view.setTextColor(Color.WHITE);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        view.setGravity(Gravity.START);
        view.setText(log.toString());
        column.addView(view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        page.addView(column, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return page;
    }

    /**
     * What the activity itself asked to be told.
     *
     * All of them, because which ones arrive is the question. The plain callbacks come from
     * `Activity.onStart` and friends dispatching to their own list; the Pre/Post pairs come only
     * from `Activity.performStart`/`performResume`, which the container cannot call and has to
     * stand in for.
     */
    private final class Watcher implements Application.ActivityLifecycleCallbacks {
        @Override public void onActivityCreated(Activity a, Bundle s) { own("onActivityCreated"); }
        @Override public void onActivityStarted(Activity a) { own("onActivityStarted"); }
        @Override public void onActivityResumed(Activity a) { own("onActivityResumed"); }
        @Override public void onActivityPaused(Activity a) { own("onActivityPaused"); }
        @Override public void onActivityStopped(Activity a) { own("onActivityStopped"); }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle s) { own("onActivitySaveInstanceState"); }
        @Override public void onActivityDestroyed(Activity a) { own("onActivityDestroyed"); }

        @Override public void onActivityPostStarted(Activity a) { own("onActivityPostStarted"); }
        @Override public void onActivityPostResumed(Activity a) { own("onActivityPostResumed"); }
        @Override public void onActivityPrePaused(Activity a) { own("onActivityPrePaused"); }
        @Override public void onActivityPreStopped(Activity a) { own("onActivityPreStopped"); }
    }

    private void own(String step) {
        say("own: " + step);
    }

    private void say(String step) {
        Log.i(TAG, "main: " + step);
        log.append(step).append('\n');
        if (view != null) view.setText(log.toString());
    }
}

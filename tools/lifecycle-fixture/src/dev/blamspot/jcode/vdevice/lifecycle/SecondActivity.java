package dev.blamspot.jcode.vdevice.lifecycle;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * Something for the first screen to be covered by.
 *
 * The question this fixture exists to answer is what happens to the activity *underneath*, and that
 * needs one on top. Deliberately plain: it reports its own callbacks and draws nothing else, so
 * anything interesting in the log belongs to the screen it covered.
 */
public class SecondActivity extends Activity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        TextView text = new TextView(this);
        text.setText("Second screen\n\nBack returns to the first.");
        text.setTextColor(Color.WHITE);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        text.setGravity(Gravity.CENTER);
        text.setBackgroundColor(0xFF1E293B);
        setContentView(text);
        say("onCreate");
    }

    @Override protected void onStart() { super.onStart(); say("onStart"); }
    @Override protected void onResume() { super.onResume(); say("onResume"); }
    @Override protected void onPause() { super.onPause(); say("onPause"); }
    @Override protected void onStop() { super.onStop(); say("onStop"); }
    @Override protected void onDestroy() { super.onDestroy(); say("onDestroy"); }

    private void say(String step) {
        Log.i(LifecycleActivity.TAG, "second: " + step);
    }
}

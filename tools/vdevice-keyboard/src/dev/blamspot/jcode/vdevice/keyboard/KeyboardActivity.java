package dev.blamspot.jcode.vdevice.keyboard;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * The keyboard's own screen: what it looks like, and somewhere to try it.
 *
 * <p>An IME on a phone is an app whose activity is its settings, and that is what this is. The
 * typing surface deliberately is <em>not</em> an activity — it has to appear over the app being
 * typed into without pausing it, and an activity on the device's back stack would do precisely the
 * opposite.
 *
 * <p>The field at the bottom is not a demo. It is the same path every other app takes: it takes the
 * focus, the container notices, and this app's own keyboard opens over this app's own screen. If it
 * types here it types anywhere, which makes this screen the cheapest test the feature has.
 */
public final class KeyboardActivity extends Activity {

    private DeviceSettings settings;
    private LinearLayout page;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        settings = new DeviceSettings(this);
        ScrollView scroller = new ScrollView(this);
        scroller.setBackgroundColor(Ui.BACKGROUND);
        page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        scroller.addView(page, new ScrollView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroller);
        render();
    }

    /** Rebuilt rather than patched: every control here changes what the others should show. */
    private void render() {
        page.removeAllViews();
        KeyboardSettings chosen = settings.keyboard();

        TextView title = Ui.text(this, "Keyboard", 22f, Ui.TEXT);
        title.setPadding(Ui.dp(this, 22), Ui.dp(this, 24), Ui.dp(this, 22), 0);
        page.addView(title, Ui.wrap());
        TextView subtitle = Ui.text(this, "This device's own, not the phone's", 13f, Ui.MUTED);
        subtitle.setPadding(Ui.dp(this, 22), Ui.dp(this, 4), Ui.dp(this, 22), 0);
        page.addView(subtitle, Ui.wrap());

        LinearLayout arrangement = Ui.card(this, page, "ARRANGEMENT");
        Ui.chips(this, arrangement, KeyboardSettings.layouts(), chosen.layout,
            value -> apply(KeyboardSettings.KEY_LAYOUT, value));

        LinearLayout size = Ui.card(this, page, "KEY SIZE");
        Ui.chips(this, size, KeyboardSettings.heights(), chosen.height,
            value -> apply(KeyboardSettings.KEY_HEIGHT, value));

        LinearLayout feedback = Ui.card(this, page, "FEEDBACK");
        Ui.toggle(this, feedback, "Key preview",
            "Shows the character above your finger. Never on a password field.",
            chosen.preview, value -> apply(KeyboardSettings.KEY_PREVIEW, value));
        Ui.toggle(this, feedback, "Vibrate on keypress",
            "A short tap, on the phone's own motor.",
            chosen.haptics, value -> apply(KeyboardSettings.KEY_HAPTICS, value));

        LinearLayout tryIt = Ui.card(this, page, "TRY IT");
        EditText sample = new EditText(this);
        sample.setHint("Type something");
        sample.setContentDescription("Try the keyboard");
        sample.setTextColor(Ui.TEXT);
        sample.setHintTextColor(Ui.MUTED);
        sample.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        sample.setBackground(Ui.rounded(this, Ui.KEY, 10f));
        sample.setPadding(Ui.dp(this, 14), Ui.dp(this, 12), Ui.dp(this, 14), Ui.dp(this, 12));
        sample.setGravity(Gravity.CENTER_VERTICAL);
        tryIt.addView(sample, Ui.wrap());

        Ui.note(this, page,
            "The keyboard opens by itself whenever a text field takes the focus, and goes away when "
                + "the field does. It is drawn on the device's own screen, so a screenshot of this "
                + "device shows it and an agent driving this device over adb can press its keys.");
    }

    private void apply(String key, String value) {
        settings.set(key, value);
        render();
    }
}

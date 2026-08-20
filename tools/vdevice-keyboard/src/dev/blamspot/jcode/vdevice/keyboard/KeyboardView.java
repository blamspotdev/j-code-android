package dev.blamspot.jcode.vdevice.keyboard;

import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * The keys, and the state a keyboard has to keep.
 *
 * <p>Rows of real views inside a column, so the layout is the platform's and every key is something
 * {@code uiautomator dump} can name and {@code input tap} can hit — see {@link KeyView}.
 *
 * <h2>The strip</h2>
 *
 * <p>A slim line above the keys saying what field is being typed into, and whose keyboard this is.
 * The second half is not decoration: the point of this app is that the device has a keyboard of its
 * own rather than borrowing the phone's, and in a screenshot the two would otherwise be
 * indistinguishable. The device's status bar carries its name for the same reason.
 *
 * <h2>Focus</h2>
 *
 * <p>Nothing here is focusable, at any level. The field being typed into has to keep the focus for
 * its {@code InputConnection} to remain the live one, so this view tree sits over the app and never
 * asks for any.
 */
final class KeyboardView extends FrameLayout {

    private static final int SHIFT_OFF = 0;
    private static final int SHIFT_ON = 1;
    private static final int SHIFT_LOCKED = 2;

    /** Two presses of shift inside this long lock it, which is how every phone keyboard behaves. */
    private static final long DOUBLE_TAP_MS = 400L;

    /** One key row's height, before the person's own scaling. */
    private static final float KEY_DP = 50f;

    /** The strip above the keys. */
    private static final float STRIP_DP = 26f;

    private final Handler host;
    private final LinearLayout rows;
    private final TextView field;
    private final TextView preview;

    private final List<KeyView> keys = new ArrayList<>();

    private InputConnection connection;
    private EditorInfo info;

    private int page = Layouts.LETTERS;
    private int shift = SHIFT_OFF;
    private long shiftPressedAt;

    private KeyboardSettings settings = KeyboardSettings.defaults();

    KeyboardView(Context context, Handler host) {
        super(context);
        this.host = host;
        setBackgroundColor(Ui.BACKGROUND);
        setFocusable(false);
        setFocusableInTouchMode(false);
        setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);

        field = Ui.text(context, "Text", 11f, Ui.MUTED);
        field.setSingleLine(true);
        TextView mark = Ui.text(context, "vDevice", 10f, Ui.MUTED);
        mark.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        mark.setAlpha(0.7f);

        LinearLayout strip = new LinearLayout(context);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setBackgroundColor(Ui.STRIP);
        strip.setPadding(Ui.dp(context, 14), 0, Ui.dp(context, 14), 0);
        strip.setContentDescription("Keyboard");
        strip.addView(field, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        strip.addView(mark, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(Ui.dp(context, 4), Ui.dp(context, 5), Ui.dp(context, 4), Ui.dp(context, 7));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.addView(strip, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Ui.dp(context, STRIP_DP)));
        column.addView(rows, Ui.wrap());
        addView(column, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        preview = Ui.text(context, null, 26f, 0xFF0B0F14);
        preview.setGravity(Gravity.CENTER);
        preview.setBackground(Ui.rounded(context, Ui.ACCENT, 9f));
        preview.setVisibility(GONE);
        addView(preview, new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        build();
    }

    // --- what the container drives ------------------------------------------------------------

    void startInput(InputConnection connection, EditorInfo info, KeyboardSettings settings) {
        this.connection = connection;
        this.info = info;
        this.settings = settings == null ? KeyboardSettings.defaults() : settings;
        this.page = Layouts.pageFor(info);
        this.shift = Editing.allCaps(info) ? SHIFT_LOCKED
            : Editing.shouldCapitalise(connection, info) ? SHIFT_ON : SHIFT_OFF;
        String label = Layouts.fieldLabel(info);
        field.setText(label == null ? "Text" : label);
        field.setContentDescription(label == null ? "Text field" : label);
        build();
    }

    void finishInput() {
        connection = null;
        info = null;
        hidePreview();
    }

    // --- building -----------------------------------------------------------------------------

    private void build() {
        rows.removeAllViews();
        keys.clear();
        Context context = getContext();
        int height = Math.round(Ui.dp(context, KEY_DP) * settings.heightScale);
        int gap = Ui.dp(context, 2.5f);
        for (List<Key> row : Layouts.rows(page, info, settings.layout)) {
            LinearLayout line = new LinearLayout(context);
            line.setOrientation(LinearLayout.HORIZONTAL);
            float total = 0f;
            for (Key key : row) {
                total += key.weight;
            }
            line.setWeightSum(total);
            for (Key key : row) {
                KeyView view = new KeyView(context, key);
                view.showFace(shift != SHIFT_OFF);
                dress(view);
                wire(view);
                LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, key.weight);
                params.setMargins(gap, gap, gap, gap);
                line.addView(view, params);
                keys.add(view);
            }
            rows.addView(line, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, height));
        }
    }

    /** The three keys that show something other than their own name. */
    private void dress(KeyView view) {
        switch (view.key().kind) {
            case Key.SHIFT:
                view.showSymbol(
                    shift == SHIFT_LOCKED ? Ui.SHIFT_LOCKED : Ui.SHIFT,
                    shift == SHIFT_LOCKED ? "Caps lock, on" : shift == SHIFT_ON ? "Shift, on" : "Shift");
                view.tint(shift == SHIFT_LOCKED ? Ui.LOCKED : shift == SHIFT_ON ? Ui.ACCENT : Ui.MODIFIER);
                break;

            case Key.ACTION:
                String label = Layouts.actionLabel(info);
                if (label != null) {
                    view.showLabel(label, label);
                } else {
                    // Back to the mark, and not only on the first field: a form that goes from one
                    // asking for Search to one asking for nothing would otherwise keep saying Search.
                    view.showSymbol(Ui.ENTER, "Enter");
                }
                view.tint(Ui.ACTION);
                break;

            case Key.SPACE:
                // A space bar with nothing written on it is a gap; a phone's says which keyboard it is.
                view.showLabel(settings.layout, "Space");
                view.setTextColor(Ui.MUTED);
                view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
                break;

            default:
                break;
        }
    }

    private void wire(KeyView view) {
        view.setOnClickListener(v -> press((KeyView) v));
        if (view.key().alternate != null) {
            view.setOnLongClickListener(v -> {
                feedback(v, true);
                Editing.type(connection, ((KeyView) v).key().alternate);
                afterTyping();
                return true;
            });
        }
        // Watches only, and says so by returning false: taking the event here would stop the key's
        // own click and long-press handling, which is to say it would stop the keyboard.
        view.setOnTouchListener((v, event) -> {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                showPreview((KeyView) v);
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                hidePreview();
            }
            return false;
        });
    }

    // --- pressing -----------------------------------------------------------------------------

    private void press(KeyView view) {
        Key key = view.key();
        feedback(view, false);
        switch (key.kind) {
            case Key.CHARACTER:
                Editing.type(connection, view.face(shift != SHIFT_OFF));
                afterTyping();
                break;

            case Key.SPACE:
                Editing.type(connection, " ");
                afterTyping();
                break;

            case Key.BACKSPACE:
                Editing.backspace(connection);
                afterTyping();
                break;

            case Key.SHIFT:
                toggleShift();
                break;

            case Key.PAGE:
                page = key.page;
                shift = SHIFT_OFF;
                build();
                break;

            case Key.ACTION:
                if (!Editing.action(connection, info)) {
                    // A single-line field with no action of its own: the platform sends the key and
                    // lets the app decide, so the container is asked to deliver it the way every
                    // other key reaches a guest.
                    host.sendMessage(Message.obtain(host, KeyboardHost.MSG_KEY, KeyEvent.KEYCODE_ENTER, 0));
                }
                break;

            case Key.HIDE:
                host.sendMessage(Message.obtain(host, KeyboardHost.MSG_HIDE));
                break;

            default:
                break;
        }
    }

    /**
     * Shift after a character: off unless it is locked, and back on wherever the field says a
     * capital belongs — the start of a sentence, or of every word.
     */
    private void afterTyping() {
        int next = shift == SHIFT_LOCKED ? SHIFT_LOCKED
            : Editing.shouldCapitalise(connection, info) ? SHIFT_ON : SHIFT_OFF;
        if (next == shift) {
            return;
        }
        shift = next;
        reface();
    }

    private void toggleShift() {
        long now = SystemClock.uptimeMillis();
        if (shift == SHIFT_ON && now - shiftPressedAt < DOUBLE_TAP_MS) {
            shift = SHIFT_LOCKED;
        } else if (shift == SHIFT_OFF) {
            shift = SHIFT_ON;
        } else {
            shift = SHIFT_OFF;
        }
        shiftPressedAt = now;
        reface();
    }

    private void reface() {
        for (KeyView view : keys) {
            view.showFace(shift != SHIFT_OFF);
            if (view.key().kind == Key.SHIFT) {
                dress(view);
            }
        }
    }

    /**
     * A tap on the phone's own motor.
     *
     * <p>Deliberately not forced past the phone's haptics setting: this device shares the phone's
     * hardware, and a keyboard inside a sandbox is not the thing that gets to overrule somebody who
     * has turned vibration off. The keyboard's own toggle sits in front of that, so either can
     * silence it and neither can override the other.
     */
    private void feedback(View view, boolean longPress) {
        if (!settings.haptics) {
            return;
        }
        view.performHapticFeedback(
            longPress ? HapticFeedbackConstants.LONG_PRESS : HapticFeedbackConstants.KEYBOARD_TAP);
    }

    // --- the preview --------------------------------------------------------------------------

    /**
     * The enlarged character above the finger.
     *
     * <p>Only for character keys, and never for a password field: a preview is a copy of what was
     * just typed, held on the screen — which is the one thing a password field exists to avoid, and
     * what would make a screen recording of somebody signing in worth having.
     */
    private void showPreview(KeyView view) {
        if (!settings.preview || view.key().kind != Key.CHARACTER || Layouts.isPassword(info)) {
            return;
        }
        preview.setText(view.face(shift != SHIFT_OFF));
        preview.setVisibility(VISIBLE);
        int width = Math.round(view.getWidth() * 1.15f);
        int height = Math.round(view.getHeight() * 1.05f);
        LayoutParams params = (LayoutParams) preview.getLayoutParams();
        params.width = width;
        params.height = height;
        params.leftMargin = Math.max(0, Math.round(offset(view, true) + (view.getWidth() - width) / 2f));
        params.topMargin = Math.max(0, Math.round(offset(view, false) - height * 1.05f));
        preview.setLayoutParams(params);
    }

    private void hidePreview() {
        preview.setVisibility(GONE);
    }

    /** Where a key sits inside this view, walked up the tree rather than asked of the window. */
    private float offset(View view, boolean horizontal) {
        float at = 0f;
        View node = view;
        while (node != null && node != this) {
            at += horizontal ? node.getX() : node.getY();
            ViewGroup parent = node.getParent() instanceof ViewGroup ? (ViewGroup) node.getParent() : null;
            node = parent;
        }
        return at;
    }
}

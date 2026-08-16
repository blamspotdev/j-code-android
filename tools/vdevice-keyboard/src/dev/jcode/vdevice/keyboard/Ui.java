package dev.jcode.vdevice.keyboard;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look of the device's keyboard, sharing the palette its Settings app uses.
 *
 * <p>One device should look like one device. These are the same three surfaces at increasing
 * lightness the Settings app is built from, so a keyboard opening over an app reads as part of the
 * same machine rather than as something that has landed on it.
 *
 * <p>Everything here is a {@link GradientDrawable} or a {@link RippleDrawable} built in code and
 * sized from {@link #dp}. The only resources this app has are its icons, which is what keeps it
 * buildable with plain {@code javac} and {@code aapt2}.
 */
final class Ui {

    static final int BACKGROUND = 0xFF0B0F14;
    static final int KEY = 0xFF1E2733;
    static final int MODIFIER = 0xFF151B24;
    static final int STRIP = 0xFF10161E;

    static final int TEXT = 0xFFE8ECF4;
    static final int MUTED = 0xFF97A2B6;
    static final int ACCENT = 0xFF8AB4F8;

    /** The action key, which is the one key that does something rather than typing something. */
    static final int ACTION = 0xFF2563EB;

    /** Shift while it is locked — the one piece of state a keyboard has that is easy to lose track of. */
    static final int LOCKED = 0xFF0EA5A4;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    /**
     * A key's background: a ripple over a rounded fill.
     *
     * <p>Both halves earn their place. The fill is what makes a key a key at rest, and the ripple is
     * the only thing that answers a finger on a surface with no travel — a keyboard that does not
     * flash under a press feels broken long before anybody checks whether the character arrived.
     */
    static Drawable key(Context context, int colour) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(colour);
        fill.setCornerRadius(dp(context, 7));
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(dp(context, 7));
        return new RippleDrawable(ColorStateList.valueOf(0x668AB4F8), fill, mask);
    }

    static Drawable rounded(Context context, int colour, float radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(dp(context, radius));
        return shape;
    }

    static TextView text(Context context, String value, float size, int colour) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        return view;
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    // --- the settings screen ----------------------------------------------------------------------

    /** A group of rows on a raised, rounded surface, under a small label. */
    static LinearLayout card(Context context, LinearLayout parent, String label) {
        TextView heading = text(context, label, 12f, ACCENT);
        heading.setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 8));
        parent.addView(heading, wrap());
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(context, MODIFIER, 18f));
        card.setPadding(dp(context, 12), dp(context, 10), dp(context, 12), dp(context, 12));
        LinearLayout.LayoutParams params = wrap();
        params.setMargins(dp(context, 14), 0, dp(context, 14), dp(context, 6));
        parent.addView(card, params);
        return card;
    }

    /**
     * A row of exclusive choices as chips.
     *
     * <p>Chips rather than a spinner: there are never more than three of these, and three chips show
     * both what is chosen and what else there is, which a closed dropdown shows neither of.
     */
    static void chips(
        Context context,
        LinearLayout card,
        String[] options,
        String chosen,
        Chosen onChoose
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        for (String option : options) {
            boolean on = option.equals(chosen);
            TextView chip = text(context, option, 13f, on ? BACKGROUND : TEXT);
            chip.setGravity(android.view.Gravity.CENTER);
            chip.setPadding(0, dp(context, 11), 0, dp(context, 11));
            chip.setBackground(rounded(context, on ? ACCENT : KEY, 10f));
            chip.setContentDescription(option);
            chip.setClickable(true);
            chip.setOnClickListener(v -> onChoose.run(option));
            LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            params.setMargins(dp(context, 3), 0, dp(context, 3), 0);
            row.addView(chip, params);
        }
        card.addView(row, wrap());
    }

    /** A labelled on/off row, with the switch on the right where every settings screen puts it. */
    static void toggle(Context context, LinearLayout card, String title, String detail, boolean on, Chosen onChange) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(context, 6), dp(context, 10), dp(context, 6), dp(context, 10));
        row.setContentDescription(title);

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(context, title, 15f, TEXT), wrap());
        TextView note = text(context, detail, 12f, MUTED);
        note.setPadding(0, dp(context, 2), 0, 0);
        labels.addView(note, wrap());
        row.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch control = new android.widget.Switch(context);
        control.setChecked(on);
        control.setContentDescription(title);
        control.setOnCheckedChangeListener((v, checked) -> onChange.run(String.valueOf(checked)));
        row.addView(control, new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row, wrap());
    }

    /** A paragraph that is explaining rather than listing. */
    static void note(Context context, LinearLayout parent, String message) {
        TextView view = text(context, message, 12f, MUTED);
        view.setPadding(dp(context, 22), dp(context, 12), dp(context, 22), dp(context, 20));
        view.setLineSpacing(dp(context, 3), 1f);
        parent.addView(view, wrap());
    }

    /** What a chip or a toggle answers with. */
    interface Chosen {
        void run(String value);
    }
}

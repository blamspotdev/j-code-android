package dev.blamspot.jcode.vdevice.settings;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * The look of the device's own apps, in one place.
 *
 * <p>These apps had no resources at all — a packaging constraint, so that plain `javac` and `aapt2`
 * can build them without a Gradle project — and that had quietly become an excuse. Everything was a
 * flat `LinearLayout` of `TextView`s on one background colour, which is what a settings screen looks
 * like before anybody has designed it.
 *
 * <p>The constraint turns out not to have been the problem. `aapt2` compiles a `res/` directory
 * perfectly well on its own, so the icons are real vector drawables now; and everything else here —
 * rounded surfaces, tinted icon chips, ripples — is a `GradientDrawable` or a `RippleDrawable` built
 * in code, which needs no resources of any kind.
 *
 * <h2>What the design is</h2>
 *
 * <p>Three surfaces at increasing lightness ({@link #BACKGROUND}, {@link #SURFACE}, {@link #CHIP}),
 * so depth comes from tone rather than from drop shadows a dark theme cannot show. Rows are grouped
 * into rounded cards with a section label above, because a list of eleven identical rows is a list
 * nobody scans. Each row gets a **tinted icon chip** — the glyph in white on a colour that belongs to
 * that setting — which is the thing that makes a settings screen readable at a glance, and the thing
 * these apps most obviously lacked.
 *
 * <p>Everything is sized from {@link #dp} rather than in raw pixels. The old code passed
 * {@code setPadding(36, 32, 36, 24)} — pixel values that happened to look right on one device and
 * would be half the size on a tablet.
 */
final class Ui {

    // --- colour ----------------------------------------------------------------------------------

    static final int BACKGROUND = 0xFF0B0F14;
    static final int SURFACE = 0xFF151B24;
    static final int CHIP = 0xFF1E2733;
    static final int DIVIDER = 0xFF232C3A;

    static final int TEXT = 0xFFE8ECF4;
    static final int MUTED = 0xFF97A2B6;
    static final int ACCENT = 0xFF8AB4F8;
    static final int WARNING = 0xFFE6A23C;

    /** One colour per kind of setting, so a chip is recognisable before its label is read. */
    static final int TINT_NETWORK = 0xFF2563EB;
    static final int TINT_PRIVACY = 0xFF9333EA;
    static final int TINT_MOTION = 0xFF0EA5A4;
    static final int TINT_APPS = 0xFFF59E0B;
    static final int TINT_STORAGE = 0xFF64748B;

    private Ui() {
    }

    static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    // --- building blocks -------------------------------------------------------------------------

    /** The page: one scrolling column on the darkest surface. */
    static LinearLayout page(Context context) {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setBackgroundColor(BACKGROUND);
        return column;
    }

    /**
     * A group of rows on a raised, rounded surface, under a small label.
     *
     * <p>The label is the part that does the work: it is what turns eleven rows into three things
     * somebody can look for.
     */
    static LinearLayout card(Context context, LinearLayout parent, String label) {
        if (label != null) {
            TextView heading = text(context, label, 12f, ACCENT);
            heading.setPadding(dp(context, 22), dp(context, 18), dp(context, 22), dp(context, 8));
            parent.addView(heading, wrap());
        }
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(SURFACE, dp(context, 18)));
        card.setPadding(0, dp(context, 4), 0, dp(context, 4));
        LinearLayout.LayoutParams params = wrap();
        params.setMargins(dp(context, 14), 0, dp(context, 14), dp(context, 6));
        parent.addView(card, params);
        return card;
    }

    /**
     * One row: a tinted icon chip, a title with an optional subtitle, and a value on the right.
     *
     * <p>A row with an {@code onClick} gets a ripple; one without does not, so a line that only
     * reports something does not look like a control that has stopped working.
     */
    static View row(
        Context context,
        int iconRes,
        int tint,
        String title,
        String value,
        String detail,
        Runnable onClick
    ) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        // Set here rather than left to the caller. A LinearLayout child with no params defaults to
        // WRAP_CONTENT, and this row has a weighted column in it — which in a wrap-width row is
        // given **zero** width, so the title vanishes and the subtitle wraps one character per line
        // into a card several hundred pixels tall. Measured, on the first run of this design.
        row.setLayoutParams(wrap());
        row.setPadding(dp(context, 14), dp(context, 12), dp(context, 16), dp(context, 12));
        row.setContentDescription(title);
        if (onClick != null) {
            row.setBackground(ripple());
            row.setClickable(true);
            row.setOnClickListener(v -> onClick.run());
        }

        if (iconRes != 0) {
            row.addView(chip(context, iconRes, tint));
        }

        LinearLayout labels = new LinearLayout(context);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(context, title, 15f, TEXT), wrap());
        if (detail != null) {
            TextView note = text(context, detail, 12f, MUTED);
            note.setPadding(0, dp(context, 2), 0, 0);
            labels.addView(note, wrap());
        }
        LinearLayout.LayoutParams grow =
            new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        grow.setMargins(iconRes == 0 ? 0 : dp(context, 14), 0, dp(context, 10), 0);
        row.addView(labels, grow);

        if (value != null) {
            // WRAP_CONTENT, not wrap(): wrap() is MATCH_PARENT wide, which in a horizontal row would
            // give the value everything the labels did not take.
            row.addView(text(context, value, 13f, ACCENT), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        return row;
    }

    /** The rounded, tinted square a row's glyph sits in. */
    static View chip(Context context, int iconRes, int tint) {
        ImageView icon = new ImageView(context);
        icon.setImageResource(iconRes);
        icon.setImageTintList(ColorStateList.valueOf(Color.WHITE));
        int size = dp(context, 36);
        int inset = dp(context, 8);
        icon.setPadding(inset, inset, inset, inset);
        icon.setBackground(rounded(tint, dp(context, 11)));
        icon.setLayoutParams(new LinearLayout.LayoutParams(size, size));
        return icon;
    }

    /** A hairline between rows, inset past the chip so it reads as a list rather than a table. */
    static void divider(Context context, LinearLayout card) {
        View line = new View(context);
        line.setBackgroundColor(DIVIDER);
        LinearLayout.LayoutParams params =
            new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(context, 0.5f)));
        params.setMargins(dp(context, 64), 0, 0, 0);
        card.addView(line, params);
    }

    /** A paragraph that is explaining rather than listing. */
    static View note(Context context, String message, int colour) {
        TextView view = text(context, message, 12f, colour);
        view.setLayoutParams(wrap());
        view.setPadding(dp(context, 22), dp(context, 10), dp(context, 22), dp(context, 18));
        view.setLineSpacing(dp(context, 3), 1f);
        return view;
    }

    static TextView text(Context context, String value, float size, int colour) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextColor(colour);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        return view;
    }

    /** A filled, rounded rectangle — the one drawable this whole design needs. */
    static Drawable rounded(int colour, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(colour);
        shape.setCornerRadius(radius);
        return shape;
    }

    /** Press feedback. A settings row that does not answer a finger feels broken before it is read. */
    static Drawable ripple() {
        return new RippleDrawable(ColorStateList.valueOf(0x338AB4F8), null, new GradientDrawable() {
            {
                setColor(Color.WHITE);
            }
        });
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}

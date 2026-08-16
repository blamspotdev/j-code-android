package dev.jcode.vdevice.keyboard;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.TextView;

/**
 * One key on the screen.
 *
 * <p>A {@link TextView} rather than something drawn on a canvas, and that is the load-bearing
 * decision in this whole app. {@code uiautomator dump} walks the view tree and reports each view's
 * class, text, content description and bounds; a keyboard painted onto one big canvas would be a
 * single rectangle with nothing in it, which is precisely the state the phone's IME left an agent
 * in. As real views, every key is addressable — {@code text="a"}, {@code content-desc="Backspace"},
 * {@code resource-id="dev.jcode.vdevice.keyboard:id/key_shift"} — and lands where the dump says it
 * does, because the same bounds are what the container hit-tests a tap against.
 *
 * <p>Never focusable, which is not a detail: the field being typed into has to keep the focus for
 * its {@code InputConnection} to stay the live one, and a key that took focus would end the input it
 * was in the middle of.
 *
 * <p>Icons are drawn in {@link #onDraw} rather than set as a compound drawable, which would put the
 * glyph beside the (empty) text and leave the key looking off-centre.
 */
final class KeyView extends TextView {

    /** How much of the key's smaller dimension an icon takes; the rest is the key's own padding. */
    private static final float GLYPH_FRACTION = 0.42f;

    private final Key key;
    private Drawable glyph;

    KeyView(Context context, Key key) {
        super(context);
        this.key = key;
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setTextColor(Ui.TEXT);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, key.kind == Key.CHARACTER ? 18f : 14f);
        setContentDescription(key.description);
        setClickable(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        if (key.id != 0) {
            setId(key.id);
        }
        if (key.icon != 0) {
            glyph = icon(context, key.icon);
        } else if (key.label != null) {
            // Every key that is not a glyph shows its own label from the start. Only character keys
            // are redrawn by showFace, so a page key ("?123", "ABC") would otherwise be blank.
            setText(key.label);
        }
        setBackground(Ui.key(context, key.modifier ? Ui.MODIFIER : Ui.KEY));
    }

    Key key() {
        return key;
    }

    /** The face this key is currently showing, which is what a press types. */
    String face(boolean shifted) {
        if (key.kind != Key.CHARACTER) {
            return key.label;
        }
        return shifted && key.shifted != null ? key.shifted : key.label;
    }

    /**
     * Redraws a character key for the current shift state.
     *
     * <p>The content description follows the label. An agent looking for the {@code A} key after
     * pressing shift should find {@code A}, not the {@code a} that is no longer written on it.
     */
    void showFace(boolean shifted) {
        if (key.kind != Key.CHARACTER) {
            return;
        }
        String face = face(shifted);
        setText(face);
        setContentDescription(face);
    }

    void showIcon(int iconRes, String description) {
        glyph = icon(getContext(), iconRes);
        setText(null);
        setContentDescription(description);
        invalidate();
    }

    /** Replaces an icon key's glyph with a word — the action key, once the field has said what it is. */
    void showLabel(String label, String description) {
        glyph = null;
        setText(label);
        setContentDescription(description);
        invalidate();
    }

    void tint(int colour) {
        setBackground(Ui.key(getContext(), colour));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (glyph == null) {
            return;
        }
        int size = Math.round(Math.min(getWidth(), getHeight()) * GLYPH_FRACTION);
        int left = (getWidth() - size) / 2;
        int top = (getHeight() - size) / 2;
        glyph.setBounds(left, top, left + size, top + size);
        glyph.draw(canvas);
    }

    /** Mutated, because a shared constant-state drawable would tint every key that uses that icon. */
    private static Drawable icon(Context context, int iconRes) {
        Drawable drawable = context.getDrawable(iconRes);
        if (drawable == null) {
            return null;
        }
        drawable = drawable.mutate();
        drawable.setTint(Ui.TEXT);
        return drawable;
    }
}

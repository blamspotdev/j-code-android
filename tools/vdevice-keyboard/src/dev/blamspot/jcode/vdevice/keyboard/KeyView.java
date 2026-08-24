package dev.blamspot.jcode.vdevice.keyboard;

import android.content.Context;
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
 * {@code resource-id="dev.blamspot.jcode.vdevice.keyboard:id/key_shift"} — and lands where the dump says it
 * does, because the same bounds are what the container hit-tests a tap against.
 *
 * <p>Never focusable, which is not a detail: the field being typed into has to keep the focus for
 * its {@code InputConnection} to stay the live one, and a key that took focus would end the input it
 * was in the middle of.
 *
 * <p>A key that wears a mark instead of a word wears it as <em>text</em>, in the bundled symbol font
 * — see {@link Ui#symbolFont}. It was a vector drawn onto the key's own canvas, and that does not
 * work here: {@link TextView#onDraw} clips the canvas to the space it lays text out in, and a key
 * with no text lays out nothing to clip to, so shift, backspace and hide came out blank while their
 * backgrounds, their bounds and their taps were all perfectly fine. As text they are laid out,
 * centred, scaled and tinted by the code that already does all four for the letters.
 */
final class KeyView extends TextView {

    private final Key key;

    KeyView(Context context, Key key) {
        super(context);
        this.key = key;
        setGravity(Gravity.CENTER);
        setSingleLine(true);
        setTextColor(Ui.TEXT);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, faceSize(key));
        setContentDescription(key.description);
        setClickable(true);
        setFocusable(false);
        setFocusableInTouchMode(false);
        if (key.id != 0) {
            setId(key.id);
        }
        if (key.symbol != null) {
            setTypeface(Ui.symbolFont(context));
            setText(key.symbol);
        } else if (key.label != null) {
            // Every key that is not a mark shows its own label from the start. Only character keys
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

    /** Swaps one mark for another — shift, as it goes off, on and locked. */
    void showSymbol(String symbol, String description) {
        setTypeface(Ui.symbolFont(getContext()));
        setTextSize(TypedValue.COMPLEX_UNIT_SP, SYMBOL_SP);
        setText(symbol);
        setContentDescription(description);
    }

    /** Replaces a mark with a word — the action key, once the field has said what it is. */
    void showLabel(String label, String description) {
        setTypeface(null);
        setTextSize(TypedValue.COMPLEX_UNIT_SP, LABEL_SP);
        setText(label);
        setContentDescription(description);
    }

    void tint(int colour) {
        setBackground(Ui.key(getContext(), colour));
    }

    /**
     * A mark is drawn bigger than a word, because it has to carry a key on its own.
     *
     * <p>"?123" and "Go" are read; ⇧ and ⌫ are recognised, and recognising a shape wants more of the
     * key than reading four characters does.
     */
    private static float faceSize(Key key) {
        if (key.symbol != null) {
            return SYMBOL_SP;
        }
        return key.kind == Key.CHARACTER ? 18f : LABEL_SP;
    }

    private static final float SYMBOL_SP = 22f;
    private static final float LABEL_SP = 14f;
}

package dev.blamspot.jcode.vdevice.keyboard;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Which keys a field gets.
 *
 * <p>A keyboard that shows the same keys to every field is a keyboard that has not read the field.
 * {@link EditorInfo#inputType} says what the app is asking for and the platform's own IMEs act on
 * it, so this does too: a number field gets a keypad rather than a row of digits to hunt for, an
 * email field gets {@code @} without a trip to the symbols page, and a URI field gets {@code /}.
 *
 * <p>The tables are written as the rows they draw. That is the whole reason {@link Key} carries a
 * kind instead of a behaviour: every letter below is one call, so the shape of the keyboard is
 * visible in the shape of the code.
 */
final class Layouts {

    static final int LETTERS = 0;
    static final int SYMBOLS = 1;
    static final int MORE = 2;
    static final int NUMBER = 3;
    static final int PHONE = 4;

    static final String QWERTY = "QWERTY";
    static final String QWERTZ = "QWERTZ";
    static final String AZERTY = "AZERTY";

    /** The three arrangements, as the three rows of letters each of them is. */
    private static final String[][] ARRANGEMENTS = {
        {"qwertyuiop", "asdfghjkl", "zxcvbnm"},
        {"qwertzuiop", "asdfghjkl", "yxcvbnm"},
        {"azertyuiop", "qsdfghjklm", "wxcvbn"},
    };

    /**
     * The accent a letter's long press types.
     *
     * <p>One table rather than one per arrangement: which letter carries {@code é} does not change
     * when the rows are reordered, and a table per layout is three places to forget.
     */
    private static final String ACCENTED = "aàeéiîoôuùcçnñsßyÿ";

    private Layouts() {
    }

    static String[] arrangement(String layout) {
        if (QWERTZ.equals(layout)) {
            return ARRANGEMENTS[1];
        }
        if (AZERTY.equals(layout)) {
            return ARRANGEMENTS[2];
        }
        return ARRANGEMENTS[0];
    }

    /** The page a field opens on, which for anything numeric is the only page it has. */
    static int pageFor(EditorInfo info) {
        int type = info == null ? InputType.TYPE_CLASS_TEXT : info.inputType;
        switch (type & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_PHONE:
                return PHONE;
            case InputType.TYPE_CLASS_NUMBER:
            case InputType.TYPE_CLASS_DATETIME:
                return NUMBER;
            default:
                return LETTERS;
        }
    }

    /** True for the pages that have no letters, so the keyboard does not offer a way off them. */
    static boolean isNumeric(int page) {
        return page == NUMBER || page == PHONE;
    }

    static List<List<Key>> rows(int page, EditorInfo info, String layout) {
        switch (page) {
            case SYMBOLS:
                return symbols(info);
            case MORE:
                return more(info);
            case NUMBER:
                return number();
            case PHONE:
                return phone();
            default:
                return letters(info, layout);
        }
    }

    /**
     * The letters, with the digits on the top row's long press exactly where a phone puts them.
     *
     * <p>The accented letters are there for the same reason and matter more than they look: a device
     * that can only type ASCII is a device half the world cannot type its own name on, and the
     * container's old path — replaying text as {@code KeyCharacterMap} events — could not have
     * delivered one of these characters however it was asked to.
     */
    private static List<List<Key>> letters(EditorInfo info, String layout) {
        String[] arrangement = arrangement(layout);
        List<List<Key>> rows = new ArrayList<>();
        rows.add(row(arrangement[0], "1234567890"));
        rows.add(row(arrangement[1], null));
        List<Key> last = new ArrayList<>();
        last.add(Key.shift());
        last.addAll(row(arrangement[2], null));
        last.add(Key.backspace());
        rows.add(last);
        rows.add(bottom(Key.page("?123", SYMBOLS), info));
        return rows;
    }

    /** One row of letters. [digits], when given, is what each key's long press types instead. */
    private static List<Key> row(String letters, String digits) {
        List<Key> row = new ArrayList<>(letters.length());
        for (int at = 0; at < letters.length(); at++) {
            String letter = letters.substring(at, at + 1);
            String alternate = digits != null && at < digits.length()
                ? digits.substring(at, at + 1)
                : accent(letter);
            row.add(Key.letter(letter, alternate));
        }
        return row;
    }

    private static String accent(String letter) {
        int at = ACCENTED.indexOf(letter);
        return at < 0 || at % 2 != 0 ? null : ACCENTED.substring(at + 1, at + 2);
    }

    private static List<List<Key>> symbols(EditorInfo info) {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(
            Key.symbol("1", null), Key.symbol("2", null), Key.symbol("3", null),
            Key.symbol("4", null), Key.symbol("5", null), Key.symbol("6", null),
            Key.symbol("7", null), Key.symbol("8", null), Key.symbol("9", null),
            Key.symbol("0", null)));
        rows.add(Arrays.asList(
            Key.symbol("@", null), Key.symbol("#", null), Key.symbol("$", "€"),
            Key.symbol("_", null), Key.symbol("&", null), Key.symbol("-", "—"),
            Key.symbol("+", "±"), Key.symbol("(", "["), Key.symbol(")", "]"),
            Key.symbol("/", "\\")));
        rows.add(Arrays.asList(
            Key.page("=\\<", MORE),
            Key.symbol("*", null), Key.symbol("\"", null), Key.symbol("'", null),
            Key.symbol(":", null), Key.symbol(";", null), Key.symbol("!", null),
            Key.symbol("?", "¿"),
            Key.backspace()));
        rows.add(bottom(Key.page("ABC", LETTERS), info));
        return rows;
    }

    private static List<List<Key>> more(EditorInfo info) {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(
            Key.symbol("~", null), Key.symbol("`", null), Key.symbol("|", null),
            Key.symbol("•", null), Key.symbol("√", null), Key.symbol("π", null),
            Key.symbol("÷", null), Key.symbol("×", null), Key.symbol("¶", null),
            Key.symbol("Δ", null)));
        rows.add(Arrays.asList(
            Key.symbol("£", null), Key.symbol("¢", null), Key.symbol("€", null),
            Key.symbol("¥", null), Key.symbol("^", null), Key.symbol("°", null),
            Key.symbol("=", null), Key.symbol("{", null), Key.symbol("}", null),
            Key.symbol("%", null)));
        rows.add(Arrays.asList(
            Key.page("?123", SYMBOLS),
            Key.symbol("\\", null), Key.symbol("©", null), Key.symbol("®", null),
            Key.symbol("™", null), Key.symbol("<", null), Key.symbol(">", null),
            Key.symbol("₽", null),
            Key.backspace()));
        rows.add(bottom(Key.page("ABC", LETTERS), info));
        return rows;
    }

    /**
     * The bottom row, which is the one row a field gets to change.
     *
     * <p>An email address is mostly {@code @} and {@code .} and a URL is mostly {@code /} and
     * {@code .com}, and a keyboard that makes you go to the symbols page for them has understood the
     * field and then done nothing about it.
     */
    private static List<Key> bottom(Key page, EditorInfo info) {
        int variation = info == null ? 0 : (info.inputType & InputType.TYPE_MASK_VARIATION);
        List<Key> row = new ArrayList<>();
        row.add(page);
        row.add(Key.hide());
        if (variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            || variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS) {
            row.add(Key.wide("@", 1f));
            row.add(Key.space().weighted(3f));
            row.add(Key.wide(".", 1f));
        } else if (variation == InputType.TYPE_TEXT_VARIATION_URI) {
            row.add(Key.wide("/", 1f));
            row.add(Key.space().weighted(3f));
            row.add(Key.wide(".com", 1.2f));
        } else {
            row.add(Key.wide(",", 1f));
            row.add(Key.space().weighted(3.2f));
            row.add(Key.wide(".", 1f));
        }
        row.add(Key.action());
        return row;
    }

    /** A keypad, laid out as one: four rows of four, with the digits where a phone's are. */
    private static List<List<Key>> number() {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(Key.symbol("1", null), Key.symbol("2", null), Key.symbol("3", null), Key.backspace()));
        rows.add(Arrays.asList(Key.symbol("4", null), Key.symbol("5", null), Key.symbol("6", null), Key.hide()));
        rows.add(Arrays.asList(Key.symbol("7", null), Key.symbol("8", null), Key.symbol("9", null), Key.symbol("-", null)));
        rows.add(Arrays.asList(Key.symbol(",", null), Key.symbol("0", null), Key.symbol(".", null), Key.action()));
        return rows;
    }

    private static List<List<Key>> phone() {
        List<List<Key>> rows = new ArrayList<>();
        rows.add(Arrays.asList(Key.symbol("1", null), Key.symbol("2", null), Key.symbol("3", null), Key.backspace()));
        rows.add(Arrays.asList(Key.symbol("4", null), Key.symbol("5", null), Key.symbol("6", null), Key.hide()));
        rows.add(Arrays.asList(Key.symbol("7", null), Key.symbol("8", null), Key.symbol("9", null), Key.symbol("+", null)));
        rows.add(Arrays.asList(Key.symbol("*", null), Key.symbol("0", null), Key.symbol("#", null), Key.action()));
        return rows;
    }

    /**
     * What the action key says, from {@link EditorInfo#imeOptions} — the label the app asked for,
     * its own {@code actionLabel} first, since an app that supplied one has said what it wants the
     * key to read.
     */
    static String actionLabel(EditorInfo info) {
        if (info == null) {
            return null;
        }
        if (info.actionLabel != null && info.actionLabel.length() > 0) {
            return info.actionLabel.toString();
        }
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_GO:
                return "Go";
            case EditorInfo.IME_ACTION_SEARCH:
                return "Search";
            case EditorInfo.IME_ACTION_SEND:
                return "Send";
            case EditorInfo.IME_ACTION_NEXT:
                return "Next";
            case EditorInfo.IME_ACTION_DONE:
                return "Done";
            case EditorInfo.IME_ACTION_PREVIOUS:
                return "Back";
            default:
                return null;
        }
    }

    /** What the strip above the keys calls this field, or null when there is nothing worth saying. */
    static String fieldLabel(EditorInfo info) {
        if (info == null) {
            return null;
        }
        int type = info.inputType;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        switch (type & InputType.TYPE_MASK_CLASS) {
            case InputType.TYPE_CLASS_PHONE:
                return "Phone number";
            case InputType.TYPE_CLASS_DATETIME:
                return "Date or time";
            case InputType.TYPE_CLASS_NUMBER:
                return variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD ? "PIN" : "Number";
            default:
                break;
        }
        switch (variation) {
            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
                return "Password";
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                return "Password (visible)";
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                return "Email address";
            case InputType.TYPE_TEXT_VARIATION_URI:
                return "Web address";
            default:
                return info.hintText == null ? null : info.hintText.toString();
        }
    }

    /**
     * Whether the key preview has to stay down.
     *
     * <p>A preview enlarges the character just typed above the finger, which is exactly what a
     * password field exists to prevent — and it is what makes shoulder-surfing a screen recording
     * trivial. Every platform IME suppresses it here and so does this one.
     */
    static boolean isPassword(EditorInfo info) {
        if (info == null) {
            return false;
        }
        int variation = info.inputType & InputType.TYPE_MASK_VARIATION;
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
            || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
            || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            || variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }
}

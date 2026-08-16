package dev.jcode.vdevice.keyboard;

/**
 * One key.
 *
 * <p>A key is a value and a job, and separating the two is what keeps the layouts readable: every
 * character key is {@link #CHARACTER} and differs only in what it types, so the tables in {@link
 * Layouts} read as the rows they draw rather than as code.
 *
 * <p>{@link #description} is not decoration. It is what {@code uiautomator dump} reports as
 * {@code content-desc}, which is how an agent driving this device finds a key by name instead of
 * guessing at coordinates in a screenshot — so every key has one, including the ones whose label
 * already says what they are.
 */
final class Key {

    /** Types {@link #label} (or {@link #shifted} while shift is on). */
    static final int CHARACTER = 0;
    /** Off, on for one character, or locked — see {@link KeyboardView}. */
    static final int SHIFT = 1;
    /** Deletes the selection, or the character before the cursor. */
    static final int BACKSPACE = 2;
    /** The editor's action — Go, Search, Send, Next, Done — or a newline. */
    static final int ACTION = 3;
    /** A space, and the one key wide enough to be worth its own kind. */
    static final int SPACE = 4;
    /** Switches to another page of the same keyboard. */
    static final int PAGE = 5;
    /** Puts the keyboard away without leaving the field. */
    static final int HIDE = 6;

    final int kind;

    /** What a character key types, and what every key shows unless it has an {@link #icon}. */
    final String label;

    /** The upper-case form, when it differs from {@link #label}. Null for a key shift does nothing to. */
    final String shifted;

    /** What a long press types — the digits above QWERTY's top row, and the accents under its vowels. */
    final String alternate;

    /** Which page a {@link #PAGE} key goes to; ignored otherwise. */
    final int page;

    /** A vector drawable drawn instead of a label, for the keys a word would be too big for. */
    final int icon;

    /** A declared id, so {@code uiautomator dump} reports the key as `pkg:id/name`. 0 for characters. */
    final int id;

    /** What this key is called, in {@code content-desc}. */
    final String description;

    /** How much of its row this key takes, relative to its neighbours. */
    final float weight;

    /** Whether this is one of the darker keys — shift, backspace, page and action, as on a phone. */
    final boolean modifier;

    private Key(
        int kind,
        String label,
        String shifted,
        String alternate,
        int page,
        int icon,
        int id,
        String description,
        float weight,
        boolean modifier
    ) {
        this.kind = kind;
        this.label = label;
        this.shifted = shifted;
        this.alternate = alternate;
        this.page = page;
        this.icon = icon;
        this.id = id;
        this.description = description;
        this.weight = weight;
        this.modifier = modifier;
    }

    /** A letter, with the digit or symbol its long press types. */
    static Key letter(String lower, String alternate) {
        return new Key(CHARACTER, lower, lower.toUpperCase(), alternate, 0, 0, 0, lower, 1f, false);
    }

    /** A key whose two faces are unrelated — the symbol pages, where shift is a different character. */
    static Key symbol(String label, String alternate) {
        return new Key(CHARACTER, label, null, alternate, 0, 0, 0, label, 1f, false);
    }

    static Key wide(String label, float weight) {
        return new Key(CHARACTER, label, null, null, 0, 0, 0, label, weight, false);
    }

    static Key shift() {
        return new Key(SHIFT, null, null, null, 0, R.drawable.ic_shift, R.id.key_shift, "Shift", 1.5f, true);
    }

    static Key backspace() {
        return new Key(
            BACKSPACE, null, null, null, 0,
            R.drawable.ic_backspace, R.id.key_backspace, "Backspace", 1.5f, true);
    }

    static Key action() {
        return new Key(ACTION, null, null, null, 0, R.drawable.ic_enter, R.id.key_action, "Enter", 1.75f, true);
    }

    static Key space() {
        return new Key(SPACE, null, null, null, 0, 0, R.id.key_space, "Space", 4f, false);
    }

    static Key page(String label, int page) {
        return new Key(PAGE, label, null, null, page, 0, R.id.key_page, label, 1.5f, true);
    }

    static Key hide() {
        return new Key(HIDE, null, null, null, 0, R.drawable.ic_hide, R.id.key_hide, "Hide keyboard", 1f, true);
    }

    /** The same key, taking more or less of its row. */
    Key weighted(float weight) {
        return new Key(kind, label, shifted, alternate, page, icon, id, description, weight, modifier);
    }
}

package dev.blamspot.jcode.vdevice.keyboard;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

/**
 * What a key press does to the field, through the field's own {@link InputConnection}.
 *
 * <p>This is the whole reason the keyboard can be an ordinary app. An {@code InputConnection} is the
 * platform's editing contract — the same object {@code TextView} hands a real IME — so committing
 * text, deleting it and firing the editor's action are the framework's own code paths, running
 * inside the guest that owns the field. Nothing here re-implements editing, which is what a keyboard
 * that typed by synthesising key events was doing badly: {@code KeyCharacterMap} has no key for
 * {@code é}, so it dropped it, and it had no way at all to fire {@code IME_ACTION_SEARCH}.
 */
final class Editing {

    private Editing() {
    }

    static void type(InputConnection connection, String text) {
        if (connection == null || text == null || text.isEmpty()) {
            return;
        }
        connection.commitText(text, 1);
    }

    /**
     * Backspace.
     *
     * <p>A selection is replaced rather than shortened — deleting one character behind a highlighted
     * word is not what any keyboard does — and the fall-back deletes a whole code point, so one press
     * removes one emoji rather than half of a surrogate pair.
     */
    static void backspace(InputConnection connection) {
        if (connection == null) {
            return;
        }
        CharSequence selected = connection.getSelectedText(0);
        if (selected != null && selected.length() > 0) {
            connection.commitText("", 1);
            return;
        }
        connection.deleteSurroundingTextInCodePoints(1, 0);
    }

    /**
     * The action key.
     *
     * <p>Three outcomes, in the order the platform decides them: the action the app asked for, a
     * newline in a field that takes them, or a plain Enter for the app to make of what it will.
     * Returns true when it was handled here, and false when the caller has to send the key.
     */
    static boolean action(InputConnection connection, EditorInfo info) {
        if (connection == null) {
            return false;
        }
        int options = info == null ? 0 : info.imeOptions;
        int action = options & EditorInfo.IME_MASK_ACTION;
        boolean refused = (options & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!refused && action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            connection.performEditorAction(action);
            return true;
        }
        if (multiLine(info)) {
            connection.commitText("\n", 1);
            return true;
        }
        return false;
    }

    static boolean multiLine(EditorInfo info) {
        if (info == null) {
            return false;
        }
        return (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0
            || (info.inputType & InputType.TYPE_TEXT_FLAG_IME_MULTI_LINE) != 0;
    }

    /**
     * Whether the next character should be typed capital, asked of the field rather than tracked
     * here.
     *
     * <p>{@code getCursorCapsMode} is what the platform's own IMEs use: it reads the text either side
     * of the cursor and applies the field's {@code TYPE_TEXT_FLAG_CAP_*} flags. Working it out from
     * what this keyboard has typed would be wrong the moment the app changes the text itself, which
     * every form that formats as you type does.
     */
    static boolean shouldCapitalise(InputConnection connection, EditorInfo info) {
        if (connection == null || info == null) {
            return false;
        }
        int wanted = info.inputType & (InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            | InputType.TYPE_TEXT_FLAG_CAP_WORDS
            | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (wanted == 0) {
            return false;
        }
        return connection.getCursorCapsMode(info.inputType) != 0;
    }

    /** True for a field whose every character is capital, where shift has nothing left to decide. */
    static boolean allCaps(EditorInfo info) {
        return info != null && (info.inputType & InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS) != 0;
    }
}

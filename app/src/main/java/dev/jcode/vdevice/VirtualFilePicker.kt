package dev.jcode.vdevice

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

/** What a picker was opened to do, and so what it hands back. */
internal enum class PickerMode { OpenFile, CreateFile, OpenTree }

/**
 * The virtual device's own file picker — the screen a guest gets when it asks for a document.
 *
 * Before this, `ACTION_OPEN_DOCUMENT` from a guest went out to the real system and **the phone's**
 * picker opened over JCode, offering a sandboxed app the user's own downloads, screenshots and
 * cloud accounts; and because an embedded activity's token is one no `ActivityRecord` answers to,
 * whatever was chosen was then delivered nowhere. Measured on WaveRepo: tapping "Open a bank"
 * listed the user's files and picking one did nothing at all, for ever.
 *
 * ### It is device content, not IDE chrome
 *
 * A real `View`, added to [EmbeddedGuest]'s container as its topmost child, exactly like
 * [VirtualStatusBar] and for the same reasons. That one decision is what makes it usable by
 * something that is not a pair of eyes:
 *
 * | | Falls out of being a child of the container |
 * |---|---|
 * | `screencap` shows it | `EmbeddedGuest.capture` draws the container |
 * | `uiautomator dump` lists every row | these are real `TextView`s with real text |
 * | `input tap` opens a folder and picks a file | the same `EmbeddedGuest.touch` a finger goes through |
 *
 * The alternative — composing it in the IDE over the tab, the way the permission prompt is done —
 * would have put a modal an agent can see on screen and cannot read or answer.
 *
 * Drawn in code rather than from a layout, because `:guest` inflating JCode's resources while a
 * guest's own resource table is installed is the class of bug the container spends most of its
 * effort avoiding.
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
internal class VirtualFilePicker(
    context: Context,
    private val mode: PickerMode,
    private val title: String,
    suggestedName: String,
    /** Null is a cancel — the same answer as `RESULT_CANCELED`, which apps are written to handle. */
    private val onDone: (String?) -> Unit,
) : LinearLayout(context) {

    private val root = VirtualStorage.root(context)
    private var here: File = root
    private val list = LinearLayout(context).apply { orientation = VERTICAL }
    private val path = text(size = 11f, colour = MUTED)
    private val name = EditText(context).takeIf { mode == PickerMode.CreateFile }?.apply {
        setText(suggestedName)
        setTextColor(FOREGROUND)
        setBackgroundColor(Color.argb(0xFF, 0x24, 0x28, 0x33))
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        setSingleLine()
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        contentDescription = "File name"
    }

    /** The chosen file, kept so Save/Open can be pressed after a row was tapped. */
    private var chosen: File? = null

    init {
        orientation = VERTICAL
        setBackgroundColor(SCRIM)
        // Nothing behind the picker may be touched while it is up, the way a modal on a phone
        // behaves — and without this a stray tap reaches the app underneath, which is still live.
        isClickable = true
        isFocusable = true

        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(CARD)
        }
        card.addView(header(), params(MATCH, WRAP))
        card.addView(
            ScrollView(context).apply { addView(list, params(MATCH, WRAP)) },
            LinearLayout.LayoutParams(MATCH, 0, 1f),
        )
        name?.let { card.addView(it, params(MATCH, WRAP).also { p -> p.setMargins(dp(12), 0, dp(12), 0) }) }
        card.addView(buttons(), params(MATCH, WRAP))

        addView(card, LinearLayout.LayoutParams(MATCH, 0, 1f).also { it.setMargins(dp(16), dp(24), dp(16), dp(24)) })
        show(root)
    }

    private fun header(): View = LinearLayout(context).apply {
        orientation = VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(10))
        addView(text(size = 15f, colour = FOREGROUND).apply { text = title }, params(MATCH, WRAP))
        addView(path, params(MATCH, WRAP))
    }

    private fun buttons(): View = LinearLayout(context).apply {
        orientation = HORIZONTAL
        gravity = Gravity.END
        setPadding(dp(8), dp(6), dp(8), dp(8))
        addView(button("Cancel", MUTED) { onDone(null) })
        addView(button(confirmLabel(), ACCENT) { confirm() })
    }

    /** Back, and the Cancel button: the app is told the person chose nothing. */
    fun cancel() = onDone(null)

    private fun confirmLabel(): String = when (mode) {
        PickerMode.OpenFile -> "Open"
        PickerMode.CreateFile -> "Save"
        PickerMode.OpenTree -> "Use this folder"
    }

    /**
     * Lists one directory. Every row is a real view with the file's name on it, so what a person
     * reads and what `uiautomator dump` reports are the same string in the same place.
     */
    private fun show(directory: File) {
        here = directory
        chosen = null
        path.text = VirtualStorage.devicePath(context, directory)
        list.removeAllViews()

        if (directory.canonicalPath != root.canonicalPath) {
            list.addView(row("..", "Up one folder", directory.parentFile ?: root, isUp = true))
        }
        val children = directory.listFiles().orEmpty()
            .sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name.lowercase() })
        if (children.isEmpty()) {
            list.addView(
                text(size = 13f, colour = MUTED).apply {
                    text = "This folder is empty"
                    setPadding(dp(16), dp(20), dp(16), dp(20))
                },
                params(MATCH, WRAP),
            )
        }
        children.forEach { child ->
            list.addView(row(child.name, describe(child), child, isUp = false))
        }
    }

    private fun describe(file: File): String = when {
        file.isDirectory -> "Folder"
        else -> formatBytes(file.length())
    }

    private fun row(label: String, detail: String, target: File, isUp: Boolean): View =
        LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(16), dp(11), dp(16), dp(11))
            isClickable = true
            contentDescription = label
            addView(text(size = 14f, colour = FOREGROUND).apply { text = label }, params(MATCH, WRAP))
            addView(text(size = 11f, colour = MUTED).apply { text = detail }, params(MATCH, WRAP))
            setOnClickListener {
                when {
                    isUp || target.isDirectory -> show(target)
                    // A file is chosen rather than accepted, so a mis-tap is one tap to undo and
                    // the confirm button stays the only thing that ends the picker.
                    else -> select(target)
                }
            }
        }

    private fun select(file: File) {
        chosen = file
        name?.setText(file.name)
        path.text = "Selected: ${file.name}"
    }

    private fun confirm() {
        val target = when (mode) {
            PickerMode.OpenTree -> here
            PickerMode.OpenFile -> chosen ?: return flash("Tap a file first")
            PickerMode.CreateFile -> {
                val typed = name?.text?.toString()?.trim().orEmpty()
                if (typed.isEmpty()) return flash("Type a file name")
                File(here, typed)
            }
        }
        onDone(VirtualStorage.devicePath(context, target))
    }

    /** Says why nothing happened, in the one place a person is already looking. */
    private fun flash(message: String) {
        path.text = message
    }

    private fun button(label: String, colour: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            text = label
            isAllCaps = false
            setTextColor(colour)
            setBackgroundColor(Color.TRANSPARENT)
            contentDescription = label
            setOnClickListener { onClick() }
        }

    private fun text(size: Float, colour: Int): TextView = TextView(context).apply {
        setTextColor(colour)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun params(width: Int, height: Int) = LinearLayout.LayoutParams(width, height)

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    internal companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        private val SCRIM = Color.argb(0xB8, 0x00, 0x00, 0x00)
        private val CARD = Color.argb(0xFF, 0x1B, 0x1E, 0x27)
        private val FOREGROUND = Color.argb(0xFF, 0xE6, 0xE8, 0xEF)
        private val MUTED = Color.argb(0xFF, 0x9A, 0xA0, 0xB0)
        private val ACCENT = Color.argb(0xFF, 0x8A, 0xB4, 0xF8)

        private fun formatBytes(bytes: Long): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"
            else -> "%.1f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
        }
    }
}

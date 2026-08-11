package dev.jcode.vdevice

import android.graphics.Rect
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Checkable
import android.widget.TextView
import java.io.File

/**
 * The running guest's view tree, in the shape `uiautomator dump` answers with.
 *
 * This is the device's answer to "what is on the screen?" for something that cannot look at it. A
 * screenshot says where things are but not what they are; this says both, so an agent driving the
 * device over adb can find a button by its resource id and tap its centre instead of guessing
 * coordinates out of a PNG.
 *
 * Real `uiautomator` reads an accessibility node tree from outside the app. There is no such tree to
 * read here — the embedded hierarchy is registered with no accessibility connection — but the
 * container is *inside* the process holding the views, so it walks them directly. The attribute set
 * is uiautomator's, in uiautomator's order, so a parser written for one reads the other.
 *
 * Only what is drawn is listed: an invisible or gone subtree is skipped whole, the same way the real
 * dump skips what the accessibility layer never reports.
 */
internal object GuestHierarchy {

    /** [windows] is each root to walk with the offset its window sits at in the device's screen. */
    fun write(xml: File, windows: List<Pair<View, Rect>>) {
        val out = StringBuilder(8 * 1024)
        out.append("<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n")
        // The device is an editor tab, so its screen never turns under the guest.
        out.append("<hierarchy rotation=\"0\">\n")
        windows.forEachIndexed { index, (view, frame) ->
            node(out, view, index, frame.left, frame.top, depth = 1)
        }
        out.append("</hierarchy>\n")
        xml.parentFile?.mkdirs()
        xml.writeText(out.toString())
    }

    private fun node(out: StringBuilder, view: View, index: Int, dx: Int, dy: Int, depth: Int) {
        if (view.visibility != View.VISIBLE) return

        val indent = "  ".repeat(depth)
        val children = (view as? ViewGroup)?.let { group -> (0 until group.childCount).map(group::getChildAt) }
            .orEmpty()

        out.append(indent).append("<node")
        attribute(out, "index", index.toString())
        attribute(out, "text", (view as? TextView)?.text?.toString().orEmpty())
        attribute(out, "resource-id", resourceId(view))
        attribute(out, "class", view.javaClass.name)
        attribute(out, "package", runCatching { view.context.packageName }.getOrNull().orEmpty())
        attribute(out, "content-desc", view.contentDescription?.toString().orEmpty())
        attribute(out, "checkable", (view is Checkable).toString())
        attribute(out, "checked", ((view as? Checkable)?.isChecked == true).toString())
        attribute(out, "clickable", view.isClickable.toString())
        attribute(out, "enabled", view.isEnabled.toString())
        attribute(out, "focusable", view.isFocusable.toString())
        attribute(out, "focused", view.isFocused.toString())
        attribute(out, "scrollable", scrollable(view).toString())
        attribute(out, "long-clickable", view.isLongClickable.toString())
        attribute(out, "password", password(view).toString())
        attribute(out, "selected", view.isSelected.toString())
        attribute(out, "bounds", bounds(view, dx, dy))

        if (children.isEmpty()) {
            out.append(" />\n")
            return
        }
        out.append(">\n")
        children.forEachIndexed { child, node -> node(out, node, child, dx, dy, depth + 1) }
        out.append(indent).append("</node>\n")
    }

    /** `pkg:id/name`, resolved against the guest's own resource table. Empty for an unnamed view. */
    private fun resourceId(view: View): String {
        if (view.id == View.NO_ID) return ""
        return runCatching { view.resources.getResourceName(view.id) }.getOrDefault("")
    }

    /** Screen coordinates, which for this device are the tab's — the same ones `input tap` takes. */
    private fun bounds(view: View, dx: Int, dy: Int): String {
        val at = IntArray(2)
        view.getLocationInWindow(at)
        val left = at[0] + dx
        val top = at[1] + dy
        return "[$left,$top][${left + view.width},${top + view.height}]"
    }

    private fun scrollable(view: View): Boolean = view.canScrollVertically(1) ||
        view.canScrollVertically(-1) ||
        view.canScrollHorizontally(1) ||
        view.canScrollHorizontally(-1)

    private fun password(view: View): Boolean {
        val variation = (view as? TextView)?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: return false
        return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
            variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }

    private fun attribute(out: StringBuilder, name: String, value: String) {
        out.append(' ').append(name).append("=\"")
        value.forEach { char ->
            when {
                char == '&' -> out.append("&amp;")
                char == '<' -> out.append("&lt;")
                char == '>' -> out.append("&gt;")
                char == '"' -> out.append("&quot;")
                // Control characters have no XML escape at all, so they are dropped rather than
                // written out to break the parser at the other end.
                char.code < 0x20 -> Unit
                else -> out.append(char)
            }
        }
        out.append('"')
    }
}

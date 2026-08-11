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
        out.append(UiXml.HEADER).append(UiXml.OPEN)
        windows.forEachIndexed { index, (view, frame) ->
            node(out, view, index, frame.left, frame.top, depth = 1)
        }
        out.append(UiXml.CLOSE)
        xml.parentFile?.mkdirs()
        xml.writeText(out.toString())
    }

    private fun node(out: StringBuilder, view: View, index: Int, dx: Int, dy: Int, depth: Int) {
        if (view.visibility != View.VISIBLE) return

        val children = (view as? ViewGroup)?.let { group -> (0 until group.childCount).map(group::getChildAt) }
            .orEmpty()
        UiXml.node(
            out = out,
            depth = depth,
            index = index,
            bounds = bounds(view, dx, dy),
            className = view.javaClass.name,
            packageName = runCatching { view.context.packageName }.getOrNull().orEmpty(),
            selfClosing = children.isEmpty(),
            text = (view as? TextView)?.text?.toString().orEmpty(),
            resourceId = resourceId(view),
            contentDesc = view.contentDescription?.toString().orEmpty(),
            checkable = view is Checkable,
            checked = (view as? Checkable)?.isChecked == true,
            clickable = view.isClickable,
            enabled = view.isEnabled,
            focusable = view.isFocusable,
            focused = view.isFocused,
            scrollable = scrollable(view),
            longClickable = view.isLongClickable,
            password = password(view),
            selected = view.isSelected,
        )
        if (children.isEmpty()) return
        children.forEachIndexed { child, node -> node(out, node, child, dx, dy, depth + 1) }
        UiXml.close(out, depth)
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
}

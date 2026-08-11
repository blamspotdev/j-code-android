package dev.jcode.vdevice

/**
 * The `uiautomator dump` document shape, in one place.
 *
 * Two things answer that command on this device — a running guest's view tree ([GuestHierarchy]) and
 * the device's own launcher ([VirtualLauncher]) — and a driver must not be able to tell which it is
 * parsing. The attribute set and its order are uiautomator's, not ours, so they live here rather
 * than being written out twice and drifting.
 */
internal object UiXml {

    const val HEADER = "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>\n"

    /** The device is an editor tab, so its screen never turns under what is on it. */
    const val OPEN = "<hierarchy rotation=\"0\">\n"
    const val CLOSE = "</hierarchy>\n"

    /**
     * One `<node>`. [selfClosing] false leaves the element open for children, which the caller then
     * closes with [close].
     */
    @Suppress("LongParameterList")
    fun node(
        out: StringBuilder,
        depth: Int,
        index: Int,
        bounds: String,
        className: String,
        packageName: String,
        selfClosing: Boolean,
        text: String = "",
        resourceId: String = "",
        contentDesc: String = "",
        checkable: Boolean = false,
        checked: Boolean = false,
        clickable: Boolean = false,
        enabled: Boolean = true,
        focusable: Boolean = false,
        focused: Boolean = false,
        scrollable: Boolean = false,
        longClickable: Boolean = false,
        password: Boolean = false,
        selected: Boolean = false,
    ) {
        out.append(indent(depth)).append("<node")
        attribute(out, "index", index.toString())
        attribute(out, "text", text)
        attribute(out, "resource-id", resourceId)
        attribute(out, "class", className)
        attribute(out, "package", packageName)
        attribute(out, "content-desc", contentDesc)
        attribute(out, "checkable", checkable.toString())
        attribute(out, "checked", checked.toString())
        attribute(out, "clickable", clickable.toString())
        attribute(out, "enabled", enabled.toString())
        attribute(out, "focusable", focusable.toString())
        attribute(out, "focused", focused.toString())
        attribute(out, "scrollable", scrollable.toString())
        attribute(out, "long-clickable", longClickable.toString())
        attribute(out, "password", password.toString())
        attribute(out, "selected", selected.toString())
        attribute(out, "bounds", bounds)
        out.append(if (selfClosing) " />\n" else ">\n")
    }

    fun close(out: StringBuilder, depth: Int) {
        out.append(indent(depth)).append("</node>\n")
    }

    private fun indent(depth: Int) = "  ".repeat(depth)

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

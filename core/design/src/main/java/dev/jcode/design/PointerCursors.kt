package dev.jcode.design

import android.view.PointerIcon as AndroidPointerIcon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalContext

/**
 * What the cursor turns into over a surface.
 *
 * JCode was written for a touchscreen, where a pointer has no shape and nothing to say before it is
 * put down. With a mouse attached the cursor is the only thing that tells you what a surface *is*
 * before you commit to clicking it — whether a row is pressable, whether text can be selected,
 * whether an edge can be dragged. Until now it was an arrow over all of them.
 *
 * These live in `:core:design` and are applied inside the shared components rather than at call
 * sites, so a chip, a settings row or a dialog button gets the right cursor by being what it is.
 */

/** For anything that responds to a click: rows, chips, tabs, buttons, links. */
fun Modifier.handCursor(): Modifier = pointerHoverIcon(PointerIcon.Hand)

/** For text that can be typed into or selected. */
fun Modifier.textCursor(): Modifier = pointerHoverIcon(PointerIcon.Text)

/**
 * For a draggable edge — a split divider, a panel resize handle.
 *
 * Composed rather than a plain modifier because the double-arrow cursors are platform icons that
 * need a Context; Compose's own `PointerIcon` constants stop at hand/text/crosshair.
 */
fun Modifier.resizeCursor(horizontal: Boolean): Modifier = composed {
    val context = LocalContext.current
    val type = if (horizontal) {
        AndroidPointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW
    } else {
        AndroidPointerIcon.TYPE_VERTICAL_DOUBLE_ARROW
    }
    pointerHoverIcon(PointerIcon(AndroidPointerIcon.getSystemIcon(context, type)))
}

/** For a surface being dragged from, as opposed to one merely draggable. */
@Composable
fun grabbingCursor(): PointerIcon {
    val context = LocalContext.current
    return PointerIcon(AndroidPointerIcon.getSystemIcon(context, AndroidPointerIcon.TYPE_GRABBING))
}

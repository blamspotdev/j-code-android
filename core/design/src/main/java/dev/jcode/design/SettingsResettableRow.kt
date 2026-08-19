package dev.jcode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Hosts one settings row: long-press opens a "Reset this setting" menu at the touch point, and a
 *  small dot in the enclosing card's left padding gutter marks a value that differs from its
 *  default (or, on a scoped tab, has an override saved). Interactive children (switches, buttons,
 *  dropdown pills) keep their own gestures — the long-press lands on the row's label area.
 *  With [onReset] null the content renders untouched. */
@Composable
fun SettingsResettableRow(
    modified: Boolean,
    onReset: (() -> Unit)?,
    content: @Composable () -> Unit,
) {
    if (onReset == null) {
        content()
        return
    }
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = { position ->
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    // DropdownMenu measures its y offset from the anchor's BOTTOM edge, so the
                    // row height is subtracted to land the menu at the finger instead of a full
                    // row-height below it.
                    menuAt = with(density) {
                        DpOffset(position.x.toDp(), (position.y - size.height).toDp())
                    }
                })
            }
            .semantics {
                if (modified) {
                    stateDescription = "Modified"
                    customActions = listOf(
                        CustomAccessibilityAction("Reset this setting") { onReset(); true },
                    )
                }
            },
    ) {
        content()
        if (modified) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset(x = (-9).dp)
                    .size(6.dp)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
        }
        menuAt?.let { at ->
            CompactContextMenu(
                expanded = true,
                onDismissRequest = { menuAt = null },
                offset = at,
                listActions = listOf(
                    ContextAction(JCodeIcon.Discard, "Reset this setting", enabled = modified) { onReset() },
                ),
            )
        }
    }
}

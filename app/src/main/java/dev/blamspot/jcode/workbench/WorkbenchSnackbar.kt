package dev.blamspot.jcode.workbench

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.blamspot.jcode.design.Radius
import dev.blamspot.jcode.design.Space
import dev.blamspot.jcode.design.StrokeWidth

/**
 * The app's snackbar host, themed to match JCode panels — a rounded `surfaceContainerHighest` card
 * with an outline and shadow, rather than Material's default inverse-surface pill. Intended to be
 * rendered as a top-level overlay above the workbench drawers/sidebars so a message is never hidden
 * behind them (the default Scaffold slot sits under the modal drawer sheet).
 *
 * A swipe either way dismisses it. These carry an action worth reading — "Show detail" on a git
 * result, "Reload extension" after an update — so they sit for several seconds, and on a phone they
 * land over the drawer content the message is about. Flicking one away beats waiting it out.
 */
@Composable
fun WorkbenchSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data ->
        // Keyed on the snackbar: the dismiss state has to start over for each one, or the box stays
        // swiped-away and the next message arrives already off-screen.
        key(data) {
            val dismissState = rememberSwipeToDismissBoxState(
                confirmValueChange = { value ->
                    if (value == SwipeToDismissBoxValue.Settled) {
                        false
                    } else {
                        data.dismiss()
                        true
                    }
                },
            )
            SwipeToDismissBox(
                state = dismissState,
                // Nothing is revealed behind it: the snackbar is the whole surface, and a reveal
                // colour under a floating card would read as a second, half-drawn card.
                backgroundContent = {},
            ) {
                SnackbarCard(data)
            }
        }
    }
}

/** The card itself — separated only so the dismiss wrapper above stays readable. */
@Composable
private fun SnackbarCard(data: SnackbarData) {
    Surface(
        shape = RoundedCornerShape(Radius.xxl),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = MaterialTheme.colorScheme.onSurface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
        border = BorderStroke(StrokeWidth.thin, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f)),
        modifier = Modifier.padding(horizontal = Space.md).widthIn(max = 560.dp),
    ) {
        Row(
            modifier = Modifier.padding(start = Space.lg, end = Space.s),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            Text(
                text = data.visuals.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f, fill = false).padding(vertical = Space.md),
            )
            data.visuals.actionLabel?.let { label ->
                TextButton(onClick = { data.performAction() }) {
                    Text(label, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

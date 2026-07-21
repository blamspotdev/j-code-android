package dev.jcode.design

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Wraps an icon-only control so its [label] surfaces on long-press (touch) or hover (pointer) — the
 * app's standard way to make icon-only buttons discoverable, since they carry no visible text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JcTooltip(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier,
        // Non-focusable: a focusable tooltip popup consumes the click that follows a mouse hover
        // (dismiss-on-outside-click) instead of forwarding it to the wrapped button. The label is
        // plain text with nothing to focus, so this is safe and makes every tooltip button clickable
        // with a mouse on the first click.
        focusable = false,
    ) {
        content()
    }
}

package dev.blamspot.jcode.design

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The app's primary action at compact size — the safe or expected choice in a dialog, a settings
 * row, a manager panel.
 *
 * [busy] puts a spinner in the leading slot without moving the label, so a button that kicks off
 * work keeps its width and the row around it does not jump the moment it is pressed. It disables
 * itself while it spins.
 */
@Composable
fun CompactFilledButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    busy: Boolean = false,
) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled && !busy,
        modifier = modifier.defaultMinSize(minHeight = ControlSize.compactHeight),
        contentPadding = ControlSize.compactPadding,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(IconSize.xs),
                strokeWidth = StrokeWidth.thick,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            Spacer(Modifier.width(Space.sm))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/** The alternative to a [CompactFilledButton]: available, but not the one being recommended. */
@Composable
fun CompactOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    /** Optional leading icon, for a button whose verb reads faster with one. */
    icon: JCodeIcon? = null,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = ControlSize.compactHeight),
        contentPadding = ControlSize.compactPadding,
    ) {
        if (icon != null) {
            Icon(
                imageVector = jcIcon(icon),
                contentDescription = null,
                modifier = Modifier.size(IconSize.sm),
            )
            Spacer(Modifier.size(Space.s))
        }
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

/**
 * The compact button for an action that destroys something — Delete, Discard, Kill.
 *
 * Outlined rather than filled: a destructive action should read as available, not as the one to
 * reach for, and the error colour carries the warning without the weight of a filled button. Pair it
 * with a [CompactFilledButton] for the safe choice.
 */
@Composable
fun CompactDestructiveButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.defaultMinSize(minHeight = ControlSize.compactHeight),
        contentPadding = ControlSize.compactPadding,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        border = BorderStroke(StrokeWidth.thin, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
    ) {
        Text(text, style = MaterialTheme.typography.labelMedium)
    }
}

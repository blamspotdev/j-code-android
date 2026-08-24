package dev.blamspot.jcode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check

import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

/**
 * The "Change Tab Color" picker: a 5x5 grid of [TabColorPalette] swatches, a "Random" button, and
 * (when a color is already set) a "Clear" button. Picking a swatch or Random calls [onPick] and
 * dismisses; Clear calls [onClear].
 */
@Composable
fun TabColorDialog(
    currentHex: String?,
    onPick: (Color) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val current = currentHex?.let { tabColorFromHex(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tab color") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                TabColorPalette.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        row.forEach { color ->
                            val selected = current != null && color.toArgbHex() == current.toArgbHex()
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RectangleShape)
                                    .background(color)
                                    .border(
                                        width = if (selected) 3.dp else 1.dp,
                                        color = if (selected) MaterialTheme.colorScheme.onSurface
                                        else MaterialTheme.colorScheme.outlineVariant,
                                    )
                                    .clickable { onPick(color) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (selected) {
                                    Icon(
                                        imageVector = Icons.Rounded.Check,
                                        contentDescription = null,
                                        tint = if (color.luminance() > 0.5f) Color.Black else Color.White,
                                        modifier = Modifier.size(IconSize.lg),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            CompactFilledButton(text = "Random", onClick = { onPick(randomTabColor()) })
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                if (current != null) {
                    CompactDestructiveButton(text = "Clear", onClick = onClear)
                }
                CompactOutlinedButton(text = "Cancel", onClick = onDismiss)
            }
        },
    )
}

private fun Color.toArgbHex(): String = tabColorToHex(this)

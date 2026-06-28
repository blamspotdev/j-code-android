package dev.jcode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * Editor keyboard preferences, bundled into one object so the host (JCodeShell) gains a single
 * parameter rather than several — that composable is near the ART verifier's register limit, so the
 * holder mirrors the WorkbenchManagerActions mitigation. [onChange] carries the full updated value
 * back to the ViewModel.
 */
data class EditorKeyboardSettings(
    val useInAppKeyboard: Boolean = true,
    val codeKeys: List<String> = KeyboardDefaults.codeKeys,
    val onChange: (EditorKeyboardSettings) -> Unit = {},
)

object KeyboardDefaults {
    /** The configurable code-symbol row shown across the top of the in-app keyboard. */
    val codeKeys: List<String> = listOf(
        "{", "}", "(", ")", "[", "]", "<", ">",
        "=", ";", ":", "\"", "'", "/", "\\", "|",
        "-", "_", "+", "*", "&", "#", "$", "%", "@", "!", "?",
    )
}

/** An action emitted by the in-app keyboard; the host applies it to the focused editor. */
sealed interface KeyAction {
    data class Text(val value: String) : KeyAction
    data object Backspace : KeyAction
    data object Enter : KeyAction
    data object Space : KeyAction
    data object Tab : KeyAction
    data object Left : KeyAction
    data object Right : KeyAction
    data object Up : KeyAction
    data object Down : KeyAction
    data object Hide : KeyAction
}

/**
 * A self-contained on-screen code keyboard. Owns its Shift + symbols-layer state internally and
 * emits [KeyAction]s for everything the host must apply to the editor. Layout is data-light for now
 * (a fixed QWERTY/symbols base plus the configurable [codeKeys] row); future extensions can supply
 * fuller custom layouts. Keys use pointer gestures (never `clickable`) so taps don't steal focus
 * from the editor.
 */
@Composable
fun InAppKeyboard(
    codeKeys: List<String>,
    onAction: (KeyAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    var shift by remember { mutableStateOf(false) }
    var symbols by remember { mutableStateOf(false) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            CodeRow(codeKeys = codeKeys, onAction = onAction)

            if (!symbols) {
                KeyRow {
                    "qwertyuiop".forEach { c -> LetterKey(c, shift, onAction) { shift = false } }
                }
                KeyRow(sidePadding = 0.5f) {
                    "asdfghjkl".forEach { c -> LetterKey(c, shift, onAction) { shift = false } }
                }
                KeyRow {
                    KeyCap("⇧", weight = 1.5f, mono = false, emphasized = shift) { shift = !shift }
                    "zxcvbnm".forEach { c -> LetterKey(c, shift, onAction) { shift = false } }
                    KeyCap("⌫", weight = 1.5f, mono = false, repeat = true) { onAction(KeyAction.Backspace) }
                }
                BottomRow(layerLabel = "?123", onToggleLayer = { symbols = true }, onAction = onAction)
            } else {
                KeyRow { "1234567890".forEach { c -> SymKey(c, onAction) } }
                KeyRow { "@#\$_&-+()/".forEach { c -> SymKey(c, onAction) } }
                KeyRow {
                    "%*\"':;!?".forEach { c -> SymKey(c, onAction) }
                    KeyCap("⌫", weight = 1.5f, mono = false, repeat = true) { onAction(KeyAction.Backspace) }
                }
                BottomRow(layerLabel = "ABC", onToggleLayer = { symbols = false }, onAction = onAction)
            }
        }
    }
}

@Composable
private fun KeyRow(
    sidePadding: Float = 0f,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (sidePadding > 0f) Box(Modifier.weight(sidePadding))
        content()
        if (sidePadding > 0f) Box(Modifier.weight(sidePadding))
    }
}

@Composable
private fun BottomRow(
    layerLabel: String,
    onToggleLayer: () -> Unit,
    onAction: (KeyAction) -> Unit,
) {
    KeyRow {
        KeyCap(layerLabel, weight = 1.6f, mono = false) { onToggleLayer() }
        KeyCap(",", weight = 1f) { onAction(KeyAction.Text(",")) }
        KeyCap("space", weight = 5f, mono = false) { onAction(KeyAction.Space) }
        KeyCap(".", weight = 1f) { onAction(KeyAction.Text(".")) }
        KeyCap("⏎", weight = 1.6f, mono = false) { onAction(KeyAction.Enter) }
    }
}

@Composable
private fun RowScope.LetterKey(
    c: Char,
    shift: Boolean,
    onAction: (KeyAction) -> Unit,
    onConsumeShift: () -> Unit,
) {
    val label = if (shift) c.uppercase() else c.toString()
    KeyCap(label = label) {
        onAction(KeyAction.Text(label))
        if (shift) onConsumeShift()
    }
}

@Composable
private fun RowScope.SymKey(c: Char, onAction: (KeyAction) -> Unit) {
    KeyCap(label = c.toString()) { onAction(KeyAction.Text(c.toString())) }
}

@Composable
private fun RowScope.KeyCap(
    label: String,
    weight: Float = 1f,
    mono: Boolean = true,
    emphasized: Boolean = false,
    repeat: Boolean = false,
    onTap: () -> Unit,
) {
    val base = Modifier
        .weight(weight)
        .padding(2.dp)
        .height(46.dp)
        .clip(RoundedCornerShape(6.dp))
        .background(
            if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.30f)
            else MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        )
    Box(
        modifier = if (repeat) base.repeatingTap(onTap) else base.pointerInput(label) {
            detectTapGestures(onTap = { onTap() })
        },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun CodeRow(codeKeys: List<String>, onAction: (KeyAction) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        FixedKey("⇥", mono = false) { onAction(KeyAction.Tab) }
        codeKeys.forEach { k -> FixedKey(k) { onAction(KeyAction.Text(k)) } }
        FixedKey("←", mono = false) { onAction(KeyAction.Left) }
        FixedKey("↓", mono = false) { onAction(KeyAction.Down) }
        FixedKey("↑", mono = false) { onAction(KeyAction.Up) }
        FixedKey("→", mono = false) { onAction(KeyAction.Right) }
        FixedKey("⌄", mono = false) { onAction(KeyAction.Hide) }
    }
}

@Composable
private fun FixedKey(label: String, mono: Boolean = true, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 4.dp)
            .defaultMinSize(minWidth = 34.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.92f))
            .pointerInput(label) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 9.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** Press-and-hold auto-repeat (used by Backspace): fire immediately on press (so even a quick tap
 *  always deletes once), then after a short delay repeat while still held. */
private fun Modifier.repeatingTap(onTap: () -> Unit): Modifier = composed {
    val current by rememberUpdatedState(onTap)
    var pressed by remember { mutableStateOf(false) }
    LaunchedEffect(pressed) {
        if (pressed) {
            delay(350)
            while (pressed) {
                current()
                delay(45)
            }
        }
    }
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            current()
            pressed = true
            waitForUpOrCancellation()
            pressed = false
        }
    }
}

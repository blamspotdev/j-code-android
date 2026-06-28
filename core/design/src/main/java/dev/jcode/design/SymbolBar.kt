package dev.jcode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/**
 * Editor soft-keyboard preferences, bundled into one object so the host (JCodeShell) gains a single
 * parameter rather than several — important because that composable is at the edge of the ART
 * verifier's register limit. [onChange] carries the full updated value back to the ViewModel.
 */
data class EditorKeyboardSettings(
    val hideSuggestions: Boolean = true,
    val showSymbolBar: Boolean = true,
    val symbolKeys: List<String> = SymbolBarDefaults.symbols,
    val onChange: (EditorKeyboardSettings) -> Unit = {},
)

/** Emitted when a symbol-bar key is tapped. */
sealed interface SymbolBarAction {
    data class Insert(val text: String) : SymbolBarAction
    data object Tab : SymbolBarAction
    data object Left : SymbolBarAction
    data object Right : SymbolBarAction
}

object SymbolBarDefaults {
    /** Default configurable symbols. The bar always prepends Tab and appends caret arrows. */
    val symbols: List<String> = listOf(
        "{", "}", "(", ")", "[", "]", "<", ">",
        "=", ";", ":", ",", ".", "\"", "'", "`",
        "/", "\\", "|", "-", "_", "+", "*", "&",
        "!", "?", "@", "#", "$", "%",
    )
}

/**
 * A horizontally-scrollable bar of quick-insert code keys, meant to sit just above the soft
 * keyboard. Renders Tab, the configurable [symbols], then caret arrows. Keys use
 * [detectTapGestures] (not [androidx.compose.foundation.clickable]) so a tap never requests focus —
 * which would otherwise pull focus off the editor and dismiss the keyboard.
 */
@Composable
fun SymbolBar(
    symbols: List<String>,
    onAction: (SymbolBarAction) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(44.dp)
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SymbolKey("⇥") { onAction(SymbolBarAction.Tab) }
            symbols.forEach { sym ->
                SymbolKey(sym) { onAction(SymbolBarAction.Insert(sym)) }
            }
            SymbolKey("←") { onAction(SymbolBarAction.Left) }
            SymbolKey("→") { onAction(SymbolBarAction.Right) }
        }
    }
}

@Composable
private fun SymbolKey(label: String, onTap: () -> Unit) {
    Box(
        modifier = Modifier
            .padding(vertical = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f))
            .height(32.dp)
            .defaultMinSize(minWidth = 36.dp)
            .pointerInput(label) { detectTapGestures(onTap = { onTap() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 10.dp),
            style = MaterialTheme.typography.bodyLarge,
            fontFamily = FontFamily.Monospace,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

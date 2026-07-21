package dev.jcode.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One chip on the extra-keys row. [Ctrl]/[Alt] are one-shot sticky modifiers, not sent keys. */
enum class ExtraKey(val label: String) {
    Esc("ESC"),
    Slash("/"),
    Dash("-"),
    Tab("TAB"),
    Ctrl("CTRL"),
    Alt("ALT"),
    Left("←"),
    Up("↑"),
    Down("↓"),
    Right("→"),
    Home("HOME"),
    End("END"),
    PageUp("PGUP"),
    PageDown("PGDN"),
    F1("F1"),
    F2("F2"),
    F3("F3"),
    F4("F4"),
    F5("F5"),
    F6("F6"),
    F7("F7"),
    F8("F8"),
    F9("F9"),
    F10("F10"),
    F11("F11"),
    F12("F12"),
}

/** The function-key chips appended to the row when the setting is on (see [ExtraKeysRow]). */
val EXTRA_FUNCTION_KEYS = listOf(
    ExtraKey.F1, ExtraKey.F2, ExtraKey.F3, ExtraKey.F4, ExtraKey.F5, ExtraKey.F6,
    ExtraKey.F7, ExtraKey.F8, ExtraKey.F9, ExtraKey.F10, ExtraKey.F11, ExtraKey.F12,
)

/** A focused surface (terminal or editor) that consumes extra-keys row presses. */
interface ExtraKeysTarget {
    /** Chips to show, in order. Including [ExtraKey.Ctrl]/[ExtraKey.Alt] opts into sticky modifiers. */
    val keys: List<ExtraKey>

    /** Whether the surface consumes F1-F12 — gates the "Function keys" setting so the chips only
     *  appear where they do something (the terminal; the editor has no F-key bindings). */
    val supportsFunctionKeys: Boolean get() = false

    /** A non-modifier chip was tapped with the sticky modifier state captured at tap time. */
    fun onExtraKey(key: ExtraKey, ctrl: Boolean, alt: Boolean)

    /** Sticky modifiers were armed/cleared; a terminal applies them to the next typed character. */
    fun onModifiersChanged(ctrl: Boolean, alt: Boolean) {}

    /** Scroll the surface by [lines]: positive reveals earlier content (editor: up the file; terminal:
     *  back into scrollback), negative moves toward the newest content. Default: no-op. */
    fun onScroll(lines: Int) {}
}

/**
 * Which surface currently owns the soft keyboard (set from the views' focus callbacks) plus the
 * row's sticky modifier state. The row renders only while [target] is non-null and the IME is
 * visible, so it never appears over ordinary text fields.
 */
class ExtraKeysState {
    var target by mutableStateOf<ExtraKeysTarget?>(null)
    var ctrl by mutableStateOf(false)
    var alt by mutableStateOf(false)

    fun clearModifiers() {
        ctrl = false
        alt = false
    }
}

val LocalExtraKeysState = compositionLocalOf { ExtraKeysState() }

/** When the extra-keys row is shown, chosen independently per orientation. */
enum class ExtraKeysVisibility {
    /** Never show the row. */
    Hidden,

    /** Show only while the soft keyboard is up (and a terminal/editor is focused). */
    WithKeyboard,

    /** Show whenever a terminal/editor is focused, keyboard up or not. */
    Always,
}

/** App setting: per-orientation visibility of the extra-keys row above the keyboard, plus whether
 *  the F1-F12 chips are appended for surfaces that consume them. */
class ExtraKeysSetting(
    val portrait: ExtraKeysVisibility = SettingsDefaults.EXTRA_KEYS_PORTRAIT,
    val landscape: ExtraKeysVisibility = SettingsDefaults.EXTRA_KEYS_LANDSCAPE,
    val functionKeys: Boolean = SettingsDefaults.EXTRA_KEYS_FUNCTION_KEYS,
    val onChangePortrait: (ExtraKeysVisibility) -> Unit = {},
    val onChangeLandscape: (ExtraKeysVisibility) -> Unit = {},
    val onChangeFunctionKeys: (Boolean) -> Unit = {},
)

val LocalExtraKeysSetting = compositionLocalOf { ExtraKeysSetting() }

/** One-line, horizontally scrolling Termux-style key row pinned above the soft keyboard. */
@Composable
fun ExtraKeysRow(
    target: ExtraKeysTarget,
    state: ExtraKeysState,
    modifier: Modifier = Modifier,
) {
    // F1-F12 append behind the setting, only for surfaces that consume them (the terminal).
    val showFunctionKeys = LocalExtraKeysSetting.current.functionKeys && target.supportsFunctionKeys
    val keys = if (showFunctionKeys) target.keys + EXTRA_FUNCTION_KEYS else target.keys
    // Directional glyphs (←↑↓→) render from a bundled font the host supplies, so they look the same on
    // every device instead of depending on the manufacturer's system-font coverage.
    val glyphFont = LocalExtraKeysGlyphFontFamily.current
    Surface(color = MaterialTheme.colorScheme.surface, modifier = modifier.fillMaxWidth()) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(38.dp)
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                keys.forEach { key ->
                    val active = (key == ExtraKey.Ctrl && state.ctrl) || (key == ExtraKey.Alt && state.alt)
                    ExtraKeyChip(
                        label = key.label,
                        glyphFont = if (key.isDirectional) glyphFont else null,
                        active = active,
                        onClick = {
                            when (key) {
                                ExtraKey.Ctrl -> {
                                    state.ctrl = !state.ctrl
                                    target.onModifiersChanged(state.ctrl, state.alt)
                                }
                                ExtraKey.Alt -> {
                                    state.alt = !state.alt
                                    target.onModifiersChanged(state.ctrl, state.alt)
                                }
                                else -> {
                                    val ctrl = state.ctrl
                                    val alt = state.alt
                                    if (ctrl || alt) {
                                        state.clearModifiers()
                                        target.onModifiersChanged(false, false)
                                    }
                                    target.onExtraKey(key, ctrl, alt)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

/** Font family the host supplies (from a BUNDLED font that ships the ←↑↓→ glyphs) for the directional
 *  keys, so the arrows render identically everywhere rather than relying on the device's system font,
 *  whose charset coverage and metrics vary by manufacturer. Null falls back to the default font. */
val LocalExtraKeysGlyphFontFamily = compositionLocalOf<FontFamily?> { null }

private val ExtraKey.isDirectional: Boolean
    get() = this == ExtraKey.Left || this == ExtraKey.Up || this == ExtraKey.Down || this == ExtraKey.Right

@Composable
private fun ExtraKeyChip(label: String, active: Boolean, onClick: () -> Unit, glyphFont: FontFamily? = null) {
    val contentColor = if (active) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (active) MaterialTheme.colorScheme.primary.copy(alpha = 0.22f)
                else androidx.compose.ui.graphics.Color.Transparent,
            )
            .clickable(onClick = onClick)
            .widthIn(min = 44.dp)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontFamily = glyphFont,
            fontWeight = FontWeight.SemiBold,
            color = contentColor,
        )
    }
}

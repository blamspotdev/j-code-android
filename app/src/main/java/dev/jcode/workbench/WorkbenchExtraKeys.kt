package dev.jcode.workbench

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import dev.jcode.core.term.TerminalView
import dev.jcode.design.BottomBarVisibility
import dev.jcode.design.ExtraKey
import dev.jcode.design.ExtraKeysRow
import dev.jcode.design.ExtraKeysTarget
import dev.jcode.design.ExtraKeysVisibility
import dev.jcode.design.LocalBottomBarSetting
import dev.jcode.design.LocalExtraKeysSetting
import dev.jcode.design.LocalExtraKeysState

/**
 * The Termux-style extra-keys row, pinned above the soft keyboard. Renders only while a terminal or
 * editor owns focus (never over plain text fields) and the current orientation's visibility mode
 * allows it: [ExtraKeysVisibility.WithKeyboard] gates on the IME being up, [ExtraKeysVisibility.Always]
 * shows regardless, [ExtraKeysVisibility.Hidden] never. Mounted in the Scaffold bottom bar (reserves
 * layout space) and at the bottom of the modal terminal sidebar (which covers the bottom bar).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WorkbenchExtraKeysBar(modifier: Modifier = Modifier) {
    val setting = LocalExtraKeysSetting.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val mode = if (landscape) setting.landscape else setting.portrait
    if (mode == ExtraKeysVisibility.Hidden) return
    val state = LocalExtraKeysState.current
    val target = state.target ?: return
    if (mode == ExtraKeysVisibility.WithKeyboard && !WindowInsets.isImeVisible) return
    ExtraKeysRow(target = target, state = state, modifier = modifier)
}

/** Whether the workbench's bottom status bar should render, per its visibility setting and the IME. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberBottomStatusBarVisible(): Boolean = when (LocalBottomBarSetting.current.visibility) {
    BottomBarVisibility.Hidden -> false
    BottomBarVisibility.HideOnKeyboard -> !WindowInsets.isImeVisible
    BottomBarVisibility.AlwaysShow -> true
}

/** Routes extra-keys presses to a [TerminalView] as VT escape bytes. */
class TerminalExtraKeysTarget(private val view: TerminalView) : ExtraKeysTarget {

    override val keys = listOf(
        ExtraKey.Esc, ExtraKey.Tab, ExtraKey.Ctrl, ExtraKey.Alt,
        ExtraKey.Left, ExtraKey.Up, ExtraKey.Down, ExtraKey.Right,
        ExtraKey.Home, ExtraKey.End, ExtraKey.PageUp, ExtraKey.PageDown,
        ExtraKey.Slash, ExtraKey.Dash,
    )

    override fun onExtraKey(key: ExtraKey, ctrl: Boolean, alt: Boolean) {
        if (ctrl || alt) {
            modifiedSequence(key, ctrl, alt)?.let {
                view.sendInput(it)
                return
            }
        }
        when (key) {
            ExtraKey.Esc -> view.sendKey(KeyEvent.KEYCODE_ESCAPE, null)
            ExtraKey.Tab -> view.sendKey(KeyEvent.KEYCODE_TAB, null)
            ExtraKey.Left -> view.sendKey(KeyEvent.KEYCODE_DPAD_LEFT, null)
            ExtraKey.Up -> view.sendKey(KeyEvent.KEYCODE_DPAD_UP, null)
            ExtraKey.Down -> view.sendKey(KeyEvent.KEYCODE_DPAD_DOWN, null)
            ExtraKey.Right -> view.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT, null)
            ExtraKey.Home -> view.sendKey(KeyEvent.KEYCODE_MOVE_HOME, null)
            ExtraKey.End -> view.sendKey(KeyEvent.KEYCODE_MOVE_END, null)
            ExtraKey.PageUp -> view.sendKey(KeyEvent.KEYCODE_PAGE_UP, null)
            ExtraKey.PageDown -> view.sendKey(KeyEvent.KEYCODE_PAGE_DOWN, null)
            ExtraKey.Slash -> view.sendInput("/")
            ExtraKey.Dash -> view.sendInput("-")
            ExtraKey.Ctrl, ExtraKey.Alt -> Unit
        }
    }

    override fun onModifiersChanged(ctrl: Boolean, alt: Boolean) {
        view.pendingCtrl = ctrl
        view.pendingAlt = alt
    }

    // [lines] positive = up into scrollback history, matching the terminal's scrollOffset axis.
    override fun onScroll(lines: Int) {
        view.scrollByLines(lines)
    }

    /** xterm modified sequences: modifier code = 1 + (2 if alt) + (4 if ctrl). */
    private fun modifiedSequence(key: ExtraKey, ctrl: Boolean, alt: Boolean): String? {
        val mod = 1 + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        return when (key) {
            ExtraKey.Up -> "\u001B[1;${mod}A"
            ExtraKey.Down -> "\u001B[1;${mod}B"
            ExtraKey.Right -> "\u001B[1;${mod}C"
            ExtraKey.Left -> "\u001B[1;${mod}D"
            ExtraKey.Home -> "\u001B[1;${mod}H"
            ExtraKey.End -> "\u001B[1;${mod}F"
            ExtraKey.PageUp -> "\u001B[5;${mod}~"
            ExtraKey.PageDown -> "\u001B[6;${mod}~"
            else -> null
        }
    }
}

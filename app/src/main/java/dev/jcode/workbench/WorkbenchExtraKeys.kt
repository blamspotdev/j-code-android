package dev.jcode.workbench

import android.content.res.Configuration
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    // Fade, don't size-tween: the workbench snaps its IME padding to the animation target in one
    // relayout, and a height tween here would put the Scaffold content back on a per-frame relayout
    // treadmill for 200ms (the exact keyboard-toggle jank the snap removed). A fade animates only
    // layer alpha — the row claims its space in the same single relayout as the keyboard snap.
    // Render from the last non-null target so a focus loss (target -> null) fades out too instead
    // of unmounting mid-transition.
    var lastTarget by remember { mutableStateOf<ExtraKeysTarget?>(null) }
    state.target?.let { if (lastTarget !== it) lastTarget = it }
    val target = lastTarget ?: return
    AnimatedVisibility(
        visible = state.target != null && (mode != ExtraKeysVisibility.WithKeyboard || WindowInsets.isImeVisible),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
    ) {
        ExtraKeysRow(target = target, state = state, modifier = modifier)
    }
}

/** Whether the workbench's bottom status bar should render, per its visibility setting and the IME. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun rememberBottomStatusBarVisible(): Boolean = when (LocalBottomBarSetting.current.visibility) {
    BottomBarVisibility.Hidden -> false
    BottomBarVisibility.HideOnKeyboard -> !WindowInsets.isImeVisible
    BottomBarVisibility.AlwaysShow -> true
}

/**
 * Hosts the bottom status bar behind [rememberBottomStatusBarVisible]. A restartable slot so the
 * helper's IME-inset read recomposes only this composable (value-returning composables invalidate
 * their CALLER — previously the whole Scaffold bottomBar). Fades rather than size-tweens for the
 * same reason as [WorkbenchExtraKeysBar]: a height animation re-lays-out the Scaffold content every
 * frame, which is the keyboard-toggle jank the snapped IME padding removed.
 */
@Composable
fun BottomStatusBarSlot(content: @Composable () -> Unit) {
    AnimatedVisibility(
        visible = rememberBottomStatusBarVisible(),
        enter = fadeIn(tween(120)),
        exit = fadeOut(tween(120)),
    ) {
        content()
    }
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

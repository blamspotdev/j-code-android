package dev.blamspot.jcode.design

import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupPositionProvider
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** How long the pointer must hover / press before the tooltip opens, so a passing cursor is quiet. */
private const val TOOLTIP_SHOW_DELAY_MS = 500L

/** Positions the tooltip centered UNDER the anchor, flipping above only when there's no room below. */
private fun belowAnchorPositionProvider(spacingPx: Int): PopupPositionProvider =
    object : PopupPositionProvider {
        override fun calculatePosition(
            anchorBounds: IntRect,
            windowSize: IntSize,
            layoutDirection: LayoutDirection,
            popupContentSize: IntSize,
        ): IntOffset {
            val x = (anchorBounds.left + (anchorBounds.width - popupContentSize.width) / 2)
                .coerceIn(0, (windowSize.width - popupContentSize.width).coerceAtLeast(0))
            val below = anchorBounds.bottom + spacingPx
            val y = if (below + popupContentSize.height <= windowSize.height) {
                below
            } else {
                (anchorBounds.top - popupContentSize.height - spacingPx).coerceAtLeast(0)
            }
            return IntOffset(x, y)
        }
    }

/**
 * Wraps an icon-only control so its [label] surfaces on hover (pointer) or long-press (touch) — the
 * app's standard way to make icon-only buttons discoverable, since they carry no visible text.
 *
 * The label opens **under** the control after a short delay. The gestures are driven here rather than
 * by [TooltipBox]'s built-in handling (which is instant and anchors above): the pointer pass is
 * observe-only, so the wrapped button still receives its own clicks — a touch long-press that opened
 * the label is the one case whose release is swallowed, so reading a tooltip never also taps.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JcTooltip(
    label: String,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val state = rememberTooltipState()
    val scope = rememberCoroutineScope()
    val spacingPx = with(LocalDensity.current) { 4.dp.roundToPx() }
    val positionProvider = remember(spacingPx) { belowAnchorPositionProvider(spacingPx) }
    var showJob by remember { mutableStateOf<Job?>(null) }

    TooltipBox(
        positionProvider = positionProvider,
        tooltip = { PlainTooltip { Text(label) } },
        state = state,
        modifier = modifier,
        // Non-focusable: a focusable tooltip popup consumes the click that follows a mouse hover
        // (dismiss-on-outside-click) instead of forwarding it to the wrapped button.
        focusable = false,
        enableUserInput = false,
    ) {
        Box(
            modifier = Modifier.pointerInput(state) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull()
                        val touch = change?.type == PointerType.Touch
                        when (event.type) {
                            // Mouse/stylus hover, or a touch press held long enough: arm a delayed open.
                            PointerEventType.Enter -> {
                                showJob?.cancel()
                                showJob = scope.launch {
                                    delay(TOOLTIP_SHOW_DELAY_MS)
                                    state.show(MutatePriority.PreventUserInput)
                                }
                            }
                            PointerEventType.Press -> {
                                showJob?.cancel()
                                if (touch) {
                                    showJob = scope.launch {
                                        delay(TOOLTIP_SHOW_DELAY_MS)
                                        state.show(MutatePriority.PreventUserInput)
                                    }
                                } else if (state.isVisible) {
                                    state.dismiss()
                                }
                            }
                            PointerEventType.Exit -> {
                                showJob?.cancel()
                                if (state.isVisible) state.dismiss()
                            }
                            PointerEventType.Release -> if (touch) {
                                showJob?.cancel()
                                // If the long-press already opened the label, swallow this release so
                                // the button doesn't also fire; a quick tap (nothing shown) clicks.
                                if (state.isVisible) {
                                    change?.consume()
                                    state.dismiss()
                                }
                            }
                        }
                    }
                }
            },
        ) {
            content()
        }
    }
}

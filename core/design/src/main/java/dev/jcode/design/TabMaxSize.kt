package dev.jcode.design

import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Maximum width an editor/terminal tab's title may occupy before it is middle-ellipsized. */
enum class TabMaxSize(val titleMaxWidth: Dp) {
    Small(44.dp),
    Medium(64.dp),
    Large(104.dp),
}

/** App setting: the editor/terminal tab width cap. Shared with the tab strips + settings screen via
 *  [LocalTabMaxSize] without threading params through JCodeShell (ART register limit). */
class TabMaxSizeSetting(
    val size: TabMaxSize = SettingsDefaults.TAB_MAX_SIZE,
    val onChange: (TabMaxSize) -> Unit = {},
)

val LocalTabMaxSize = compositionLocalOf { TabMaxSizeSetting() }

/**
 * A single-line [Text] that truncates in the MIDDLE (keeping the start and the end) when it exceeds
 * [maxWidth] — e.g. "build.gradle.kts" -> "build.g…kts" — so a tab keeps its extension/suffix visible.
 * Compose ui 1.7.x has no `TextOverflow.MiddleEllipsis`, so the display string is measured here.
 */
@Composable
fun MiddleEllipsisText(
    text: String,
    maxWidth: Dp,
    modifier: Modifier = Modifier,
    style: TextStyle = LocalTextStyle.current,
    color: Color = Color.Unspecified,
) {
    val measurer = rememberTextMeasurer()
    val maxPx = with(LocalDensity.current) { maxWidth.toPx() }
    val shown = remember(text, maxPx, style) { middleEllipsize(text, style, maxPx, measurer) }
    Text(
        text = shown,
        modifier = modifier,
        style = style,
        color = color,
        maxLines = 1,
        softWrap = false,
    )
}

private const val ELLIPSIS = "…"

private fun middleEllipsize(text: String, style: TextStyle, maxPx: Float, measurer: TextMeasurer): String {
    if (text.isEmpty()) return text
    fun widthOf(s: String): Int = measurer.measure(text = s, style = style, softWrap = false, maxLines = 1).size.width
    if (widthOf(text) <= maxPx || text.length <= 2) return text
    // Largest k = head+tail visible chars (head ~2:1 biased, keeping the suffix) whose "head…tail" fits.
    var lo = 1
    var hi = text.length - 1
    var best = ELLIPSIS
    while (lo <= hi) {
        val k = (lo + hi) / 2
        val head = ((k * 2 + 2) / 3).coerceIn(1, k)
        val tail = k - head
        val candidate = text.take(head) + ELLIPSIS + if (tail > 0) text.takeLast(tail) else ""
        if (widthOf(candidate) <= maxPx) {
            best = candidate
            lo = k + 1
        } else {
            hi = k - 1
        }
    }
    return best
}

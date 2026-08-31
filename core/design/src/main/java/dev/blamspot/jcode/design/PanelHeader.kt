package dev.blamspot.jcode.design

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp

/**
 * What the top of a left-drawer panel is made of.
 *
 * Not a component — the panels keep their own headers, because what sits in one differs: Source
 * Control shows a branch, Toolchains a count and a filter, Run the project it is looking at. What
 * they should not each decide is how big the title is and how big the buttons beside it are, and
 * they were each deciding: `titleSmall` in Run and DB, `titleMedium` in the manager panels, icon
 * buttons at 36dp in one and 28dp in the next. Moving between two panels in the same drawer read as
 * moving between two applications.
 *
 * These are the compact measurements Source Control settled on. They delegate to the app's scales
 * rather than restating numbers, so a change to [ControlSize] or [IconSize] still moves everything
 * at once; what this adds is the *name* — somewhere for a panel to ask what a header looks like
 * instead of guessing.
 *
 * Extensions draw their own panels from this too, which is why it lives in the design module rather
 * than in the app: [dev.blamspot.jcode.design] is what a plugin compiles against.
 */
object PanelHeader {

    /** The icon-only buttons in a header row — refresh, search, and whatever a panel adds. */
    val iconButton: Dp = ControlSize.iconButtonSm

    /** Padding around the header's own content, inside the rule that closes it. */
    val horizontalPadding: Dp = Space.ms
    val verticalPadding: Dp = Space.s

    /**
     * How tall a header row is whether or not it carries a button.
     *
     * A panel with nothing but a title had a header a button's worth shorter than the panel beside
     * it, so the rule under it sat at a different height and the whole drawer shifted on every tab.
     */
    val minHeight: Dp = ControlSize.iconButtonSm + Space.s * 2

    /** The glyph inside one. Smaller than the app default: a header is a label, not a toolbar. */
    val icon: Dp = IconSize.sm

    /** A busy spinner standing in for the refresh button, sized to keep the row from reflowing. */
    val busyStroke: Dp = StrokeWidth.thick

    /** The rule that closes a header off from the list under it. */
    val rule: Dp = StrokeWidth.hairline

    val titleWeight: FontWeight = FontWeight.SemiBold

    /** The panel's name. `titleMedium`, so it reads as the heading of the drawer rather than of a
     *  section inside it — the sections below use `titleSmall` and `labelSmall`. */
    val titleStyle: TextStyle
        @Composable @ReadOnlyComposable get() = MaterialTheme.typography.titleMedium
}

package dev.jcode.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Custom vector icon packs. Each is an [IconBundle] whose overrides are hand-authored [ImageVector]s
 * and which falls back to [defaultIconBundle] for any slot it doesn't restyle, so a pack can ship
 * just its hero icons. This is the same shape a disk/asset icon-bundle extension would map onto.
 *
 * Icons are drawn on a 24x24 grid. Colors are black; the consuming `Icon` applies the real tint.
 */
// --- "JCode Line": a minimal, uniform-stroke line set --------------------------------------
// Declared before [jcodeLineIconBundle] / [customIconBundles] below so top-level init order
// (top-to-bottom) populates the map before the bundle reads it.

private val lineIcons: Map<JCodeIcon, ImageVector> = mapOf(
    JCodeIcon.Add to icon("Add") {
        strokePath { hLine(4f, 20f, 12f); vLine(4f, 20f, 12f) }
    },
    JCodeIcon.Close to icon("Close") {
        strokePath { moveTo(6f, 6f); lineTo(18f, 18f); moveTo(18f, 6f); lineTo(6f, 18f) }
    },
    JCodeIcon.Run to icon("Run") {
        fillPath { moveTo(7f, 5f); lineTo(7f, 19f); lineTo(19f, 12f); close() }
    },
    JCodeIcon.Stop to icon("Stop") {
        fillPath { moveTo(7f, 7f); lineTo(17f, 7f); lineTo(17f, 17f); lineTo(7f, 17f); close() }
    },
    JCodeIcon.Terminal to icon("Terminal") {
        strokePath {
            moveTo(3f, 5f); lineTo(21f, 5f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            moveTo(7f, 10f); lineTo(10f, 12.5f); lineTo(7f, 15f)
            moveTo(12f, 15f); lineTo(16f, 15f)
        }
    },
    JCodeIcon.Search to icon("Search") {
        strokePath {
            circle(11f, 11f, 5.5f)
            moveTo(15.5f, 15.5f); lineTo(20f, 20f)
        }
    },
    JCodeIcon.Settings to icon("Settings") {
        strokePath {
            hLine(4f, 20f, 7f); hLine(4f, 20f, 12f); hLine(4f, 20f, 17f)
        }
        fillPath { circle(9f, 7f, 2f); circle(15f, 12f, 2f); circle(8f, 17f, 2f) }
    },
    JCodeIcon.Extensions to icon("Extensions") {
        strokePath {
            box(4f, 4f, 10f, 10f)
            box(14f, 4f, 20f, 10f)
            box(4f, 14f, 10f, 20f)
            box(14f, 14f, 20f, 20f)
        }
    },
    JCodeIcon.Sdk to icon("Sdk") {
        strokePath {
            moveTo(12f, 3f); lineTo(20f, 7f); lineTo(20f, 17f); lineTo(12f, 21f)
            lineTo(4f, 17f); lineTo(4f, 7f); close()
            moveTo(4f, 7f); lineTo(12f, 11f); lineTo(20f, 7f)
            moveTo(12f, 11f); lineTo(12f, 21f)
        }
    },
    JCodeIcon.Scm to icon("Scm") {
        strokePath { vLine(8f, 16f, 6f); moveTo(6f, 11f); lineTo(14f, 11f) }
        fillPath { circle(6f, 6f, 2f); circle(6f, 18f, 2f); circle(16f, 11f, 2f) }
    },
    JCodeIcon.Files to icon("Files") { strokePath { folder() } },
    JCodeIcon.Folder to icon("Folder") { strokePath { folder() } },
    JCodeIcon.NewFile to icon("NewFile") {
        strokePath {
            moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close()
            moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
            hLine(9f, 15f, 14f); vLine(11f, 17f, 12f)
        }
    },
    JCodeIcon.NewFolder to icon("NewFolder") {
        strokePath {
            folder()
            hLine(9.5f, 14.5f, 14f); vLine(11.5f, 16.5f, 12f)
        }
    },
    JCodeIcon.Code to icon("Code") {
        strokePath {
            moveTo(9f, 8f); lineTo(5f, 12f); lineTo(9f, 16f)
            moveTo(15f, 8f); lineTo(19f, 12f); lineTo(15f, 16f)
        }
    },
    JCodeIcon.ChevronDown to icon("ChevronDown") {
        strokePath { moveTo(6f, 10f); lineTo(12f, 16f); lineTo(18f, 10f) }
    },
    JCodeIcon.DropDown to icon("DropDown") {
        strokePath { moveTo(7f, 10f); lineTo(12f, 15f); lineTo(17f, 10f) }
    },
    JCodeIcon.ChevronRight to icon("ChevronRight") {
        strokePath { moveTo(10f, 6f); lineTo(16f, 12f); lineTo(10f, 18f) }
    },
    JCodeIcon.ArrowUp to icon("ArrowUp") {
        strokePath { vLine(5f, 20f, 12f); moveTo(6f, 11f); lineTo(12f, 5f); lineTo(18f, 11f) }
    },
    JCodeIcon.MoreVert to icon("MoreVert") {
        fillPath { circle(12f, 5f, 1.7f); circle(12f, 12f, 1.7f); circle(12f, 19f, 1.7f) }
    },
    JCodeIcon.MenuToggle to icon("MenuToggle") {
        strokePath { hLine(4f, 20f, 7f); hLine(4f, 14f, 12f); hLine(4f, 20f, 17f) }
    },
    JCodeIcon.Output to icon("Output") {
        strokePath {
            moveTo(6f, 3f); lineTo(14f, 3f); lineTo(18f, 7f); lineTo(18f, 21f); lineTo(6f, 21f); close()
            moveTo(14f, 3f); lineTo(14f, 7f); lineTo(18f, 7f)
            hLine(9f, 15f, 12f); hLine(9f, 15f, 16f)
        }
    },
    JCodeIcon.Logs to icon("Logs") {
        strokePath { hLine(9f, 20f, 7f); hLine(9f, 20f, 12f); hLine(9f, 16f, 17f) }
        fillPath { circle(5f, 7f, 1.3f); circle(5f, 12f, 1.3f); circle(5f, 17f, 1.3f) }
    },
)

private val jcodeLineIconBundle = IconBundle(
    id = "jcode-line",
    name = "JCode Line",
    description = "Custom minimal line icons.",
    overrides = lineIcons,
    fallback = defaultIconBundle,
)

val customIconBundles: List<IconBundle> = listOf(jcodeLineIconBundle)

// --- builders -------------------------------------------------------------------------------

private fun icon(name: String, content: ImageVector.Builder.() -> Unit): ImageVector =
    ImageVector.Builder(
        name = "jc_$name",
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).apply(content).build()

private fun ImageVector.Builder.strokePath(content: PathBuilder.() -> Unit) {
    path(
        stroke = SolidColor(Color.Black),
        strokeLineWidth = 2f,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round,
        pathBuilder = content,
    )
}

private fun ImageVector.Builder.fillPath(content: PathBuilder.() -> Unit) {
    path(fill = SolidColor(Color.Black), pathBuilder = content)
}

private fun PathBuilder.hLine(x1: Float, x2: Float, y: Float) {
    moveTo(x1, y); lineTo(x2, y)
}

private fun PathBuilder.vLine(y1: Float, y2: Float, x: Float) {
    moveTo(x, y1); lineTo(x, y2)
}

private fun PathBuilder.box(left: Float, top: Float, right: Float, bottom: Float) {
    moveTo(left, top); lineTo(right, top); lineTo(right, bottom); lineTo(left, bottom); close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcToRelative(r, r, 0f, false, true, 2 * r, 0f)
    arcToRelative(r, r, 0f, false, true, -2 * r, 0f)
    close()
}

private fun PathBuilder.folder() {
    moveTo(3f, 7f); lineTo(9f, 7f); lineTo(11f, 9f); lineTo(21f, 9f)
    lineTo(21f, 18f); lineTo(3f, 18f); close()
}

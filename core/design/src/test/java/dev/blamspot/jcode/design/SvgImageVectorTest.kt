package dev.blamspot.jcode.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorNode
import androidx.compose.ui.graphics.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SVG reader is the one piece of this feature that consumes third-party files, so it is the one
 * that has to be right about malformed input as well as well-formed input.
 */
class SvgImageVectorTest {

    private fun svg(body: String, attrs: String = """viewBox="0 0 24 24""""): ImageVector? =
        SvgImageVector.parse("""<svg xmlns="http://www.w3.org/2000/svg" $attrs>$body</svg>""", "test")

    private fun paths(image: ImageVector): List<VectorPath> {
        val out = mutableListOf<VectorPath>()
        fun walk(node: VectorNode) {
            when (node) {
                is VectorPath -> out += node
                is VectorGroup -> node.forEach(::walk)
            }
        }
        walk(image.root)
        return out
    }

    private fun groups(image: ImageVector): List<VectorGroup> {
        val out = mutableListOf<VectorGroup>()
        fun walk(node: VectorNode) {
            if (node is VectorGroup) {
                out += node
                node.forEach(::walk)
            }
        }
        walk(image.root)
        return out
    }

    private fun fillOf(path: VectorPath): Color? = (path.fill as? SolidColor)?.value

    @Test
    fun `reads viewBox as the viewport`() {
        val image = svg("""<path d="M0 0 H24 V24 Z"/>""")!!
        assertEquals(24f, image.viewportWidth, 0f)
        assertEquals(24f, image.viewportHeight, 0f)
        assertEquals(1, paths(image).size)
    }

    @Test
    fun `falls back to width and height without a viewBox`() {
        val image = svg("""<path d="M0 0 H32 V32 Z"/>""", attrs = """width="32" height="32"""")!!
        assertEquals(32f, image.viewportWidth, 0f)
        assertEquals(32f, image.viewportHeight, 0f)
    }

    @Test
    fun `a non-zero viewBox origin becomes a translating group`() {
        val image = svg("""<path d="M0 0 H24 Z"/>""", attrs = """viewBox="-4 -8 24 24"""")!!
        val shift = groups(image).first { it.translationX != 0f || it.translationY != 0f }
        assertEquals(4f, shift.translationX, 0f)
        assertEquals(8f, shift.translationY, 0f)
    }

    // --- shapes -------------------------------------------------------------------------------

    @Test
    fun `converts every primitive shape to a path`() {
        val image = svg(
            """
            <rect x="1" y="1" width="4" height="4"/>
            <rect x="1" y="1" width="8" height="8" rx="2"/>
            <circle cx="12" cy="12" r="5"/>
            <ellipse cx="6" cy="6" rx="3" ry="2"/>
            <line x1="0" y1="0" x2="9" y2="9" stroke="#000"/>
            <polyline points="1,1 5,5 9,1" stroke="#000"/>
            <polygon points="1,1 5,5 9,1"/>
            """.trimIndent(),
        )!!
        assertEquals(7, paths(image).size)
    }

    @Test
    fun `drops a degenerate shape rather than emitting an empty path`() {
        assertNull(svg("""<circle cx="5" cy="5" r="0"/>"""))
        assertNull(svg("""<rect x="1" y="1" width="0" height="4"/>"""))
        assertNull(svg("""<polyline points="1,1"/>"""))
    }

    // --- paint --------------------------------------------------------------------------------

    @Test
    fun `reads the hex colour forms`() {
        assertEquals(Color(0xFFFF0000), fillOf(paths(svg("""<path d="M0 0 H1" fill="#f00"/>""")!!).single()))
        assertEquals(Color(0xFF3178C6), fillOf(paths(svg("""<path d="M0 0 H1" fill="#3178c6"/>""")!!).single()))
        // #rrggbbaa puts alpha last; Compose wants it first.
        assertEquals(Color(0x8033AA55), fillOf(paths(svg("""<path d="M0 0 H1" fill="#33aa5580"/>""")!!).single()))
    }

    @Test
    fun `reads rgb and named colours`() {
        assertEquals(Color(0xFF0A141E), fillOf(paths(svg("""<path d="M0 0 H1" fill="rgb(10,20,30)"/>""")!!).single()))
        assertEquals(Color.White, fillOf(paths(svg("""<path d="M0 0 H1" fill="white"/>""")!!).single()))
    }

    @Test
    fun `fill none leaves a stroke-only path`() {
        val path = paths(svg("""<path d="M0 0 H9" fill="none" stroke="#000" stroke-width="2"/>""")!!).single()
        assertNull(path.fill)
        assertNotNull(path.stroke)
        assertEquals(2f, path.strokeLineWidth, 0f)
    }

    @Test
    fun `a shape with neither fill nor stroke draws nothing`() {
        assertNull(svg("""<path d="M0 0 H9" fill="none"/>"""))
    }

    @Test
    fun `an inline style declaration outranks the presentation attribute`() {
        val path = paths(svg("""<path d="M0 0 H1" fill="#000000" style="fill:#ff0000"/>""")!!).single()
        assertEquals(Color(0xFFFF0000), fillOf(path))
    }

    @Test
    fun `a gradient reference keeps the inherited flat colour instead of vanishing`() {
        val path = paths(svg("""<g fill="#3178c6"><path d="M0 0 H1" fill="url(#grad)"/></g>""")!!).single()
        assertEquals(Color(0xFF3178C6), fillOf(path))
    }

    @Test
    fun `children inherit their group's paint`() {
        val path = paths(svg("""<g fill="#00ff00"><path d="M0 0 H1"/></g>""")!!).single()
        assertEquals(Color(0xFF00FF00), fillOf(path))
    }

    @Test
    fun `group opacity multiplies into the child's alpha`() {
        val path = paths(svg("""<g opacity="0.5"><path d="M0 0 H1" fill-opacity="0.5"/></g>""")!!).single()
        assertEquals(0.25f, path.fillAlpha, 1e-4f)
    }

    // --- transforms ---------------------------------------------------------------------------

    @Test
    fun `a transform list nests one group per primitive, outermost first`() {
        val image = svg("""<g transform="translate(4,5) scale(2)"><path d="M0 0 H1"/></g>""")!!
        val translate = groups(image).first { it.translationX == 4f }
        assertEquals(5f, translate.translationY, 0f)
        val scale = groups(image).first { it.scaleX == 2f }
        assertEquals(2f, scale.scaleY, 0f)
    }

    @Test
    fun `rotate carries its pivot`() {
        val group = groups(svg("""<g transform="rotate(45 12 12)"><path d="M0 0 H1"/></g>""")!!)
            .first { it.rotation != 0f }
        assertEquals(45f, group.rotation, 0f)
        assertEquals(12f, group.pivotX, 0f)
        assertEquals(12f, group.pivotY, 0f)
    }

    @Test
    fun `a sheared matrix is dropped rather than approximated`() {
        val image = svg("""<g transform="matrix(1,0.5,0.5,1,0,0)"><path d="M0 0 H1"/></g>""")!!
        assertTrue(groups(image).none { it.scaleX != 1f || it.translationX != 0f })
        assertEquals(1, paths(image).size)
    }

    // --- refusals -----------------------------------------------------------------------------

    @Test
    fun `refuses input that is not SVG`() {
        assertNull(SvgImageVector.parse("not xml at all", "test"))
        assertNull(SvgImageVector.parse("<html><body/></html>", "test"))
        assertNull(SvgImageVector.parse("", "test"))
        // Truncated: the document never closes.
        assertNull(SvgImageVector.parse("""<svg viewBox="0 0 24 24"><path d="M0 0""", "test"))
    }

    @Test
    fun `skips referenced-only and unsupported content`() {
        // Only the path draws; the gradient stops and the title must not become shapes.
        val image = svg(
            """
            <title>An icon</title>
            <defs><linearGradient id="g"><stop offset="0" stop-color="#fff"/></linearGradient></defs>
            <text x="0" y="0">hi</text>
            <path d="M0 0 H9 V9 Z"/>
            """.trimIndent(),
        )!!
        assertEquals(1, paths(image).size)
    }

    @Test
    fun `ignores a doctype rather than resolving it`() {
        // Third-party art must not be able to reach the filesystem through an external entity.
        val withDoctype = """
            <!DOCTYPE svg PUBLIC "-//W3C//DTD SVG 1.1//EN" "http://www.w3.org/Graphics/SVG/1.1/DTD/svg11.dtd">
            <svg viewBox="0 0 24 24"><path d="M0 0 H9 V9 Z"/></svg>
        """.trimIndent()
        // Either it parses the art or it refuses the document; what it must never do is fetch the DTD.
        val image = SvgImageVector.parse(withDoctype, "test")
        if (image != null) assertEquals(1, paths(image).size)
    }

    @Test
    fun `honours autoMirror for a direction icon`() {
        val mirrored = SvgImageVector.parse(
            """<svg viewBox="0 0 24 24"><path d="M0 0 H9 V9 Z"/></svg>""",
            "back",
            autoMirror = true,
        )!!
        assertTrue(mirrored.autoMirror)
    }
}

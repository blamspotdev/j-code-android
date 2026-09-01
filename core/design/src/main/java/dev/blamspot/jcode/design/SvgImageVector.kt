package dev.blamspot.jcode.design

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource

/**
 * A small SVG reader that produces a Compose [ImageVector].
 *
 * Icon packs ship SVG because that is what icon authors export, but Android has no SVG loader and
 * no way to tint a bitmap crisply at every density. Converting to an [ImageVector] on the way in
 * gets both: the art scales, and `Icon`'s tint reaches it like any built-in glyph.
 *
 * The heavy half of the job is already in Compose — [PathParser] speaks the same path grammar SVG's
 * `d` attribute uses — so this file only has to walk the document, resolve inherited presentation
 * attributes, and turn the primitive shapes into path data.
 *
 * Supported: `svg` (`viewBox`, `width`, `height`), `g` (`transform`), `path`, `rect` (including
 * `rx`/`ry`), `circle`, `ellipse`, `line`, `polyline`, `polygon`, presentation attributes inline or
 * in a `style="…"` attribute, and `translate`/`scale`/`rotate`/`matrix` transforms.
 *
 * Not supported, and skipped rather than guessed at: gradients and patterns (a `url(#…)` paint
 * keeps the inherited flat colour), `use`, `text`, filters, masks, and CSS in a `style` element.
 * Icon art rarely uses them; a pack that does looks flat rather than broken.
 */
object SvgImageVector {

    /**
     * Parse [text] as SVG, or return null when it is not SVG this reader understands.
     *
     * [name] names the vector for tooling only. [designSize] is the fallback intrinsic size for a
     * document that declares neither `width`/`height` nor a `viewBox`.
     */
    fun parse(
        text: String,
        name: String,
        autoMirror: Boolean = false,
        designSize: Dp = 24.dp,
    ): ImageVector? = runCatching { read(text, name, autoMirror, designSize) }.getOrNull()

    private fun read(text: String, name: String, autoMirror: Boolean, designSize: Dp): ImageVector? {
        val root = documentElement(text) ?: return null
        if (root.localName() != "svg") return null

        val viewBox = root["viewBox"]?.let { numbers(it) }?.takeIf { it.size == 4 }
        val declaredWidth = root["width"]?.let(::length)
        val declaredHeight = root["height"]?.let(::length)
        val viewportWidth = viewBox?.get(2) ?: declaredWidth ?: designSize.value
        val viewportHeight = viewBox?.get(3) ?: declaredHeight ?: designSize.value
        if (viewportWidth <= 0f || viewportHeight <= 0f) return null

        val builder = ImageVector.Builder(
            name = name,
            defaultWidth = (declaredWidth ?: viewportWidth).dp,
            defaultHeight = (declaredHeight ?: viewportHeight).dp,
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            autoMirror = autoMirror,
        )
        // A viewBox whose origin is not 0,0 shifts every coordinate in the document.
        val originGroups = if (viewBox != null && (viewBox[0] != 0f || viewBox[1] != 0f)) {
            builder.addGroup(translationX = -viewBox[0], translationY = -viewBox[1])
            1
        } else {
            0
        }

        val drew = readChildren(root, builder, SvgStyle.ROOT.inherit(root))
        repeat(originGroups) { builder.clearGroup() }
        // A vector with no paths renders as a silently blank slot, which reads as a host bug rather
        // than as the malformed art it is. Report it as "not parseable" so the caller can fall back.
        return if (drew) builder.build() else null
    }

    /**
     * The document element of [text], or null when it does not parse.
     *
     * Icon art is third-party content, so the parser is closed off first: no DTDs, no external
     * entities, no external schema. An SVG that needs any of them is not an icon.
     */
    private fun documentElement(text: String): Element? = runCatching {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = false
            isExpandEntityReferences = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
            runCatching { setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "") }
        }
        factory.newDocumentBuilder()
            .apply { setEntityResolver { _, _ -> InputSource(java.io.StringReader("")) } }
            .parse(InputSource(java.io.StringReader(text)))
            .documentElement
    }.getOrNull()

    /** Emits every drawable descendant of [parent]. Returns true when at least one path was added. */
    private fun readChildren(parent: Element, builder: ImageVector.Builder, style: SvgStyle): Boolean {
        var drew = false
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val child = children.item(i) as? Element ?: continue
            when (val tag = child.localName()) {
                // Referenced-only or out-of-scope content: skipping the whole subtree is what keeps
                // a gradient stop from drawing itself as a shape.
                in SKIPPED -> Unit

                in CONTAINERS -> {
                    val childStyle = style.inherit(child)
                    val groups = pushTransform(builder, child["transform"])
                    if (readChildren(child, builder, childStyle)) drew = true
                    repeat(groups) { builder.clearGroup() }
                }

                else -> {
                    val data = pathDataFor(tag, child) ?: continue
                    val childStyle = style.inherit(child)
                    val groups = pushTransform(builder, child["transform"])
                    if (addPath(builder, data, childStyle)) drew = true
                    repeat(groups) { builder.clearGroup() }
                }
            }
        }
        return drew
    }

    private fun addPath(builder: ImageVector.Builder, data: String, style: SvgStyle): Boolean {
        if (style.fill == null && style.stroke == null) return false
        val nodes = runCatching { PathParser().parsePathString(data).toNodes() }.getOrNull() ?: return false
        if (nodes.isEmpty()) return false
        builder.addPath(
            pathData = nodes,
            pathFillType = if (style.fillEvenOdd) PathFillType.EvenOdd else PathFillType.NonZero,
            fill = style.fill?.let { SolidColor(it) },
            fillAlpha = style.fillAlpha,
            stroke = style.stroke?.let { SolidColor(it) },
            strokeAlpha = style.strokeAlpha,
            strokeLineWidth = style.strokeWidth,
            strokeLineCap = style.strokeCap,
            strokeLineJoin = style.strokeJoin,
            strokeLineMiter = style.strokeMiter,
        )
        return true
    }

    private val SKIPPED = setOf(
        "defs", "style", "text", "title", "desc", "metadata",
        "clipPath", "mask", "filter", "symbol", "marker", "pattern", "use",
    )
    private val CONTAINERS = setOf("g", "svg", "a", "switch")

    // --- shapes -------------------------------------------------------------------------------

    /** SVG path data for [tag], or null when the element draws nothing this reader handles. */
    private fun pathDataFor(tag: String, e: Element): String? = when (tag) {
        "path" -> e["d"]?.takeIf { it.isNotBlank() }
        "rect" -> rectPath(e)
        "circle" -> e.num("r").takeIf { it > 0f }?.let { r -> ellipsePath(e.num("cx"), e.num("cy"), r, r) }
        "ellipse" -> {
            val rx = e.num("rx")
            val ry = e.num("ry")
            if (rx > 0f && ry > 0f) ellipsePath(e.num("cx"), e.num("cy"), rx, ry) else null
        }
        "line" -> "M${e.num("x1")},${e.num("y1")} L${e.num("x2")},${e.num("y2")}"
        "polyline" -> polyPath(e["points"], close = false)
        "polygon" -> polyPath(e["points"], close = true)
        else -> null
    }

    private fun rectPath(e: Element): String? {
        val w = e.num("width")
        val h = e.num("height")
        if (w <= 0f || h <= 0f) return null
        val x = e.num("x")
        val y = e.num("y")
        // SVG lets one corner radius stand in for the other, and clamps both to half the side.
        val declaredRx = e["rx"]?.let(::length)
        val declaredRy = e["ry"]?.let(::length)
        val rx = (declaredRx ?: declaredRy ?: 0f).coerceIn(0f, w / 2f)
        val ry = (declaredRy ?: declaredRx ?: 0f).coerceIn(0f, h / 2f)
        if (rx <= 0f || ry <= 0f) return "M$x,$y h$w v$h h${-w} z"
        return "M${x + rx},$y" +
            " h${w - 2 * rx}" +
            " a$rx,$ry 0 0 1 $rx,$ry" +
            " v${h - 2 * ry}" +
            " a$rx,$ry 0 0 1 ${-rx},$ry" +
            " h${-(w - 2 * rx)}" +
            " a$rx,$ry 0 0 1 ${-rx},${-ry}" +
            " v${-(h - 2 * ry)}" +
            " a$rx,$ry 0 0 1 $rx,${-ry} z"
    }

    /** Two half-arcs: a single 360° arc is degenerate and draws nothing. */
    private fun ellipsePath(cx: Float, cy: Float, rx: Float, ry: Float): String =
        "M${cx - rx},$cy a$rx,$ry 0 1 0 ${2 * rx},0 a$rx,$ry 0 1 0 ${-2 * rx},0 z"

    private fun polyPath(points: String?, close: Boolean): String? {
        val values = points?.let { numbers(it) } ?: return null
        if (values.size < 4) return null
        return buildString {
            append("M${values[0]},${values[1]}")
            var i = 2
            while (i + 1 < values.size) {
                append(" L${values[i]},${values[i + 1]}")
                i += 2
            }
            if (close) append(" z")
        }
    }

    // --- transforms ---------------------------------------------------------------------------

    /**
     * Opens one Compose group per SVG transform, outermost first, and returns how many to close.
     *
     * One group per primitive on purpose: a Compose group applies scale, then rotation, then
     * translation in a fixed order, so folding a whole `transform` list into a single group would
     * silently reorder it. Nesting preserves the list's own semantics.
     */
    private fun pushTransform(builder: ImageVector.Builder, transform: String?): Int {
        if (transform.isNullOrBlank()) return 0
        var opened = 0
        for (match in TRANSFORM.findAll(transform)) {
            val args = numbers(match.groupValues[2])
            when (match.groupValues[1]) {
                "translate" -> if (args.isNotEmpty()) {
                    builder.addGroup(translationX = args[0], translationY = args.getOrElse(1) { 0f })
                    opened++
                }

                "scale" -> if (args.isNotEmpty()) {
                    builder.addGroup(scaleX = args[0], scaleY = args.getOrElse(1) { args[0] })
                    opened++
                }

                "rotate" -> if (args.isNotEmpty()) {
                    builder.addGroup(
                        rotate = args[0],
                        pivotX = args.getOrElse(1) { 0f },
                        pivotY = args.getOrElse(2) { 0f },
                    )
                    opened++
                }

                // Only an axis-aligned matrix maps onto a Compose group. A sheared one is dropped
                // rather than approximated: a wrong shear is worse than none.
                "matrix" -> if (args.size == 6 && args[1] == 0f && args[2] == 0f) {
                    builder.addGroup(translationX = args[4], translationY = args[5])
                    builder.addGroup(scaleX = args[0], scaleY = args[3])
                    opened += 2
                }
            }
        }
        return opened
    }

    // --- presentation attributes --------------------------------------------------------------

    private class SvgStyle(
        val fill: Color?,
        val stroke: Color?,
        val fillOpacity: Float,
        val strokeOpacity: Float,
        /**
         * The product of every enclosing `opacity`.
         *
         * Kept apart from [fillOpacity] rather than folded into it, because a child that states its
         * own `fill-opacity` replaces that value but must still be dimmed by the groups around it —
         * SVG composites a group as a layer. Folding the two lost the group's share.
         */
        val groupAlpha: Float,
        val fillEvenOdd: Boolean,
        val strokeWidth: Float,
        val strokeCap: StrokeCap,
        val strokeJoin: StrokeJoin,
        val strokeMiter: Float,
    ) {
        val fillAlpha: Float get() = fillOpacity * groupAlpha
        val strokeAlpha: Float get() = strokeOpacity * groupAlpha

        /** This style overlaid by the presentation attributes on [e]. */
        fun inherit(e: Element): SvgStyle {
            val inline = e["style"]?.let(::declarations).orEmpty()
            // An inline `style` declaration outranks the matching presentation attribute, per CSS.
            fun value(key: String): String? = inline[key] ?: e[key]
            val fillRaw = value("fill")
            val strokeRaw = value("stroke")
            return SvgStyle(
                // `?.let` is deliberately not used here: `paint` returns null for `fill="none"`,
                // and an elvis fallback would read that as "absent" and inherit the parent's colour
                // — which is how a deliberately unfilled shape ends up filled.
                fill = if (fillRaw == null) fill else paint(fillRaw, fill),
                stroke = if (strokeRaw == null) stroke else paint(strokeRaw, stroke),
                fillOpacity = value("fill-opacity")?.toFloatOrNull() ?: fillOpacity,
                strokeOpacity = value("stroke-opacity")?.toFloatOrNull() ?: strokeOpacity,
                groupAlpha = groupAlpha * (value("opacity")?.toFloatOrNull() ?: 1f),
                fillEvenOdd = value("fill-rule")?.let { it.trim() == "evenodd" } ?: fillEvenOdd,
                strokeWidth = value("stroke-width")?.let(::length) ?: strokeWidth,
                strokeCap = when (value("stroke-linecap")?.trim()) {
                    "round" -> StrokeCap.Round
                    "square" -> StrokeCap.Square
                    "butt" -> StrokeCap.Butt
                    else -> strokeCap
                },
                strokeJoin = when (value("stroke-linejoin")?.trim()) {
                    "round" -> StrokeJoin.Round
                    "bevel" -> StrokeJoin.Bevel
                    "miter" -> StrokeJoin.Miter
                    else -> strokeJoin
                },
                strokeMiter = value("stroke-miterlimit")?.toFloatOrNull() ?: strokeMiter,
            )
        }

        companion object {
            /** SVG's own initial values: black fill, no stroke. */
            val ROOT = SvgStyle(
                fill = Color.Black,
                stroke = null,
                fillOpacity = 1f,
                strokeOpacity = 1f,
                groupAlpha = 1f,
                fillEvenOdd = false,
                strokeWidth = 1f,
                strokeCap = StrokeCap.Butt,
                strokeJoin = StrokeJoin.Miter,
                strokeMiter = 4f,
            )
        }
    }

    /** `style="fill:#fff;stroke:none"` split into its declarations. */
    private fun declarations(style: String): Map<String, String> =
        style.split(';').mapNotNull { part ->
            val colon = part.indexOf(':')
            if (colon <= 0) return@mapNotNull null
            part.substring(0, colon).trim().lowercase() to part.substring(colon + 1).trim()
        }.toMap()

    /**
     * A paint value. `none` means "do not draw this half"; `currentColor` and an unresolvable
     * `url(#…)` reference keep whatever was inherited, which for a tinted icon is the right answer
     * — `Icon` recolours the whole vector anyway.
     */
    private fun paint(raw: String, inherited: Color?): Color? {
        val value = raw.trim()
        return when {
            value.equals("none", ignoreCase = true) -> null
            value.equals("transparent", ignoreCase = true) -> null
            value.equals("currentColor", ignoreCase = true) -> inherited ?: Color.Black
            value.startsWith("url(") -> inherited ?: Color.Black
            else -> color(value) ?: inherited
        }
    }

    private fun color(value: String): Color? {
        if (value.startsWith("#")) return hexColor(value.substring(1))
        RGB.matchEntire(value)?.let { return rgbColor(it.groupValues[1]) }
        return NAMED[value.lowercase()]
    }

    private fun hexColor(hex: String): Color? {
        // #rgb and #rgba are shorthand for each digit doubled.
        val expanded = if (hex.length == 3 || hex.length == 4) hex.map { "$it$it" }.joinToString("") else hex
        val bits = expanded.toLongOrNull(16) ?: return null
        return when (expanded.length) {
            6 -> Color(0xFF000000L.or(bits).toInt())
            // #rrggbbaa puts alpha last; Compose wants it first.
            8 -> Color(((bits ushr 8) or ((bits and 0xFF) shl 24)).toInt())
            else -> null
        }
    }

    private fun rgbColor(args: String): Color? {
        val parts = args.split(',').map { it.trim() }
        if (parts.size < 3) return null
        val channels = parts.take(3).map { part ->
            val raw = if (part.endsWith("%")) {
                (part.dropLast(1).toFloatOrNull() ?: return null) / 100f * 255f
            } else {
                part.toFloatOrNull() ?: return null
            }
            raw.toInt().coerceIn(0, 255)
        }
        val alpha = parts.getOrNull(3)?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        return Color(channels[0], channels[1], channels[2], (alpha * 255f).toInt())
    }

    // --- primitives ---------------------------------------------------------------------------

    /** A length, ignoring any CSS unit suffix. Icon art is unitless or in px; the rest is rare. */
    private fun length(raw: String): Float? = LENGTH.find(raw)?.value?.toFloatOrNull()

    private fun numbers(raw: String): List<Float> =
        LENGTH.findAll(raw).mapNotNull { it.value.toFloatOrNull() }.toList()

    /** The tag name without its prefix — namespaces are off, so `svg:path` arrives whole. */
    private fun Element.localName(): String = tagName.substringAfterLast(':')

    private operator fun Element.get(attr: String): String? {
        val attributes = attributes ?: return null
        for (i in 0 until attributes.length) {
            val node: Node = attributes.item(i)
            if (node.nodeName.substringAfterLast(':') == attr) return node.nodeValue
        }
        return null
    }

    private fun Element.num(attr: String): Float = this[attr]?.let(::length) ?: 0f

    private val LENGTH = Regex("[-+]?(?:\\d*\\.\\d+|\\d+\\.?)(?:[eE][-+]?\\d+)?")
    private val TRANSFORM = Regex("(translate|scale|rotate|matrix|skewX|skewY)\\s*\\(([^)]*)\\)")
    private val RGB = Regex("rgba?\\(([^)]*)\\)", RegexOption.IGNORE_CASE)

    /** The handful of CSS colour names icon art actually uses; anything else keeps the inherited paint. */
    private val NAMED = mapOf(
        "black" to Color.Black, "white" to Color.White, "red" to Color(0xFFFF0000),
        "green" to Color(0xFF008000), "blue" to Color(0xFF0000FF), "yellow" to Color(0xFFFFFF00),
        "orange" to Color(0xFFFFA500), "purple" to Color(0xFF800080), "gray" to Color(0xFF808080),
        "grey" to Color(0xFF808080), "silver" to Color(0xFFC0C0C0), "navy" to Color(0xFF000080),
        "teal" to Color(0xFF008080), "cyan" to Color(0xFF00FFFF), "magenta" to Color(0xFFFF00FF),
        "lime" to Color(0xFF00FF00), "maroon" to Color(0xFF800000), "olive" to Color(0xFF808000),
    )
}

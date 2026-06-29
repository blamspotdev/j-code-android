package dev.jcode.editor

import dev.jcode.core.editor.decor.ColoredSpan
import dev.jcode.feature.marketplace.LanguagePack

/** Token colors for syntax highlighting; chosen per light/dark theme. */
data class TokenPalette(
    val keyword: Int,
    val type: Int,
    val string: Int,
    val comment: Int,
    val number: Int,
    val function: Int,
    val variable: Int,
    val constant: Int,
    val property: Int,
    val operator: Int,
    val annotation: Int,
) {
    companion object {
        val DARK = TokenPalette(
            keyword = 0xFFCBA6F7.toInt(),
            type = 0xFF89DCEB.toInt(),
            string = 0xFFA6E3A1.toInt(),
            comment = 0xFF7F849C.toInt(),
            number = 0xFFFAB387.toInt(),
            function = 0xFF89B4FA.toInt(),
            variable = 0xFFB4BEFE.toInt(),
            constant = 0xFFEBA0AC.toInt(),
            property = 0xFF94E2D5.toInt(),
            operator = 0xFFBAC2DE.toInt(),
            annotation = 0xFFF9E2AF.toInt(),
        )
        val LIGHT = TokenPalette(
            keyword = 0xFF8839EF.toInt(),
            type = 0xFF179299.toInt(),
            string = 0xFF40A02B.toInt(),
            comment = 0xFF8C8FA1.toInt(),
            number = 0xFFFE640B.toInt(),
            function = 0xFF1E66F5.toInt(),
            variable = 0xFF7287FD.toInt(),
            constant = 0xFFE64553.toInt(),
            property = 0xFF04A5E5.toInt(),
            operator = 0xFF6C6F85.toInt(),
            annotation = 0xFFDF8E1D.toInt(),
        )
    }
}

/**
 * A small, dependency-free tokenizer that produces [ColoredSpan]s (byte offsets) for an editor
 * buffer, driven by a [LanguagePack]'s syntax rules plus language-agnostic heuristics. It colors
 * comments, strings, numbers, operators, annotations/decorators, and classifies identifiers as
 * keyword / type / function-call / constant / property / variable. Not a full grammar, but it covers
 * the common "code coloring" need without a native grammar. Spans use UTF-8 byte offsets to match the
 * renderer.
 */
object SyntaxHighlighter {
    private val OPERATORS = "+-*/%=<>!&|^~?:".toCharArray().toHashSet()

    fun highlight(text: String, lang: LanguagePack, palette: TokenPalette): List<ColoredSpan> {
        val n = text.length
        if (n == 0) return emptyList()

        // char index -> UTF-8 byte offset (the renderer addresses spans by byte).
        val byteAt = IntArray(n + 1)
        var b = 0
        for (i in 0 until n) {
            byteAt[i] = b
            val code = text[i].code
            b += when {
                code < 0x80 -> 1
                code < 0x800 -> 2
                text[i].isHighSurrogate() -> 4
                text[i].isLowSurrogate() -> 0
                else -> 3
            }
        }
        byteAt[n] = b

        val spans = ArrayList<ColoredSpan>()
        fun add(start: Int, end: Int, color: Int) {
            if (end > start) spans.add(ColoredSpan(byteAt[start], byteAt[end], color))
        }

        val lineComment = lang.lineComment
        val blockStart = lang.blockCommentStart
        val blockEnd = lang.blockCommentEnd
        val delimiters = lang.stringDelimiters

        var i = 0
        while (i < n) {
            val c = text[i]
            // line comment
            if (lineComment != null && text.startsWith(lineComment, i)) {
                var j = i
                while (j < n && text[j] != '\n') j++
                add(i, j, palette.comment); i = j; continue
            }
            // block comment
            if (blockStart != null && blockEnd != null && text.startsWith(blockStart, i)) {
                val end = text.indexOf(blockEnd, i + blockStart.length)
                val j = if (end < 0) n else end + blockEnd.length
                add(i, j, palette.comment); i = j; continue
            }
            // string
            val delim = delimiters.firstOrNull { text.startsWith(it, i) }
            if (delim != null) {
                val multiline = delim == "`"
                var j = i + delim.length
                while (j < n) {
                    val cj = text[j]
                    if (cj == '\\') { j += 2; continue }
                    if (!multiline && cj == '\n') break
                    if (text.startsWith(delim, j)) { j += delim.length; break }
                    j++
                }
                if (j > n) j = n
                add(i, j, palette.string); i = j; continue
            }
            // annotation / decorator: @name  (Java/C#/TS/Python)
            if (c == '@' && i + 1 < n && (text[i + 1].isLetter() || text[i + 1] == '_')) {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                add(i, j, palette.annotation); i = j; continue
            }
            // number
            if (c.isDigit()) {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                add(i, j, palette.number); i = j; continue
            }
            // identifier -> keyword / type / function / constant / property / variable
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                // next non-space char: '(' marks a function call/definition
                var k = j
                while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                val isCall = k < n && text[k] == '('
                // previous non-space char: '.' marks member/property access
                var p = i - 1
                while (p >= 0 && (text[p] == ' ' || text[p] == '\t')) p--
                val isMember = p >= 0 && text[p] == '.'
                val color = when {
                    word in lang.keywords -> palette.keyword
                    word in lang.types -> palette.type
                    isCall -> palette.function
                    isConstantName(word) -> palette.constant
                    isMember -> palette.property
                    else -> palette.variable
                }
                add(i, j, color); i = j; continue
            }
            // operators (+, -, =, =>, &&, etc.); comments are handled above so '/' is safe here
            if (c in OPERATORS) {
                var j = i
                while (j < n && text[j] in OPERATORS) j++
                add(i, j, palette.operator); i = j; continue
            }
            i++
        }
        return spans
    }

    // ALL_CAPS identifier (at least one letter; only A-Z, 0-9, _), e.g. MAX_VALUE, PI — treated as a
    // constant. Single chars (T, K) are left as plain identifiers to avoid mis-coloring type params.
    private fun isConstantName(w: String): Boolean {
        if (w.length < 2) return false
        var hasLetter = false
        for (ch in w) {
            when (ch) {
                in 'A'..'Z' -> hasLetter = true
                in '0'..'9', '_' -> {}
                else -> return false
            }
        }
        return hasLetter
    }
}

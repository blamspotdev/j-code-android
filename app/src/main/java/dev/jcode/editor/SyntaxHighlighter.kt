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
) {
    companion object {
        val DARK = TokenPalette(
            keyword = 0xFFCBA6F7.toInt(),
            type = 0xFF89DCEB.toInt(),
            string = 0xFFA6E3A1.toInt(),
            comment = 0xFF7F849C.toInt(),
            number = 0xFFFAB387.toInt(),
        )
        val LIGHT = TokenPalette(
            keyword = 0xFF8839EF.toInt(),
            type = 0xFF179299.toInt(),
            string = 0xFF40A02B.toInt(),
            comment = 0xFF8C8FA1.toInt(),
            number = 0xFFFE640B.toInt(),
        )
    }
}

/**
 * A small, dependency-free tokenizer that produces [ColoredSpan]s (byte offsets) for an editor
 * buffer, driven by a [LanguagePack]'s syntax rules. Not a full grammar — it colors comments,
 * strings, numbers, and keyword/type identifiers, which covers the "code coloring" need without a
 * native grammar. Spans use UTF-8 byte offsets to match the renderer.
 */
object SyntaxHighlighter {
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
            // number
            if (c.isDigit()) {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '.' || text[j] == '_')) j++
                add(i, j, palette.number); i = j; continue
            }
            // identifier -> keyword / type
            if (c.isLetter() || c == '_') {
                var j = i
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '_')) j++
                val word = text.substring(i, j)
                when (word) {
                    in lang.keywords -> add(i, j, palette.keyword)
                    in lang.types -> add(i, j, palette.type)
                }
                i = j; continue
            }
            i++
        }
        return spans
    }
}

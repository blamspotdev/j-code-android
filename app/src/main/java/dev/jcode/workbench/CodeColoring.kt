package dev.jcode.workbench

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import dev.jcode.core.editor.decor.ColoredSpan
import dev.jcode.editor.SyntaxHighlighter
import dev.jcode.editor.TokenPalette
import dev.jcode.feature.marketplace.LanguagePack
import dev.jcode.lsp.SemanticToken

/**
 * What DevTools can call on to colour a page source.
 *
 * A composition local rather than parameters because the panel sits five composables deep in the
 * right sidebar, none of which has anything else to do with language support; threading a resolver
 * and a suspend function through all of them would put the workbench's shell in the business of
 * syntax colouring. Both members default to doing nothing, which is also what they do when no Dev
 * Pack and no language server are installed.
 */
data class DevToolsCodeSupport(
    val packResolver: (String) -> LanguagePack? = { null },
    val semanticTokens: suspend (String, String) -> List<SemanticToken> = { _, _ -> emptyList() },
)

val LocalDevToolsCodeSupport = staticCompositionLocalOf { DevToolsCodeSupport() }

/**
 * Colouring for source the DevTools panes show — page scripts, stylesheets, the DOM.
 *
 * The same three-layer arrangement the editor uses, in the same order of authority, because a page
 * source is a source: JCode's built-in Markdown and JSON colouring, then an installed Dev Pack's
 * rules for the language, then a generic tokenizer so nothing is ever left unlit. On top of that,
 * a language server's own classification when one happens to be running and willing — see
 * [dev.jcode.lsp.LspController.detachedSemanticTokens] — which is the only layer that knows a
 * *name* from a *function call* rather than guessing from shape.
 *
 * Deliberately never a reason for anything to fail. Every layer above the generic tokenizer is
 * optional, and the pane's job is to show the text.
 */
internal object CodeColoring {

    /**
     * Above this, colouring is skipped and the text is shown plain.
     *
     * A page's serialised DOM runs to megabytes, and tokenizing one to draw a panel nobody is
     * reading that far down would cost more than the panel is worth.
     */
    const val MAX_COLORED_CHARS = 1_500_000

    /**
     * A line longer than this is not a line anybody wrote — it is a minified bundle, and the
     * source is worth offering [prettyPrint] for.
     */
    const val MINIFIED_LINE_CHARS = 2_000

    /**
     * The most of one line the panes will draw.
     *
     * Not a nicety. Compose measures a no-wrap line by laying out every glyph in it to find its
     * width, so a 400 KB minified row is hundreds of thousands of glyph advances on the main
     * thread — long enough for Android to put up "isn't responding", which is what it did. Nobody
     * reads a row that long anyway; [prettyPrint] is how the rest of it becomes visible.
     */
    const val MAX_LINE_CHARS = 1_000

    private const val CLIP_MARK = " …"

    /** One line as it can safely be drawn. */
    fun clipLine(line: String): String =
        if (line.length <= MAX_LINE_CHARS) line else line.take(MAX_LINE_CHARS) + CLIP_MARK

    fun looksMinified(text: String): Boolean {
        var run = 0
        for (c in text) {
            if (c == '\n') run = 0 else if (++run > MINIFIED_LINE_CHARS) return true
        }
        return false
    }

    /**
     * Put the line breaks back into a minified script or stylesheet.
     *
     * Every script a real site serves arrives on one line, which defeats line numbers, defeats the
     * console's jump-to-line, and makes a 400 KB stylesheet a single row that scrolls sideways for
     * a screen and a half. Chrome answers this with its `{}` button; this is the same idea.
     *
     * Breaks only on structural punctuation, and only when the scanner is not inside a string, a
     * template literal or a comment — the point of reformatting is to end up with the same program
     * laid out differently, not a program with newlines inside its string literals. Semicolons
     * inside parentheses are left alone so `for (a; b; c)` survives.
     *
     * It is not a formatter and does not try to be: no attempt at operators, wrapping, or the many
     * places a real one would break. It converts one unreadable line into many readable ones.
     */
    fun prettyPrint(src: String): String {
        if (src.length > MAX_COLORED_CHARS) return src
        val sb = StringBuilder(src.length + src.length / 8)
        var indent = 0
        var parens = 0
        var i = 0
        var quote: Char? = null
        var inLineComment = false
        var inBlockComment = false
        fun newline() {
            while (sb.isNotEmpty() && sb.last() == ' ') sb.setLength(sb.length - 1)
            sb.append('\n')
            repeat(indent.coerceIn(0, 30)) { sb.append("  ") }
        }
        while (i < src.length) {
            val c = src[i]
            when {
                inLineComment -> {
                    sb.append(c)
                    if (c == '\n') { inLineComment = false; repeat(indent.coerceIn(0, 30)) { sb.append("  ") } }
                }
                inBlockComment -> {
                    sb.append(c)
                    if (c == '*' && i + 1 < src.length && src[i + 1] == '/') { sb.append('/'); i++; inBlockComment = false }
                }
                quote != null -> {
                    sb.append(c)
                    if (c == '\\' && i + 1 < src.length) { sb.append(src[i + 1]); i++ } else if (c == quote) quote = null
                }
                c == '"' || c == '\'' || c == '`' -> { quote = c; sb.append(c) }
                // Not every `//` starts a comment. A stylesheet has none at all, and the `//` that
                // shows up in one is the scheme separator of an unquoted `url(https://…)` — reading
                // that as a comment swallows the rest of the file, which on a minified bundle is
                // the whole file. A preceding colon, or being inside parentheses, means URL.
                c == '/' && i + 1 < src.length && src[i + 1] == '/' &&
                    parens == 0 && sb.lastOrNull() != ':' -> {
                    inLineComment = true
                    sb.append("//")
                    i++
                }
                c == '/' && i + 1 < src.length && src[i + 1] == '*' -> { inBlockComment = true; sb.append("/*"); i++ }
                c == '(' -> { parens++; sb.append(c) }
                c == ')' -> { if (parens > 0) parens--; sb.append(c) }
                c == '{' -> { sb.append(c); indent++; newline() }
                c == '}' -> { indent--; newline(); sb.append(c); newline() }
                c == ';' && parens == 0 -> { sb.append(c); newline() }
                c == '\n' -> newline()
                else -> sb.append(c)
            }
            i++
        }
        return sb.toString().lineSequence().joinToString("\n") { it.trimEnd() }.trim('\n')
    }

    /**
     * A pseudo file name for a page source, which is how the highlighter is told what language it
     * is looking at. Real DevTools has the MIME type; here the URL's own extension is the honest
     * signal, and [kind] is the fallback for the many script URLs that carry none.
     */
    fun pseudoFileName(url: String, kind: String): String {
        val tail = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = tail.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) return "source.$ext"
        return when (kind) {
            "document" -> "source.html"
            "style" -> "source.css"
            else -> "source.js"
        }
    }

    /**
     * [text] as styled lines, or null when it is too large to be worth colouring.
     *
     * Off the main thread by contract — the tokenizer walks the whole document.
     */
    fun coloredLines(
        text: String,
        fileName: String,
        pack: LanguagePack?,
        palette: TokenPalette,
        semantic: List<SemanticToken> = emptyList(),
    ): List<AnnotatedString>? {
        if (text.length > MAX_COLORED_CHARS) return null
        val spans = runCatching { SyntaxHighlighter.highlightFor(text, fileName, pack, palette) }
            .getOrDefault(emptyList())
        val tokenized = toCharRanges(text, spans)
        // The server's classification wins where the two disagree: the tokenizer guesses a
        // language from punctuation, and the server has actually resolved the program.
        val runs = if (semantic.isEmpty()) tokenized else overlay(tokenized, semantic, palette)
        if (runs.isEmpty()) return null
        return splitColored(text, runs)
    }

    /**
     * Cut [text] into styled lines in one pass over [runs].
     *
     * Both lists are already in order, so a single cursor walks them together. The obvious
     * alternative — build one styled document and take a `subSequence` per line — re-filters the
     * whole span list on every call, which on a minified bundle is tens of thousands of spans
     * against tens of thousands of lines and hangs the app outright.
     */
    private fun splitColored(text: String, runs: List<Run>): List<AnnotatedString> {
        val out = ArrayList<AnnotatedString>(text.count { it == '\n' } + 1)
        var lineStart = 0
        var first = 0
        while (true) {
            val nl = text.indexOf('\n', lineStart)
            val lineEnd = if (nl < 0) text.length else nl
            val drawnEnd = minOf(lineEnd, lineStart + MAX_LINE_CHARS)
            val builder = AnnotatedString.Builder(drawnEnd - lineStart)
            builder.append(text.substring(lineStart, drawnEnd))
            if (drawnEnd < lineEnd) builder.append(CLIP_MARK)
            while (first < runs.size && runs[first].end <= lineStart) first++
            var k = first
            while (k < runs.size && runs[k].start < drawnEnd) {
                val s = maxOf(runs[k].start, lineStart) - lineStart
                val e = minOf(runs[k].end, drawnEnd) - lineStart
                if (e > s) builder.addStyle(SpanStyle(color = Color(runs[k].color)), s, e)
                k++
            }
            out.add(builder.toAnnotatedString())
            if (nl < 0) break
            lineStart = nl + 1
        }
        return out
    }

    /** One coloured run, in UTF-16 indices into the source. */
    private data class Run(val start: Int, val end: Int, val color: Int)

    /**
     * [ColoredSpan]s address the buffer in UTF-8 bytes, matching the editor's renderer; a Compose
     * string is indexed in UTF-16. The two agree only for ASCII, and page sources are full of text
     * that is not.
     */
    private fun toCharRanges(text: String, spans: List<ColoredSpan>): List<Run> {
        if (spans.isEmpty()) return emptyList()
        val byteLen = text.sumOf { utf8Len(it) }
        val byteToChar = IntArray(byteLen + 1)
        var b = 0
        text.forEachIndexed { charIdx, ch ->
            val n = utf8Len(ch)
            for (k in 0 until n) byteToChar[b + k] = charIdx
            b += n
        }
        byteToChar[byteLen] = text.length

        val out = ArrayList<Run>(spans.size)
        var cursor = 0
        for (span in spans.sortedBy { it.startByte }) {
            val start = byteToChar[span.startByte.coerceIn(0, byteLen)]
            val end = byteToChar[span.endByte.coerceIn(0, byteLen)]
            if (start < cursor || end <= start) continue
            out.add(Run(start, end, span.color))
            cursor = end
        }
        return out
    }

    private fun utf8Len(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        else -> 3
    }

    /** Replace tokenizer runs wherever a server classified the same text, keeping the rest. */
    private fun overlay(base: List<Run>, semantic: List<SemanticToken>, palette: TokenPalette): List<Run> {
        val fromServer = semantic.mapNotNull { token ->
            colorFor(token.type, palette)?.let { Run(token.start, token.end, it) }
        }.sortedBy { it.start }
        if (fromServer.isEmpty()) return base
        val kept = base.filterNot { run ->
            fromServer.any { it.start < run.end && run.start < it.end }
        }
        var cursor = -1
        return (kept + fromServer).sortedBy { it.start }.filter { run ->
            // Overlapping runs cannot both be applied in one pass; the earlier one wins, which
            // after the sort is the one already placed.
            if (run.start >= cursor) { cursor = run.end; true } else false
        }
    }

    /**
     * The standard LSP token types, onto the palette the editor already uses.
     *
     * Servers may extend the legend with their own names; anything unrecognised returns null and
     * leaves the tokenizer's own guess in place rather than colouring it wrong.
     */
    private fun colorFor(type: String, palette: TokenPalette): Int? = when (type) {
        "keyword", "modifier" -> palette.keyword
        "type", "class", "interface", "enum", "struct", "typeParameter", "namespace" -> palette.type
        "string", "regexp" -> palette.string
        "comment" -> palette.comment
        "number" -> palette.number
        "function", "method" -> palette.function
        "variable", "parameter" -> palette.variable
        "property", "enumMember", "event" -> palette.property
        "operator" -> palette.operator
        "decorator", "macro" -> palette.annotation
        else -> null
    }

}

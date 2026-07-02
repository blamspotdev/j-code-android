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
 * buffer. [highlight] is driven by a [LanguagePack]'s syntax rules; [highlightMarkdown] does
 * structural Markdown coloring; [highlightGeneric] is the built-in fallback for files with no pack.
 * Use [highlightFor] to pick the right one. Not a full grammar, but it covers the common "code
 * coloring" need without a native grammar. Spans use UTF-8 byte offsets to match the renderer.
 */
object SyntaxHighlighter {
    private val OPERATORS = "+-*/%=<>!&|^~?:".toCharArray().toHashSet()

    /**
     * Resolve the right highlighter for a file. Markdown and JSON are built into J Code, so they color
     * natively and authoritatively — ahead of any installed pack — with no extension required. Otherwise
     * an installed [lang] pack drives coloring, falling back to the generic tokenizer so every buffer is lit.
     */
    fun highlightFor(text: String, fileName: String, lang: LanguagePack?, palette: TokenPalette): List<ColoredSpan> =
        when {
            isMarkdownFile(fileName) -> highlightMarkdown(text, palette)
            isJsonFile(fileName) -> highlightJson(text, palette)
            lang != null -> highlight(text, lang, palette)
            else -> highlightGeneric(text, palette)
        }

    fun isMarkdownFile(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".md") || l.endsWith(".markdown") || l.endsWith(".mdown") ||
            l.endsWith(".mkd") || l.endsWith(".mkdn")
    }

    fun isJsonFile(name: String): Boolean {
        val l = name.lowercase()
        return l.endsWith(".json") || l.endsWith(".jsonc") || l.endsWith(".json5") || l.endsWith(".webmanifest")
    }

    /**
     * Language-pack-driven coloring. Markup languages (HTML/XML/SVG — detected by their `<!-- -->` block
     * comment) use the structural [highlightMarkup] pass instead of the keyword tokenizer: the tokenizer
     * would only match bare tag names that happen to be in the keyword list and would mis-color common
     * words (`a`, `code`, `link`, `title`, `path`, ...) wherever they appear in text content.
     */
    fun highlight(text: String, lang: LanguagePack, palette: TokenPalette): List<ColoredSpan> =
        when {
            isMarkupLanguage(lang) -> highlightMarkup(text, palette)
            isKeyValueLanguage(lang) -> {
                val yaml = lang.languageId.lowercase() in YAML_LANGS
                highlightKeyValue(
                    text, palette,
                    sep = if (yaml) ':' else '=',
                    // INI also accepts ';' comments on top of the pack's '#'.
                    lineComments = listOfNotNull(lang.lineComment) +
                        (if (lang.languageId.equals("ini", true)) listOf(";") else emptyList()),
                    delimiters = lang.stringDelimiters,
                    boolWords = lang.keywords,
                    sections = !yaml,
                )
            }
            else -> tokenize(
                text, palette,
                lineComments = listOfNotNull(lang.lineComment),
                blockStart = lang.blockCommentStart,
                blockEnd = lang.blockCommentEnd,
                delimiters = lang.stringDelimiters,
                keywords = lang.keywords,
                types = lang.types,
            )
        }

    /** Markup = uses the `<!-- -->` comment form (HTML, XML, SVG and friends). */
    private fun isMarkupLanguage(lang: LanguagePack): Boolean =
        lang.blockCommentStart == "<!--" && lang.blockCommentEnd == "-->"

    private val YAML_LANGS = hashSetOf("yaml", "yml")
    private val KEY_VALUE_LANGS = hashSetOf("yaml", "yml", "toml", "ini")

    /** Line-oriented `key: value` / `key = value` config languages (YAML, TOML, INI). */
    private fun isKeyValueLanguage(lang: LanguagePack): Boolean =
        lang.languageId.lowercase() in KEY_VALUE_LANGS

    /**
     * Built-in fallback coloring for files with no language pack: strings, numbers, `//` and (space-led)
     * `# ` line comments, `/* */` block comments, and a broad common-keyword/type set. `#` requires a
     * trailing space so C/CSS `#include` / `#id` / `#fff` are not mistaken for comments.
     */
    fun highlightGeneric(text: String, palette: TokenPalette): List<ColoredSpan> =
        tokenize(
            text, palette,
            lineComments = GENERIC_LINE_COMMENTS,
            blockStart = "/*",
            blockEnd = "*/",
            delimiters = GENERIC_STRING_DELIMS,
            keywords = GENERIC_KEYWORDS,
            types = GENERIC_TYPES,
        )

    /** Shared char-by-char state-machine tokenizer (comments / strings / numbers / identifiers / ops). */
    private fun tokenize(
        text: String,
        palette: TokenPalette,
        lineComments: List<String>,
        blockStart: String?,
        blockEnd: String?,
        delimiters: List<String>,
        keywords: Set<String>,
        types: Set<String>,
    ): List<ColoredSpan> {
        val n = text.length
        if (n == 0) return emptyList()
        val byteAt = byteOffsets(text)
        val lineCom = lineComments.filter { it.isNotEmpty() }
        val spans = ArrayList<ColoredSpan>()
        fun add(start: Int, end: Int, color: Int) {
            if (end > start) spans.add(ColoredSpan(byteAt[start], byteAt[end], color))
        }

        var i = 0
        while (i < n) {
            val c = text[i]
            // line comment
            if (lineCom.any { text.startsWith(it, i) }) {
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
                var k = j
                while (k < n && (text[k] == ' ' || text[k] == '\t')) k++
                val isCall = k < n && text[k] == '('
                var p = i - 1
                while (p >= 0 && (text[p] == ' ' || text[p] == '\t')) p--
                val isMember = p >= 0 && text[p] == '.'
                val color = when {
                    word in keywords -> palette.keyword
                    word in types -> palette.type
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

    /**
     * Structural Markdown coloring: ATX headings, fenced + inline code, bold / italic, links / images,
     * list markers, blockquotes, and horizontal rules — mapped onto the existing [TokenPalette] slots.
     */
    fun highlightMarkdown(text: String, palette: TokenPalette): List<ColoredSpan> {
        val n = text.length
        if (n == 0) return emptyList()
        val byteAt = byteOffsets(text)
        val spans = ArrayList<ColoredSpan>()
        fun add(start: Int, end: Int, color: Int) {
            if (end > start) spans.add(ColoredSpan(byteAt[start], byteAt[end], color))
        }
        // Inline scan over [start, end): code spans, links/images, bold, then italic.
        fun inline(start: Int, end: Int) {
            var k = start
            while (k < end) {
                val ch = text[k]
                if (ch == '`') {
                    var j = k + 1
                    while (j < end && text[j] != '`') j++
                    val close = if (j < end) j + 1 else end
                    add(k, close, palette.string); k = close; continue
                }
                if (ch == '[' || (ch == '!' && k + 1 < end && text[k + 1] == '[')) {
                    val open = if (ch == '!') k + 1 else k
                    val closeB = text.indexOf(']', open + 1)
                    if (closeB in (open + 1) until end && closeB + 1 < end && text[closeB + 1] == '(') {
                        val closeP = text.indexOf(')', closeB + 2)
                        if (closeP in (closeB + 2) until end) {
                            add(k, closeB + 1, palette.type)             // [text] (incl. leading !)
                            add(closeB + 1, closeP + 1, palette.string)  // (url)
                            k = closeP + 1; continue
                        }
                    }
                }
                if ((ch == '*' || ch == '_') && k + 1 < end && text[k + 1] == ch) {
                    val marker = "$ch$ch"
                    val j = text.indexOf(marker, k + 2)
                    if (j in (k + 2) until end) { add(k, j + 2, palette.constant); k = j + 2; continue }
                }
                if (ch == '*' || ch == '_') {
                    var t = k + 1
                    while (t < end && text[t] != ch) t++
                    if (t < end && t > k + 1) { add(k, t + 1, palette.property); k = t + 1; continue }
                }
                k++
            }
        }

        var inFence = false
        var fenceMarker = ""
        var i = 0
        while (i < n) {
            val lineStart = i
            var lineEnd = i
            while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
            val nextLine = if (lineEnd < n) lineEnd + 1 else lineEnd

            var t = lineStart
            while (t < lineEnd && (text[t] == ' ' || text[t] == '\t')) t++
            val fence = when {
                text.startsWith("```", t) -> "```"
                text.startsWith("~~~", t) -> "~~~"
                else -> null
            }
            if (inFence) {
                add(lineStart, lineEnd, palette.string)
                if (fence != null && fence == fenceMarker) inFence = false
                i = nextLine; continue
            }
            if (fence != null) {
                add(lineStart, lineEnd, palette.string)
                inFence = true; fenceMarker = fence
                i = nextLine; continue
            }
            // ATX heading (#..###### then space/eol)
            if (t < lineEnd && text[t] == '#') {
                var h = t
                var hashes = 0
                while (h < lineEnd && text[h] == '#') { h++; hashes++ }
                if (hashes in 1..6 && (h >= lineEnd || text[h] == ' ' || text[h] == '\t')) {
                    add(lineStart, lineEnd, palette.keyword); i = nextLine; continue
                }
            }
            // horizontal rule (>=3 of - * _)
            if (isHrLine(text, t, lineEnd)) {
                add(lineStart, lineEnd, palette.operator); i = nextLine; continue
            }
            // blockquote
            if (t < lineEnd && text[t] == '>') {
                var q = t
                while (q < lineEnd && (text[q] == '>' || text[q] == ' ')) q++
                add(t, q, palette.comment)
                inline(q, lineEnd); i = nextLine; continue
            }
            // list marker, then inline content
            val mEnd = listMarkerEnd(text, t, lineEnd)
            var contentStart = t
            if (mEnd > t) { add(t, mEnd, palette.operator); contentStart = mEnd }
            inline(contentStart, lineEnd)
            i = nextLine
        }
        return spans
    }

    /**
     * Structural coloring for markup (HTML / XML / SVG): `<!-- -->` comments, tag delimiters, tag names,
     * attribute names, quoted attribute values, and `&entity;` references — mapped onto the [TokenPalette].
     * Text content between tags is intentionally left uncolored. Unlike the keyword tokenizer it is aware
     * of `<...>` structure, so it never colors a word like `code` or `link` when it appears in body text.
     */
    fun highlightMarkup(text: String, palette: TokenPalette): List<ColoredSpan> {
        val n = text.length
        if (n == 0) return emptyList()
        val byteAt = byteOffsets(text)
        val spans = ArrayList<ColoredSpan>()
        fun add(start: Int, end: Int, color: Int) {
            if (end > start) spans.add(ColoredSpan(byteAt[start], byteAt[end], color))
        }
        fun isNameChar(ch: Char) =
            ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == ':' || ch == '.'

        var i = 0
        while (i < n) {
            val c = text[i]
            // comment <!-- ... -->
            if (text.startsWith("<!--", i)) {
                val end = text.indexOf("-->", i + 4)
                val j = if (end < 0) n else end + 3
                add(i, j, palette.comment); i = j; continue
            }
            // entity: &name; or &#123;
            if (c == '&') {
                var j = i + 1
                while (j < n && (text[j].isLetterOrDigit() || text[j] == '#')) j++
                if (j < n && text[j] == ';') j++
                if (j > i + 1) { add(i, j, palette.constant); i = j; continue }
                i++; continue
            }
            // tag: < ... >  (also </close>, <!doctype ...>, <?pi ...?>)
            if (c == '<') {
                var j = i + 1
                add(i, i + 1, palette.operator)                 // '<'
                if (j < n && text[j] in "/!?") { add(j, j + 1, palette.operator); j++ }  // '/', '!', '?'
                while (j < n && (text[j] == ' ' || text[j] == '\t')) j++
                val nameStart = j
                while (j < n && isNameChar(text[j])) j++
                if (j > nameStart) add(nameStart, j, palette.keyword)   // tag name
                while (j < n && text[j] != '>') {
                    val a = text[j]
                    when {
                        a == ' ' || a == '\t' || a == '\n' || a == '\r' -> j++
                        a == '/' || a == '?' || a == '=' -> { add(j, j + 1, palette.operator); j++ }
                        a == '"' || a == '\'' -> {
                            var k = j + 1
                            while (k < n && text[k] != a) k++
                            val close = if (k < n) k + 1 else n
                            add(j, close, palette.string); j = close   // attribute value
                        }
                        isNameChar(a) -> {
                            val attrStart = j
                            while (j < n && isNameChar(text[j])) j++
                            add(attrStart, j, palette.property)         // attribute name
                        }
                        else -> j++
                    }
                }
                if (j < n && text[j] == '>') { add(j, j + 1, palette.operator); j++ }  // '>'
                i = j; continue
            }
            i++   // text content: uncolored
        }
        return spans
    }

    /**
     * Structural coloring for line-oriented config/data languages (YAML, TOML, INI): keys get the
     * property color so they read distinctly from values, the `:`/`=` [sep] is an operator, `[section]`
     * / `[[table]]` headers and YAML `---` markers stand out, and values are scanned for quoted strings,
     * numbers, [boolWords] (true/false/null/...) and trailing inline comments. Unlike the keyword
     * tokenizer this is structure-aware, so a key never shares the color of an arbitrary value word.
     */
    fun highlightKeyValue(
        text: String,
        palette: TokenPalette,
        sep: Char,
        lineComments: List<String>,
        delimiters: List<String>,
        boolWords: Set<String>,
        sections: Boolean,
    ): List<ColoredSpan> {
        val n = text.length
        if (n == 0) return emptyList()
        val byteAt = byteOffsets(text)
        val comments = lineComments.filter { it.isNotEmpty() }
        val spans = ArrayList<ColoredSpan>()
        fun add(start: Int, end: Int, color: Int) {
            if (end > start) spans.add(ColoredSpan(byteAt[start], byteAt[end], color))
        }
        // Color a value range: quoted strings, numbers, boolean/null words, and a trailing inline comment.
        fun value(start: Int, end: Int) {
            var k = start
            while (k < end) {
                val ch = text[k]
                if ((k == start || text[k - 1] == ' ' || text[k - 1] == '\t') &&
                    comments.any { text.startsWith(it, k) }
                ) {
                    add(k, end, palette.comment); return
                }
                val d = delimiters.firstOrNull { text.startsWith(it, k) }
                if (d != null) {
                    var j = k + d.length
                    while (j < end && !text.startsWith(d, j)) { if (text[j] == '\\') j++; j++ }
                    val close = if (j < end) j + d.length else end
                    add(k, close, palette.string); k = close; continue
                }
                if (ch.isDigit() || (ch == '-' && k + 1 < end && text[k + 1].isDigit())) {
                    var j = k + 1
                    while (j < end && (text[j].isLetterOrDigit() || text[j] in ".:+-_")) j++
                    add(k, j, palette.number); k = j; continue
                }
                if (ch.isLetter() || ch == '_' || ch == '~') {
                    var j = k
                    while (j < end && (text[j].isLetterOrDigit() || text[j] in "_-~")) j++
                    if (text.substring(k, j) in boolWords) add(k, j, palette.keyword)
                    k = j; continue
                }
                k++
            }
        }

        var i = 0
        while (i < n) {
            val lineStart = i
            var lineEnd = i
            while (lineEnd < n && text[lineEnd] != '\n') lineEnd++
            val nextLine = if (lineEnd < n) lineEnd + 1 else lineEnd

            var t = lineStart
            while (t < lineEnd && (text[t] == ' ' || text[t] == '\t')) t++

            when {
                t >= lineEnd -> {}
                comments.any { text.startsWith(it, t) } -> add(t, lineEnd, palette.comment)
                sections && text[t] == '[' -> add(t, lineEnd, palette.type)
                sep == ':' && (text.startsWith("---", t) || text.startsWith("...", t)) ->
                    add(t, lineEnd, palette.operator)
                else -> {
                    var c = t
                    // YAML list marker "- "
                    if (sep == ':' && text[c] == '-' && c + 1 < lineEnd &&
                        (text[c + 1] == ' ' || text[c + 1] == '\t')
                    ) {
                        add(c, c + 1, palette.operator); c++
                        while (c < lineEnd && (text[c] == ' ' || text[c] == '\t')) c++
                    }
                    // Locate the key/value separator: for YAML the ':' must be followed by space/EOL.
                    var s = c
                    while (s < lineEnd && !(text[s] == sep &&
                            (sep != ':' || s + 1 >= lineEnd || text[s + 1] == ' ' || text[s + 1] == '\t'))
                    ) s++
                    if (s < lineEnd && text[s] == sep && s > c) {
                        var keyEnd = s
                        while (keyEnd > c && (text[keyEnd - 1] == ' ' || text[keyEnd - 1] == '\t')) keyEnd--
                        add(c, keyEnd, palette.property)   // key
                        add(s, s + 1, palette.operator)    // : or =
                        value(s + 1, lineEnd)
                    } else {
                        value(c, lineEnd)
                    }
                }
            }
            i = nextLine
        }
        return spans
    }

    /**
     * Built-in JSON coloring (no extension needed): object keys get the property color so they read
     * distinctly from string values, plus numbers, `true`/`false`/`null`, structural punctuation, and
     * `//` + `/* */` comments for JSONC/JSON5. A string is treated as a key when the next non-space
     * character is `:`.
     */
    fun highlightJson(text: String, palette: TokenPalette): List<ColoredSpan> {
        val n = text.length
        if (n == 0) return emptyList()
        val byteAt = byteOffsets(text)
        val spans = ArrayList<ColoredSpan>()
        fun add(start: Int, end: Int, color: Int) {
            if (end > start) spans.add(ColoredSpan(byteAt[start], byteAt[end], color))
        }

        var i = 0
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    var j = i + 2
                    while (j < n && text[j] != '\n') j++
                    add(i, j, palette.comment); i = j
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    val e = text.indexOf("*/", i + 2)
                    val j = if (e < 0) n else e + 2
                    add(i, j, palette.comment); i = j
                }
                c == '"' || c == '\'' -> {
                    var j = i + 1
                    while (j < n && text[j] != c) { if (text[j] == '\\') j++; j++ }
                    val close = if (j < n) j + 1 else n
                    var k = close
                    while (k < n && (text[k] == ' ' || text[k] == '\t' || text[k] == '\n' || text[k] == '\r')) k++
                    val isKey = k < n && text[k] == ':'
                    add(i, close, if (isKey) palette.property else palette.string); i = close
                }
                c.isDigit() || (c == '-' && i + 1 < n && text[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (text[j].isLetterOrDigit() || text[j] in ".+-eE")) j++
                    add(i, j, palette.number); i = j
                }
                c.isLetter() -> {
                    var j = i
                    while (j < n && text[j].isLetter()) j++
                    when (text.substring(i, j)) {
                        "true", "false", "null", "Infinity", "NaN" -> add(i, j, palette.keyword)
                    }
                    i = j
                }
                c in "{}[]:," -> { add(i, i + 1, palette.operator); i++ }
                else -> i++
            }
        }
        return spans
    }

    // A horizontal rule: only -, *, or _ (>=3 of the same) plus optional spaces.
    private fun isHrLine(text: String, start: Int, end: Int): Boolean {
        if (start >= end) return false
        val ch = text[start]
        if (ch != '-' && ch != '*' && ch != '_') return false
        var count = 0
        for (i in start until end) {
            val c = text[i]
            when {
                c == ch -> count++
                c == ' ' || c == '\t' -> {}
                else -> return false
            }
        }
        return count >= 3
    }

    // Returns the end char-index of a list marker ("- ", "* ", "+ ", "1. ", "1) ") at [start, end), else start.
    private fun listMarkerEnd(text: String, start: Int, end: Int): Int {
        if (start >= end) return start
        val c = text[start]
        if ((c == '-' || c == '*' || c == '+') && start + 1 < end && (text[start + 1] == ' ' || text[start + 1] == '\t')) {
            return start + 1
        }
        if (c.isDigit()) {
            var j = start
            while (j < end && text[j].isDigit()) j++
            if (j < end && (text[j] == '.' || text[j] == ')') &&
                j + 1 < end && (text[j + 1] == ' ' || text[j + 1] == '\t')
            ) {
                return j + 1
            }
        }
        return start
    }

    // char index -> UTF-8 byte offset (the renderer addresses spans by byte).
    private fun byteOffsets(text: String): IntArray {
        val n = text.length
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
        return byteAt
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

    // `#` requires a trailing space so preprocessor / CSS selectors aren't mistaken for comments.
    private val GENERIC_LINE_COMMENTS = listOf("//", "# ")
    private val GENERIC_STRING_DELIMS = listOf("\"", "'", "`")
    private val GENERIC_KEYWORDS = hashSetOf(
        "if", "else", "elif", "for", "while", "do", "switch", "case", "default", "break", "continue",
        "return", "function", "func", "fn", "def", "lambda", "class", "struct", "interface", "enum",
        "trait", "impl", "const", "let", "var", "val", "final", "static", "public", "private",
        "protected", "internal", "import", "from", "include", "require", "package", "namespace",
        "using", "module", "export", "new", "delete", "this", "self", "super", "null", "nil", "none",
        "true", "false", "void", "async", "await", "yield", "try", "catch", "except", "finally",
        "throw", "throws", "raise", "with", "as", "in", "is", "not", "and", "or", "typeof",
        "instanceof", "extends", "implements", "override", "virtual", "abstract", "sealed", "when",
        "match", "where", "goto", "defer", "echo", "print", "println", "end", "then", "begin", "fun",
    )
    private val GENERIC_TYPES = hashSetOf(
        "int", "integer", "long", "short", "byte", "float", "double", "bool", "boolean", "char",
        "string", "str", "void", "object", "any", "unit", "number", "list", "map", "set", "array",
        "vector", "dict", "tuple", "uint", "usize", "isize", "i32", "i64", "u32", "u64", "f32", "f64",
    )
}

package dev.jcode.editor

import dev.jcode.feature.marketplace.LanguagePack

/**
 * Markdown → HTML for the built-in editor preview. Covers the GFM constructs READMEs actually use:
 * ATX/setext headings, fenced code (colored via [SyntaxHighlighter] like the editor), blockquotes,
 * nested/ordered/task lists, tables, HR, and inline emphasis/code/links/images/strikethrough.
 * Raw HTML in the source is ALWAYS escaped — the preview WebView has JS enabled for theme injection,
 * so document content must never reach the DOM unescaped. [imageResolver] maps a Markdown image src
 * to a replacement URL (e.g. a local file inlined as a data: URI); null keeps the original.
 */
object MarkdownHtml {

    fun render(
        markdown: String,
        palette: TokenPalette,
        packResolver: (fileName: String) -> LanguagePack? = { null },
        imageResolver: (src: String) -> String? = { null },
    ): String {
        val ctx = Ctx(palette, packResolver, imageResolver)
        return renderLines(markdown.replace("\r\n", "\n").replace('\r', '\n').split("\n"), ctx)
    }

    private class Ctx(
        val palette: TokenPalette,
        val packResolver: (String) -> LanguagePack?,
        val imageResolver: (String) -> String?,
    )

    /** Nesting bound for the recursive paths (blockquotes, link labels, emphasis): legitimate
     *  documents never approach it, and adversarial input (a line of 10k '>'s, deeply nested
     *  brackets) degrades to escaped text instead of a StackOverflowError. */
    private const val MAX_DEPTH = 32

    // --- block level ---------------------------------------------------------------------------

    private fun renderLines(lines: List<String>, ctx: Ctx, depth: Int = 0): String {
        if (depth > MAX_DEPTH) return "<pre>" + escStr(lines.joinToString("\n")) + "</pre>"
        val out = StringBuilder()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> i++

                fenceOf(line) != null -> {
                    val (char, len, info) = fenceOf(line)!!
                    val code = StringBuilder()
                    var j = i + 1
                    while (j < lines.size && !isFenceClose(lines[j], char, len)) {
                        code.append(lines[j]).append('\n')
                        j++
                    }
                    if (info.trim().lowercase() == "mermaid") {
                        // Mermaid fence: emit the ESCAPED source in a marked block. When the
                        // Mermaid Preview extension is installed the shell's render pass swaps it
                        // for the drawn diagram; otherwise it stays a plain code block.
                        out.append("<pre class=\"mermaid-src\"><code>")
                            .append(escStr(code.toString()))
                            .append("</code></pre>")
                    } else {
                        out.append("<pre><code>")
                            .append(coloredCode(code.toString(), info, ctx))
                            .append("</code></pre>")
                    }
                    i = if (j < lines.size) j + 1 else j
                }

                atxLevel(trimmed) > 0 -> {
                    val level = atxLevel(trimmed)
                    val content = trimmed.drop(level).trim().trimEnd('#').trim()
                    out.append("<h$level>").append(inline(content, ctx, depth)).append("</h$level>")
                    i++
                }

                isHr(trimmed) -> { out.append("<hr>"); i++ }

                trimmed.startsWith(">") -> {
                    val quoted = mutableListOf<String>()
                    while (i < lines.size && lines[i].trim().startsWith(">")) {
                        quoted.add(lines[i].trim().removePrefix(">").removePrefix(" "))
                        i++
                    }
                    out.append("<blockquote>").append(renderLines(quoted, ctx, depth + 1)).append("</blockquote>")
                }

                indentColumns(line) >= 4 -> {
                    val code = StringBuilder()
                    while (i < lines.size && (
                            indentColumns(lines[i]) >= 4 ||
                                (lines[i].isBlank() && i + 1 < lines.size && indentColumns(lines[i + 1]) >= 4)
                            )
                    ) {
                        code.append(if (lines[i].isBlank()) "" else dedent4(lines[i])).append('\n')
                        i++
                    }
                    out.append("<pre><code>").append(escStr(code.toString())).append("</code></pre>")
                }

                listItemOf(line) != null -> {
                    val items = mutableListOf<ListItem>()
                    while (i < lines.size) {
                        val item = listItemOf(lines[i])
                        if (item != null) {
                            items.add(item)
                            i++
                        } else if (lines[i].isNotBlank() && lines[i].startsWith(" ") && items.isNotEmpty()) {
                            // Indented continuation text joins the previous item.
                            val last = items.last()
                            items[items.size - 1] = last.copy(content = last.content + " " + lines[i].trim())
                            i++
                        } else break
                    }
                    renderList(items, ctx, out, depth)
                }

                i + 1 < lines.size && trimmed.contains('|') && isTableSeparator(lines[i + 1]) -> {
                    val header = splitRow(line)
                    val aligns = splitRow(lines[i + 1]).map { cell ->
                        val l = cell.startsWith(":"); val r = cell.endsWith(":")
                        when { l && r -> "center"; r -> "right"; l -> "left"; else -> null }
                    }
                    fun align(idx: Int) = aligns.getOrNull(idx)?.let { " style=\"text-align:$it\"" } ?: ""
                    out.append("<table><thead><tr>")
                    header.forEachIndexed { c, cell ->
                        out.append("<th").append(align(c)).append(">").append(inline(cell, ctx, depth)).append("</th>")
                    }
                    out.append("</tr></thead><tbody>")
                    var j = i + 2
                    while (j < lines.size && lines[j].contains('|') && lines[j].isNotBlank()) {
                        out.append("<tr>")
                        splitRow(lines[j]).forEachIndexed { c, cell ->
                            out.append("<td").append(align(c)).append(">").append(inline(cell, ctx, depth)).append("</td>")
                        }
                        out.append("</tr>")
                        j++
                    }
                    out.append("</tbody></table>")
                    i = j
                }

                else -> {
                    val para = mutableListOf(line.trimStart())
                    var setext = 0
                    i++
                    while (i < lines.size) {
                        val next = lines[i]
                        val nt = next.trim()
                        if (nt.isEmpty()) break
                        if (nt.length >= 2 && nt.all { it == '=' }) { setext = 1; i++; break }
                        if (nt.length >= 2 && nt.all { it == '-' }) { setext = 2; i++; break }
                        if (fenceOf(next) != null || atxLevel(nt) > 0 || isHr(nt) ||
                            nt.startsWith(">") || listItemOf(next) != null ||
                            (i + 1 < lines.size && nt.contains('|') && isTableSeparator(lines[i + 1]))
                        ) break
                        para.add(next.trimStart())
                        i++
                    }
                    // Two trailing spaces force a hard break.
                    val html = para.joinToString("") { ln ->
                        inline(ln.trimEnd(), ctx, depth) + if (ln.endsWith("  ")) "<br>" else " "
                    }.trim()
                    if (setext > 0) {
                        out.append("<h$setext>").append(html).append("</h$setext>")
                    } else {
                        out.append("<p>").append(html).append("</p>")
                    }
                }
            }
        }
        return out.toString()
    }

    /** Open fence: ≥3 backticks or tildes (after ≤3 spaces of indent) + optional info string. */
    private fun fenceOf(line: String): Triple<Char, Int, String>? {
        val t = line.trimStart()
        if (line.length - t.length > 3) return null
        val c = t.firstOrNull() ?: return null
        if (c != '`' && c != '~') return null
        var n = 0
        while (n < t.length && t[n] == c) n++
        if (n < 3) return null
        val info = t.substring(n).trim()
        if (c == '`' && info.contains('`')) return null
        return Triple(c, n, info.substringBefore(' ').lowercase())
    }

    private fun isFenceClose(line: String, char: Char, openLen: Int): Boolean {
        val t = line.trim()
        return t.length >= openLen && t.all { it == char }
    }

    private fun atxLevel(trimmed: String): Int {
        var n = 0
        while (n < trimmed.length && trimmed[n] == '#') n++
        return if (n in 1..6 && (trimmed.length == n || trimmed[n] == ' ')) n else 0
    }

    private fun indentColumns(line: String): Int {
        if (line.isBlank()) return 0
        var col = 0
        for (c in line) {
            when (c) {
                ' ' -> col++
                '\t' -> col += 4
                else -> return col
            }
        }
        return col
    }

    private fun dedent4(line: String): String {
        var col = 0
        var i = 0
        while (i < line.length && col < 4) {
            when (line[i]) {
                ' ' -> col++
                '\t' -> col += 4
                else -> return line.substring(i)
            }
            i++
        }
        return line.substring(i)
    }

    private fun isHr(trimmed: String): Boolean {
        if (trimmed.length < 3) return false
        val c = trimmed.firstOrNull { it != ' ' } ?: return false
        if (c != '-' && c != '*' && c != '_') return false
        var count = 0
        for (ch in trimmed) {
            if (ch == c) count++
            else if (ch != ' ') return false
        }
        return count >= 3
    }

    // --- lists ---------------------------------------------------------------------------------

    private data class ListItem(val indent: Int, val ordered: Boolean, val content: String)

    private fun listItemOf(line: String): ListItem? {
        var i = 0
        var indent = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
            indent += if (line[i] == '\t') 4 else 1
            i++
        }
        if (i >= line.length) return null
        val c = line[i]
        if ((c == '-' || c == '*' || c == '+') && i + 1 < line.length && line[i + 1] == ' ') {
            return ListItem(indent, ordered = false, content = line.substring(i + 2).trim())
        }
        if (c.isDigit()) {
            var j = i
            while (j < line.length && line[j].isDigit() && j - i < 9) j++
            if (j < line.length && (line[j] == '.' || line[j] == ')') &&
                j + 1 < line.length && line[j + 1] == ' '
            ) {
                return ListItem(indent, ordered = true, content = line.substring(j + 2).trim())
            }
        }
        return null
    }

    private fun renderList(items: List<ListItem>, ctx: Ctx, out: StringBuilder, depth: Int) {
        if (items.isEmpty()) return
        data class Open(val indent: Int, val tag: String)
        val stack = ArrayDeque<Open>()
        for (item in items) {
            val tag = if (item.ordered) "ol" else "ul"
            if (stack.isEmpty()) {
                out.append("<$tag>")
                stack.addLast(Open(item.indent, tag))
            } else if (item.indent > stack.last().indent + 1) {
                out.append("<$tag>")
                stack.addLast(Open(item.indent, tag))
            } else {
                while (stack.size > 1 && item.indent < stack.last().indent) {
                    out.append("</li></${stack.removeLast().tag}>")
                }
                out.append("</li>")
            }
            out.append("<li>")
            var content = item.content
            when {
                content.startsWith("[ ] ") -> {
                    out.append("<input type=\"checkbox\" disabled> ")
                    content = content.drop(4)
                }
                content.startsWith("[x] ", ignoreCase = true) -> {
                    out.append("<input type=\"checkbox\" checked disabled> ")
                    content = content.drop(4)
                }
            }
            out.append(inline(content, ctx, depth))
        }
        while (stack.isNotEmpty()) out.append("</li></${stack.removeLast().tag}>")
    }

    // --- tables --------------------------------------------------------------------------------

    private fun isTableSeparator(line: String): Boolean {
        val t = line.trim()
        return t.contains('|') && t.contains('-') &&
            t.all { it == '|' || it == '-' || it == ':' || it == ' ' }
    }

    private fun splitRow(line: String): List<String> {
        var t = line.trim()
        if (t.startsWith("|")) t = t.substring(1)
        if (t.endsWith("|") && !t.endsWith("\\|")) t = t.dropLast(1)
        val cells = mutableListOf<String>()
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c == '\\' && i + 1 < t.length && t[i + 1] == '|' -> { sb.append('|'); i += 2 }
                c == '|' -> { cells.add(sb.toString().trim()); sb.clear(); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        cells.add(sb.toString().trim())
        return cells
    }

    // --- inline --------------------------------------------------------------------------------

    private val ESCAPABLE = "\\`*_{}[]()#+-.!|~<>".toHashSet()

    private fun inline(text: String, ctx: Ctx, depth: Int = 0): String {
        if (depth > MAX_DEPTH) return escStr(text)
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '\\' && i + 1 < text.length && text[i + 1] in ESCAPABLE -> {
                    sb.append(esc(text[i + 1]))
                    i += 2
                }

                c == '`' -> {
                    var run = 1
                    while (i + run < text.length && text[i + run] == '`') run++
                    val close = findBacktickRun(text, i + run, run)
                    if (close >= 0) {
                        sb.append("<code>").append(escStr(text.substring(i + run, close).trim())).append("</code>")
                        i = close + run
                    } else {
                        sb.append('`')
                        i++
                    }
                }

                c == '!' && i + 1 < text.length && text[i + 1] == '[' -> {
                    val link = parseLink(text, i + 1)
                    if (link != null) {
                        val src = ctx.imageResolver(link.dest) ?: link.dest
                        if (isSafeSrc(src)) {
                            sb.append("<img src=\"").append(escStr(src))
                                .append("\" alt=\"").append(escStr(link.label)).append("\">")
                        } else {
                            sb.append(escStr(text.substring(i, link.end)))
                        }
                        i = link.end
                    } else {
                        sb.append('!')
                        i++
                    }
                }

                c == '[' -> {
                    val link = parseLink(text, i)
                    if (link != null) {
                        if (isSafeHref(link.dest)) {
                            sb.append("<a href=\"").append(escStr(link.dest)).append("\">")
                                .append(inline(link.label, ctx, depth + 1)).append("</a>")
                        } else {
                            sb.append(inline(link.label, ctx, depth + 1))
                        }
                        i = link.end
                    } else {
                        sb.append('[')
                        i++
                    }
                }

                c == '<' -> {
                    val gt = text.indexOf('>', i + 1)
                    val inner = if (gt > 0) text.substring(i + 1, gt) else ""
                    if (gt > 0 && !inner.contains(' ') &&
                        (inner.startsWith("http://") || inner.startsWith("https://"))
                    ) {
                        sb.append("<a href=\"").append(escStr(inner)).append("\">")
                            .append(escStr(inner)).append("</a>")
                        i = gt + 1
                    } else {
                        sb.append("&lt;")
                        i++
                    }
                }

                c == '~' && i + 1 < text.length && text[i + 1] == '~' -> {
                    val close = text.indexOf("~~", i + 2)
                    if (close > i + 1) {
                        sb.append("<del>").append(inline(text.substring(i + 2, close), ctx, depth + 1)).append("</del>")
                        i = close + 2
                    } else {
                        sb.append('~')
                        i++
                    }
                }

                c == '*' || c == '_' -> {
                    val double = i + 1 < text.length && text[i + 1] == c
                    if (double) {
                        val close = text.indexOf("$c$c", i + 2)
                        if (close > i + 1 && text.substring(i + 2, close).isNotBlank()) {
                            sb.append("<strong>").append(inline(text.substring(i + 2, close), ctx, depth + 1)).append("</strong>")
                            i = close + 2
                        } else {
                            sb.append(esc(c)).append(esc(c))
                            i += 2
                        }
                    } else {
                        // `_` never opens mid-word (snake_case); `*` needs a non-space right after.
                        val canOpen = i + 1 < text.length && !text[i + 1].isWhitespace() &&
                            (c == '*' || i == 0 || !text[i - 1].isLetterOrDigit())
                        val close = if (canOpen) findEmphasisClose(text, i + 1, c) else -1
                        if (close > i && text.substring(i + 1, close).isNotBlank()) {
                            sb.append("<em>").append(inline(text.substring(i + 1, close), ctx, depth + 1)).append("</em>")
                            i = close + 1
                        } else {
                            sb.append(esc(c))
                            i++
                        }
                    }
                }

                else -> {
                    sb.append(esc(c))
                    i++
                }
            }
        }
        return sb.toString()
    }

    private fun findBacktickRun(text: String, from: Int, length: Int): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == '`') {
                var n = 1
                while (i + n < text.length && text[i + n] == '`') n++
                if (n == length) return i
                i += n
            } else {
                i++
            }
        }
        return -1
    }

    private fun findEmphasisClose(text: String, from: Int, delim: Char): Int {
        var i = from
        while (i < text.length) {
            if (text[i] == delim && !text[i - 1].isWhitespace() &&
                (delim == '*' || i + 1 >= text.length || !text[i + 1].isLetterOrDigit())
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private data class Link(val label: String, val dest: String, val end: Int)

    /** `[label](dest "title")` starting at the `[`; label may nest brackets, dest stops at space. */
    private fun parseLink(text: String, at: Int): Link? {
        if (at >= text.length || text[at] != '[') return null
        var i = at + 1
        var depth = 1
        while (i < text.length && depth > 0) {
            when (text[i]) {
                '\\' -> i++
                '[' -> depth++
                ']' -> depth--
            }
            i++
        }
        if (depth != 0 || i >= text.length || text[i] != '(') return null
        val label = text.substring(at + 1, i - 1)
        i++
        val destStart = i
        var parens = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\') { i += 2; continue }
            if (ch == '(') parens++
            if (ch == ')') {
                if (parens == 0) break
                parens--
            }
            i++
        }
        if (i >= text.length) return null
        val rawDest = text.substring(destStart, i).trim()
        val dest = rawDest.substringBefore(' ').removeSurrounding("<", ">")
        return Link(label, dest, i + 1)
    }

    private fun isSafeHref(dest: String): Boolean {
        val d = dest.trim().lowercase()
        val scheme = d.substringBefore(':', "")
        return scheme.isEmpty() || d.startsWith("#") ||
            scheme == "http" || scheme == "https" || scheme == "mailto"
    }

    private fun isSafeSrc(src: String): Boolean {
        val s = src.trim().lowercase()
        val scheme = s.substringBefore(':', "")
        return scheme.isEmpty() || scheme == "http" || scheme == "https" || scheme == "data"
    }

    // --- fenced-code coloring --------------------------------------------------------------------

    private val LANG_EXT = mapOf(
        "kotlin" to "kt", "python" to "py", "javascript" to "js", "typescript" to "ts",
        "csharp" to "cs", "c#" to "cs", "c++" to "cpp", "rust" to "rs", "ruby" to "rb",
        "shell" to "sh", "bash" to "sh", "zsh" to "sh", "console" to "sh", "yml" to "yaml",
        "markdown" to "md", "text" to "", "plaintext" to "", "plain" to "", "txt" to "",
    )

    private fun coloredCode(code: String, langTag: String, ctx: Ctx): String {
        val ext = LANG_EXT.getOrDefault(langTag, langTag)
        if (ext.isEmpty() && langTag.isNotEmpty() && langTag in LANG_EXT) return escStr(code)
        val pseudo = "snippet." + ext.ifEmpty { "txt" }
        val spans = runCatching {
            SyntaxHighlighter.highlightFor(code, pseudo, ctx.packResolver(pseudo), ctx.palette)
        }.getOrDefault(emptyList())
        if (spans.isEmpty()) return escStr(code)

        // Spans carry UTF-8 byte offsets; map them to char indices to slice the string.
        val byteLen = code.sumOf { utf8Len(it) }
        val byteToChar = IntArray(byteLen + 1)
        var b = 0
        code.forEachIndexed { charIdx, ch ->
            val n = utf8Len(ch)
            for (k in 0 until n) byteToChar[b + k] = charIdx
            b += n
        }
        byteToChar[byteLen] = code.length

        val sb = StringBuilder()
        var cursor = 0
        for (span in spans.sortedBy { it.startByte }) {
            val start = byteToChar[span.startByte.coerceIn(0, byteLen)]
            val end = byteToChar[span.endByte.coerceIn(0, byteLen)]
            if (start < cursor || end <= start) continue
            if (start > cursor) sb.append(escStr(code.substring(cursor, start)))
            sb.append("<span style=\"color:").append(hexColor(span.color)).append("\">")
                .append(escStr(code.substring(start, end)))
                .append("</span>")
            cursor = end
        }
        if (cursor < code.length) sb.append(escStr(code.substring(cursor)))
        return sb.toString()
    }

    private fun utf8Len(c: Char): Int = when {
        c.code < 0x80 -> 1
        c.code < 0x800 -> 2
        Character.isHighSurrogate(c) -> 2
        Character.isLowSurrogate(c) -> 2
        else -> 3
    }

    private fun hexColor(argb: Int): String = String.format("#%06X", 0xFFFFFF and argb)

    // --- escaping --------------------------------------------------------------------------------

    private fun esc(c: Char): String = when (c) {
        '&' -> "&amp;"
        '<' -> "&lt;"
        '>' -> "&gt;"
        '"' -> "&quot;"
        else -> c.toString()
    }

    private fun escStr(s: String): String = buildString(s.length) { s.forEach { append(esc(it)) } }
}

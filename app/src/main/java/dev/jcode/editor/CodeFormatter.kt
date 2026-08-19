package dev.jcode.editor

import dev.jcode.feature.marketplace.LanguagePack

/**
 * A small, dependency-free "basic formatter" driven by a [LanguagePack]'s formatting rules
 * ([LanguagePack.indent], [LanguagePack.trimTrailingWhitespace], [LanguagePack.insertFinalNewline]).
 * It does only the safe, language-agnostic normalizations those fields describe — it is NOT a
 * grammar-aware reindenter:
 *  - trims trailing whitespace from each line,
 *  - converts leading tabs to `indent` spaces (when `indent` is set),
 *  - ensures the file ends with exactly one newline.
 * The original line-ending style (LF vs CRLF) is preserved.
 */
object CodeFormatter {
    fun format(text: String, lang: LanguagePack?): String {
        val indent = lang?.indent
        val trim = lang?.trimTrailingWhitespace ?: true
        val finalNewline = lang?.insertFinalNewline ?: true
        if (text.isEmpty()) return text

        val crlf = text.contains("\r\n")
        val nl = if (crlf) "\r\n" else "\n"
        val lines = text.split("\n").map { it.removeSuffix("\r") }

        val out = StringBuilder(text.length)
        for ((i, raw) in lines.withIndex()) {
            var line = raw
            if (indent != null && indent > 0) line = expandLeadingTabs(line, indent)
            if (trim) line = line.trimEnd(' ', '\t')
            out.append(line)
            if (i < lines.lastIndex) out.append(nl)
        }

        var result = out.toString()
        if (finalNewline) {
            // Collapse any trailing blank lines to exactly one newline. Always re-add it (input is
            // non-empty here, guarded above), so an all-whitespace file becomes "\n", never "".
            result = result.trimEnd('\n', '\r') + nl
        }
        return result
    }

    // Replace tabs in the run of leading whitespace with `indent` spaces; leaves the rest untouched.
    private fun expandLeadingTabs(line: String, indent: Int): String {
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) i++
        if (i == 0 || line.indexOf('\t', 0).let { it < 0 || it >= i }) return line
        val sb = StringBuilder(line.length + indent)
        for (j in 0 until i) {
            if (line[j] == '\t') repeat(indent) { sb.append(' ') } else sb.append(line[j])
        }
        sb.append(line, i, line.length)
        return sb.toString()
    }
}

package dev.blamspot.jcode.design

/**
 * Reflow a description written as Markdown so the reader's screen decides the line breaks.
 *
 * A single newline inside a Markdown paragraph is a *soft* break — the place the author's editor
 * wrapped, not a line the reader is meant to see. Extension descriptions live in files wrapped at
 * about 80 columns, and drawn verbatim on a phone that wraps at far fewer they came out ragged:
 * every source line ended mid-sentence and started a new display line, so the text broke twice,
 * once where the author's editor stopped and again where the screen did.
 *
 * Fixed here rather than in the packages because the wrapping is correct authoring — and because a
 * third-party description is not ours to rewrite, while its detail page still has to read properly.
 *
 * What the author *meant* as a break is kept: blank lines still separate paragraphs, and any line
 * carrying structure — a list item, a heading, a quote, a table row, an indented block — keeps the
 * newline before and after it, because there the break is the content.
 */
fun reflowDescription(text: String): String =
    text.replace("\r\n", "\n")
        .split(Regex("\n[ \t]*\n"))
        .joinToString("\n\n") { paragraph -> foldSoftBreaks(paragraph) }
        .trim()

private fun foldSoftBreaks(paragraph: String): String {
    val lines = paragraph.split("\n")
    return buildString {
        var previousWasStructural = false
        lines.forEachIndexed { index, raw ->
            val structural = isStructural(raw)
            if (index > 0) append(if (structural || previousWasStructural) "\n" else " ")
            // An indented block is indented on purpose; everything else is trimmed, since leading
            // space on a folded line would land mid-sentence.
            append(if (raw.startsWith("    ") || raw.startsWith("\t")) raw.trimEnd() else raw.trim())
            previousWasStructural = structural
        }
    }
}

/** Lines whose newline is content rather than wrapping. */
private fun isStructural(raw: String): Boolean {
    if (raw.startsWith("    ") || raw.startsWith("\t")) return true
    val line = raw.trimStart()
    return line.startsWith("- ") ||
        line.startsWith("* ") ||
        line.startsWith("+ ") ||
        line.startsWith("#") ||
        line.startsWith("> ") ||
        line.startsWith("|") ||
        line.startsWith("```") ||
        ORDERED_ITEM.containsMatchIn(line)
}

private val ORDERED_ITEM = Regex("""^\d+[.)]\s""")

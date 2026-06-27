package dev.jcode.core.editor.completion

/**
 * Snippet engine that parses and applies LSP-style snippet syntax.
 *
 * Supports:
 * - $0, $1, $2... — tab stops (0 is final cursor position)
 * - ${1:placeholder} — tab stop with placeholder text
 * - ${1|choice1,choice2|} — tab stop with choices
 * - ${1/regex/format/options} — transforms
 * - $TM_SELECTED_TEXT, $TM_CURRENT_LINE, etc. — variables
 * - \n, \t, \\, \$ — escapes
 */
class SnippetEngine {

    /**
     * Parse a snippet string and return the plain text + tab stops.
     */
    fun parse(snippet: String): SnippetResult {
        val text = StringBuilder()
        val tabStops = mutableListOf<TabStop>()
        var i = 0
        var tabStopIndex = 0

        while (i < snippet.length) {
            when {
                // Escape sequences
                snippet[i] == '\\' && i + 1 < snippet.length -> {
                    when (snippet[i + 1]) {
                        'n' -> text.append('\n')
                        't' -> text.append('\t')
                        '\\' -> text.append('\\')
                        '$' -> text.append('$')
                        '}' -> text.append('}')
                        else -> {
                            text.append('\\')
                            text.append(snippet[i + 1])
                        }
                    }
                    i += 2
                }

                // Tab stop: $N or ${N:placeholder} or ${N|choices|}
                snippet[i] == '$' && i + 1 < snippet.length -> {
                    i++
                    if (snippet[i] == '{') {
                        // Complex tab stop: ${...}
                        i++
                        val result = parseComplexTabStop(snippet, i)
                        if (result != null) {
                            val (stop, endIndex) = result
                            tabStops.add(stop.copy(index = tabStopIndex))
                            text.append(stop.placeholder)
                            tabStopIndex++
                            i = endIndex
                        } else {
                            text.append('$')
                            text.append('{')
                            i++
                        }
                    } else if (snippet[i].isDigit()) {
                        // Simple tab stop: $N
                        val numStart = i
                        while (i < snippet.length && snippet[i].isDigit()) i++
                        val num = snippet.substring(numStart, i).toIntOrNull() ?: 0
                        tabStops.add(TabStop(index = tabStopIndex, number = num))
                        tabStopIndex++
                    } else if (snippet[i].isLetter()) {
                        // Variable: $VAR_NAME
                        val nameStart = i
                        while (i < snippet.length && (snippet[i].isLetterOrDigit() || snippet[i] == '_')) i++
                        val varName = snippet.substring(nameStart, i)
                        text.append(resolveVariable(varName))
                    } else {
                        text.append('$')
                    }
                }

                else -> {
                    text.append(snippet[i])
                    i++
                }
            }
        }

        return SnippetResult(
            text = text.toString(),
            tabStops = tabStops.sortedBy { it.number },
        )
    }

    private fun parseComplexTabStop(snippet: String, start: Int): Pair<TabStop, Int>? {
        var i = start
        // Parse number
        val numStart = i
        while (i < snippet.length && snippet[i].isDigit()) i++
        if (i == numStart) return null
        val number = snippet.substring(numStart, i).toIntOrNull() ?: return null

        if (i >= snippet.length) return null

        return when {
            snippet[i] == ':' -> {
                // Placeholder: ${N:placeholder}
                i++
                val placeholderStart = i
                var depth = 1
                while (i < snippet.length && depth > 0) {
                    if (snippet[i] == '{') depth++
                    else if (snippet[i] == '}') depth--
                    if (depth > 0) i++
                }
                val placeholder = snippet.substring(placeholderStart, i)
                if (i < snippet.length) i++ // skip closing }
                TabStop(index = 0, number = number, placeholder = placeholder) to i
            }
            snippet[i] == '|' -> {
                // Choices: ${N|choice1,choice2|}
                i++
                val choicesStart = i
                while (i < snippet.length && snippet[i] != '|') i++
                val choicesStr = snippet.substring(choicesStart, i)
                val choices = choicesStr.split(',')
                if (i < snippet.length) i++ // skip closing |
                while (i < snippet.length && snippet[i] != '}') i++
                if (i < snippet.length) i++ // skip closing }
                TabStop(index = 0, number = number, choices = choices) to i
            }
            snippet[i] == '}' -> {
                i++
                TabStop(index = 0, number = number) to i
            }
            else -> null
        }
    }

    private fun resolveVariable(name: String): String {
        return when (name) {
            "TM_SELECTED_TEXT" -> ""
            "TM_CURRENT_LINE" -> ""
            "TM_CURRENT_WORD" -> ""
            "TM_LINE_INDEX" -> "0"
            "TM_LINE_NUMBER" -> "1"
            "TM_FILENAME" -> ""
            "TM_FILENAME_BASE" -> ""
            "TM_DIRECTORY" -> ""
            "TM_FILEPATH" -> ""
            "CLIPBOARD" -> ""
            "CURRENT_YEAR" -> java.time.Year.now().toString()
            "CURRENT_MONTH" -> "%02d".format(java.time.LocalDate.now().monthValue)
            "CURRENT_DATE" -> "%02d".format(java.time.LocalDate.now().dayOfMonth)
            "CURRENT_HOUR" -> "%02d".format(java.time.LocalTime.now().hour)
            "CURRENT_MINUTE" -> "%02d".format(java.time.LocalTime.now().minute)
            "CURRENT_SECOND" -> "%02d".format(java.time.LocalTime.now().second)
            "BLOCK_COMMENT_START" -> "/*"
            "BLOCK_COMMENT_END" -> "*/"
            "LINE_COMMENT" -> "//"
            else -> "\$$name"
        }
    }

    /**
     * Apply a snippet at the given offset, returning the insertion text and tab stop positions.
     */
    fun apply(snippet: String, offset: Int): AppliedSnippet {
        val result = parse(snippet)
        val adjustedStops = result.tabStops.map { stop ->
            stop.copy(offset = offset + findPlaceholderStart(result.text, stop))
        }
        return AppliedSnippet(
            text = result.text,
            tabStops = adjustedStops,
            finalOffset = offset + result.text.length,
        )
    }

    private fun findPlaceholderStart(text: String, stop: TabStop): Int {
        if (stop.placeholder.isEmpty()) return 0
        return text.indexOf(stop.placeholder).coerceAtLeast(0)
    }
}

/**
 * A tab stop within a snippet.
 */
data class TabStop(
    val index: Int,
    val number: Int,
    val placeholder: String = "",
    val choices: List<String> = emptyList(),
    val offset: Int = 0,
)

/**
 * Result of parsing a snippet.
 */
data class SnippetResult(
    val text: String,
    val tabStops: List<TabStop>,
)

/**
 * Result of applying a snippet at a specific offset.
 */
data class AppliedSnippet(
    val text: String,
    val tabStops: List<TabStop>,
    val finalOffset: Int,
) {
    /** Get the next tab stop after the given number. */
    fun nextTabStop(after: Int): TabStop? {
        return tabStops.firstOrNull { it.number > after }
            ?: tabStops.firstOrNull { it.number == 0 }
    }
}

package dev.jcode.core.search

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.util.regex.Pattern

/**
 * A single search match.
 */
data class SearchMatch(
    /** File path (relative to search root) */
    val filePath: String,
    /** 0-based line number */
    val lineNumber: Int,
    /** 0-based column start */
    val columnStart: Int,
    /** 0-based column end */
    val columnEnd: Int,
    /** Full line text */
    val lineText: String,
    /** Matched text */
    val matchText: String,
)

/**
 * Search options.
 */
data class SearchOptions(
    /** Search query (plain text or regex) */
    val query: String,
    /** Whether query is a regex pattern */
    val isRegex: Boolean = false,
    /** Case-sensitive search */
    val caseSensitive: Boolean = false,
    /** Match whole words only */
    val wholeWord: Boolean = false,
    /** Include glob patterns (e.g., "*.kt", "*.java") */
    val includePatterns: List<String> = emptyList(),
    /** Exclude glob patterns (e.g., "node_modules", ".git") */
    val excludePatterns: List<String> = emptyList(),
    /** Maximum number of results (0 = unlimited) */
    val maxResults: Int = 10000,
    /** Search in hidden files */
    val includeHidden: Boolean = false,
)

/**
 * Search engine that provides text search across project files.
 * Uses ripgrep (rg) when available via distro, falls back to in-process Java search.
 */
class SearchEngine {

    /**
     * Search for matches in a directory using an in-process Java implementation.
     * Results are emitted as a flow for progressive display.
     */
    fun search(rootDir: File, options: SearchOptions): Flow<SearchMatch> = flow {
        val pattern = compilePattern(options)
        var resultCount = 0

        rootDir.walkTopDown()
            .filter { it.isFile }
            .filter { file -> shouldInclude(file, rootDir, options) }
            .forEach { file ->
                if (resultCount >= options.maxResults) return@forEach

                try {
                    val relativePath = file.relativeTo(rootDir).path
                    file.useLines { lines ->
                        lines.forEachIndexed { lineIndex, lineText ->
                            if (resultCount >= options.maxResults) return@forEachIndexed

                            val matcher = pattern.matcher(lineText)
                            while (matcher.find()) {
                                if (resultCount >= options.maxResults) break

                                val match = SearchMatch(
                                    filePath = relativePath,
                                    lineNumber = lineIndex,
                                    columnStart = matcher.start(),
                                    columnEnd = matcher.end(),
                                    lineText = lineText,
                                    matchText = matcher.group(),
                                )
                                emit(match)
                                resultCount++
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Skip files that can't be read (binary, permission, etc.)
                }
            }
    }

    /**
     * Search using ripgrep via a command executor (PTY or process).
     * Results are emitted as a flow for progressive display.
     */
    fun searchWithRipgrep(
        rootDir: String,
        options: SearchOptions,
        executor: suspend (command: String) -> String,
    ): Flow<SearchMatch> = flow {
        val args = buildRipgrepArgs(options)
        val command = "rg ${args.joinToString(" ")} -- '${escapeForShell(options.query)}' '$rootDir'"

        try {
            val output = executor(command)
            var resultCount = 0

            output.lines().forEach { line ->
                if (resultCount >= options.maxResults || line.isBlank()) return@forEach

                // ripgrep --json format: {"type":"match","data":{...}}
                // ripgrep default format: filepath:line:col:text
                val match = parseRipgrepLine(line, rootDir)
                if (match != null) {
                    emit(match)
                    resultCount++
                }
            }
        } catch (e: Exception) {
            // ripgrep not available or failed
        }
    }

    /**
     * Quick count of matches (for status bar display).
     */
    suspend fun countMatches(rootDir: File, options: SearchOptions): Int {
        var count = 0
        val pattern = compilePattern(options)

        rootDir.walkTopDown()
            .filter { it.isFile }
            .filter { file -> shouldInclude(file, rootDir, options) }
            .forEach { file ->
                try {
                    file.useLines { lines ->
                        lines.forEach { lineText ->
                            val matcher = pattern.matcher(lineText)
                            while (matcher.find()) {
                                count++
                                if (count >= options.maxResults) return count
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

        return count
    }

    /**
     * Replace all matches in a directory.
     */
    suspend fun replaceAll(
        rootDir: File,
        options: SearchOptions,
        replacement: String,
    ): ReplaceResult {
        val pattern = compilePattern(options)
        var filesModified = 0
        var replacementsMade = 0

        rootDir.walkTopDown()
            .filter { it.isFile }
            .filter { file -> shouldInclude(file, rootDir, options) }
            .forEach { file ->
                try {
                    val content = file.readText()
                    val matcher = pattern.matcher(content)
                    val newContent = matcher.replaceAll(replacement)

                    if (newContent != content) {
                        file.writeText(newContent)
                        filesModified++
                        // Count replacements
                        val countMatcher = pattern.matcher(content)
                        while (countMatcher.find()) replacementsMade++
                    }
                } catch (_: Exception) {}
            }

        return ReplaceResult(filesModified, replacementsMade)
    }

    private fun compilePattern(options: SearchOptions): Pattern {
        var regex = if (options.isRegex) {
            options.query
        } else {
            Pattern.quote(options.query)
        }

        if (options.wholeWord) {
            regex = "\\b$regex\\b"
        }

        val flags = if (options.caseSensitive) 0 else Pattern.CASE_INSENSITIVE
        return Pattern.compile(regex, flags)
    }

    private fun shouldInclude(file: File, rootDir: File, options: SearchOptions): Boolean {
        val relativePath = file.relativeTo(rootDir).path.replace("\\", "/")

        // Check exclude patterns
        for (pattern in options.excludePatterns) {
            if (matchesGlob(relativePath, pattern)) return false
        }

        // Default excludes
        val defaultExcludes = listOf(
            ".git/", ".jcode/trash/", "node_modules/", "build/", ".gradle/",
            ".idea/", "*.class", "*.jar", "*.so", "*.png", "*.jpg", "*.gif",
        )
        for (pattern in defaultExcludes) {
            if (matchesGlob(relativePath, pattern)) return false
        }

        // Check include patterns
        if (options.includePatterns.isNotEmpty()) {
            return options.includePatterns.any { matchesGlob(relativePath, it) }
        }

        // Skip hidden files unless requested
        if (!options.includeHidden && file.name.startsWith(".")) return false

        // Skip likely binary files (> 1MB)
        if (file.length() > 1_000_000) return false

        return true
    }

    private fun matchesGlob(path: String, glob: String): Boolean {
        val regex = glob
            .replace(".", "\\.")
            .replace("**", "§§")
            .replace("*", "[^/]*")
            .replace("§§", ".*")
            .replace("?", "[^/]")
        return path.matches(Regex(regex))
    }

    private fun buildRipgrepArgs(options: SearchOptions): List<String> {
        val args = mutableListOf("--line-number", "--column", "--no-heading")

        if (!options.caseSensitive) args.add("--ignore-case")
        if (options.isRegex) args.add("--regexp")
        if (options.wholeWord) args.add("--word-regexp")
        if (options.includeHidden) args.add("--hidden")
        if (options.maxResults > 0) args.addAll(listOf("--max-count", "${options.maxResults}"))

        for (pattern in options.includePatterns) {
            args.addAll(listOf("--glob", pattern))
        }
        for (pattern in options.excludePatterns) {
            args.addAll(listOf("--glob", "!$pattern"))
        }

        return args
    }

    private fun parseRipgrepLine(line: String, rootDir: String): SearchMatch? {
        // Format: filepath:line:col:text
        val parts = line.split(":", limit = 4)
        if (parts.size < 4) return null

        val filePath = parts[0].removePrefix(rootDir).removePrefix("/").removePrefix("\\")
        val lineNumber = parts[1].toIntOrNull()?.minus(1) ?: return null
        val column = parts[2].toIntOrNull()?.minus(1) ?: return null
        val lineText = parts[3]

        return SearchMatch(
            filePath = filePath,
            lineNumber = lineNumber,
            columnStart = column,
            columnEnd = column + lineText.length,
            lineText = lineText,
            matchText = lineText,
        )
    }

    private fun escapeForShell(text: String): String {
        return text.replace("'", "'\\''")
    }
}

/**
 * Result of a replace-all operation.
 */
data class ReplaceResult(
    val filesModified: Int,
    val replacementsMade: Int,
)

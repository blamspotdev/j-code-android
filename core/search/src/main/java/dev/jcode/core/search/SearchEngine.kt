package dev.jcode.core.search

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
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
 * Uses the native ripgrep FFI (libripgrep_ffi.so) when available, falling back to an in-process
 * Kotlin walk when it isn't (CMake stub build / load failure).
 */
class SearchEngine {

    /**
     * Search for matches under [rootDir]. Results stream as a flow for progressive display.
     * Prefers the native ripgrep engine; transparently falls back to the Kotlin implementation.
     */
    fun search(rootDir: File, options: SearchOptions): Flow<SearchMatch> =
        if (NativeSearch.isAvailable) nativeSearch(rootDir, options) else javaSearch(rootDir, options)

    private fun nativeSearch(rootDir: File, options: SearchOptions): Flow<SearchMatch> = channelFlow {
        withContext(Dispatchers.IO) {
            NativeSearch.search(
                root = rootDir.absolutePath,
                query = options.query,
                flags = NativeSearch.flagsOf(options),
                includeGlobs = options.includePatterns.toTypedArray(),
                excludeGlobs = options.excludePatterns.toTypedArray(),
                maxResults = options.maxResults,
            ) { filePath, lineNumber, columnStart, columnEnd, lineText ->
                val matchText =
                    if (columnStart in 0..columnEnd && columnEnd <= lineText.length) {
                        lineText.substring(columnStart, columnEnd)
                    } else {
                        ""
                    }
                // trySendBlocking applies backpressure on the search thread and fails once the
                // collector cancels/closes the channel, which tells the native side to stop.
                trySendBlocking(
                    SearchMatch(filePath, lineNumber, columnStart, columnEnd, lineText, matchText)
                ).isSuccess
            }
        }
    }.buffer(1024)

    /**
     * Match [SearchOptions.query] against file names (not contents) under [rootDir]. Kotlin-only:
     * a name walk reads no file contents, so the native engine buys nothing here. Each match
     * carries the file's relative path, with [SearchMatch.lineText] set to the file name and the
     * column range covering the matched part of the name.
     */
    fun searchFileNames(rootDir: File, options: SearchOptions): Flow<SearchMatch> = flow {
        val pattern = compilePattern(options)
        var resultCount = 0
        rootDir.walkTopDown()
            .filter { it.isFile }
            .filter { file -> shouldInclude(file, rootDir, options, checkSize = false) }
            .forEach { file ->
                if (resultCount >= options.maxResults) return@forEach
                val matcher = pattern.matcher(file.name)
                if (matcher.find()) {
                    emit(
                        SearchMatch(
                            filePath = file.relativeTo(rootDir).path,
                            lineNumber = 0,
                            columnStart = matcher.start(),
                            columnEnd = matcher.end(),
                            lineText = file.name,
                            matchText = matcher.group(),
                        )
                    )
                    resultCount++
                }
            }
    }.flowOn(Dispatchers.IO)

    /**
     * Search [lineCount] lines of an in-memory document served by [lineText] — used for the active
     * editor buffer, which may be dirtier than what is on disk. [filePath] is stamped onto matches.
     */
    fun searchLines(
        lineCount: Int,
        lineText: (Int) -> String,
        options: SearchOptions,
        filePath: String,
    ): Flow<SearchMatch> = flow {
        val pattern = compilePattern(options)
        var resultCount = 0
        for (line in 0 until lineCount) {
            if (resultCount >= options.maxResults) break
            val text = lineText(line)
            val matcher = pattern.matcher(text)
            while (matcher.find()) {
                if (resultCount >= options.maxResults) break
                emit(SearchMatch(filePath, line, matcher.start(), matcher.end(), text, matcher.group()))
                resultCount++
            }
        }
    }.flowOn(Dispatchers.IO)

    private fun javaSearch(rootDir: File, options: SearchOptions): Flow<SearchMatch> = flow {
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

    private fun shouldInclude(
        file: File,
        rootDir: File,
        options: SearchOptions,
        checkSize: Boolean = true,
    ): Boolean {
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

        // Skip likely binary files (> 1MB) — content modes only; a name match has no size concern
        if (checkSize && file.length() > 1_000_000) return false

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

}

/**
 * Result of a replace-all operation.
 */
data class ReplaceResult(
    val filesModified: Int,
    val replacementsMade: Int,
)

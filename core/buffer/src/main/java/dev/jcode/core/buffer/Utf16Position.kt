package dev.jcode.core.buffer

import java.nio.charset.StandardCharsets

/*
 * The buffer addresses text by BYTE offset; protocols that talk about text in terms of (line,
 * character) — LSP, DAP — count UTF-16 code units within the line. The two agree only for ASCII, so
 * anything that crosses that boundary must convert rather than pass a column through.
 */

/** Byte [offset] -> 0-based (line, UTF-16 character within that line). */
fun Snapshot.offsetToUtf16Position(offset: Int): Pair<Int, Int> {
    val clamped = offset.coerceIn(0, byteLength)
    val (line, _) = offsetToLineColumn(clamped)
    val (lineStart, _) = lineAt(line)
    if (clamped <= lineStart) return line to 0
    return line to readRangeAsUtf16(lineStart, clamped).length
}

/** 0-based ([line], UTF-16 [character]) -> byte offset, clamped to the line and the buffer. */
fun Snapshot.utf16PositionToOffset(line: Int, character: Int): Int {
    if (lineCount == 0) return 0
    val clampedLine = line.coerceIn(0, lineCount - 1)
    val (lineStart, lineEnd) = lineAt(clampedLine)
    if (character <= 0) return lineStart
    val text = readRangeAsUtf16(lineStart, lineEnd)
    val clampedCharacter = character.coerceIn(0, text.length)
    return lineStart + text.substring(0, clampedCharacter).toByteArray(StandardCharsets.UTF_8).size
}

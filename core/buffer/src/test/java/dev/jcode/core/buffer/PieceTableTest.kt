package dev.jcode.core.buffer

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/**
 * Verifies the piece table against a naive byte-array-splice reference model — the previous
 * (correct but O(n)) fallback implementation, including its exact line/column semantics.
 */
class PieceTableTest {

    // --- Reference model: the old array-splice implementation, verbatim semantics ---

    private fun refInsert(content: ByteArray, offset: Int, bytes: ByteArray): ByteArray {
        val at = min(offset, content.size)
        return content.copyOfRange(0, at) + bytes + content.copyOfRange(at, content.size)
    }

    private fun refDelete(content: ByteArray, start: Int, end: Int): ByteArray {
        val s = min(start, content.size)
        val e = min(end, content.size)
        if (s >= e) return content
        return content.copyOfRange(0, s) + content.copyOfRange(e, content.size)
    }

    private fun refLineCount(content: ByteArray): Int =
        content.count { it == '\n'.code.toByte() } + 1

    private fun refOffsetToLineColumn(content: ByteArray, offset: Int): Pair<Int, Int> {
        val clamped = max(0, min(offset, content.size))
        var line = 0
        var colStart = 0
        for (i in 0 until clamped) {
            if (content[i] == '\n'.code.toByte()) {
                line++
                colStart = i + 1
            }
        }
        return line to (clamped - colStart)
    }

    private fun refLineRange(content: ByteArray, line: Int): Pair<Int, Int> {
        var currentLine = 0
        var start = 0
        var i = 0
        while (i < content.size && currentLine < line) {
            if (content[i] == '\n'.code.toByte()) {
                currentLine++
                if (currentLine == line) {
                    start = i + 1
                    break
                }
            }
            i++
        }
        if (currentLine < line) return content.size to content.size
        var end = start
        while (end < content.size && content[end] != '\n'.code.toByte()) end++
        return start to end
    }

    private fun bytes(text: String): ByteArray = text.toByteArray(StandardCharsets.UTF_8)

    private fun PieceTable.fullText(): ByteArray = snapshot().readRange(0, length)

    private fun assertMatchesReference(table: PieceTable, ref: ByteArray, rng: Random) {
        val snap = table.snapshot()
        assertEquals(ref.size, table.length)
        assertEquals(ref.size, snap.length)
        assertArrayEquals(ref, snap.readRange(0, ref.size))
        assertEquals(refLineCount(ref), snap.lineCount)

        repeat(10) {
            val off = if (ref.isEmpty()) 0 else rng.nextInt(ref.size + 1)
            assertEquals("offsetToLineColumn($off)", refOffsetToLineColumn(ref, off), snap.offsetToLineColumn(off))
        }
        repeat(10) {
            val line = rng.nextInt(refLineCount(ref) + 2)
            assertEquals("lineAt($line)", refLineRange(ref, line), snap.lineAt(line))
        }
        repeat(10) {
            val off = if (ref.isEmpty()) 0 else rng.nextInt(ref.size + 1)
            val (line, col) = refOffsetToLineColumn(ref, off)
            assertEquals("lineColumnToOffset($line, $col)", off, snap.lineColumnToOffset(line, col))
        }
        if (ref.isNotEmpty()) {
            val a = rng.nextInt(ref.size + 1)
            val b = rng.nextInt(ref.size + 1)
            val s = min(a, b)
            val e = max(a, b)
            assertArrayEquals("readRange($s, $e)", ref.copyOfRange(s, e), snap.readRange(s, e))
        }
    }

    // --- Randomized fuzz against the reference model ---

    @Test
    fun fuzzAgainstReferenceModel() {
        // Mixed single-byte, multi-byte, and newline-heavy fragments; short fragments dominate so the
        // typing (piece-extend) path is exercised heavily.
        val fragments = listOf("a", "b", "\n", "x\n", "é", "🙂", "fun main() {\n}\n", "\t", " ", "line\nline\n")
        for (seed in longArrayOf(1L, 42L, 20260703L)) {
            val rng = Random(seed)
            var ref = bytes("package demo\n\nfun main() {\n    println(\"hello\")\n}\n")
            val table = PieceTable(ref.copyOf())
            val heldSnapshot = table.snapshot()
            val heldBytes = ref.copyOf()

            repeat(1500) { step ->
                if (rng.nextInt(100) < 60 || ref.isEmpty()) {
                    val text = buildString {
                        repeat(1 + rng.nextInt(3)) { append(fragments[rng.nextInt(fragments.size)]) }
                    }
                    val insertBytes = bytes(text)
                    val off = rng.nextInt(ref.size + 1)
                    table.insert(off, insertBytes)
                    ref = refInsert(ref, off, insertBytes)
                } else {
                    val a = rng.nextInt(ref.size + 1)
                    val b = min(ref.size, a + rng.nextInt(30))
                    table.delete(a, b)
                    ref = refDelete(ref, a, b)
                }
                assertEquals("length after step $step (seed $seed)", ref.size, table.length)
                if (step % 25 == 0) assertMatchesReference(table, ref, rng)
            }
            assertMatchesReference(table, ref, rng)

            // A snapshot taken before all those edits must still read the original content: snapshots
            // share the underlying byte arrays, so this pins the append-only sharing contract.
            assertArrayEquals(heldBytes, heldSnapshot.readRange(0, heldSnapshot.length))
            assertEquals(refLineCount(heldBytes), heldSnapshot.lineCount)
        }
    }

    // --- Targeted cases ---

    @Test
    fun emptyBuffer() {
        val table = PieceTable(ByteArray(0))
        val snap = table.snapshot()
        assertEquals(0, table.length)
        assertEquals(1, snap.lineCount)
        assertEquals(0 to 0, snap.offsetToLineColumn(0))
        assertEquals(0 to 0, snap.lineAt(0))
        assertArrayEquals(ByteArray(0), snap.readRange(0, 0))
    }

    @Test
    fun sequentialTypingExtendsInPlace() {
        val table = PieceTable(ByteArray(0))
        val text = "fun main() {\n    println(\"hi\")\n}\n"
        for ((i, ch) in text.withIndex()) {
            table.insert(i, bytes(ch.toString()))
        }
        assertArrayEquals(bytes(text), table.fullText())
        assertEquals(4, table.snapshot().lineCount)
    }

    @Test
    fun insertMiddleSplitsAndReads() {
        val table = PieceTable(bytes("hello world"))
        table.insert(5, bytes(", big"))
        assertArrayEquals(bytes("hello, big world"), table.fullText())
        table.insert(0, bytes(">> "))
        table.insert(table.length, bytes(" <<"))
        assertArrayEquals(bytes(">> hello, big world <<"), table.fullText())
    }

    @Test
    fun deleteAcrossPieces() {
        val table = PieceTable(bytes("aaa\nbbb\n"))
        table.insert(4, bytes("XXX\n"))       // "aaa\nXXX\nbbb\n"
        table.insert(0, bytes("start\n"))     // "start\naaa\nXXX\nbbb\n"
        table.delete(3, 15)                    // removes across three pieces -> "sta" + "bb\n"
        assertArrayEquals(bytes("stabb\n"), table.fullText())
        assertEquals(2, table.snapshot().lineCount)
        table.delete(0, table.length)
        assertEquals(0, table.length)
        assertEquals(1, table.snapshot().lineCount)
    }

    @Test
    fun lineQueriesWithTrailingNewline() {
        val table = PieceTable(bytes("one\ntwo\n"))
        val snap = table.snapshot()
        assertEquals(3, snap.lineCount)
        assertEquals(0 to 3, snap.lineAt(0))
        assertEquals(4 to 7, snap.lineAt(1))
        assertEquals(8 to 8, snap.lineAt(2))
        assertEquals(8 to 8, snap.lineAt(5))
        assertEquals(1 to 2, snap.offsetToLineColumn(6))
        assertEquals(6, snap.lineColumnToOffset(1, 2))
    }

    @Test
    fun bufferApiAppliesMultiOpTransactions() {
        Buffer.fromText("hello world").use { buffer ->
            buffer.applyEdit(EditTx.replace(0, 5, "goodbye")).let { snap ->
                assertEquals("goodbye world", snap.readRangeAsUtf16(0, snap.byteLength))
            }
            buffer.applyEdit(
                EditTx.builder()
                    .insert(0, "# ")
                    .delete(2, 9)
                    .insert(2, "hi")
                    .build()
            ).let { snap ->
                assertEquals("# hi world", snap.readRangeAsUtf16(0, snap.byteLength))
            }
            assertEquals(10, buffer.byteLength)
            assertEquals(1, buffer.lineCount)
        }
    }
}

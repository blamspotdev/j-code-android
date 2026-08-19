package dev.jcode.core.editor

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jcode.core.buffer.Buffer
import dev.jcode.core.buffer.NativeWrap
import dev.jcode.core.buffer.Snapshot
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Pins the native wrap layout (wrap_map.cpp) to [WrapMap.buildWithKotlin], the Kotlin reference it
 * was ported from, through the real JNI. Both sides emit the same packed int array, so the assertion
 * is exact array equality — any divergence in a row break, a line length or the UTF-16 column walk
 * fails here rather than as a misplaced caret on device.
 *
 * Run: `ANDROID_SERIAL=<dev> gradlew :core:editor:connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class WrapMapDifferentialTest {

    private val widths = intArrayOf(1, 2, 3, 7, 16, 40, 80, 200)

    private fun withSnapshot(text: String, body: (Snapshot) -> Unit) {
        assertTrue(
            "native buffer unavailable — the differential has nothing to compare",
            Buffer.isNativeAvailable(),
        )
        Buffer.fromText(text).use { buffer ->
            buffer.snapshot().use { snapshot -> body(snapshot) }
        }
    }

    private fun assertParity(text: String, label: String) {
        withSnapshot(text) { snapshot ->
            for (cpr in widths) {
                val expected = WrapMap.buildWithKotlin(snapshot, cpr)
                val actual = NativeWrap.build(snapshot, cpr)
                    ?: throw AssertionError("native build returned null for $label")
                assertArrayEquals("$label @ charsPerRow=$cpr", expected, actual)
            }
        }
    }

    @Test
    fun emptyAndTrivialBuffers() {
        assertParity("", "empty")
        assertParity("\n", "single newline")
        assertParity("\n\n\n", "blank lines")
        assertParity("a", "one char")
        assertParity("no trailing newline", "no trailing newline")
        assertParity("trailing newline\n", "trailing newline")
    }

    @Test
    fun asciiCodeShapes() {
        assertParity(
            """
            package dev.jcode.core.editor

            class Example(private val name: String) {
                fun greet(): String = "hello, ${'$'}name — this line is deliberately long enough to wrap several times over"
            }
            """.trimIndent(),
            "kotlin source",
        )
        assertParity("word ".repeat(400), "many short words")
        assertParity("x".repeat(5000), "one unbroken run")
        assertParity("\t\t\tindented\tby\ttabs\tthroughout\tthe\twhole\tline", "tabs")
        assertParity("   ".repeat(200) + "end", "leading whitespace runs")
        assertParity("a  b   c    d     e", "consecutive spaces")
    }

    @Test
    fun nonAsciiColumnsCountUtf16Units() {
        // Two bytes/one unit, three bytes/one unit, four bytes/TWO units — the surrogate case is the
        // one that separates a UTF-16 walk from a codepoint walk.
        assertParity("café ".repeat(120), "latin-1 accents")
        assertParity("ы".repeat(300), "cyrillic")
        assertParity("λ ".repeat(300), "greek")
        assertParity("日本語のテキストが折り返される".repeat(40), "cjk")
        assertParity("🙂".repeat(200), "emoji only (surrogate pairs)")
        assertParity("a🙂b🙂c 🙂 ".repeat(80), "mixed ascii and surrogates")
        assertParity("🙂 ".repeat(200), "surrogate pairs with spaces")
        assertParity("mixed λ text ы with 🙂 everything 日本 ".repeat(60), "mixed scripts")
    }

    @Test
    fun malformedUtf8MatchesJavaReplacement() {
        // Java's decoder emits one U+FFFD per malformed sequence; the C++ decoder mirrors that so a
        // corrupt byte shifts both layouts identically instead of only one of them.
        val cases = listOf(
            byteArrayOf(0x41, 0x80.toByte(), 0x42) to "stray continuation",
            byteArrayOf(0x41, 0xC0.toByte(), 0x80.toByte()) to "overlong two-byte",
            byteArrayOf(0x41, 0xE0.toByte(), 0x80.toByte(), 0x41) to "overlong three-byte",
            byteArrayOf(0x41, 0xED.toByte(), 0xA0.toByte(), 0x80.toByte()) to "encoded surrogate",
            byteArrayOf(0x41, 0xF5.toByte(), 0x41) to "out of range lead",
            byteArrayOf(0x41, 0xE2.toByte(), 0x82.toByte()) to "truncated at end",
            byteArrayOf(0x41, 0xF0.toByte(), 0x9F.toByte(), 0x99.toByte()) to "truncated surrogate pair",
        )
        for ((bytes, label) in cases) {
            assertParity(String(bytes, Charsets.UTF_8), "malformed: $label")
        }
    }

    @Test
    fun fuzzAgainstKotlinReference() {
        val alphabet = listOf(
            "a", "b", " ", "  ", "\t", "\n", "word", "longertoken", "-", "_",
            "é", "ы", "λ", "日", "🙂", "🙂🙂",
        )
        for (seed in listOf(1, 7, 99)) {
            val rng = Random(seed)
            val text = buildString {
                repeat(4000) { append(alphabet[rng.nextInt(alphabet.size)]) }
            }
            assertParity(text, "fuzz seed=$seed")
        }
    }

    @Test
    fun queriesRoundTripThroughTheFlatLayout() {
        val text = (0 until 500).joinToString("\n") { i ->
            "line $i " + "token ".repeat(i % 17)
        }
        withSnapshot(text) { snapshot ->
            val map = WrapMap(snapshot, 40)
            assertTrue(map.totalRows >= snapshot.lineCount)

            // Every row resolves to a line whose row range contains it, and rowOf inverts rowToLine.
            for (row in 0 until map.totalRows) {
                val span = map.rowToLine(row)
                assertTrue("row $row -> line ${span.line}", span.line in 0 until snapshot.lineCount)
                assertTrue("span $span", span.startColumn <= span.endColumn)
                assertEquals("rowOf(rowToLine($row))", row, map.rowOf(span.line, span.startColumn))
                assertTrue("row $row precedes its line start", row >= map.firstRowOf(span.line))
            }

            // First row of each line is exactly where that line's rows begin.
            for (line in 0 until snapshot.lineCount) {
                val first = map.firstRowOf(line)
                assertEquals("firstRowOf($line)", line, map.rowToLine(first).line)
                assertEquals("column 0 of line $line", first, map.rowOf(line, 0))
            }
        }
    }

    @Test
    fun byteColumnConvertersRoundTrip() {
        val samples = listOf(
            "plain ascii line",
            "café ы λ 日本語 🙂 mixed",
            "🙂🙂🙂",
            "",
            "\ttabbed\tline",
        )
        for (text in samples) {
            for (charIndex in 0..text.length) {
                val byteCol = WrapMap.charIndexToByteCol(text, charIndex)
                // The old implementation, kept here as the oracle for the allocation-free rewrite.
                val expected = text.substring(0, charIndex).toByteArray(Charsets.UTF_8).size
                assertEquals("charIndexToByteCol($text, $charIndex)", expected, byteCol)
            }
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                val byteCol = WrapMap.charIndexToByteCol(text, i)
                assertEquals("round trip at $i in \"$text\"", i, WrapMap.byteColToCharIndex(text, byteCol))
                i += Character.charCount(cp)
            }
        }
    }

    @Test
    fun benchmarkNativeVsKotlin() {
        val short = "val someValue = compute(argument, other) // a representative source line\n"
        // Prose-shaped long lines are the case wrap actually costs on: each logical line becomes
        // many visual rows, so the scan does far more work per line than a short-line source file.
        val long = ("lorem ipsum dolor sit amet consectetur adipiscing elit sed do eiusmod " +
            "tempor incididunt ut labore et dolore magna aliqua ").repeat(6) + "\n"
        val unicode = "café ы λ 日本語 🙂 mixed content that has to decode instead of taking the ascii path\n"

        val cases = listOf(
            Triple("short-lines", short.repeat(20_000), 80),
            Triple("long-lines", long.repeat(4_000), 80),
            Triple("narrow-wrap", long.repeat(2_000), 24),
            Triple("non-ascii", unicode.repeat(10_000), 80),
        )
        for ((label, text, cpr) in cases) {
            withSnapshot(text) { snapshot ->
                // Warm both paths so the numbers are steady-state, not first-call.
                WrapMap.buildWithKotlin(snapshot, cpr)
                NativeWrap.build(snapshot, cpr)

                val kotlinMs = measureTimeMillis { repeat(3) { WrapMap.buildWithKotlin(snapshot, cpr) } } / 3
                val nativeMs = measureTimeMillis { repeat(3) { NativeWrap.build(snapshot, cpr) } } / 3
                val rows = NativeWrap.build(snapshot, cpr)!![1]
                Log.i(
                    "WrapMapBench",
                    "$label lines=${snapshot.lineCount} rows=$rows charsPerRow=$cpr " +
                        "kotlin=${kotlinMs}ms native=${nativeMs}ms",
                )
                assertArrayEquals(WrapMap.buildWithKotlin(snapshot, cpr), NativeWrap.build(snapshot, cpr))
            }
        }
    }
}

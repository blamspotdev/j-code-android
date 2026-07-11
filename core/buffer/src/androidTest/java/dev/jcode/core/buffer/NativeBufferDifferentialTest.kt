package dev.jcode.core.buffer

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.random.Random
import kotlin.system.measureNanoTime
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Differential verification of the C++ piece table (libjcodebuffer.so) against the production
 * Kotlin PieceTable, through the real Buffer/Snapshot JNI on a real device. Identical random
 * transactions are applied to both implementations plus a naive byte-splice reference; content
 * and every line/offset query must agree at each checkpoint. This must pass before
 * USE_NATIVE_BUFFER is flipped.
 */
@RunWith(AndroidJUnit4::class)
class NativeBufferDifferentialTest {

    private fun nativeBuffer(initial: ByteArray) = Buffer(initial, useNative = true)
    private fun kotlinBuffer(initial: ByteArray) = Buffer(initial, useNative = false)

    private class ReferenceModel(initial: ByteArray) {
        var content: ByteArray = initial.copyOf()
            private set

        fun apply(tx: EditTx) {
            for (op in tx.ops) {
                when (op) {
                    is EditOp.Insert -> {
                        val bytes = op.text.toByteArray(Charsets.UTF_8)
                        val off = op.offset.coerceIn(0, content.size)
                        content = content.copyOfRange(0, off) + bytes + content.copyOfRange(off, content.size)
                    }
                    is EditOp.Delete -> {
                        val s = op.start.coerceIn(0, content.size)
                        val e = op.end.coerceIn(0, content.size)
                        if (s < e) content = content.copyOfRange(0, s) + content.copyOfRange(e, content.size)
                    }
                }
            }
        }
    }

    private fun randomText(rng: Random, maxLen: Int): String {
        val alphabet = listOf("a", "b", "z", " ", "\n", "λ", "ы", "🙂", "\n\n", "word ", "fun main()\n")
        val n = rng.nextInt(1, maxLen + 1)
        return buildString { repeat(n) { append(alphabet[rng.nextInt(alphabet.size)]) } }
    }

    private fun randomTx(rng: Random, length: Int): EditTx {
        return when (rng.nextInt(10)) {
            in 0..5 -> EditTx.insert(rng.nextInt(-2, length + 3), randomText(rng, 6))
            in 6..8 -> {
                val s = rng.nextInt(-2, length + 3)
                EditTx.delete(s, s + rng.nextInt(0, 24))
            }
            else -> {
                val s = rng.nextInt(-2, length + 3).coerceAtLeast(0)
                EditTx.replace(s, s + rng.nextInt(0, 12), randomText(rng, 4))
            }
        }
    }

    private fun assertBuffersAgree(nat: Buffer, ktl: Buffer, ref: ReferenceModel, rng: Random, step: Int) {
        val len = ref.content.size
        assertEquals("byteLength@$step", len, nat.byteLength)
        assertEquals("byteLength(kotlin)@$step", len, ktl.byteLength)
        assertArrayEquals("content@$step", ref.content, nat.readRange(0, len))
        assertEquals("lineCount@$step", ktl.lineCount, nat.lineCount)

        repeat(8) {
            val off = rng.nextInt(0, len + 2)
            assertEquals("offsetToLineColumn($off)@$step", ktl.offsetToLineColumn(off), nat.offsetToLineColumn(off))
        }
        repeat(8) {
            val line = rng.nextInt(0, ktl.lineCount + 2)
            assertEquals("lineAt($line)@$step", ktl.lineAt(line), nat.lineAt(line))
        }
        repeat(8) {
            val line = rng.nextInt(0, ktl.lineCount + 2)
            val col = rng.nextInt(0, 40)
            assertEquals(
                "lineColumnToOffset($line,$col)@$step",
                ktl.lineColumnToOffset(line, col),
                nat.lineColumnToOffset(line, col),
            )
        }
        if (len > 0) {
            val s = rng.nextInt(0, len)
            val e = rng.nextInt(s, len + 1)
            assertArrayEquals("readRange($s,$e)@$step", ktl.readRange(s, e), nat.readRange(s, e))
        }

        val first = rng.nextInt(0, ktl.lineCount + 1)
        val lines = rng.nextInt(1, 24)
        ktl.snapshot().use { kSnap ->
            nat.snapshot().use { nSnap ->
                val kw = kSnap.readLines(first, lines)
                val nw = nSnap.readLines(first, lines)
                for (line in first until first + lines) {
                    assertEquals("readLines text($line)@$step", kw.text(line), nw.text(line))
                    assertEquals("readLines start($line)@$step", kw.byteStart(line), nw.byteStart(line))
                    assertEquals("readLines end($line)@$step", kw.byteEnd(line), nw.byteEnd(line))
                }
            }
        }
    }

    @Test
    fun fuzzNativeAgainstKotlinAndReference() {
        for (seed in longArrayOf(1L, 42L, 20260711L)) {
            val rng = Random(seed)
            val initial = randomText(rng, 400).toByteArray(Charsets.UTF_8)
            val ref = ReferenceModel(initial)
            var earlySnapshot: Snapshot? = null
            var earlyContent: ByteArray? = null

            nativeBuffer(initial).use { nat ->
                kotlinBuffer(initial).use { ktl ->
                    repeat(1200) { step ->
                        val tx = randomTx(rng, ref.content.size)
                        ref.apply(tx)
                        nat.applyEdit(tx).close()
                        ktl.applyEdit(tx).close()

                        if (step == 100) {
                            earlySnapshot = nat.snapshot()
                            earlyContent = ref.content.copyOf()
                        }
                        if (step % 40 == 0 || step == 1199) {
                            assertBuffersAgree(nat, ktl, ref, rng, step)
                        }
                    }

                    // A snapshot taken mid-run must still read the content from its capture time,
                    // untouched by the 1100 edits that followed.
                    val snap = earlySnapshot!!
                    val expected = earlyContent!!
                    assertEquals("snapshot byteLength (seed=$seed)", expected.size, snap.byteLength)
                    assertArrayEquals("snapshot content (seed=$seed)", expected, snap.readRange(0, expected.size))
                    snap.close()
                }
            }
        }
    }

    @Test
    fun emptyAndEdgeSemantics() {
        nativeBuffer(ByteArray(0)).use { b ->
            assertEquals(0, b.byteLength)
            assertEquals(1, b.lineCount)
            assertEquals(0 to 0, b.offsetToLineColumn(0))
            assertEquals(0 to 0, b.lineAt(0))
            assertEquals(0, b.readRange(0, 0).size)
        }
        // Reference values mirrored from PieceTableTest.lineQueriesWithTrailingNewline.
        nativeBuffer("one\ntwo\n".toByteArray()).use { b ->
            assertEquals(3, b.lineCount)
            assertEquals(0 to 3, b.lineAt(0))
            assertEquals(4 to 7, b.lineAt(1))
            assertEquals(8 to 8, b.lineAt(2))
            assertEquals(8 to 8, b.lineAt(5))
            assertEquals(1 to 2, b.offsetToLineColumn(6))
            assertEquals(6, b.lineColumnToOffset(1, 2))
        }
    }

    @Test
    fun benchmarkNativeVsKotlin() {
        val bigText = buildString {
            repeat(30_000) { append("val line$it = compute($it) + \"αβγ\" // trailing comment\n") }
        }
        val bigBytes = bigText.toByteArray(Charsets.UTF_8)

        fun bench(label: String, make: () -> Buffer) {
            val tOpen = measureNanoTime { make().close() }

            val b = make()
            var caret = b.byteLength / 2
            val tTyping = measureNanoTime {
                repeat(10_000) {
                    b.applyEdit(EditTx.insert(caret, "x")).close()
                    caret += 1
                }
            }

            val rng = Random(7)
            val tRandom = measureNanoTime {
                repeat(2_000) {
                    val len = b.byteLength
                    if (rng.nextBoolean()) {
                        b.applyEdit(EditTx.insert(rng.nextInt(len + 1), "hello\n")).close()
                    } else {
                        val s = rng.nextInt(len)
                        b.applyEdit(EditTx.delete(s, s + rng.nextInt(1, 8))).close()
                    }
                }
            }

            val snap = b.snapshot()
            var scanned = 0L
            val tLineScan = measureNanoTime {
                for (line in 0 until snap.lineCount) {
                    val (s, e) = snap.lineAt(line)
                    scanned += e - s
                }
            }
            snap.close()
            b.close()

            Log.i(
                TAG,
                "$label: open=${tOpen / 1_000_000}ms typing10k=${tTyping / 1_000_000}ms " +
                    "random2k=${tRandom / 1_000_000}ms lineScan=${tLineScan / 1_000_000}ms scanned=$scanned",
            )
        }

        bench("KOTLIN") { kotlinBuffer(bigBytes) }
        bench("NATIVE") { nativeBuffer(bigBytes) }
    }

    private companion object {
        const val TAG = "NativeBufferBench"
    }
}

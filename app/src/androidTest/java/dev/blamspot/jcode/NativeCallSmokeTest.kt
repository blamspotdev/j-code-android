package dev.blamspot.jcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.blamspot.jcode.core.search.SearchEngine
import dev.blamspot.jcode.core.search.SearchOptions
import dev.blamspot.jcode.core.term.PtyProcess
import dev.blamspot.jcode.core.term.VtParser
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Calls into the native libraries that [NativeLibrariesSmokeTest] only loads.
 *
 * Loading proves very little on its own: JCode binds JNI implicitly, by symbol name, so an entry
 * point that no longer matches its Kotlin declaration links fine and fails at the first *call*.
 * `dlopen` succeeding is exactly what that failure looks like right up until something calls it.
 * These three are the libraries no other instrumented test reaches — buffer and the editor core are
 * already covered by the differential fuzz tests in `:core:buffer` and `:core:editor`.

 */
@RunWith(AndroidJUnit4::class)
class NativeCallSmokeTest {

    @Test
    fun vtParserFeedsAndReadsBack() {
        VtParser(rows = 24, cols = 80).use { vt ->
            vt.feed("hi".toByteArray())
            assertEquals('h'.code, vt.getCellCodePoint(0, 0))
            assertEquals('i'.code, vt.getCellCodePoint(0, 1))
            // An SGR sequence drives the parser itself, not just the cell store. The escape is
            // spelled out rather than pasted: a raw ESC byte in source is invisible in every editor
            // and diff that will ever show this line.
            vt.feed("\u001B[31mR".toByteArray())
            assertEquals('R'.code, vt.getCellCodePoint(0, 2))
            vt.resize(30, 100)
            vt.reset()
        }
    }

    @Test
    fun ptySpawnsReadsAndReaps() {
        val pty = PtyProcess.create(
            exe = "/system/bin/sh",
            argv = listOf("sh", "-c", "echo jcode-pty-ok"),
        )
        val out = StringBuilder()
        val buf = ByteArray(4096)
        repeat(40) {
            if (!pty.awaitReadable(250)) return@repeat
            val n = pty.read(buf)
            if (n > 0) out.append(String(buf, 0, n))
            if (out.contains("jcode-pty-ok")) return@repeat
        }
        assertTrue("pty output was: $out", out.contains("jcode-pty-ok"))
        pty.waitForExit()
    }

    @Test
    fun ripgrepFindsAMatch() {
        val dir = File(
            InstrumentationRegistry.getInstrumentation().targetContext.cacheDir,
            "native-call-smoke",
        ).apply { mkdirs() }
        File(dir, "sample.txt").writeText("alpha\nbeta jcode-needle gamma\ndelta\n")
        try {
            val matches = runBlocking {
                SearchEngine().search(dir, SearchOptions(query = "jcode-needle")).toList()
            }
            assertEquals("matches: $matches", 1, matches.size)
            assertEquals(1, matches[0].lineNumber)
        } finally {
            dir.deleteRecursively()
        }
    }
}

package dev.jcode

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.jcode.core.buffer.Buffer
import dev.jcode.editor.SyntaxHighlighter
import dev.jcode.editor.TokenPalette
import dev.jcode.feature.marketplace.LanguagePack
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Differential verification of the native highlighter (highlight.cpp) against the Kotlin
 * SyntaxHighlighter reference, through the real Buffer/Snapshot JNI on device. Every mode's span
 * list must match exactly — order, byte offsets, colors, flags. Must stay green before any change
 * to either implementation ships.
 */
@RunWith(AndroidJUnit4::class)
class SyntaxHighlighterDifferentialTest {

    private fun pack(
        id: String,
        lineComment: String? = "//",
        blockStart: String? = "/*",
        blockEnd: String? = "*/",
        delims: List<String> = listOf("\"", "'"),
        keywords: Set<String> = setOf("fun", "val", "if", "true", "false", "null"),
        types: Set<String> = setOf("Int", "String"),
    ) = LanguagePack(
        languageId = id,
        fileExtensions = emptyList(),
        lineComment = lineComment,
        blockCommentStart = blockStart,
        blockCommentEnd = blockEnd,
        stringDelimiters = delims,
        keywords = keywords,
        types = types,
        indent = null,
        trimTrailingWhitespace = false,
        insertFinalNewline = false,
        formatterCommand = null,
        completions = emptyList(),
        helpers = emptyList(),
    )

    private val kotlinPack = pack("kotlin")
    private val yamlPack = pack("yaml", lineComment = "#", blockStart = null, blockEnd = null, keywords = setOf("true", "false", "null", "yes", "no"))
    private val iniPack = pack("ini", lineComment = "#", blockStart = null, blockEnd = null, keywords = setOf("true", "false"))
    private val htmlPack = pack("html", lineComment = null, blockStart = "<!--", blockEnd = "-->", keywords = emptySet(), types = emptySet())

    private fun check(text: String, fileName: String, lang: LanguagePack?) {
        assertTrue("native buffer must be available", Buffer.isNativeAvailable())
        for (palette in listOf(TokenPalette.DARK, TokenPalette.LIGHT)) {
            val expected = SyntaxHighlighter.highlightFor(text, fileName, lang, palette)
            Buffer.fromText(text).use { buffer ->
                buffer.snapshot().use { snap ->
                    val actual = SyntaxHighlighter.nativeSpans(snap, fileName, lang, palette)
                    assertNotNull("native path must run for $fileName", actual)
                    assertEquals("spans for $fileName over:\n$text", expected, actual)
                }
            }
        }
    }

    @Test
    fun tokenizeMode() {
        val code = """
            @Deprecated("old")
            fun main(args: String) { // entry 🙂 comment
                val COUNT_MAX = 0xFF_2.parts
                val λstreng = "esc \" quote 🙂" + 'c' + unterminated
                obj . member(if (COUNT_MAX >= 3) foo() else bar)
                /* block
                   spans lines */ ы_var != other /* unterminated...
            """.trimIndent()
        check(code, "main.kt", kotlinPack)
        check(code, "plain.zz", null)  // generic fallback profile incl backtick + "# " comment
        check("# led comment\n#nospace\nx = `multi\nline`\n", "gen.zz", null)
        check("", "empty.kt", kotlinPack)
    }

    @Test
    fun markdownMode() {
        val md = """
            # Heading one
            ####### not a heading
            Some **bold** and _italic_ and `code span` here λ.
            > quoted **deep**
            - item one
            12. numbered ) wrong
            12) numbered right
            ***
            [link](https://x.y) and ![img](a.png) and [broken](
            ```kotlin
            fenced code ** not bold **
            ~~~
            still fenced
            ```
            after ~~~ tilde fence
            ~~~
            tilde fenced
            """.trimIndent()
        check(md, "README.md", null)
        check("*a*_b_**c**`d`", "t.markdown", kotlinPack)
    }

    @Test
    fun markupMode() {
        val html = """
            <!doctype html>
            <!-- comment <b>bold</b> -->
            <div class="x y" data-λ='v' disabled>
              text &amp; more &#169; plain & bare
              <br/>
              <?xml version="1.0"?>
            </div>
            <unterminated attr="v
            """.trimIndent()
        check(html, "page.html", htmlPack)
    }

    @Test
    fun keyValueMode() {
        val yaml = """
            ---
            key: value with true and 42
            - list: item
            quoted: "str # not comment" # real comment
            negative: -3.5:x
            empty:
            no-sep-line value here
            ...
            """.trimIndent()
        check(yaml, "config.yaml", yamlPack)
        val ini = """
            [section]
            key = value ; ini comment
            flag = true
            # hash comment
            """.trimIndent()
        check(ini, "conf.ini", iniPack)
    }

    @Test
    fun jsonMode() {
        val json = """
            {
              // jsonc comment
              "key": "value", 'single': -12.5e+3,
              /* block */ "flag": true, "n": null, "inf": Infinity,
              "λ": [1, 2, NaN], "unterminated": "...
            """.trimIndent()
        check(json, "data.json", null)
        check("{}", "m.webmanifest", null)
    }

    @Test
    fun benchmarkNativeVsKotlin() {
        val big = buildString {
            repeat(12_000) {
                append("fun name$it(x: Int): String { val COUNT_$it = obj.prop + \"str λ $it\" } // note 🙂\n")
            }
        }
        val palette = TokenPalette.DARK
        var kotlinNs = 0L
        var nativeNs = 0L
        var kotlinSpans = 0
        var nativeSpans = 0
        Buffer.fromText(big).use { buffer ->
            buffer.snapshot().use { snap ->
                repeat(3) {
                    val k0 = System.nanoTime()
                    kotlinSpans = SyntaxHighlighter.highlightFor(big, "bench.kt", kotlinPack, palette).size
                    kotlinNs += System.nanoTime() - k0
                    val n0 = System.nanoTime()
                    nativeSpans = SyntaxHighlighter.nativeSpans(snap, "bench.kt", kotlinPack, palette)!!.size
                    nativeNs += System.nanoTime() - n0
                }
            }
        }
        assertEquals(kotlinSpans, nativeSpans)
        android.util.Log.i(
            "HighlightBench",
            "bytes=${big.toByteArray().size} kotlin=${kotlinNs / 3_000_000}ms native=${nativeNs / 3_000_000}ms spans=$nativeSpans",
        )
    }

    @Test
    fun fuzzAllModes() {
        val pieces = listOf(
            "fun ", "val ", "if(", ")", " { ", "}\n", "\"str\\\"x\"", "'c'", "`tick`", "// cm\n",
            "/* b */", "@Anno ", "123.4_5 ", "CONST_X ", ".prop ", "a+b=>c ", "λы ", "🙂", "# h\n",
            "**b** ", "_i_ ", "[l](u) ", "```\n", "> q\n", "- li\n", "<tag a=\"v\">", "&amp;",
            "<!-- c -->", "k: v\n", "k = v\n", "[sec]\n", "--- \n", "\"k\": 1,", "true ", "null\n",
            "\\", "\n", " ", "\t",
        )
        for (seed in longArrayOf(3L, 77L, 20260711L)) {
            val rng = Random(seed)
            val doc = buildString { repeat(300) { append(pieces[rng.nextInt(pieces.size)]) } }
            check(doc, "f.kt", kotlinPack)
            check(doc, "f.zz", null)
            check(doc, "f.md", null)
            check(doc, "f.html", htmlPack)
            check(doc, "f.yaml", yamlPack)
            check(doc, "f.ini", iniPack)
            check(doc, "f.json", null)
        }
    }
}

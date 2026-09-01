package dev.blamspot.jcode.design

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Rule matching decides which icon a file gets, and packs will lean on the precedence order being
 * what the docs promise: exact name, then glob, then regex, then extension.
 */
class IconRuleTableTest {

    private val table = IconRuleTable(
        names = mapOf("dockerfile" to "docker", "package.json" to "npm"),
        globs = listOf(Regex("\\.env.*", RegexOption.IGNORE_CASE) to "env"),
        patterns = listOf(Regex("^test_") to "test"),
        extensions = mapOf("ts" to "typescript", "d.ts" to "typedef", "json" to "json", "py" to "python"),
    )

    @Test
    fun `an exact name wins over its own extension`() {
        assertEquals("npm", table.match("package.json"))
        assertEquals("json", table.match("tsconfig.json"))
    }

    @Test
    fun `names match without regard to case`() {
        assertEquals("docker", table.match("Dockerfile"))
        assertEquals("docker", table.match("dockerfile"))
    }

    @Test
    fun `a glob beats a regex, and a regex beats an extension`() {
        assertEquals("env", table.match(".env.local"))
        assertEquals("test", table.match("test_utils.py"))
        assertEquals("python", table.match("utils.py"))
    }

    @Test
    fun `the longest compound extension wins`() {
        assertEquals("typedef", table.match("index.d.ts"))
        assertEquals("typescript", table.match("index.ts"))
        // Only the extension is compound, not the whole name.
        assertEquals("typescript", table.match("some.d.thing.ts"))
    }

    @Test
    fun `a leading dot is part of the name, not the start of an extension`() {
        // `.ts` as a whole filename is not a TypeScript file; there is no extension to read.
        assertNull(table.match(".ts"))
        assertEquals("typescript", table.match(".hidden.ts"))
    }

    @Test
    fun `an unmatched name resolves to nothing so the host can fall back`() {
        assertNull(table.match("LICENSE"))
        assertNull(table.match("archive.tar.gz"))
    }

    @Test
    fun `an empty table matches nothing`() {
        assertNull(IconRuleTable<String>().match("index.ts"))
    }
}

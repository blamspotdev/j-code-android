package dev.blamspot.jcode.design

import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The index reader, against packs on disk. Covers both shipped layouts and the ways a hand-written
 * index goes wrong — a pack is third-party content, so "drops the bad entry, keeps the rest" is the
 * behaviour that matters.
 */
class IconPackLoaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var pack: File

    private val squareSvg =
        """<svg viewBox="0 0 24 24"><path d="M4 4 H20 V20 H4 Z"/></svg>"""

    @Before
    fun setUp() {
        pack = temp.newFolder("pack")
        // Parsed sets are memoized by path + mtime, and a temp folder can reuse a path.
        IconPackLoader.evict()
    }

    @After
    fun tearDown() = IconPackLoader.evict()

    private fun write(relative: String, text: String): File {
        val file = File(pack, relative)
        file.parentFile?.mkdirs()
        file.writeText(text)
        return file
    }

    private fun art(vararg names: String) = names.forEach { write(it, squareSvg) }

    // --- UI sets ------------------------------------------------------------------------------

    @Test
    fun `reads a ui set laid out beside its art`() = runBlocking {
        art("ui-icons/run.svg", "ui-icons/stop.svg")
        val index = write(
            "ui-icons/index.yaml",
            """
            id: my-ui
            name: My UI
            description: Hello
            icons:
              Run: run.svg
              Stop: stop.svg
            aliases:
              Continue: Run
            """.trimIndent(),
        )

        val set = IconPackLoader.uiIconSet(index, "ext.id", "Ext")!!
        assertEquals("ext.id/my-ui", set.id)
        assertEquals("My UI", set.name)
        assertEquals("ext.id", set.providerId)
        // Two definitions plus the alias.
        assertEquals(3, set.filledSlots)
        assertEquals(set.art(JCodeIcon.Run), set.art(JCodeIcon.Continue))
    }

    @Test
    fun `a ui set falls back to the built-in default for slots it does not fill`() = runBlocking {
        art("ui-icons/run.svg")
        val index = write("ui-icons/index.yaml", "id: partial\nicons:\n  Run: run.svg\n")

        val set = IconPackLoader.uiIconSet(index, "ext.id", "Ext")!!
        assertEquals(1, set.filledSlots)
        // Debug is not in the pack, so it must be Material's glyph — not the unknown-slot circle.
        assertEquals(defaultUiIconSet.art(JCodeIcon.Debug), set.art(JCodeIcon.Debug))
    }

    @Test
    fun `reads the flat layout through base`() = runBlocking {
        art("media/icons/run.svg")
        val index = write(
            "ui-icons.yaml",
            """
            id: flat
            base: media/icons
            icons:
              Run: run.svg
            """.trimIndent(),
        )
        assertEquals(1, IconPackLoader.uiIconSet(index, "ext.id", "Ext")!!.filledSlots)
    }

    @Test
    fun `drops entries that name nothing and keeps the rest`() = runBlocking {
        art("ui-icons/run.svg")
        val index = write(
            "ui-icons/index.yaml",
            """
            id: partial
            icons:
              Run: run.svg
              Stop: missing.svg
              NotASlot: run.svg
            aliases:
              Pause: AlsoNotASlot
            """.trimIndent(),
        )
        assertEquals(1, IconPackLoader.uiIconSet(index, "ext.id", "Ext")!!.filledSlots)
    }

    @Test
    fun `refuses art outside the pack`() = runBlocking {
        val outside = temp.newFile("outside.svg").also { it.writeText(squareSvg) }
        val index = write(
            "ui-icons/index.yaml",
            "id: escape\nicons:\n  Run: ../../${outside.name}\n",
        )
        assertNull(IconPackLoader.uiIconSet(index, "ext.id", "Ext"))
    }

    @Test
    fun `refuses a file type it cannot decode`() = runBlocking {
        write("ui-icons/run.exe", "MZ")
        val index = write("ui-icons/index.yaml", "id: bad\nicons:\n  Run: run.exe\n")
        assertNull(IconPackLoader.uiIconSet(index, "ext.id", "Ext"))
    }

    @Test
    fun `refuses a malformed index instead of throwing`() = runBlocking {
        assertNull(IconPackLoader.uiIconSet(write("a.yaml", "\t: [unbalanced"), "ext.id", "Ext"))
        assertNull(IconPackLoader.uiIconSet(write("b.yaml", "just a string"), "ext.id", "Ext"))
        assertNull(IconPackLoader.uiIconSet(write("c.yaml", ""), "ext.id", "Ext"))
        assertNull(IconPackLoader.uiIconSet(File(pack, "nope.yaml"), "ext.id", "Ext"))
    }

    // --- file sets ----------------------------------------------------------------------------

    private fun fileIndex(): File {
        art(
            "files-icons/file.svg", "files-icons/folder.svg", "files-icons/folder-open.svg",
            "files-icons/ts.svg", "files-icons/npm.svg", "files-icons/src.svg", "files-icons/src-open.svg",
        )
        return write(
            "files-icons/index.yaml",
            """
            id: my-files
            name: My Files
            defaults:
              size: 16
              tint: none
              file: file
              folder: folder
              folderOpen: folder-open
            icons:
              file: file.svg
              folder: folder.svg
              folder-open: folder-open.svg
              typescript: { file: ts.svg, size: 20, scale: 0.9, tint: theme }
              npm: npm.svg
              folder-src: src.svg
              folder-src-open: src-open.svg
            aliases:
              ts: typescript
            files:
              - icon: typescript
                extensions: [ts, tsx]
              - icon: npm
                names: [package.json]
            folders:
              - icon: folder-src
                openIcon: folder-src-open
                names: [src]
            """.trimIndent(),
        )
    }

    @Test
    fun `resolves files, folders and their open variants`() = runBlocking {
        val set = IconPackLoader.fileIconSet(fileIndex(), "ext.id", "Ext")!!
        assertEquals("ext.id/my-files", set.id)
        assertEquals("typescript", set.resolve("main.ts", isDirectory = false)!!.id)
        assertEquals("npm", set.resolve("package.json", isDirectory = false)!!.id)
        // No rule matches, so the declared default file icon does.
        assertEquals("file", set.resolve("LICENSE", isDirectory = false)!!.id)
        assertEquals("folder-src", set.resolve("src", isDirectory = true)!!.id)
        assertEquals("folder-src-open", set.resolve("src", isDirectory = true, isExpanded = true)!!.id)
        assertEquals("folder", set.resolve("docs", isDirectory = true)!!.id)
        assertEquals("folder-open", set.resolve("docs", isDirectory = true, isExpanded = true)!!.id)
    }

    @Test
    fun `per-icon settings override the set defaults`() = runBlocking {
        val set = IconPackLoader.fileIconSet(fileIndex(), "ext.id", "Ext")!!
        val ts = set.resolve("main.ts", isDirectory = false)!!
        assertEquals(20.dp, ts.designSize)
        assertEquals(0.9f, ts.scale, 0f)
        assertTrue(ts.tinted)

        val plain = set.resolve("LICENSE", isDirectory = false)!!
        assertEquals(16.dp, plain.designSize)
        assertEquals(1f, plain.scale, 0f)
        assertTrue(!plain.tinted)
    }

    @Test
    fun `an alias points at the same art under its own id`() = runBlocking {
        val set = IconPackLoader.fileIconSet(fileIndex(), "ext.id", "Ext")!!
        // `ts: typescript` adds a definition, so a rule could target either name.
        assertEquals(8, set.iconCount)
    }

    @Test
    fun `a set with no defaults leaves unmatched names to the host`() = runBlocking {
        art("files-icons/ts.svg")
        val index = write(
            "files-icons/index.yaml",
            """
            id: sparse
            icons:
              typescript: ts.svg
            files:
              - icon: typescript
                extensions: [ts]
            """.trimIndent(),
        )
        val set = IconPackLoader.fileIconSet(index, "ext.id", "Ext")!!
        assertNotNull(set.resolve("main.ts", isDirectory = false))
        assertNull(set.resolve("LICENSE", isDirectory = false))
        assertNull(set.resolve("docs", isDirectory = true))
    }

    @Test
    fun `a rule pointing at an undefined icon is dropped`() = runBlocking {
        art("files-icons/ts.svg")
        val index = write(
            "files-icons/index.yaml",
            """
            id: dangling
            icons:
              typescript: ts.svg
            files:
              - icon: nonexistent
                extensions: [ts]
            """.trimIndent(),
        )
        val set = IconPackLoader.fileIconSet(index, "ext.id", "Ext")!!
        assertNull(set.resolve("main.ts", isDirectory = false))
    }

    @Test
    fun `a set defining no icons is not offered at all`() = runBlocking {
        val index = write("files-icons/index.yaml", "id: empty\nname: Empty\n")
        assertNull(IconPackLoader.fileIconSet(index, "ext.id", "Ext"))
    }

    @Test
    fun `identity falls back to the providing extension`() = runBlocking {
        art("files-icons/file.svg")
        val index = write("files-icons/index.yaml", "icons:\n  file: file.svg\n")
        val set = IconPackLoader.fileIconSet(index, "ext.id", "Ext Name")!!
        // The conventional single-set directory is not a variant name, so neither the id nor the
        // display name picks it up.
        assertEquals("ext.id", set.id)
        assertEquals("Ext Name", set.name)
    }

    // --- several sets from one pack -----------------------------------------------------------

    @Test
    fun `a variant directory names the set when the index does not`() = runBlocking {
        art("ui-icons/outlined/run.svg")
        val index = write("ui-icons/outlined/index.yaml", "icons:\n  Run: run.svg\n")
        val set = IconPackLoader.uiIconSet(index, "ext.id", "Neon", localId = "outlined")!!
        assertEquals("ext.id/outlined", set.id)
        assertEquals("Neon — outlined", set.name)
    }

    @Test
    fun `variants of one pack are separate sets`() = runBlocking {
        art("ui-icons/outlined/run.svg", "ui-icons/filled/run.svg")
        val outlined = write("ui-icons/outlined/index.yaml", "name: Neon Outlined\nicons:\n  Run: run.svg\n")
        val filled = write("ui-icons/filled/index.yaml", "name: Neon Filled\nicons:\n  Run: run.svg\n")

        val a = IconPackLoader.uiIconSet(outlined, "ext.id", "Neon", localId = "outlined")!!
        val b = IconPackLoader.uiIconSet(filled, "ext.id", "Neon", localId = "filled")!!
        assertEquals("ext.id/outlined", a.id)
        assertEquals("ext.id/filled", b.id)
        assertEquals("Neon Outlined", a.name)
        assertEquals("Neon Filled", b.name)
    }

    @Test
    fun `two packs shipping the same variant name do not collide`() = runBlocking {
        art("ui-icons/outlined/run.svg")
        val index = write("ui-icons/outlined/index.yaml", "id: outlined\nicons:\n  Run: run.svg\n")
        val first = IconPackLoader.uiIconSet(index, "pack.one", "One", localId = "outlined")!!
        val second = IconPackLoader.uiIconSet(index, "pack.two", "Two", localId = "outlined")!!
        assertEquals("pack.one/outlined", first.id)
        assertEquals("pack.two/outlined", second.id)
    }
}

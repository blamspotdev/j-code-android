package dev.blamspot.jcode.feature.marketplace

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Where a pack's icon indexes are found. A conventionally laid-out pack declares nothing, so this
 * discovery is the only thing standing between an installed pack and Settings never offering it.
 */
class IconPackLayoutTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun pack(vararg files: String): File {
        val root = temp.newFolder()
        for (relative in files) {
            val file = File(root, relative)
            file.parentFile?.mkdirs()
            file.writeText("id: x\n")
        }
        return root
    }

    private fun resolve(dir: File, ui: List<String> = emptyList(), files: List<String> = emptyList()) =
        IconPackLayout.resolve(dir, ui, files)

    @Test
    fun `finds a single set of each kind by convention`() {
        val sets = resolve(pack("ui-icons/index.yaml", "files-icons/index.yaml"))
        assertEquals(1, sets.uiIndexes.size)
        assertEquals(1, sets.filesIndexes.size)
        assertTrue(sets.unresolved.isEmpty())
    }

    @Test
    fun `finds one set per variant directory`() {
        val sets = resolve(
            pack(
                "ui-icons/outlined/index.yaml",
                "ui-icons/filled/index.yaml",
                "files-icons/light/index.yaml",
                "files-icons/dark/index.yaml",
            ),
        )
        assertEquals(listOf("filled", "outlined"), sets.uiIndexes.map { it.parentFile.name }.sorted())
        assertEquals(listOf("dark", "light"), sets.filesIndexes.map { it.parentFile.name }.sorted())
    }

    @Test
    fun `an index directly in the conventional directory is the whole set`() {
        // `ui-icons/index.yaml` present means "one set here", so a stray subdirectory beside it is
        // art, not a second variant.
        val sets = resolve(pack("ui-icons/index.yaml", "ui-icons/glyphs/index.yaml"))
        assertEquals(1, sets.uiIndexes.size)
        assertEquals("ui-icons", sets.uiIndexes.single().parentFile.name)
    }

    @Test
    fun `variants are ordered by name, not by the filesystem`() {
        val sets = resolve(pack("ui-icons/zeta/index.yaml", "ui-icons/alpha/index.yaml", "ui-icons/mid/index.yaml"))
        assertEquals(listOf("alpha", "mid", "zeta"), sets.uiIndexes.map { it.parentFile.name })
    }

    @Test
    fun `falls back to the flat layout`() {
        val sets = resolve(pack("ui-icons.yaml", "files-icons.yml"))
        assertEquals("ui-icons.yaml", sets.uiIndexes.single().name)
        assertEquals("files-icons.yml", sets.filesIndexes.single().name)
    }

    @Test
    fun `the directory layout wins over a flat file of the same name`() {
        val sets = resolve(pack("ui-icons/index.yaml", "ui-icons.yaml"))
        assertEquals(1, sets.uiIndexes.size)
        assertEquals("index.yaml", sets.uiIndexes.single().name)
    }

    @Test
    fun `an ordinary extension contributes nothing`() {
        val sets = resolve(pack("extension.yaml", "www/index.html", "media/icon.png"))
        assertTrue(sets.isEmpty)
        assertTrue(sets.unresolved.isEmpty())
    }

    // --- declared paths -----------------------------------------------------------------------

    @Test
    fun `a declaration may name a file or a directory`() {
        val dir = pack("art/chrome/index.yaml", "art/filetypes.yaml")
        val sets = resolve(dir, ui = listOf("art/chrome"), files = listOf("art/filetypes.yaml"))
        assertEquals("index.yaml", sets.uiIndexes.single().name)
        assertEquals("filetypes.yaml", sets.filesIndexes.single().name)
    }

    @Test
    fun `a declaration may list several sets`() {
        val dir = pack("art/light.yaml", "art/dark.yaml")
        val sets = resolve(dir, files = listOf("art/light.yaml", "art/dark.yaml"))
        assertEquals(listOf("light.yaml", "dark.yaml"), sets.filesIndexes.map { it.name })
    }

    @Test
    fun `a declared directory expands to every variant inside it`() {
        val dir = pack("art/chrome/outlined/index.yaml", "art/chrome/filled/index.yaml")
        val sets = resolve(dir, ui = listOf("art/chrome"))
        assertEquals(listOf("filled", "outlined"), sets.uiIndexes.map { it.parentFile.name }.sorted())
    }

    @Test
    fun `a declaration that resolves to nothing is reported, not replaced`() {
        // The conventional index exists, but the pack said its icons are elsewhere. Silently using
        // the conventional one would hide the typo.
        val dir = pack("ui-icons/index.yaml")
        val sets = resolve(dir, ui = listOf("art/typo"))
        assertTrue(sets.uiIndexes.isEmpty())
        assertEquals(listOf("contributes.iconSets.ui: art/typo"), sets.unresolved)
    }

    // --- fallback ids -------------------------------------------------------------------------

    @Test
    fun `localId is the variant directory, or the file name for the flat layout`() {
        val dir = pack("ui-icons/outlined/index.yaml", "ui-icons.yaml", "files-icons/index.yaml")
        assertEquals("outlined", IconPackLayout.localId(File(dir, "ui-icons/outlined/index.yaml")))
        assertEquals("ui-icons", IconPackLayout.localId(File(dir, "ui-icons.yaml")))
        assertEquals("files-icons", IconPackLayout.localId(File(dir, "files-icons/index.yaml")))
    }
}

package dev.blamspot.jcode.feature.marketplace

import java.io.File
import org.apache.commons.compress.archivers.zip.ZipFile

/**
 * A `.vsix` on disk, read without ever holding it in memory.
 *
 * The packages worth importing are big: Anthropic's Claude Code extension is a 103 MB archive built
 * around a single 325 MB `linux-arm64` executable, and OpenAI's ChatGPT extension is larger still.
 * Neither the archive nor one decompressed entry of it fits in an app heap capped at a few hundred
 * megabytes, so every entry is streamed straight into its destination file and only the two small
 * manifests are ever turned into a string.
 *
 * This reads the archive by random access rather than as a [java.util.zip.ZipInputStream] because of
 * the Unix mode: a zip records file permissions in its central directory, which a streaming reader
 * never sees. Carrying that mode across is what lets an extension's bundled executable still run
 * once imported — the extension host spawns it by path inside the Linux runtime, and a file that
 * lost its exec bit on the way in is simply not runnable.
 */
internal class VsixArchive private constructor(private val zip: ZipFile) : AutoCloseable {

    /** Every file entry in the archive, by path. Directory entries are left out. */
    val names: Set<String> = buildSet {
        zip.entries.asSequence().forEach { if (!it.isDirectory) add(it.name.replace('\\', '/')) }
    }

    /** The extension's own `package.json`. Throws when the archive is not a `.vsix` at all. */
    fun packageJson(): String {
        val json = readText(VsixPackage.PACKAGE_JSON)
        if (json == null || !VsixPackage.looksLikeVsix(names)) {
            error("not a .vsix package (no ${VsixPackage.PACKAGE_JSON})")
        }
        return json
    }

    /** The `package.nls.json` string bundle, or null for an extension that ships none. */
    fun nlsJson(): String? = readText(VsixPackage.NLS_JSON)

    /**
     * Unpack the `extension/` subtree into [into], asking [skip] about each path relative to that
     * prefix. Returns the paths it made executable, so the caller can put the bit back if the
     * install's final move did not carry it.
     */
    fun extractPayload(into: File, skip: (String) -> Boolean): List<String> {
        val rootPath = into.canonicalPath + File.separator
        val executables = mutableListOf<String>()
        for (entry in zip.entries.asSequence()) {
            if (entry.isDirectory) continue
            val name = entry.name.replace('\\', '/')
            if (!name.startsWith(VsixPackage.PAYLOAD_PREFIX)) continue
            val relative = name.removePrefix(VsixPackage.PAYLOAD_PREFIX)
            if (relative.isEmpty() || skip(relative)) continue
            val outFile = File(into, relative)
            if (!outFile.canonicalPath.startsWith(rootPath)) continue // zip-slip guard
            outFile.parentFile?.mkdirs()
            zip.getInputStream(entry).use { input ->
                outFile.outputStream().use { output -> input.copyTo(output, COPY_BUFFER) }
            }
            if ((entry.unixMode and EXEC_BITS) != 0 && outFile.setExecutable(true, false)) {
                executables += relative
            }
        }
        return executables
    }

    override fun close() {
        zip.close()
    }

    companion object {
        /** `--x--x--x`: any of the three exec bits means the entry was meant to be run. */
        private const val EXEC_BITS = 0b001_001_001
        private const val COPY_BUFFER = 1 shl 16

        /** Open [file] for reading. The caller closes it; nothing is decompressed until asked for. */
        @Suppress("DEPRECATION") // ZipFile.builder() would drag commons-io onto the compile classpath.
        fun open(file: File): VsixArchive = VsixArchive(ZipFile(file))
    }

    private fun readText(name: String): String? {
        val entry = zip.getEntry(name) ?: return null
        return zip.getInputStream(entry).use { it.readBytes().toString(Charsets.UTF_8) }
    }
}

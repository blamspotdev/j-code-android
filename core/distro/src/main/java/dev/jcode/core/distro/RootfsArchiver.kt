package dev.jcode.core.distro

import android.system.Os
import java.io.File
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.GZIPOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream

/**
 * Packs an extracted rootfs directory into a `.tar.gz` stream — the reverse of
 * [RootfsManager.extractRootfs] — for the "Back up environment" action. Symlinks are stored as
 * symlink entries (never followed) and the executable bit is preserved; device nodes never exist in
 * the extracted tree so nothing is skipped. Restore reuses [RootfsManager.extractRootfs], which
 * already understands tar.gz + symlinks + hard-links + exec bits, so no separate unpacker is needed.
 */
object RootfsArchiver {
    private val MODE_EXEC = "755".toInt(8)
    private val MODE_FILE = "644".toInt(8)

    /**
     * Stream [rootfsDir] to [out] as tar.gz. [onProgress] receives (files added, uncompressed bytes
     * read) roughly every 200 files so the UI can show progress. Returns true on success.
     */
    fun pack(
        rootfsDir: File,
        out: OutputStream,
        onProgress: ((files: Long, bytes: Long) -> Unit)? = null,
    ): Boolean {
        return try {
            TarArchiveOutputStream(GZIPOutputStream(out.buffered(1 shl 16))).use { tar ->
                // Long paths / large files are common in a rootfs; use the POSIX extensions.
                tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX)
                tar.setBigNumberMode(TarArchiveOutputStream.BIGNUMBER_POSIX)
                val root = rootfsDir.toPath()
                var files = 0L
                var bytes = 0L

                fun add(f: File) {
                    val rel = root.relativize(f.toPath()).toString().replace('\\', '/')
                    if (rel.isEmpty()) return
                    when {
                        isSymlink(f) -> {
                            val e = TarArchiveEntry(rel, TarArchiveEntry.LF_SYMLINK)
                            e.linkName = runCatching { Os.readlink(f.absolutePath) }.getOrDefault("")
                            tar.putArchiveEntry(e)
                            tar.closeArchiveEntry()
                        }
                        f.isDirectory -> {
                            tar.putArchiveEntry(TarArchiveEntry(f, "$rel/"))
                            tar.closeArchiveEntry()
                            f.listFiles()?.forEach { add(it) } // symlinks handled above → never followed
                        }
                        f.isFile -> {
                            val e = TarArchiveEntry(f, rel)
                            e.size = f.length()
                            e.mode = if (f.canExecute()) MODE_EXEC else MODE_FILE
                            tar.putArchiveEntry(e)
                            f.inputStream().use { input -> bytes += input.copyTo(tar, 1 shl 16) }
                            tar.closeArchiveEntry()
                            files++
                            if (files % 200 == 0L) onProgress?.invoke(files, bytes)
                        }
                    }
                }

                rootfsDir.listFiles()?.forEach { add(it) }
                onProgress?.invoke(files, bytes)
            }
            true
        } catch (e: Exception) {
            android.util.Log.e("RootfsArchiver", "pack failed", e)
            false
        }
    }

    private fun isSymlink(f: File): Boolean =
        runCatching { Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
}

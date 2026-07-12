package dev.jcode.core.distro

import android.content.Context
import android.system.ErrnoException
import android.system.Os
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.tukaani.xz.XZInputStream

/**
 * Manages rootfs (root filesystem) download, extraction, and lifecycle.
 * 
 * Storage layout:
 *   appContext.filesDir/distros/<distro-id>/
 *     ├── rootfs/          # Extracted root filesystem
 *     └── metadata.json    # Install metadata
 */
class RootfsManager(
    private val context: Context,
    val downloader: RootfsDownloader,
) {
    private val appContext = context.applicationContext
    
    /** Base directory for all distro installations */
    val distrosDir: File
        get() = File(appContext.filesDir, "distros")
    
    /** Temporary directory for downloads */
    val tmpDir: File
        get() = File(appContext.filesDir, "tmp").also { it.mkdirs() }
    
    /**
     * Get the rootfs directory for a specific distro.
     */
    fun getRootfsPath(distroId: String): File {
        return File(distrosDir, "$distroId/rootfs")
    }
    
    /**
     * Check if a distro is installed (rootfs extracted).
     */
    fun isDistroInstalled(distroId: String): Boolean {
        val rootfs = getRootfsPath(distroId)
        // Check for essential directories
        return rootfs.exists() &&
            File(rootfs, "bin").exists() &&
            File(rootfs, "etc").exists()
    }
    
    /**
     * Get list of installed distros.
     */
    fun getInstalledDistros(): List<InstalledDistro> {
        if (!distrosDir.exists()) return emptyList()
        
        return distrosDir.listFiles()
            ?.filter { it.isDirectory && isDistroInstalled(it.name) }
            ?.map { dir ->
                InstalledDistro(
                    id = dir.name,
                    rootfsPath = File(dir, "rootfs"),
                    installDate = File(dir, "metadata.json").lastModified(),
                )
            }
            ?: emptyList()
    }
    
    /**
     * Download and extract a rootfs for the given distro profile.
     * 
     * @param profile The distro to install
     * @return Flow of installation progress events
     */
    fun installDistro(profile: DistroProfile): Flow<InstallProgress> = flow {
        val distroDir = File(distrosDir, profile.id)
        val rootfsDir = File(distroDir, "rootfs")
        
        // Check if already installed
        if (isDistroInstalled(profile.id)) {
            emit(InstallProgress.AlreadyInstalled(profile.id))
            return@flow
        }
        
        emit(InstallProgress.FetchingManifest(profile.label))
        
        // Fetch manifest and find matching entry
        val manifest = downloader.fetchManifest()
        val entry = manifest.findByDistroId(profile.id)
        
        if (entry == null) {
            emit(InstallProgress.Failed(profile.id, "No rootfs image found for ${profile.label}"))
            return@flow
        }
        
        // Download tarball preserving compression extension from URL
        val urlExt = entry.url.substringAfterLast('.').let { ext ->
            if (ext in listOf("xz", "gz", "bz2")) ".tar.$ext" else ".tar.gz"
        }
        val tarball = File(tmpDir, "${profile.id}$urlExt")
        
        emit(InstallProgress.Downloading(profile.label, 0))
        
        var downloadFailed = false
        downloader.download(entry, tarball).collect { progress ->
            when (progress) {
                is DownloadProgress.Starting -> {
                    emit(InstallProgress.Downloading(profile.label, 0))
                }
                is DownloadProgress.Downloading -> {
                    emit(InstallProgress.Downloading(profile.label, progress.percent))
                }
                is DownloadProgress.Completed -> {
                    emit(InstallProgress.DownloadComplete(profile.label, tarball))
                }
                is DownloadProgress.Failed -> {
                    downloadFailed = true
                    emit(InstallProgress.Failed(profile.id, progress.error))
                }
            }
        }
        
        if (downloadFailed) return@flow
        
        // Extract tarball
        emit(InstallProgress.Extracting(profile.label))
        
        val extractSuccess = extractRootfs(tarball, rootfsDir)
        
        // Clean up tarball
        tarball.delete()
        
        if (!extractSuccess) {
            emit(InstallProgress.Failed(profile.id, "Failed to extract rootfs"))
            return@flow
        }
        
        // Write metadata
        writeMetadata(profile)
        
        emit(InstallProgress.Installed(profile.id, rootfsDir))
    }.flowOn(Dispatchers.IO)
    
    /**
     * Remove an installed distro.
     */
    suspend fun removeDistro(distroId: String): Boolean = withContext(Dispatchers.IO) {
        val distroDir = File(distrosDir, distroId)
        if (distroDir.exists()) {
            distroDir.deleteRecursively()
        } else {
            true
        }
    }
    
    /**
     * Download a rootfs tarball synchronously (for auto-setup).
     * @return true if download succeeded
     */
    fun downloadDirect(
        entry: RootfsEntry,
        targetFile: File,
        onProgress: ((percent: Int?, detail: String) -> Unit)? = null,
    ): Boolean {
        return try {
            android.util.Log.d("RootfsManager", "downloadDirect: downloading ${entry.url} to ${targetFile.absolutePath}")
            kotlinx.coroutines.runBlocking {
                var result = false
                var lastPercent = -1
                var lastReportedBytes = 0L
                downloader.download(entry, targetFile).collect { progress ->
                    when (progress) {
                        is DownloadProgress.Downloading -> {
                            val mb = progress.bytesDownloaded / (1024f * 1024f)
                            if (progress.totalBytes > 0) {
                                val percent = progress.percent.coerceIn(0, 100)
                                if (percent != lastPercent) {
                                    lastPercent = percent
                                    val totalMb = progress.totalBytes / (1024f * 1024f)
                                    onProgress?.invoke(percent, "%.1f / %.1f MB".format(mb, totalMb))
                                }
                            } else if (progress.bytesDownloaded - lastReportedBytes >= 512 * 1024) {
                                lastReportedBytes = progress.bytesDownloaded
                                onProgress?.invoke(null, "%.1f MB downloaded".format(mb))
                            }
                        }
                        is DownloadProgress.Completed -> {
                            result = true
                            android.util.Log.d("RootfsManager", "downloadDirect: completed, size=${progress.bytesDownloaded}")
                        }
                        is DownloadProgress.Failed -> {
                            android.util.Log.e("RootfsManager", "downloadDirect: failed - ${progress.error}")
                        }
                        else -> {}
                    }
                }
                result
            }
        } catch (e: Exception) {
            android.util.Log.e("RootfsManager", "downloadDirect: exception", e)
            false
        }
    }

    /**
     * Extract a rootfs tarball to the target directory. Extraction is pure Kotlin: SELinux denies
     * hard-link creation in app data at targetSdk >= 30, and Ubuntu rootfs tarballs are full of
     * hard links, so a system `tar` pass always fails — each hard link is instead created for real
     * when possible and otherwise degraded to a *relative* symlink to its target (already extracted
     * earlier in the stream), which resolves correctly both on the host and inside proot.
     */
    fun extractRootfs(tarball: File, targetDir: File): Boolean {
        return try {
            // A previous interrupted pass may have left a partial tree; start clean.
            if (targetDir.exists()) targetDir.deleteRecursively()
            targetDir.mkdirs()
            val name = tarball.name.lowercase()
            val raw = FileInputStream(tarball).buffered(1 shl 16)
            val decompressed: InputStream = when {
                name.endsWith(".tar.xz") || name.endsWith(".txz") -> XZInputStream(raw)
                name.endsWith(".tar.bz2") || name.endsWith(".tbz2") -> BZip2CompressorInputStream(raw)
                name.endsWith(".tar.gz") || name.endsWith(".tgz") -> GZIPInputStream(raw)
                name.endsWith(".tar") -> raw
                else -> GZIPInputStream(raw)
            }
            var hardlinks = 0
            var symlinkedLinks = 0
            TarArchiveInputStream(decompressed).use { tin ->
                var entry = tin.nextTarEntry
                while (entry != null) {
                    val e = entry
                    val out = File(targetDir, e.name)
                    when {
                        e.isDirectory -> out.mkdirs()
                        e.isSymbolicLink -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            Os.symlink(e.linkName, out.absolutePath)
                        }
                        e.isLink -> { // hard link — target already extracted earlier in the stream
                            out.parentFile?.mkdirs()
                            out.delete()
                            hardlinks++
                            val target = File(targetDir, e.linkName)
                            if (!tryHardLink(target, out)) {
                                // This filesystem rejects hard links: use a relative symlink instead.
                                val rel = out.parentFile!!.toPath().relativize(target.toPath()).toString()
                                Os.symlink(rel, out.absolutePath)
                                symlinkedLinks++
                            }
                        }
                        e.isFile -> {
                            out.parentFile?.mkdirs()
                            out.delete()
                            FileOutputStream(out).use { fos -> tin.copyTo(fos, 1 shl 16) }
                            // Preserve the executable bit (any-execute); other bits don't matter under proot -0.
                            if ((e.mode and 0b1001001) != 0) out.setExecutable(true, false)
                        }
                        // char/block/fifo device nodes: skip (proot fakes /dev; app can't mknod).
                    }
                    entry = tin.nextTarEntry
                }
            }
            android.util.Log.d(
                "RootfsManager",
                "extractRootfs: OK ($hardlinks hard links, $symlinkedLinks converted to symlinks)",
            )
            true
        } catch (e: Exception) {
            android.util.Log.e("RootfsManager", "extractRootfs: failed", e)
            false
        }
    }

    private fun tryHardLink(target: File, link: File): Boolean = try {
        Os.link(target.absolutePath, link.absolutePath)
        true
    } catch (e: ErrnoException) {
        false
    }

    /**
     * Write installation metadata for a distro profile.
     */
    fun writeMetadata(profile: DistroProfile) {
        val distroDir = File(distrosDir, profile.id)
        distroDir.mkdirs()
        val metadata = File(distroDir, "metadata.json")
        metadata.writeText("""
            {
                "distroId": "${profile.id}",
                "label": "${profile.label}",
                "arch": "${profile.arch.rootfsKey}",
                "installedAt": ${System.currentTimeMillis()},
                "version": "1.1"
            }
        """.trimIndent())
    }

    /**
     * Read the recorded architecture for an installed distro, or null if unknown
     * (e.g. a legacy install written before arch metadata existed).
     */
    fun readMetadataArch(distroId: String): Arch? {
        val metadata = File(File(distrosDir, distroId), METADATA_FILE)
        if (!metadata.exists()) return null
        return runCatching {
            val match = Regex("\"arch\"\\s*:\\s*\"([^\"]+)\"").find(metadata.readText())
            Arch.fromKey(match?.groupValues?.get(1))
        }.getOrNull()
    }

    /** Metadata filename for installation info */
    companion object {
        const val METADATA_FILE = "metadata.json"
    }
}

/** Represents an installed distro. */
data class InstalledDistro(
    val id: String,
    val rootfsPath: File,
    val installDate: Long,
)

/** Progress events for distro installation. */
sealed interface InstallProgress {
    data class FetchingManifest(val distroLabel: String) : InstallProgress
    data class Downloading(val distroLabel: String, val percent: Int) : InstallProgress
    data class DownloadComplete(val distroLabel: String, val file: File) : InstallProgress
    data class Extracting(val distroLabel: String) : InstallProgress
    data class Installed(val distroId: String, val rootfsPath: File) : InstallProgress
    data class AlreadyInstalled(val distroId: String) : InstallProgress
    data class Failed(val distroId: String, val error: String) : InstallProgress
}

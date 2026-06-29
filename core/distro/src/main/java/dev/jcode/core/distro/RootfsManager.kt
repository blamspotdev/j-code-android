package dev.jcode.core.distro

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
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
    fun downloadDirect(entry: RootfsEntry, targetFile: File): Boolean {
        return try {
            android.util.Log.d("RootfsManager", "downloadDirect: downloading ${entry.url} to ${targetFile.absolutePath}")
            kotlinx.coroutines.runBlocking {
                var result = false
                downloader.download(entry, targetFile).collect { progress ->
                    when (progress) {
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
     * Extract a rootfs tarball to the target directory.
     * Uses tar command (available on Android) for extraction.
     */
    fun extractRootfs(tarball: File, targetDir: File): Boolean {
        return try {
            targetDir.mkdirs()
            
            val isXz = tarball.name.endsWith(".tar.xz") || tarball.name.endsWith(".txz")
            
            if (isXz) {
                // Try multiple methods for XZ decompression
                val tarFile = File(tarball.parentFile, tarball.name.removeSuffix(".xz"))
                android.util.Log.d("RootfsManager", "extractRootfs: decompressing xz to ${tarFile.name}")
                
                var xzOk = tryDecompressXz(tarball, tarFile)
                
                if (!xzOk || !tarFile.exists() || tarFile.length() == 0L) {
                    android.util.Log.e("RootfsManager", "extractRootfs: all xz methods failed")
                    return false
                }
                
                // Extract the tar file
                val process = ProcessBuilder(
                    "tar", "-xf", tarFile.absolutePath, "-C", targetDir.absolutePath
                ).redirectErrorStream(true).start()
                
                val exitCode = process.waitFor()
                tarFile.delete()
                exitCode == 0
            } else {
                val compressFlag = when {
                    tarball.name.endsWith(".tar.bz2") || tarball.name.endsWith(".tbz2") -> "-xjf"
                    else -> "-xzf"
                }
                
                val process = ProcessBuilder(
                    "tar", compressFlag, tarball.absolutePath, "-C", targetDir.absolutePath
                ).redirectErrorStream(true).start()
                
                process.waitFor() == 0
            }
        } catch (e: Exception) {
            android.util.Log.e("RootfsManager", "extractRootfs: exception", e)
            false
        }
    }
    
    private fun tryDecompressXz(input: File, output: File): Boolean {
        return try {
            FileInputStream(input).use { fileIn ->
                XZInputStream(fileIn).use { xzIn ->
                    FileOutputStream(output).use { out ->
                        xzIn.copyTo(out, 64 * 1024)
                    }
                }
            }
            val ok = output.exists() && output.length() > 0L
            android.util.Log.d("RootfsManager", "tryDecompressXz: kotlin xz result=$ok size=${output.length()}")
            ok
        } catch (e: Exception) {
            android.util.Log.e("RootfsManager", "tryDecompressXz: kotlin xz failed", e)
            false
        }
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

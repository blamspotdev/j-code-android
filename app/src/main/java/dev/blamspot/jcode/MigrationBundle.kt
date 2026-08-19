package dev.blamspot.jcode

import android.content.Context
import java.io.File
import org.json.JSONObject

/**
 * Everything one install holds, written somewhere the next install can read it.
 *
 * Android keys an app by its package, so renaming the applicationId does not upgrade an existing
 * install — the new package gets its own empty data directory and the old one keeps everything. That
 * is fine for the projects that still live in shared storage and fatal for everything else: the
 * Linux rootfs, the projects moved to app-private ext4, the imported extensions and the settings all
 * sit under `/data/data/<old package>/`, which no other app can read.
 *
 * So the old install writes them out itself, to the shared JCode folder both packages can reach, and
 * the new one picks them up. The parts are the app's existing backup formats — the rootfs tarball
 * [dev.blamspot.jcode.core.distro.RootfsArchiver] already produces, and the settings document
 * [SettingsBackup] already writes — so a bundle is also just a full backup, and restoring one is the
 * same code path as restoring those.
 *
 * TEMPORARY — DELETE AT 1.7.0. This exists for one rename, shipped in 1.6.1, and has no purpose once
 * users have moved off `dev.jcode`. See "Temporary code with a removal date" in AGENTS.md and the
 * note in docs/specifications/09-platform/02-build-variants-and-release.md for everything that comes
 * out with it.
 */
object MigrationBundle {

    /** Where a bundle is written. Shared storage, so it survives the old app being uninstalled. */
    fun directory(): File = File("/storage/emulated/0/JCode/migration")

    const val MANIFEST = "manifest.json"
    const val SETTINGS = "settings.json"
    const val ROOTFS = "rootfs.tar.gz"
    const val PROJECTS = "projects.tar.gz"
    const val EXTENSIONS = "extensions.tar.gz"

    /** Bundle format; refused rather than half-read if a later version changes the layout. */
    const val FORMAT = 1

    /** A bundle found on disk, and what it turned out to hold. */
    data class Found(
        val dir: File,
        /** The applicationId that wrote it, so an import can say where it came from. */
        val sourcePackage: String,
        val versionName: String,
        val createdAt: Long,
        val parts: Set<String>,
    ) {
        fun file(name: String): File? = File(dir, name).takeIf { it.isFile && name in parts }

        /** Total size on disk, for telling the user what the import is about to move. */
        val bytes: Long get() = parts.sumOf { File(dir, it).length() }
    }

    /** The bundle waiting to be imported, or null when there is none this app did not write itself. */
    fun find(context: Context): Found? {
        val dir = directory()
        val manifest = File(dir, MANIFEST).takeIf { it.isFile } ?: return null
        val json = runCatching { JSONObject(manifest.readText()) }.getOrNull() ?: return null
        if (json.optInt("format") != FORMAT) return null
        val source = json.optString("package")
        // A bundle this install wrote is its own backup, not something to import over itself.
        if (source.isBlank() || source == context.packageName) return null
        val parts = json.optJSONArray("parts") ?: return null
        return Found(
            dir = dir,
            sourcePackage = source,
            versionName = json.optString("version"),
            createdAt = json.optLong("createdAt"),
            parts = (0 until parts.length()).mapNotNull { parts.optString(it).takeIf { p -> p.isNotBlank() } }.toSet(),
        )
    }

    /**
     * Write the manifest last, and only naming the parts that actually landed.
     *
     * A bundle is found by its manifest, so writing it at the end is what makes a half-finished
     * export invisible rather than importable — an interrupted 2.5 GB rootfs must not present itself
     * as a restore.
     */
    fun writeManifest(context: Context, dir: File, parts: List<String>, versionName: String) {
        File(dir, MANIFEST).writeText(
            JSONObject()
                .put("format", FORMAT)
                .put("package", context.packageName)
                .put("version", versionName)
                .put("createdAt", System.currentTimeMillis())
                .put("parts", org.json.JSONArray(parts))
                .toString(2),
        )
    }
}

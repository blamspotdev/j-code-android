package dev.jcode.vdevice

import android.content.Context
import android.os.Environment
import dev.jcode.core.distro.WorkspaceHostPaths
import java.io.File

/**
 * The virtual device's two storage volumes.
 *
 * A device with no filesystem is a device most apps cannot finish a sentence on. An app opens a
 * document, saves an export, unpacks its assets, writes a log; before this the container had nowhere
 * for any of that to go, so those calls either failed or — worse — landed in the **phone's** shared
 * storage, among the user's own files, under JCode's `MANAGE_EXTERNAL_STORAGE`. Measured on WaveRepo:
 * asking for a SoundFont opened the phone's own document picker over the IDE, listing the user's
 * downloads and screenshots to an app that is supposed to be sandboxed.
 *
 * There are two volumes because the device needs storage for two different lifetimes, and one volume
 * cannot have both.
 *
 * | | [Internal] | [External] |
 * |---|---|---|
 * | Device path | `/sdcard` | `/storage/external` |
 * | Lives in | `filesDir/vdevice/storage` | the workspace, as `vDevice_ExtStorage` |
 * | Survives a JCode restart | **no** | yes |
 * | Visible in the IDE | no | yes, beside your projects |
 *
 * **Internal is the clean room.** It is emptied on every JCode start along with the installed apps,
 * for the reason they are: a file that outlived the app that wrote it would be waiting to be found
 * by whatever was installed under that package name next. [seed] puts the empty media directories
 * back afterwards, so the device starts as a formatted phone does rather than as a bare directory.
 *
 * **External is the way out.** A photo the device took, a file an app exported, a log it wrote — on
 * a clean-room device those are gone the next time JCode starts, which is right for a sandbox and
 * useless for the thing a sandbox is for. This volume is an ordinary folder in the workspace, so
 * what an app writes there is still there tomorrow, is visible in the project explorer, is editable
 * in the IDE, and is reachable from the Linux environment at `/workspace/vDevice_ExtStorage`. It is
 * the seam between the device and the work.
 *
 * Both are presented to apps the way a phone presents two volumes: `getExternalFilesDirs` and its
 * siblings answer with **two** entries, internal first, which is exactly the shape an app written
 * for a phone with an SD card already handles.
 *
 * ### Known gap
 *
 * `Environment.getExternalStorageDirectory()` still answers the **phone's** path. It is computed
 * fresh inside `Environment.UserEnvironment.getExternalDirs()` on every call, out of
 * `StorageManager.getVolumeList`, so there is no cached field to redirect and no method to override
 * without standing in front of the storage service for the whole process. Everything reached through
 * a `Context` — which is what an app targeting API 30 or later has to use — is redirected here; an
 * app that reaches for the static instead sees the phone.
 */
internal object VirtualStorage {

    /**
     * A volume, by the name the device calls it.
     *
     * `/storage/external` rather than a phone's `/storage/XXXX-XXXX`: the path is arbitrary — what
     * an app actually uses is whatever `getExternalFilesDirs` hands back — and a path somebody has
     * to type into `adb pull` should say what it is.
     */
    enum class Volume(val deviceRoot: String, val label: String) {
        Internal("/sdcard", "Internal storage"),
        External("/storage/external", "External storage"),
    }

    /** Where the device says its internal storage is — the spelling everything already uses. */
    const val DEVICE_ROOT = "/sdcard"

    /** The other spelling a phone answers to for its internal volume. */
    private const val EMULATED_ROOT = "/storage/emulated/0"

    private const val ROOT = "vdevice/storage"
    private const val ANDROID = "Android"

    /** The workspace folder [Volume.External] is, named so it is obvious what put it there. */
    const val EXTERNAL_FOLDER = "vDevice_ExtStorage"

    /**
     * What a freshly formatted phone has. Seeded empty rather than left to be created on demand, so
     * `adb push … /sdcard/Download/` works on a device nothing has run on yet and `ls` shows a
     * device rather than a void.
     */
    private val MEDIA_DIRECTORIES = listOf(
        Environment.DIRECTORY_DOWNLOADS,
        Environment.DIRECTORY_DOCUMENTS,
        Environment.DIRECTORY_MUSIC,
        Environment.DIRECTORY_PICTURES,
        Environment.DIRECTORY_MOVIES,
        Environment.DIRECTORY_DCIM,
    )

    fun root(context: Context): File = root(context, Volume.Internal)

    /**
     * Where a volume's bytes are.
     *
     * External is under [WorkspaceHostPaths.projectsRoot], which is the same directory the Linux
     * environment sees as `/workspace` and the explorer lists projects from — so the folder appears
     * where the work is rather than somewhere a person has to be told about.
     */
    fun root(context: Context, volume: Volume): File = when (volume) {
        Volume.Internal -> File(context.applicationContext.filesDir, ROOT).ensure()
        Volume.External -> File(WorkspaceHostPaths.projectsRoot, EXTERNAL_FOLDER).ensure()
    }

    /**
     * Puts the standard media directories back on a device that has just been emptied.
     *
     * Internal only. External is a folder in somebody's workspace, and a sandbox that scatters six
     * empty directories through it every time JCode starts is a sandbox making a mess of the work.
     */
    fun seed(context: Context) {
        val root = root(context, Volume.Internal)
        MEDIA_DIRECTORIES.forEach { File(root, it).ensure() }
        File(root, "$ANDROID/data").ensure()
        File(root, "$ANDROID/media").ensure()
        File(root, "$ANDROID/obb").ensure()
    }

    /** `getExternalFilesDir(type)`: `Android/data/<pkg>/files`, plus [type] when one is asked for. */
    fun externalFilesDir(context: Context, packageName: String, type: String?): File =
        externalFilesDir(context, packageName, type, Volume.Internal)

    fun externalFilesDir(context: Context, packageName: String, type: String?, volume: Volume): File {
        val files = File(appDir(context, packageName, volume), "files")
        return if (type.isNullOrEmpty()) files.ensure() else File(files, type).ensure()
    }

    fun externalCacheDir(context: Context, packageName: String, volume: Volume = Volume.Internal): File =
        File(appDir(context, packageName, volume), "cache").ensure()

    fun externalMediaDir(context: Context, packageName: String, volume: Volume = Volume.Internal): File =
        File(root(context, volume), "$ANDROID/media/$packageName").ensure()

    fun obbDir(context: Context, packageName: String, volume: Volume = Volume.Internal): File =
        File(root(context, volume), "$ANDROID/obb/$packageName").ensure()

    /**
     * The host file a path *on the device* names, or null when it points outside both volumes.
     *
     * Everything reachable over adb comes through here, so this is the one place that has to be
     * unfoolable: the resolved path is compared against the volume's root as a **canonical** path,
     * which is what makes `../` — and a symlink planted by a guest, which `..` alone would not
     * catch — land outside and be refused rather than reaching JCode's own data directory. That
     * matters more for the external volume than the internal one, because outside *it* is the user's
     * whole workspace.
     */
    fun resolve(context: Context, path: String): File? {
        val volume = volumeOf(path)
        val root = root(context, volume)
        val relative = path
            .removePrefix(EMULATED_ROOT)
            .let { if (it == path) it.removePrefix(volume.deviceRoot) else it }
            .trimStart('/')
        val target = if (relative.isEmpty()) root else File(root, relative)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it == base || it.path.startsWith(base.path + File.separator) }
    }

    /** Which volume a device path names. Internal is the default, as it is the one `/sdcard` means. */
    fun volumeOf(path: String): Volume =
        if (path == Volume.External.deviceRoot || path.startsWith(Volume.External.deviceRoot + "/")) {
            Volume.External
        } else {
            Volume.Internal
        }

    /**
     * The reverse: what the device calls a host file, for anything a driver reads back.
     *
     * Canonical on both sides, and that is not tidiness. [resolve] hands back a canonical file, and
     * `/data/user/0/<pkg>` is a **symlink** to `/data/data/<pkg>` — so comparing the two as written
     * never matched, and `screencap /sdcard/shot.png` answered "written to
     * /data/data/dev.jcode/files/vdevice/storage/shot.png", printing JCode's own data directory to
     * whoever was driving the device.
     */
    fun devicePath(context: Context, file: File): String {
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        Volume.entries.forEach { volume ->
            val base = runCatching { root(context, volume).canonicalPath }.getOrNull() ?: return@forEach
            if (path == base) return volume.deviceRoot
            if (path.startsWith(base + File.separator)) {
                return volume.deviceRoot + path.removePrefix(base).replace(File.separatorChar, '/')
            }
        }
        return path
    }

    /**
     * Everything one app has put in shared storage — its private tree under `Android/`, on both
     * volumes.
     *
     * The **shared** part of the external volume is deliberately left alone. It is a folder in
     * somebody's workspace; uninstalling an app from the device is not a licence to delete the files
     * a person has been working with, and a photo taken last week is not the app's to take away.
     */
    fun forget(context: Context, packageName: String) {
        Volume.entries.forEach { volume ->
            appDir(context, packageName, volume).deleteRecursively()
            externalMediaDir(context, packageName, volume).deleteRecursively()
            obbDir(context, packageName, volume).deleteRecursively()
        }
    }

    private fun appDir(context: Context, packageName: String, volume: Volume): File =
        File(root(context, volume), "$ANDROID/data/$packageName").ensure()

    private fun File.ensure(): File = also { if (!it.isDirectory) it.mkdirs() }
}

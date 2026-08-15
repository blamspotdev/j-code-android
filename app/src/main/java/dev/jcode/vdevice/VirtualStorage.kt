package dev.jcode.vdevice

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * The virtual device's internal storage — what `/sdcard` is on a phone.
 *
 * A device with no filesystem is a device most apps cannot finish a sentence on. An app opens a
 * document, saves an export, unpacks its assets, writes a log; before this the container had nowhere
 * for any of that to go, so those calls either failed or — worse — landed in the **phone's** shared
 * storage, among the user's own files, under JCode's `MANAGE_EXTERNAL_STORAGE`. Measured on WaveRepo:
 * asking for a SoundFont opened the phone's own document picker over the IDE, listing the user's
 * downloads and screenshots to an app that is supposed to be sandboxed.
 *
 * So the device has storage of its own, at `filesDir/vdevice/storage`, presented to whoever is
 * driving the device as [DEVICE_ROOT]:
 *
 * ```
 * /sdcard/Download            the standard media directories, seeded on every start
 * /sdcard/Documents           …
 * /sdcard/Android/data/<pkg>/files    what getExternalFilesDir() answers
 * /sdcard/Android/data/<pkg>/cache    getExternalCacheDir()
 * /sdcard/Android/media/<pkg>         getExternalMediaDirs()
 * /sdcard/Android/obb/<pkg>           getObbDir()
 * ```
 *
 * **It is emptied on every JCode start**, because it lives under `filesDir/vdevice/` and
 * [VirtualDeviceApps.resetOnStart] wipes that whole tree — the same clean-room rule the installed
 * apps follow, and for the same reason: a file that outlived the app that wrote it would be waiting
 * to be found by whatever was installed under that package name next. [seed] puts the empty
 * media directories back afterwards, so the device starts as a formatted phone does rather than as
 * a bare directory.
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

    /** Where the device says its storage is. Both spellings a phone answers to resolve here. */
    const val DEVICE_ROOT = "/sdcard"
    private const val EMULATED_ROOT = "/storage/emulated/0"

    private const val ROOT = "vdevice/storage"
    private const val ANDROID = "Android"

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

    fun root(context: Context): File =
        File(context.applicationContext.filesDir, ROOT).ensure()

    /** Puts the standard media directories back on a device that has just been emptied. */
    fun seed(context: Context) {
        val root = root(context)
        MEDIA_DIRECTORIES.forEach { File(root, it).ensure() }
        File(root, "$ANDROID/data").ensure()
        File(root, "$ANDROID/media").ensure()
        File(root, "$ANDROID/obb").ensure()
    }

    /** `getExternalFilesDir(type)`: `Android/data/<pkg>/files`, plus [type] when one is asked for. */
    fun externalFilesDir(context: Context, packageName: String, type: String?): File {
        val files = File(appDir(context, packageName), "files")
        return if (type.isNullOrEmpty()) files.ensure() else File(files, type).ensure()
    }

    fun externalCacheDir(context: Context, packageName: String): File =
        File(appDir(context, packageName), "cache").ensure()

    fun externalMediaDir(context: Context, packageName: String): File =
        File(root(context), "$ANDROID/media/$packageName").ensure()

    fun obbDir(context: Context, packageName: String): File =
        File(root(context), "$ANDROID/obb/$packageName").ensure()

    /**
     * The host file a path *on the device* names, or null when it points outside the device.
     *
     * Everything reachable over adb comes through here, so this is the one place that has to be
     * unfoolable: the resolved path is compared against the root as a **canonical** path, which is
     * what makes `../` — and a symlink planted by a guest, which `..` alone would not catch — land
     * outside and be refused rather than reaching JCode's own data directory.
     */
    fun resolve(context: Context, path: String): File? {
        val root = root(context)
        val relative = path
            .removePrefix(EMULATED_ROOT)
            .let { if (it == path) it.removePrefix(DEVICE_ROOT) else it }
            .trimStart('/')
        val target = if (relative.isEmpty()) root else File(root, relative)
        val canonical = runCatching { target.canonicalFile }.getOrNull() ?: return null
        val base = runCatching { root.canonicalFile }.getOrNull() ?: return null
        return canonical.takeIf { it == base || it.path.startsWith(base.path + File.separator) }
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
        val base = runCatching { root(context).canonicalPath }.getOrNull() ?: return file.absolutePath
        val path = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        if (!path.startsWith(base)) return path
        return DEVICE_ROOT + path.removePrefix(base).replace(File.separatorChar, '/')
    }

    /** Everything one app has put in shared storage — its private tree under `Android/`. */
    fun forget(context: Context, packageName: String) {
        appDir(context, packageName).deleteRecursively()
        externalMediaDir(context, packageName).deleteRecursively()
        obbDir(context, packageName).deleteRecursively()
    }

    private fun appDir(context: Context, packageName: String): File =
        File(root(context), "$ANDROID/data/$packageName").ensure()

    private fun File.ensure(): File = also { if (!it.isDirectory) it.mkdirs() }
}

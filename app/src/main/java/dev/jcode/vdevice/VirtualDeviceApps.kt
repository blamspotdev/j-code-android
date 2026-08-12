package dev.jcode.vdevice

import android.content.Context
import android.util.Log
import androidx.compose.runtime.mutableIntStateOf
import java.io.File

/**
 * What is installed on J Code's virtual device — the one place the launcher, `adb` and the start-up
 * reset all read and write it.
 *
 * "Installed" means staged under `filesDir/vdevice/apps/<package>.apk`, with the app's private
 * storage beside it at `filesDir/vdevice/<package>/` (which is what [GuestLoader] hands a running
 * guest as its data directory). There is no system package database involved: the real `pm` has
 * never heard of any of these, which is the whole point — an app can be put on this device and taken
 * off again without touching the phone.
 *
 * **Nothing here survives a restart.** [resetOnStart] wipes the whole tree the first time J Code's
 * process asks for it, so every session begins with an empty device: no apps, no data, no
 * preferences a previous run left behind. That is what makes the device a clean room rather than a
 * second phone slowly filling up inside the IDE.
 */
internal object VirtualDeviceApps {

    private const val ROOT = "vdevice"
    private const val APPS = "apps"
    private const val APK = ".apk"

    /**
     * Bumped whenever the installed set changes, so the launcher redraws for an `adb install` it did
     * not initiate. Snapshot state rather than a flow: the only reader is a composable.
     */
    val revision = mutableIntStateOf(0)

    private var reset = false

    /**
     * Empties the device, once per process.
     *
     * Called from the workbench on start and again from the adb daemon before it accepts a
     * connection, because those two race and the loser must not wipe what the winner just installed.
     * `@Synchronized` plus the [reset] flag is what makes the second call a no-op rather than a
     * second wipe.
     */
    @Synchronized
    fun resetOnStart(context: Context) {
        if (reset) return
        reset = true
        val root = File(context.applicationContext.filesDir, ROOT)
        val removed = root.listFiles().orEmpty().count { it.deleteRecursively() }
        if (removed > 0) Log.i(TAG, "virtual device reset: $removed entries cleared from $root")
        revision.intValue++
    }

    /** Every app staged on the device, by label. Unreadable APKs are skipped, not reported. */
    fun list(context: Context): List<VirtualDeviceApp> = apksDir(context).listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(APK) }
        .mapNotNull { VirtualDevice.inspect(context, it.absolutePath).getOrNull() }
        .sortedBy { it.label.lowercase() }

    /**
     * Just the installed package names — what `pm list packages` needs. Read off the file names
     * rather than through [list], which parses every APK to answer questions this does not ask.
     */
    fun packages(context: Context): List<String> = apksDir(context).listFiles().orEmpty()
        .filter { it.isFile && it.name.endsWith(APK) }
        .map { it.name.removeSuffix(APK) }
        .sorted()

    /** The staged APK for [packageName], or null when it is not installed. */
    fun apk(context: Context, packageName: String): File? =
        File(apksDir(context), packageName + APK).takeIf { it.isFile }

    /**
     * Takes over [staged] — an APK already written into the apps directory — as the install of
     * whatever package it turns out to declare. The file is consumed either way: a rename on
     * success, a delete on failure.
     */
    fun install(context: Context, staged: File): Result<VirtualDeviceApp> = try {
        VirtualDevice.inspect(context, staged.absolutePath).mapCatching { app ->
            val target = File(apksDir(context), app.packageName + APK)
            target.delete()
            if (!staged.renameTo(target)) {
                throw VirtualDeviceException("cannot store ${app.packageName}")
            }
            revision.intValue++
            Log.i(TAG, "installed ${app.packageName} ${app.versionName} (${target.length()} bytes)")
            app.copy(apkPath = target.absolutePath)
        }
    } finally {
        staged.delete()
    }

    /**
     * Where [packageName]'s split APKs live, beside its base: `apps/<package>.splits/`.
     *
     * Splits sit in a directory *next to* the base rather than replacing it with one, so every
     * reader that already knew where a package's APK is — [list], [packages], [apk], the launcher,
     * `pm path` — keeps working unchanged, and [GuestLoader.splitsOf] finds the rest by the same
     * convention without anything having to be passed across the binder.
     */
    fun splitsDir(context: Context, packageName: String): File =
        File(apksDir(context), packageName + SPLITS_SUFFIX)

    /**
     * Takes over a whole install session: a base APK plus the config splits that belong with it,
     * which is what an app bundle actually is by the time `adb install-multiple` streams it over.
     *
     * The base is whichever staged file parses as a package on its own. A config split carries a
     * manifest with no `<application>` in it, so it is exactly the file [VirtualDevice.inspect]
     * refuses — which makes "the one that inspects" a sound test rather than a guess about names.
     * Every staged file is consumed either way.
     */
    fun installSession(context: Context, staged: List<File>): Result<VirtualDeviceApp> = try {
        runCatching {
            val base = staged.firstNotNullOfOrNull { file ->
                VirtualDevice.inspect(context, file.absolutePath).getOrNull()?.let { file to it }
            } ?: throw VirtualDeviceException("no base APK among ${staged.size} staged file(s)")
            val (baseFile, app) = base

            val target = File(apksDir(context), app.packageName + APK)
            val splitsTarget = splitsDir(context, app.packageName)
            target.delete()
            splitsTarget.deleteRecursively()
            if (!baseFile.renameTo(target)) throw VirtualDeviceException("cannot store ${app.packageName}")

            val splits = staged.filter { it != baseFile }
            if (splits.isNotEmpty()) {
                splitsTarget.mkdirs()
                splits.forEach { split ->
                    val name = if (split.name.endsWith(APK)) split.name else split.name + APK
                    split.renameTo(File(splitsTarget, name))
                }
            }
            revision.intValue++
            Log.i(TAG, "installed ${app.packageName} ${app.versionName} with ${splits.size} split(s)")
            app.copy(apkPath = target.absolutePath)
        }
    } finally {
        staged.forEach { it.delete() }
    }

    /** Installs a copy of [apk] — the launcher's "Install", and any APK a build just produced. */
    fun installCopy(context: Context, apk: File): Result<VirtualDeviceApp> = runCatching {
        if (!apk.canRead()) throw VirtualDeviceException("Cannot read APK: ${apk.absolutePath}")
        val staged = staging(context)
        apk.inputStream().use { input -> staged.outputStream().use { input.copyTo(it) } }
        staged
    }.mapCatching { staged -> install(context, staged).getOrThrow() }

    /** A scratch file in the apps directory for a stream that has not been identified yet. */
    fun staging(context: Context): File =
        File(apksDir(context), "staged-${System.nanoTime()}$APK")

    /** Removes the app and everything it stored — its base, its splits, and its data. */
    fun uninstall(context: Context, packageName: String): Boolean {
        val apk = apk(context, packageName) ?: return false
        val removed = apk.delete()
        splitsDir(context, packageName).deleteRecursively()
        dataDir(context, packageName).deleteRecursively()
        if (removed) revision.intValue++
        return removed
    }

    /** Wipes one app's private storage, leaving it installed — `pm clear`. */
    fun clearData(context: Context, packageName: String): Boolean {
        if (apk(context, packageName) == null) return false
        val data = dataDir(context, packageName)
        // The dex cache is derived from the APK, so dropping it costs one re-optimisation and keeps
        // "cleared" meaning cleared rather than "cleared except the bits we were unsure about".
        data.deleteRecursively()
        data.mkdirs()
        return true
    }

    fun apksDir(context: Context): File =
        File(context.applicationContext.filesDir, "$ROOT/$APPS").apply { mkdirs() }

    private fun dataDir(context: Context, packageName: String): File =
        File(context.applicationContext.filesDir, "$ROOT/$packageName")
}

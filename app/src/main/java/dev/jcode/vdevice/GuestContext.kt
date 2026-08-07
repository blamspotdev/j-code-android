package dev.jcode.vdevice

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.util.Log
import android.view.Display
import android.view.LayoutInflater
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * The [Context] the guest sees.
 *
 * Wraps J Code's real `ContextImpl` — so every binder call still goes out under J Code's uid and
 * package, which is what makes them succeed — but reports the guest's identity for everything the
 * guest can observe about itself: package name, `ApplicationInfo`, resources, class loader, and a
 * private storage tree redirected under `<J Code filesDir>/vdevice/<guest package>/`.
 *
 * The redirect is what keeps a guest from ever seeing (or writing into) J Code's own data directory.
 */
internal class GuestContext(base: Context, private val guest: LoadedGuest) : ContextWrapper(base) {

    private var inflater: LayoutInflater? = null
    private var theme: Resources.Theme? = null
    private var themeResource = 0

    override fun getPackageName(): String = guest.packageName
    override fun getApplicationInfo(): ApplicationInfo = guest.applicationInfo
    override fun getResources(): Resources = guest.resources
    override fun getAssets(): AssetManager = guest.resources.assets
    override fun getClassLoader(): ClassLoader = guest.classLoader
    override fun getPackageCodePath(): String = guest.apkPath
    override fun getPackageResourcePath(): String = guest.apkPath
    override fun getApplicationContext(): Context = guest.application ?: guest.appContext

    override fun getTheme(): Resources.Theme {
        theme?.let { return it }
        if (themeResource == 0) themeResource = guest.applicationInfo.theme
        return guest.resources.newTheme().also {
            if (themeResource != 0) it.applyStyle(themeResource, true)
            theme = it
        }
    }

    override fun setTheme(resid: Int) {
        if (themeResource == resid && theme != null) return
        themeResource = resid
        theme = null
    }

    /**
     * A [LayoutInflater] from the base context would resolve layouts and custom views against J
     * Code's resources and class loader, so hand out one cloned into this context instead.
     */
    override fun getSystemService(name: String): Any? {
        if (name != LAYOUT_INFLATER_SERVICE) return super.getSystemService(name)
        return inflater ?: LayoutInflater.from(baseContext).cloneInContext(this).also { inflater = it }
    }

    override fun getDataDir(): File = guest.dataDir.ensure()
    override fun getFilesDir(): File = guest.filesDir.ensure()
    override fun getCacheDir(): File = guest.cacheDir.ensure()
    override fun getCodeCacheDir(): File = guest.codeCacheDir.ensure()
    override fun getNoBackupFilesDir(): File = guest.noBackupDir.ensure()
    override fun getDir(name: String, mode: Int): File = File(guest.dataDir, "app_$name").ensure()

    override fun getFileStreamPath(name: String): File = File(getFilesDir(), name)
    override fun fileList(): Array<String> = guest.filesDir.list() ?: emptyArray()
    override fun deleteFile(name: String): Boolean = getFileStreamPath(name).delete()
    override fun openFileInput(name: String): FileInputStream = FileInputStream(getFileStreamPath(name))

    override fun openFileOutput(name: String, mode: Int): FileOutputStream =
        FileOutputStream(getFileStreamPath(name), mode and MODE_APPEND != 0)

    override fun getDatabasePath(name: String): File = File(guest.databasesDir.ensure(), name)
    override fun databaseList(): Array<String> = guest.databasesDir.list() ?: emptyArray()
    override fun deleteDatabase(name: String): Boolean = SQLiteDatabase.deleteDatabase(getDatabasePath(name))

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
    ): SQLiteDatabase = openOrCreateDatabase(name, mode, factory, null)

    override fun openOrCreateDatabase(
        name: String,
        mode: Int,
        factory: SQLiteDatabase.CursorFactory?,
        errorHandler: DatabaseErrorHandler?,
    ): SQLiteDatabase =
        SQLiteDatabase.openOrCreateDatabase(getDatabasePath(name).path, factory, errorHandler)

    /**
     * `Context.getSharedPreferences(File, int)` is the hidden overload every implementation funnels
     * into; calling it on the base context is what lets the guest's preferences land in its own
     * `shared_prefs/` instead of J Code's. Without it the guest's files would sit next to the IDE's.
     */
    override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
        val byFile = HiddenApi.method(
            Context::class.java,
            "getSharedPreferences",
            File::class.java,
            Int::class.javaPrimitiveType!!,
        )
        val file = File(guest.sharedPrefsDir.ensure(), "$name.xml")
        return byFile?.let { runCatching { it.invoke(baseContext, file, mode) as SharedPreferences }.getOrNull() }
            ?: super.getSharedPreferences(name, mode).also {
                Log.w(TAG, "shared prefs '$name' not redirected; falling back to the host directory")
            }
    }

    override fun deleteSharedPreferences(name: String): Boolean =
        File(guest.sharedPrefsDir, "$name.xml").delete()

    override fun createConfigurationContext(overrideConfiguration: Configuration): Context =
        GuestContext(super.createConfigurationContext(overrideConfiguration), guest)

    override fun createDisplayContext(display: Display): Context =
        GuestContext(super.createDisplayContext(display), guest)

    override fun createDeviceProtectedStorageContext(): Context =
        GuestContext(super.createDeviceProtectedStorageContext(), guest)

    override fun createPackageContext(packageName: String, flags: Int): Context =
        if (packageName == guest.packageName) this else super.createPackageContext(packageName, flags)

    private fun File.ensure(): File = also { if (!it.isDirectory) it.mkdirs() }
}

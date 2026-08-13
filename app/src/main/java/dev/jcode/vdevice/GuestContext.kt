package dev.jcode.vdevice

import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.content.res.Configuration
import android.content.res.Resources
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.hardware.SensorManager
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

    /**
     * The guest's theme, never an empty one: an app that declares no `android:theme` is asking for
     * the platform default for its `targetSdkVersion`, not for a theme with no styles in it — see
     * [selectDefaultTheme].
     */
    override fun getTheme(): Resources.Theme {
        theme?.let { return it }
        if (themeResource == 0) themeResource = guest.applicationTheme
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
     * Two services are the device's rather than the phone's.
     *
     * A [LayoutInflater] from the base context would resolve layouts and custom views against J
     * Code's resources and class loader, so the guest is handed one cloned into this context
     * instead. And the sensors it is offered are the ones the user has given *this app* — see
     * [GuestSensorManager], which is the only thing standing between a guest APK and the phone's
     * real accelerometer.
     *
     * The base context is what looks the policy up, not this one: `getApplicationContext` here
     * answers with the guest's, whose `filesDir` is the redirected tree, and the device's policy
     * lives in J Code's.
     *
     * Location is *not* here. It is replaced a layer lower, at the binder the framework builds every
     * `LocationManager` around, because the manager itself admits to no field that could be patched
     * — see [GuestLocation].
     */
    override fun getSystemService(name: String): Any? = when (name) {
        LAYOUT_INFLATER_SERVICE ->
            inflater ?: LayoutInflater.from(baseContext).cloneInContext(this).also { inflater = it }

        SENSOR_SERVICE -> (super.getSystemService(name) as? SensorManager)
            ?.let { GuestSensors.forGuest(baseContext, guest, it) }

        else -> super.getSystemService(name)
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

    /**
     * `ContextImpl.getDatabasePath` accepts an **absolute** name and returns it as-is, and libraries
     * rely on it: WorkManager hands Room a full path under `no_backup/`, and Room passes that
     * straight back through here. Joining it onto `databases/` produced
     * `…/databases/data/user/0/…/no_backup/androidx.work.workdb`, whose parent does not exist, and
     * the `SQLiteCantOpenDatabaseException` came back on a WorkManager thread where nothing catches
     * it — killing `:guest` and, with it, the activity J Code was showing.
     */
    override fun getDatabasePath(name: String): File =
        if (name.startsWith(File.separatorChar)) {
            File(name).also { it.parentFile?.mkdirs() }
        } else {
            File(guest.databasesDir.ensure(), name)
        }
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

    // ------------------------------------------------- the guest's own components
    //
    // A guest's services and receivers belong to a package the real PackageManager has never heard
    // of, so letting these calls through unchanged ends in the activity manager refusing a component
    // that does not exist. Each one is offered to [GuestComponents] first and only falls through to
    // the host when the target is not the guest's — which is what keeps a guest able to fire an
    // intent at the phone (a share sheet, a browser) while talking to itself in-process.

    override fun startService(service: Intent): ComponentName? =
        guest.components.startService(this, service) ?: super.startService(service)

    override fun startForegroundService(service: Intent): ComponentName? =
        guest.components.startService(this, service) ?: super.startForegroundService(service)

    override fun stopService(name: Intent): Boolean =
        if (guest.components.stopService(name)) true else super.stopService(name)

    override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean =
        if (guest.components.bindService(this, service, conn)) true else super.bindService(service, conn, flags)

    override fun unbindService(conn: ServiceConnection) {
        if (!guest.components.unbindService(conn)) super.unbindService(conn)
    }

    /**
     * A broadcast is offered to the guest's own manifest receivers and *still* sent on, because the
     * two audiences do not overlap: a hosted receiver is invisible to the system, and a system
     * receiver is invisible to [GuestComponents]. Only an explicit intent naming the guest is kept
     * in-process, since the system would reject that one anyway.
     */
    override fun sendBroadcast(intent: Intent) {
        val handled = guest.components.sendBroadcast(this, intent)
        if (handled == 0 || intent.component == null) super.sendBroadcast(intent)
    }

    override fun sendBroadcast(intent: Intent, receiverPermission: String?) {
        val handled = guest.components.sendBroadcast(this, intent)
        if (handled == 0 || intent.component == null) super.sendBroadcast(intent, receiverPermission)
    }

    private fun File.ensure(): File = also { if (!it.isDirectory) it.mkdirs() }
}

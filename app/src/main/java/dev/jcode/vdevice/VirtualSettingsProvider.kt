package dev.jcode.vdevice

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log

/**
 * How the device's Settings app reads and changes the device's settings.
 *
 * Every other screen that governs this device is JCode's — the hardware bench, Manage permissions —
 * and they reach [VirtualDevicePolicy] by calling it. The Settings app cannot: it is an ordinary
 * guest in its own class loader, and the whole point of it being an ordinary guest is that it has no
 * privileges to call anything with. It needs the same thing a real Settings app needs, which is a
 * provider, and this is it.
 *
 * `call()` rather than a table of rows, because none of this is a table. What the app wants is "tell
 * me everything you would show on this screen" and "change this one thing", and a cursor for either
 * would be a shape imposed on the data by the transport.
 *
 * | Method | Answers |
 * |---|---|
 * | `device` | Every hardware entry with its modes and current setting, both storage volumes, and what the device is |
 * | `apps` | What is installed, with labels |
 * | `app` | One app's declared permissions and the rule on each |
 * | `set` | Changes one hardware mode or one permission rule |
 *
 * ### What this deliberately does not claim
 *
 * **It cannot tell one guest from another.** Every guest runs in `:guest` under JCode's own uid, so
 * `getCallingPackage()` is JCode for all of them and a provider that tried to admit only the
 * device's Settings app would be checking a claim the caller makes about itself. So it does not
 * pretend to: any app on the device can read these settings and change them, exactly as any app
 * could reach [VirtualStorageProvider]. That is the same trade the whole container makes — *a
 * sandboxed preview, not a security boundary* — and the honest version of it is a comment rather
 * than a check that looks like one. What it does do is **write down who asked**, so a setting that
 * changed on its own has a name against it in the device's log.
 */
class VirtualSettingsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val context = context?.applicationContext ?: return null
        return runCatching {
            when (method) {
                METHOD_DEVICE -> device(context)
                METHOD_APPS -> apps(context)
                METHOD_APP -> app(context, arg ?: return null)
                METHOD_SET -> set(context, arg ?: return null, extras)
                else -> null
            }
        }.onFailure { Log.w(TAG, "cannot answer $method", it) }.getOrNull()
    }

    /** Everything the Settings app's own screens are built from, in one round trip. */
    private fun device(context: Context): Bundle = Bundle().apply {
        val ids = ArrayList<String>()
        VirtualHardware.entries.forEach { hardware ->
            ids += hardware.id
            putString("hw/${hardware.id}/label", hardware.label)
            putString("hw/${hardware.id}/summary", hardware.summary)
            putString("hw/${hardware.id}/mode", VirtualDevicePolicy.mode(context, hardware).name)
            putStringArray(
                "hw/${hardware.id}/modes",
                hardware.modes.map { it.name }.toTypedArray(),
            )
            // The inner switch, which only radios have: whether hardware the device *has* is
            // currently switched on. See VirtualDevicePolicy.switchedOn for why that is a second
            // question rather than a third mode.
            putBoolean("hw/${hardware.id}/radio", hardware.id in RADIOS)
            putBoolean("hw/${hardware.id}/on", VirtualDevicePolicy.switchedOn(context, hardware))
        }
        putStringArray("hardware", ids.toTypedArray())

        val volumes = ArrayList<String>()
        VirtualStorage.Volume.entries.forEach { volume ->
            val root = VirtualStorage.root(context, volume)
            volumes += volume.name
            putString("vol/${volume.name}/label", volume.label)
            putString("vol/${volume.name}/path", volume.deviceRoot)
            putLong("vol/${volume.name}/free", root.freeSpace)
            putLong("vol/${volume.name}/total", root.totalSpace)
            putLong("vol/${volume.name}/used", sizeOf(root))
            putBoolean("vol/${volume.name}/keeps", volume == VirtualStorage.Volume.External)
        }
        putStringArray("volumes", volumes.toTypedArray())

        // What the camera has in front of it — read by the device's own Camera app, which is an
        // ordinary guest and so has no other way to learn a device setting.
        putString("camera/scene", VirtualDevicePolicy.cameraScene(context).id)

        // What the keyboard has been told about itself, read by the keyboard app at the start of
        // every input. The container keeps the values and never interprets them: which layouts
        // exist and what "Tall" is worth are the keyboard's business, and it is the thing that would
        // have to be changed to add another.
        VirtualDevicePolicy.KEYBOARD_KEYS.forEach { key ->
            putString(key, VirtualDevicePolicy.keyboard(context, key))
        }

        // What the radios have around them. The Settings app cannot ask the platform for any of
        // this: a scan there reaches the phone's WifiManager, which answers a caller holding no
        // location permission with an empty list. See VirtualRadios.
        putStringArray(
            "wifi/networks",
            VirtualRadios.wifi(context)
                .map { "${it.ssid}|${it.level}|${it.secured}" }
                .toTypedArray(),
        )
        putString("wifi/ssid", VirtualRadios.connected(context)?.ssid.orEmpty())
        putStringArray(
            "bluetooth/devices",
            VirtualRadios.bluetooth(context)
                .map { "${it.name}|${it.kind}|${it.paired}" }
                .toTypedArray(),
        )

        putString("about/model", VirtualIdentity.MODEL)
        putString("about/android", android.os.Build.VERSION.RELEASE)
        putInt("about/sdk", android.os.Build.VERSION.SDK_INT)
        putString("about/host", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
    }

    private fun apps(context: Context): Bundle = Bundle().apply {
        val installed = VirtualDeviceApps.list(context)
        putStringArray("packages", installed.map { it.packageName }.toTypedArray())
        installed.forEach { app ->
            putString("app/${app.packageName}/label", app.label)
            putString("app/${app.packageName}/version", app.versionName.orEmpty())
            putBoolean("app/${app.packageName}/system", DeviceIntents.isSystem(app.packageName))
        }
    }

    /**
     * One app's permissions — **the ones its manifest declares**, which is the only set that means
     * anything. A permission an app never asked for is not a setting, it is a row that can only ever
     * say no.
     */
    private fun app(context: Context, packageName: String): Bundle = Bundle().apply {
        val apk = VirtualDeviceApps.apk(context, packageName)
        val info = apk?.let {
            runCatching {
                context.packageManager.getPackageArchiveInfo(
                    it.absolutePath,
                    android.content.pm.PackageManager.GET_PERMISSIONS,
                )
            }.getOrNull()
        }
        // Read out of the archive rather than from a running guest, so the answer is the same
        // whether the app has ever been opened.
        val declared = info?.requestedPermissions.orEmpty().toList()
        putStringArray("permissions", declared.toTypedArray())
        declared.forEach { permission ->
            putString(
                "perm/$permission/rule",
                VirtualDevicePolicy.rule(context, packageName, permission).name,
            )
            putString("perm/$permission/label", VirtualDevicePolicy.title(context, permission))
            putBoolean("perm/$permission/runtime", VirtualDevicePolicy.dangerous(context, permission))
        }
    }

    /**
     * Changes one setting. `hw/<id>` takes a [HardwareMode]; `perm/<package>/<permission>` takes a
     * [PermissionRule].
     */
    private fun set(context: Context, key: String, extras: Bundle?): Bundle {
        val value = extras?.getString(EXTRA_VALUE).orEmpty()
        val who = GuestRuntime.activePackage() ?: "an app"
        val applied = when {
            key.startsWith("hw/") -> {
                val hardware = VirtualHardware.entries.firstOrNull { it.id == key.removePrefix("hw/") }
                val mode = runCatching { HardwareMode.valueOf(value) }.getOrNull()
                if (hardware != null && mode != null && mode in hardware.modes) {
                    VirtualDevicePolicy.setMode(context, hardware, mode)
                    true
                } else {
                    false
                }
            }

            key.startsWith("switch/") -> {
                val hardware = VirtualHardware.entries.firstOrNull { it.id == key.removePrefix("switch/") }
                if (hardware != null && hardware.id in RADIOS) {
                    VirtualDevicePolicy.setSwitchedOn(context, hardware, value.toBoolean())
                    true
                } else {
                    false
                }
            }

            // What the device is on and what it has paired — the device's business rather than the
            // bench's, and the one part of the radios a screen on the device gets to change.
            key == "wifi/ssid" -> {
                VirtualRadios.connect(context, value)
                true
            }

            key == "wifi/scan" -> {
                VirtualRadios.rescanWifi(context)
                true
            }

            key == "bluetooth/pair" -> {
                val paired = VirtualRadios.bluetooth(context).firstOrNull { it.name == value }
                if (paired != null) {
                    VirtualRadios.setPaired(context, value, !paired.paired)
                    true
                } else {
                    false
                }
            }

            key == "bluetooth/scan" -> {
                VirtualRadios.rescanBluetooth(context)
                true
            }

            // The key is checked and the value is not, which is the whole shape of this namespace:
            // the container is the device's store, and the keyboard is the authority on what a
            // layout is called. Validating the value here would mean this file had to be edited to
            // add a layout to an app it does not otherwise know anything about.
            key in VirtualDevicePolicy.KEYBOARD_KEYS -> {
                VirtualDevicePolicy.setKeyboard(context, key, value)
                true
            }

            key.startsWith("perm/") -> {
                val rest = key.removePrefix("perm/")
                val packageName = rest.substringBefore('/')
                val permission = rest.substringAfter('/', "")
                val rule = runCatching { PermissionRule.valueOf(value) }.getOrNull()
                if (permission.isNotEmpty() && rule != null) {
                    VirtualDevicePolicy.setRule(context, packageName, permission, rule)
                    true
                } else {
                    false
                }
            }

            else -> false
        }
        VirtualDeviceLog.append(
            context,
            if (applied) 'I' else 'W',
            TAG,
            if (applied) "$who set $key to $value" else "$who tried to set $key to '$value', refused",
        )
        return Bundle().apply { putBoolean(EXTRA_APPLIED, applied) }
    }

    /** What a volume is actually holding, which is the number a storage screen is for. */
    private fun sizeOf(file: java.io.File): Long =
        if (file.isDirectory) file.listFiles().orEmpty().sumOf { sizeOf(it) } else file.length()

    // A provider has to exist for `call` to be reachable, and none of the rest of it is meaningful
    // here: the device's settings are not documents and not rows.
    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    companion object {
        private const val TAG = "VDEVICE"

        const val METHOD_DEVICE = "device"
        const val METHOD_APPS = "apps"
        const val METHOD_APP = "app"
        const val METHOD_SET = "set"

        /** The hardware whose on/off state the device decides for itself, in its own Settings. */
        private val RADIOS = setOf("wifi", "bluetooth", "cellular")

        const val EXTRA_VALUE = "value"
        const val EXTRA_APPLIED = "applied"

        /** `${applicationId}.vdevice.settings`, as declared in the manifest. */
        fun authority(context: Context): String = context.packageName + ".vdevice.settings"
    }
}

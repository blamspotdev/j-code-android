package dev.jcode.adb

import android.content.Context
import android.os.Build
import android.util.Log
import dev.jcode.core.distro.adb.AdbAuthorizedKeys
import dev.jcode.core.distro.adb.AdbDaemon
import dev.jcode.core.distro.adb.AdbServiceHandler
import dev.jcode.core.distro.adb.AdbStream
import dev.jcode.core.distro.adb.adbCommandArgs
import dev.jcode.core.distro.adb.unsupportedService
import dev.jcode.vdevice.VirtualDevice
import dev.jcode.vdevice.VirtualIdentity
import java.io.File

/**
 * The adb services JCode's virtual device answers: everything an adb client asks of a device is
 * served out of the [VirtualDevice] container, and nothing is ever forwarded to the host phone.
 *
 * Implemented: `shell:getprop`, `shell:echo`, `shell:pm list packages`, `shell:am start -n`, and
 * `exec:cmd package 'install' -S <n>` — which is the single stream `adb install` uses once the
 * connection banner advertises the `cmd` feature. Everything else answers [unsupportedService] on
 * one line rather than hanging or pretending to have worked.
 *
 * "Installing" here means staging the APK under the container's own storage; there is no system
 * package database involved, so a guest is still invisible to the real `pm`.
 */
class VirtualDeviceAdbService(context: Context) : AdbServiceHandler {

    private val appContext = context.applicationContext
    private val apps = File(appContext.filesDir, APPS_DIR)

    /** What `getprop` answers with — the subset ddmlib and AGP actually read off a device. */
    private val properties: Map<String, String> by lazy {
        mapOf(
            "ro.product.name" to VirtualIdentity.PRODUCT,
            "ro.product.device" to VirtualIdentity.DEVICE,
            "ro.product.model" to VirtualIdentity.MODEL,
            "ro.product.brand" to BRAND,
            "ro.product.manufacturer" to BRAND,
            "ro.serialno" to VirtualIdentity.SERIAL,
            "ro.build.version.sdk" to Build.VERSION.SDK_INT.toString(),
            "ro.build.version.release" to Build.VERSION.RELEASE,
            "ro.build.version.codename" to Build.VERSION.CODENAME,
            "ro.build.version.preview_sdk" to "0",
            "ro.build.type" to "user",
            "ro.build.characteristics" to "default",
            "ro.product.cpu.abi" to Build.SUPPORTED_ABIS.first(),
            "ro.product.cpu.abilist" to Build.SUPPORTED_ABIS.joinToString(","),
            "ro.sf.lcd_density" to appContext.resources.displayMetrics.densityDpi.toString(),
        )
    }

    override suspend fun handle(stream: AdbStream) {
        val service = stream.service
        when {
            service.startsWith(SHELL) -> stream.write(
                shell(adbCommandArgs(service.removePrefix(SHELL))) ?: unsupportedService(service),
            )

            service.startsWith(EXEC) -> exec(adbCommandArgs(service.removePrefix(EXEC)), stream)
            else -> stream.write(unsupportedService(service))
        }
    }

    /** Null for "this daemon does not implement that command". */
    private fun shell(args: List<String>): String? = when (args.firstOrNull()) {
        "getprop" -> getprop(args.getOrNull(1))
        "echo" -> args.drop(1).joinToString(" ") + "\n"
        "pm" -> pm(args.drop(1))
        "am" -> am(args.drop(1))
        else -> null
    }

    private suspend fun exec(args: List<String>, stream: AdbStream) {
        val isInstall = args.getOrNull(0) == "cmd" &&
            args.getOrNull(1) == "package" &&
            args.getOrNull(2) == "install"
        if (!isInstall) {
            stream.write(unsupportedService(stream.service))
            return
        }
        val size = args.zipWithNext().firstOrNull { it.first == "-S" }?.second?.toLongOrNull()
        if (size == null) {
            // Without -S the client would stream until it closed the stream, which is the sync:
            // style install this daemon does not implement.
            stream.write("Failure [INSTALL_FAILED_INVALID_ARGS: '${stream.service}' has no -S size]\n")
            return
        }
        stream.write(install(size, stream))
    }

    private suspend fun install(size: Long, stream: AdbStream): String {
        apps.mkdirs()
        val staged = File(apps, "staged-${System.nanoTime()}.apk")
        var received = 0L
        try {
            staged.outputStream().use { out ->
                while (received < size) {
                    val chunk = stream.read() ?: break
                    out.write(chunk)
                    received += chunk.size
                }
            }
            if (received != size) {
                return "Failure [INSTALL_FAILED_INVALID_APK: got $received of $size bytes]\n"
            }
            val app = VirtualDevice.inspect(appContext, staged.absolutePath).getOrElse { error ->
                return "Failure [INSTALL_PARSE_FAILED_NOT_APK: ${error.message}]\n"
            }
            val target = File(apps, "${app.packageName}.apk")
            target.delete()
            if (!staged.renameTo(target)) {
                return "Failure [INSTALL_FAILED_INTERNAL_ERROR: cannot store ${app.packageName}]\n"
            }
            Log.i(TAG, "installed ${app.packageName} ${app.versionName} (${target.length()} bytes)")
            return "Success\n"
        } finally {
            staged.delete()
        }
    }

    private fun getprop(key: String?): String = when (key) {
        null -> properties.entries.joinToString("\n", postfix = "\n") { "[${it.key}]: [${it.value}]" }
        else -> properties[key].orEmpty() + "\n"
    }

    private fun pm(args: List<String>): String? {
        if (args.getOrNull(0) != "list" || args.getOrNull(1) != "packages") return null
        return installed().joinToString("") { "package:${it.name.removeSuffix(APK)}\n" }
    }

    private fun am(args: List<String>): String? {
        if (args.firstOrNull() != "start") return null
        val component = args.zipWithNext().firstOrNull { it.first == "-n" }?.second ?: return null
        val packageName = component.substringBefore('/')
        val activity = component.substringAfter('/', missingDelimiterValue = "")
        val apk = installed().firstOrNull { it.name == packageName + APK }
            ?: return "Error: Package $packageName is not installed on the virtual device\n"
        val className = activity.takeIf { it.isNotEmpty() }?.let { qualify(it, packageName) }
        return VirtualDevice.launch(appContext, apk.absolutePath, className).fold(
            onSuccess = { "Starting: Intent { cmp=$component }\n" },
            onFailure = { "Error: ${it.message}\n" },
        )
    }

    private fun installed(): List<File> = apps.listFiles().orEmpty()
        .filter { it.name.endsWith(APK) }
        .sortedBy(File::getName)

    private fun qualify(activity: String, packageName: String): String = when {
        activity.startsWith(".") -> packageName + activity
        !activity.contains('.') -> "$packageName.$activity"
        else -> activity
    }

    companion object {
        /**
         * `cmd` is the load-bearing feature: with it `adb install` opens exactly one
         * `exec:cmd package 'install' -S <n>` stream, and without it the client falls back to
         * `push` + `pm install`, which would need the whole `sync:` service. `shell_v2` is
         * deliberately absent — the client falls back to the simpler legacy `shell:` happily.
         */
        private const val FEATURES = "cmd,stat_v2,ls_v2,fixed_push_mkdir,apex,fixed_push_symlink_timestamp"

        private const val BRAND = "JCode"
        private const val APPS_DIR = "vdevice/apps"
        private const val APK = ".apk"
        private const val SHELL = "shell:"
        private const val EXEC = "exec:"
        private const val TAG = "VirtualDeviceAdb"

        /**
         * The adb daemon for JCode's virtual device, bound to loopback and authenticated against the
         * distro's own `~/.android/adbkey.pub`. Call [AdbDaemon.start] to bind (it returns the port
         * for `adb connect 127.0.0.1:<port>`) and [AdbDaemon.stop] to tear it down.
         */
        fun daemon(context: Context): AdbDaemon {
            val app = context.applicationContext
            return AdbDaemon(
                banner = "device::ro.product.name=${VirtualIdentity.PRODUCT};" +
                    "ro.product.model=${VirtualIdentity.MODEL};" +
                    "ro.product.device=${VirtualIdentity.DEVICE};" +
                    "features=$FEATURES",
                authorizedKeys = AdbAuthorizedKeys(File(app.filesDir, "distros")),
                handler = VirtualDeviceAdbService(app),
                log = { message -> Log.i(TAG, message) },
            )
        }
    }
}

package dev.jcode.run

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import dev.jcode.AppInstallReceiver
import java.io.File

/**
 * Installs an APK the user's project just built, for the degraded run path used when the ADB bridge
 * isn't set up (`adb install` is preferred whenever it is, because it doesn't interrupt with a
 * confirmation dialog).
 *
 * Deliberately NOT reusing [dev.jcode.AppUpdateInstaller]: that object's state drives the app-update
 * UI, and its `setRequireUserAction(USER_ACTION_NOT_REQUIRED)` is only honoured for a same-signature
 * self-update. A project's debug APK is a different package signed with a different key, so the
 * system will always prompt — the session is created without that flag so the behaviour is explicit.
 * Status routes through the shared [AppInstallReceiver] on its own action.
 */
object ApkInstaller {

    const val INSTALL_ACTION = "dev.jcode.PROJECT_APK_INSTALL_STATUS"

    sealed interface State {
        data object Idle : State
        data class Installing(val apkName: String) : State
        data object NeedsUnknownSourcePermission : State
        data class Success(val packageName: String?) : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() {
        _state.value = State.Idle
    }

    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openUnknownSourceSettings(context: Context) {
        val intent = Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    suspend fun install(context: Context, apk: File) {
        if (!apk.isFile) {
            _state.value = State.Failed("APK not found: ${apk.name}")
            return
        }
        if (!canInstall(context)) {
            _state.value = State.NeedsUnknownSourcePermission
            return
        }
        _state.value = State.Installing(apk.name)
        runCatching {
            withContext(Dispatchers.IO) { commit(context, apk) }
        }.onFailure {
            _state.value = State.Failed(it.message ?: "Install failed")
        }
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite(apk.name, 0, apk.length()).use { dest ->
                apk.inputStream().use { it.copyTo(dest) }
                session.fsync(dest)
            }
            // Must target AppInstallReceiver by explicit component: the receiver has no
            // <intent-filter>, so an action-only intent is never delivered and the session status —
            // including STATUS_PENDING_USER_ACTION — is silently dropped.
            val intent = Intent(context, AppInstallReceiver::class.java).setAction(INSTALL_ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    /** Called from [AppInstallReceiver] with the session status broadcast. */
    fun onSessionStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    runCatching { context.startActivity(confirm) }
                        .onFailure { _state.value = State.Failed("Couldn't open the installer") }
                } else {
                    _state.value = State.Failed("Installer confirmation unavailable")
                }
            }

            PackageInstaller.STATUS_SUCCESS ->
                _state.value = State.Success(intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME))

            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _state.value = State.Failed(msg?.takeIf { it.isNotBlank() } ?: "Install failed")
            }
        }
    }

    /**
     * Launches an installed package. targetSdk is 28, so package-visibility filtering does not apply
     * and this resolves unrestricted; raising targetSdk to 30+ would require a `<queries>` entry for
     * MAIN/LAUNCHER in the manifest.
     */
    fun launch(context: Context, packageName: String): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: return false
        return runCatching { context.startActivity(intent) }.isSuccess
    }
}

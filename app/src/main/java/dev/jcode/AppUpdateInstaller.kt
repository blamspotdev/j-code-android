package dev.jcode

import android.app.PendingIntent
import android.content.BroadcastReceiver
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
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * In-app updater: downloads the release `.apk` from GitHub and installs it via [PackageInstaller].
 *
 * The system still shows its own install confirmation (and Google Play Protect's scan for a build it
 * doesn't recognize) — no app can suppress that. But a **same-signature self-update** on API 31+ can
 * be applied silently (USER_ACTION_NOT_REQUIRED), and the whole flow stays inside the app instead of
 * bouncing the user out to a browser + file manager.
 *
 * A process-wide singleton so [AppInstallReceiver] (which the system invokes with the session status)
 * can route results back into [state] regardless of which Activity is alive.
 */
object AppUpdateInstaller {
    const val INSTALL_ACTION = "dev.jcode.action.APP_INSTALL_STATUS"

    sealed interface State {
        data object Idle : State
        data class Downloading(val percent: Int) : State
        data object Installing : State

        /** The user hasn't allowed this app to install unknown apps — send them to that setting. */
        data object NeedsUnknownSourcePermission : State
        data object Success : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() {
        val s = _state.value
        if (s is State.Success || s is State.Failed || s is State.NeedsUnknownSourcePermission) {
            _state.value = State.Idle
        }
    }

    /** Whether the user has granted "install unknown apps" to JCode. */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    /** Open the system "install unknown apps" screen for JCode so the user can allow it. */
    fun openUnknownSourceSettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${context.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** Download [apkUrl] and hand it to the system installer, publishing progress via [state]. */
    suspend fun downloadAndInstall(context: Context, apkUrl: String) {
        val app = context.applicationContext
        if (!canInstall(app)) {
            _state.value = State.NeedsUnknownSourcePermission
            return
        }
        val apk = try {
            _state.value = State.Downloading(0)
            withContext(Dispatchers.IO) { download(app, apkUrl) }
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Download failed")
            return
        }
        try {
            _state.value = State.Installing
            withContext(Dispatchers.IO) { commit(app, apk) }
            // Terminal state (Success/Failed) is set by AppInstallReceiver when the session reports.
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Install failed")
        }
    }

    private fun download(context: Context, url: String): File {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 30_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "JCode-Android")
        }
        try {
            conn.connect()
            if (conn.responseCode != HttpURLConnection.HTTP_OK) {
                throw IOException("Download failed (HTTP ${conn.responseCode})")
            }
            val total = conn.contentLengthLong
            val out = File(context.cacheDir, "jcode-update.apk")
            conn.inputStream.use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    var n = input.read(buf)
                    while (n >= 0) {
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            _state.value = State.Downloading(((downloaded * 100) / total).toInt().coerceIn(0, 100))
                        }
                        n = input.read(buf)
                    }
                }
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun commit(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        // Same-signature self-updates can skip the confirmation dialog; otherwise the session reports
        // STATUS_PENDING_USER_ACTION and we launch the system installer to confirm.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("jcode.apk", 0, apk.length()).use { dest ->
                apk.inputStream().use { it.copyTo(dest) }
                session.fsync(dest)
            }
            val intent = Intent(INSTALL_ACTION).setPackage(context.packageName)
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
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.Success
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _state.value = State.Failed(msg?.takeIf { it.isNotBlank() } ?: "Install failed")
            }
        }
    }
}

/** Manifest receiver for the [PackageInstaller] session status PendingIntent — see [AppUpdateInstaller]. */
class AppInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == AppUpdateInstaller.INSTALL_ACTION) {
            AppUpdateInstaller.onSessionStatus(context.applicationContext, intent)
        }
    }
}

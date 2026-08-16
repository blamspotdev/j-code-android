package dev.jcode.webengine

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Installs the engine split APK into JCode's own package.
 *
 * `MODE_INHERIT_EXISTING` is what makes this an *addition*: the session contributes the
 * `webengine` split alongside the installed base APK instead of replacing it. The split must be
 * signed with the app's key and carry the app's exact `versionCode` — both are true by
 * construction, because the split is built from the same tree by `:webengine` (and the Web
 * Engine extension ships the split matching the JCode release it targets).
 *
 * After a successful commit the new classes are **not** visible to the running process; the
 * engine appears on the next start. [State.NeedsRestart] is therefore the success state, and the
 * caller surfaces JCode's existing restart prompt.
 *
 * Mirrors [dev.jcode.AppUpdateInstaller]'s session/receiver shape — same watchdog reasoning,
 * same explicit-component PendingIntent requirement — but kept separate: that flow replaces the
 * whole app and exits; this one adds a split and keeps running.
 */
object WebEngineInstaller {
    const val INSTALL_ACTION = "dev.jcode.action.WEBENGINE_INSTALL_STATUS"

    sealed interface State {
        data object Idle : State
        data object Installing : State
        data object NeedsRestart : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun reset() {
        if (_state.value is State.Failed || _state.value is State.NeedsRestart) _state.value = State.Idle
    }

    /** Commit [splitApk] as the `webengine` split of this package. Progress arrives via [state]. */
    suspend fun install(context: Context, splitApk: File) {
        val app = context.applicationContext
        try {
            _state.value = State.Installing
            withContext(Dispatchers.IO) { commit(app, splitApk) }
        } catch (e: Exception) {
            _state.value = State.Failed(e.message ?: "Engine install failed")
        }
    }

    private fun commit(context: Context, splitApk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_INHERIT_EXISTING)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("webengine.apk", 0, splitApk.length()).use { dest ->
                splitApk.inputStream().use { it.copyTo(dest) }
                session.fsync(dest)
            }
            val intent = Intent(context, WebEngineInstallReceiver::class.java).setAction(INSTALL_ACTION)
            var flags = PendingIntent.FLAG_UPDATE_CURRENT
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) flags = flags or PendingIntent.FLAG_MUTABLE
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

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
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.NeedsRestart
            else -> {
                val msg = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                _state.value = State.Failed(msg?.takeIf { it.isNotBlank() } ?: "Engine install failed")
            }
        }
    }
}

/** Manifest receiver for the engine-split session status — see [WebEngineInstaller]. */
class WebEngineInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == WebEngineInstaller.INSTALL_ACTION) {
            WebEngineInstaller.onSessionStatus(context.applicationContext, intent)
        }
    }
}

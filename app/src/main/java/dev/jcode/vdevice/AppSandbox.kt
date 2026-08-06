package dev.jcode.vdevice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceControlViewHost
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** How a guest app can be shown, given what this window supports. */
internal enum class AppSandboxTier {
    /** Composited into the editor tab out of the `:guest` process. */
    Embedded,

    /** No embedding possible; the app can still take over the screen as its own task. */
    FullScreen,
}

internal sealed interface SandboxStatus {
    data object Idle : SandboxStatus
    data object Starting : SandboxStatus

    /** [warning] is set when the guest is up but something about it is degraded. */
    data class Running(val packageName: String?, val warning: String?) : SandboxStatus
    data class Stopped(val reason: String) : SandboxStatus
    data class Failed(val message: String) : SandboxStatus
}

/**
 * Shared state for J Code's single app-sandbox tab: the run flow only asks for one, the shell opens
 * the tab, and the page reads the APK back out.
 */
internal object AppSandbox {

    /** Bumped on each open request so the shell can surface the tab. */
    val revealSignal = mutableStateOf(0)

    // The tab's own state lives here rather than in the composition: the editor pane tears a page
    // down when another tab is selected, and a guest must survive that without being restarted or
    // losing the APK the user typed.

    /** APK the sandbox runs, set by a finished virtual-device build or typed into the page. */
    val apkPath = mutableStateOf("")

    /** True once the user has asked for the guest, i.e. the page shows the surface, not the setup. */
    val showing = mutableStateOf(false)

    private var session: AppSandboxSession? = null

    fun requestOpen(apkPath: String?) {
        apkPath?.trim()?.takeIf { it.isNotEmpty() }?.let { this.apkPath.value = it }
        revealSignal.value += 1
    }

    /** One session per process: the container owns a single `:guest` process, so a second tab would
     *  only ever be a second view of the same guest. */
    @Synchronized
    fun session(context: Context): AppSandboxSession =
        session ?: AppSandboxSession(context).also { session = it }

    @Synchronized
    fun close() {
        session?.close()
        session = null
        showing.value = false
    }
}

/**
 * The IDE's half of an embedded guest: binds [GuestSessionService], holds the resulting
 * `SurfacePackage`, and forwards input.
 *
 * Unbinding is the teardown: nothing else in the app keeps `:guest` alive, so dropping the binding
 * takes the guest's heap, its framework hooks and its faked `Build` identity with it.
 */
internal class AppSandboxSession(context: Context) {

    private val appContext = context.applicationContext
    private val _status = MutableStateFlow<SandboxStatus>(SandboxStatus.Idle)
    val status: StateFlow<SandboxStatus> = _status.asStateFlow()

    /** The package the tab's [android.view.SurfaceView] should adopt. Re-published, not reused, when
     *  the view is recreated: a SurfaceView releases its child package as it detaches. */
    private val _surface = MutableStateFlow<SurfaceControlViewHost.SurfacePackage?>(null)
    val surface: StateFlow<SurfaceControlViewHost.SurfacePackage?> = _surface.asStateFlow()

    // Startup outlives the composition that asked for it. Driving it from a LaunchedEffect instead
    // would abandon a half-started guest — with the service bound and no way back — every time the
    // tab is relaid out, which is exactly what a surface's first size change does.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var startup: Job? = null

    @Volatile
    private var service: IGuestSession? = null
    private var bound = false
    private val connected = MutableStateFlow(false)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = IGuestSession.Stub.asInterface(binder)
            connected.value = true
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            connected.value = false
            _status.value = SandboxStatus.Failed("The guest process stopped unexpectedly.")
        }
    }

    private val callback = object : IGuestSessionCallback.Stub() {
        override fun onGuestFinished(reason: String?) {
            _status.value = SandboxStatus.Stopped(reason ?: "The app closed.")
        }
    }

    /**
     * Starts the guest, or — when it is already running — adapts it to the tab's current size and
     * re-publishes its surface. Safe to call on every layout pass; it does nothing while a start is
     * already in flight.
     */
    fun ensureStarted(apkPath: String, width: Int, height: Int, hostToken: IBinder?) {
        if (startup?.isActive == true || width <= 0 || height <= 0) return
        if (_status.value is SandboxStatus.Running) {
            resize(width, height)
            startup = scope.launch { _surface.value = readSurface() }
            return
        }
        startup = scope.launch { start(apkPath, width, height, hostToken) }
    }

    fun restart(apkPath: String, width: Int, height: Int, hostToken: IBinder?) {
        close()
        ensureStarted(apkPath, width, height, hostToken)
    }

    private suspend fun start(apkPath: String, width: Int, height: Int, hostToken: IBinder?) {
        _status.value = SandboxStatus.Starting
        val guest = withTimeoutOrNull(BIND_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                if (!bind()) return@withContext null
                connected.first { it }
                service
            }
        } ?: return fail("Could not start the guest process.")

        val result = withContext(Dispatchers.IO) {
            runCatching { guest.start(apkPath, null, width, height, hostToken, callback) }
        }.getOrElse { return fail(it.message ?: "The guest process refused the app.") }

        result.getString(GuestSessionService.KEY_ERROR)?.let { return fail(it) }
        val surface = result.getParcelable(
            GuestSessionService.KEY_SURFACE,
            SurfaceControlViewHost.SurfacePackage::class.java,
        ) ?: return fail("The guest produced no surface.")

        _surface.value = surface
        _status.value = SandboxStatus.Running(
            packageName = result.getString(GuestSessionService.KEY_PACKAGE),
            warning = if (result.getBoolean(GuestSessionService.KEY_FULL_LIFECYCLE, true)) null else
                "The container could not drive this activity's full lifecycle, so anything that " +
                    "waits on onStart/onResume — Compose, in particular — may not draw.",
        )
    }

    private suspend fun readSurface(): SurfaceControlViewHost.SurfacePackage? {
        val guest = service ?: return null
        return withContext(Dispatchers.IO) { runCatching { guest.surface() } }.getOrNull()
            ?.getParcelable(
                GuestSessionService.KEY_SURFACE,
                SurfaceControlViewHost.SurfacePackage::class.java,
            )
    }

    fun resize(width: Int, height: Int) = ignoringDeath { it.resize(width, height) }

    fun touch(event: MotionEvent) = ignoringDeath { it.touch(event) }

    fun key(event: KeyEvent) = ignoringDeath { it.key(event) }

    fun text(text: String) = ignoringDeath { it.text(text) }

    fun back() = ignoringDeath { it.back() }

    /** Unbinding is the teardown: `:guest` has no other component keeping it alive. */

    fun close() {
        startup?.cancel()
        startup = null
        if (bound) runCatching { appContext.unbindService(connection) }
        bound = false
        service = null
        connected.value = false
        _surface.value = null
        _status.value = SandboxStatus.Idle
    }

    private fun bind(): Boolean {
        if (bound) return true
        bound = appContext.bindService(
            Intent(appContext, GuestSessionService::class.java),
            connection,
            Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT,
        )
        if (!bound) Log.e(TAG, "cannot bind ${GuestSessionService::class.java.name}")
        return bound
    }

    private fun fail(message: String) {
        _surface.value = null
        _status.value = SandboxStatus.Failed(message)
    }

    private inline fun ignoringDeath(block: (IGuestSession) -> Unit) {
        val guest = service ?: return
        runCatching { block(guest) }.onFailure { Log.w(TAG, "guest call failed", it) }
    }

    private companion object {
        const val BIND_TIMEOUT_MS = 15_000L
    }
}

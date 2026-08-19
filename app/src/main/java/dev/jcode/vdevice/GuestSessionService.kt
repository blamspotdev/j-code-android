package dev.jcode.vdevice

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * The `:guest` process's entry point for the device-sandbox editor tab.
 *
 * The container has to stay out of the IDE process — it swaps `ActivityThread.mInstrumentation` and
 * rewrites `Build`, neither of which the workbench could survive — so the tab reaches it the only
 * way one process can reach another: a bound service. Everything it does touches the view tree, so
 * every call is marshalled onto this process's main thread.
 */
class GuestSessionService : Service() {

    private val main = Handler(Looper.getMainLooper())
    private var callback: IGuestSessionCallback? = null

    private val guest: EmbeddedGuest by lazy {
        EmbeddedGuest(this) { reason ->
            runCatching { callback?.onGuestFinished(reason) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { GuestRuntime.install(this) }
            .onFailure { Log.e(TAG, "cannot install container hooks", it) }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        onMain { guest.stop() }
        callback = null
        // Nothing left to ask, so a request that arrives after this is denied rather than left
        // waiting for an answer that cannot come.
        GuestPermissions.setPrompt(null)
        return false
    }

    /**
     * The device's own prompt, put up over the app that is asking.
     *
     * This used to be a round trip: out of `:guest` over the binder, on to the screen as a Compose
     * dialog in the tab, and back again with the answer. Both halves of it were in this process the
     * whole time — [GuestPermissions] asks and [EmbeddedGuest] draws — so the question never needed
     * to leave, and leaving was what put it in the wrong window. See [VirtualPermissionDialog] for
     * why a dialog an agent can photograph and cannot tap is worse than no dialog at all.
     *
     * Posted rather than called: the ask arrives on whatever thread the guest called
     * `requestPermissions` on, and the view tree is the main thread's alone.
     */
    private fun ask(requestId: Int, permissions: Array<String>) {
        val packageName = GuestRuntime.activePackage().orEmpty()
        main.post {
            guest.askPermission(packageName, permissions.toList()) { allow ->
                GuestPermissions.answered(requestId, BooleanArray(permissions.size) { allow })
            }
        }
    }

    private val binder = object : IGuestSession.Stub() {

        override fun start(
            apkPath: String?,
            activityClass: String?,
            width: Int,
            height: Int,
            hostToken: IBinder?,
            callback: IGuestSessionCallback?,
        ): Bundle = Bundle().also { result ->
            this@GuestSessionService.callback = callback
            // Wired here rather than at install, because the prompt is drawn on the device's screen
            // and until now there has not been one.
            GuestPermissions.setPrompt(::ask)
            if (!GuestRuntime.isInstalled) {
                result.putString(KEY_ERROR, "The container's framework hooks are not installed.")
                return@also
            }
            runCatching {
                onMain {
                    guest.start(
                        apkPath ?: throw VirtualDeviceException("no APK path"),
                        activityClass,
                        width,
                        height,
                        hostToken,
                    )
                }
            }.onSuccess { surface ->
                result.putParcelable(KEY_SURFACE, surface)
                result.putBoolean(KEY_FULL_LIFECYCLE, guest.fullLifecycle)
            }.onFailure { error ->
                Log.e(TAG, "cannot embed $apkPath", error)
                onMain { guest.stop() }
                result.putString(KEY_ERROR, error.describe())
            }
        }

        override fun surface(): Bundle = Bundle().also { result ->
            runCatching { onMain { guest.surface() } }
                .onSuccess { result.putParcelable(KEY_SURFACE, it) }
                .onFailure { result.putString(KEY_ERROR, it.message ?: it.toString()) }
        }

        override fun capture(pngPath: String?): Bundle = Bundle().also { result ->
            runCatching {
                onMain { guest.capture(File(pngPath ?: throw VirtualDeviceException("no capture path"))) }
            }.onFailure {
                Log.w(TAG, "cannot capture the guest's screen", it)
                result.putString(KEY_ERROR, it.message ?: it.toString())
            }
        }

        override fun dump(xmlPath: String?): Bundle = Bundle().also { result ->
            runCatching {
                onMain { guest.dump(File(xmlPath ?: throw VirtualDeviceException("no dump path"))) }
            }.onFailure {
                Log.w(TAG, "cannot dump the guest's view tree", it)
                result.putString(KEY_ERROR, it.message ?: it.toString())
            }
        }

        override fun resize(width: Int, height: Int) = post { guest.resize(width, height) }

        override fun touch(event: MotionEvent?) {
            event?.let { post { guest.touch(it) } }
        }

        override fun key(event: KeyEvent?) {
            event?.let { post { guest.key(it) } }
        }

        override fun text(text: String?) {
            text?.takeIf { it.isNotEmpty() }?.let { post { guest.text(it) } }
        }

        override fun back() = post { guest.back() }

        override fun ime(command: String?): Bundle = Bundle().also { result ->
            runCatching { onMain { guest.ime(command.orEmpty()) } }
                .onSuccess { result.putString(KEY_OUTPUT, it) }
                .onFailure { result.putString(KEY_ERROR, it.message ?: it.toString()) }
        }

        override fun forceStop(packageName: String?) {
            packageName?.let { name -> post { GuestRuntime.forceStop(name) } }
        }

        /**
         * Ends the device, process and all.
         *
         * Killing our own pid is allowed — same uid, same app — and it is the only thing that
         * actually clears what this process has accumulated: the loaded guests and their class
         * loaders, anything `GuestComponents` is still hosting, the `Instrumentation` swapped into
         * `ActivityThread`, the rewritten `Build`, and the WebView data directory claimed for the
         * guest. None of that has an undo, which is why the container is in a process of its own.
         *
         * Posted rather than immediate so this transaction can return first; the caller is one-way,
         * but the unbind that follows it is not.
         */
        override fun setVisible(visible: Boolean) {
            post { guest.setVisible(visible) }
        }

        override fun shutdown() {
            post { guest.stop() }
            main.postDelayed({
                Log.i(TAG, "virtual device off; ending the guest process")
                Process.killProcess(Process.myPid())
            }, SHUTDOWN_DELAY_MS)
        }
    }

    private fun post(block: () -> Unit) {
        main.post { runCatching(block).onFailure { Log.w(TAG, "guest input", it) } }
    }

    /** Runs [block] on this process's main thread and rethrows what it threw there, so a failure
     *  reaches the caller as its own message instead of a bare transaction error. */
    private fun <T> onMain(block: () -> T): T {
        if (Looper.myLooper() == Looper.getMainLooper()) return block()
        val done = CountDownLatch(1)
        val outcome = arrayOfNulls<Any>(1)
        val failure = arrayOfNulls<Throwable>(1)
        main.post {
            try {
                outcome[0] = block()
            } catch (t: Throwable) {
                failure[0] = t
            } finally {
                done.countDown()
            }
        }
        done.await()
        failure[0]?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return outcome[0] as T
    }

    companion object {
        const val KEY_SURFACE = "surface"
        const val KEY_ERROR = "error"

        /** What a command wrote, for the calls that answer with text rather than with a screen. */
        const val KEY_OUTPUT = "output"

        /** Long enough for the shutdown transaction and the unbind behind it to finish. */
        private const val SHUTDOWN_DELAY_MS = 150L

        /** False when the container could not reach the activity's `ActivityLifecycleCallbacks` and
         *  had to nudge the guest's own `LifecycleRegistry` — see [GuestRuntime.resumeEmbedded]. */
        const val KEY_FULL_LIFECYCLE = "fullLifecycle"
    }
}

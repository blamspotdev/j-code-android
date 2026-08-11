package dev.jcode.vdevice

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
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
        return false
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
                result.putString(KEY_ERROR, error.message ?: error.toString())
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

        /** False when the container could not reach the activity's `ActivityLifecycleCallbacks` and
         *  had to nudge the guest's own `LifecycleRegistry` — see [GuestRuntime.resumeEmbedded]. */
        const val KEY_FULL_LIFECYCLE = "fullLifecycle"
    }
}

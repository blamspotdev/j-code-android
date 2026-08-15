package dev.jcode.vdevice

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import java.io.File

/**
 * What the device does when a guest asks for a photo.
 *
 * `MediaStore.ACTION_IMAGE_CAPTURE` is how an app asks for *a picture* rather than for *a camera
 * pipeline*, and on a phone it is answered by the camera app: the requester never opens a camera,
 * never needs the `CAMERA` permission, and receives an image. That shape is one this device can
 * honour completely, which is why it is the one that is honoured.
 *
 * Before this the intent left the device — the **phone's** camera app opened over JCode, took a real
 * photograph with the user's real camera, and then delivered the answer to an activity token no
 * `ActivityRecord` responds to, so the guest never got it. A sandboxed app pointing the user's
 * camera at the world and JCode being unable to hand back the result is the worst of both.
 *
 * Two answers, matching the platform's contract exactly:
 *
 * | The app passed | It gets back |
 * |---|---|
 * | `EXTRA_OUTPUT` | The full-size JPEG written to that URI, and `RESULT_OK` with no data |
 * | nothing | A thumbnail `Bitmap` under the `"data"` extra, which is the contract's fallback |
 *
 * Either way the full-size image is also kept in the device's own `DCIM/Camera`, because the photo
 * somebody just took should be somewhere they can find it — and on this device that is a path
 * `adb pull` takes.
 *
 * ### What it does *not* do
 *
 * Stand in for Camera2. An app that opens a `CameraDevice` and configures a capture session is
 * talking to a native binder pipeline the container cannot get in front of: `CameraManager` is
 * `final`, so it cannot be substituted, and the frames an app would receive are written into its
 * `Surface` by the camera HAL rather than by anything this process could intercept. That is stated
 * in the device's log the first time a guest asks for the camera service, because a preview that
 * stays black with nothing anywhere saying why is exactly the failure this whole subsystem exists to
 * stop producing.
 */
internal object GuestCamera {

    private const val RESULT_CANCELED = 0
    private const val RESULT_OK = -1

    /** The extra a capture with no `EXTRA_OUTPUT` answers under — `"data"`, by contract. */
    private const val EXTRA_THUMBNAIL = "data"

    /** A thumbnail's longest side. The contract says "small"; a phone's is about this. */
    private const val THUMBNAIL = 512

    private lateinit var host: Context

    /** How the container reaches the tab to put a viewfinder on the screen; null with no tab bound. */
    @Volatile
    private var camera: ((String, (File?) -> Unit) -> Boolean)? = null

    /** Said once per process — see the class docs on Camera2. */
    @Volatile
    private var warnedAboutCamera2 = false

    fun install(context: Context) {
        host = context.applicationContext
    }

    fun setCamera(camera: ((String, (File?) -> Unit) -> Boolean)?) {
        this.camera = camera
    }

    /**
     * Answers a guest's capture request. True when the launch has been dealt with and the binder
     * call must not happen.
     *
     * The request code is read the same way [GuestDocuments.consume] reads it, and for the same
     * reason: `IActivityTaskManager.startActivity` carries no `int` before `requestCode`.
     */
    fun consume(args: Array<Any?>): Boolean {
        val intent = args.filterIsInstance<Intent>().firstOrNull() ?: return false
        if (intent.action !in CAPTURE_ACTIONS) return false
        val activity = GuestRuntime.foregroundActivity() ?: return false
        val slot = args.indexOfFirst { it is Intent }
        val requestCode = args.drop(slot + 1).filterIsInstance<Int>().firstOrNull() ?: -1
        val output = intent.getParcelableExtra(MediaStore.EXTRA_OUTPUT, Uri::class.java)

        // The device's own switch decides whether there is a camera at all, exactly as it does for
        // every other piece of hardware — an app cannot be handed one the device does not have.
        if (!hasCamera()) {
            report("the device's camera is switched off — turn it on in Device hardware")
            answer(activity, requestCode, null, null)
            return true
        }
        val ask = camera
        if (ask == null) {
            report("there is no device screen to open a viewfinder on")
            answer(activity, requestCode, null, null)
            return true
        }
        // Posted for the reason GuestDocuments posts: a guest may ask from any thread, and a View may
        // only be added on the main one.
        Handler(Looper.getMainLooper()).post {
            val shown = runCatching {
                ask(titleFor()) { file -> answer(activity, requestCode, file, output) }
            }.onFailure { Log.w(TAG, "cannot open the device's camera", it) }.getOrDefault(false)
            if (!shown) {
                report("the device could not open its camera")
                answer(activity, requestCode, null, null)
            }
        }
        return true
    }

    /**
     * Says, once, that a guest reaching for Camera2 will not get frames.
     *
     * Called from [GuestContext.getSystemService]. It changes nothing about what the app receives —
     * there is nothing this container can do about it — but "the preview is black" and "this device
     * does not do previews" are very different things to be holding while you debug, and only one of
     * them was previously available.
     */
    fun noteCamera2Use() {
        if (warnedAboutCamera2 || !::host.isInitialized) return
        warnedAboutCamera2 = true
        VirtualDeviceLog.append(
            host,
            'W',
            TAG,
            "${GuestRuntime.activePackage()} asked for the camera service. This device answers " +
                "ACTION_IMAGE_CAPTURE with its own simulated camera, but it cannot stand in for " +
                "Camera2 — CameraManager is final and its frames are written by the camera HAL — so " +
                "a CameraDevice preview will stay black.",
        )
    }

    private fun hasCamera(): Boolean =
        runCatching { VirtualDevicePolicy.mode(host, VirtualHardware.Camera) != HardwareMode.Off }
            .getOrDefault(false)

    private fun titleFor(): String = "${GuestRuntime.activeLabel() ?: "This app"} wants a photo"

    /**
     * Hands the picture back the way the platform's contract says to.
     *
     * A failure to write the app's `EXTRA_OUTPUT` is answered as a **cancel** rather than as an OK
     * with nothing behind it: an app told the capture succeeded then reads an empty file, which is a
     * harder thing to debug than a capture that says it did not happen.
     */
    private fun answer(activity: Activity, requestCode: Int, file: File?, output: Uri?) {
        if (file == null) {
            deliver(activity, requestCode, RESULT_CANCELED, null)
            return
        }
        VirtualDeviceLog.append(
            host,
            'I',
            TAG,
            "${GuestRuntime.activePackage()} took a photo: " +
                "${VirtualStorage.devicePath(host, file)} (${file.length()} bytes)" +
                (output?.let { " -> $it" }.orEmpty()),
        )
        if (output != null) {
            val written = runCatching {
                host.contentResolver.openOutputStream(output)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } != null
            }.onFailure { Log.w(TAG, "cannot write the photo to $output", it) }.getOrDefault(false)
            if (!written) {
                report("could not write the photo to the app's own $output")
                deliver(activity, requestCode, RESULT_CANCELED, null)
                return
            }
            deliver(activity, requestCode, RESULT_OK, Intent())
            return
        }
        deliver(activity, requestCode, RESULT_OK, Intent().putExtra(EXTRA_THUMBNAIL, thumbnail(file)))
    }

    /** The small bitmap the no-`EXTRA_OUTPUT` form of the contract answers with. */
    private fun thumbnail(file: File): Bitmap? = runCatching {
        val full = BitmapFactory.decodeFile(file.absolutePath) ?: return null
        val scale = THUMBNAIL.toFloat() / maxOf(full.width, full.height)
        Bitmap.createScaledBitmap(full, (full.width * scale).toInt(), (full.height * scale).toInt(), true)
            .also { if (it !== full) full.recycle() }
    }.onFailure { Log.w(TAG, "cannot build a thumbnail of ${file.name}", it) }.getOrNull()

    /** The same door [GuestDocuments] delivers through; the reasoning is documented there. */
    private fun deliver(activity: Activity, requestCode: Int, resultCode: Int, data: Intent?) {
        Handler(Looper.getMainLooper()).post {
            GuestDocuments.deliverResult(activity, requestCode, resultCode, data)
        }
    }

    private fun report(why: String) {
        if (!::host.isInitialized) return
        VirtualDeviceLog.append(
            host,
            'W',
            TAG,
            "${GuestRuntime.activePackage()} asked for a photo, but $why — " +
                "answering the app with a cancelled result",
        )
    }

    /**
     * `ACTION_IMAGE_CAPTURE` and the secure-lockscreen variant beside it. `ACTION_VIDEO_CAPTURE` is
     * deliberately absent: the device can draw a frame and cannot encode a film, and an app handed a
     * one-frame video would be worse off than one told there is no camera app for it.
     */
    private val CAPTURE_ACTIONS = setOf(
        MediaStore.ACTION_IMAGE_CAPTURE,
        MediaStore.ACTION_IMAGE_CAPTURE_SECURE,
    )
}

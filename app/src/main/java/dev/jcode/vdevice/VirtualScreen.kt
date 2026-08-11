package dev.jcode.vdevice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.WindowManager
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The device sandbox's screen, as pixels — what `adb shell screencap` answers with.
 *
 * Neither of the obvious ways to read it works, and both were measured rather than assumed.
 * `screencap -d <id>` returns a 0-byte file for a display this app owns, which is what the retired
 * display-server work ran into. And `PixelCopy` over the tab's `SurfaceView` answers
 * `ERROR_SOURCE_NO_DATA` every time: the guest's pixels live in a `SurfaceControl` of its own that
 * the view only *parents*, so the buffer queue `PixelCopy` reads is one nothing was ever queued
 * into — while every non-SDK member of `SurfaceControlViewHost` that could reach the real layer is
 * denied outright.
 *
 * So the screen is asked of whoever is drawing it: the guest re-draws its own hierarchy, which is
 * exact for everything the view system draws. A surface the guest composites itself — a `VideoView`,
 * say — would come back empty, and there is no way from here to know it did.
 *
 * A device with no app on it is not an error: it answers the idle screen at the size the tab gives
 * the device — the same [VirtualWallpaper] the tab paints — so what a driver captures is what the
 * user is looking at.
 */
internal object VirtualScreen {

    private const val CAPTURE_FILE = "vdevice/screen.png"

    @Volatile
    private var size: Pair<Int, Int>? = null

    /** The tab's pixel size is the device's resolution, and it outlives the view that measured it. */
    fun sized(width: Int, height: Int) {
        if (width > 0 && height > 0) size = width to height
    }

    /** The device's resolution, for `wm size` and for anything mapping its own coordinates onto it. */
    fun resolution(context: Context): Pair<Int, Int> = size ?: displaySize(context)

    /** The current screen as PNG bytes. Never fails: with nothing running the screen is black. */
    suspend fun png(context: Context): ByteArray {
        val capture = File(context.filesDir, CAPTURE_FILE)
        if (AppSandbox.sessionOrNull()?.capture(capture) == true) {
            return withContext(Dispatchers.IO) { capture.readBytes() }
        }
        return encode(blank(context))
    }

    /** A screen with nothing on it, at the size the tab last gave the device. */
    private fun blank(context: Context): Bitmap {
        val (width, height) = size ?: displaySize(context)
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            .also { VirtualWallpaper.draw(Canvas(it), width, height) }
    }

    /** With no tab ever opened the device is the size of this phone's screen, as its identity says. */
    private fun displaySize(context: Context): Pair<Int, Int> {
        val bounds = context.getSystemService(WindowManager::class.java)?.maximumWindowMetrics?.bounds
        if (bounds != null && bounds.width() > 0 && bounds.height() > 0) {
            return bounds.width() to bounds.height()
        }
        val metrics = context.resources.displayMetrics
        return metrics.widthPixels to metrics.heightPixels
    }

    private suspend fun encode(bitmap: Bitmap): ByteArray = withContext(Dispatchers.IO) {
        ByteArrayOutputStream(bitmap.width * bitmap.height / 8)
            .also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            .toByteArray()
            .also { bitmap.recycle() }
    }
}

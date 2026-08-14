package dev.jcode.vdevice

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Drawable
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.graphics.withTranslation
import kotlin.math.floor
import kotlin.math.max

/** One installed app as the launcher shows it: what it is, and the icon to draw for it. */
internal class LauncherApp(val app: VirtualDeviceApp, val icon: Drawable?)

/** Where one app's icon sits on the device's screen. */
internal class LauncherTile(val app: VirtualDeviceApp, val bounds: Rect)

/**
 * The virtual device's home screen, drawn onto the device's own screen rather than composed over it.
 *
 * This is the whole point of the class. The launcher used to be Compose laid over the `SurfaceView`,
 * which meant `adb shell screencap` answered a bare wallpaper: an agent could not see what was
 * installed, and could not tell an empty device from an app that had not drawn yet. Worse, drawing
 * it a second time for the capture would put the icons an agent *sees* in one place and the taps it
 * sends in another, since the two layouts would drift.
 *
 * So there is one layout ([tiles]) and one renderer ([draw]), and both the tab's surface and
 * [VirtualScreen]'s capture go through them. What the agent screenshots is, by construction, where
 * `input tap` lands — [hit] resolves a touch against the same rectangles.
 *
 * The consequence worth stating: only what belongs to the *device* is drawn here. JCode's own
 * "Install an app" button floats over the screen as IDE chrome and is deliberately absent from a
 * capture, the same way the tab's control bar is.
 *
 * Sizes are in dp against the display's density and never in sp: the device's screen must not
 * re-flow because the phone's font-scale setting changed, or two captures of one screen would
 * differ.
 */
internal object VirtualLauncher {

    private const val PADDING_DP = 12f
    private const val TOP_DP = 16f
    private const val HEADER_GAP_DP = 14f
    private const val CELL_MIN_DP = 88f
    private const val CELL_GAP_DP = 4f
    private const val ROW_GAP_DP = 10f
    private const val ICON_DP = 46f
    private const val ICON_LABEL_GAP_DP = 6f
    private const val HEADER_TEXT_DP = 14f
    private const val LABEL_TEXT_DP = 12f
    private const val PLACEHOLDER_TEXT_DP = 15f
    private const val LABEL_LINES = 2

    private const val PLACEHOLDER = "No app installed"

    /** Named so a driver can tell the launcher's nodes from a guest's at a glance. */
    private const val LAUNCHER_PACKAGE = "dev.jcode.vdevice.launcher"
    private const val LAUNCHER_CLASS = "dev.jcode.vdevice.Launcher"
    private const val ICON_CLASS = "dev.jcode.vdevice.LauncherIcon"
    private const val STATUS_BAR_CLASS = "dev.jcode.vdevice.StatusBar"

    /** Reads what is installed, with each app's own icon. Parses APKs — never call it on the UI thread. */
    fun load(context: Context): List<LauncherApp> = VirtualDeviceApps.list(context)
        .map { LauncherApp(it, VirtualDevice.icon(context, it.apkPath)) }

    /**
     * Where each app's tile sits, in device-screen pixels — the coordinates `screencap` shows and
     * `input tap` takes.
     */
    fun tiles(width: Int, height: Int, density: Float, apps: List<LauncherApp>): List<LauncherTile> {
        if (apps.isEmpty() || width <= 0 || height <= 0) return emptyList()
        val padding = PADDING_DP * density
        val gap = CELL_GAP_DP * density
        val available = width - padding * 2
        if (available <= 0f) return emptyList()

        val columns = max(1, floor((available + gap) / (CELL_MIN_DP * density + gap)).toInt())
        val cell = (available - gap * (columns - 1)) / columns
        val tileHeight = ICON_DP * density + ICON_LABEL_GAP_DP * density + labelHeight(density)
        val top = barHeight(density) + TOP_DP * density + HEADER_GAP_DP * density

        return apps.mapIndexed { index, entry ->
            val left = padding + (index % columns) * (cell + gap)
            val rowTop = top + (index / columns) * (tileHeight + ROW_GAP_DP * density)
            LauncherTile(
                app = entry.app,
                bounds = Rect(
                    left.toInt(),
                    rowTop.toInt(),
                    (left + cell).toInt(),
                    (rowTop + tileHeight).toInt(),
                ),
            )
        }
    }

    /** The app whose tile contains ([x], [y]), or null for a tap on the wallpaper. */
    fun hit(
        width: Int,
        height: Int,
        density: Float,
        apps: List<LauncherApp>,
        x: Float,
        y: Float,
    ): VirtualDeviceApp? = tiles(width, height, density, apps)
        .firstOrNull { it.bounds.contains(x.toInt(), y.toInt()) }
        ?.app

    /**
     * The home screen as `uiautomator dump` XML, so a driver can find an icon by name instead of
     * reading pixels out of a screenshot.
     *
     * An idle device is showing something tappable, so answering "nothing is running" would be a
     * lie — the same lie a capture used to tell by coming back empty. Each icon is one clickable
     * node whose `content-desc` is the package, which is what `am start` and `pm uninstall` take.
     */
    fun dump(width: Int, height: Int, density: Float, apps: List<LauncherApp>): String {
        val out = StringBuilder(1024)
        out.append(UiXml.HEADER).append(UiXml.OPEN)
        UiXml.node(
            out = out,
            depth = 1,
            index = 0,
            bounds = "[0,0][$width,$height]",
            className = LAUNCHER_CLASS,
            packageName = LAUNCHER_PACKAGE,
            selfClosing = false,
            text = if (apps.isEmpty()) PLACEHOLDER else "",
        )
        // The bar is on the screen whether or not anything is installed, so it is in the dump the
        // same way — a driver reading the device's state should not have to infer it from pixels.
        // It carries no text on the home screen: the bar reports the *app*, and with nothing running
        // there is no app to report. Naming the device there only repeated what the tab's own title
        // already says.
        UiXml.node(
            out = out,
            depth = 2,
            index = 0,
            bounds = "[0,0][$width,${barHeight(density).toInt()}]",
            className = STATUS_BAR_CLASS,
            packageName = LAUNCHER_PACKAGE,
            selfClosing = true,
        )
        if (apps.isNotEmpty()) {
            tiles(width, height, density, apps).forEachIndexed { index, tile ->
                UiXml.node(
                    out = out,
                    depth = 2,
                    index = index,
                    bounds = tile.bounds.let { "[${it.left},${it.top}][${it.right},${it.bottom}]" },
                    className = ICON_CLASS,
                    packageName = LAUNCHER_PACKAGE,
                    selfClosing = true,
                    text = tile.app.label,
                    contentDesc = tile.app.packageName,
                    clickable = true,
                    longClickable = true,
                )
            }
        }
        UiXml.close(out, 1)
        out.append(UiXml.CLOSE)
        return out.toString()
    }

    /** The device's screen with nothing running on it: wallpaper, identity, and what is installed. */
    fun draw(canvas: Canvas, width: Int, height: Int, density: Float, apps: List<LauncherApp>) {
        VirtualWallpaper.draw(canvas, width, height)
        if (width <= 0 || height <= 0) return
        drawStatusBar(canvas, width, density)

        if (apps.isEmpty()) {
            // Said on the device's own screen, so a capture of an empty device reads as empty rather
            // than as a screen that failed to draw.
            val placeholder = textPaint(PLACEHOLDER_TEXT_DP * density, 0x8CFFFFFF.toInt())
            val metrics = placeholder.fontMetrics
            canvas.drawText(
                PLACEHOLDER,
                width / 2f,
                height / 2f - (metrics.ascent + metrics.descent) / 2f,
                placeholder,
            )
            return
        }

        // Left-aligned on purpose: StaticLayout does the centring, and a centred paint under it
        // would offset every line a second time.
        val label = textPaint(LABEL_TEXT_DP * density, 0xD9FFFFFF.toInt(), Paint.Align.LEFT)
        val icons = apps.associateBy({ it.app.packageName }, { it.icon })
        tiles(width, height, density, apps).forEach { tile ->
            if (tile.bounds.top > height) return@forEach
            drawTile(canvas, tile, icons[tile.app.packageName], label, density)
        }
    }

    /**
     * The device's status bar, on the home screen.
     *
     * Same strip, same height, same palette as the one [VirtualStatusBar] puts over a running guest
     * — drawn with a canvas here because the home screen is drawn, not composed. That is what makes
     * the bar *persistent*: it is a property of the device rather than of whatever app happens to be
     * on it, so it does not appear when something starts and vanish when it stops.
     *
     * It carries the device's name and nothing else. There is no app to report the state of, and no
     * notifications to count: the guest process is what holds them, and with nothing running there
     * is no guest process.
     */
    private fun drawStatusBar(canvas: Canvas, width: Int, density: Float) {
        canvas.drawRect(
            0f,
            0f,
            width.toFloat(),
            barHeight(density),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = VirtualStatusBar.BAR_BACKGROUND },
        )
    }

    private fun barHeight(density: Float): Float = VirtualStatusBar.BAR_DP * density

    private fun drawTile(
        canvas: Canvas,
        tile: LauncherTile,
        icon: Drawable?,
        label: TextPaint,
        density: Float,
    ) {
        val size = (ICON_DP * density).toInt()
        val left = tile.bounds.centerX() - size / 2
        icon?.apply {
            setBounds(left, tile.bounds.top, left + size, tile.bounds.top + size)
            draw(canvas)
        }
        val text = StaticLayout.Builder
            .obtain(tile.app.label, 0, tile.app.label.length, label, tile.bounds.width())
            .setAlignment(Layout.Alignment.ALIGN_CENTER)
            .setMaxLines(LABEL_LINES)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setIncludePad(false)
            .build()
        canvas.withTranslation(
            tile.bounds.left.toFloat(),
            tile.bounds.top + size + ICON_LABEL_GAP_DP * density,
        ) {
            text.draw(this)
        }
    }

    private fun textPaint(
        size: Float,
        color: Int,
        align: Paint.Align = Paint.Align.CENTER,
    ) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size
        this.color = color
        textAlign = align
    }

    private fun labelHeight(density: Float): Float = textHeight(LABEL_TEXT_DP * density) * LABEL_LINES

    private fun textHeight(size: Float): Float =
        Paint().apply { textSize = size }.fontMetrics.let { it.descent - it.ascent }
}

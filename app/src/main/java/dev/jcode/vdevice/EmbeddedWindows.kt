package dev.jcode.vdevice

import android.graphics.Point
import android.graphics.Rect
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import android.util.Log
import android.view.Gravity
import android.view.SurfaceControl
import android.view.SurfaceControlViewHost
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import java.lang.reflect.Field
import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/** One of the guest's child windows, and where it sits inside the tab. */
internal class EmbeddedWindow(val view: View, val frame: Rect)

/**
 * Dialogs, popup menus and drop-downs inside an embedded guest.
 *
 * A `SurfaceControlViewHost` already owns a `WindowlessWindowManager`, and `WindowManagerGlobal`
 * will route a window into it — no permission and no real activity token involved — as long as the
 * window's `LayoutParams.token` is the host's own window token. That token is [token]: `Dialog`
 * takes it from the activity window's app token, which [GuestHooks.hostWindowIn] sets, while
 * `PopupWindow`, `Spinner` drop-downs and option menus already read it off their anchor view.
 *
 * What the windowless session does *not* do is lay a window out. It answers every relayout with
 * `frame = (0, 0, attrs.width, attrs.height)` and never moves the surface, so a WRAP_CONTENT
 * window — which every dialog and every popup is — comes back with a -2x-2 frame, is given a 1x1
 * surface, draws nothing, and would sit in the tab's top-left corner if it did. [install] wraps the
 * host's `IWindowSession` to do the two things a real window manager would: resolve
 * WRAP_CONTENT/MATCH_PARENT against the tab, and place the window's surface by its gravity.
 *
 * All of this reflection is on greylisted members (`ViewRootImpl.mAttachInfo`,
 * `ViewRootImpl.mWindowSession`, `WindowManagerGlobal.mViews`/`mParams`), and every step is
 * guarded: [install] answering null leaves the guest exactly as it was before, refusing dialogs
 * outright rather than showing them wrong.
 */
internal class EmbeddedWindows private constructor(
    val token: IBinder,
    private val root: SurfaceControl,
) {

    @Volatile
    private var width = 0

    @Volatile
    private var height = 0

    /** The attributes each child asked for, so a relayout that passes none can still be resolved. */
    private val requests = HashMap<IBinder, WindowManager.LayoutParams>()

    /** The layer each child window is moved by — see [Session.position]. */
    private val frames = HashMap<IBinder, SurfaceControl>()

    // Resolved once: [children] runs on every relayed touch and key.
    private val windowManagerGlobal: Any? by lazy {
        val holder = HiddenApi.classOrNull("android.view.WindowManagerGlobal") ?: return@lazy null
        HiddenApi.method(holder, "getInstance")?.let { runCatching { it.invoke(null) }.getOrNull() }
    }

    private val viewsField by lazy { windowManagerGlobal?.let { HiddenApi.field(it.javaClass, VIEWS) } }

    private val paramsField by lazy { windowManagerGlobal?.let { HiddenApi.field(it.javaClass, PARAMS) } }

    fun resize(width: Int, height: Int) {
        this.width = width
        this.height = height
    }

    /** Drops the layers this owns; the host's own release takes the windows themselves. */
    fun release() {
        frames.values.forEach { it.release() }
        frames.clear()
        requests.clear()
        root.release()
    }

    /**
     * The guest's child windows, bottom first — so the last one is the one input belongs to.
     *
     * They are read back out of `WindowManagerGlobal` rather than tracked here because that is
     * where the *view* is: the session only ever sees an `IWindow`, and input has to reach a view.
     */
    fun children(): List<EmbeddedWindow> {
        val views = list(viewsField) ?: return emptyList()
        val params = list(paramsField) ?: return emptyList()
        val children = ArrayList<EmbeddedWindow>()
        for (index in 0 until minOf(views.size, params.size)) {
            val view = views[index] as? View ?: continue
            val attrs = params[index] as? WindowManager.LayoutParams ?: continue
            if (attrs.token !== token) continue
            children += EmbeddedWindow(view, place(attrs, view.width, view.height))
        }
        return children
    }

    private fun list(field: Field?): List<*>? {
        val global = windowManagerGlobal ?: return null
        return field?.let { runCatching { it.get(global) }.getOrNull() } as? List<*>
    }

    /** Where a [width] x [height] window with these attributes belongs inside the tab. */
    private fun place(attrs: WindowManager.LayoutParams, width: Int, height: Int): Rect {
        val frame = Rect()
        Gravity.apply(
            attrs.gravity,
            width,
            height,
            Rect(0, 0, this.width, this.height),
            attrs.x,
            attrs.y,
            frame,
        )
        return frame
    }

    private fun measure(request: Int, measured: Int, available: Int): Int = when {
        request == ViewGroup.LayoutParams.MATCH_PARENT -> available
        request >= 0 -> request.coerceAtMost(available)
        else -> measured.coerceIn(1, available)
    }

    /**
     * The [WindowManager.LayoutParams] slot of a session call, taken from the method's signature
     * rather than its arguments: a relayout that reuses the window's attributes passes null there,
     * and that is exactly the call whose attributes have to be filled in.
     */
    private fun attrsSlot(method: Method): Int =
        method.parameterTypes.indexOfFirst { it == WindowManager.LayoutParams::class.java }

    private inner class Session(private val real: Any) : InvocationHandler {

        override fun invoke(proxy: Any?, method: Method, args: Array<Any?>?): Any? {
            val arguments = args ?: emptyArray()
            val origin = runCatching { intercept(method, arguments) }
                .onFailure { Log.w(TAG, "cannot lay out an embedded window", it) }
                .getOrNull()
            val result = try {
                method.invoke(real, *arguments)
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            // After the call, because the surface only exists once the session has built it.
            origin?.let {
                runCatching { position(arguments, it) }
                    .onFailure { failure -> Log.w(TAG, "cannot place an embedded window", failure) }
            }
            return result
        }

        /**
         * Rewrites a child's relayout request into concrete pixels and answers with where its
         * surface should go. Null means "leave this one alone" — the host's own root window, a call
         * that is not a relayout, or a window this cannot resolve.
         */
        private fun intercept(method: Method, args: Array<Any?>): Point? {
            val slot = attrsSlot(method)
            when {
                method.name.startsWith(ADD) -> {
                    remember(args, slot)
                    return null
                }

                method.name == REMOVE -> {
                    forget(args)
                    return null
                }

                method.name != RELAYOUT || slot < 0 -> return null
            }
            if (width <= 0 || height <= 0) return null
            val window = args.getOrNull(0) as? IInterface ?: return null
            if (window.asBinder() === token) return null
            val attrs = remember(args, slot) ?: requests[window.asBinder()] ?: return null

            // The two ints after the attributes are the size the view measured itself at, which is
            // the only thing WRAP_CONTENT can be resolved against out here.
            val measured = args.drop(slot + 1).filterIsInstance<Int>()
            val resolved = WindowManager.LayoutParams().apply {
                copyFrom(attrs)
                width = measure(attrs.width, measured.getOrElse(0) { 0 }, this@EmbeddedWindows.width)
                height = measure(attrs.height, measured.getOrElse(1) { 0 }, this@EmbeddedWindows.height)
            }
            args[slot] = resolved

            // The session answers every relayout with a frame at the origin, so where the window
            // actually lands is left to [position].
            val frame = place(resolved, resolved.width, resolved.height)
            val insets = surfaceInsets(attrs)
            return Point(frame.left - insets.x, frame.top - insets.y)
        }

        /**
         * How far a window's surface starts before its frame.
         *
         * `ViewRootImpl` asks for `frame + surfaceInsets` — room for the window's drop shadow — and
         * draws the view inset into it, so a surface put at the frame's own origin shows its content
         * that much too far in. `LayoutParams.surfaceInsets` is denied to this app, but the value is
         * in the string `LayoutParams` prints itself as; failing to find it there only costs the
         * shadow's worth of offset back.
         */
        private fun surfaceInsets(attrs: WindowManager.LayoutParams): Point {
            val match = SURFACE_INSETS.find(attrs.toString()) ?: return Point()
            return Point(match.groupValues[1].toInt(), match.groupValues[2].toInt())
        }

        private fun remember(args: Array<Any?>, slot: Int): WindowManager.LayoutParams? {
            val window = args.getOrNull(0) as? IInterface ?: return null
            val attrs = args.getOrNull(slot) as? WindowManager.LayoutParams ?: return null
            return WindowManager.LayoutParams().apply { copyFrom(attrs) }
                .also { requests[window.asBinder()] = it }
        }

        private fun forget(args: Array<Any?>) {
            val window = (args.getOrNull(0) as? IInterface)?.asBinder() ?: return
            requests.remove(window)
            frames.remove(window)?.release()
        }

        /**
         * Moves a child window's surface to [origin].
         *
         * Not by positioning the window's own layer: a hardware-accelerated window is fed through a
         * `BLASTBufferQueue`, which sets that layer's destination frame from the buffer on every
         * frame and wipes anything set out here — measured on Android 13, where the surface stayed
         * in the tab's top-left corner however often it was moved. The layer is reparented into a
         * layer this owns instead, and that one is what moves; nothing else touches it.
         */
        private fun position(args: Array<Any?>, origin: Point) {
            val window = (args.getOrNull(0) as? IInterface)?.asBinder() ?: return
            val surface = args.firstOrNull { it is SurfaceControl } as? SurfaceControl ?: return
            if (!surface.isValid) return
            val frame = frames.getOrPut(window) {
                SurfaceControl.Builder().setName(FRAME_LAYER).setParent(root).build()
            }
            SurfaceControl.Transaction().use {
                it.reparent(surface, frame)
                    .setVisibility(frame, true)
                    .setPosition(frame, origin.x.toFloat(), origin.y.toFloat())
                    .apply()
            }
        }
    }

    companion object {

        private const val ADD = "addToDisplay"
        private const val RELAYOUT = "relayout"
        private const val REMOVE = "remove"
        private const val VIEWS = "mViews"
        private const val PARAMS = "mParams"
        private const val FRAME_LAYER = "JCodeEmbeddedWindow"

        /** `surfaceInsets=Rect(left, top - right, bottom)` in `LayoutParams.toString()`. */
        private val SURFACE_INSETS = Regex("""surfaceInsets=Rect\((-?\d+), (-?\d+) - """)

        /**
         * Turns child windows on for a host whose view has just been set, answering with the token
         * they have to carry — or null when a member this needs is denied.
         *
         * `ViewRootImpl.setView` makes itself the container's parent, and that is the only handle on
         * the host's view root this app can have: `SurfaceControlViewHost` declares no non-SDK
         * member it may touch — measured on Android 13, even `getWindowToken()` is denied — while
         * `ViewRootImpl`'s own `mAttachInfo` and `mWindowSession` are greylisted and answer.
         */
        fun install(host: SurfaceControlViewHost, container: View, width: Int, height: Int): EmbeddedWindows? {
            val root = rootSurface(host) ?: return null
            val viewRoot = container.parent ?: return null
            val attachInfo = HiddenApi.field(viewRoot.javaClass, "mAttachInfo")
                ?.let { runCatching { it.get(viewRoot) }.getOrNull() } ?: return null
            val window = HiddenApi.field(attachInfo.javaClass, "mWindow")
                ?.let { runCatching { it.get(attachInfo) }.getOrNull() } as? IInterface ?: return null
            val sessionField = HiddenApi.field(viewRoot.javaClass, "mWindowSession") ?: return null
            val session = runCatching { sessionField.get(viewRoot) }.getOrNull() as? IInterface ?: return null
            val iface = HiddenApi.classOrNull("android.view.IWindowSession") ?: return null

            val windows = EmbeddedWindows(window.asBinder(), root)
            windows.resize(width, height)
            val proxy = Proxy.newProxyInstance(
                EmbeddedWindows::class.java.classLoader,
                arrayOf(iface),
                windows.Session(session),
            )
            return runCatching { sessionField.set(viewRoot, proxy); windows }
                .onFailure { Log.w(TAG, "the guest cannot host child windows in this tab", it) }
                .getOrNull()
        }

        /**
         * The host's own root layer, which the per-window layers hang off.
         *
         * `SurfacePackage.getSurfaceControl()` is denied, but the package is a `Parcelable` whose
         * first field is that very `SurfaceControl` — so it is read back out through a `Parcel`,
         * on nothing but public API.
         */
        private fun rootSurface(host: SurfaceControlViewHost): SurfaceControl? {
            val surfacePackage = host.surfacePackage ?: return null
            val parcel = Parcel.obtain()
            return try {
                surfacePackage.writeToParcel(parcel, 0)
                parcel.setDataPosition(0)
                SurfaceControl.CREATOR.createFromParcel(parcel).takeIf { it.isValid }
            } catch (t: Throwable) {
                Log.w(TAG, "cannot reach the host's own layer", t)
                null
            } finally {
                parcel.recycle()
                surfacePackage.release()
            }
        }
    }
}

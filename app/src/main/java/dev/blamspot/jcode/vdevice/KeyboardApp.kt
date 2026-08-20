package dev.blamspot.jcode.vdevice

import android.content.Context
import android.os.Handler
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import java.lang.reflect.Method

/**
 * The device's keyboard app, loaded and asked for its keys.
 *
 * The keyboard is an ordinary guest — `tools/vdevice-keyboard`, bundled like the Camera and Files
 * apps — and it stays one. It is not put on the embedded back stack, because the one thing a
 * keyboard must never do is what starting an activity would do: pause the app being typed into. So
 * the container loads it, asks it for a `View`, and hosts that over whatever is running, the way a
 * phone hosts an IME's window over the app it is serving.
 *
 * ### What crosses between the two
 *
 * Nothing but framework types, and that is not a style choice. A guest's class loader is parented to
 * the **boot** loader rather than to JCode's (see [GuestLoader]), so a type declared in either place
 * is a different class on the other side. `Context`, `Handler`, `View`, `InputConnection` and
 * `EditorInfo` are all loaded by the boot loader, so they are the same class in both — which is
 * exactly the set this contract is written in.
 *
 * That constraint turns out to be the design rather than a tax on it. `InputConnection` is the
 * platform's own editing contract, the object a `TextView` hands a real IME, so the container has no
 * protocol to invent: it passes the focused field's connection straight through and the keyboard
 * edits with it exactly as any IME would. Text arrives as text — accents, emoji and all — instead of
 * being squeezed through `KeyCharacterMap`, which has no key for most of it.
 *
 * The reverse direction is a [Handler], because `Message` is a framework type and an interface would
 * not be: [MSG_HIDE] for the keyboard's own hide key, and [MSG_KEY] carrying a key code in `arg1`
 * for the one case an `InputConnection` cannot express — Enter on a single-line field that asked for
 * no editor action, which on a phone is delivered as a key.
 *
 * Every reflective step is guarded and [load] answers null rather than throwing: a device whose
 * keyboard APK is missing or too old should be a device with no keyboard, not a device that cannot
 * run an app.
 */
internal class KeyboardApp private constructor(
    private val instance: Any,
    val view: View,
    private val startInput: Method,
    private val finishInput: Method,
) {

    fun startInput(connection: InputConnection, info: EditorInfo) {
        runCatching { startInput.invoke(instance, connection, info) }
            .onFailure { Log.w(TAG, "the keyboard refused a field", it) }
    }

    fun finishInput() {
        runCatching { finishInput.invoke(instance) }
            .onFailure { Log.w(TAG, "the keyboard refused to let go of a field", it) }
    }

    companion object {

        const val PACKAGE = "dev.jcode.vdevice.keyboard"

        /** The one class the container talks to — see `KeyboardHost` in the app. */
        private const val HOST = "$PACKAGE.KeyboardHost"

        /** The person pressed the keyboard's own hide key. */
        const val MSG_HIDE = 1

        /** `arg1` is a key code the container should deliver the ordinary way. */
        const val MSG_KEY = 2

        /**
         * Loads the keyboard and builds its view, or answers null with a line in the device's log.
         *
         * The context is the keyboard guest's own, so its resources, theme and class loader are the
         * app's — a view built against JCode's would resolve the keyboard's drawables out of the
         * IDE's resource table, which is the same trap the guest class loader exists to avoid.
         */
        fun load(host: Context, messages: Handler): KeyboardApp? {
            val apk = VirtualDeviceApps.apk(host, PACKAGE) ?: run {
                VirtualDeviceLog.append(host, 'W', TAG, "this device has no keyboard installed")
                return null
            }
            return runCatching {
                val guest = GuestLoader.load(host, apk.absolutePath)
                val type = guest.classLoader.loadClass(HOST)
                val instance = type
                    .getConstructor(Context::class.java, Handler::class.java)
                    .newInstance(guest.appContext, messages)
                val view = type.getMethod("view").invoke(instance) as View
                KeyboardApp(
                    instance = instance,
                    view = view,
                    startInput = type.getMethod(
                        "startInput",
                        InputConnection::class.java,
                        EditorInfo::class.java,
                    ),
                    finishInput = type.getMethod("finishInput"),
                )
            }.onFailure {
                Log.w(TAG, "cannot load the device's keyboard", it)
                VirtualDeviceLog.append(host, 'W', TAG, "the device's keyboard would not load: ${it.message}")
            }.getOrNull()
        }
    }
}

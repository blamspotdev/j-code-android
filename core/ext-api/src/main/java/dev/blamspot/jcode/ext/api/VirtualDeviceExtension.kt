package dev.blamspot.jcode.ext.api

import android.content.Intent
import android.os.IBinder

/**
 * The contract between JCode and the extension that provides its **virtual device**.
 *
 * The device used to be part of the app. It is not any more: everything that makes an APK run inside
 * JCode — the container, the framework hooks, the launcher, the status bar, the adb daemon's device
 * end — ships in the Android Dev Pack, and what stays behind is this interface plus the four manifest
 * components that cannot come from anywhere else (see below). A JCode with no Android Dev Pack has no
 * virtual device, the same way it has no Kotlin completions without the Kotlin pack.
 *
 * **Why four components stay in the host.** An installed extension is a directory of files that a
 * [dalvik.system.DexClassLoader] opens inside a process that is already running. It cannot contribute
 * to `AndroidManifest.xml`, and the device needs four things only a manifest can declare:
 *
 *  - the `:guest` process, so the container's swapped `Instrumentation` and rewritten `Build` stay
 *    out of the IDE's heap — declared on `GuestActivity` and `GuestSessionService`;
 *  - `GuestActivity` itself, which exists only to be an `ActivityInfo` template for a package the
 *    real `PackageManager` has never heard of;
 *  - two `ContentProvider` authorities, which are `${applicationId}`-scoped and resolved by the
 *    system from the manifest long before any extension could be consulted;
 *  - the guest's `<uses-permission>` set, which is fixed at install time.
 *
 * Each of those is a **stub**: it holds no device logic and delegates to whatever the installed pack
 * supplies. With no pack installed they answer emptily rather than failing, because the phone's
 * Files app may query a `DocumentsProvider` whether or not the user ever installed a dev pack.
 */
interface JCodeVirtualDevice {

    /**
     * Hand the device the workbench it is running inside. Called once, before anything else.
     *
     * Separate from construction because the pack is instantiated by the same loader that
     * instantiates its UI half, which knows nothing about devices; this is where the two meet.
     *
     * [context] is JCode's application context — the device's storage, its cache and its package
     * name all come from it. Passed rather than left for the pack to find, because the pack's other
     * entry points are a composition and a bound service, and neither of those has happened yet the
     * first time something asks for a device.
     *
     * **This is also where the device is emptied.** Everything on it lives in JCode's cache and does
     * not survive a restart, so something has to do the emptying and exactly one thing may — a second
     * pass would wipe an install that landed between the two. That used to be a race between the
     * workbench and the adb daemon, each calling a `resetOnStart` that had to be idempotent to be
     * safe. With one loader there is one `attach`, so the ordering hazard is gone rather than guarded.
     */
    fun attach(host: VirtualDeviceHost, context: android.content.Context)

    /**
     * Show [apkPath] on the device, opening or focusing its tab.
     *
     * A null [apkPath] opens the device with nothing on it — its home screen, which is a legitimate
     * destination: the launcher lists what is already installed.
     *
     * [activityClass] names which of the APK's activities to start, for `am start -n`; null means the
     * launcher activity. [run] distinguishes running an APK straight from disk from installing it
     * first — the one-off run the tab's install sheet offers.
     *
     * Errors are reported through [VirtualDeviceHost], not returned. The caller is a finished build
     * or an adb command, and neither has anywhere better to put "that file is not a readable APK"
     * than the place the pack would put it anyway.
     */
    fun requestOpen(apkPath: String?, activityClass: String? = null, run: Boolean = false)

    /** Stop whatever is running, leaving the device up. */
    fun requestStop()

    /**
     * End the device, `:guest` process and all.
     *
     * Called when the backend service goes down and when the user swipes the session away in the
     * Task Manager. Stopping a device that was never started is not an error.
     */
    fun shutdown()

    /**
     * The device's own content providers, by role — `"files"` or `"settings"`.
     *
     * The host declares the two authorities because `${applicationId}`-scoped authorities are
     * resolved from the manifest, and answers them with stubs that forward here. Returns null for a
     * role this device does not serve, which the stub reports as an empty provider rather than a
     * crash: the phone's Files app may query the device's `DocumentsProvider` whether or not a pack
     * is installed, and a `ContentProvider` that throws takes DocumentsUI down with it.
     *
     * Called lazily, on the stub's first real query — never from `ContentProvider.onCreate`, which
     * runs before `Application.onCreate` and would load the pack's whole archive at every app start.
     */
    fun provider(role: String): android.content.ContentProvider?

    /** Roles [provider] is asked for. */
    object Roles {
        /** The device's storage, as `content://` URIs — its half of a file picker. */
        const val FILES = "files"

        /** How the device's own Settings app reads and changes the device's settings. */
        const val SETTINGS = "settings"
    }

    /**
     * Start the device's own adb daemon, binding its socket inside [rootfs].
     *
     * Returns what an adb client must be given to reach it -- `adb connect <this>` -- or null if
     * it could not start. The daemon is the pack's, like the banner it answers with and the
     * services it serves: everything the device *is* belongs to whoever provides the device.
     *
     * What the host still decides is *whether* and *where*: it owns the setting, and it owns the
     * Linux runtime whose rootfs the socket has to live inside to be reachable from it. It also
     * attaches the runtime's adb client to the spec this returns, because that client is the
     * runtime's rather than the device's.
     *
     * Defaulted so a pack built before this existed still satisfies the interface; it simply has
     * no adb, which is what it had.
     */
    suspend fun startAdb(rootfs: java.io.File): String? = null

    /** Stop it again, releasing the socket. */
    fun stopAdb() {}
}

/**
 * The four manifest components the host declares on the device's behalf, by name.
 *
 * The pack has to reach two of them — it binds the guest service, and it builds the `ActivityInfo`
 * template out of the guest activity — and it cannot reference either class, because both live in
 * the app. Naming them here rather than spelling the strings into the pack keeps the app free to
 * move them: a rename that forgets this file stops compiling, where a rename that forgets a string
 * literal in an extension fails at runtime on a user's phone.
 *
 * The authorities are suffixes, not whole authorities: they are `${applicationId}`-scoped, so the
 * package name in front of them is the JCode build the pack finds itself inside.
 */
object VirtualDeviceComponents {

    /** Bound by the pack's IDE half; answers with the pack's own `:guest` binder. */
    const val GUEST_SERVICE = "dev.blamspot.jcode.vdevice.GuestSessionService"

    /**
     * The declared activity a guest's own activities are instantiated from.
     *
     * Never started. A guest activity belongs to a package the real `PackageManager` has never heard
     * of, so there is no `ActivityInfo` to build one from, and this is that template.
     */
    const val GUEST_ACTIVITY = "dev.blamspot.jcode.vdevice.GuestActivity"

    /** Appended to the package name: the device's storage, as `content://` URIs. */
    const val FILES_AUTHORITY = ".vdevice.files"

    /** Appended to the package name: how the device's Settings app reaches the device's settings. */
    const val SETTINGS_AUTHORITY = ".vdevice.settings"
}

/**
 * The `:guest`-process half of the same extension, named by `entry.native.guest` in the manifest.
 *
 * Loaded by the host's `GuestSessionService` stub out of the same payload as [JCodeVirtualDevice],
 * in a different process, with JCode's own class loader as parent — the guest *app*'s loader is the
 * boot-parented one, and this is not that.
 *
 * Kept apart from [JCodeVirtualDevice] because the two never coexist: this side installs framework
 * hooks that would take the IDE down with them, and the IDE side draws Compose that has no business
 * in a process with a rewritten `Build`.
 */
interface JCodeVirtualDeviceGuest {

    /**
     * Install the container's framework hooks into this process, once.
     *
     * [service] is the manifest stub that owns this process — what the container binds its view tree
     * and its lifecycle to. [resources] is a context whose `AssetManager` has the pack's own archive
     * attached, which is where the device's status bar, its quick-settings icons and its permission
     * prompt come from; the stub cannot supply those, because they are not the app's resources any
     * more.
     *
     * Returns false when the platform has moved under them — a hidden member that is now denied, a
     * hook point that no longer exists. The stub reports that as a device that cannot start rather
     * than letting a half-hooked process pretend to be one.
     */
    fun install(service: android.app.Service, resources: android.content.Context): Boolean

    /** The binder the tab talks to the container over. [intent] is the one the workbench bound with. */
    fun bind(intent: Intent?): IBinder?

    /** Tear the container down; the stub's `onUnbind`. */
    fun unbind()
}

/**
 * What the virtual device may ask of the workbench.
 *
 * Deliberately tiny. The pack runs in JCode's process with JCode's `Context`, so nearly everything a
 * device needs it can simply do; what it cannot do is navigate the workbench, which is the only
 * thing here that is not available from a `Context`.
 */
interface VirtualDeviceHost {

    /**
     * Open the device's editor tab, or focus it if it is already open.
     *
     * [title] is what the tab should read — the running app's name, which only the device knows. A
     * second request can name a different app (`adb shell am start` does) and the tab is reused, so
     * the name has to travel with every request rather than being set once. Null keeps whatever the
     * tab is already called.
     */
    fun openDeviceTab(title: String? = null)

    /** The same for the hardware bench, which opens beside the device rather than over it. */
    fun openHardwareTab()

    /** A transient message in the workbench. */
    fun snackbar(message: String)

    /**
     * A line in the Output pane, where a build's own log already is.
     *
     * A finished build that goes straight to the device writes both there; splitting them would put
     * half the story somewhere the user is not looking.
     */
    fun output(line: String, error: Boolean = false)
}

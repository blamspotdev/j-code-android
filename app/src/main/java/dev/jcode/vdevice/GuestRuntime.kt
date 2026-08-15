package dev.jcode.vdevice

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.webkit.WebView

/**
 * The container itself, living in the `:guest` process: it installs the hooks in [GuestHooks] and
 * then decides what each of them should do.
 *
 * A guest activity belongs to a package the system has never heard of, so there is no `ActivityInfo`
 * to build one from. [GuestActivity] is that template and nothing more: an intent naming it carries
 * the real guest's identity in extras, [onLaunchActivity] rewrites it to name the guest activity,
 * [newActivity] instantiates that class out of the guest's class loader, and [bind] hands the
 * instance a [GuestContext] — all before `onCreate` runs.
 *
 * [embed] does that without the system: it builds the activity here so the device-sandbox
 * editor tab can host its decor view, and takes over driving the lifecycle in exchange.
 */
internal object GuestRuntime {

    const val EXTRA_APK = "dev.jcode.vdevice.apk"
    const val EXTRA_ACTIVITY = "dev.jcode.vdevice.activity"

    /** Embedded-activity id, the `Activity.getId()` a system launch would never produce. */
    private const val EMBEDDED_ID = "jcode-embedded"

    /** Keeps the guest's WebView data out of JCode's, which already holds the lock on its own. */
    internal const val GUEST_WEBVIEW_SUFFIX = "jcode-guest"

    private class Target(val guest: LoadedGuest, val activityClass: String)

    @Volatile
    var isInstalled = false
        private set

    private lateinit var host: Context
    private var instrumentation: GuestInstrumentation? = null
    private var activityThread: Any? = null

    /** Set while a device-sandbox tab is showing this process, so intra-guest navigation is hosted
     *  in the tab. Returns true when it took the launch. */
    @Volatile
    private var embeddedLauncher: ((Intent) -> Boolean)? = null

    /** Set alongside [embeddedLauncher]: told when an embedded activity has called `finish()`. */
    @Volatile
    private var embeddedFinisher: (() -> Unit)? = null

    /** Set alongside [embeddedLauncher]: told when Back on an embedded activity reached the server. */
    @Volatile
    private var embeddedBackHandler: (() -> Unit)? = null

    /** The guest whose intents outgoing `startActivity` calls should be redirected for. */
    @Volatile
    private var active: LoadedGuest? = null

    /** Set while [embed] is building an activity, which is what tells [created] the two apart. */
    private var embedding = false

    /**
     * The embedded activity currently on the device's screen.
     *
     * Tracked here rather than asked of the tab because the two calls that decide it already come
     * through this object — [resumeEmbedded] for whatever has just come to the front, and
     * [destroyEmbedded] for whatever has just gone. [GuestPermissions] needs it: a permission result
     * is delivered to an activity, and the container has to know which one asked.
     */
    @Volatile
    private var foreground: Activity? = null

    /** The activity a result the container answered itself should be handed to, if there is one. */
    fun foregroundActivity(): Activity? = foreground

    @Synchronized
    fun install(context: Context) {
        if (isInstalled) return
        host = context.applicationContext
        VirtualIdentity.apply(Application.getProcessName())
        claimWebViewDirectory()

        val activityThread = GuestHooks.currentActivityThread()
            ?: throw VirtualDeviceException("no ActivityThread in this process")
        this.activityThread = activityThread
        instrumentation = GuestHooks.installInstrumentation(activityThread)
            ?: throw VirtualDeviceException("cannot replace ActivityThread.mInstrumentation")

        GuestPermissions.install(host)
        GuestDocuments.install(host)
        DeviceIntents.install(host)
        // Before any guest exists, which is the whole requirement: the framework builds one
        // LocationManager per context and caches it, so the service has to be in place before the
        // first one is asked for.
        val location = GuestLocation.install(host)
        // Same requirement and the same seam: a ConnectivityManager is built once per context and
        // caches its binder, so the replacement has to be in place before the first guest context.
        val network = GuestNetwork.install(host)
        val navigation = GuestHooks.installStartActivityHook(::rewriteOutgoing)
        val packages = GuestPackageHook.install(host.packageManager)
        val notifications = GuestNotificationHook.install()
        val intents = GuestActivityManagerHook.install(host.packageName)
        installCrashHandler()
        VirtualDeviceLog.captureStandardStreams(host)
        // Before anything a guest does, so the device's log holds the whole of a session rather than
        // starting once something has already gone wrong.
        VirtualDeviceLog.captureProcessLog(host)
        isInstalled = true
        Log.i(
            TAG,
            "hooks installed: instrumentation=true navigation=$navigation " +
                "packages=$packages notifications=$notifications intents=$intents " +
                "location=$location network=$network",
        )
        VirtualDeviceLog.append(host, 'I', TAG, "container ready in ${Application.getProcessName()}")
    }

    /**
     * Gives `:guest` a WebView data directory of its own.
     *
     * WebView takes an exclusive lock on its data directory and refuses to load in a second process
     * of the same app without one — and JCode's own process, which is full of WebViews, always gets
     * there first. So a guest that touches a WebView **at all** died on:
     *
     * ```
     * java.lang.RuntimeException: Using WebView from more than one process at once with the same
     * data directory is not supported. … Current process dev.jcode.debug:guest, lock owner
     * dev.jcode.debug
     * ```
     *
     * That is not a niche case: ad SDKs, sign-in flows, Cordova and Ionic apps, and anything with an
     * in-app browser all reach for one. Measured on CPU-Z, whose Mobile Ads provider loads WebView
     * from `Application.onCreate` — the crash killed `:guest`, and with it the activity JCode was
     * showing.
     *
     * `setDataDirectorySuffix` is public API from API 28 and must run before WebView is used in the
     * process, which is what makes this the first thing [install] does.
     */
    private fun claimWebViewDirectory() {
        runCatching { WebView.setDataDirectorySuffix(GUEST_WEBVIEW_SUFFIX) }
            .onFailure { Log.w(TAG, "guest WebViews may not work: $it") }
    }

    /**
     * Records what killed a guest.
     *
     * A crash in `:guest` goes to the system log, which no app on this platform can read back — so
     * without this the device could show that an app died and never say why. The previous handler
     * still runs, so the process dies exactly as it would have; this only writes the trace down
     * first, where `adb logcat` against the virtual device can reach it.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                VirtualDeviceLog.append(
                    context = host,
                    level = 'E',
                    tag = "AndroidRuntime",
                    message = "FATAL EXCEPTION: ${thread.name}\n" +
                        "Process: ${Application.getProcessName()}, guest: ${active?.packageName ?: "none"}\n" +
                        error.stackTraceToString(),
                )
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /**
     * Creates a guest activity with no window of its own, for the device-sandbox editor tab.
     *
     * The system will not put a guest activity on a display we own — `setLaunchDisplayId` is refused
     * without the signature|privileged `ACTIVITY_EMBEDDING` permission, even for our own
     * `allowEmbedded` activity on our own virtual display — so the container asks the system for no
     * activity at all and hands only the resulting `Window`'s decor view to a
     * `SurfaceControlViewHost`.
     *
     * What builds it is `Instrumentation.newActivity`, which is *public SDK* and performs the same
     * `Activity.attach` `performLaunchActivity` would. `ActivityThread.startActivityNow` — the entry
     * point `LocalActivityManager` uses for exactly this — is not an option: it is filtered out of
     * `ActivityThread`'s declared methods entirely at `targetSdk` 33, measured on Android 13, so it
     * is denied rather than greylisted and there is nothing to reflect at.
     *
     * The system drives none of the resulting activity's lifecycle — see [resumeEmbedded]. Its child
     * windows are hosted by [windowToken], the token of the `SurfaceControlViewHost` the decor view
     * is going into; without one, `Dialog`, `PopupWindow` and option menus have no window to attach
     * to and the window manager refuses them.
     */
    fun embed(apkPath: String, activityClass: String?, windowToken: IBinder?): Activity {
        val guest = GuestLoader.load(host, apkPath)
        active = guest
        val target = activityClass?.takeIf { guest.activities.containsKey(it) } ?: guest.launchActivity
        return embed(stubIntent(guest, target), windowToken)
    }

    /** Builds an embedded activity from a stub intent — the shape [rewriteOutgoing] hands its host. */
    fun embed(stub: Intent, windowToken: IBinder?): Activity {
        val instrumentation = instrumentation ?: throw VirtualDeviceException("the container is not installed")
        val component = stub.component ?: throw VirtualDeviceException("no stub component")
        val info = host.packageManager.getActivityInfo(component, 0)
        // The same rewrite the LAUNCH_ACTIVITY hook applies: guest component, guest resource ids, and
        // above all theme 0, so no theme is built against JCode's resources before bind() runs.
        onLaunchActivity(stub, info)
        val target = resolve(stub) ?: throw VirtualDeviceException("$stub carries no guest identity")
        // The tab is the only window shape on offer, so an activity that declares itself
        // unresizeable or pins an orientation has to give that up here — see GuestWindow.
        target.guest.activities[target.activityClass]?.let(GuestWindow::makeResizable)
        GuestWindow.makeResizable(info)

        // Before newActivity, not after. `ActivityThread` builds an app's Application in
        // handleBindApplication, long before it instantiates any activity, and apps rely on that
        // ordering far more than they say: a field initialiser or a static <clinit> reached from the
        // activity's *constructor* routinely reads a context some holder captured in
        // Application.onCreate. Creating it inside bind() — which the framework only reaches on the
        // way into onCreate — is one step too late. Measured on MiXplorer:
        //
        //   ExceptionInInitializerError at libs.v04.<clinit>
        //   Caused by: NullPointerException: Context.getResources() on a null object reference
        //
        // — its static holder was still null because the constructor had beaten the Application to
        // it, and the activity could not even be built.
        ensureApplication(target.guest)

        // Registered before the activity is built: the guest can reach ActivityClient from its own
        // onCreate, and a token the hook has not heard of yet is one the server rejects.
        val token = Binder().also(GuestActivityClient::register)
        val activity = instrumentation.newActivity(
            target.guest.classLoader.loadClass(target.activityClass),
            host,
            token,
            null,
            stub,
            info,
            target.guest.labelOf(target.activityClass),
            null,
            EMBEDDED_ID,
            null,
        )
        GuestHooks.adoptActivityThread(activity, activityThread)
        GuestHooks.hostWindowIn(activity, windowToken)
        // Runs through GuestInstrumentation, so bind() still lands between attach and onCreate.
        embedding = true
        try {
            instrumentation.callActivityOnCreate(activity, null)
        } finally {
            embedding = false
        }
        return activity
    }

    /** Whether the device lets [packageName] keep running once it is not the app on the screen. */
    fun mayRunInBackground(packageName: String): Boolean =
        runCatching { VirtualDevicePolicy.backgroundAllowed(host, packageName) }.getOrDefault(false)

    /**
     * Ends what the active guest is still hosting: its services, its bound connections, its
     * providers. Its code stays loaded, so reopening it is a start rather than a reload.
     */
    fun releaseComponents() {
        active?.let { guest -> runCatching { guest.components.shutdown() } }
    }

    /**
     * Force-stop: the app is gone, whatever it was allowed to do.
     *
     * Everything [releaseComponents] ends, plus its notifications and its place in the loader's
     * cache — so the next launch re-reads the APK rather than reusing a heap the user just asked to
     * be rid of.
     */
    fun forceStop(packageName: String) {
        GuestLoader.forPackage(packageName)?.let { guest ->
            runCatching { guest.components.shutdown() }
                .onFailure { Log.w(TAG, "cannot stop $packageName's components", it) }
        }
        VirtualNotifications.cancelAll(packageName)
        GuestLoader.forget(packageName)
        if (active?.packageName == packageName) active = null
        Log.i(TAG, "force-stopped $packageName")
    }

    /** The package a hook should attribute the current call to, or null outside a guest. */
    fun activePackage(): String? = active?.packageName

    /** The loaded guest a hook should attribute the current call to — its manifest included. */
    fun activeGuest(): LoadedGuest? = active

    /**
     * Tells the loaded guest how big its window is, before anything of it is built.
     *
     * Called by [EmbeddedGuest] on start and on every resize, so a guest that is laid out for the
     * tab stays laid out for it when the tab changes shape — a rotation, or the drawer opening.
     */
    fun sizeEmbeddedWindow(apkPath: String, widthPx: Int, heightPx: Int) {
        val guest = runCatching { GuestLoader.load(host, apkPath) }.getOrNull() ?: return
        GuestWindow.applySize(guest, widthPx, heightPx)
    }

    /** The resize path, once a guest is already loaded and running. */
    fun sizeEmbeddedWindow(widthPx: Int, heightPx: Int) {
        active?.let { GuestWindow.applySize(it, widthPx, heightPx) }
    }

    /** The label the device's status bar names the running app by. */
    fun activeLabel(): String? = active?.let { guest -> guest.labelOf(guest.launchActivity).toString() }

    /** Hosts intra-guest `startActivity` calls in the tab while [launcher] is set. */
    fun setEmbeddedLauncher(launcher: ((Intent) -> Boolean)?) {
        embeddedLauncher = launcher
    }

    /** Tells the tab an embedded activity finished itself, while [finisher] is set. */
    fun setEmbeddedFinisher(finisher: (() -> Unit)?) {
        embeddedFinisher = finisher
    }

    /**
     * [GuestActivityClient] calls this when a guest finishes an embedded activity.
     *
     * Posted rather than run inline for two reasons. `Activity.finish()` sets `mFinished` *after*
     * this returns, so a container that reaped immediately would look at the activity before it
     * admitted to finishing; and `finishActivity` can arrive on any thread, while the stack is the
     * main thread's alone.
     *
     * Being told beats looking. The reap used to be attempted after each touch, which missed every
     * `finish()` that did not happen inline with input — and a click is one of those: `View` posts
     * `performClick`, so the handler ran a message *later* than the reap that was supposed to catch
     * it. NewPipe's error screen therefore could not be dismissed by its own back arrow, and neither
     * could anything else on a second screen.
     */
    fun onEmbeddedFinish() {
        val finisher = embeddedFinisher ?: return
        Handler(Looper.getMainLooper()).post(finisher)
    }

    /** Tells the tab an embedded activity's Back was handed to the system, while [handler] is set. */
    fun setEmbeddedBackHandler(handler: (() -> Unit)?) {
        embeddedBackHandler = handler
    }

    /**
     * The device's own app for an implicit intent, as a stub ready to host — or null for one the
     * device has no app for, which goes out as it did before rather than doing nothing.
     *
     * This is what a phone's package manager does for an app that asks for a photo or a link: it
     * finds the app the *device* has. Before, the intent left the device, and the phone answered it
     * with the user's camera over their own storage and their own browser under their own profile —
     * and then no result could come back, because an embedded activity's token is one no
     * `ActivityRecord` answers to. Both halves are fixed by answering it here.
     */
    private fun deviceAppFor(intent: Intent): Intent? {
        val component = DeviceIntents.resolve(intent) ?: return null
        val apk = VirtualDeviceApps.apk(host, component.packageName) ?: return null
        val guest = runCatching { GuestLoader.load(host, apk.absolutePath) }.getOrNull() ?: return null
        return stubIntent(guest, component.className, Intent(intent))
    }

    /** [GuestActivityClient] calls this for the `onBackPressed` the platform routes to the server. */
    fun onEmbeddedBackPressed() {
        val handler = embeddedBackHandler ?: return
        Handler(Looper.getMainLooper()).post(handler)
    }


    /**
     * Drives one embedded activity to RESUMED, the way `ActivityThread` would.
     *
     * `Activity.performStart`/`performResume` are denied at `targetSdk` 33, so each step is the
     * public `Instrumentation` call wrapped in the `Pre`/`Post` lifecycle-callback dispatches those
     * two would have made — see [GuestHooks.dispatchLifecycleCallback], which is what actually
     * advances an AndroidX guest's `LifecycleRegistry` and so lets Compose run its frame clock.
     *
     * Returns false when the callback lists could not be reached at all; the tab reports that rather
     * than hiding it.
     */
    fun resumeEmbedded(activity: Activity): Boolean {
        val instrumentation = instrumentation ?: return false
        foreground = activity
        // `active` is what every hook attributes a call to — which permissions apply, whose manifest
        // to read — and it used to be set when an activity was *started* and never put back. That
        // was harmless while the only cross-app launch was fire-and-forget; now that an app can
        // start the device's Camera and be returned to, it is a leak: measured, the hardware fixture
        // read CAMERA=GRANTED after the Camera app was allowed it, because `active` was still the
        // Camera. Whatever is in front is what a call belongs to.
        GuestLoader.forPackage(activity.packageName)?.let { active = it }
        GuestHooks.dispatchLifecycleCallback(activity, "onActivityPreStarted")
        instrumentation.callActivityOnStart(activity)
        val started = GuestHooks.dispatchLifecycleCallback(activity, "onActivityPostStarted")

        GuestHooks.dispatchLifecycleCallback(activity, "onActivityPreResumed")
        instrumentation.callActivityOnResume(activity)
        postResume(activity)
        val resumed = GuestHooks.dispatchLifecycleCallback(activity, "onActivityPostResumed")

        if (!started || !resumed) {
            // ON_CREATE first, and that is not belt-and-braces. `ReportFragment` is what would
            // normally dispatch it, and on API 29+ it registers on the activity's own callback list
            // — the one that is blocked here — so the registry is still at INITIALIZED. Sending
            // ON_START to a registry that has never been created is an illegal transition, and
            // LifecycleRegistry refuses it: the guest would stay INITIALIZED, Compose would never
            // start a composition, and the app would draw nothing.
            advanceLifecycle(activity, "ON_CREATE")
            advanceLifecycle(activity, "ON_START")
            advanceLifecycle(activity, "ON_RESUME")
        }
        focus(activity, true)
        return started && resumed
    }

    /**
     * Tells an embedded guest whether it has the window's focus.
     *
     * Nothing else will. `onWindowFocusChanged` is delivered by the window manager to a *real*
     * window, and an embedded guest has a token no `ActivityRecord` answers to — so as far as the
     * system is concerned there is no window here to give focus to. The activity is nonetheless the
     * only thing on the device's screen, so the honest answer is the one the system cannot give.
     *
     * This is not a detail. Frameworks gate their **render thread** on it: SDL will not start until
     * it has a surface *and* focus, and says so —
     *
     * ```
     * V SDL: surfaceCreated()
     * V SDL: Window size: 1080x1420
     * V SDL: Skip .. Surface is not ready.
     * ```
     *
     * — which is why ES-DE ran perfectly, initialised SDL, read the device's identity, created its
     * surface, and drew nothing at all. Unity and most game engines pause on the same signal, so
     * this is the difference between a black rectangle and a running app for that whole family.
     *
     * Both routes are dispatched because frameworks listen on either: the activity's own callback,
     * and the view tree's, which is what a `ViewRootImpl` would have driven.
     */
    fun focus(activity: Activity, hasFocus: Boolean) {
        runCatching { activity.onWindowFocusChanged(hasFocus) }
            .onFailure { Log.w(TAG, "cannot tell ${activity.javaClass.name} it has focus", it) }
        runCatching { activity.window?.decorView?.dispatchWindowFocusChanged(hasFocus) }
            .onFailure { Log.w(TAG, "cannot dispatch window focus into the guest's views", it) }
    }

    /**
     * `Activity.performResume` calls `onPostResume` after `onResume`, and AndroidX's
     * `FragmentActivity` is where its fragments are moved to RESUMED. Protected SDK API, so no
     * hidden-API policy applies — only the container being outside the class.
     */
    private fun postResume(activity: Activity) {
        runCatching {
            Activity::class.java.getDeclaredMethod("onPostResume")
                .apply { isAccessible = true }
                .invoke(activity)
        }.onFailure { Log.w(TAG, "Activity#onPostResume failed", it) }
    }

    /**
     * Last-resort route to the guest's own AndroidX lifecycle, for a platform where the callback
     * lists have gone out of reach.
     *
     * It talks to `androidx.lifecycle` in the *guest's* class loader, which is the app's own code and
     * so is plain reflection with no platform policy over it — the same thing
     * `ReportFragment.dispatch` does on API 28 and below. Silent when the guest does not use
     * AndroidX at all.
     */
    private fun advanceLifecycle(activity: Activity, event: String) {
        val lifecycle = runCatching { activity.javaClass.getMethod("getLifecycle").invoke(activity) }
            .getOrNull() ?: return
        runCatching {
            val loader = lifecycle.javaClass.classLoader ?: return
            val registry = loader.loadClass("androidx.lifecycle.LifecycleRegistry")
            if (!registry.isInstance(lifecycle)) return
            val events = loader.loadClass("androidx.lifecycle.Lifecycle\$Event")
            val value = eventConstant(events, event) ?: return
            registry.getMethod("handleLifecycleEvent", events).invoke(lifecycle, value)
        }.onFailure { Log.w(TAG, "cannot advance the guest's lifecycle to $event", it) }
    }

    /**
     * One `Lifecycle.Event` constant, out of a guest that has been through R8.
     *
     * Not `Enum.valueOf`: R8 **removes** it from an enum nothing looks up by name, and a release
     * build of an app that only ever writes `Lifecycle.Event.ON_START` gives
     * `NoSuchMethodException: androidx.lifecycle.Lifecycle$Event.valueOf`. Measured on AI Edge
     * Gallery — and because that was the only route to the registry, the guest's lifecycle stayed at
     * INITIALIZED, so Compose never started a composition and the app drew nothing at all.
     *
     * The static field survives where the method does not, since the enum's own code reads it. The
     * other two are fallbacks for a shape neither assumption fits.
     */
    private fun eventConstant(events: Class<*>, name: String): Any? {
        runCatching { return events.getField(name).get(null) }
        runCatching {
            return events.enumConstants?.firstOrNull { (it as? Enum<*>)?.name == name }
        }
        runCatching { return events.getMethod("valueOf", String::class.java).invoke(null, name) }
        Log.w(TAG, "no Lifecycle.Event.$name in this guest")
        return null
    }

    /**
     * Tears one embedded activity down, in lifecycle order.
     *
     * Only the stop step needs help: `callActivityOnPause` and `callActivityOnDestroy` go through
     * `performPause`/`performDestroy`, which dispatch their own `Pre`/`Post` callbacks, while
     * `callActivityOnStop` calls `onStop()` straight.
     */
    fun destroyEmbedded(activity: Activity) {
        val instrumentation = instrumentation ?: return
        if (foreground === activity) foreground = null
        // Focus goes before the lifecycle does, the way it would on a real window: an engine that
        // started its render thread on gaining focus stops it on losing focus, and one told it still
        // had focus while being destroyed would keep drawing into a surface that is going away.
        focus(activity, false)
        instrumentation.callActivityOnPause(activity)
        GuestHooks.dispatchLifecycleCallback(activity, "onActivityPreStopped")
        instrumentation.callActivityOnStop(activity)
        GuestHooks.dispatchLifecycleCallback(activity, "onActivityPostStopped")
        instrumentation.callActivityOnDestroy(activity)
    }

    private fun onLaunchActivity(intent: Intent, info: ActivityInfo?) {
        val target = resolve(intent) ?: return
        intent.component = ComponentName(target.guest.packageName, target.activityClass)
        if (info == null) return

        // Whatever the client resolves out of this ActivityInfo, it resolves against the *activity's*
        // resources — which bind() is about to make the guest's. So every resource id here has to be
        // one of the guest's, or the framework looks a JCode id up in the guest's table and throws.
        // `applicationInfo` still keeps JCode's identity otherwise: swapping it wholesale sends
        // ActivityThread looking for a LoadedApk — and an installed package record — for a package
        // the system has never heard of.
        target.guest.activities[target.activityClass]?.let { guestInfo ->
            info.softInputMode = guestInfo.softInputMode
            info.uiOptions = guestInfo.uiOptions
            info.icon = guestInfo.icon
            info.logo = guestInfo.logo
        }
        info.nonLocalizedLabel = target.guest.labelOf(target.activityClass)
        info.labelRes = 0

        // The theme is the one id that must be zeroed rather than translated. performLaunchActivity
        // applies it while the activity is still on JCode's context, which builds
        // ContextThemeWrapper.mTheme out of JCode's resource table — and mTheme is the only member
        // the container needs but cannot reach to undo that, being max-target-p and so denied at
        // targetSdk 33. With getThemeResource() forced to 0 no theme is created at all, and bind()
        // applies the guest's own against the right resources a moment later.
        info.theme = 0
        info.applicationInfo = ApplicationInfo(info.applicationInfo).apply {
            theme = 0
            icon = target.guest.applicationInfo.icon
            logo = target.guest.applicationInfo.logo
        }

        Log.i(TAG, "launching ${target.guest.packageName}/${target.activityClass}")
    }

    /** Called from [GuestInstrumentation.newActivity]; null means "not one of ours". */
    fun newActivity(intent: Intent?): Activity? {
        val target = resolve(intent) ?: return null
        return target.guest.classLoader
            .loadClass(target.activityClass)
            .getDeclaredConstructor()
            .newInstance() as Activity
    }

    /** Called from [GuestInstrumentation.callActivityOnCreate], after `attach` and before `onCreate`. */
    fun bind(activity: Activity) {
        if (activity is GuestActivity) return
        val target = resolve(activity.intent) ?: return
        ensureApplication(target.guest)
        if (!GuestHooks.rebase(activity, target.guest)) return

        // Two calls, and the second is what makes the first safe.
        //
        // The int form is the one the activity's Window watches, so it still has to happen. What it
        // cannot do on its own is guarantee *which* resource table the theme is built from:
        // ContextThemeWrapper.initializeTheme only creates mTheme the first time, so a guest that
        // had mTheme created before bind() — against JCode's resources, since that is the context
        // the activity was attached to — would have its style id applied to the wrong table, and
        // mTheme is max-target-p and cannot be cleared.
        //
        // The object form replaces mTheme outright with one built from the guest's own resources,
        // so that stops being a matter of timing. It is public SDK from API 29; the container never
        // needed the field it cannot touch.
        val theme = target.guest.themeOf(target.activityClass)
        if (theme != 0) activity.setTheme(theme)
        activity.setTheme(target.guest.newTheme(target.activityClass))
        Log.i(
            TAG,
            "bound ${target.activityClass}: package=${activity.packageName} " +
                "filesDir=${activity.filesDir} theme=$theme",
        )
        VirtualDeviceLog.append(
            host,
            'I',
            TAG,
            "started ${target.guest.packageName}/${target.activityClass}",
        )
    }

    /**
     * The guest's own [Application], so `getApplication()` casts and
     * `registerActivityLifecycleCallbacks` work. `Instrumentation.newApplication` is public API and
     * attaches the context for us; only the `LoadedApk` behind it stays JCode's.
     */
    private fun ensureApplication(guest: LoadedGuest) {
        if (guest.application != null) return
        val instrumentation = instrumentation ?: return
        val className = guest.applicationInfo.className ?: Application::class.java.name
        runCatching {
            val app = instrumentation.newApplication(guest.classLoader, className, guest.appContext)
            guest.application = app
            // Between the Application being attached and its onCreate, exactly where
            // ActivityThread.handleBindApplication runs installContentProviders. Libraries that boot
            // from a provider — androidx.startup, and so WorkManager, Firebase and emoji2 — are
            // written to be up by the time application code runs, and putting this either side of
            // that line is the difference between them working and not.
            guest.components.installProviders(guest.appContext)
            instrumentation.callApplicationOnCreate(app)
            Log.i(TAG, "guest Application $className created")
        }.onFailure { Log.e(TAG, "guest Application $className failed", it) }
    }

    /**
     * Decides what to do with an intent the guest started: nothing, if it is not one of its own
     * activities; otherwise host it in the device-sandbox tab. A guest's own activity must never
     * reach the real system, which would resolve it against the phone's copy of the package.
     */
    private fun rewriteOutgoing(intent: Intent): StartAction {
        val component = intent.component
        // Resolved against *every* loaded guest rather than only the active one. A guest naming its
        // own package must never reach the real system, and `active` is a moving target — the
        // component is the reliable statement of whose activity this is.
        val guest = component?.let { GuestLoader.forPackage(it.packageName) }
            ?: active
            ?: return StartAction.Proceed
        if (component == null) {
            // An implicit intent is a question about what the *device* has — a camera, a picker, a
            // browser — and the device answers it with its own apps rather than letting the phone
            // answer it with the user's. See DeviceIntents.
            deviceAppFor(intent)?.let { stub ->
                val launcher = embeddedLauncher ?: return StartAction.Redirect(stub)
                return if (runCatching { launcher(stub) }.getOrDefault(false)) {
                    StartAction.Consumed
                } else {
                    StartAction.Redirect(stub)
                }
            }
            // Said in the *device's* log, not only the system one. An intent leaving the device is
            // the single most consequential thing that can happen without anybody being told: the
            // phone answers it, with the user's own apps and the user's own data, and from inside
            // the guest nothing went wrong at all. It cost a whole investigation to find that a
            // document picker was doing exactly this — see GuestDocuments.
            VirtualDeviceLog.append(
                host,
                'W',
                TAG,
                "${guest.packageName} started ${intent.action ?: "an intent"} with no component; " +
                    "the device has no app for it, so the PHONE will answer it and no result can " +
                    "come back",
            )
            return StartAction.Proceed
        }
        if (component.packageName != guest.packageName) return StartAction.Proceed
        // Deliberately not Proceed. The phone may have its **own copy** of this package installed —
        // the guest is a sideloaded build of something the user already has — and letting the intent
        // out means the system resolves it to that copy and runs the wrong app, outside the device,
        // with the user's own data. Measured on ES-DE, whose ConfiguratorActivity opened the
        // installed app over the top of JCode. A stub that fails inside the device is a far better
        // outcome than the right screen from the wrong application.
        if (!guest.activities.containsKey(component.className)) {
            Log.w(
                TAG,
                "${guest.packageName} has no activity ${component.className}; " +
                    "keeping it on the device rather than letting the phone answer it",
            )
        }
        val stub = stubIntent(guest, component.className, Intent(intent))
        val launcher = embeddedLauncher ?: return StartAction.Redirect(stub)
        val hosted = runCatching { launcher(stub) }
            .onFailure { Log.e(TAG, "cannot host $intent in the sandbox tab", it) }
            .getOrDefault(false)
        return if (hosted) StartAction.Consumed else StartAction.Redirect(stub)
    }

    /**
     * The stub an intent aimed at a loaded guest should be launched as, or null when it is not one.
     *
     * Exposed for [GuestActivityManagerHook]: a `PendingIntent` is sent through the activity
     * *manager*, not the activity task manager, so it never passes the hook that redirects a guest's
     * own `startActivity` and would otherwise be resolved by the system against the phone's copy of
     * the package.
     */
    fun redirectForGuest(intent: Intent): Intent? {
        val component = intent.component ?: return null
        val guest = GuestLoader.forPackage(component.packageName) ?: return null
        return stubIntent(guest, component.className, Intent(intent))
    }

    /**
     * The shape an embedded launch is carried in: which guest, which of its activities.
     *
     * The component is [GuestActivity] every time. It is never started — it is there so that
     * `getActivityInfo` has something to answer with, since the activity actually being built
     * belongs to a package the system has never heard of. One stub is enough for that; there used to
     * be four, so several guest activities could hold separate places in a real task, and with the
     * full-screen path gone there is no task to hold a place in.
     */
    private fun stubIntent(guest: LoadedGuest, activityClass: String, from: Intent? = null): Intent =
        (from ?: Intent())
            .setComponent(ComponentName(host, GuestActivity::class.java))
            .putExtra(EXTRA_APK, guest.apkPath)
            .putExtra(EXTRA_ACTIVITY, activityClass)

    private fun resolve(intent: Intent?): Target? {
        val apkPath = intent?.getStringExtra(EXTRA_APK) ?: return null
        val activityClass = intent.getStringExtra(EXTRA_ACTIVITY) ?: return null
        val guest = runCatching { GuestLoader.load(host, apkPath) }.getOrElse {
            Log.e(TAG, "cannot load $apkPath", it)
            return null
        }
        active = guest
        return Target(guest, activityClass)
    }
}

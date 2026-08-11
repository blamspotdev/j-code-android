package dev.jcode.vdevice

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.os.Binder
import android.os.IBinder
import android.util.Log

/**
 * The container itself, living in the `:guest` process: it installs the hooks in [GuestHooks] and
 * then decides what each of them should do.
 *
 * The launch path is a relay. J Code cannot ask the system to start an activity that is not
 * installed, so it starts one of its own stubs ([GuestActivity0]…[GuestActivity3]) carrying the
 * guest's identity in extras. On the way back in, [onLaunchActivity] rewrites the transaction to
 * name the guest activity, [newActivity] instantiates that class out of the guest's class loader,
 * and [bind] hands the instance a [GuestContext] — all before `onCreate` runs, and all while the
 * system remains the one performing `attach` and driving the lifecycle.
 *
 * [embed] is the same thing without the system: it builds the activity here so the device-sandbox
 * editor tab can host its decor view, and takes over driving the lifecycle in exchange.
 */
internal object GuestRuntime {

    const val EXTRA_APK = "dev.jcode.vdevice.apk"
    const val EXTRA_ACTIVITY = "dev.jcode.vdevice.activity"

    private const val STUB_COUNT = 4

    /** Embedded-activity id, the `Activity.getId()` a system launch would never produce. */
    private const val EMBEDDED_ID = "jcode-embedded"

    private class Target(val guest: LoadedGuest, val activityClass: String)

    @Volatile
    var isInstalled = false
        private set

    private lateinit var host: Context
    private var instrumentation: GuestInstrumentation? = null
    private var activityThread: Any? = null

    /** Set while a device-sandbox tab is showing this process, so intra-guest navigation is hosted in
     *  the tab instead of being bounced onto a full-screen stub. Returns true when it took the
     *  launch. */
    @Volatile
    private var embeddedLauncher: ((Intent) -> Boolean)? = null

    /** Guest activity class -> stub slot, so a given guest activity always lands on the same stub. */
    private val stubSlots = LinkedHashMap<String, Int>()

    /** The guest whose intents outgoing `startActivity` calls should be redirected for. */
    @Volatile
    private var active: LoadedGuest? = null

    /** Set while [embed] is building an activity, which is what tells [created] the two apart. */
    private var embedding = false

    @Synchronized
    fun install(context: Context) {
        if (isInstalled) return
        host = context.applicationContext
        VirtualIdentity.apply(Application.getProcessName())

        val activityThread = GuestHooks.currentActivityThread()
            ?: throw VirtualDeviceException("no ActivityThread in this process")
        this.activityThread = activityThread
        instrumentation = GuestHooks.installInstrumentation(activityThread)
            ?: throw VirtualDeviceException("cannot replace ActivityThread.mInstrumentation")

        val launch = GuestHooks.installLaunchHook(activityThread, ::onLaunchActivity)
        val navigation = GuestHooks.installStartActivityHook(::rewriteOutgoing)
        installCrashHandler()
        VirtualDeviceLog.captureStandardStreams(host)
        isInstalled = true
        Log.i(TAG, "hooks installed: instrumentation=true launch=$launch navigation=$navigation")
        VirtualDeviceLog.append(host, 'I', TAG, "container ready in ${Application.getProcessName()}")
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

    /** Starts [activityClass] (or the guest's launcher activity) on one of the stubs. */
    fun startGuest(from: Activity, apkPath: String, activityClass: String?) {
        val guest = GuestLoader.load(from, apkPath)
        active = guest
        val target = activityClass?.takeIf { guest.activities.containsKey(it) } ?: guest.launchActivity
        from.startActivity(stubIntent(guest, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
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
        // above all theme 0, so no theme is built against J Code's resources before bind() runs.
        onLaunchActivity(stub, info)
        val target = resolve(stub) ?: throw VirtualDeviceException("$stub carries no guest identity")

        val activity = instrumentation.newActivity(
            target.guest.classLoader.loadClass(target.activityClass),
            host,
            Binder(),
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

    /**
     * Called once a guest activity's `onCreate` has returned — the first moment its content view
     * exists, and the last before `setContentView` could clear anything added to it.
     *
     * Only a full-screen guest is given the overlay: one in the tab already has the tab's controls
     * floating over it.
     */
    fun created(activity: Activity) {
        if (embedding || activity is GuestActivity || activity is GuestBootstrapActivity) return
        if (resolve(activity.intent) == null) return
        GuestOverlay.install(activity)
    }

    /** Hosts intra-guest `startActivity` calls in the tab while [launcher] is set. */
    fun setEmbeddedLauncher(launcher: ((Intent) -> Boolean)?) {
        embeddedLauncher = launcher
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
        GuestHooks.dispatchLifecycleCallback(activity, "onActivityPreStarted")
        instrumentation.callActivityOnStart(activity)
        val started = GuestHooks.dispatchLifecycleCallback(activity, "onActivityPostStarted")

        GuestHooks.dispatchLifecycleCallback(activity, "onActivityPreResumed")
        instrumentation.callActivityOnResume(activity)
        postResume(activity)
        val resumed = GuestHooks.dispatchLifecycleCallback(activity, "onActivityPostResumed")

        if (!started || !resumed) {
            advanceLifecycle(activity, "ON_START")
            advanceLifecycle(activity, "ON_RESUME")
        }
        return started && resumed
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
            val value = events.getMethod("valueOf", String::class.java).invoke(null, event)
            registry.getMethod("handleLifecycleEvent", events).invoke(lifecycle, value)
        }.onFailure { Log.w(TAG, "cannot advance the guest's lifecycle to $event", it) }
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
        // one of the guest's, or the framework looks a J Code id up in the guest's table and throws.
        // `applicationInfo` still keeps J Code's identity otherwise: swapping it wholesale sends
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
        // applies it while the activity is still on J Code's context, which builds
        // ContextThemeWrapper.mTheme out of J Code's resource table — and mTheme is the only member
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
        if (activity is GuestActivity || activity is GuestBootstrapActivity) return
        val target = resolve(activity.intent) ?: return
        ensureApplication(target.guest)
        if (!GuestHooks.rebase(activity, target.guest)) return

        // Two calls, and the second is what makes the first safe.
        //
        // The int form is the one the activity's Window watches, so it still has to happen. What it
        // cannot do on its own is guarantee *which* resource table the theme is built from:
        // ContextThemeWrapper.initializeTheme only creates mTheme the first time, so a guest that
        // had mTheme created before bind() — against J Code's resources, since that is the context
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
     * attaches the context for us; only the `LoadedApk` behind it stays J Code's.
     */
    private fun ensureApplication(guest: LoadedGuest) {
        if (guest.application != null) return
        val instrumentation = instrumentation ?: return
        val className = guest.applicationInfo.className ?: Application::class.java.name
        runCatching {
            val app = instrumentation.newApplication(guest.classLoader, className, guest.appContext)
            guest.application = app
            instrumentation.callApplicationOnCreate(app)
            Log.i(TAG, "guest Application $className created")
        }.onFailure { Log.e(TAG, "guest Application $className failed", it) }
    }

    /**
     * Decides what to do with an intent the guest started: nothing, if it is not one of its own
     * activities; host it in the device-sandbox tab, if one is showing this guest; otherwise redirect it
     * onto a free stub and let the system launch it full screen.
     */
    private fun rewriteOutgoing(intent: Intent): StartAction {
        val guest = active ?: return StartAction.Proceed
        val component = intent.component
        if (component == null) {
            if (intent.`package` == guest.packageName || intent.selector != null) {
                Log.w(TAG, "implicit intents inside ${guest.packageName} are not supported: $intent")
            }
            return StartAction.Proceed
        }
        if (component.packageName != guest.packageName) return StartAction.Proceed
        if (!guest.activities.containsKey(component.className)) {
            Log.w(TAG, "${guest.packageName} has no activity ${component.className}")
            return StartAction.Proceed
        }
        val stub = stubIntent(guest, component.className, Intent(intent))
        val launcher = embeddedLauncher ?: return StartAction.Redirect(stub)
        val hosted = runCatching { launcher(stub) }
            .onFailure { Log.e(TAG, "cannot host $intent in the sandbox tab", it) }
            .getOrDefault(false)
        return if (hosted) StartAction.Consumed else StartAction.Redirect(stub)
    }

    private fun stubIntent(guest: LoadedGuest, activityClass: String, from: Intent? = null): Intent {
        val slot = synchronized(stubSlots) {
            stubSlots.getOrPut(activityClass) { stubSlots.size % STUB_COUNT }
        }
        return (from ?: Intent())
            .setComponent(ComponentName(host, GuestActivity.stub(slot)))
            .putExtra(EXTRA_APK, guest.apkPath)
            .putExtra(EXTRA_ACTIVITY, activityClass)
    }

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

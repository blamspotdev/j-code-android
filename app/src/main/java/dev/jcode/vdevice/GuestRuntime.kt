package dev.jcode.vdevice

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
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
 */
internal object GuestRuntime {

    const val EXTRA_APK = "dev.jcode.vdevice.apk"
    const val EXTRA_ACTIVITY = "dev.jcode.vdevice.activity"

    private const val STUB_COUNT = 4

    private class Target(val guest: LoadedGuest, val activityClass: String)

    @Volatile
    var isInstalled = false
        private set

    private lateinit var host: Context
    private var instrumentation: GuestInstrumentation? = null

    /** Guest activity class -> stub slot, so a given guest activity always lands on the same stub. */
    private val stubSlots = LinkedHashMap<String, Int>()

    /** The guest whose intents outgoing `startActivity` calls should be redirected for. */
    @Volatile
    private var active: LoadedGuest? = null

    @Synchronized
    fun install(context: Context) {
        if (isInstalled) return
        host = context.applicationContext
        VirtualIdentity.apply(Application.getProcessName())

        val activityThread = GuestHooks.currentActivityThread()
            ?: throw VirtualDeviceException("no ActivityThread in this process")
        instrumentation = GuestHooks.installInstrumentation(activityThread)
            ?: throw VirtualDeviceException("cannot replace ActivityThread.mInstrumentation")

        val launch = GuestHooks.installLaunchHook(activityThread, ::onLaunchActivity)
        val navigation = GuestHooks.installStartActivityHook(::rewriteOutgoing)
        isInstalled = true
        Log.i(TAG, "hooks installed: instrumentation=true launch=$launch navigation=$navigation")
    }

    /** Starts [activityClass] (or the guest's launcher activity) on one of the stubs. */
    fun startGuest(from: Activity, apkPath: String, activityClass: String?) {
        val guest = GuestLoader.load(from, apkPath)
        active = guest
        val target = activityClass?.takeIf { guest.activities.containsKey(it) } ?: guest.launchActivity
        from.startActivity(stubIntent(guest, target).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION))
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

        val theme = target.guest.themeOf(target.activityClass)
        if (theme != 0) activity.setTheme(theme)
        Log.i(
            TAG,
            "bound ${target.activityClass}: package=${activity.packageName} " +
                "filesDir=${activity.filesDir} theme=$theme",
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

    /** Redirects an intent the guest aimed at one of its own activities onto a free stub. */
    private fun rewriteOutgoing(intent: Intent): Intent? {
        val guest = active ?: return null
        val component = intent.component
        if (component == null) {
            if (intent.`package` == guest.packageName || intent.selector != null) {
                Log.w(TAG, "implicit intents inside ${guest.packageName} are not supported: $intent")
            }
            return null
        }
        if (component.packageName != guest.packageName) return null
        if (!guest.activities.containsKey(component.className)) {
            Log.w(TAG, "${guest.packageName} has no activity ${component.className}")
            return null
        }
        return stubIntent(guest, component.className, Intent(intent))
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

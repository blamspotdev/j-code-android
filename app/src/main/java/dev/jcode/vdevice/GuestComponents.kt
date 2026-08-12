package dev.jcode.vdevice

import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.ContentProvider
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicInteger

/**
 * The guest's non-activity components, hosted in `:guest`.
 *
 * The container cannot register a guest's providers, services or receivers with the system: they
 * belong to a package the real `PackageManager` has never heard of, and every registration path ends
 * at a binder call that checks exactly that. What it *can* do is what `ActivityThread` does inside an
 * app process — build the objects, attach them to a [GuestContext], and drive their lifecycle by hand
 * — so that code which only ever talks to its own components sees them working.
 *
 * **This is in-process only, and that is the boundary.** Nothing here is reachable from outside
 * `:guest`: another app cannot query a hosted provider, no system broadcast arrives on its own, and
 * a service gets no process of its own to be restarted in. What it buys is the far more common case
 * of an app talking to itself — which is where the frameworks live.
 *
 * The one that matters most is `androidx.startup.InitializationProvider`. WorkManager, Firebase,
 * `emoji2`, ProfileInstaller and Coil all boot from it, and with no provider hosting at all a guest
 * that merely *depends* on WorkManager dies before its first frame with "WorkManager is not
 * initialized properly". Measured on NewPipe.
 */
internal class GuestComponents(private val guest: LoadedGuest) {

    private val main = Handler(Looper.getMainLooper())
    private val nextStartId = AtomicInteger(1)

    private val providers = LinkedHashMap<String, ContentProvider>()
    private val services = LinkedHashMap<String, Service>()
    private val bindings = LinkedHashMap<ServiceConnection, String>()

    @Volatile
    private var providersInstalled = false

    /** Live providers by authority, for [GuestContext] to answer its own resolver lookups from. */
    fun provider(authority: String): ContentProvider? = synchronized(providers) { providers[authority] }

    /**
     * Instantiates every declared `<provider>`, once, before the guest's `Application.onCreate`.
     *
     * That ordering is the platform's, not a convenience: `ActivityThread.handleBindApplication`
     * runs `installContentProviders` between `Application.attachBaseContext` and
     * `Application.onCreate`, and libraries that boot from a provider rely on being up by the time
     * application code runs. Providers are started in descending `initOrder` for the same reason the
     * platform does it — that attribute is the only say an app has over the sequence.
     *
     * A provider that throws is logged and skipped rather than allowed to take the guest with it:
     * one broken analytics provider should not stop an app from starting on a device meant for
     * trying it out.
     */
    fun installProviders(context: Context) {
        if (providersInstalled) return
        providersInstalled = true
        guest.providers
            .sortedByDescending { it.initOrder }
            .forEach { info ->
                val authority = info.authority ?: return@forEach
                runCatching {
                    val provider = guest.classLoader.loadClass(info.name)
                        .getDeclaredConstructor()
                        .newInstance() as ContentProvider
                    // Public API, and it calls onCreate() itself — which is the whole point of
                    // hosting a provider at all.
                    provider.attachInfo(context, info)
                    authority.split(';').forEach { synchronized(providers) { providers[it] = provider } }
                    Log.i(TAG, "guest provider ${info.name} attached as $authority")
                }.onFailure {
                    Log.e(TAG, "guest provider ${info.name} failed", it)
                    VirtualDeviceLog.append(
                        guest.appContext,
                        'W',
                        TAG,
                        "provider ${info.name} did not start: ${it.rootMessage()}",
                    )
                }
            }
    }

    // ---------------------------------------------------------------- services

    /** `Context.startService` for one of the guest's own services. Null when it is not the guest's. */
    fun startService(context: Context, intent: Intent): ComponentName? {
        val info = resolveService(intent) ?: return null
        val service = instantiate(context, info) ?: return null
        val id = nextStartId.getAndIncrement()
        runCatching { service.onStartCommand(Intent(intent), 0, id) }
            .onFailure { Log.e(TAG, "guest service $info onStartCommand failed", it) }
        return ComponentName(guest.packageName, info)
    }

    /** `Context.stopService`. True when the guest declared it, whether or not it was running. */
    fun stopService(intent: Intent): Boolean {
        val info = resolveService(intent) ?: return false
        synchronized(services) { services.remove(info) }?.let { service ->
            runCatching { service.onDestroy() }.onFailure { Log.w(TAG, "guest service $info onDestroy", it) }
        }
        return true
    }

    /**
     * `Context.bindService`. The connection is called back on the main thread, never inline, because
     * that is the one thing every caller of `bindService` is written against — a client that binds
     * from `onCreate` and expects `onServiceConnected` later must not be re-entered inside its own
     * `bindService` call.
     */
    fun bindService(context: Context, intent: Intent, connection: ServiceConnection): Boolean {
        val info = resolveService(intent) ?: return false
        val service = instantiate(context, info) ?: return false
        val binder = runCatching { service.onBind(Intent(intent)) }
            .onFailure { Log.e(TAG, "guest service $info onBind failed", it) }
            .getOrNull()
        synchronized(bindings) { bindings[connection] = info }
        val name = ComponentName(guest.packageName, info)
        main.post {
            runCatching { connection.onServiceConnected(name, binder ?: Binder()) }
                .onFailure { Log.e(TAG, "onServiceConnected for $info", it) }
        }
        return true
    }

    /** `Context.unbindService`. False when this connection was never one of the guest's. */
    fun unbindService(connection: ServiceConnection): Boolean {
        val info = synchronized(bindings) { bindings.remove(connection) } ?: return false
        synchronized(services) { services[info] }?.let { service ->
            runCatching { service.onUnbind(Intent()) }.onFailure { Log.w(TAG, "onUnbind for $info", it) }
        }
        return true
    }

    private fun resolveService(intent: Intent): String? {
        val component = intent.component
        if (component != null) {
            if (component.packageName != guest.packageName) return null
            return component.className.takeIf { guest.services.containsKey(it) }
        }
        val action = intent.action ?: return null
        if (intent.`package` != null && intent.`package` != guest.packageName) return null
        return guest.serviceActions.entries.firstOrNull { action in it.value }?.key
    }

    /**
     * Builds a [Service] and runs `onCreate`, or returns the one already running.
     *
     * `Service.attach` is the framework's own entry point and carries everything a service needs to
     * be a `Context`; it is greylisted, so it is tried first and its absence is not fatal. Failing
     * that, the base context is set directly — the same `ContextWrapper.mBase` swap
     * [GuestHooks.rebase] performs on an activity — which leaves a service that can resolve
     * resources, storage and system services but has no token behind it, so `startForeground` and
     * anything else that calls back into the activity manager under its own identity will not work.
     */
    private fun instantiate(context: Context, className: String): Service? {
        synchronized(services) { services[className] }?.let { return it }
        return runCatching {
            val service = guest.classLoader.loadClass(className)
                .getDeclaredConstructor()
                .newInstance() as Service
            if (!attach(service, context, className)) {
                throw VirtualDeviceException("cannot give $className a context")
            }
            synchronized(services) { services[className] = service }
            service.onCreate()
            Log.i(TAG, "guest service $className created")
            service
        }.onFailure {
            synchronized(services) { services.remove(className) }
            Log.e(TAG, "guest service $className failed", it)
            VirtualDeviceLog.append(
                guest.appContext,
                'W',
                TAG,
                "service $className did not start: ${it.rootMessage()}",
            )
        }.getOrNull()
    }

    private fun attach(service: Service, context: Context, className: String): Boolean {
        val application = guest.application
        HiddenApi.classOrNull("android.app.ActivityThread")?.let { threadClass ->
            val attach = HiddenApi.method(
                Service::class.java,
                "attach",
                Context::class.java,
                threadClass,
                String::class.java,
                IBinder::class.java,
                android.app.Application::class.java,
                Any::class.java,
            )
            if (attach != null && application != null) {
                val attached = runCatching {
                    attach.invoke(
                        service,
                        context,
                        GuestHooks.currentActivityThread(),
                        className,
                        Binder(),
                        application,
                        null,
                    )
                    true
                }.onFailure { Log.w(TAG, "Service.attach unavailable for $className", it) }
                    .getOrDefault(false)
                if (attached) return true
            }
        }
        return runCatching {
            HiddenApi.field(ContextWrapper::class.java, "mBase")?.set(service, context) ?: return false
            true
        }.getOrDefault(false)
    }

    // -------------------------------------------------------------- receivers

    /**
     * Delivers [intent] to the guest's own manifest receivers.
     *
     * Only the guest's are considered, and only for a broadcast the guest itself sent: a hosted
     * receiver is not registered with the system, so nothing else can reach it and it never hears a
     * system broadcast. Returns how many receivers ran, so the caller can tell an unhandled
     * broadcast from a handled one and still let it out to the real system.
     */
    fun sendBroadcast(context: Context, intent: Intent): Int {
        val targets = resolveReceivers(intent)
        targets.forEach { className ->
            runCatching {
                val receiver = guest.classLoader.loadClass(className)
                    .getDeclaredConstructor()
                    .newInstance() as BroadcastReceiver
                receiver.onReceive(context, Intent(intent))
                Log.i(TAG, "guest receiver $className handled ${intent.action ?: intent.component}")
            }.onFailure { Log.e(TAG, "guest receiver $className failed", it) }
        }
        return targets.size
    }

    private fun resolveReceivers(intent: Intent): List<String> {
        val component = intent.component
        if (component != null) {
            if (component.packageName != guest.packageName) return emptyList()
            return listOfNotNull(component.className.takeIf { guest.receivers.containsKey(it) })
        }
        val action = intent.action ?: return emptyList()
        if (intent.`package` != null && intent.`package` != guest.packageName) return emptyList()
        return guest.receiverActions.filterValues { action in it }.keys.toList()
    }

    /** Tears every hosted component down, in the order a process death would end them. */
    fun shutdown() {
        synchronized(bindings) { bindings.clear() }
        val running = synchronized(services) { services.values.toList().also { services.clear() } }
        running.forEach { service ->
            runCatching { service.onDestroy() }.onFailure { Log.w(TAG, "onDestroy on shutdown", it) }
        }
        synchronized(providers) { providers.clear() }
        providersInstalled = false
    }
}

/** The deepest cause — the one that says what actually went wrong. */
internal fun Throwable.rootCause(): Throwable =
    generateSequence(this) { error -> error.cause?.takeIf { it !== error } }.last()

/** The message of the deepest cause, which is the one that says what actually went wrong. */
internal fun Throwable.rootMessage(): String {
    val root = rootCause()
    return root.message?.takeIf { it.isNotBlank() } ?: root.javaClass.simpleName
}

/**
 * A failure phrased for someone reading the device's log or the tab's error card.
 *
 * Two things make the difference between a usable report and a riddle. The first is following the
 * cause chain: loading a guest activity whose static initialiser fails surfaces as a
 * `ClassNotFoundException` whose message is *only the class name*, while the cause underneath it is
 * the `UnsatisfiedLinkError` that actually explains it. Measured: a GameActivity app built without
 * its native glue reported `dev.waverepo.WaveRepoActivity` and nothing else, when what had happened
 * was `dlopen failed: cannot locate symbol "android_app_set_motion_event_filter"`.
 *
 * The second is naming the exception only when its message cannot stand alone. "You need to use a
 * Theme.AppCompat theme (or descendant) with this activity." is already a sentence; prefixing it
 * with its class helps nobody.
 */
internal fun Throwable.describe(): String {
    val root = rootCause()
    val detail = root.message?.takeIf { it.isNotBlank() } ?: return root.javaClass.simpleName
    // A class name on its own reads like a label rather than a failure, and these carry nothing else.
    val bare = root is ClassNotFoundException || root is LinkageError
    return if (bare) "${root.javaClass.simpleName}: $detail" else detail
}

package dev.blamspot.jcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.system.OsConstants
import androidx.core.content.ContextCompat
import dev.blamspot.jcode.backend.BackendSessionKind
import dev.blamspot.jcode.backend.SessionRegistry
import dev.blamspot.jcode.backend.SessionRegistryState
import dev.blamspot.jcode.core.distro.AppProcesses
import dev.blamspot.jcode.vdevice.VirtualDeviceBridge
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BackendService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var isForegroundActive = false
    private var memoryTicker: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        ensureNotificationChannel()
        SessionRegistry.onServiceCreated()
        serviceScope.launch {
            SessionRegistry.state.collectLatest { state ->
                applyRegistryState(state)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // The notification's "Stop & close" action: an explicit user request to fully close, so
            // tear down regardless of the swipe-away preference.
            shutdownRuntimeAndExit()
            return START_NOT_STICKY
        }
        applyRegistryState(SessionRegistry.state.value, startId)
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (MainViewModel.exitOnSwipeAwayEnabled) {
            shutdownRuntimeAndExit()
        } else {
            super.onTaskRemoved(rootIntent)
        }
    }

    /** Reap every proot tree (terminals + runs / VMs / language servers / debug adapters) so nothing
     *  lingers in the background, then drop the foreground notification and kill the process. Without
     *  this the runtime keeps running headless after the task is gone. */
    private fun shutdownRuntimeAndExit() {
        // Flush unsaved editor buffers to disk before anything else — killProcess below would otherwise
        // race the async onStop flush and drop the latest edits (esp. the "Stop & close" action, which
        // fires from the notification shade without the Activity reaching onStop).
        runCatching { MainViewModel.sessionFlushBlocking?.invoke() }
        runCatching { TerminalSessionHost.manager(applicationContext).closeAll() }
        runCatching { MainViewModel.runtimeTeardown?.invoke() }
        // The virtual device runs in a process of its own, and a close that only ends *this* one
        // leaves it up: measured, `:guest` and the logcat a guest had open outlived a Stop & close,
        // holding 198 MB with no JCode left to show them in. Turned off through its own door first,
        // so the guest is told rather than found dead.
        runCatching { VirtualDeviceBridge.shutdown() }
        endEveryOtherProcess()
        if (isForegroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundActive = false
        }
        stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    /**
     * Everything this app owns except the process running this.
     *
     * "Stop & close" means the app is gone, and the app is more processes than the one with the UI
     * in it. `/proc` is mounted with hidepid for apps, so a uid-filtered walk of it is exactly this
     * app's tree and nothing else: the virtual device, whatever a guest forked, the detached adb
     * daemon that has `init` for a parent and so is reaped by nobody, and any proot still standing
     * after its own teardown.
     *
     * `SIGKILL` because this is the last thing that runs before the process asking is itself gone —
     * there would be nobody left to notice a polite signal being ignored. Each kill is guarded on
     * its own: a pid that has already exited is the ordinary case here, not a failure.
     */
    private fun endEveryOtherProcess() {
        val self = android.os.Process.myPid()
        AppProcesses.list()
            .filter { it.pid != self }
            .forEach { runCatching { Os.kill(it.pid, OsConstants.SIGKILL) } }
    }

    override fun onDestroy() {
        SessionRegistry.onServiceDestroyed()
        if (isForegroundActive) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            isForegroundActive = false
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun applyRegistryState(state: SessionRegistryState, startId: Int? = null) {
        if (state.isEmpty) {
            if (isForegroundActive) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                isForegroundActive = false
            }
            if (startId != null) {
                stopSelf(startId)
            } else {
                stopSelf()
            }
            return
        }

        val notification = buildNotification(state)
        if (!isForegroundActive) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            isForegroundActive = true
            startMemoryTicker()
        } else {
            notificationManager.notify(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            getString(R.string.backend_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.backend_service_channel_description)
            setShowBadge(false)
            enableVibration(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    /**
     * Keep the memory figure moving while the shade is open.
     *
     * There is no signal for "the user pulled the shade down", so this is a slow tick rather than a
     * subscription: rebuilding the notification is a few small `/proc` reads and one `notify`, and at
     * this interval it costs less than the sessions it is describing. It runs only while the service
     * is foreground, which is only while something is actually running.
     */
    private fun startMemoryTicker() {
        if (memoryTicker?.isActive == true) return
        memoryTicker = serviceScope.launch {
            while (true) {
                delay(MEMORY_REFRESH_MS)
                if (!isForegroundActive) continue
                val state = SessionRegistry.state.value
                if (state.isEmpty) continue
                runCatching { notificationManager.notify(NOTIFICATION_ID, buildNotification(state)) }
            }
        }
    }

    private fun buildNotification(state: SessionRegistryState): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val contentIntent = launchIntent?.let { intent ->
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
        // What is running, in the words the person who started it would use. The registry has carried
        // a kind and a name all along; the notification used to reduce all of it to a count.
        val task = state.task
        val terminals = state.terminals
        val lines = buildList {
            task?.let { add(it.percent?.let { p -> "${it.label} — $p%" } ?: it.label) }
            state.sessions
                .filter { it.kind != BackendSessionKind.TERMINAL }
                .forEach { add(it.displayLabel()) }
            terminals.forEach { add(it.displayLabel()) }
        }
        val title = when {
            task != null -> task.label
            terminals.size == 1 && state.sessions.size == 1 -> terminals.first().displayLabel()
            terminals.isNotEmpty() -> resources.getQuantityString(
                R.plurals.backend_service_terminals,
                terminals.size,
                terminals.size,
            )
            else -> lines.firstOrNull() ?: getString(R.string.backend_service_notification_title)
        }
        val memory = AppProcesses.totalPssKb()?.let { formatMemory(it) }
        val summary = listOfNotNull(
            task?.percent?.let { "$it%" },
            lines.takeIf { it.size > 1 }?.let { "${it.size} running" },
            memory,
        ).joinToString(" · ").ifEmpty { lines.firstOrNull().orEmpty() }
        // One session already names itself in the title; repeating it underneath would be the
        // notification talking to itself. The list earns its place only when there is more than one.
        val detail = if (lines.size > 1) lines else emptyList()
        val expanded = (detail + listOfNotNull(memory?.let { getString(R.string.backend_service_memory, it) }))
            .joinToString("\n")
            .ifBlank { summary }

        val stopIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, BackendService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            getString(R.string.backend_service_stop_action),
            stopIntent,
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(summary)
            .setStyle(Notification.BigTextStyle().bigText(expanded))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .apply {
                // Determinate once the script reports a percentage, indeterminate until then — a task
                // that is clearly moving is the difference between waiting and wondering.
                task?.let { setProgress(100, it.percent ?: 0, it.percent == null) }
            }
            .build()
    }

    /** GB past a thousand megabytes: "1.4 GB" reads at a glance where "1428 MB" has to be counted. */
    private fun formatMemory(kb: Long): String {
        val mb = kb / 1024.0
        return if (mb >= 1024) String.format(java.util.Locale.US, "%.1f GB", mb / 1024) else "${mb.toInt()} MB"
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "dev.blamspot.jcode.backend.sessions"
        private const val NOTIFICATION_ID = 7_601
        private const val ACTION_STOP = "dev.blamspot.jcode.backend.action.STOP"
        private const val MEMORY_REFRESH_MS = 10_000L

        internal fun start(context: android.content.Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, BackendService::class.java),
            )
        }

        internal fun stop(context: android.content.Context) {
            context.stopService(Intent(context, BackendService::class.java))
        }
    }
}

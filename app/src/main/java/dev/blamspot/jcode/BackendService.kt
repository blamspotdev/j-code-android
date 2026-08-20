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
import dev.blamspot.jcode.backend.SessionRegistry
import dev.blamspot.jcode.backend.SessionRegistryState
import dev.blamspot.jcode.core.distro.AppProcesses
import dev.blamspot.jcode.vdevice.AppSandbox
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class BackendService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var isForegroundActive = false

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
        runCatching { AppSandbox.shutdown() }
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

        val notification = buildNotification(activeCount = state.activeCount)
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

    private fun buildNotification(activeCount: Int): Notification {
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
        val sessionText = resources.getQuantityString(
            R.plurals.backend_service_active_sessions,
            activeCount,
            activeCount,
        )

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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(getString(R.string.backend_service_notification_title))
            .setContentText(sessionText)
            .setStyle(Notification.BigTextStyle().bigText(sessionText))
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(contentIntent)
            .addAction(stopAction)
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "dev.jcode.backend.sessions"
        private const val NOTIFICATION_ID = 7_601
        private const val ACTION_STOP = "dev.jcode.backend.action.STOP"

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

package dev.jcode

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import dev.jcode.backend.SessionRegistry
import dev.jcode.backend.SessionRegistryState
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
        applyRegistryState(SessionRegistry.state.value, startId)
        return START_NOT_STICKY
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
            .build()
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "dev.jcode.backend.sessions"
        private const val NOTIFICATION_ID = 7_601

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

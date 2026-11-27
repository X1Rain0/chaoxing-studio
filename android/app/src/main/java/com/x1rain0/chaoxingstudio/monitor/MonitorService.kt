package com.x1rain0.chaoxingstudio.monitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.x1rain0.chaoxingstudio.ChaoxingStudioApplication
import com.x1rain0.chaoxingstudio.MainActivity
import com.x1rain0.chaoxingstudio.R
import com.x1rain0.chaoxingstudio.data.MonitorMode
import com.x1rain0.chaoxingstudio.data.MonitorRuntime
import com.x1rain0.chaoxingstudio.data.ActivityCard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MonitorService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: MonitorRuntime
    private var monitorJob: Job? = null
    private lateinit var notifications: NotificationManager

    override fun onCreate() {
        super.onCreate()
        runtime = (application as ChaoxingStudioApplication).monitorRuntime
        notifications = getSystemService(NotificationManager::class.java)
        createNotificationChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        if (!runtime.preferences.config().monitorWanted || runtime.preferences.config().mode != MonitorMode.BACKGROUND) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        enterForeground()
        if (monitorJob?.isActive != true) monitorJob = serviceScope.launch { monitorLoop() }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // The user-selected background mode remains active when the task is dismissed.
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        monitorJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun enterForeground() {
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_MONITOR_ID, ongoingNotification(), type)
    }

    private suspend fun monitorLoop() {
        while (serviceScope.isActive) {
            val config = runtime.preferences.config()
            if (!config.monitorWanted || config.mode != MonitorMode.BACKGROUND) {
                stopSelf()
                return
            }
            try {
                val scan = runtime.scanOnce()
                scan.newlyDiscovered.forEach(::notifyNewActivity)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                runtime.reportError(error.message ?: "后台巡检失败")
            }
            delay(runtime.preferences.config().intervalSeconds.coerceAtLeast(30) * 1_000L)
        }
    }

    private fun notifyNewActivity(activity: ActivityCard) {
        val title = activity.name.ifBlank { activity.kindName }
        val content = listOf(activity.courseTitle, activity.kindName, activity.freshness())
            .filter(String::isNotBlank)
            .joinToString(" · ")
        val notification = NotificationCompat.Builder(this, CHANNEL_EVENTS)
            .setSmallIcon(R.drawable.ic_monitor_notification)
            .setContentTitle(title)
            .setContentText(content)
            .setStyle(NotificationCompat.BigTextStyle().bigText(content))
            .setContentIntent(openAppIntent())
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .build()
        notifications.notify(NOTIFICATION_EVENT_BASE + (activity.activeId.hashCode() and 0x3fffffff), notification)
    }

    private fun ongoingNotification(): Notification = NotificationCompat.Builder(this, CHANNEL_MONITOR)
        .setSmallIcon(R.drawable.ic_monitor_notification)
        .setContentTitle(getString(R.string.monitor_notification_title))
        .setContentText(getString(R.string.monitor_notification_body, runtime.preferences.config().intervalSeconds))
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setSilent(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(
            this,
            REQUEST_OPEN_APP,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun createNotificationChannels() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_MONITOR, getString(R.string.monitor_channel_name), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.monitor_channel_description)
                setShowBadge(false)
            },
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, getString(R.string.events_channel_name), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.events_channel_description)
            },
        )
    }

    companion object {
        const val ACTION_STOP = "com.x1rain0.chaoxingstudio.action.STOP_MONITOR"
        private const val CHANNEL_MONITOR = "monitor_service"
        private const val CHANNEL_EVENTS = "sign_events"
        private const val NOTIFICATION_MONITOR_ID = 202601
        private const val NOTIFICATION_EVENT_BASE = 300000
        private const val REQUEST_OPEN_APP = 401
    }
}

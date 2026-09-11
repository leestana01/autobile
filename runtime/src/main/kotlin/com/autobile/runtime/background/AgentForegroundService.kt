package com.autobile.runtime.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import com.autobile.core.common.Logx
import com.autobile.runtime.AutobileRuntime
import com.autobile.runtime.R
import com.autobile.runtime.agent.AgentActivity
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Keeps a run alive while the user is in another app, and makes it visible.
 *
 * The notification is not only a platform requirement. An automation driving the phone
 * on its own must always be visible and stoppable from anywhere, and the notification is
 * the one surface that satisfies both regardless of which app is in front.
 */
class AgentForegroundService : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Starting", null))

        lifecycleScope.launch {
            val orchestrator = AutobileRuntime.services?.orchestrator ?: return@launch
            orchestrator.activity.collectLatest { activity ->
                when (activity) {
                    is AgentActivity.Running -> notify(activity)
                    AgentActivity.Idle -> stopSelf()
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            AutobileRuntime.services?.orchestrator?.cancelCurrentRun()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    private fun notify(activity: AgentActivity.Running) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(
            NOTIFICATION_ID,
            buildNotification(
                title = activity.skillName,
                text = "${activity.stepDescription} · step ${activity.stepIndex + 1} of ${activity.totalSteps}",
            ),
        )
    }

    private fun buildNotification(title: String, text: String?): Notification {
        val stopIntent = PendingIntent.getService(
            this,
            REQUEST_STOP,
            Intent(this, AgentForegroundService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_autobile_agent)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .addAction(R.drawable.ic_autobile_stop, "Stop", stopIntent)
            .build()
    }

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Running automations",
            // Low importance: the notification must be present and reachable, but a
            // scheduled automation should not interrupt whatever the user is doing.
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows what Autobile is doing while an automation runs"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "autobile_agent"
        private const val NOTIFICATION_ID = 4201
        private const val REQUEST_STOP = 1
        const val ACTION_STOP = "com.autobile.runtime.STOP_AGENT"

        /**
         * Starts the service if the platform allows it right now.
         *
         * Android restricts foreground service starts from the background, and the
         * restriction is not something the app can detect in advance. A rejection here
         * means the run continues without the notification, which is better than
         * crashing a scheduled automation over its own progress indicator.
         */
        fun start(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            runCatching { context.startForegroundService(intent) }
                .onFailure { Logx.w("Could not start the agent notification", it) }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, AgentForegroundService::class.java)) }
        }
    }
}

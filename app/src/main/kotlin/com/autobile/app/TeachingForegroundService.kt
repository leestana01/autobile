package com.autobile.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat

/** Keeps a user-started teaching session visible while they demonstrate in other apps. */
class TeachingForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Teaching sessions", NotificationManager.IMPORTANCE_LOW),
        )
        val reopen = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        startForeground(
            NOTIFICATION_ID,
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(com.autobile.runtime.R.drawable.ic_autobile_agent)
                .setContentTitle("Autobile is learning")
                .setContentText("Perform the task, then return here to finish teaching.")
                .setContentIntent(reopen)
                .setOngoing(true)
                .setSilent(true)
                .build(),
        )
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "autobile_teaching"
        private const val NOTIFICATION_ID = 4202

        fun start(context: Context) {
            context.startForegroundService(Intent(context, TeachingForegroundService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TeachingForegroundService::class.java))
        }
    }
}

package com.example.voiceannouncer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * שירות רקע קבוע (Foreground). בלעדיו המערכת סוגרת את התהליך זמן קצר אחרי
 * שהאפליקציה נסגרת, ואז השיחות וההודעות לא מוקראות.
 */
class KeepAliveService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Speaker.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            goForeground()
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun goForeground() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(CHANNEL, "שירות רקע", NotificationManager.IMPORTANCE_MIN)
        channel.setShowBadge(false)
        nm.createNotificationChannel(channel)

        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("קורא הודעות פעיל")
            .setContentText("מקריא שיחות והודעות ברקע")
            .setSmallIcon(android.R.drawable.ic_lock_silent_mode_off)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIF_ID, n)
        }
    }

    companion object {
        private const val CHANNEL = "keepalive"
        private const val NOTIF_ID = 1

        fun start(ctx: Context) {
            try {
                if (!Prefs(ctx).keepAlive) return
                ctx.applicationContext.startForegroundService(
                    Intent(ctx.applicationContext, KeepAliveService::class.java)
                )
            } catch (e: Exception) {
                // המערכת לא מאפשרת הפעלה כרגע; ננסה שוב בהזדמנות הבאה
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.applicationContext.stopService(
                    Intent(ctx.applicationContext, KeepAliveService::class.java)
                )
            } catch (e: Exception) {
            }
        }
    }
}

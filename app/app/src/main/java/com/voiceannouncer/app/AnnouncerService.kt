package com.voiceannouncer.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

/** שירות רקע קבוע (עם התראה קטנה) שמונע מהמערכת להרוג את האפליקציה כשהיא סגורה. */
class AnnouncerService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Speaker.init(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel("svc", "שירות פעיל", NotificationManager.IMPORTANCE_MIN)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "svc")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("קורא הודעות פעיל")
            .setContentText("מקריא שיחות והודעות נכנסות")
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, 1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(1, n)
            }
        } catch (_: Exception) {
        }
        return START_STICKY
    }
}

/** הפעלת שירות הרקע (בטוחה לקריאה מכל מקום) */
fun startAnnouncer(ctx: Context) {
    try {
        ContextCompat.startForegroundService(ctx, Intent(ctx, AnnouncerService::class.java))
    } catch (_: Exception) {
    }
}

/** מפעיל את השירות מחדש אחרי הדלקת הטלפון ואחרי עדכון האפליקציה */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs(context).enabled) {
            Speaker.init(context)
            startAnnouncer(context)
        }
    }
}

package com.voiceannouncer.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** קורא הודעות נכנסות (וואטסאפ / SMS) מתוך ההתראות. עובד גם כשהאפליקציה סגורה. */
class MessageListener : NotificationListenerService() {

    private var lastKey = ""
    private var lastTime = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        Speaker.init(this)
        startAnnouncer(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        if (sbn.packageName == packageName) return
        if (!enabledFor(sbn.packageName, prefs)) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isEmpty() && body.isEmpty()) return

        // מניעת הקראה כפולה של אותה הודעה
        val key = "${sbn.packageName}|$title|$body"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTime < 10_000) return
        lastKey = key
        lastTime = now

        if (!Speaker.allowed(this)) return

        val text = fillTemplate(
            prefs.tplMessage,
            name = title,
            text = if (prefs.readBody) body.take(250) else ""
        )
        Speaker.speak(this, text)
    }

    private fun enabledFor(pkg: String, p: Prefs): Boolean = when (pkg) {
        "com.whatsapp", "com.whatsapp.w4b" -> p.whatsapp
        "com.google.android.apps.messaging",
        "com.android.mms",
        "com.android.messaging",
        "com.samsung.android.messaging" -> p.sms
        else -> false
    }
}

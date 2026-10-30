package com.voiceannouncer.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

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
        val (body, source) = extractBody(n)
        if (title.isEmpty() && body.isEmpty()) return
        prefs.lastMsgDebug = "שולח: $title\nתוכן: ${body.ifEmpty { "(לא נמצא)" }.take(60)}\nמקור התוכן: $source"

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

    /**
     * מחלץ את תוכן ההודעה. אפליקציות כמו וואטסאפ ו-Google Messages לא תמיד שמות את הטקסט
     * בשדה הרגיל, אז בודקים כמה מקומות לפי סדר: ההודעה האחרונה בשיחה, הטקסט הרגיל,
     * טקסט מורחב, ושורות הרשימה.
     */
    private fun extractBody(n: Notification): Pair<String, String> {
        val ex = n.extras
        try {
            val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
            val last = style?.messages?.lastOrNull()?.text?.toString()?.trim().orEmpty()
            if (last.isNotEmpty()) return last to "הודעה אחרונה בשיחה"
        } catch (_: Exception) {
        }
        val text = ex?.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (text.isNotEmpty()) return text to "טקסט רגיל"
        val big = ex?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim().orEmpty()
        if (big.isNotEmpty()) return big to "טקסט מורחב"
        val lines = ex?.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
        val lastLine = lines?.lastOrNull()?.toString()?.trim().orEmpty()
        if (lastLine.isNotEmpty()) return lastLine to "שורות"
        val sub = ex?.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()?.trim().orEmpty()
        if (sub.isNotEmpty()) return sub to "תת-טקסט"
        return "" to "אין"
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

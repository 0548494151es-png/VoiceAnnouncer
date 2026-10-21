package com.example.voiceannouncer

import android.app.Notification
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * שירות האזנה להתראות. המערכת מקשרת אותו לבד (גם אחרי הפעלה מחדש של הטלפון).
 */
class MessageListener : NotificationListenerService() {

    private val recent = HashMap<String, Long>()

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogStore.add("שירות ההתראות התחבר")
        KeepAliveService.start(this)
    }

    override fun onListenerDisconnected() {
        LogStore.add("שירות ההתראות התנתק, מנסה להתחבר מחדש")
        NotificationListenerService.requestRebind(ComponentName(this, MessageListener::class.java))
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        val prefs = Prefs(this)
        if (!prefs.enabled) return

        val pkg = sbn.packageName
        if (pkg == packageName) return

        val n = sbn.notification ?: return

        // התראת שיחה: אם יש בה שם (ולא רק מספר), מעבירים אותו להכרזת השיחה
        if (n.category == Notification.CATEGORY_CALL) {
            if (prefs.announceCalls) {
                val callTitle = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
                    ?.toString()?.trim().orEmpty()
                if (callTitle.any { it.isLetter() }) {
                    CallAnnouncer.onCallNotificationName(this, callTitle)
                }
            }
            return
        }

        if (!prefs.announceMessages) return
        if (pkg in prefs.blockedApps) {
            LogStore.add("התראה מ-$pkg דולגה: האפליקציה חסומה")
            return
        }
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val category = n.category
        if (!prefs.readAllNotifications) {
            val isMessage = category == Notification.CATEGORY_MESSAGE ||
                category == Notification.CATEGORY_EMAIL ||
                category == Notification.CATEGORY_SOCIAL
            if (!isMessage) {
                LogStore.add("התראה מ-$pkg דולגה: לא מסומנת כהודעה (${category ?: "ללא סוג"})")
                return
            }
        }

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val text = (extras.getCharSequence(Notification.EXTRA_TEXT)
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return

        // מניעת הקראה כפולה כשאפליקציה מעדכנת את אותה התראה
        val key = "$pkg|$title|$text"
        val now = System.currentTimeMillis()
        synchronized(recent) {
            recent.values.removeAll { now - it > 15000 }
            if (recent.containsKey(key)) return
            recent[key] = now
        }

        val sender = if (title.isNotEmpty()) title else appLabel(pkg)
        val body = if (prefs.readContent && text.isNotEmpty()) ". ${text.take(300)}" else ""
        Speaker.announce(this, "הודעה מאת ${sender}${body}")
    }

    private fun appLabel(pkg: String): String {
        return try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }
    }
}

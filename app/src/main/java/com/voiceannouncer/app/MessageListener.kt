package com.voiceannouncer.app

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat

/** סוג האפליקציה: "wa" וואטסאפ, "sms" הודעות, או null לכל אפליקציה אחרת. */
fun builtInKind(pkg: String): String? = when {
    pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b" -> "wa"
    pkg == "com.google.android.apps.messaging" ||
        pkg == "com.android.mms" ||
        pkg == "com.android.messaging" ||
        pkg == "com.samsung.android.messaging" ||
        pkg.endsWith(".mms") -> "sms"
    else -> null
}

/** קורא הודעות נכנסות (וואטסאפ / SMS / אפליקציות שאושרו) מתוך ההתראות. עובד גם כשהאפליקציה סגורה. */
class MessageListener : NotificationListenerService() {

    private var lastKey = ""
    private var lastTime = 0L

    override fun onListenerConnected() {
        super.onListenerConnected()
        DiagLog.log(this, "מאזין ההתראות התחבר")
        Speaker.init(this)
        startAnnouncer(this)
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        DiagLog.log(this, "מאזין ההתראות התנתק, מנסה להתחבר מחדש")
        try {
            NotificationListenerService.requestRebind(
                android.content.ComponentName(this, MessageListener::class.java)
            )
        } catch (_: Exception) {
        }
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = Prefs(this)
        if (!prefs.enabled) return
        val pkg = sbn.packageName
        if (pkg == packageName) return

        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return

        val kind = builtInKind(pkg)
        val label = if (kind == null) labelOf(pkg) else ""
        if (kind == null && pkg != "android" && pkg != "com.android.systemui") {
            // רושם את האפליקציה ברשימה, כדי שאפשר יהיה לאשר אותה מתוך המסך הראשי
            if (prefs.noteSeen(pkg, label)) DiagLog.log(this, "אפליקציה חדשה שהתריעה: $label ($pkg)")
        }

        val enabled = when (kind) {
            "wa" -> prefs.whatsapp
            "sms" -> prefs.sms
            else -> prefs.readApps.contains(pkg)
        }
        if (!enabled) return

        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val (body, source) = extractBody(n)
        if (title.isEmpty() && body.isEmpty()) return
        prefs.lastMsgDebug = "שולח: $title\nתוכן: ${body.ifEmpty { "(לא נמצא)" }.take(60)}\nמקור התוכן: $source"

        // מניעת הקראה כפולה של אותה הודעה
        val key = "$pkg|$title|$body"
        val now = System.currentTimeMillis()
        if (key == lastKey && now - lastTime < 10_000) return
        lastKey = key
        lastTime = now

        val bodyOut = if (prefs.readBody) body.take(250) else ""
        val text: String
        if (kind == null) {
            // אפליקציה אחרת (תזכורת וכו'): שם האפליקציה, הכותרת והתוכן
            val parts = ArrayList<String>()
            parts.add(label)
            if (title.isNotEmpty()) parts.add(title)
            if (bodyOut.isNotEmpty() && bodyOut != title) parts.add(bodyOut)
            text = parts.joinToString(". ")
        } else {
            val tpl = prefs.tplMessage
            // אם בנוסח ההודעה אין {text} (למשל נמחק בטעות) - מוסיפים את התוכן בסוף, כדי שלא יוקרא רק השולח
            text = if (bodyOut.isNotEmpty() && !tpl.contains("{text}")) {
                fillTemplate("$tpl. {text}", name = title, text = bodyOut)
            } else {
                fillTemplate(tpl, name = title, text = bodyOut)
            }
        }

        val deny = Speaker.denyReason(this)
        if (deny != null) {
            DiagLog.log(this, "לא מקריא הודעה מ-${label.ifEmpty { pkg.substringAfterLast('.') }}: $deny")
            return
        }
        DiagLog.log(
            this,
            "מקריא הודעה מ-${label.ifEmpty { pkg.substringAfterLast('.') }}: שולח ${title.length} תווים, תוכן ${body.length} (מקור: $source)"
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
        // הודעות בסגנון שיחה (וואטסאפ, Google Messages): קוראים ישירות את רשימת ההודעות
        try {
            val arr = ex?.getParcelableArray(Notification.EXTRA_MESSAGES)
            val lastBundle = arr?.lastOrNull() as? Bundle
            val t = lastBundle?.getCharSequence("text")?.toString()?.trim().orEmpty()
            if (t.isNotEmpty()) return t to "רשימת הודעות"
        } catch (_: Exception) {
        }
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
        val ticker = n.tickerText?.toString()?.trim().orEmpty()
        if (ticker.isNotEmpty()) return ticker to "טיקר"
        return "" to "אין"
    }
}

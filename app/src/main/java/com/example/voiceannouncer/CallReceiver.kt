package com.example.voiceannouncer

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.TelephonyManager

class CallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                // מוודא שהשירות הקבוע פעיל, כדי שהתהליך לא ייסגר באמצע ההכרזה
                KeepAliveService.start(context)
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                LogStore.add("שיחה נכנסת: " + (if (number.isNullOrBlank()) "התקבל בלי מספר" else "התקבל עם מספר"))
                CallAnnouncer.onRinging(context.applicationContext, number)
            }
            TelephonyManager.EXTRA_STATE_IDLE,
            TelephonyManager.EXTRA_STATE_OFFHOOK -> CallAnnouncer.onStopped()
        }
    }
}

/** מנהל את הכרזת השיחה הנכנסת (שם איש קשר, חזרה על ההכרזה בזמן צלצול) */
object CallAnnouncer {
    private val handler = Handler(Looper.getMainLooper())
    private var ringing = false
    private var ringStart = 0L
    private var numberKnown = false
    private var waitingForName = false
    private var repeats = 0
    private var phrase: String? = null
    private var notifName: String? = null
    private var notifTime = 0L
    private var repeatRunnable: Runnable? = null
    private var hiddenRunnable: Runnable? = null
    private var waitRunnable: Runnable? = null

    fun onRinging(ctx: Context, number: String?) {
        val prefs = Prefs(ctx)
        if (!prefs.enabled || !prefs.announceCalls) {
            LogStore.add("שיחה: דולגה, כבויה בהגדרות")
            return
        }

        // אם המערכת פספסה את סיום השיחה הקודמת, המצב מתאפס אחרי דקה
        if (!ringing || System.currentTimeMillis() - ringStart > 60000) {
            clearTimers()
            ringing = true
            ringStart = System.currentTimeMillis()
            numberKnown = false
            waitingForName = false
            repeats = 0
            phrase = null
            val hasCallLog = ctx.checkSelfPermission(Manifest.permission.READ_CALL_LOG) ==
                PackageManager.PERMISSION_GRANTED
            val r = Runnable {
                if (ringing && !numberKnown) {
                    start(ctx, if (hasCallLog) "שיחה נכנסת ממספר חסוי" else "שיחה נכנסת")
                }
            }
            hiddenRunnable = r
            handler.postDelayed(r, 2500)
        }

        if (!number.isNullOrBlank() && !numberKnown) {
            numberKnown = true
            hiddenRunnable?.let { handler.removeCallbacks(it) }

            val name = lookupName(ctx, number)
            if (name != null) {
                start(ctx, "שיחה נכנסת, ${name}")
                return
            }

            val recent = notifName
            if (recent != null && System.currentTimeMillis() - notifTime < 5000) {
                start(ctx, "שיחה נכנסת, ${recent}")
                return
            }

            // לא נמצא באנשי הקשר: מחכים רגע לשם מהתראת השיחה (למשל טרוקולר)
            val digits = number.filter { it.isDigit() }.toCharArray().joinToString(" ")
            waitingForName = true
            val w = Runnable {
                if (ringing && waitingForName) {
                    waitingForName = false
                    start(ctx, "שיחה נכנסת, מספר ${digits}")
                }
            }
            waitRunnable = w
            handler.postDelayed(w, 2000)
        }
    }

    /** נקרא מ-MessageListener כשמגיעה התראת שיחה עם שם */
    fun onCallNotificationName(ctx: Context, name: String) {
        val app = ctx.applicationContext
        handler.post {
            notifName = name
            notifTime = System.currentTimeMillis()
            if (ringing && waitingForName) {
                waitingForName = false
                waitRunnable?.let { handler.removeCallbacks(it) }
                start(app, "שיחה נכנסת, ${name}")
            }
        }
    }

    fun onStopped() {
        if (ringing) LogStore.add("שיחה: הסתיימה או נענתה")
        ringing = false
        numberKnown = false
        waitingForName = false
        notifName = null
        clearTimers()
        Speaker.stop()
    }

    private fun clearTimers() {
        hiddenRunnable?.let { handler.removeCallbacks(it) }
        waitRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun start(ctx: Context, text: String) {
        phrase = text
        Speaker.announce(ctx, text, ignoreQuiet = true)
        scheduleRepeat(ctx)
    }

    private fun scheduleRepeat(ctx: Context) {
        if (!Prefs(ctx).repeatCall) return
        val r = Runnable {
            if (ringing && repeats < 4) {
                repeats++
                phrase?.let { Speaker.announce(ctx, it, ignoreQuiet = true) }
                scheduleRepeat(ctx)
            }
        }
        repeatRunnable = r
        handler.postDelayed(r, 7000)
    }

    private fun lookupName(ctx: Context, number: String): String? {
        if (ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
                Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}

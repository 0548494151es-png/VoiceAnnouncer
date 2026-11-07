package com.voiceannouncer.app

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
import androidx.core.content.ContextCompat

/**
 * מזהה שיחה נכנסת ומקריא את שם המתקשר. עובד גם כשהאפליקציה סגורה.
 *
 * סדר העדיפויות לשם: איש קשר שמור ← שם ש-Truecaller מזהה ← מספר ← "מספר חסוי".
 * כשהמתקשר לא שמור ו-Truecaller זמין, מחכים רגע קצר לזיהוי שלו לפני שמכריזים על מספר או על "חסוי".
 * אם הזיהוי מגיע אחרי שכבר התחלנו להכריז, ההכרזה מתחילה מחדש עם השם.
 */
class CallReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != TelephonyManager.ACTION_PHONE_STATE_CHANGED) return
        val app = context.applicationContext
        val prefs = Prefs(app)

        when (intent.getStringExtra(TelephonyManager.EXTRA_STATE)) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                if (!prefs.calls) return
                ringing = true
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
                if (number.isNullOrBlank()) {
                    // לפעמים המספר מגיע בשידור השני. אם עד 2.5 שניות לא הגיע - כנראה מספר חסוי
                    // (ואם Truecaller יזהה בינתיים, יוקרא השם שלו)
                    if (!announced && fallback == null) scheduleFallback(app, 2500)
                    return
                }
                if (number == lastNumber && (announced || fallback != null)) return
                lastNumber = number

                val name = nameFor(app, number)
                if (name != null) {
                    // מתקשר שמור באנשי קשר: מכריזים מיד
                    cancelFallback()
                    contactAnnounced = true
                    announce(app, fillTemplate(prefs.tplKnown, name = name, number = spaced(number)))
                    return
                }
                val tc = tcName
                if (tc != null) {
                    cancelFallback()
                    announce(app, tcText(prefs, tc, number))
                    return
                }
                if (TruecallerBridge.canHelp(app)) {
                    // לא שמור באנשי קשר: נותנים ל-Truecaller רגע לזהות
                    scheduleFallback(app, TC_WAIT_MS)
                } else {
                    cancelFallback()
                    announce(app, fillTemplate(prefs.tplUnknown, number = spaced(number)))
                }
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                ringing = false
                announced = false
                contactAnnounced = false
                lastNumber = null
                tcName = null
                cancelFallback()
                Speaker.stopRinging()
            }
        }
    }

    private fun nameFor(ctx: Context, number: String): String? {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS)
            != PackageManager.PERMISSION_GRANTED
        ) return null
        return try {
            val uri = Uri.withAppendedPath(
                ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)
            )
            ctx.contentResolver.query(
                uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** כמה זמן מחכים לזיהוי של Truecaller לפני שמכריזים על מספר לא שמור */
        private const val TC_WAIT_MS = 2000L

        private val handler = Handler(Looper.getMainLooper())
        private var ringing = false
        private var announced = false
        private var contactAnnounced = false
        private var lastNumber: String? = null
        private var tcName: String? = null
        private var fallback: Runnable? = null

        /** האם יש כרגע שיחה שמצלצלת (משמש את שירות הזיהוי של Truecaller) */
        fun isRinging(): Boolean = ringing

        private fun spaced(number: String) =
            number.filter { it.isDigit() }.toCharArray().joinToString(" ")

        private fun tcText(prefs: Prefs, name: String, number: String?) =
            fillTemplate(prefs.tplTc, name = name, number = number?.let { spaced(it) } ?: "")

        private fun cancelFallback() {
            fallback?.let { handler.removeCallbacks(it) }
            fallback = null
        }

        /** מכריז (או מתחיל מחדש עם נוסח חדש). לא עושה כלום אם מותר לא להקריא כרגע (שקט וכו'). */
        private fun announce(app: Context, text: String) {
            if (!Speaker.allowed(app)) return
            announced = true
            Speaker.startRinging(app, text)
        }

        /** אם עד סוף ההמתנה לא הגיע שם - מכריזים על המספר, או "חסוי" אם אין מספר */
        private fun scheduleFallback(app: Context, delayMs: Long) {
            cancelFallback()
            val r = Runnable {
                fallback = null
                if (!ringing || announced) return@Runnable
                val prefs = Prefs(app)
                val number = lastNumber
                val tc = tcName
                val text = when {
                    tc != null -> tcText(prefs, tc, number)
                    number != null -> fillTemplate(prefs.tplUnknown, number = spaced(number))
                    else -> fillTemplate(prefs.tplPrivate)
                }
                announce(app, text)
            }
            fallback = r
            handler.postDelayed(r, delayMs)
        }

        /**
         * נקרא כש-Truecaller זיהתה את המתקשר (משירות הנגישות או מהתראה). נקרא מה-thread הראשי.
         * אם כבר הוכרז שם מאנשי הקשר - לא נוגעים. אם כבר הוכרז מספר או "חסוי" - ההכרזה מתחילה מחדש עם השם.
         */
        fun onTruecallerName(ctx: Context, name: String) {
            if (!ringing || contactAnnounced) return
            if (tcName == name) return
            val app = ctx.applicationContext
            val prefs = Prefs(app)
            if (!prefs.calls || !prefs.tcEnabled) return
            tcName = name
            cancelFallback()
            DiagLog.log(app, "מכריז לפי Truecaller: $name")
            announce(app, tcText(prefs, name, lastNumber))
        }
    }
}

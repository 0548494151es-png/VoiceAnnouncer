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

/** מזהה שיחה נכנסת ומקריא את שם המתקשר. עובד גם כשהאפליקציה סגורה. */
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
                    handler.postDelayed({
                        if (ringing && !announced && Speaker.allowed(app)) {
                            announced = true
                            Speaker.startRinging(app, "שיחה ממספר חסוי")
                        }
                    }, 2500)
                    return
                }
                if (announced && number == lastNumber) return
                announced = true
                lastNumber = number
                if (!Speaker.allowed(app)) return
                val name = nameFor(app, number)
                val text = if (name != null) {
                    "שיחה מ$name"
                } else {
                    "שיחה ממספר " + number.filter { it.isDigit() }.toCharArray().joinToString(" ")
                }
                Speaker.startRinging(app, text)
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK, TelephonyManager.EXTRA_STATE_IDLE -> {
                ringing = false
                announced = false
                lastNumber = null
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
        private val handler = Handler(Looper.getMainLooper())
        private var ringing = false
        private var announced = false
        private var lastNumber: String? = null
    }
}

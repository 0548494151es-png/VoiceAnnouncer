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
                @Suppress("DEPRECATION")
                val number = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
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
    private var numberKnown = false
    private var repeats = 0
    private var phrase: String? = null
    private var repeatRunnable: Runnable? = null
    private var hiddenRunnable: Runnable? = null

    fun onRinging(ctx: Context, number: String?) {
        val prefs = Prefs(ctx)
        if (!prefs.enabled || !prefs.announceCalls) return

        if (!ringing) {
            ringing = true
            numberKnown = false
            repeats = 0
            phrase = null
            // המערכת שולחת את המספר בשידור שני; אם לא הגיע - מספר חסוי (או שאין הרשאת יומן שיחות)
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
            val who = name ?: ("מספר " + number.filter { it.isDigit() }.toCharArray().joinToString(" "))
            start(ctx, "שיחה נכנסת, ${who}")
        }
    }

    fun onStopped() {
        ringing = false
        numberKnown = false
        hiddenRunnable?.let { handler.removeCallbacks(it) }
        repeatRunnable?.let { handler.removeCallbacks(it) }
        Speaker.stop()
    }

    private fun start(ctx: Context, text: String) {
        phrase = text
        // שיחות נכנסות מוכרזות גם בשעות שקט
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

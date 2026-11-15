package com.voiceannouncer.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * יומן אבחון קטן. שומר את 150 השורות האחרונות, וכן רשימה נפרדת של כשלי הקראה (25 אחרונים)
 * כדי שסיבת הכישלון לא תידחק החוצה בגלל שורות רגילות.
 */
object DiagLog {
    private const val MAX_LINES = 150
    private const val MAX_FAILS = 25
    private const val SCREEN_LINES = 15
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    private fun sp(ctx: Context) =
        ctx.applicationContext.getSharedPreferences("diag", Context.MODE_PRIVATE)

    private fun append(ctx: Context, key: String, max: Int, line: String) {
        try {
            val s = sp(ctx)
            val old = s.getString(key, "") ?: ""
            val lines = if (old.isEmpty()) ArrayList<String>() else ArrayList(old.split("\n"))
            lines.add(line)
            while (lines.size > max) lines.removeAt(0)
            s.edit().putString(key, lines.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun log(ctx: Context, msg: String) {
        append(ctx, "log", MAX_LINES, fmt.format(Date()) + "  " + msg.replace("\n", " "))
    }

    /** כשל בהקראה: נרשם ביומן הרגיל (עם ⚠) וגם ברשימת הכשלים הנפרדת */
    @Synchronized
    fun fail(ctx: Context, reason: String) {
        val line = fmt.format(Date()) + "  " + reason.replace("\n", " ")
        append(ctx, "log", MAX_LINES, fmt.format(Date()) + "  ⚠ " + reason.replace("\n", " "))
        append(ctx, "fails", MAX_FAILS, line)
    }

    /** היומן המלא (לשיתוף): קודם כשלי ההקראה, ואחריהם כל היומן */
    @Synchronized
    fun full(ctx: Context): String = try {
        val s = sp(ctx)
        val fails = s.getString("fails", "") ?: ""
        val log = s.getString("log", "") ?: ""
        if (fails.isEmpty()) log
        else "── כשלי הקראה אחרונים ──\n$fails\n\n── יומן ──\n$log"
    } catch (_: Exception) {
        ""
    }

    /** מה שמוצג במסך הראשי: הכשל האחרון (אם יש) ומתחתיו השורות האחרונות ביומן */
    @Synchronized
    fun screen(ctx: Context): String = try {
        val s = sp(ctx)
        val fails = s.getString("fails", "") ?: ""
        val log = s.getString("log", "") ?: ""
        val tail = log.split("\n").filter { it.isNotBlank() }.takeLast(SCREEN_LINES).joinToString("\n")
        val last = fails.split("\n").lastOrNull { it.isNotBlank() }
        if (last == null) tail else "⚠ הכשל האחרון: $last\n\n$tail"
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun text(ctx: Context): String = try {
        sp(ctx).getString("log", "") ?: ""
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            sp(ctx).edit().remove("log").remove("fails").apply()
        } catch (_: Exception) {
        }
    }
}

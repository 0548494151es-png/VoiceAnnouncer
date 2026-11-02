package com.voiceannouncer.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** יומן אבחון קטן: שומר את 60 השורות האחרונות כדי שאפשר יהיה להבין למה משהו לא הוקרא. */
object DiagLog {
    private const val MAX_LINES = 60
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)

    @Synchronized
    fun log(ctx: Context, msg: String) {
        try {
            val sp = ctx.applicationContext.getSharedPreferences("diag", Context.MODE_PRIVATE)
            val old = sp.getString("log", "") ?: ""
            val lines = if (old.isEmpty()) ArrayList() else ArrayList(old.split("\n"))
            lines.add(fmt.format(Date()) + "  " + msg.replace("\n", " "))
            while (lines.size > MAX_LINES) lines.removeAt(0)
            sp.edit().putString("log", lines.joinToString("\n")).apply()
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun text(ctx: Context): String = try {
        ctx.applicationContext.getSharedPreferences("diag", Context.MODE_PRIVATE)
            .getString("log", "") ?: ""
    } catch (_: Exception) {
        ""
    }

    @Synchronized
    fun clear(ctx: Context) {
        try {
            ctx.applicationContext.getSharedPreferences("diag", Context.MODE_PRIVATE)
                .edit().remove("log").apply()
        } catch (_: Exception) {
        }
    }
}

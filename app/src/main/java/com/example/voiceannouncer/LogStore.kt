package com.example.voiceannouncer

import android.content.Context
import android.content.SharedPreferences
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** יומן פעילות קצר, נשמר בטלפון בלבד (לא כולל תוכן הודעות) */
object LogStore {
    private var sp: SharedPreferences? = null
    private val fmt = SimpleDateFormat("dd/MM HH:mm:ss", Locale.US)
    private const val MAX = 150

    fun init(ctx: Context) {
        if (sp == null) {
            sp = ctx.applicationContext.getSharedPreferences("log", Context.MODE_PRIVATE)
        }
    }

    @Synchronized
    fun add(msg: String) {
        val s = sp ?: return
        val old = s.getString("l", "") ?: ""
        val lines = old.split("\n").filter { it.isNotEmpty() }.takeLast(MAX - 1).toMutableList()
        lines.add(fmt.format(Date()) + "  " + msg)
        s.edit().putString("l", lines.joinToString("\n")).apply()
    }

    /** החדש ביותר למעלה */
    @Synchronized
    fun read(): String {
        val s = sp ?: return ""
        val old = s.getString("l", "") ?: ""
        return old.split("\n").filter { it.isNotEmpty() }.reversed().joinToString("\n")
    }

    @Synchronized
    fun clear() {
        sp?.edit()?.putString("l", "")?.apply()
    }
}

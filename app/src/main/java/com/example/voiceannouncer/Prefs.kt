package com.example.voiceannouncer

import android.content.Context
import java.util.Calendar

class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", true)
        set(v) { sp.edit().putBoolean("enabled", v).apply() }

    /** קריאה רק כשמחובר התקן שמע בלוטוס */
    var bluetoothOnly: Boolean
        get() = sp.getBoolean("bluetoothOnly", true)
        set(v) { sp.edit().putBoolean("bluetoothOnly", v).apply() }

    /** לא להקריא כשהטלפון במצב שקט או רטט */
    var respectSilent: Boolean
        get() = sp.getBoolean("respectSilent", true)
        set(v) { sp.edit().putBoolean("respectSilent", v).apply() }

    /** שירות רקע קבוע, כדי שיעבוד גם כשהאפליקציה סגורה */
    var keepAlive: Boolean
        get() = sp.getBoolean("keepAlive", true)
        set(v) { sp.edit().putBoolean("keepAlive", v).apply() }

    var announceCalls: Boolean
        get() = sp.getBoolean("announceCalls", true)
        set(v) { sp.edit().putBoolean("announceCalls", v).apply() }

    var announceMessages: Boolean
        get() = sp.getBoolean("announceMessages", true)
        set(v) { sp.edit().putBoolean("announceMessages", v).apply() }

    /** להקריא גם את תוכן ההודעה, לא רק את שם השולח */
    var readContent: Boolean
        get() = sp.getBoolean("readContent", true)
        set(v) { sp.edit().putBoolean("readContent", v).apply() }

    /** להקריא כל התראה, לא רק הודעות/מיילים/רשתות חברתיות */
    var readAllNotifications: Boolean
        get() = sp.getBoolean("readAll", false)
        set(v) { sp.edit().putBoolean("readAll", v).apply() }

    /** לחזור על הכרזת השיחה כל כמה שניות כל עוד הטלפון מצלצל */
    var repeatCall: Boolean
        get() = sp.getBoolean("repeatCall", true)
        set(v) { sp.edit().putBoolean("repeatCall", v).apply() }

    var speechRate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) { sp.edit().putFloat("rate", v).apply() }

    var pitch: Float
        get() = sp.getFloat("pitch", 1.0f)
        set(v) { sp.edit().putFloat("pitch", v).apply() }

    var quietEnabled: Boolean
        get() = sp.getBoolean("quietEnabled", false)
        set(v) { sp.edit().putBoolean("quietEnabled", v).apply() }

    /** דקות מחצות */
    var quietStart: Int
        get() = sp.getInt("quietStart", 23 * 60)
        set(v) { sp.edit().putInt("quietStart", v).apply() }

    var quietEnd: Int
        get() = sp.getInt("quietEnd", 7 * 60)
        set(v) { sp.edit().putInt("quietEnd", v).apply() }

    /** אפליקציות שהמשתמש חסם (לא יוקראו) */
    var blockedApps: Set<String>
        get() = sp.getStringSet("blocked", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("blocked", HashSet(v)).apply() }

    fun isQuietNow(): Boolean {
        if (!quietEnabled) return false
        val c = Calendar.getInstance()
        val now = c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE)
        val s = quietStart
        val e = quietEnd
        return if (s <= e) now in s until e else (now >= s || now < e)
    }
}

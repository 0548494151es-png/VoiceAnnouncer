package com.voiceannouncer.app

import android.content.Context
import android.content.SharedPreferences

/** כל ההגדרות של האפליקציה, נשמרות בטלפון. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()

    /** מתג ראשי */
    var enabled: Boolean
        get() = b("enabled", true)
        set(v) = putB("enabled", v)

    var calls: Boolean
        get() = b("calls", true)
        set(v) = putB("calls", v)

    /** לחזור על שם המתקשר כל כמה שניות כל עוד הטלפון מצלצל */
    var repeatCalls: Boolean
        get() = b("repeatCalls", true)
        set(v) = putB("repeatCalls", v)

    var whatsapp: Boolean
        get() = b("whatsapp", true)
        set(v) = putB("whatsapp", v)

    var sms: Boolean
        get() = b("sms", true)
        set(v) = putB("sms", v)

    var telegram: Boolean
        get() = b("telegram", false)
        set(v) = putB("telegram", v)

    /** להקריא את תוכן ההודעה (ולא רק את שם השולח) */
    var readBody: Boolean
        get() = b("readBody", true)
        set(v) = putB("readBody", v)

    /** להקריא רק כשמחובר בלוטוס */
    var btOnly: Boolean
        get() = b("btOnly", false)
        set(v) = putB("btOnly", v)

    /** לא להקריא כשהטלפון על שקט / רטט */
    var skipSilent: Boolean
        get() = b("skipSilent", true)
        set(v) = putB("skipSilent", v)

    /** גובה קול: נמוך = יותר גברי */
    var pitch: Float
        get() = sp.getFloat("pitch", 0.8f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    var rate: Float
        get() = sp.getFloat("rate", 1.0f)
        set(v) = sp.edit().putFloat("rate", v).apply()

    /** שם הקול שנבחר (ריק = אוטומטי) */
    var voice: String
        get() = sp.getString("voice", "") ?: ""
        set(v) = sp.edit().putString("voice", v).apply()

    /** מאגר GitHub לעדכונים, בפורמט משתמש/מאגר */
    var repo: String
        get() = sp.getString("repo", "0548494151es-png/VoiceAnnouncer") ?: ""
        set(v) = sp.edit().putString("repo", v).apply()

    /** טוקן GitHub - נדרש רק אם המאגר פרטי */
    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit().putString("token", v).apply()
}

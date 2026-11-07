package com.voiceannouncer.app

import android.content.Context
import android.content.SharedPreferences

/** כל ההגדרות של האפליקציה, נשמרות בטלפון. */
class Prefs(ctx: Context) {
    private val sp: SharedPreferences =
        ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    private fun b(key: String, def: Boolean) = sp.getBoolean(key, def)
    private fun putB(key: String, v: Boolean) = sp.edit().putBoolean(key, v).apply()
    private fun s(key: String, def: String) = sp.getString(key, def) ?: def
    private fun putS(key: String, v: String) = sp.edit().putString(key, v).apply()

    /** מתג ראשי */
    var enabled: Boolean
        get() = b("enabled", true)
        set(v) = putB("enabled", v)

    var calls: Boolean
        get() = b("calls", true)
        set(v) = putB("calls", v)

    // ---------- הגדרות הכרזת שיחות ----------

    /** כמה פעמים להכריז על המתקשר (1 = פעם אחת, בלי חזרות) */
    var repeatCount: Int
        get() = sp.getInt("repeatCount", 3).coerceIn(1, 10)
        set(v) = sp.edit().putInt("repeatCount", v).apply()

    /** לחזור על ההכרזה עד שעונים או שהשיחה מסתיימת (מתעלם ממספר הפעמים) */
    var repeatForever: Boolean
        get() = b("repeatForever", false)
        set(v) = putB("repeatForever", v)

    /** מרווח בשניות בין סיום הכרזה אחת לתחילת הבאה */
    var repeatGapSec: Int
        get() = sp.getInt("repeatGapSec", 3).coerceIn(1, 30)
        set(v) = sp.edit().putInt("repeatGapSec", v).apply()

    /** מהירות דיבור להכרזת שיחות (נפרדת מהמהירות של הודעות) */
    var callRate: Float
        get() = sp.getFloat("callRate", 1.0f)
        set(v) = sp.edit().putFloat("callRate", v).apply()

    /** להשתיק את הצלצול בזמן ההכרזה בלבד, ולהחזיר אותו מיד אחריה */
    var muteRing: Boolean
        get() = b("muteRing", true)
        set(v) = putB("muteRing", v)

    /** כשמחובר בלוטוס: להכריז על המתקשר גם ברמקול של הטלפון (בנוסף לאוזניה/רמקול הבלוטוס) */
    var btAlsoSpeaker: Boolean
        get() = b("btAlsoSpeaker", false)
        set(v) = putB("btAlsoSpeaker", v)

    /** עוצמת הצלצול המקורית בזמן שהצלצול מונמך להכרזה (0 = אין הנמכה פעילה). נשמר כדי לשחזר גם אם התהליך נהרג באמצע. */
    var ringBackup: Int
        get() = sp.getInt("ringBackup", 0)
        set(v) = sp.edit().putInt("ringBackup", v).apply()

    /** אפליקציות נוספות (תזכורות וכו') שהמשתמש אישר להקריא. ברירת מחדל: אף אחת. */
    var readApps: Set<String>
        get() = sp.getStringSet("readApps", emptySet())?.toSet() ?: emptySet()
        set(v) = sp.edit().putStringSet("readApps", HashSet(v)).apply()

    /** אפליקציות שהתריעו לאחרונה, כדי שאפשר יהיה לבחור מהן. כל שורה: חבילה, טאב, שם. */
    var seenAppsRaw: String
        get() = s("seenApps", "")
        set(v) = putS("seenApps", v)

    fun seenApps(): List<Pair<String, String>> =
        seenAppsRaw.split("\n").filter { it.contains("\t") }.map {
            val i = it.indexOf('\t')
            it.substring(0, i) to it.substring(i + 1)
        }

    /** רושם אפליקציה שהתריעה. מחזיר true אם היא חדשה. */
    fun noteSeen(pkg: String, label: String): Boolean {
        val cur = seenApps()
        if (cur.any { it.first == pkg }) return false
        val next = (listOf(pkg to label) + cur).take(40)
        seenAppsRaw = next.joinToString("\n") { it.first + "\t" + it.second }
        return true
    }

    /** בלוטוס: להכריז שיחה דרך ערוץ השיחה של האוזנייה (אם ההכרזה לא נשמעת בבלוטוס) */
    var btVoiceRoute: Boolean
        get() = b("btVoiceRoute", false)
        set(v) = putB("btVoiceRoute", v)

    /** אבחון: מה זוהה בהודעה האחרונה (שולח, תוכן, ומאיפה נלקח התוכן) */
    var lastMsgDebug: String
        get() = s("lastMsgDebug", "")
        set(v) = putS("lastMsgDebug", v)

    /** נוסח ההכרזה: מתקשר שמור באנשי קשר. משתנים: {name} {number} */
    var tplKnown: String
        get() = s("tplKnown", DEF_KNOWN)
        set(v) = putS("tplKnown", v)

    /** נוסח ההכרזה: מספר שלא שמור באנשי קשר */
    var tplUnknown: String
        get() = s("tplUnknown", DEF_UNKNOWN)
        set(v) = putS("tplUnknown", v)

    /** נוסח ההכרזה: מספר חסוי */
    var tplPrivate: String
        get() = s("tplPrivate", DEF_PRIVATE)
        set(v) = putS("tplPrivate", v)

    /** לזהות מתקשרים שלא שמורים באנשי קשר גם לפי Truecaller (קורא את השם שהיא מציגה) */
    var tcEnabled: Boolean
        get() = b("tcEnabled", true)
        set(v) = putB("tcEnabled", v)

    /** נוסח ההכרזה: מתקשר שזוהה ב-Truecaller. משתנים: {name} {number} */
    var tplTc: String
        get() = s("tplTc", DEF_TC)
        set(v) = putS("tplTc", v)

    /** נוסח הקראת הודעות. משתנים: {name} שולח, {text} תוכן ההודעה */
    var tplMessage: String
        get() = s("tplMessage", DEF_MESSAGE)
        set(v) = putS("tplMessage", v)

    fun resetTemplates() {
        tplKnown = DEF_KNOWN
        tplUnknown = DEF_UNKNOWN
        tplPrivate = DEF_PRIVATE
        tplTc = DEF_TC
        tplMessage = DEF_MESSAGE
    }

    // ---------- הודעות ----------

    var whatsapp: Boolean
        get() = b("whatsapp", true)
        set(v) = putB("whatsapp", v)

    var sms: Boolean
        get() = b("sms", true)
        set(v) = putB("sms", v)

    /** להקריא את תוכן ההודעה (ולא רק את שם השולח) */
    var readBody: Boolean
        get() = b("readBody", true)
        set(v) = putB("readBody", v)

    // ---------- תנאים וקול ----------

    /** להקריא רק כשמחובר בלוטוס */
    var btOnly: Boolean
        get() = b("btOnly", false)
        set(v) = putB("btOnly", v)

    /** גובה קול: נמוך = יותר גברי */
    var pitch: Float
        get() = sp.getFloat("pitch", 0.8f)
        set(v) = sp.edit().putFloat("pitch", v).apply()

    /** מהירות דיבור להודעות */
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

    companion object {
        const val DEF_KNOWN = "{name}"
        const val DEF_UNKNOWN = "שיחה ממספר {number}"
        const val DEF_PRIVATE = "שיחה ממספר חסוי"
        const val DEF_TC = "{name}"
        const val DEF_MESSAGE = "הודעה מ{name}. {text}"
    }
}

/** ממלא את המשתנים בנוסח. אם התוצאה ריקה - מחזיר גיבוי כדי שתמיד יהיה מה להקריא. */
fun fillTemplate(template: String, name: String = "", number: String = "", text: String = ""): String {
    val out = template
        .replace("{name}", name)
        .replace("{number}", number)
        .replace("{text}", text)
        .replace(Regex("\\s+"), " ")
        .trim()
        .trimEnd('.', ',', ' ')
    return out.ifBlank { name.ifBlank { number.ifBlank { text } } }
}

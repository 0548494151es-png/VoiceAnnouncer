package com.voiceannouncer.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.telecom.TelecomManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationManagerCompat

/**
 * זיהוי מתקשר לפי Truecaller.
 *
 * Truecaller לא חושף API לאפליקציות אחרות, אז קוראים את השם ממה שהוא מציג על המסך בזמן הצלצול:
 *  1. שירות נגישות (TruecallerService) שרואה רק את החלון של Truecaller ומוציא ממנו את השם.
 *  2. התראת השיחה של Truecaller (רלוונטי רק כש-Truecaller היא אפליקציית הטלפון הראשית).
 * השם שנמצא מועבר ל-CallReceiver, שמקריא אותו במקום "מספר חסוי" או מספר לא שמור.
 */
object TruecallerBridge {
    const val PKG = "com.truecaller"

    /** טקסטים שמופיעים בחלון של Truecaller ואינם שם של מתקשר (השוואה מלאה, בלי הבדל אותיות) */
    private val notNames = setOf(
        "incoming call", "incoming", "answer", "decline", "reject", "ignore", "dismiss",
        "message", "call", "mute", "speaker", "block", "report", "spam", "likely spam",
        "unknown", "private number", "hidden number", "no caller id", "verified", "identified",
        "שיחה נכנסת", "ענה", "דחה", "התעלם", "הודעה", "ספאם", "חסום", "דווח",
        "לא ידוע", "מספר לא ידוע", "מספר פרטי", "מספר חסוי", "אנונימי", "שיחה"
    )

    /** מזהי רכיבים (id) שמרמזים שהטקסט הוא לא שם: מספר, מיקום, ספק, תווית, כפתור */
    private val notNameIds = listOf(
        "number", "phone", "location", "carrier", "operator", "tag", "label", "spam",
        "button", "btn", "action", "status", "time", "timer", "badge", "subtitle", "hint"
    )

    class Cand(val text: String, val id: String)

    fun installed(ctx: Context): Boolean = try {
        ctx.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Exception) {
        false
    }

    /** האם שירות הנגישות של זיהוי Truecaller מופעל בהגדרות הטלפון */
    fun accessibilityOn(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val cn = ComponentName(ctx, TruecallerService::class.java)
        enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    } catch (_: Exception) {
        false
    }

    private fun listenerOn(ctx: Context): Boolean = try {
        NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    } catch (_: Exception) {
        false
    }

    private fun isDefaultDialer(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == PKG
    } catch (_: Exception) {
        false
    }

    /**
     * האם יש סיכוי שנקבל שם מ-Truecaller בזמן הצלצול. אם לא, לא מחכים לו
     * ומכריזים מיד כמו קודם (בלי עיכוב מיותר).
     */
    fun canHelp(ctx: Context): Boolean =
        Prefs(ctx).tcEnabled && installed(ctx) &&
            (accessibilityOn(ctx) || (listenerOn(ctx) && isDefaultDialer(ctx)))

    /** מנקה טקסט ומחזיר אותו רק אם הוא נראה כמו שם של אדם או עסק (לא מספר ולא כפתור) */
    fun cleanName(raw: CharSequence?): String? {
        val s = raw?.toString()?.replace(Regex("\\s+"), " ")?.trim() ?: return null
        if (s.length < 2 || s.length > 40) return null
        if (!s.any { it.isLetter() }) return null
        if (s.count { it.isDigit() } >= 5) return null // כנראה מספר טלפון
        val low = s.lowercase()
        if (low.contains("truecaller")) return null
        if (low in notNames) return null
        if (s.contains('•') || s.contains('·') || s.contains('|')) return null // שורת מיקום / ספק
        return s
    }

    /** בוחר מתוך טקסטי החלון את הטקסט שהכי נראה כמו שם המתקשר */
    fun pickName(cands: List<Cand>): String? {
        val valid = cands.filter { cleanName(it.text) != null }
        if (valid.isEmpty()) return null
        fun idOf(c: Cand) = c.id.substringAfter(":id/").lowercase()
        // קודם: רכיב שה-id שלו מרמז על שם
        valid.firstOrNull {
            val i = idOf(it)
            i.contains("name") && notNameIds.none { bad -> i.contains(bad) }
        }?.let { return cleanName(it.text) }
        // אחרת: הראשון שה-id שלו לא מרמז על משהו אחר
        valid.firstOrNull { c -> idOf(c).let { i -> notNameIds.none { bad -> i.contains(bad) } } }
            ?.let { return cleanName(it.text) }
        return null
    }

    /** מוסיף לרשימה את כל הטקסטים שבעץ הרכיבים */
    fun collect(node: AccessibilityNodeInfo?, out: MutableList<Cand>, depth: Int = 0) {
        if (node == null || depth > 25 || out.size > 80) return
        val id = node.viewIdResourceName ?: ""
        val t = node.text?.toString()?.trim()
        if (!t.isNullOrEmpty()) {
            out.add(Cand(t, id))
        } else {
            val d = node.contentDescription?.toString()?.trim()
            if (!d.isNullOrEmpty()) out.add(Cand(d, id))
        }
        for (i in 0 until node.childCount) collect(node.getChild(i), out, depth + 1)
    }

    /**
     * התראת שיחה של Truecaller (כש-Truecaller היא אפליקציית הטלפון הראשית): הכותרת היא שם המתקשר.
     * מחזיר true אם ההתראה טופלה כהתראת שיחה (ואז לא ממשיכים לטפל בה כהודעה רגילה).
     */
    fun fromNotification(ctx: Context, sbn: StatusBarNotification): Boolean {
        val n = sbn.notification ?: return false
        if (n.category != Notification.CATEGORY_CALL) return false
        if (!Prefs(ctx).tcEnabled) return true
        val title = n.extras?.getCharSequence(Notification.EXTRA_TITLE)
        DiagLog.log(ctx, "Truecaller: התראת שיחה, כותרת: ${title?.toString()?.take(30)}")
        cleanName(title)?.let { CallReceiver.onTruecallerName(ctx, it) }
        return true
    }
}

/**
 * שירות נגישות שמוגדר (ב-truecaller_accessibility.xml) לקבל אירועים רק מהחבילה של Truecaller,
 * ולכן לא רואה שום אפליקציה אחרת. תפקידו היחיד: להוציא את שם המתקשר מחלון הזיהוי של Truecaller.
 */
class TruecallerService : AccessibilityService() {

    private var lastSig = ""

    override fun onServiceConnected() {
        super.onServiceConnected()
        DiagLog.log(this, "שירות זיהוי Truecaller התחבר")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        if (event.packageName?.toString() != TruecallerBridge.PKG) return
        if (!CallReceiver.isRinging()) {
            lastSig = ""
            return
        }
        val prefs = Prefs(this)
        if (!prefs.enabled || !prefs.calls || !prefs.tcEnabled) return

        val cands = ArrayList<TruecallerBridge.Cand>()
        try {
            event.source?.let { TruecallerBridge.collect(topOf(it), cands) }
        } catch (_: Exception) {
        }
        if (cands.isEmpty()) {
            try {
                for (w in windows) {
                    val r = w.root ?: continue
                    if (r.packageName?.toString() == TruecallerBridge.PKG) TruecallerBridge.collect(r, cands)
                }
            } catch (_: Exception) {
            }
        }
        if (cands.isEmpty()) return

        val sig = cands.joinToString("|") { it.text }
        if (sig == lastSig) return
        lastSig = sig
        // רישום של מה שנראה בחלון, כדי שאפשר יהיה לכוון את הזיהוי אם הוא טועה
        DiagLog.log(this, "Truecaller: טקסטים בחלון: " + cands.take(8).joinToString(" ¦ ") { it.text.take(25) })
        val name = TruecallerBridge.pickName(cands) ?: return
        DiagLog.log(this, "Truecaller זיהה: $name")
        CallReceiver.onTruecallerName(this, name)
    }

    override fun onInterrupt() {}

    private fun topOf(n: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var cur = n
        var i = 0
        while (i++ < 30) {
            val p = cur.parent ?: break
            cur = p
        }
        return cur
    }
}

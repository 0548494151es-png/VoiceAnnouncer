package com.voiceannouncer.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import org.json.JSONArray

/** הגדרות הקראת המסך. נשמרות באותו קובץ הגדרות של האפליקציה, עם מפתחות חדשים בלבד. */
object ScreenPrefs {
    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)

    /** להציג את הכפתור הצף */
    fun buttonOn(ctx: Context): Boolean = sp(ctx).getBoolean("scrBtn", true)
    fun setButtonOn(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("scrBtn", v).apply()
    }

    /** לתרגם כיתוב באנגלית לעברית לפני ההקראה */
    fun translate(ctx: Context): Boolean = sp(ctx).getBoolean("scrTranslate", true)
    fun setTranslate(ctx: Context, v: Boolean) {
        sp(ctx).edit().putBoolean("scrTranslate", v).apply()
    }

    /** גודל הכפתור בדפים (dp) */
    fun sizeDp(ctx: Context): Int = sp(ctx).getInt("scrSize", 44).coerceIn(32, 80)
    fun setSizeDp(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("scrSize", v).apply()
    }

    /** כמה הכפתור גלוי, באחוזים (נמוך = שקוף יותר, עדין יותר) */
    fun alphaPct(ctx: Context): Int = sp(ctx).getInt("scrAlpha", 60).coerceIn(20, 100)
    fun setAlphaPct(ctx: Context, v: Int) {
        sp(ctx).edit().putInt("scrAlpha", v).apply()
    }

    /** מהירות הקראת המסך. אם לא הוגדרה - אותה מהירות של הקראת הודעות */
    fun rate(ctx: Context): Float {
        val v = sp(ctx).getFloat("scrRate", -1f)
        return if (v > 0f) v.coerceIn(0.5f, 2.0f) else Prefs(ctx).rate
    }
    fun setRate(ctx: Context, v: Float) {
        sp(ctx).edit().putFloat("scrRate", v).apply()
    }

    fun posX(ctx: Context): Int = sp(ctx).getInt("scrX", -1)
    fun posY(ctx: Context): Int = sp(ctx).getInt("scrY", -1)
    fun setPos(ctx: Context, x: Int, y: Int) {
        sp(ctx).edit().putInt("scrX", x).putInt("scrY", y).apply()
    }

    /** מחזיר את הכפתור למיקום ברירת המחדל */
    fun resetPos(ctx: Context) {
        val s = sp(ctx)
        s.edit().remove("scrX").remove("scrY").putInt("scrReset", s.getInt("scrReset", 0) + 1).apply()
    }

    /** האם שירות הנגישות של הקראת המסך מופעל בהגדרות הטלפון */
    fun serviceOn(ctx: Context): Boolean = try {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val cn = ComponentName(ctx, ScreenReaderService::class.java)
        enabled.split(':').any {
            it.equals(cn.flattenToString(), true) || it.equals(cn.flattenToShortString(), true)
        }
    } catch (_: Exception) {
        false
    }
}

/** תרגום כיתוב באנגלית לעברית דרך שירות התרגום החינמי של גוגל (דורש אינטרנט). */
object ScreenTranslator {
    /** האם רוב האותיות בטקסט הן אותיות לטיניות (אנגלית) */
    fun isLatinText(s: String): Boolean {
        var latin = 0
        var other = 0
        for (c in s) {
            if ((c in 'a'..'z') || (c in 'A'..'Z')) latin++
            else if (Character.isLetter(c)) other++
        }
        return latin >= 3 && latin >= other * 1.5
    }

    /** מחזיר את התרגום, או null אם נכשל (אין אינטרנט וכו') */
    fun toHebrew(text: String): String? {
        var c: HttpURLConnection? = null
        try {
            val url = URL(
                "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl=iw&dt=t&q=" +
                    URLEncoder.encode(text, "UTF-8")
            )
            c = url.openConnection() as HttpURLConnection
            c.connectTimeout = 5000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "Mozilla/5.0")
            if (c.responseCode != 200) return null
            val body = c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val arr = JSONArray(body).getJSONArray(0)
            val sb = StringBuilder()
            for (i in 0 until arr.length()) {
                val part = arr.optJSONArray(i) ?: continue
                sb.append(part.optString(0, ""))
            }
            val out = sb.toString().trim()
            return if (out.isEmpty()) null else out
        } catch (_: Exception) {
            return null
        } finally {
            try {
                c?.disconnect()
            } catch (_: Exception) {
            }
        }
    }
}

/**
 * הקראת המסך: כפתור צף קטן. לחיצה עליו מקריאה את הטקסט שמוצג כרגע במסך (של כל אפליקציה),
 * ולחיצה נוספת עוצרת. אפשר לגרור את הכפתור. כיתוב באנגלית מתורגם לעברית לפני ההקראה.
 * השירות לא קורא ולא שומר כלום מהמסך אלא ברגע שלוחצים על הכפתור.
 */
class ScreenReaderService : AccessibilityService() {

    private class Block(val text: String, val latin: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val lock = Any()

    private var wm: WindowManager? = null
    private var frame: FrameLayout? = null
    private var icon: ImageView? = null

    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false

    // מצב הקראה (gen משתנה בכל התחלה/עצירה כדי להתעלם מאירועים ישנים)
    @Volatile private var gen = 0
    private var outstanding = 0
    private var producing = false
    private var seq = 0
    @Volatile private var reading = false

    private var focusReq: AudioFocusRequest? = null
    private var prefsListener: SharedPreferences.OnSharedPreferenceChangeListener? = null

    private val audioAttrs: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    override fun onServiceConnected() {
        super.onServiceConnected()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        initTts()
        val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == "scrBtn" || key == "scrSize" || key == "scrAlpha" || key == "scrReset") {
                refreshButton()
            }
        }
        prefsListener = l
        applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
            .registerOnSharedPreferenceChangeListener(l)
        refreshButton()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // לא עושים כלום באירועים: קוראים את המסך רק בלחיצה על הכפתור
    }

    override fun onInterrupt() {
        stopReading()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshButton()
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        cleanup()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        cleanup()
        super.onDestroy()
    }

    private fun cleanup() {
        try {
            prefsListener?.let {
                applicationContext.getSharedPreferences("prefs", Context.MODE_PRIVATE)
                    .unregisterOnSharedPreferenceChangeListener(it)
            }
        } catch (_: Exception) {
        }
        prefsListener = null
        stopReading()
        removeButton()
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        try {
            worker.shutdownNow()
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- הכפתור הצף

    private fun refreshButton() {
        main.post {
            removeButton()
            if (ScreenPrefs.buttonOn(this)) addButton()
        }
    }

    private fun removeButton() {
        val f = frame ?: return
        try {
            wm?.removeView(f)
        } catch (_: Exception) {
        }
        frame = null
        icon = null
    }

    private fun addButton() {
        val w = wm ?: return
        val dm = resources.displayMetrics
        val density = dm.density
        val sizePx = (ScreenPrefs.sizeDp(this) * density).toInt()
        val maxX = (dm.widthPixels - sizePx).coerceAtLeast(0)
        val maxY = (dm.heightPixels - sizePx).coerceAtLeast(0)

        val f = FrameLayout(this)
        val bg = GradientDrawable()
        bg.shape = GradientDrawable.OVAL
        bg.setColor(Color.parseColor("#00695C"))
        bg.setStroke((2 * density).toInt(), Color.WHITE)
        f.background = bg
        f.alpha = ScreenPrefs.alphaPct(this) / 100f
        f.contentDescription = "הקראת המסך"

        val img = ImageView(this)
        img.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
        img.scaleType = ImageView.ScaleType.FIT_CENTER
        val pad = (sizePx * 0.22f).toInt()
        img.setPadding(pad, pad, pad, pad)
        f.addView(img, FrameLayout.LayoutParams(sizePx, sizePx))

        val lp = WindowManager.LayoutParams(
            sizePx, sizePx,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.LEFT
        val sx = ScreenPrefs.posX(this)
        val sy = ScreenPrefs.posY(this)
        lp.x = (if (sx >= 0) sx else maxX - (8 * density).toInt()).coerceIn(0, maxX)
        lp.y = (if (sy >= 0) sy else (dm.heightPixels * 0.4f).toInt()).coerceIn(0, maxY)

        val slop = ViewConfiguration.get(this).scaledTouchSlop
        f.setOnTouchListener(object : View.OnTouchListener {
            var downX = 0f
            var downY = 0f
            var startX = 0
            var startY = 0
            var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX
                        downY = e.rawY
                        startX = lp.x
                        startY = lp.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (!moved && (abs(dx) > slop || abs(dy) > slop)) moved = true
                        if (moved) {
                            lp.x = (startX + dx.toInt()).coerceIn(0, maxX)
                            lp.y = (startY + dy.toInt()).coerceIn(0, maxY)
                            try {
                                w.updateViewLayout(f, lp)
                            } catch (_: Exception) {
                            }
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (moved) ScreenPrefs.setPos(this@ScreenReaderService, lp.x, lp.y)
                        else toggleReading()
                    }
                }
                return true
            }
        })

        try {
            w.addView(f, lp)
            frame = f
            icon = img
            applyReadingUi()
        } catch (_: Exception) {
        }
    }

    private fun applyReadingUi() {
        val img = icon ?: return
        val f = frame ?: return
        if (reading) {
            img.setImageResource(android.R.drawable.ic_media_pause)
            f.alpha = 1f
        } else {
            img.setImageResource(android.R.drawable.ic_lock_silent_mode_off)
            f.alpha = ScreenPrefs.alphaPct(this) / 100f
        }
    }

    // ---------------------------------------------------------------- מנוע ההקראה

    private fun initTts() {
        if (tts != null) return
        ttsReady = false
        tts = TextToSpeech(applicationContext) { status ->
            main.post {
                if (status == TextToSpeech.SUCCESS) {
                    setupTts()
                    ttsReady = true
                } else {
                    ttsReady = false
                }
            }
        }
    }

    private fun resetTts() {
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = null
        ttsReady = false
        initTts()
    }

    private fun setupTts() {
        val t = tts ?: return
        try {
            t.setAudioAttributes(audioAttrs)
        } catch (_: Exception) {
        }
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                utteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?) {
                utteranceFinished(utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                utteranceFinished(utteranceId)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                utteranceFinished(utteranceId)
            }
        })
    }

    private fun applyLang(t: TextToSpeech, lang: String) {
        val p = Prefs(this)
        t.setSpeechRate(ScreenPrefs.rate(this))
        if (lang == "en") {
            t.setPitch(1.0f)
            t.setLanguage(Locale.US)
        } else {
            t.setPitch(p.pitch)
            t.setLanguage(Locale("he", "IL"))
            val name = p.voice
            if (name.isNotBlank()) {
                try {
                    val v = t.voices?.firstOrNull { it.name == name }
                    if (v != null) t.setVoice(v)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun enqueue(my: Int, text: String, lang: String) {
        val t = tts ?: return
        var failed = false
        synchronized(lock) {
            if (my != gen) return
            applyLang(t, lang)
            seq++
            val id = "scr${my}_$seq"
            outstanding++
            val r = t.speak(text, TextToSpeech.QUEUE_ADD, Bundle(), id)
            if (r != TextToSpeech.SUCCESS) {
                outstanding--
                failed = true
            }
        }
        if (failed) main.post { resetTts() }
    }

    private fun utteranceFinished(id: String?) {
        if (id == null || !id.startsWith("scr")) return
        val us = id.indexOf('_')
        if (us < 0) return
        val g = id.substring(3, us).toIntOrNull() ?: return
        synchronized(lock) {
            if (g != gen) return
            if (outstanding > 0) outstanding--
        }
        finishIfDone(g)
    }

    private fun finishIfDone(my: Int) {
        var done = false
        synchronized(lock) {
            if (my == gen && !producing && outstanding <= 0) done = true
        }
        if (done) {
            main.post {
                if (my == gen) setReading(false)
            }
        }
    }

    private fun setReading(on: Boolean) {
        reading = on
        applyReadingUi()
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            if (on) {
                val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(audioAttrs)
                    .setOnAudioFocusChangeListener { _ -> }
                    .build()
                am.requestAudioFocus(req)
                focusReq = req
            } else {
                focusReq?.let { am.abandonAudioFocusRequest(it) }
                focusReq = null
            }
        } catch (_: Exception) {
        }
    }

    private fun toggleReading() {
        if (reading) {
            stopReading()
            return
        }
        var my = 0
        synchronized(lock) {
            gen++
            my = gen
            outstanding = 0
            producing = true
        }
        setReading(true)
        val g = my
        try {
            worker.execute { runRead(g) }
        } catch (_: Exception) {
            stopReading()
        }
    }

    private fun stopReading() {
        synchronized(lock) {
            gen++
            outstanding = 0
            producing = false
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        if (Looper.myLooper() == Looper.getMainLooper()) setReading(false)
        else main.post { setReading(false) }
    }

    private fun awaitTts(my: Int): Boolean {
        var waited = 0
        while (!ttsReady && waited < 4000) {
            if (my != gen) return false
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
            waited += 100
        }
        return ttsReady
    }

    private fun runRead(my: Int) {
        try {
            runReadInner(my)
        } catch (_: Exception) {
            if (my == gen) stopReading()
        }
    }

    private fun runReadInner(my: Int) {
        if (!awaitTts(my)) {
            if (my == gen) {
                main.post {
                    Toast.makeText(this, "מנוע ההקראה לא זמין, נסה שוב", Toast.LENGTH_SHORT).show()
                    if (!ttsReady) resetTts()
                }
                stopReading()
            }
            return
        }
        val lines = try {
            collectScreenText()
        } catch (_: Exception) {
            emptyList<String>()
        }
        if (my != gen) return
        if (lines.isEmpty()) {
            enqueue(my, "לא נמצא טקסט להקראה במסך", "he")
            synchronized(lock) { if (my == gen) producing = false }
            finishIfDone(my)
            return
        }
        val translate = ScreenPrefs.translate(this)
        val blocks = buildBlocks(lines)
        for (b in blocks) {
            if (my != gen) return
            var text = b.text
            var lang = "he"
            if (b.latin) {
                val tr = if (translate) ScreenTranslator.toHebrew(text) else null
                if (tr != null) text = tr else lang = "en"
            }
            if (my != gen) return
            enqueue(my, text, lang)
        }
        synchronized(lock) { if (my == gen) producing = false }
        finishIfDone(my)
    }

    // ---------------------------------------------------------------- איסוף הטקסט מהמסך

    private fun findRoot(): AccessibilityNodeInfo? {
        val r = rootInActiveWindow
        if (r != null) return r
        return try {
            windows?.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
        } catch (_: Exception) {
            null
        }
    }

    private fun collectScreenText(): List<String> {
        val root = findRoot() ?: return emptyList()
        val raw = ArrayList<String>()
        walk(root, 0, raw, intArrayOf(2500))
        val out = ArrayList<String>()
        for (s in raw) {
            val t = s.replace(Regex("\\s+"), " ").trim()
            if (t.isEmpty()) continue
            if (out.isNotEmpty() && out[out.size - 1] == t) continue
            out.add(t)
        }
        return out
    }

    private fun walk(n: AccessibilityNodeInfo, depth: Int, out: MutableList<String>, budget: IntArray) {
        if (budget[0] <= 0 || depth > 70) return
        budget[0]--
        if (!n.isVisibleToUser) return
        if (n.isPassword) return
        val txt = n.text?.toString()?.trim().orEmpty()
        val desc = n.contentDescription?.toString()?.trim().orEmpty()
        val t = if (txt.isNotEmpty()) txt else if (n.childCount == 0) desc else ""
        if (t.isNotEmpty() && t.any { it.isLetterOrDigit() }) out.add(t)
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            walk(c, depth + 1, out, budget)
        }
    }

    /** מחלק טקסט ארוך לחתיכות קצרות (בגבול מילה) */
    private fun chunk(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val res = ArrayList<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(' ', max)
            if (cut < max / 2) cut = max
            res.add(rest.substring(0, cut).trim())
            rest = rest.substring(cut).trim()
        }
        if (rest.isNotEmpty()) res.add(rest)
        return res
    }

    private fun term(s: String): String {
        val last = s.last()
        return if (last == '.' || last == '!' || last == '?' || last == ':' || last == ';' || last == ',') s else "$s."
    }

    /** מאחד שורות רצופות באותה שפה לגושים, כדי שהתרגום וההקראה יהיו רציפים */
    private fun buildBlocks(lines: List<String>): List<Block> {
        val blocks = ArrayList<Block>()
        var sb = StringBuilder()
        var curLatin: Boolean? = null
        for (raw in lines) {
            for (line in chunk(raw, 550)) {
                if (line.isBlank()) continue
                val latin = ScreenTranslator.isLatinText(line)
                val limit = if (latin) 600 else 1500
                if (curLatin != null && (curLatin != latin || sb.length + line.length > limit)) {
                    val text = sb.toString().trim()
                    if (text.isNotEmpty()) blocks.add(Block(text, curLatin == true))
                    sb = StringBuilder()
                }
                curLatin = latin
                sb.append(term(line)).append(' ')
            }
        }
        val last = sb.toString().trim()
        if (last.isNotEmpty()) blocks.add(Block(last, curLatin == true))
        return blocks
    }
}

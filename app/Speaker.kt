package com.voiceannouncer.app

import android.app.NotificationManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/**
 * מנוע ההקראה. אובייקט יחיד שחי כל עוד התהליך חי. תמיד נקרא מה-thread הראשי.
 *
 * עמידות בפני תקלות (אחרי שעות בלי פעילות המנוע של המערכת יכול להתנתק או להיתקע):
 *  - כל בקשה למנוע נמצאת תחת שעון עצר. אם לא מגיע "הסתיימה" בזמן, או שהמנוע מחזיר שגיאה,
 *    המנוע מאותחל מחדש והבקשה נשלחת שוב (פעם אחת).
 *  - לפני כל הקראה נבדקת האוזנייה בפועל (התקן שמע פעיל + חיבור פרופיל הבלוטוס).
 *  - סיבת כל כישלון נרשמת ביומן (DiagLog.fail).
 */
object Speaker {
    /** בקשת הקראה אחת. נשמרת כדי שאפשר יהיה לשלוח אותה שוב אם המנוע נכשל. */
    private class Item(
        val text: String,
        val rate: Float?,
        val ring: Boolean,
        val force: Boolean,
        val cb: (() -> Unit)?,
        val session: Int,
        val attempt: Int
    ) {
        fun again() = Item(text, rate, ring, force, cb, session, attempt + 1)
    }

    /** בקשה שנשלחה למנוע וממתינה לסיום. onComplete = מה עושים כשהמנוע מסיים. */
    private class Job(val item: Item, val retryable: Boolean, val onComplete: () -> Unit) {
        var started = false
    }

    /** מצב האוזנייה: connected = מחוברת (לפי ההתקן או הפרופיל), routed = ערוץ השמע שלה פעיל בפועל */
    private class Ear(val connected: Boolean, val routed: Boolean, val detail: String)

    private const val MAX_ATTEMPTS = 2            // ניסיון ראשון + ניסיון חוזר אחד אחרי אתחול המנוע
    private const val READY_TIMEOUT_MS = 8_000L   // כמה מחכים שהמנוע יעלה
    private const val EAR_WAIT_MS = 2_000L        // כמה מחכים שערוץ השמע של האוזנייה יעלה
    private const val EAR_POLL_MS = 200L
    private const val IDLE_CHECK_MS = 10 * 60_000L // אחרי כמה זמן בלי פעילות בודקים שהמנוע חי

    private var tts: TextToSpeech? = null
    private var ready = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Item>()
    private val inflight = HashMap<String, Job>()
    private val watches = HashMap<String, Runnable>()
    private var counter = 0
    private var engineGen = 0
    private var initFails = 0
    private var readyWatch: Runnable? = null
    private var hadVoices = false
    private var lastOk = SystemClock.elapsedRealtime()

    // ---- מצב הכרזת שיחה ----
    private var ringRunnable: Runnable? = null
    private var ringSession = 0

    // ---- השתקת צלצול זמנית ----
    private var muted = false
    private var savedMode = AudioManager.RINGER_MODE_NORMAL
    private var savedVol = 0
    private var ringDoMute = false // האם ההכרזה הנוכחית אמורה להנמיך את הצלצול (כשההקראה מתחילה בפועל)
    private val safetyUnmute = Runnable { app?.let { unmuteRing(it) } }

    /** כמה זמן מקסימום הצלצול נשאר מונמך. אחרי זה הוא חוזר לבד, גם אם ההקראה נתקעה. */
    private const val DUCK_SAFETY_MS = 12_000L

    /** מתי בודקים שהעוצמה באמת חזרה (ומנסים שוב אם המערכת התעלמה) */
    private val verifyDelays = longArrayOf(300L, 800L, 1_500L)

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String?) {
            main.post { started(utteranceId) }
        }

        override fun onDone(utteranceId: String?) {
            main.post {
                d("ההקראה הסתיימה ($utteranceId)")
                lastOk = SystemClock.elapsedRealtime()
                complete(utteranceId)
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onError(utteranceId: String?) {
            main.post { failed(utteranceId, TextToSpeech.ERROR) }
        }

        override fun onError(utteranceId: String?, errorCode: Int) {
            main.post { failed(utteranceId, errorCode) }
        }

        override fun onStop(utteranceId: String?, interrupted: Boolean) {
            main.post { complete(utteranceId) }
        }
    }

    fun init(ctx: Context) {
        if (tts != null) return
        val a = ctx.applicationContext
        app = a
        recoverRing(a)
        val gen = ++engineGen
        tts = TextToSpeech(a) { status ->
            main.post { onEngineReady(a, gen, status) }
        }
    }

    private fun onEngineReady(a: Context, gen: Int, status: Int) {
        if (gen != engineGen) return // מנוע ישן שכבר הוחלף
        val t = tts
        if (status == TextToSpeech.SUCCESS && t != null) {
            try {
                t.language = Locale("he", "IL")
            } catch (_: Throwable) {
            }
            try {
                t.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
            } catch (_: Throwable) {
            }
            t.setOnUtteranceProgressListener(listener)
            ready = true
            initFails = 0
            readyWatch?.let { main.removeCallbacks(it) }
            readyWatch = null
            lastOk = SystemClock.elapsedRealtime()
            hadVoices = engineAlive(t)
            d("מנוע ההקראה מוכן (${engineName(t)})")
            val list = ArrayList(pending)
            pending.clear()
            for (item in list) {
                speakInternal(a, item.text, item.rate, item.ring, item.force, item.session, item.attempt, EAR_WAIT_MS, item.cb)
            }
        } else {
            fail("אתחול מנוע ההקראה נכשל (status=$status, מנוע=${engineName(t)})")
            try {
                t?.shutdown()
            } catch (_: Throwable) {
            }
            tts = null
            ready = false
            initFails++
            if (initFails < 3) {
                main.postDelayed({ if (tts == null && pending.isNotEmpty()) init(a) }, 1_500L)
            } else {
                initFails = 0
                failPending("מנוע ההקראה לא עלה אחרי 3 ניסיונות")
            }
        }
    }

    /** אם המנוע לא מוכן אחרי READY_TIMEOUT_MS - מאתחלים אותו מחדש (עד פעמיים), ואז מוותרים */
    private fun armReadyWatch() {
        if (readyWatch != null) return
        val r = Runnable {
            readyWatch = null
            if (ready) return@Runnable
            val a = app ?: return@Runnable
            initFails++
            if (initFails <= 2) {
                fail("מנוע ההקראה לא הוכן תוך ${READY_TIMEOUT_MS / 1000} שניות (ניסיון $initFails)")
                val old = tts
                tts = null
                try {
                    old?.shutdown()
                } catch (_: Throwable) {
                }
                init(a)
                armReadyWatch()
            } else {
                initFails = 0
                failPending("מנוע ההקראה לא עלה")
            }
        }
        readyWatch = r
        main.postDelayed(r, READY_TIMEOUT_MS)
    }

    /** משחרר את כל מה שחיכה למנוע שלא עלה (כדי שהכרזות לא יישארו תלויות) */
    private fun failPending(reason: String) {
        fail(reason)
        val list = ArrayList(pending)
        pending.clear()
        for (item in list) item.cb?.invoke()
    }

    // ---- מעקב אחרי בקשות שנשלחו למנוע ----

    private fun estMs(item: Item): Long {
        val r = (item.rate ?: app?.let { Prefs(it).rate } ?: 1f).coerceIn(0.3f, 3f)
        return (6_000L + (item.text.length * 90L / r).toLong()).coerceAtMost(60_000L)
    }

    /** זמן המתנה עד שההקראה אמורה להתחיל: כולל מה שכבר בתור לפניה */
    private fun startTimeout(item: Item): Long {
        var ahead = 0L
        for (j in inflight.values) ahead += estMs(j.item)
        return (if (item.ring) 5_000L else 8_000L) + ahead
    }

    private fun arm(id: String, ms: Long) {
        watches.remove(id)?.let { main.removeCallbacks(it) }
        val r = Runnable { onTimeout(id, ms) }
        watches[id] = r
        main.postDelayed(r, ms)
    }

    private fun track(id: String, job: Job, startMs: Long) {
        inflight[id] = job
        arm(id, startMs)
    }

    private fun untrack(id: String): Job? {
        watches.remove(id)?.let { main.removeCallbacks(it) }
        return inflight.remove(id)
    }

    private fun started(id: String?) {
        if (id == null) return
        d("ההקראה התחילה להישמע ($id)")
        val job = inflight[id] ?: return
        job.started = true
        // מנמיכים את הצלצול רק כשההקראה באמת התחילה (ולא מוקדם יותר, כדי שמנוע איטי או תקוע לא ישתיק שיחה)
        if (job.item.ring && ringDoMute && job.item.session == ringSession) {
            app?.let { muteRing(it) }
        }
        arm(id, estMs(job.item))
    }

    private fun complete(id: String?) {
        if (id == null) return
        val job = untrack(id) ?: return
        job.onComplete()
    }

    private fun failed(id: String?, code: Int) {
        if (id == null) return
        val job = untrack(id) ?: return
        retry(job, "שגיאת מנוע ההקראה (${errName(code)})")
    }

    private fun onTimeout(id: String, ms: Long) {
        watches.remove(id)
        val job = inflight.remove(id) ?: return
        val why = if (job.started) {
            "ההקראה התחילה ולא הסתיימה תוך ${ms / 1000} שניות (נתקעה)"
        } else {
            "ההקראה לא התחילה תוך ${ms / 1000} שניות (המנוע לא מגיב)"
        }
        retry(job, why)
    }

    /** בקשה נכשלה: רושמים את הסיבה, מאתחלים את המנוע ושולחים שוב. אחרי הניסיון האחרון משחררים את ההכרזה. */
    private fun retry(job: Job, reason: String) {
        val item = job.item
        if (!job.retryable) return
        // השיחה כבר הסתיימה או הוחלפה בהכרזה חדשה: אין מה לנסות שוב, וזה לא כשל
        if (item.ring && item.session != ringSession) {
            item.cb?.invoke()
            return
        }
        fail("$reason | \"${item.text.take(30)}\" | ניסיון ${item.attempt + 1} מתוך $MAX_ATTEMPTS")
        if (item.attempt + 1 < MAX_ATTEMPTS) {
            pending.add(0, item.again())
            resetEngine(reason)
        } else {
            fail("ויתרתי על ההקראה אחרי $MAX_ATTEMPTS ניסיונות: \"${item.text.take(30)}\"")
            item.cb?.invoke()
        }
    }

    /** מאתחל את מנוע ההקראה. מה שהיה באמצע חוזר לתור, כי הוא הולך לאיבוד יחד עם המנוע הישן. */
    private fun resetEngine(reason: String) {
        val a = app ?: return
        d("מאתחל את מנוע ההקראה: $reason")
        for (id in ArrayList(inflight.keys)) {
            val job = untrack(id) ?: continue
            if (!job.retryable) continue
            val item = job.item
            if (item.ring && item.session != ringSession) {
                item.cb?.invoke()
            } else if (item.attempt + 1 < MAX_ATTEMPTS) {
                pending.add(item.again())
            } else {
                item.cb?.invoke()
            }
        }
        val old = tts
        tts = null
        ready = false
        try {
            old?.stop()
        } catch (_: Throwable) {
        }
        try {
            old?.shutdown()
        } catch (_: Throwable) {
        }
        init(a)
        armReadyWatch()
    }

    private fun engineAlive(t: TextToSpeech): Boolean = try {
        !t.voices.isNullOrEmpty()
    } catch (_: Throwable) {
        false
    }

    private fun engineName(t: TextToSpeech?): String = try {
        t?.defaultEngine ?: "?"
    } catch (_: Throwable) {
        "?"
    }

    private fun errName(code: Int): String {
        val n = when (code) {
            TextToSpeech.ERROR_SYNTHESIS -> "סינתזה"
            TextToSpeech.ERROR_SERVICE -> "שירות"
            TextToSpeech.ERROR_OUTPUT -> "פלט"
            TextToSpeech.ERROR_NETWORK -> "רשת"
            TextToSpeech.ERROR_NETWORK_TIMEOUT -> "זמן רשת"
            TextToSpeech.ERROR_INVALID_REQUEST -> "בקשה לא תקינה"
            TextToSpeech.ERROR_NOT_INSTALLED_YET -> "קול לא מותקן"
            else -> "כללית"
        }
        return "$n, קוד $code"
    }

    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) =
        speakInternal(ctx, text, null, false, false, onDone = onDone)

    private fun speakInternal(
        ctx: Context, text: String, rate: Float?, ring: Boolean, force: Boolean,
        session: Int = ringSession, attempt: Int = 0, earWaitLeft: Long = EAR_WAIT_MS,
        onDone: (() -> Unit)?
    ) {
        // ההכרזה הזו כבר לא רלוונטית (השיחה הסתיימה או הוחלפה בהכרזה חדשה)
        if (ring && session != ringSession) {
            onDone?.invoke()
            return
        }
        // בדיקה מרכזית: על שקט / רטט לא מקריאים שום דבר, בלי קשר מי ביקש
        if (!force && !allowed(ctx)) {
            onDone?.invoke()
            return
        }
        init(ctx)
        val t = tts
        if (!ready || t == null) {
            pending.add(Item(text, rate, ring, force, onDone, session, attempt))
            armReadyWatch()
            return
        }
        // אחרי הרבה זמן בלי פעילות: בודקים שהמנוע עדיין חי, ואם לא - מאתחלים לפני ההקראה
        val idleMs = SystemClock.elapsedRealtime() - lastOk
        if (hadVoices && idleMs > IDLE_CHECK_MS && !engineAlive(t)) {
            val why = "מנוע ההקראה לא מגיב אחרי ${idleMs / 60_000} דקות בלי פעילות"
            fail(why)
            pending.add(0, Item(text, rate, ring, force, onDone, session, attempt))
            resetEngine(why)
            return
        }
        val p = Prefs(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        // בדיקת אוזנייה בפועל, לפני כל הקראה
        val ear = headsetState(am)
        val btOn = ear.routed
        val silent = isSilent(am)
        // מצבים שבהם מותר להקריא רק באוזנייה ובשום אופן לא ברמקול הטלפון
        val needEar = silent || p.btOnly || (ring && p.btVoiceRoute)
        if (ear.connected && !btOn && needEar) {
            if (earWaitLeft > 0) {
                // האוזנייה מחוברת לפי הפרופיל, אבל ערוץ השמע שלה עוד לא עלה: ממתינים רגע
                main.postDelayed({
                    speakInternal(ctx, text, rate, ring, force, session, attempt, earWaitLeft - EAR_POLL_MS, onDone)
                }, EAR_POLL_MS)
                return
            }
            fail("האוזנייה מחוברת אבל ערוץ השמע שלה לא זמין (${ear.detail}) - לא מקריא כדי לא לשמוע ברמקול")
            onDone?.invoke()
            return
        }
        t.setPitch(p.pitch)
        t.setSpeechRate(rate ?: p.rate)
        applyVoice(t, p.voice)
        val item = Item(text, rate, ring, force, onDone, session, attempt)
        // בלוטוס + שקט/רטט: מקריאים רק באוזנייה, ובשום אופן לא ברמקול הטלפון
        val earOnly = btOn && !muted && silent
        // בלוטוס: ההקראה נוצרת לקובץ ומנוגנת ישירות על האוזנייה (ולא על הרמקול)
        val useEar = btOn && ((ring && p.btVoiceRoute) || earOnly)
        if (useEar) {
            speakToEar(ctx, t, item, ring && p.btVoiceRoute)
            return
        }
        // בלוטוס מחובר + הוגדר "גם ברמקול": מכינים עותק של ההכרזה שיושמע ברמקול הטלפון
        if (ring && p.btAlsoSpeaker && btOn) {
            try {
                val dir = ctx.cacheDir
                dir.listFiles()?.filter { it.name.startsWith("spk_") }?.forEach { it.delete() }
                val f = File(dir, "spk_${counter}.wav")
                val fid = "f${counter++}"
                val copy = Job(item, false) { if (session == ringSession) playOnPhoneSpeaker(ctx, f) }
                track(fid, copy, startTimeout(item))
                val rr = t.synthesizeToFile(text, Bundle(), f, fid)
                if (rr != TextToSpeech.SUCCESS) untrack(fid)
            } catch (_: Exception) {
            }
        }
        val id = "u${counter++}"
        try {
            t.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
        } catch (_: Exception) {
        }
        track(id, Job(item, true) { onDone?.invoke() }, startTimeout(item))
        val r = try {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
        d("הקראה (\"${text.take(40)}\"): " + (if (r == TextToSpeech.SUCCESS) "נשלחה" else "נכשלה") + " | בלוטוס=$btOn")
        if (r != TextToSpeech.SUCCESS) {
            val job = untrack(id)
            if (job != null) retry(job, "הפקודה speak נכשלה (קוד $r)")
        }
    }

    // ---- הקראה לאוזנייה בלבד ----
    private var earPlayer: MediaPlayer? = null

    private fun findOut(am: AudioManager, sco: Boolean): AudioDeviceInfo? = try {
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull {
            if (sco) it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            else it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    } catch (_: Exception) {
        null
    }

    /**
     * יוצר קובץ הקראה ומנגן אותו ישירות על אוזניית הבלוטוס.
     * אם האוזנייה לא זמינה - לא מקריאים בכלל (כדי שלא יישמע ברמקול).
     */
    private fun speakToEar(ctx: Context, t: TextToSpeech, item: Item, useSco: Boolean) {
        val text = item.text
        val ring = item.ring
        val onDone = item.cb
        val session = item.session
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        try {
            val cutoff = System.currentTimeMillis() - 5 * 60_000L
            ctx.cacheDir.listFiles()
                ?.filter { it.name.startsWith("ear_") && it.lastModified() < cutoff }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
        val file = File(ctx.cacheDir, "ear_${counter}.wav")
        val fid = "e${counter++}"
        var finished = false
        fun finish() {
            if (finished) return
            finished = true
            try {
                file.delete()
            } catch (_: Exception) {
            }
            // בזמן צלצול משאירים את ערוץ השיחה פתוח בין ההכרזות (נסגר ב-stopRinging)
            if (!ring) stopSco(am)
            onDone?.invoke()
        }
        val job = Job(item, true) {
            if (ring && session != ringSession) {
                finish()
            } else if (!file.exists() || file.length() < 100) {
                try {
                    file.delete()
                } catch (_: Exception) {
                }
                retry(Job(item, true) {}, "לא נוצר קובץ הקראה לאוזנייה (הקובץ ריק)")
            } else {
                routeToEar(ctx, am, file, ring, session, useSco) { finish() }
            }
        }
        d("הקראה לאוזנייה בלבד: \"${text.take(40)}\"")
        track(fid, job, startTimeout(item))
        val rr = try {
            t.synthesizeToFile(text, Bundle(), file, fid)
        } catch (_: Throwable) {
            TextToSpeech.ERROR
        }
        if (rr != TextToSpeech.SUCCESS) {
            val j = untrack(fid)
            try {
                file.delete()
            } catch (_: Exception) {
            }
            if (j != null) retry(j, "יצירת קובץ ההקראה נכשלה (קוד $rr)")
        }
    }

    private fun scoConnected(ctx: Context): Boolean = try {
        ctx.registerReceiver(null, IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED))
            ?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) == AudioManager.SCO_AUDIO_STATE_CONNECTED
    } catch (_: Exception) {
        false
    }

    private fun routeToEar(
        ctx: Context, am: AudioManager, file: File, ring: Boolean, session: Int,
        useSco: Boolean, finish: () -> Unit
    ) {
        // מדיה (A2DP) - ברירת מחדל. ערוץ שיחה (SCO) רק אם ההגדרה דולקת (או אם אין A2DP).
        if (!useSco) {
            val a2dp = findOut(am, false)
            if (a2dp != null) {
                playOn(file, a2dp, false, finish)
                return
            }
        }
        startSco(am)
        var tries = 0
        fun check() {
            if (ring && session != ringSession) {
                finish()
                return
            }
            val sco = findOut(am, true)
            if (sco != null && scoConnected(ctx)) {
                if (tries == 0) {
                    playOn(file, sco, true, finish)
                } else {
                    d("ערוץ השיחה התחבר אחרי ${tries * 200} מילישניות")
                    // רגע קצר אחרי החיבור, כדי שלא ייחתך תחילת המשפט
                    main.postDelayed({
                        if (ring && session != ringSession) finish() else playOn(file, sco, true, finish)
                    }, 600)
                }
            } else if (++tries < 30) {
                main.postDelayed({ check() }, 200)
            } else {
                fail("ערוץ השיחה לאוזנייה לא התחבר תוך 6 שניות - לא מקריא (כדי לא לשמוע ברמקול) | ${headsetState(am).detail}")
                finish()
            }
        }
        check()
    }

    /** מנגן קובץ על האוזנייה. עם שעון עצר: אם הניגון לא מסתיים, משחררים ורושמים כשל. */
    private fun playOn(file: File, dev: AudioDeviceInfo, voice: Boolean, finish: () -> Unit) {
        var watchdog: Runnable? = null
        var player: MediaPlayer? = null
        fun end(problem: String?) {
            watchdog?.let { main.removeCallbacks(it) }
            watchdog = null
            val m = player
            try {
                m?.release()
            } catch (_: Exception) {
            }
            if (earPlayer === m) earPlayer = null
            if (problem != null) fail(problem)
            finish()
        }
        try {
            val mp = MediaPlayer()
            player = mp
            earPlayer = mp
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(
                        if (voice) AudioAttributes.USAGE_VOICE_COMMUNICATION
                        else AudioAttributes.USAGE_MEDIA
                    )
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener {
                d("ההקראה באוזנייה הסתיימה")
                lastOk = SystemClock.elapsedRealtime()
                end(null)
            }
            mp.setOnErrorListener { _, what, extra ->
                end("שגיאת ניגון באוזנייה (what=$what, extra=$extra)")
                true
            }
            mp.prepare()
            val accepted = mp.setPreferredDevice(dev)
            if (!accepted) {
                val am = app?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                @Suppress("DEPRECATION")
                val btRoute = voice || (am?.isBluetoothA2dpOn == true)
                d("⚠ המערכת דחתה את ההתקן המועדף (${typeName(dev.type)}), ערוץ בלוטוס פעיל=$btRoute")
                if (!btRoute) {
                    end("האוזנייה לא קיבלה את הניגון (ההתקן נדחה) - לא מנגן כדי לא לשמוע ברמקול")
                    return
                }
            }
            mp.start()
            d("מנגן באוזנייה (" + (if (voice) "ערוץ שיחה" else "מדיה") + ")")
            val dur = try {
                mp.duration
            } catch (_: Exception) {
                0
            }
            val waitMs = (if (dur > 0) dur else 10_000) + 4_000L
            val r = Runnable { end("הניגון באוזנייה לא הסתיים תוך ${waitMs / 1000} שניות (נתקע)") }
            watchdog = r
            main.postDelayed(r, waitMs)
        } catch (e: Exception) {
            end("שגיאה בניגון לאוזנייה: ${e.message}")
        }
    }

    // ---- ערוץ שיחה בבלוטוס ----
    private var scoOn = false

    private fun startSco(am: AudioManager) {
        try {
            if (!scoOn) {
                am.startBluetoothSco()
                am.isBluetoothScoOn = true
                scoOn = true
                d("ערוץ שיחה בבלוטוס הופעל")
            }
        } catch (e: Exception) {
            d("⚠ שגיאה בהפעלת ערוץ שיחה בבלוטוס: ${e.message}")
        }
    }

    private fun stopSco(am: AudioManager) {
        if (!scoOn) return
        scoOn = false
        try {
            // אם השיחה כבר נענתה, לא נוגעים בערוץ - הוא שייך לשיחה
            if (am.mode == AudioManager.MODE_IN_CALL || am.mode == AudioManager.MODE_IN_COMMUNICATION) return
            am.isBluetoothScoOn = false
            am.stopBluetoothSco()
        } catch (_: Exception) {
        }
    }

    private fun d(msg: String) {
        app?.let { DiagLog.log(it, msg) }
    }

    /** רושם כשל הקראה ביומן, עם הסיבה ומצב המנוע */
    private fun fail(reason: String) {
        val a = app ?: return
        val idleMin = (SystemClock.elapsedRealtime() - lastOk) / 60_000
        DiagLog.fail(a, "$reason | מנוע=${engineName(tts)} | הצלחה אחרונה לפני $idleMin דק'")
    }

    /** למה לא מקריאים כרגע (null = מותר) */
    fun denyReason(ctx: Context): String? {
        val p = Prefs(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return when {
            !p.enabled -> "האפליקציה כבויה"
            !muted && !bluetoothConnected(am) && am.ringerMode != AudioManager.RINGER_MODE_NORMAL -> "הטלפון על שקט/רטט"
            !muted && !bluetoothConnected(am) && am.getStreamVolume(AudioManager.STREAM_RING) == 0 -> "עוצמת צלצול 0"
            p.btOnly && !bluetoothConnected(am) -> "מוגדר 'רק בבלוטוס' ואין בלוטוס"
            else -> null
        }
    }

    /** מנגן קובץ הקראה דרך הרמקול של הטלפון עצמו, גם כשבלוטוס מחובר */
    private fun playOnPhoneSpeaker(ctx: Context, file: File) {
        try {
            if (!file.exists() || file.length() < 100) return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val dev = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER } ?: return
            val mp = MediaPlayer()
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            mp.setDataSource(file.absolutePath)
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { m, _, _ -> m.release(); true }
            mp.prepare()
            mp.setPreferredDevice(dev)
            mp.start()
        } catch (_: Exception) {
        }
    }

    private fun applyVoice(t: TextToSpeech, name: String) {
        try {
            if (name.isNotBlank()) {
                val v = t.voices?.firstOrNull { it.name == name }
                if (v != null) {
                    t.voice = v
                    return
                }
            }
            t.language = Locale("he", "IL")
        } catch (_: Exception) {
        }
    }

    /** רשימת הקולות בעברית שמותקנים בטלפון */
    fun hebrewVoices(): List<Voice> {
        return try {
            tts?.voices
                ?.filter { it.locale.language == "he" || it.locale.language == "iw" }
                ?.sortedBy { it.name }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * הכרזה על שיחה נכנסת. מספר החזרות, המרווח ביניהן והמהירות נלקחים מההגדרות.
     * אם מופעלת ההשתקה - הצלצול מושתק רק בזמן ההכרזה עצמה וחוזר מיד אחריה.
     */
    fun startRinging(ctx: Context, text: String, allowMute: Boolean = true, force: Boolean = false) {
        stopRinging()
        val a = ctx.applicationContext
        app = a
        val p = Prefs(a)
        val session = ++ringSession
        d("התחלת הכרזה: \"$text\" | חזרות=${if (p.repeatForever) "∞" else p.repeatCount.toString()} השתקה=${p.muteRing}")
        val total = if (p.repeatForever) Int.MAX_VALUE else p.repeatCount
        val gapMs = p.repeatGapSec * 1000L
        val rate = p.callRate
        val doMute = allowMute && p.muteRing
        ringDoMute = doMute
        var n = 0

        fun round() {
            if (session != ringSession) return
            // עברו לשקט / רטט באמצע הצלצול - מפסיקים את ההכרזות
            if (!force && !allowed(a)) return
            n++
            speakInternal(a, text, rate, true, force, session) {
                if (doMute) unmuteRing(a)
                if (session == ringSession && n < total) {
                    val r = Runnable { round() }
                    ringRunnable = r
                    main.postDelayed(r, gapMs)
                }
            }
        }
        round()
    }

    fun stopRinging() {
        ringSession++
        ringRunnable?.let { main.removeCallbacks(it) }
        ringRunnable = null
        pending.removeAll { it.ring }
        // בקשות הכרזה שעדיין ממתינות למנוע כבר לא רלוונטיות (אין צורך בשעון עצר עליהן)
        for (id in ArrayList(inflight.keys)) {
            if (inflight[id]?.item?.ring == true) untrack(id)
        }
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        try {
            earPlayer?.stop()
            earPlayer?.release()
        } catch (_: Exception) {
        }
        earPlayer = null
        app?.let {
            unmuteRing(it)
            stopSco(it.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
        }
    }

    /** האם יש הרשאת "נא לא להפריע" - נדרשת כדי להשתיק את הצלצול */
    fun canMuteRing(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
    } catch (_: Exception) {
        false
    }

    /**
     * הנמכת הצלצול בזמן ההכרזה. לא מורידים ל-0: בהרבה מכשירים ירידה ל-0 הופכת את הטלפון
     * למצב רטט, הצלצול נעלם ולא תמיד חוזר, ואז האפליקציה חושבת שהטלפון על שקט ומפסיקה להקריא.
     * לכן מנמיכים לרמה שהמשתמש בחר בהגדרות (ברירת מחדל 3, לא פחות מ-1) ומחזירים את העוצמה
     * המקורית מיד אחרי ההכרזה. ההנמכה מתחילה רק כשההקראה באמת התחילה להישמע.
     * העוצמה המקורית נשמרת בהגדרות, כדי שגם אם התהליך נהרג באמצע אפשר יהיה לשחזר.
     */
    private fun muteRing(ctx: Context) {
        if (muted) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val p = Prefs(ctx)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
            val level = p.ringDuckLevel.coerceIn(1, max)
            val vol = am.getStreamVolume(AudioManager.STREAM_RING)
            // הצלצול כבר שקט מהרמה שנבחרה: אין מה להנמיך
            if (vol <= level) return
            savedMode = am.ringerMode
            savedVol = vol
            p.ringBackup = vol
            muted = true
            am.setStreamVolume(AudioManager.STREAM_RING, level, 0)
            d("הצלצול הונמך מ-$vol ל-$level")
            // רשת ביטחון: אם משהו משתבש, הצלצול חוזר לבד אחרי כמה שניות
            main.removeCallbacks(safetyUnmute)
            main.postDelayed(safetyUnmute, DUCK_SAFETY_MS)
        } catch (_: Exception) {
            unmuteRing(ctx)
        }
    }

    private fun unmuteRing(ctx: Context) {
        if (!muted) return
        muted = false
        main.removeCallbacks(safetyUnmute)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val mode = savedMode
        val vol = savedVol
        restoreRing(am, mode, vol)
        d("הצלצול הוחזר ל-$vol")
        verifyRestore(ctx, am, mode, vol, 0)
    }

    /**
     * לפעמים המערכת (במיוחד בשיאומי) מתעלמת מהשחזור. בודקים כמה פעמים שהעוצמה באמת חזרה.
     * רק כשהיא חזרה מוחקים את הגיבוי. אם לא חזרה, הגיבוי נשאר ו-recoverRing ישחזר אותה בהכרזה הבאה.
     */
    private fun verifyRestore(ctx: Context, am: AudioManager, mode: Int, vol: Int, step: Int) {
        if (step >= verifyDelays.size) return
        main.postDelayed({
            try {
                // הכרזה חדשה כבר הנמיכה שוב: היא אחראית על הגיבוי עכשיו
                if (muted) return@postDelayed
                if (vol <= 0 || am.getStreamVolume(AudioManager.STREAM_RING) == vol) {
                    Prefs(ctx).ringBackup = 0
                    return@postDelayed
                }
                if (step + 1 < verifyDelays.size) {
                    restoreRing(am, mode, vol)
                    verifyRestore(ctx, am, mode, vol, step + 1)
                } else {
                    d("⚠ העוצמה לא חזרה ל-$vol אחרי כמה ניסיונות, ישוחזר בהכרזה הבאה")
                }
            } catch (_: Exception) {
            }
        }, verifyDelays[step])
    }

    private fun restoreRing(am: AudioManager, mode: Int, vol: Int) {
        try {
            if (am.ringerMode != mode) am.ringerMode = mode
        } catch (_: Exception) {
        }
        try {
            if (vol > 0) am.setStreamVolume(AudioManager.STREAM_RING, vol, 0)
        } catch (_: Exception) {
        }
    }

    /**
     * אם התהליך נהרג כשהצלצול היה מונמך, או שהמערכת התעלמה מהשחזור, העוצמה נשארת נמוכה.
     * כאן משחזרים את העוצמה המקורית. הגיבוי נמחק רק אחרי שהשחזור הצליח, כך שהוא לא הולך לאיבוד.
     */
    fun recoverRing(ctx: Context) {
        if (muted) return
        try {
            val p = Prefs(ctx)
            val backup = p.ringBackup
            if (backup <= 0) return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stuck = am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                am.getStreamVolume(AudioManager.STREAM_RING) < backup
            if (stuck) {
                restoreRing(am, AudioManager.RINGER_MODE_NORMAL, backup)
                d("שוחזרה עוצמת צלצול שנשארה נמוכה ($backup)")
            }
            // מוחקים את הגיבוי רק אם העוצמה באמת חזרה. אחרת נשאר לניסיון הבא.
            if (am.getStreamVolume(AudioManager.STREAM_RING) >= backup) p.ringBackup = 0
        } catch (_: Exception) {
        }
    }

    /** האם מותר להקריא כרגע לפי ההגדרות */
    fun allowed(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (!p.enabled) return false
        recoverRing(ctx)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!muted && isSilent(am) && !bluetoothConnected(am)) return false
        if (p.btOnly && !bluetoothConnected(am)) return false
        return true
    }

    /** שקט, רטט, או עוצמת צלצול 0 */
    private fun isSilent(am: AudioManager): Boolean =
        am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
            am.getStreamVolume(AudioManager.STREAM_RING) == 0

    // ---- בדיקת אוזנייה בפועל ----

    private fun isBtOut(type: Int): Boolean = when (type) {
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
        else -> Build.VERSION.SDK_INT >= 31 &&
            (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "רמקול"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "BT-A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "BT-SCO"
        else -> if (Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET) "BLE" else "סוג$type"
    }

    /** האם פרופיל הבלוטוס (מדיה או דיבורית) מחובר. null = לא ידוע (אין הרשאה, אין בלוטוס וכו') */
    @Suppress("DEPRECATION", "MissingPermission")
    private fun btProfileConnected(): Boolean? {
        return try {
            val ad = BluetoothAdapter.getDefaultAdapter() ?: return null
            if (!ad.isEnabled) return false
            ad.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED ||
                ad.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothProfile.STATE_CONNECTED
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * מצב האוזנייה בפועל, בשתי בדיקות: האם ערוץ השמע שלה קיים ברשימת ההתקנים של המערכת,
     * והאם פרופיל הבלוטוס מחובר. אם הפרופיל מחובר אבל ערוץ השמע עוד לא עלה, ממתינים רגע לפני ההקראה.
     */
    private fun headsetState(am: AudioManager): Ear {
        val outs = try {
            am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).filter { isBtOut(it.type) }
        } catch (_: Throwable) {
            emptyList<AudioDeviceInfo>()
        }
        val profile = btProfileConnected()
        val routed = outs.isNotEmpty()
        val connected = routed || profile == true
        val detail = "התקני שמע=[" + outs.joinToString(",") { typeName(it.type) } + "] פרופיל=" +
            (profile?.toString() ?: "לא ידוע")
        return Ear(connected, routed, detail)
    }

    private fun bluetoothConnected(am: AudioManager): Boolean = headsetState(am).connected
}

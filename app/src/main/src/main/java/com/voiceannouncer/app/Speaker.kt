package com.voiceannouncer.app

import android.app.NotificationManager
import android.content.Context
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.io.File
import java.util.Locale

/** מנוע ההקראה. אובייקט יחיד שחי כל עוד התהליך חי. תמיד נקרא מה-thread הראשי. */
object Speaker {
    private class Item(
        val text: String,
        val rate: Float?,
        val ring: Boolean,
        val force: Boolean,
        val cb: (() -> Unit)?
    )

    private var tts: TextToSpeech? = null
    private var ready = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Item>()
    private val callbacks = HashMap<String, (() -> Unit)?>()
    private var counter = 0

    // ---- מצב הכרזת שיחה ----
    private var ringRunnable: Runnable? = null
    private var ringSession = 0

    // ---- השתקת צלצול זמנית ----
    private var muted = false
    private var savedMode = AudioManager.RINGER_MODE_NORMAL
    private var savedVol = 0
    private val safetyUnmute = Runnable { app?.let { unmuteRing(it) } }

    fun init(ctx: Context) {
        if (tts != null) return
        val a = ctx.applicationContext
        app = a
        recoverRing(a)
        tts = TextToSpeech(a) { status ->
            main.post {
                val t = tts
                if (status == TextToSpeech.SUCCESS && t != null) {
                    t.language = Locale("he", "IL")
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            d("ההקראה התחילה להישמע ($utteranceId)")
                        }
                        override fun onDone(utteranceId: String?) {
                            d("ההקראה הסתיימה ($utteranceId)")
                            main.post { callbacks.remove(utteranceId)?.invoke() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            d("⚠ שגיאת הקראה ($utteranceId)")
                            main.post { callbacks.remove(utteranceId)?.invoke() }
                        }
                        override fun onStop(utteranceId: String?, interrupted: Boolean) {
                            main.post { callbacks.remove(utteranceId)?.invoke() }
                        }
                    })
                    ready = true
                    val list = ArrayList(pending)
                    pending.clear()
                    for (item in list) speakInternal(a, item.text, item.rate, item.ring, item.force, item.cb)
                } else {
                    tts = null
                }
            }
        }
    }

    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) =
        speakInternal(ctx, text, null, false, false, onDone)

    private fun speakInternal(
        ctx: Context, text: String, rate: Float?, ring: Boolean, force: Boolean, onDone: (() -> Unit)?
    ) {
        // בדיקה מרכזית: על שקט / רטט לא מקריאים שום דבר, בלי קשר מי ביקש
        if (!force && !allowed(ctx)) {
            onDone?.invoke()
            return
        }
        init(ctx)
        val t = tts
        if (!ready || t == null) {
            pending.add(Item(text, rate, ring, force, onDone))
            return
        }
        val p = Prefs(ctx)
        t.setPitch(p.pitch)
        t.setSpeechRate(rate ?: p.rate)
        applyVoice(t, p.voice)
        val am2 = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val btOn = bluetoothConnected(am2)
        // בלוטוס + שקט/רטט: מקריאים רק באוזנייה, ובשום אופן לא ברמקול הטלפון
        val earOnly = btOn && !muted && isSilent(am2)
        // בלוטוס: ההקראה נוצרת לקובץ ומנוגנת ישירות על האוזנייה (ולא על הרמקול)
        val useEar = btOn && ((ring && p.btVoiceRoute) || earOnly)
        if (useEar) {
            speakToEar(ctx, t, text, ring, ring && p.btVoiceRoute, onDone)
            return
        }
        // בלוטוס מחובר + הוגדר "גם ברמקול": מכינים עותק של ההכרזה שיושמע ברמקול הטלפון
        if (ring && p.btAlsoSpeaker) {
            try {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (bluetoothConnected(am)) {
                    val s = ringSession
                    val dir = ctx.cacheDir
                    dir.listFiles()?.filter { it.name.startsWith("spk_") }?.forEach { it.delete() }
                    val f = File(dir, "spk_${counter}.wav")
                    val fid = "f${counter++}"
                    callbacks[fid] = { if (s == ringSession) playOnPhoneSpeaker(ctx, f) }
                    val rr = t.synthesizeToFile(text, android.os.Bundle(), f, fid)
                    if (rr != TextToSpeech.SUCCESS) callbacks.remove(fid)
                }
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
        callbacks[id] = onDone
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        d("הקראה (\"${text.take(40)}\"): " + (if (r == TextToSpeech.SUCCESS) "נשלחה" else "נכשלה") + " | בלוטוס=$btOn")
        if (r != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone?.invoke()
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
    private fun speakToEar(
        ctx: Context, t: TextToSpeech, text: String, ring: Boolean, useSco: Boolean, onDone: (() -> Unit)?
    ) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val session = ringSession
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
        callbacks[fid] = {
            if (ring && session != ringSession) {
                finish()
            } else if (!file.exists() || file.length() < 100) {
                d("⚠ לא נוצר קובץ הקראה לאוזנייה")
                finish()
            } else {
                routeToEar(ctx, am, file, ring, session, useSco) { finish() }
            }
        }
        d("הקראה לאוזנייה בלבד: \"${text.take(40)}\"")
        val rr = t.synthesizeToFile(text, android.os.Bundle(), file, fid)
        if (rr != TextToSpeech.SUCCESS) {
            callbacks.remove(fid)
            d("⚠ יצירת קובץ ההקראה נכשלה")
            finish()
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
                d("⚠ ערוץ השיחה לאוזנייה לא התחבר תוך 6 שניות - לא מקריא (כדי לא לשמוע ברמקול)")
                finish()
            }
        }
        check()
    }

    private fun playOn(file: File, dev: AudioDeviceInfo, voice: Boolean, finish: () -> Unit) {
        try {
            val mp = MediaPlayer()
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
            mp.setOnCompletionListener { m ->
                try {
                    m.release()
                } catch (_: Exception) {
                }
                if (earPlayer === m) earPlayer = null
                d("ההקראה באוזנייה הסתיימה")
                finish()
            }
            mp.setOnErrorListener { m, _, _ ->
                try {
                    m.release()
                } catch (_: Exception) {
                }
                if (earPlayer === m) earPlayer = null
                d("⚠ שגיאת ניגון באוזנייה")
                finish()
                true
            }
            mp.prepare()
            mp.setPreferredDevice(dev)
            mp.start()
            d("מנגן באוזנייה (" + (if (voice) "ערוץ שיחה" else "מדיה") + ")")
        } catch (e: Exception) {
            d("⚠ שגיאה בניגון לאוזנייה: ${e.message}")
            finish()
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
        var n = 0

        fun round() {
            if (session != ringSession) return
            // עברו לשקט / רטט באמצע הצלצול - מפסיקים את ההכרזות
            if (!force && !allowed(a)) return
            n++
            if (doMute) muteRing(a)
            speakInternal(a, text, rate, true, force) {
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
     * לכן מנמיכים למינימום (1) ומחזירים את העוצמה המקורית מיד אחרי ההכרזה.
     * העוצמה המקורית נשמרת בהגדרות, כדי שגם אם התהליך נהרג באמצע אפשר יהיה לשחזר.
     */
    private fun muteRing(ctx: Context) {
        if (muted) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            val vol = am.getStreamVolume(AudioManager.STREAM_RING)
            if (vol <= 1) return
            savedMode = am.ringerMode
            savedVol = vol
            Prefs(ctx).ringBackup = vol
            muted = true
            am.setStreamVolume(AudioManager.STREAM_RING, 1, 0)
            // רשת ביטחון: אם משהו משתבש, הצלצול חוזר לבד אחרי 20 שניות
            main.removeCallbacks(safetyUnmute)
            main.postDelayed(safetyUnmute, 20_000)
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
        // לפעמים המערכת מתעלמת מהשחזור הראשון - בודקים ומנסים שוב
        main.postDelayed({
            try {
                if (!muted && vol > 0 && am.getStreamVolume(AudioManager.STREAM_RING) != vol &&
                    Prefs(ctx).ringBackup > 0
                ) {
                    restoreRing(am, mode, vol)
                }
            } catch (_: Exception) {
            }
            if (!muted) Prefs(ctx).ringBackup = 0
        }, 300)
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
     * אם התהליך נהרג כשהצלצול היה מונמך, העוצמה נשארת על המינימום והאפליקציה חושבת שהטלפון שקט.
     * כאן משחזרים את העוצמה המקורית, אבל רק אם העוצמה עדיין נמוכה (כלומר המשתמש לא שינה אותה בינתיים).
     */
    fun recoverRing(ctx: Context) {
        if (muted) return
        try {
            val p = Prefs(ctx)
            val backup = p.ringBackup
            if (backup <= 0) return
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val stuck = am.ringerMode != AudioManager.RINGER_MODE_NORMAL ||
                am.getStreamVolume(AudioManager.STREAM_RING) <= 1
            if (stuck) restoreRing(am, AudioManager.RINGER_MODE_NORMAL, backup)
            p.ringBackup = 0
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

    private fun bluetoothConnected(am: AudioManager): Boolean {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any {
            when (it.type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> true
                else -> Build.VERSION.SDK_INT >= 31 &&
                    (it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
        }
    }
}

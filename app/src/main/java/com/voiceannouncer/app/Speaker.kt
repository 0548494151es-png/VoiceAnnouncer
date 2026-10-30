package com.voiceannouncer.app

import android.app.NotificationManager
import android.content.Context
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
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) {
                            main.post { callbacks.remove(utteranceId)?.invoke() }
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
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
        callbacks[id] = onDone
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone?.invoke()
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
        val p = Prefs(a)
        val session = ++ringSession
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
        app?.let { unmuteRing(it) }
    }

    /** האם יש הרשאת "נא לא להפריע" - נדרשת כדי להשתיק את הצלצול */
    fun canMuteRing(ctx: Context): Boolean = try {
        (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .isNotificationPolicyAccessGranted
    } catch (_: Exception) {
        false
    }

    private fun muteRing(ctx: Context) {
        if (muted || !canMuteRing(ctx)) return
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return
            savedMode = am.ringerMode
            savedVol = am.getStreamVolume(AudioManager.STREAM_RING)
            muted = true
            am.setStreamVolume(AudioManager.STREAM_RING, 0, 0)
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
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (am.ringerMode != savedMode) am.ringerMode = savedMode
            if (savedVol > 0) am.setStreamVolume(AudioManager.STREAM_RING, savedVol, 0)
        } catch (_: Exception) {
        }
    }

    /** האם מותר להקריא כרגע לפי ההגדרות */
    fun allowed(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (!p.enabled) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (!muted && isSilent(am)) return false
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

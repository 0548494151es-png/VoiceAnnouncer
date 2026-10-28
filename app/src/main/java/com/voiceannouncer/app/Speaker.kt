package com.voiceannouncer.app

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import java.util.Locale

/** מנוע ההקראה. אובייקט יחיד שחי כל עוד התהליך חי. תמיד נקרא מה-thread הראשי. */
object Speaker {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var app: Context? = null
    private val main = Handler(Looper.getMainLooper())
    private val pending = ArrayList<Pair<String, (() -> Unit)?>>()
    private val callbacks = HashMap<String, (() -> Unit)?>()
    private var counter = 0
    private var ringRunnable: Runnable? = null

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
                    })
                    ready = true
                    val list = ArrayList(pending)
                    pending.clear()
                    for ((text, cb) in list) speak(a, text, cb)
                } else {
                    tts = null
                }
            }
        }
    }

    fun speak(ctx: Context, text: String, onDone: (() -> Unit)? = null) {
        init(ctx)
        val t = tts
        if (!ready || t == null) {
            pending.add(text to onDone)
            return
        }
        val p = Prefs(ctx)
        t.setPitch(p.pitch)
        t.setSpeechRate(p.rate)
        applyVoice(t, p.voice)
        val id = "u${counter++}"
        callbacks[id] = onDone
        val r = t.speak(text, TextToSpeech.QUEUE_ADD, null, id)
        if (r != TextToSpeech.SUCCESS) {
            callbacks.remove(id)
            onDone?.invoke()
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

    /** הקראה בזמן שיחה נכנסת: חוזרת על עצמה כל כמה שניות עד שמפסיקים */
    fun startRinging(ctx: Context, text: String) {
        stopRinging()
        speak(ctx, text)
        if (Prefs(ctx).repeatCalls) {
            val r = object : Runnable {
                override fun run() {
                    speak(ctx, text)
                    main.postDelayed(this, 7000)
                }
            }
            ringRunnable = r
            main.postDelayed(r, 7000)
        }
    }

    fun stopRinging() {
        ringRunnable?.let { main.removeCallbacks(it) }
        ringRunnable = null
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
    }

    /** האם מותר להקריא כרגע לפי ההגדרות */
    fun allowed(ctx: Context): Boolean {
        val p = Prefs(ctx)
        if (!p.enabled) return false
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        if (p.skipSilent && am.ringerMode != AudioManager.RINGER_MODE_NORMAL) return false
        if (p.btOnly && !bluetoothConnected(am)) return false
        return true
    }

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

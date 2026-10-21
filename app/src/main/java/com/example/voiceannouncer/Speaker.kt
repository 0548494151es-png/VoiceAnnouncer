package com.example.voiceannouncer

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

object Speaker {
    enum class Result { SPOKEN, DISABLED, QUIET_HOURS, NO_BLUETOOTH, SILENT_MODE }

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayList<String>()
    private var appContext: Context? = null
    private var focusRequest: AudioFocusRequest? = null
    private var counter = 0

    /** חייב להיקרא מה-thread הראשי (נקרא מ-App.onCreate) */
    @Synchronized
    fun init(context: Context) {
        if (tts != null) return
        val ctx = context.applicationContext
        appContext = ctx
        tts = TextToSpeech(ctx) { status ->
            synchronized(Speaker) {
                val t = tts
                if (status == TextToSpeech.SUCCESS && t != null) {
                    val r = t.setLanguage(Locale("he", "IL"))
                    if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) {
                        t.setLanguage(Locale.getDefault())
                    }
                    t.setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {}
                        override fun onDone(utteranceId: String?) { abandonFocus() }
                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) { abandonFocus() }
                    })
                    ready = true
                    for (text in pending) speakNow(text)
                    pending.clear()
                }
            }
        }
    }

    /** מקריא אם ההגדרות מאפשרות (מופעל, שעות שקט, בלוטוס בלבד) */
    @Synchronized
    fun announce(context: Context, text: String, ignoreQuiet: Boolean = false): Result {
        val prefs = Prefs(context)
        if (!prefs.enabled) return Result.DISABLED
        if (prefs.respectSilent && isPhoneSilent(context)) return Result.SILENT_MODE
        if (!ignoreQuiet && prefs.isQuietNow()) return Result.QUIET_HOURS
        if (prefs.bluetoothOnly && !isBluetoothAudioConnected(context)) return Result.NO_BLUETOOTH
        if (ready) speakNow(text) else if (pending.size < 20) pending.add(text)
        return Result.SPOKEN
    }

    @Synchronized
    fun stop() {
        tts?.stop()
        abandonFocus()
    }

    /** הטלפון במצב שקט או רטט */
    fun isPhoneSilent(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.ringerMode != AudioManager.RINGER_MODE_NORMAL
    }

    fun isBluetoothAudioConnected(context: Context): Boolean {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        return am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { d ->
            val type = d.type
            type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                (Build.VERSION.SDK_INT >= 28 && type == AudioDeviceInfo.TYPE_HEARING_AID) ||
                (Build.VERSION.SDK_INT >= 31 &&
                    (type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER))
        }
    }

    private fun speakNow(text: String) {
        val t = tts ?: return
        val ctx = appContext ?: return
        val prefs = Prefs(ctx)
        t.setSpeechRate(prefs.speechRate)
        t.setPitch(prefs.pitch)
        requestFocus(ctx)
        t.speak(text, TextToSpeech.QUEUE_ADD, null, "u" + (counter++))
    }

    private fun requestFocus(ctx: Context) {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .build()
        focusRequest = req
        am.requestAudioFocus(req)
    }

    @Synchronized
    private fun abandonFocus() {
        val ctx = appContext ?: return
        val req = focusRequest ?: return
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.abandonAudioFocusRequest(req)
        focusRequest = null
    }
}

package com.voiceannouncer.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.widget.NumberPicker
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * ניהול שמע להכרזות:
 *  1. הצלצול יורד לרמה 2 כשהשיחה מתחילה לצלצל, וחוזר לעוצמה המקורית בסיום השיחה.
 *  2. הקראת השם בעוצמה גבוהה (ברירת מחדל: מקסימום), ואחריה העוצמה חוזרת.
 *  3. במצב רטט/שקט – מקריאים רק אם מחוברת אוזניית בלוטוס, ורק דרכה (לעולם לא דרך הרמקול).
 *
 * כל עוצמה מקורית נשמרת בזיכרון קבוע (SharedPreferences), כך שגם אם האפליקציה/התהליך
 * נסגרו באמצע – העוצמה תוחזר בהפעלה הבאה.
 */
class AnnounceAudio(context: Context) {

    private val ctx = context.applicationContext
    private val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val prefs = ctx.getSharedPreferences("announce_audio", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())

    private var focusRequest: AudioFocusRequest? = null
    private var deviceCallback: AudioDeviceCallback? = null

    private val safetyRestore = Runnable {
        Log.w(TAG, "safety restore fired")
        restoreRing()
        restoreMusic()
    }

    companion object {
        private const val TAG = "AnnounceAudio"
        private const val KEY_RING = "saved_ring"
        private const val KEY_MUSIC = "saved_music"
        const val DEFAULT_DUCK_LEVEL = 2
        private const val KEY_LEVEL = "ring_duck_level"     // ההגדרה של המשתמש
        private const val KEY_APPLIED = "ring_applied_level" // הרמה שהופעלה בפועל בשיחה הנוכחית
        private const val SAFETY_RESTORE_MS = 150_000L // רשת ביטחון: שחזור אחרי 2.5 דקות לכל היותר
    }

    // ---------------------------------------------------------------- מצב המכשיר

    fun isRingerNormal(): Boolean = am.ringerMode == AudioManager.RINGER_MODE_NORMAL

    /** האם מחוברת כרגע אוזניית/רמקול בלוטוס לפלט שמע */
    fun isBluetoothOutputConnected(): Boolean {
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.any { isBluetoothType(it.type) }
    }

    private fun isBluetoothType(type: Int): Boolean {
        if (type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            type == AudioDeviceInfo.TYPE_HEARING_AID
        ) return true
        // BLE headset קיים מ-Android 12
        return Build.VERSION.SDK_INT >= 31 && type == AudioDeviceInfo.TYPE_BLE_HEADSET
    }

    /**
     * ההחלטה המרכזית: האם להקריא עכשיו?
     *  - רטט/שקט  -> רק אם יש בלוטוס מחובר.
     *  - רגיל     -> תמיד, חוץ מאם הוגדרה אפשרות "רק בבלוטוס" ואין בלוטוס.
     */
    fun shouldAnnounce(onlyOverBluetoothOption: Boolean): Boolean {
        val bt = isBluetoothOutputConnected()
        return if (!isRingerNormal()) bt else (!onlyOverBluetoothOption || bt)
    }

    // ---------------------------------------------------------------- תכונות שמע ל-TTS

    /**
     * שימוש ב-USAGE_MEDIA: ערוץ המדיה לא מושתק ע"י מצב רטט/שקט, והוא מנותב אוטומטית
     * לאוזניית הבלוטוס כשהיא מחוברת. (בניגוד ל-USAGE_NOTIFICATION שמושתק ברטט.)
     */
    fun audioAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    // ---------------------------------------------------------------- צלצול: יורד ל-2 וחוזר

    /** לקרוא כשהשיחה מתחילה לצלצל (CALL_STATE_RINGING), לפני ההכרזה הראשונה. */
    fun duckRing() {
        if (!isRingerNormal()) return // ברטט/שקט לא נוגעים בצלצול (כדי לא להפיל את המכשיר למצב אחר)
        try {
            val cur = am.getStreamVolume(AudioManager.STREAM_RING)
            val level = getRingDuckLevel()
            // מנמיכים רק אם הצלצול כרגע חזק מהרמה שנבחרה
            if (!prefs.contains(KEY_RING) && cur > level) {
                prefs.edit().putInt(KEY_RING, cur).putInt(KEY_APPLIED, level).commit() // קודם שומרים, אחר כך משנים
                am.setStreamVolume(AudioManager.STREAM_RING, level, 0)
            }
            armSafety()
        } catch (e: SecurityException) {
            Log.w(TAG, "duckRing denied (DND policy?)", e)
            prefs.edit().remove(KEY_RING).commit()
        }
    }

    /**
     * לקרוא כשהשיחה נגמרה / נענתה / נדחתה (CALL_STATE_IDLE או OFFHOOK),
     * וגם ב-onDestroy של השירות ובהפעלת האפליקציה.
     */
    fun restoreRing() {
        val saved = prefs.getInt(KEY_RING, -1)
        if (saved < 0) return
        try {
            // אם המשתמש שינה ידנית את העוצמה בינתיים – לא דורסים אותו
            val applied = prefs.getInt(KEY_APPLIED, getRingDuckLevel())
            if (am.getStreamVolume(AudioManager.STREAM_RING) == applied && isRingerNormal()) {
                am.setStreamVolume(AudioManager.STREAM_RING, saved, 0)
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "restoreRing denied", e)
        } finally {
            prefs.edit().remove(KEY_RING).remove(KEY_APPLIED).commit()
        }
    }

    // ---------------------------------------------------------------- הכרזה בעוצמה גבוהה

    /**
     * לקרוא לפני תחילת ההקראה (השם / ההודעה).
     * volumePercent: 100 = מקסימום (להקראת שם). להודעות אפשר להעביר את ההגדרה של המשתמש.
     */
    fun beginAnnouncement(volumePercent: Int = 100) {
        try {
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (!prefs.contains(KEY_MUSIC)) {
                prefs.edit().putInt(KEY_MUSIC, cur).commit()
            }
            val target = (max * volumePercent.coerceIn(10, 100) / 100f).toInt().coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "beginAnnouncement volume denied", e)
        }
        requestFocus()
        armSafety()
    }

    /** לקרוא בסיום כל ההקראה (אחרי הסיבוב האחרון של השם / סיום ההודעה). */
    fun endAnnouncement() {
        abandonFocus()
        restoreMusic()
    }

    private fun restoreMusic() {
        val saved = prefs.getInt(KEY_MUSIC, -1)
        if (saved < 0) return
        try {
            am.setStreamVolume(AudioManager.STREAM_MUSIC, saved, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "restoreMusic denied", e)
        } finally {
            prefs.edit().remove(KEY_MUSIC).commit()
        }
    }

    /** שחזור כללי – מומלץ לקרוא ב-onCreate/onDestroy של השירות וביציאה מהאפליקציה. */
    fun restoreAll() {
        main.removeCallbacks(safetyRestore)
        abandonFocus()
        unwatchBluetoothLoss()
        restoreRing()
        restoreMusic()
    }

    // ---------------------------------------------------------------- הגנה: ניתוק בלוטוס באמצע

    /**
     * ברטט/שקט: אם האוזנייה מתנתקת באמצע הקראה – עוצרים מיד,
     * כדי שהשמע לא "יקפוץ" לרמקול הטלפון.
     * onLost: תעצור שם את ה-TTS (tts.stop()).
     */
    fun watchBluetoothLoss(onLost: () -> Unit) {
        unwatchBluetoothLoss()
        val cb = object : AudioDeviceCallback() {
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                if (!isRingerNormal() && !isBluetoothOutputConnected()) {
                    onLost()
                }
            }
        }
        deviceCallback = cb
        am.registerAudioDeviceCallback(cb, main)
    }

    fun unwatchBluetoothLoss() {
        deviceCallback?.let { am.unregisterAudioDeviceCallback(it) }
        deviceCallback = null
    }

    // ---------------------------------------------------------------- הגדרה: לאיזו רמה הצלצול יורד

    /** הרמה שהצלצול יורד אליה בזמן הכרזה (ברירת מחדל 2). */
    fun getRingDuckLevel(): Int {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        return prefs.getInt(KEY_LEVEL, DEFAULT_DUCK_LEVEL).coerceIn(1, max)
    }

    fun setRingDuckLevel(level: Int) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        prefs.edit().putInt(KEY_LEVEL, level.coerceIn(1, max)).apply()
    }

    /**
     * חלון בחירה מוכן: גוללים למספר הרצוי (1 עד המקסימום של המכשיר) ולוחצים "שמור".
     * קוראים לו מכפתור בהגדרות:  audio.showRingLevelDialog(this)
     */
    fun showRingLevelDialog(activity: Activity, onSaved: ((Int) -> Unit)? = null) {
        val max = am.getStreamMaxVolume(AudioManager.STREAM_RING)
        val picker = NumberPicker(activity).apply {
            minValue = 1
            maxValue = max
            value = getRingDuckLevel()
            wrapSelectorWheel = false
        }
        AlertDialog.Builder(activity)
            .setTitle("עוצמת הצלצול בזמן הכרזה (1 עד $max)")
            .setView(picker)
            .setPositiveButton("שמור") { _, _ ->
                setRingDuckLevel(picker.value)
                onSaved?.invoke(picker.value)
            }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------------------------------------------------------------- פוקוס שמע

    private fun requestFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(audioAttributes())
                .build()
            focusRequest = req
            am.requestAudioFocus(req)
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= 26) {
            focusRequest?.let { am.abandonAudioFocusRequest(it) }
            focusRequest = null
        }
    }

    private fun armSafety() {
        main.removeCallbacks(safetyRestore)
        main.postDelayed(safetyRestore, SAFETY_RESTORE_MS)
    }
}

/*
=====================  איך מחברים לקוד הקיים  =====================

val audio = AnnounceAudio(this)

// 1) בעת יצירת השירות / הפעלת האפליקציה – מנקים שאריות אם היו:
audio.restoreAll()

// 2) כשמתחיל צלצול (CALL_STATE_RINGING), אחרי שיש שם מתקשר:
if (audio.shouldAnnounce(onlyBluetoothSetting)) {
    audio.duckRing()                       // הצלצול -> 2
    audio.beginAnnouncement(100)           // שם בעוצמה מקסימלית
    audio.watchBluetoothLoss { tts.stop() }

    tts.setAudioAttributes(audio.audioAttributes())
    val params = Bundle().apply {
        putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1.0f)
    }
    // ... הקראת השם כמה פעמים כרגיל, עם params ...
    // ב-UtteranceProgressListener.onDone של ההקראה האחרונה:
    //     audio.endAnnouncement(); audio.unwatchBluetoothLoss()
}

// 3) כשהשיחה נגמרה / נענתה / נדחתה (IDLE או OFFHOOK):
audio.restoreRing()
audio.endAnnouncement()   // ליתר ביטחון, אם ההקראה עדיין פתוחה

// 4) בהודעות נכנסות (SMS וכו'):
if (audio.shouldAnnounce(onlyBluetoothSetting)) {
    audio.beginAnnouncement(messageVolumePercent)
    // ... הקראה ...  ובסיום: audio.endAnnouncement()
}

// 5) ב-onDestroy של השירות: audio.restoreAll()

// 6) בדף ההגדרות – כפתור "עוצמת צלצול בזמן הכרזה":
//    btnRingLevel.setOnClickListener { audio.showRingLevelDialog(this) }
*/

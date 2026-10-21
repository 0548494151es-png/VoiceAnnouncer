package com.example.voiceannouncer

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.TimePickerDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var prefs: Prefs
    private lateinit var root: LinearLayout
    private lateinit var statusView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        buildUi()
        KeepAliveService.start(this)
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
        KeepAliveService.start(this)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun buildUi() {
        val scroll = ScrollView(this)
        root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(20), dp(24), dp(20), dp(32))
        scroll.addView(root)
        setContentView(scroll)

        addTitle("קורא הודעות ושיחות")
        statusView = TextView(this)
        statusView.textSize = 15f
        statusView.setPadding(0, dp(4), 0, dp(12))
        root.addView(statusView)

        addHeader("הרשאות והפעלה")
        addButton("1. הפעלת גישה להתראות (לקריאת הודעות)") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        addButton("2. אישור הרשאות (שיחות, אנשי קשר והתראות)") {
            val perms = ArrayList<String>()
            perms.add(Manifest.permission.READ_PHONE_STATE)
            perms.add(Manifest.permission.READ_CALL_LOG)
            perms.add(Manifest.permission.READ_CONTACTS)
            if (Build.VERSION.SDK_INT >= 33) perms.add(Manifest.permission.POST_NOTIFICATIONS)
            requestPermissions(perms.toTypedArray(), 1)
        }
        addButton("3. ביטול חיסכון בסוללה (כדי שימשיך לעבוד ברקע)") {
            startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }

        addHeader("כללי")
        addSwitch("הקראה פעילה", prefs.enabled) { prefs.enabled = it }
        addSwitch("שירות רקע קבוע (מומלץ, כדי שיעבוד כשהאפליקציה סגורה)", prefs.keepAlive) {
            prefs.keepAlive = it
            if (it) KeepAliveService.start(this) else KeepAliveService.stop(this)
        }
        addSwitch("לא להקריא כשהטלפון במצב שקט או רטט", prefs.respectSilent) {
            prefs.respectSilent = it
        }
        addSwitch("הקראה רק כשמחובר התקן בלוטוס", prefs.bluetoothOnly) {
            prefs.bluetoothOnly = it
            updateStatus()
        }
        addSwitch("הכרזת שיחות נכנסות (שם המתקשר)", prefs.announceCalls) { prefs.announceCalls = it }
        addSwitch("חזרה על הכרזת השיחה בזמן צלצול", prefs.repeatCall) { prefs.repeatCall = it }
        addSwitch("הקראת הודעות", prefs.announceMessages) { prefs.announceMessages = it }
        addSwitch("הקראת תוכן ההודעה (ולא רק השולח)", prefs.readContent) { prefs.readContent = it }
        addSwitch("הקראת כל ההתראות (לא רק הודעות)", prefs.readAllNotifications) {
            prefs.readAllNotifications = it
        }
        addButton("בחירת אפליקציות להקראה") { showAppsDialog() }

        addHeader("קול")
        addSlider("מהירות דיבור", 0.5f, 2.0f, prefs.speechRate) { prefs.speechRate = it }
        addSlider("גובה קול", 0.5f, 2.0f, prefs.pitch) { prefs.pitch = it }
        addButton("בדיקת קול") {
            val r = Speaker.announce(this, "זוהי בדיקה של קורא ההודעות", ignoreQuiet = true)
            val msg = when (r) {
                Speaker.Result.SPOKEN -> "מקריא..."
                Speaker.Result.DISABLED -> "הקראה כבויה"
                Speaker.Result.NO_BLUETOOTH -> "אין התקן בלוטוס מחובר"
                Speaker.Result.QUIET_HOURS -> "שעות שקט"
                Speaker.Result.SILENT_MODE -> "הטלפון במצב שקט או רטט"
            }
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
        }

        addHeader("שעות שקט (להודעות)")
        addSwitch("לא להקריא הודעות בשעות שקט", prefs.quietEnabled) { prefs.quietEnabled = it }
        val startBtn = Button(this)
        val endBtn = Button(this)
        fun fmt(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        fun refresh() {
            startBtn.text = "התחלה: " + fmt(prefs.quietStart)
            endBtn.text = "סיום: " + fmt(prefs.quietEnd)
        }
        startBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs.quietStart = h * 60 + m
                refresh()
            }, prefs.quietStart / 60, prefs.quietStart % 60, true).show()
        }
        endBtn.setOnClickListener {
            TimePickerDialog(this, { _, h, m ->
                prefs.quietEnd = h * 60 + m
                refresh()
            }, prefs.quietEnd / 60, prefs.quietEnd % 60, true).show()
        }
        refresh()
        root.addView(startBtn)
        root.addView(endBtn)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        updateStatus()
        KeepAliveService.start(this)
    }

    private fun updateStatus() {
        val listener = isListenerEnabled()
        val perms = listOf(
            Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS
        ).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        val bt = Speaker.isBluetoothAudioConnected(this)
        statusView.text = buildString {
            append(if (listener) "✔ גישה להתראות: פעילה\n" else "✘ גישה להתראות: כבויה (לחץ על כפתור 1)\n")
            append(if (perms) "✔ הרשאות שיחות ואנשי קשר: אושרו\n" else "✘ הרשאות שיחות ואנשי קשר: חסרות (לחץ על כפתור 2)\n")
            append(if (prefs.keepAlive) "✔ שירות רקע קבוע: פעיל\n" else "• שירות רקע קבוע: כבוי\n")
            append(if (bt) "✔ בלוטוס: מחובר התקן שמע" else "• בלוטוס: אין התקן שמע מחובר")
        }
    }

    private fun isListenerEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
            ?: return false
        return flat.contains(packageName)
    }

    private fun showAppsDialog() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        val blocked = prefs.blockedApps.toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first !in blocked }
        AlertDialog.Builder(this)
            .setTitle("אפליקציות שיוקראו")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, isChecked ->
                if (isChecked) blocked.remove(apps[i].first) else blocked.add(apps[i].first)
            }
            .setPositiveButton("שמור") { _, _ -> prefs.blockedApps = blocked }
            .setNegativeButton("ביטול", null)
            .show()
    }

    // ---------- עזרי UI ----------

    private fun addTitle(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 24f
        tv.typeface = Typeface.DEFAULT_BOLD
        root.addView(tv)
    }

    private fun addHeader(text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 17f
        tv.typeface = Typeface.DEFAULT_BOLD
        tv.setPadding(0, dp(18), 0, dp(4))
        root.addView(tv)
    }

    private fun addButton(text: String, onClick: () -> Unit) {
        val b = Button(this)
        b.text = text
        b.setOnClickListener { onClick() }
        root.addView(b)
    }

    private fun addSwitch(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
        val s = Switch(this)
        s.text = text
        s.textSize = 16f
        s.isChecked = checked
        s.setPadding(0, dp(10), 0, dp(10))
        s.setOnCheckedChangeListener { _, v -> onChange(v) }
        root.addView(s)
    }

    private fun addSlider(label: String, min: Float, max: Float, value: Float, onChange: (Float) -> Unit) {
        val tv = TextView(this)
        tv.text = label + ": " + "%.2f".format(value)
        val sb = SeekBar(this)
        sb.max = 30
        sb.progress = (((value - min) / (max - min)) * 30).toInt()
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val v = min + (max - min) * progress / 30f
                tv.text = label + ": " + "%.2f".format(v)
                if (fromUser) onChange(v)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })
        root.addView(tv)
        root.addView(sb)
    }
}

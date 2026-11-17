package com.voiceannouncer.app

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** מסך הגדרות של הקראת המסך (כפתור צף, תרגום, גודל, שקיפות ומהירות). */
class ScreenReaderSettingsActivity : Activity() {

    private val purple = Color.parseColor("#6A1B9A")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        build()
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun text(s: String, sp: Float, bold: Boolean = false, color: Int = Color.parseColor("#222222")): TextView {
        val t = TextView(this)
        t.text = s
        t.textSize = sp
        t.setTextColor(color)
        t.gravity = Gravity.START
        if (bold) t.setTypeface(t.typeface, android.graphics.Typeface.BOLD)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        t.layoutParams = lp
        return t
    }

    private fun button(label: String, onClick: () -> Unit): Button {
        val b = Button(this)
        b.text = label
        b.setOnClickListener { onClick() }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(10)
        b.layoutParams = lp
        return b
    }

    private fun switchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit): Switch {
        val s = Switch(this)
        s.text = label
        s.textSize = 16f
        s.isChecked = checked
        s.setOnCheckedChangeListener { _, v -> onChange(v) }
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(14)
        s.layoutParams = lp
        return s
    }

    /** מחוון עם כותרת. הערך נשמר רק בסיום הגרירה, כדי שהכפתור הצף לא יהבהב בזמן הגרירה. */
    private fun seek(
        root: LinearLayout, title: (Int) -> String, lo: Int, hi: Int, cur: Int, onSave: (Int) -> Unit
    ) {
        val label = text(title(cur), 15f)
        root.addView(label)
        val sb = SeekBar(this)
        sb.max = hi - lo
        sb.progress = (cur - lo).coerceIn(0, hi - lo)
        sb.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                label.text = title(lo + p)
            }

            override fun onStartTrackingTouch(s: SeekBar?) {}

            override fun onStopTrackingTouch(s: SeekBar?) {
                onSave(lo + (s?.progress ?: 0))
            }
        })
        root.addView(sb)
    }

    private fun build() {
        val root = LinearLayout(this)
        root.orientation = LinearLayout.VERTICAL
        root.layoutDirection = View.LAYOUT_DIRECTION_RTL
        root.setPadding(dp(18), dp(18), dp(18), dp(28))

        root.addView(text("הקראת מסך", 24f, true, purple))
        root.addView(
            text(
                "לחיצה על הכפתור הצף מקריאה בקול את הטקסט שמוצג עכשיו במסך, בכל אפליקציה. " +
                    "לחיצה נוספת עוצרת. אפשר לגרור את הכפתור למקום שנוח.",
                15f
            )
        )

        val on = ScreenPrefs.serviceOn(this)
        if (on) {
            root.addView(text("השירות פעיל. הכפתור הצף מוצג על המסך.", 16f, true, Color.parseColor("#2E7D32")))
        } else {
            root.addView(text("השירות עדיין לא מופעל.", 16f, true, Color.parseColor("#C62828")))
            root.addView(
                text(
                    "כדי להפעיל: לחץ על הכפתור למטה, בחר \"קורא הודעות - הקראת מסך\" ואשר את ההפעלה.",
                    15f
                )
            )
        }
        root.addView(button("פתח את הגדרות הנגישות") {
            try {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            } catch (_: Exception) {
            }
        })

        root.addView(text("כפתור צף", 18f, true, purple))
        root.addView(switchRow("הצג את הכפתור הצף", ScreenPrefs.buttonOn(this)) {
            ScreenPrefs.setButtonOn(this, it)
        })
        seek(root, { "גודל הכפתור: $it" }, 32, 80, ScreenPrefs.sizeDp(this)) {
            ScreenPrefs.setSizeDp(this, it)
        }
        seek(root, { "שקיפות: הכפתור גלוי ב-$it%" }, 20, 100, ScreenPrefs.alphaPct(this)) {
            ScreenPrefs.setAlphaPct(this, it)
        }
        root.addView(button("החזר את הכפתור למיקום ברירת המחדל") {
            ScreenPrefs.resetPos(this)
        })

        root.addView(text("קריאה ותרגום", 18f, true, purple))
        root.addView(switchRow("תרגם כיתוב באנגלית לעברית", ScreenPrefs.translate(this)) {
            ScreenPrefs.setTranslate(this, it)
        })
        root.addView(
            text(
                "התרגום נעשה דרך שירות התרגום של גוגל ודורש אינטרנט. הטקסט באנגלית שמוצג במסך נשלח לתרגום " +
                    "רק ברגע הלחיצה. בלי אינטרנט, או כשהתרגום כבוי, הכיתוב באנגלית יוקרא באנגלית.",
                13f,
                false,
                Color.parseColor("#555555")
            )
        )
        val rate10 = Math.round(ScreenPrefs.rate(this) * 10f)
        seek(root, { "מהירות הקראה: ${it / 10.0}" }, 5, 20, rate10.coerceIn(5, 20)) {
            ScreenPrefs.setRate(this, it / 10f)
        }
        root.addView(
            text(
                "ההקראה משתמשת בעוצמת המדיה של הטלפון, ולכן עובדת גם כשהטלפון על שקט. " +
                    "אם לא שומעים כלום, צריך להגביר את עוצמת המדיה.",
                13f,
                false,
                Color.parseColor("#555555")
            )
        )

        val scroll = ScrollView(this)
        scroll.addView(root)
        scroll.setBackgroundColor(Color.parseColor("#FFF3FB"))
        setContentView(scroll)
    }
}

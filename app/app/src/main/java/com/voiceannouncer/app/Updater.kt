package com.voiceannouncer.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * עדכון מתוך האפליקציה: בודק את ה-Release האחרון ב-GitHub, מוריד את ה-APK ומפעיל התקנה.
 * חייב להיקרא מ-thread רקע (check / download).
 */
object Updater {
    data class Info(val versionCode: Int, val tag: String, val assetUrl: String, val size: Long)

    fun currentVersion(ctx: Context): Pair<Int, String> {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return PackageInfoCompat.getLongVersionCode(pi).toInt() to (pi.versionName ?: "?")
    }

    private fun open(url: String, token: String, accept: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = false
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("Accept", accept)
        c.setRequestProperty("User-Agent", "VoiceAnnouncer")
        if (token.isNotBlank()) c.setRequestProperty("Authorization", "Bearer ${token.trim()}")
        return c
    }

    /** מחזיר מידע על גרסה חדשה, או null אם האפליקציה כבר מעודכנת */
    fun check(ctx: Context): Info? {
        val p = Prefs(ctx)
        val c = open(
            "https://api.github.com/repos/${p.repo.trim()}/releases/latest",
            p.token, "application/vnd.github+json"
        )
        when (c.responseCode) {
            200 -> {}
            404 -> error("לא נמצאה גרסה. בדוק את שם המאגר, ואם המאגר פרטי - הוסף טוקן")
            401, 403 -> error("אין הרשאה. בדוק את הטוקן")
            else -> error("שגיאה ${c.responseCode}")
        }
        val json = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        val tag = json.getString("tag_name")
        val code = tag.filter { it.isDigit() }.toIntOrNull() ?: error("תג גרסה לא תקין: $tag")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) {
                val info = Info(code, tag, a.getString("url"), a.optLong("size", 0))
                return if (code > currentVersion(ctx).first) info else null
            }
        }
        error("בגרסה האחרונה אין קובץ APK")
    }

    fun download(ctx: Context, info: Info, onProgress: (Float) -> Unit): File {
        val p = Prefs(ctx)
        var c = open(info.assetUrl, p.token, "application/octet-stream")
        var hops = 0
        // ההורדה מגיעה בהפניה לשרת אחר - שם אסור לשלוח את הטוקן
        while (c.responseCode in intArrayOf(301, 302, 303, 307, 308)) {
            val loc = c.getHeaderField("Location") ?: error("הפניה לא תקינה")
            c.disconnect()
            if (++hops > 5) error("יותר מדי הפניות")
            c = open(loc, "", "application/octet-stream")
        }
        if (c.responseCode != 200) error("ההורדה נכשלה (${c.responseCode})")

        val dir = File(ctx.cacheDir, "updates")
        dir.mkdirs()
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "VoiceAnnouncer.apk")
        val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.size
        var done = 0L
        c.inputStream.use { input ->
            out.outputStream().use { output ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    output.write(buf, 0, n)
                    done += n
                    if (total > 0) onProgress((done.toFloat() / total).coerceIn(0f, 1f))
                }
            }
        }
        return out
    }

    /** מחזיר false אם צריך קודם לאשר "התקנה ממקורות לא ידועים" לאפליקציה הזו */
    fun install(ctx: Context, file: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}")
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}

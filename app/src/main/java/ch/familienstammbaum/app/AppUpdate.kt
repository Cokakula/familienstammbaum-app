package ch.familienstammbaum.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Neue Version der App: höchstens einmal am Tag bei GitHub nachsehen (öffentliche Angabe, ohne Familiendaten)
 * und pro neuer Version eine einzige Mitteilung. Antippen lädt die neue APK im Browser herunter.
 */
object AppUpdate {
    private const val CHANNEL = "updates"
    private const val ID = 0x0FD7
    private const val HOUR = 3_600_000L
    private const val API = "https://api.github.com/repos/Cokakula/familienstammbaum-app/releases/latest"
    const val APK = "https://github.com/Cokakula/familienstammbaum-app/releases/latest/download/Familienstammbaum.apk"
    private val VER = Regex("""^\d+(\.\d+){1,3}$""")

    fun installed(c: Context): String = try { c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "" } catch (e: Exception) { "" }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.').map { it.toIntOrNull() ?: 0 }
        val y = b.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }

    /** Neueste veröffentlichte Version, z.B. «1.0.16», oder null */
    fun latest(): String? = try {
        val conn = URL(API).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "Familienstammbaum-App")
        if (conn.responseCode != 200) { conn.disconnect(); null } else {
            val j = JSONObject(conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            conn.disconnect()
            val v = j.optString("tag_name").removePrefix("v")
            if (j.optBoolean("draft") || j.optBoolean("prerelease") || !VER.matches(v)) null else v
        }
    } catch (e: Exception) { null }

    @SuppressLint("MissingPermission")
    fun check(c: Context) {
        val now = System.currentTimeMillis()
        if (now - Prefs.updChecked(c) < 20 * HOUR) return
        val v = latest() ?: return
        Prefs.setUpdChecked(c, now)
        val mine = installed(c)
        if (mine.isEmpty() || !newer(v, mine) || Prefs.updNotified(c) == v) return
        if (!Birthdays.canNotify(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        val sq = Prefs.lang(c) == "sq"
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, c.getString(R.string.upd_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val title = c.getString(if (sq) R.string.upd_title_sq else R.string.upd_title, v)
        val text = c.getString(if (sq) R.string.upd_text_sq else R.string.upd_text)
        val open = Intent(Intent.ACTION_VIEW, Uri.parse(APK)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val n = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(c, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .addAction(0, c.getString(if (sq) R.string.upd_action_sq else R.string.upd_action),
                PendingIntent.getActivity(c, ID + 1, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(c, R.color.accent))
            .build()
        try { NotificationManagerCompat.from(c).notify(ID, n); Prefs.setUpdNotified(c, v) } catch (e: SecurityException) {}
    }
}

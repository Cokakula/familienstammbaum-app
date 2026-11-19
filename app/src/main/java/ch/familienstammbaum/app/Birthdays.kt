package ch.familienstammbaum.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Birthday(
    val id: String,
    val name: String,
    val firstName: String,
    val year: Int,
    val month: Int,
    val day: Int,
    val phone: String,
    val tree: String,
) {
    /** 29. Februar wird in Nicht-Schaltjahren am 28. Februar gefeiert */
    fun dayIn(year: Int): Pair<Int, Int> =
        if (month == 2 && day == 29 && !Year.isLeap(year.toLong())) Pair(2, 28) else Pair(month, day)

    fun isOn(date: LocalDate): Boolean {
        val (m, d) = dayIn(date.year)
        return m == date.monthValue && d == date.dayOfMonth
    }

    fun ageOn(date: LocalDate): Int = if (year > 0) date.year - year else 0
}

object Birthdays {
    const val CHANNEL = "geburtstage"
    private const val WORK = "geburtstage-taeglich"
    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    fun parse(json: String): List<Birthday> {
        val root = JSONObject(json)
        if (!root.optBoolean("ok")) return emptyList()
        val arr = root.optJSONArray("people") ?: return emptyList()
        val out = ArrayList<Birthday>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val m = DATE.find(o.optString("birthDate")) ?: continue
            val name = o.optString("name").ifBlank { "Unbekannt" }
            out.add(
                Birthday(
                    id = o.optString("id"),
                    name = name,
                    firstName = o.optString("firstName").ifBlank { name },
                    year = m.groupValues[1].toInt(),
                    month = m.groupValues[2].toInt(),
                    day = m.groupValues[3].toInt(),
                    phone = o.optString("phone"),
                    tree = o.optString("tree"),
                )
            )
        }
        return out
    }

    /** Holt die Geburtstage aus dem Google-Stammbaum. null, wenn es gerade nicht ging. */
    fun fetch(context: Context): List<Birthday>? {
        val link = Prefs.link(context) ?: return null
        return try {
            val conn = URL(Links.birthdaysUrl(link)).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 40_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("Accept", "application/json")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (!JSONObject(text).optBoolean("ok")) return null
            Prefs.setCache(context, text)
            parse(text)
        } catch (e: Exception) {
            null
        }
    }

    /** Frisch aus Google, sonst die zuletzt geladene Liste */
    fun load(context: Context): List<Birthday> =
        fetch(context) ?: Prefs.cache(context)?.let { runCatching { parse(it) }.getOrNull() } ?: emptyList()

    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_name), NotificationManager.IMPORTANCE_DEFAULT)
            channel.description = context.getString(R.string.channel_description)
            nm.createNotificationChannel(channel)
        }
    }

    /** Zeigt die Erinnerungen für heute und, falls eingeschaltet, für morgen. Gibt die Anzahl zurück. */
    fun notifyDue(context: Context, list: List<Birthday>, today: LocalDate, force: Boolean): Int {
        val stamp = today.toString()
        if (!force && Prefs.lastNotified(context) == stamp) return 0
        ensureChannel(context)
        var count = 0
        list.filter { it.isOn(today) }.forEach { if (post(context, it, today, true)) count++ }
        if (Prefs.dayBefore(context)) {
            val tomorrow = today.plusDays(1)
            list.filter { it.isOn(tomorrow) }.forEach { if (post(context, it, tomorrow, false)) count++ }
        }
        Prefs.setLastNotified(context, stamp)
        return count
    }

    @SuppressLint("MissingPermission")
    private fun post(context: Context, b: Birthday, date: LocalDate, isToday: Boolean): Boolean {
        if (!canNotify(context)) return false
        val age = b.ageOn(date)
        val title = context.getString(if (isToday) R.string.bday_today_title else R.string.bday_tomorrow_title, b.name)
        val text = when {
            age > 0 && isToday -> context.getString(R.string.bday_today_text_age, b.firstName, age)
            age > 0 -> context.getString(R.string.bday_tomorrow_text_age, b.firstName, age)
            else -> context.getString(R.string.bday_text_plain)
        }
        val base = b.id.hashCode() and 0x0FFFFFFF
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PERSON, b.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val builder = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, base, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setColor(ContextCompat.getColor(context, R.color.accent))
        val phone = b.phone.filter { it.isDigit() || it == '+' }
        if (isToday && phone.length >= 6) {
            val dial = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            builder.addAction(0, context.getString(R.string.action_call),
                PendingIntent.getActivity(context, base + 1, dial, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val intl = when {
                phone.startsWith("+") -> phone.drop(1)
                phone.startsWith("00") -> phone.drop(2)
                else -> null
            }
            if (intl != null && intl.length >= 8) {
                val message = Uri.encode(context.getString(R.string.wa_text, b.firstName))
                val wa = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$intl?text=$message"))
                builder.addAction(0, context.getString(R.string.action_whatsapp),
                    PendingIntent.getActivity(context, base + 2, wa, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
        }
        return try {
            NotificationManagerCompat.from(context).notify(base * 2 + (if (isToday) 0 else 1), builder.build())
            true
        } catch (e: SecurityException) {
            false
        }
    }

    /** Der nächste Geburtstag ab einem Datum, für die Anzeige in den Einstellungen */
    fun next(list: List<Birthday>, from: LocalDate): Pair<Birthday, LocalDate>? {
        var best: Pair<Birthday, LocalDate>? = null
        for (b in list) {
            var year = from.year
            var date = b.dayIn(year).let { (m, d) -> LocalDate.of(year, m, d) }
            while (date.isBefore(from)) {
                year++
                date = b.dayIn(year).let { (m, d) -> LocalDate.of(year, m, d) }
            }
            val current = best
            if (current == null || date.isBefore(current.second)) best = Pair(b, date)
        }
        return best
    }

    fun formatDate(d: LocalDate): String =
        "${d.dayOfMonth}. ${d.month.getDisplayName(TextStyle.FULL, Locale.GERMAN)}"

    /** Plant die tägliche Prüfung zur gewählten Uhrzeit */
    fun schedule(context: Context, update: Boolean) {
        val wm = WorkManager.getInstance(context)
        if (!Prefs.remindersOn(context) || Prefs.link(context) == null) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<BirthdayWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(delayToNext(context), TimeUnit.MILLISECONDS)
            .build()
        val policy = if (update) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(WORK, policy, request)
    }

    private fun delayToNext(context: Context): Long {
        val now = LocalDateTime.now()
        var next = now.toLocalDate().atTime(Prefs.hour(context), Prefs.minute(context))
        if (!next.isAfter(now)) next = next.plusDays(1)
        return Duration.between(now, next).toMillis()
    }

    /** Wie weit die aktuelle Zeit von der gewünschten Uhrzeit entfernt ist (in Minuten) */
    fun minutesOff(context: Context): Long {
        val now = LocalDateTime.now()
        val target = now.toLocalDate().atTime(Prefs.hour(context), Prefs.minute(context))
        return kotlin.math.abs(Duration.between(target, now).toMinutes())
    }
}

/** Läuft einmal am Tag im Hintergrund, auch wenn die App geschlossen ist */
class BirthdayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (Prefs.remindersOn(c) && Prefs.link(c) != null) {
            val list = withContext(Dispatchers.IO) { Birthdays.load(c) }
            Birthdays.notifyDue(c, list, LocalDate.now(), force = false)
        }
        // Falls Android die Prüfung stark verschoben hat: wieder auf die gewünschte Uhrzeit legen
        if (Birthdays.minutesOff(c) > 120) Birthdays.schedule(c, update = true)
        return Result.success()
    }
}

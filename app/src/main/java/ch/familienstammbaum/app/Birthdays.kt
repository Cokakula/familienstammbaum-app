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
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.Year
import java.time.format.TextStyle
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

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

    /**
     * Holt die verschlüsselten Einträge aus Google und entschlüsselt die Personen hier auf dem Handy.
     * null, wenn es gerade nicht ging (dann gilt die zuletzt bekannte Liste).
     */
    fun fetch(context: Context): List<Birthday>? {
        val s = Prefs.session(context) ?: return null
        return try {
            val sess = JSONObject(s)
            val keysObj = sess.getJSONObject("keys")
            val keys = HashMap<Int, ByteArray>()
            keysObj.keys().forEach { k -> keys[k.toInt()] = EntryCrypto.unb64u(keysObj.getString(k)) }
            val body = JSONObject().put("fn", "apiLoad").put("args", JSONArray().put(sess.getString("a"))).toString()
            val res = JSONObject(Net.post(sess.getString("exec"), body))
            if (!res.optBoolean("ok")) return null
            val r = res.getJSONObject("r")
            if (r.optBoolean("denied")) { Prefs.clearSession(context); return emptyList() }
            val e = r.optJSONArray("e") ?: return null
            val trees = HashMap<String, String>()
            val people = ArrayList<JSONObject>()
            val events = ArrayList<JSONObject>()
            var lastBackup = sess.optLong("lastBackup")
            var firstCreated = 0L
            var count = 0
            for (i in 0 until e.length()) {
                val row = e.getJSONArray(i)
                val id = row.getString(0)
                val txt = EntryCrypto.decrypt(keys, id, row.getString(1))
                if (txt == null) {
                    if (EntryCrypto.version(row.getString(1)) !in keys) return null   // neuer Schlüssel: App einmal öffnen
                    continue
                }
                val o = JSONObject(txt)
                if (o.has("_del")) continue          // im Papierkorb
                when (o.optString("k")) {
                    "t" -> { trees[id] = o.optString("name"); lastBackup = maxOf(lastBackup, o.optLong("lastBackup")) }
                    "e" -> events.add(o)
                    "p" -> {
                        o.put("id", id); people.add(o); count++
                        val c = o.optLong("createdAt"); if (c > 0 && (firstCreated == 0L || c < firstCreated)) firstCreated = c
                    }
                }
            }
            val out = JSONArray()
            for (p in people) {
                if (p.optBoolean("deceased") || p.optString("deathDate").isNotEmpty()) continue
                val bd = p.optString("birthDate")
                if (!DATE.matches(bd)) continue
                val first = p.optString("firstName")
                val last = p.optString("lastName")
                val name = listOf(first, last).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Ohne Namen" }
                out.put(
                    JSONObject().put("id", p.optString("id")).put("name", name).put("firstName", first.ifBlank { last.ifBlank { name } })
                        .put("birthDate", bd).put("phone", p.optString("phone")).put("tree", trees[p.optString("treeId")] ?: "")
                )
            }
            BackupReminder.check(context, sess.optBoolean("admin"), lastBackup, firstCreated, count)
            if (sess.optBoolean("memorials")) MemorialReminder.check(context, people)
            val today = LocalDate.now().toString()
            val ev = JSONArray()
            events.filter { DATE.matches(it.optString("date")) && it.optString("date") >= today }
                .sortedBy { it.optString("date") + it.optString("time") }.take(8)
                .forEach { e -> ev.put(JSONObject().put("title", e.optString("title")).put("date", e.optString("date")).put("time", e.optString("time")).put("place", e.optString("place")).put("tree", trees[e.optString("treeId")] ?: "")) }
            val calls = Prefs.cache(context)?.let { runCatching { JSONObject(it).optJSONArray("calls") }.getOrNull() } ?: JSONArray()
            val json = JSONObject().put("ok", true).put("people", out).put("events", ev).put("calls", calls).toString()
            Prefs.setCache(context, json)
            FamilyWidget.refresh(context)
            parse(json)
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
        /* Runde Geburtstage: zwei Wochen vorher Bescheid geben, damit Zeit für ein Fest bleibt */
        val in14 = today.plusDays(14)
        list.filter { it.isOn(in14) && it.ageOn(in14) in ROUND_AGES }.forEach { if (postRound(context, it, in14)) count++ }
        Prefs.setLastNotified(context, stamp)
        return count
    }

    private val ROUND_AGES = setOf(18, 20, 25, 30, 40, 50, 60, 65, 70, 75, 80, 85, 90, 95, 100)

    @SuppressLint("MissingPermission")
    private fun postRound(context: Context, b: Birthday, date: LocalDate): Boolean {
        if (!canNotify(context)) return false
        val age = b.ageOn(date)
        val code = (b.id.hashCode() and 0x0FFFFFFF) + 3
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_PERSON, b.id)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.round_title, b.firstName, age))
            .setContentText(context.getString(R.string.round_text, formatDate(date)))
            .setContentIntent(PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .build()
        return try { NotificationManagerCompat.from(context).notify(code, n); true } catch (e: SecurityException) { false }
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
        if (!Prefs.remindersOn(context) || Prefs.exec(context) == null) {
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

/** Meldet sich bei Admins, wenn die letzte Sicherung über einen Monat alt ist (höchstens einmal pro Woche) */
object BackupReminder {
    private const val CHANNEL = "sicherung"
    private const val ID = 0x5EC0
    private const val DAY = 86_400_000L

    @SuppressLint("MissingPermission")
    fun check(context: Context, admin: Boolean, last: Long, firstCreated: Long, count: Int) {
        if (!admin || count == 0) return
        val now = System.currentTimeMillis()
        if (last > 0 && now - last < 30 * DAY) return
        if (last == 0L && (firstCreated == 0L || now - firstCreated < 3 * DAY)) return
        if (now - Prefs.backupNotified(context) < 7 * DAY) return
        if (!Birthdays.canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.backup_channel), NotificationManager.IMPORTANCE_DEFAULT))
        }
        val text = if (last > 0) context.getString(R.string.backup_text_days, ((now - last) / DAY).toInt()) else context.getString(R.string.backup_text_never)
        val open = Intent(context, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_BACKUP, true)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.getString(R.string.backup_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(PendingIntent.getActivity(context, ID, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .setColor(ContextCompat.getColor(context, R.color.accent))
            .build()
        try { NotificationManagerCompat.from(context).notify(ID, n); Prefs.setBackupNotified(context, now) } catch (e: SecurityException) {}
    }
}

/** Gedenktage: am Todestag eine stille Mitteilung (nur wenn in der App eingeschaltet) */
object MemorialReminder {
    private const val CHANNEL = "gedenktage"
    private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

    @SuppressLint("MissingPermission")
    fun check(context: Context, people: List<JSONObject>) {
        val today = LocalDate.now()
        val stamp = today.toString()
        if (Prefs.memorialNotified(context) == stamp) return
        if (!Birthdays.canNotify(context)) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (p in people) {
            if (!(p.optBoolean("deceased") || p.optString("deathDate").isNotEmpty())) continue
            val m = DATE.find(p.optString("deathDate")) ?: continue
            val y = m.groupValues[1].toInt(); val mo = m.groupValues[2].toInt(); var d = m.groupValues[3].toInt()
            if (mo == 2 && d == 29 && !today.isLeapYear) d = 28
            if (mo != today.monthValue || d != today.dayOfMonth) continue
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.mem_channel), NotificationManager.IMPORTANCE_LOW))
            val name = listOf(p.optString("firstName"), p.optString("lastName")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "?" }
            val years = today.year - y
            val text = if (y > 0 && years > 0) context.getString(R.string.mem_text, years, name) else context.getString(R.string.mem_text_plain, name)
            val id = p.optString("id")
            val open = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_PERSON, id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val code = (id.hashCode() and 0x0FFFFFFF) + 7
            val n = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.mem_title, name))
                .setContentText(text)
                .setContentIntent(PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setColor(ContextCompat.getColor(context, R.color.accent))
                .build()
            try { NotificationManagerCompat.from(context).notify(code, n) } catch (e: SecurityException) {}
        }
        Prefs.setMemorialNotified(context, stamp)
    }
}

/** Anruf-Erinnerung: «Zeit, … anzurufen» (aus der zuletzt von der App übergebenen Liste, höchstens alle 3 Tage pro Person) */
object CallReminder {
    private const val CHANNEL = "anrufe"
    private const val DAY = 86_400_000L

    @SuppressLint("MissingPermission")
    fun check(context: Context) {
        val json = Prefs.cache(context) ?: return
        val arr = runCatching { JSONObject(json).optJSONArray("calls") }.getOrNull() ?: return
        if (arr.length() == 0 || !Birthdays.canNotify(context)) return
        val now = System.currentTimeMillis()
        val sent = runCatching { JSONObject(Prefs.callsNotified(context)) }.getOrNull() ?: JSONObject()
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val id = o.optString("id"); if (id.isEmpty()) continue
            val every = o.optInt("every", 14)
            val last = o.optLong("last")
            if (now < last + every * DAY || now < o.optLong("snooze")) continue
            if (now - sent.optLong(id) < 3 * DAY) continue
            if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.call_channel), NotificationManager.IMPORTANCE_DEFAULT))
            val name = o.optString("name").ifBlank { "?" }
            val days = ((now - last) / DAY).toInt()
            val code = (id.hashCode() and 0x0FFFFFFF) + 11
            val open = Intent(context, MainActivity::class.java).apply {
                putExtra(MainActivity.EXTRA_PERSON, id)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
            val b = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(context.getString(R.string.call_title, name))
                .setContentText(context.getString(R.string.call_text, days))
                .setContentIntent(PendingIntent.getActivity(context, code, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .setColor(ContextCompat.getColor(context, R.color.accent))
            val phone = o.optString("phone").filter { it.isDigit() || it == '+' }
            if (phone.length >= 6) {
                b.addAction(0, context.getString(R.string.action_call),
                    PendingIntent.getActivity(context, code + 1, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            try { NotificationManagerCompat.from(context).notify(code, b.build()); sent.put(id, now) } catch (e: SecurityException) { }
        }
        Prefs.setCallsNotified(context, sent.toString())
    }
}

/** Läuft einmal am Tag im Hintergrund, auch wenn die App geschlossen ist */
class BirthdayWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (Prefs.remindersOn(c) && Prefs.exec(c) != null) {
            val list = withContext(Dispatchers.IO) { Birthdays.load(c) }
            Birthdays.notifyDue(c, list, LocalDate.now(), force = false)
            CallReminder.check(c)
        }
        FamilyWidget.refresh(c)
        // Falls Android die Prüfung stark verschoben hat: wieder auf die gewünschte Uhrzeit legen
        if (Birthdays.minutesOff(c) > 120) Birthdays.schedule(c, update = true)
        return Result.success()
    }
}

/** Entschlüsselt Einträge des Stammbaums (gleiches Format wie in der Oberfläche: v<Version>:<IV+Daten, base64url>) */
object EntryCrypto {
    private val ENTRY = Regex("""^v(\d+):([A-Za-z0-9_-]+)$""")

    fun unb64u(s: String): ByteArray = java.util.Base64.getUrlDecoder().decode(s)

    fun version(d: String): Int = ENTRY.find(d)?.groupValues?.get(1)?.toIntOrNull() ?: -1

    fun decrypt(keys: Map<Int, ByteArray>, id: String, d: String): String? {
        val m = ENTRY.find(d) ?: return null
        val key = keys[m.groupValues[1].toInt()] ?: return null
        return try {
            val data = unb64u(m.groupValues[2])
            if (data.size < 28) return null
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, data, 0, 12))
            c.updateAAD("e|$id".toByteArray(Charsets.UTF_8))
            String(c.doFinal(data, 12, data.size - 12), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }
}

/** Anfrage an die Google-Web-App. Google antwortet mit einer Weiterleitung, die hier von Hand gefolgt wird. */
object Net {
    fun post(url: String, body: String): String {
        var conn = URL(url).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.connectTimeout = 20_000
        conn.readTimeout = 90_000
        conn.setRequestProperty("Content-Type", "text/plain;charset=utf-8")
        conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        var code = conn.responseCode
        if (code in 300..399) {
            val next = URL(conn.url, conn.getHeaderField("Location") ?: throw IOException("Weiterleitung ohne Ziel"))
            conn.disconnect()
            conn = next.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = true
            conn.connectTimeout = 20_000
            conn.readTimeout = 90_000
            code = conn.responseCode
        }
        if (code !in 200..299) { conn.disconnect(); throw IOException("HTTP $code") }
        return conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.also { conn.disconnect() }
    }
}

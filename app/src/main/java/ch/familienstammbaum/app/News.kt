package ch.familienstammbaum.app

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.util.concurrent.TimeUnit

/**
 * Mitteilungen, wenn andere etwas Neues in den Stammbaum stellen. Ohne fremden Mitteilungsdienst:
 * Die App fragt selbst beim eigenen Google-Skript nach, bekommt nur verschlüsselte Einträge
 * und entschlüsselt sie hier auf dem Handy.
 *
 * Mit dem Google-Code ab Version 4 kommen nur die Einträge seit der letzten Abfrage (wenig Daten, etwa alle 30 Minuten).
 * Mit älterem Code lädt die App alles, darum dann nur im WLAN und höchstens alle 3 Stunden.
 */
object News {
    const val CHANNEL = "neues"
    private const val WORK = "neues-pruefen"
    private const val BASE_ID = 7300
    private const val HOUR = 3_600_000L
    private const val FULL_EVERY = 3 * HOUR
    private const val PROBE_EVERY = 24 * HOUR

    /* ---------- Einstellungen ---------- */
    fun cfg(c: Context): JSONObject {
        val o = runCatching { JSONObject(Prefs.newsCfg(c).ifBlank { "{}" }) }.getOrNull() ?: JSONObject()
        if (!o.has("on")) o.put("on", true)
        val cats = o.optJSONObject("cats") ?: JSONObject()
        NewsCore.CATS.forEach { if (!cats.has(it)) cats.put(it, true) }
        o.put("cats", cats)
        val st = state(c)
        o.put("mode", st.optString("mode")).put("last", st.optLong("last"))
        return o
    }
    fun on(c: Context): Boolean = cfg(c).optBoolean("on", true)
    private fun cats(c: Context): Set<String> {
        val o = cfg(c).optJSONObject("cats") ?: return NewsCore.CATS.toSet()
        return NewsCore.CATS.filter { o.optBoolean(it, true) }.toSet()
    }
    fun setCfg(c: Context, json: String) {
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return
        val keep = JSONObject().put("on", o.optBoolean("on", true))
        val cats = JSONObject()
        val src = o.optJSONObject("cats") ?: JSONObject()
        NewsCore.CATS.forEach { cats.put(it, src.optBoolean(it, true)) }
        Prefs.setNewsCfg(c, keep.put("cats", cats).toString())
        if (!keep.optBoolean("on")) clear(c)
    }

    /* ---------- Zustand (verschlüsselt gespeichert, enthält Namen) ---------- */
    private fun state(c: Context): JSONObject =
        Prefs.newsState(c)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
    private fun saveState(c: Context, st: JSONObject) = Prefs.setNewsState(c, st.toString())

    /** Plant die Abfrage im Hintergrund (etwa alle 30 Minuten, nur mit Internet) */
    fun schedule(c: Context, update: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (!on(c) || Prefs.exec(c) == null || Prefs.newsCtx(c) == null) {
            wm.cancelUniqueWork(WORK)
            return
        }
        val request = PeriodicWorkRequestBuilder<NewsWorker>(30, TimeUnit.MINUTES)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(15, TimeUnit.MINUTES)
            .build()
        val policy = if (update) ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE else ExistingPeriodicWorkPolicy.KEEP
        wm.enqueueUniquePeriodicWork(WORK, policy, request)
    }

    /** Die App zeigt den Stammbaum: alles bis jetzt gilt als gesehen, offene Mitteilungen verschwinden */
    fun seen(c: Context) {
        val st = state(c)
        cancelAll(c, st)
        st.put("mark", System.currentTimeMillis())
        st.remove("lines")
        st.remove("nids")
        saveState(c, st)
    }

    private fun clear(c: Context) {
        val st = state(c)
        cancelAll(c, st)
        st.remove("lines")
        st.remove("nids")
        saveState(c, st)
    }

    private fun cancelAll(c: Context, st: JSONObject) {
        val nids = st.optJSONArray("nids") ?: return
        val nm = NotificationManagerCompat.from(c)
        for (i in 0 until nids.length()) runCatching { nm.cancel(nids.optInt(i)) }
    }

    private fun unmetered(c: Context): Boolean {
        val cm = c.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    private fun call(exec: String, fn: String, vararg args: Any): JSONObject {
        val a = JSONArray()
        args.forEach { a.put(it) }
        return JSONObject(Net.post(exec, JSONObject().put("fn", fn).put("args", a).toString()))
    }

    /** Einmal beim Google-Skript nachschauen. Gibt die Anzahl neuer Meldungen zurück. */
    fun check(c: Context): Int {
        if (!on(c)) return 0
        val sess = Prefs.session(c)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return 0
        val ctx = Prefs.newsCtx(c)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return 0
        val exec = sess.optString("exec")
        val a = sess.optString("a")
        if (exec.isEmpty() || a.isEmpty()) return 0
        val keys = HashMap<Int, ByteArray>()
        sess.optJSONObject("keys")?.let { ko -> ko.keys().forEach { k -> runCatching { keys[k.toInt()] = EntryCrypto.unb64u(ko.getString(k)) } } }
        if (keys.isEmpty()) return 0
        val st = state(c)
        val now = System.currentTimeMillis()
        var rows: JSONArray? = null
        if (st.optString("mode") != "v3" || now - st.optLong("probe") > PROBE_EVERY) {
            val res = call(exec, "apiNews", a, st.optLong("since"))
            if (res.optBoolean("ok")) {
                val r = res.getJSONObject("r")
                val first = st.optLong("since") <= 0L
                st.put("mode", "v4").put("since", r.optLong("now")).put("last", now)
                if (first) { saveState(c, st); return 0 }
                rows = r.optJSONArray("e") ?: JSONArray()
            } else if (res.optString("e").contains("Unbekannte Funktion")) {
                st.put("mode", "v3").put("probe", now)
            } else {
                saveState(c, st)
                return 0
            }
        }
        if (rows == null) {
            if (!unmetered(c) || now - st.optLong("full") < FULL_EVERY) { saveState(c, st); return 0 }
            val res = call(exec, "apiLoad", a)
            val r = if (res.optBoolean("ok")) res.optJSONObject("r") else null
            if (r == null || r.optBoolean("denied")) { saveState(c, st); return 0 }
            st.put("full", now).put("last", now)
            rows = r.optJSONArray("e") ?: JSONArray()
        }
        val list = ArrayList<JSONObject>()
        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue
            val id = row.optString(0)
            val txt = EntryCrypto.decrypt(keys, id, row.optString(1)) ?: continue
            val o = runCatching { JSONObject(txt) }.getOrNull() ?: continue
            o.put("id", id)
            list.add(o)
        }
        val sentArr = st.optJSONArray("sent") ?: JSONArray()
        val sent = LinkedHashSet<String>()
        for (i in 0 until sentArr.length()) sent.add(sentArr.optString(i))
        val sq = sess.optString("lang") == "sq"
        val items = NewsCore.select(list, ctx, cats(c), st.optLong("mark"), sent, sq)
        if (items.isEmpty()) { saveState(c, st); return 0 }

        items.forEach { sent.addAll(it.ids) }
        st.put("sent", JSONArray(sent.toList().takeLast(1500)))
        val lines = st.optJSONObject("lines") ?: JSONObject()
        val nids = st.optJSONArray("nids") ?: JSONArray()
        val trees = ctx.optJSONObject("trees") ?: JSONObject()
        for ((tree, its) in items.groupBy { it.tree }) {
            val old = lines.optJSONArray(tree) ?: JSONArray()
            val all = ArrayList<String>()
            its.forEach { all.add(it.line) }
            for (i in 0 until old.length()) all.add(old.optString(i))
            val keep = all.take(30)
            lines.put(tree, JSONArray(keep))
            val nid = post(c, tree, trees.optString(tree), trees.length() > 1, keep, sq)
            if (nid != 0) {
                var known = false
                for (i in 0 until nids.length()) if (nids.optInt(i) == nid) known = true
                if (!known) nids.put(nid)
            }
        }
        st.put("lines", lines).put("nids", nids)
        saveState(c, st)
        return items.size
    }

    private fun ensureChannel(c: Context, sq: Boolean) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, if (sq) "Të rejat në trung" else "Neues im Stammbaum", NotificationManager.IMPORTANCE_DEFAULT)
        ch.description = if (sq) "Kur të tjerët shtojnë foto, postime, takime ose komentojnë gjërat e tua"
            else "Wenn andere Fotos, Beiträge oder Treffen hinzufügen oder deine Sachen kommentieren"
        nm.createNotificationChannel(ch)
    }

    @SuppressLint("MissingPermission")
    private fun post(c: Context, tree: String, treeName: String, multi: Boolean, lines: List<String>, sq: Boolean): Int {
        if (lines.isEmpty() || !Birthdays.canNotify(c)) return 0
        ensureChannel(c, sq)
        val id = BASE_ID + (tree.hashCode() and 0x3FF)
        val head = if (sq) "E re në trung" else "Neu im Stammbaum"
        val title = if (multi && treeName.isNotBlank()) "$head · $treeName" else head
        val text = if (lines.size > 1) (if (sq) "${lines.size} të reja · ${lines[0]}" else "${lines.size} Neuigkeiten · ${lines[0]}") else lines[0]
        val open = Intent(c, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_NEWS, tree.ifEmpty { "-" })
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(c, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val style: NotificationCompat.Style = if (lines.size > 1) {
            val s = NotificationCompat.InboxStyle()
            lines.take(6).forEach { s.addLine(it) }
            if (lines.size > 6) s.setSummaryText(if (sq) "+${lines.size - 6} të tjera" else "+${lines.size - 6} weitere")
            s
        } else NotificationCompat.BigTextStyle().bigText(lines[0])
        val hour = LocalTime.now().hour
        val quiet = hour >= 22 || hour < 7
        /* Auf dem gesperrten Bildschirm nur «Neu im Stammbaum», ohne Namen und Inhalt */
        val pub = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(head)
            .setContentText(if (sq) "Hape aplikacionin për t'i pa" else "Zum Ansehen die App öffnen")
            .setColor(ContextCompat.getColor(c, R.color.accent))
            .build()
        val b = NotificationCompat.Builder(c, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(style)
            .setNumber(lines.size)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setSilent(quiet)
            .setCategory(NotificationCompat.CATEGORY_SOCIAL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(pub)
            .setColor(ContextCompat.getColor(c, R.color.accent))
        return try {
            NotificationManagerCompat.from(c).notify(id, b.build())
            id
        } catch (e: SecurityException) {
            0
        }
    }
}

/** Läuft etwa alle 30 Minuten im Hintergrund. Ist die App gerade offen, sieht man das Neue ja schon. */
class NewsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        if (MainActivity.inFront || !News.on(c)) return Result.success()
        withContext(Dispatchers.IO) { runCatching { News.check(c) } }
        return Result.success()
    }
}

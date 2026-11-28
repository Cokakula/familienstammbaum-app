package ch.familienstammbaum.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.View
import android.widget.RemoteViews
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate

/**
 * Widget «Foto des Tages»: jeden Tag ein anderes Familienfoto, an Geburtstagen eines vom Geburtstagskind.
 * Die Oberfläche wählt die Fotos für die nächsten Tage und gibt kleine Bilder mit. Sie liegen mit dem
 * Android-Schlüsselspeicher verschlüsselt im Speicher der App (keine Verbindung nötig).
 */
class PhotoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = build(context)
        for (id in ids) manager.updateAppWidget(id, views)
    }

    companion object {
        private val DAY = Regex("""^\d{4}-\d{2}-\d{2}$""")
        private fun dir(c: Context): File = File(c.filesDir, "fotowidget").apply { mkdirs() }

        /** Von der Oberfläche: {title, days: [{day, id, cap, sub, img (Base64-JPEG)}]} */
        fun save(c: Context, json: String) {
            try {
                val o = JSONObject(json)
                val days = o.optJSONArray("days") ?: return
                val index = JSONArray()
                val keep = HashSet<String>()
                for (i in 0 until days.length()) {
                    val d = days.optJSONObject(i) ?: continue
                    val day = d.optString("day")
                    if (!DAY.matches(day)) continue
                    val bytes = runCatching { Base64.decode(d.optString("img"), Base64.DEFAULT) }.getOrNull() ?: continue
                    if (bytes.isEmpty() || bytes.size > 600_000) continue
                    val sealed = Vault.sealBytes(bytes) ?: continue
                    val name = "$day.bin"
                    File(dir(c), name).writeBytes(sealed)
                    keep.add(name)
                    index.put(JSONObject().put("day", day).put("id", d.optString("id")).put("cap", d.optString("cap")).put("sub", d.optString("sub")).put("file", name))
                }
                if (index.length() == 0) return
                dir(c).listFiles()?.forEach { f -> if (f.name !in keep) f.delete() }
                Prefs.setPhotoDays(c, JSONObject().put("title", o.optString("title")).put("days", index).toString())
                refresh(c)
            } catch (e: Exception) { /* Widget ist nicht so wichtig wie die App */ }
        }

        /** Beim Abmelden: Bilder löschen */
        fun clear(c: Context) {
            try { dir(c).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
            refresh(c)
        }

        fun refresh(context: Context) {
            try {
                val m = AppWidgetManager.getInstance(context) ?: return
                val ids = m.getAppWidgetIds(ComponentName(context, PhotoWidget::class.java))
                if (ids.isEmpty()) return
                val views = build(context)
                for (id in ids) m.updateAppWidget(id, views)
            } catch (e: Exception) { }
        }

        /** Foto für heute; wurde die App länger nicht geöffnet, reihum durch die vorhandenen */
        private fun pick(c: Context): Pair<JSONObject, Boolean>? {
            val idx = Prefs.photoDays(c)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
            val days = idx.optJSONArray("days") ?: return null
            if (days.length() == 0) return null
            val today = LocalDate.now().toString()
            for (i in 0 until days.length()) {
                val d = days.optJSONObject(i) ?: continue
                if (d.optString("day") == today) return Pair(d, true)
            }
            val n = days.length()
            val k = (LocalDate.now().toEpochDay() % n).toInt().let { if (it < 0) it + n else it }
            val d = days.optJSONObject(k) ?: return null
            return Pair(d, false)
        }

        private fun bitmap(c: Context, d: JSONObject): Bitmap? = try {
            val f = File(dir(c), d.optString("file"))
            if (!f.exists()) null else Vault.openBytes(f.readBytes())?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        } catch (e: Exception) { null }

        fun build(c: Context): RemoteViews {
            val v = RemoteViews(c.packageName, R.layout.widget_photo)
            val fl = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            val open = Intent(c, MainActivity::class.java).apply { flags = fl }
            v.setOnClickPendingIntent(R.id.wp_root, PendingIntent.getActivity(c, 0x72A0, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val p = pick(c)
            val bmp = p?.let { bitmap(c, it.first) }
            if (p == null || bmp == null) {
                v.setViewVisibility(R.id.wp_img, View.GONE)
                v.setViewVisibility(R.id.wp_shade, View.GONE)
                v.setViewVisibility(R.id.wp_text, View.GONE)
                v.setTextViewText(R.id.wp_empty, Lang.ctx(c).getString(R.string.wp_empty))
                v.setViewVisibility(R.id.wp_empty, View.VISIBLE)
                return v
            }
            val (d, fresh) = p
            v.setViewVisibility(R.id.wp_empty, View.GONE)
            v.setViewVisibility(R.id.wp_img, View.VISIBLE)
            v.setImageViewBitmap(R.id.wp_img, bmp)
            /* Ein altes «Heute vor 20 Jahren» stimmt an einem anderen Tag nicht mehr: dann nur Namen und Jahr */
            val cap = if (fresh) d.optString("cap") else d.optString("sub").ifBlank { "" }
            val sub = if (fresh) d.optString("sub") else ""
            if (cap.isBlank() && sub.isBlank()) {
                v.setViewVisibility(R.id.wp_shade, View.GONE)
                v.setViewVisibility(R.id.wp_text, View.GONE)
            } else {
                v.setViewVisibility(R.id.wp_shade, View.VISIBLE)
                v.setViewVisibility(R.id.wp_text, View.VISIBLE)
                v.setTextViewText(R.id.wp_cap, cap.ifBlank { sub })
                v.setTextViewText(R.id.wp_sub, if (cap.isBlank()) "" else sub)
                v.setViewVisibility(R.id.wp_sub, if (cap.isBlank() || sub.isBlank()) View.GONE else View.VISIBLE)
            }
            val id = d.optString("id")
            if (id.isNotBlank()) {
                val show = Intent(c, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_PHOTO, id)
                    flags = fl
                }
                v.setOnClickPendingIntent(R.id.wp_root, PendingIntent.getActivity(c, 0x72A1, show, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            }
            return v
        }
    }
}

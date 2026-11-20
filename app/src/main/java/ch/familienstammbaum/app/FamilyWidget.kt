package ch.familienstammbaum.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import org.json.JSONObject
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Widget für den Startbildschirm: die nächsten Geburtstage und Familientreffen.
 * Die Angaben kommen aus der zuletzt geladenen, auf dem Handy verschlüsselten Liste (keine Verbindung nötig).
 */
class FamilyWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, build(context))
    }

    companion object {
        private val ROWS = intArrayOf(R.id.w_row1, R.id.w_row2, R.id.w_row3, R.id.w_row4)
        private val DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")

        /** Nach neuen Daten aus der App oder dem täglichen Abgleich aufrufen */
        fun refresh(context: Context) {
            try {
                val m = AppWidgetManager.getInstance(context) ?: return
                val ids = m.getAppWidgetIds(ComponentName(context, FamilyWidget::class.java))
                if (ids.isEmpty()) return
                val views = build(context)
                for (id in ids) m.updateAppWidget(id, views)
            } catch (e: Exception) { /* Widget ist nicht so wichtig wie die App */ }
        }

        private data class Item(val date: LocalDate, val text: String, val person: String?, val sort: String)

        private fun dayLabel(context: Context, today: LocalDate, d: LocalDate): String {
            val n = ChronoUnit.DAYS.between(today, d).toInt()
            return when {
                n <= 0 -> context.getString(R.string.widget_today)
                n == 1 -> context.getString(R.string.widget_tomorrow)
                n < 7 -> context.getString(R.string.widget_in_days, n)
                else -> Birthdays.formatDate(d)
            }
        }

        private fun items(context: Context, json: String, today: LocalDate): List<Item> {
            val out = ArrayList<Item>()
            for (b in Birthdays.parse(json)) {
                var y = today.year
                var d = b.dayIn(y).let { (m, dd) -> LocalDate.of(y, m, dd) }
                if (d.isBefore(today)) { y++; d = b.dayIn(y).let { (m, dd) -> LocalDate.of(y, m, dd) } }
                if (ChronoUnit.DAYS.between(today, d) > 60) continue
                val age = b.ageOn(d)
                val label = dayLabel(context, today, d)
                val text = if (age > 0) context.getString(R.string.widget_bday_age, label, b.firstName, age) else context.getString(R.string.widget_bday, label, b.firstName)
                out.add(Item(d, "🎂 $text", b.id, "0"))
            }
            val ev = JSONObject(json).optJSONArray("events")
            if (ev != null) {
                for (i in 0 until ev.length()) {
                    val o = ev.optJSONObject(i) ?: continue
                    val m = DATE.find(o.optString("date")) ?: continue
                    val d = runCatching { LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) }.getOrNull() ?: continue
                    if (d.isBefore(today) || ChronoUnit.DAYS.between(today, d) > 90) continue
                    val title = listOf(o.optString("title"), o.optString("time")).filter { it.isNotBlank() }.joinToString(" · ")
                    out.add(Item(d, "📅 " + context.getString(R.string.widget_event, dayLabel(context, today, d), title.ifBlank { "Familientreffen" }), null, "1" + o.optString("time")))
                }
            }
            return out.sortedWith(compareBy({ it.date }, { it.sort }))
        }

        fun build(context: Context): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_family)
            val open = Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP }
            v.setOnClickPendingIntent(R.id.w_root, PendingIntent.getActivity(context, 0x71D6, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            val json = Prefs.cache(context)
            val list = if (json == null) null else runCatching { items(context, json, LocalDate.now()) }.getOrNull()
            ROWS.forEach { v.setViewVisibility(it, View.GONE) }
            if (list.isNullOrEmpty()) {
                v.setTextViewText(R.id.w_empty, context.getString(if (json == null) R.string.widget_empty else R.string.widget_none))
                v.setViewVisibility(R.id.w_empty, View.VISIBLE)
                return v
            }
            v.setViewVisibility(R.id.w_empty, View.GONE)
            list.take(ROWS.size).forEachIndexed { i, item ->
                v.setTextViewText(ROWS[i], item.text)
                v.setViewVisibility(ROWS[i], View.VISIBLE)
                if (item.person != null) {
                    val p = Intent(context, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_PERSON, item.person)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                    }
                    v.setOnClickPendingIntent(ROWS[i], PendingIntent.getActivity(context, 0x71E0 + i, p, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                } else {
                    v.setOnClickPendingIntent(ROWS[i], PendingIntent.getActivity(context, 0x71E0 + i, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            return v
        }
    }
}

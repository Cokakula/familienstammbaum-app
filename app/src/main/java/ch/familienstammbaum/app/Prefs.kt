package ch.familienstammbaum.app

import android.content.Context
import android.net.Uri

/** Einstellungen der App, auf dem Handy gespeichert */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("stammbaum", Context.MODE_PRIVATE)

    fun link(c: Context): String? = sp(c).getString("link", null)
    fun setLink(c: Context, value: String) {
        sp(c).edit().putString("link", value).remove("birthdays").remove("lastNotified").apply()
    }

    fun remindersOn(c: Context): Boolean = sp(c).getBoolean("reminders", true)
    fun setRemindersOn(c: Context, value: Boolean) { sp(c).edit().putBoolean("reminders", value).apply() }

    fun dayBefore(c: Context): Boolean = sp(c).getBoolean("dayBefore", true)
    fun setDayBefore(c: Context, value: Boolean) { sp(c).edit().putBoolean("dayBefore", value).apply() }

    fun hour(c: Context): Int = sp(c).getInt("hour", 8)
    fun minute(c: Context): Int = sp(c).getInt("minute", 0)
    fun setTime(c: Context, hour: Int, minute: Int) { sp(c).edit().putInt("hour", hour).putInt("minute", minute).apply() }

    fun cache(c: Context): String? = sp(c).getString("birthdays", null)
    fun setCache(c: Context, value: String) { sp(c).edit().putString("birthdays", value).apply() }

    fun lastNotified(c: Context): String? = sp(c).getString("lastNotified", null)
    fun setLastNotified(c: Context, value: String) { sp(c).edit().putString("lastNotified", value).apply() }
}

/** Hilfen rund um den persönlichen Stammbaum-Link (Google-Web-App-Adresse mit ?k=…) */
object Links {
    private val PATTERN = Regex("""https://script\.google\.com/(?:a/macros/[^/\s]+/|macros/)s/[A-Za-z0-9_-]+/(?:exec|dev)(?:\?[^\s"'<>]*)?""")

    fun extract(text: String): String? = PATTERN.find(text)?.value?.trimEnd('.', ',', ';', ')', '!')

    fun key(link: String): String = Uri.parse(link).getQueryParameter("k").orEmpty()

    fun withParam(link: String, name: String, value: String): String =
        Uri.parse(link).buildUpon().appendQueryParameter(name, value).build().toString()

    fun birthdaysUrl(link: String): String {
        val uri = Uri.parse(link)
        val builder = uri.buildUpon().clearQuery().appendQueryParameter("api", "geburtstage")
        val k = uri.getQueryParameter("k")
        if (!k.isNullOrEmpty()) builder.appendQueryParameter("k", k)
        return builder.build().toString()
    }

    fun masked(link: String): String {
        val k = key(link)
        return if (k.length > 8) link.replace(k, k.take(4) + "…" + k.takeLast(4)) else link
    }
}

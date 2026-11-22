package ch.familienstammbaum.app

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Sprache der Oberfläche, wie in der Web-App gewählt («de», «sq» oder «en»).
 * Englische Texte stehen in values-en; für Deutsch und Kosovarisch gelten die deutschen Texte (Kosovarisch nur, wo eigens vorhanden).
 * Die Sprache des Handys spielt keine Rolle, damit alles zur Wahl in der App passt.
 */
object Lang {
    fun code(c: Context): String = Prefs.uiLang(c)
    fun en(c: Context): Boolean = code(c) == "en"
    fun ctx(c: Context): Context {
        val cfg = Configuration(c.resources.configuration)
        cfg.setLocale(if (en(c)) Locale.ENGLISH else Locale.GERMAN)
        return c.createConfigurationContext(cfg)
    }
}

package ch.familienstammbaum.app

import android.content.Context
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Einstellungen der App, auf dem Handy gespeichert */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("stammbaum", Context.MODE_PRIVATE)

    /** Umstellung von Version 1 (ein einziger Link mit ?k=) */
    fun migrate(c: Context) {
        val p = sp(c)
        val old = p.getString("link", null) ?: return
        val base = Links.base(old)
        val e = p.edit().remove("link").remove("birthdays").remove("lastNotified")
        if (base != null) e.putString("exec", base).putString("pending", old)
        e.apply()
    }

    /** Adresse der Google-Web-App (ohne Geheimnis) */
    fun exec(c: Context): String? = sp(c).getString("exec", null)

    /** Link, den die Oberfläche einmal auswerten soll (Einladung, Notfall-Schlüssel, alter Admin-Link) */
    fun pending(c: Context): String? = sp(c).getString("pending", null)
    fun clearPending(c: Context) { sp(c).edit().remove("pending").apply() }
    fun reset(c: Context): Boolean = sp(c).getBoolean("reset", false)
    fun clearReset(c: Context) { sp(c).edit().remove("reset").apply() }

    /** Neuer Link vom Benutzer: Adresse merken, Link für die Oberfläche bereitlegen */
    fun useLink(c: Context, link: String) {
        val base = Links.base(link) ?: return
        val changed = exec(c) != base
        val e = sp(c).edit().putString("exec", base).putString("pending", link)
        if (changed) e.putBoolean("reset", true).remove("session").remove("birthdays").remove("lastNotified")
        e.apply()
    }

    fun remindersOn(c: Context): Boolean = sp(c).getBoolean("reminders", true)
    fun setRemindersOn(c: Context, value: Boolean) { sp(c).edit().putBoolean("reminders", value).apply() }

    fun dayBefore(c: Context): Boolean = sp(c).getBoolean("dayBefore", true)
    fun setDayBefore(c: Context, value: Boolean) { sp(c).edit().putBoolean("dayBefore", value).apply() }

    fun hour(c: Context): Int = sp(c).getInt("hour", 8)
    fun minute(c: Context): Int = sp(c).getInt("minute", 0)
    fun setTime(c: Context, hour: Int, minute: Int) { sp(c).edit().putInt("hour", hour).putInt("minute", minute).apply() }

    fun lockOn(c: Context): Boolean = sp(c).getBoolean("lock", true)
    fun setLockOn(c: Context, value: Boolean) { sp(c).edit().putBoolean("lock", value).apply() }

    /** Zugang für die Erinnerungen im Hintergrund (mit dem Android-Schlüsselspeicher verschlüsselt) */
    fun session(c: Context): String? = sp(c).getString("session", null)?.let { Vault.open(it) }
    fun setSession(c: Context, json: String) { Vault.seal(json)?.let { sp(c).edit().putString("session", it).apply() } }
    fun clearSession(c: Context) { sp(c).edit().remove("session").remove("birthdays").apply() }

    /** Zuletzt bekannte Geburtstage (ebenfalls verschlüsselt) */
    fun cache(c: Context): String? = sp(c).getString("birthdays", null)?.let { Vault.open(it) }
    fun setCache(c: Context, json: String) { Vault.seal(json)?.let { sp(c).edit().putString("birthdays", it).apply() } }

    /** Umstieg auf die Webversion in der App: einmalig den bisherigen Zugang übernehmen */
    fun webMigrated(c: Context): Boolean = sp(c).getBoolean("webMigrated", false)
    fun setWebMigrated(c: Context) { sp(c).edit().putBoolean("webMigrated", true).apply() }
    fun oldSession(c: Context): String? = sp(c).getString("oldSess", null)?.let { Vault.open(it) }
    fun setOldSession(c: Context, json: String) { Vault.seal(json)?.let { sp(c).edit().putString("oldSess", it).apply() } }
    fun clearOldSession(c: Context) { sp(c).edit().remove("oldSess").apply() }

    fun lastNotified(c: Context): String? = sp(c).getString("lastNotified", null)
    fun setLastNotified(c: Context, value: String) { sp(c).edit().putString("lastNotified", value).apply() }
}

/** Verschlüsselt kleine Texte mit einem Schlüssel, der den Android-Schlüsselspeicher nie verlässt */
object Vault {
    private const val ALIAS = "familienstammbaum"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun seal(text: String): String? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(text.toByteArray(Charsets.UTF_8))
        Base64.encodeToString(c.iv + ct, Base64.NO_WRAP)
    } catch (e: Exception) { null }

    fun open(data: String): String? = try {
        val b = Base64.decode(data, Base64.NO_WRAP)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
        String(c.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
    } catch (e: Exception) { null }
}

/** Hilfen rund um Stammbaum-Links (Google-Web-App-Adresse, Geheimnis hinter dem #) */
object Links {
    private val GAS = Regex("""https://script\.google\.com/(?:a/macros/[^/\s]+/|macros/)s/[A-Za-z0-9_-]+/(?:exec|dev)(?:\?[^\s"'<>#]*)?(?:#[^\s"'<>]*)?""")
    /** Einladungslink der Webversion: https://<name>.github.io/familienstammbaum-app/?g=<Skript-ID>#i=… */
    private val WEB = Regex("""https://[A-Za-z0-9-]+\.github\.io/[^\s"'<>?#]*\?(?:[^\s"'<>#]*&)?g=[^\s"'<>&#]+[^\s"'<>]*""")
    private val SCRIPT_ID = Regex("""^[A-Za-z0-9_-]{10,}$""")

    fun extract(text: String): String? = (GAS.find(text) ?: WEB.find(text))?.value?.trimEnd('.', ',', ';', ')', '!')

    /** Nur die Adresse der Google-Web-App, ohne Parameter und ohne Geheimnis */
    fun base(link: String): String? {
        val l = extract(link) ?: return null
        if (l.startsWith("https://script.google.com/")) return l.substringBefore('#').substringBefore('?')
        val g = try { Uri.parse(l.substringBefore('#')).getQueryParameter("g") } catch (e: Exception) { null } ?: return null
        return when {
            g.startsWith("https://script.google.com/") -> g.substringBefore('#').substringBefore('?')
            SCRIPT_ID.matches(g) -> "https://script.google.com/macros/s/$g/exec"
            else -> null
        }
    }

    fun masked(exec: String): String {
        val m = Regex("""/s/([A-Za-z0-9_-]{8})[A-Za-z0-9_-]*(?=/)""").find(exec) ?: return exec
        return exec.replace(m.value, "/s/" + m.groupValues[1] + "…")
    }

    fun isSecret(link: String): Boolean = link.contains("#i=") || link.contains("#r=")

    fun param(link: String, name: String): String? = try { Uri.parse(link.substringBefore('#')).getQueryParameter(name) } catch (e: Exception) { null }
}

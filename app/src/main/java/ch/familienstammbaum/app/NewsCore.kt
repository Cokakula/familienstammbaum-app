package ch.familienstammbaum.app

import org.json.JSONObject

/**
 * Was ist neu im Stammbaum? Wertet die hier auf dem Handy entschlüsselten Einträge aus (ohne Android-Teile).
 * Gemeldet wird nur, was andere neu angelegt haben. Geschenkideen, Wichteln, Zeitkapseln, Sprachgrüsse
 * und private Einträge kommen nie in eine Mitteilung.
 */
object NewsCore {
    /** Arten, die sich einzeln ein- und ausschalten lassen */
    val CATS = listOf("fotos", "pin", "erz", "treff", "herz")

    data class Item(val tree: String, val cat: String, val at: Long, val ids: List<String>, val line: String)

    private val MILES_EN = mapOf(
        "smile" to "First smile",
        "tooth" to "First tooth",
        "sit" to "Sits up alone",
        "crawl" to "Crawling",
        "steps" to "First steps",
        "word" to "First word",
        "kita" to "First day at nursery",
        "school" to "First day at school",
        "bike" to "Riding a bike",
        "swim" to "Learned to swim",
    )

    private val MILES = mapOf(
        "smile" to Pair("Erstes Lächeln", "Buzëqeshja e parë"),
        "tooth" to Pair("Erster Zahn", "Dhëmbi i parë"),
        "sit" to Pair("Sitzt allein", "Ulet vetë"),
        "crawl" to Pair("Krabbelt", "Zvarritet"),
        "steps" to Pair("Erste Schritte", "Hapat e parë"),
        "word" to Pair("Erstes Wort", "Fjala e parë"),
        "kita" to Pair("Erster Tag im Kindergarten", "Dita e parë në kopsht"),
        "school" to Pair("Erster Schultag", "Dita e parë e shkollës"),
        "bike" to Pair("Velo fahren", "Biçikleta pa ndihmë"),
        "swim" to Pair("Schwimmen gelernt", "Mësoi me notu"),
    )

    /** Englisch: «your photo», «a photo with you» … */
    private fun targetEn(code: String): String = when (code) {
        "f!" -> "your photo"
        "f" -> "a photo with you"
        "s!" -> "your memory"
        "s" -> "a memory with you"
        "b!" -> "your post"
        "b" -> "a post with you"
        "k!" -> "your recipe"
        "k" -> "a recipe from you"
        else -> "a milestone"
    }

    /** «dein Foto», «ein Foto mit dir» … (Nominativ, Akkusativ, Albanisch) je nach Art und ob es von mir ist */
    private fun target(code: String): Triple<String, String, String> = when (code) {
        "f!" -> Triple("dein Foto", "dein Foto", "foton tënde")
        "f" -> Triple("ein Foto mit dir", "ein Foto mit dir", "një foto me ty")
        "s!" -> Triple("deine Erinnerung", "deine Erinnerung", "kujtimin tënd")
        "s" -> Triple("eine Erinnerung mit dir", "eine Erinnerung mit dir", "një kujtim me ty")
        "b!" -> Triple("dein Beitrag", "deinen Beitrag", "postimin tënd")
        "b" -> Triple("ein Beitrag mit dir", "einen Beitrag mit dir", "një postim me ty")
        "k!" -> Triple("dein Rezept", "dein Rezept", "recetën tënde")
        "k" -> Triple("ein Rezept von dir", "ein Rezept von dir", "një recetë prej teje")
        else -> Triple("ein Meilenstein", "einen Meilenstein", "një moment të veçantë")
    }

    private fun cut(s: String, n: Int): String {
        val t = s.replace(Regex("""\s+"""), " ").trim()
        return if (t.length <= n) t else t.substring(0, n).trimEnd() + " …"
    }

    private fun map(o: JSONObject?): Map<String, String> {
        val m = HashMap<String, String>()
        o?.keys()?.forEach { k -> m[k] = o.optString(k) }
        return m
    }

    private val MON_EN = listOf("Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
    private fun date(d: String, en: Boolean = false): String {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})""").find(d) ?: return d
        if (en) return "${m.groupValues[3].toInt()} ${MON_EN.getOrElse(m.groupValues[2].toInt() - 1) { "" }} ${m.groupValues[1]}"
        return "${m.groupValues[3].toInt()}.${m.groupValues[2].toInt()}.${m.groupValues[1]}"
    }

    private fun personIds(o: JSONObject): Set<String> {
        val s = HashSet<String>()
        o.optJSONArray("personIds")?.let { a -> for (i in 0 until a.length()) s.add(a.optString(i)) }
        o.optJSONArray("tags")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("pid")?.let { s.add(it) } }
        return s
    }

    /**
     * entries: entschlüsselte Einträge (mit «id» und Kürzel «k»), ctx: was die Oberfläche über «mich» mitgibt,
     * mark: bis hierhin hat man alles in der App gesehen, sent: schon gemeldete Einträge.
     */
    fun select(entries: List<JSONObject>, ctx: JSONObject, cats: Set<String>, mark: Long, sent: Set<String>, sq: Boolean, en: Boolean = false): List<Item> {
        val cred = ctx.optString("cred")
        val myName = ctx.optString("name")
        val meIn = map(ctx.optJSONObject("me"))
        val mine = map(ctx.optJSONObject("mine"))
        val names = HashMap(map(ctx.optJSONObject("names")))
        for (o in entries) if (o.optString("k") == "p") {
            val n = o.optString("nickname").ifBlank { o.optString("firstName") }.ifBlank { o.optString("lastName") }
            if (n.isNotBlank()) names[o.optString("id")] = n
        }
        fun t(de: String, al: String, e: String) = if (en) e else if (sq) al else de
        fun byOther(o: JSONObject): Boolean {
            val cc = o.optString("createdByCred")
            if (cc.isNotEmpty()) return cc != cred
            val cb = o.optString("createdBy")
            return cb.isNotEmpty() && cb != myName
        }

        val fresh = entries.filter { o ->
            val id = o.optString("id")
            id.isNotEmpty() && id !in sent && !o.has("_del") && !o.has("pc") &&
                o.optLong("createdAt") > mark && byOther(o)
        }
        val out = ArrayList<Item>()
        fun who(o: JSONObject) = o.optString("createdBy").ifBlank { o.optString("updatedBy") }.ifBlank { t("Jemand", "Dikush", "Someone") }
        fun add(o: JSONObject, cat: String, line: String) {
            if (cat in cats) out.add(Item(o.optString("treeId"), cat, o.optLong("createdAt"), listOf(o.optString("id")), line))
        }

        // Fotos und neue Personen: pro Person zusammenfassen
        if ("fotos" in cats) {
            fresh.filter { it.optString("k") == "f" }.groupBy { Pair(it.optString("treeId"), who(it)) }.forEach { (key, list) ->
                val (tree, w) = key
                val me = meIn[tree]
                val withMe = if (me.isNullOrEmpty()) 0 else list.count { me in personIds(it) }
                val n = list.size
                val line = when {
                    n == 1 && withMe == 1 -> t("$w hat ein Foto mit dir hochgeladen", "$w ka ngarku një foto me ty", "$w uploaded a photo with you")
                    n == 1 -> t("$w hat ein Foto hochgeladen", "$w ka ngarku një foto", "$w uploaded a photo")
                    withMe > 0 -> t("$w hat $n Fotos hochgeladen, $withMe davon mit dir", "$w ka ngarku $n foto, $withMe me ty", "$w uploaded $n photos, $withMe of them with you")
                    else -> t("$w hat $n Fotos hochgeladen", "$w ka ngarku $n foto", "$w uploaded $n photos")
                }
                out.add(Item(tree, "fotos", list.maxOf { it.optLong("createdAt") }, list.map { it.optString("id") }, line))
            }
            fresh.filter { it.optString("k") == "p" }.groupBy { Pair(it.optString("treeId"), who(it)) }.forEach { (key, list) ->
                val (tree, w) = key
                val line = if (list.size == 1) {
                    val p = list[0]
                    val name = listOf(p.optString("firstName"), p.optString("lastName")).filter { it.isNotBlank() }.joinToString(" ")
                    if (name.isBlank()) t("$w hat eine Person hinzugefügt", "$w ka shtu një person", "$w added a person") else t("$w hat $name hinzugefügt", "$w e ka shtu $name", "$w added $name")
                } else t("$w hat ${list.size} Personen hinzugefügt", "$w ka shtu ${list.size} persona", "$w added ${list.size} people")
                out.add(Item(tree, "fotos", list.maxOf { it.optLong("createdAt") }, list.map { it.optString("id") }, line))
            }
        }

        for (o in fresh) {
            val w = who(o)
            when (o.optString("k")) {
                "b" -> {
                    val text = cut(o.optString("text"), 80)
                    add(o, "pin", if (text.isNotEmpty()) t("$w auf der Pinnwand: «$text»", "$w në mur: «$text»", "$w on the board: «$text»")
                        else t("$w hat ein Foto auf die Pinnwand gestellt", "$w ka postu një foto në mur", "$w put a photo on the board"))
                }
                "s" -> {
                    if (o.optString("greet").isNotEmpty() || o.optString("openAt").isNotEmpty()) continue
                    val title = cut(o.optString("title").ifBlank { o.optString("text") }, 60)
                    add(o, "erz", if (title.isNotEmpty()) t("$w hat eine Erinnerung erzählt: «$title»", "$w ka tregu një kujtim: «$title»", "$w shared a memory: «$title»")
                        else t("$w hat eine Sprachaufnahme geteilt", "$w ka nda një incizim", "$w shared a voice recording"))
                }
                "m" -> {
                    val type = o.optString("mt")
                    val pair = MILES[type]
                    val mile = if (pair != null) (if (en) MILES_EN[type] ?: pair.first else if (sq) pair.second else pair.first)
                        else o.optString("title").ifBlank { t("Eigener Meilenstein", "Moment i veçantë", "Own milestone") }
                    val child = names[o.optString("pid")].orEmpty()
                    add(o, "erz", if (child.isNotEmpty()) t("Neuer Meilenstein – $child: $mile", "Moment i ri – $child: $mile", "New milestone – $child: $mile")
                        else t("Neuer Meilenstein: $mile", "Moment i ri: $mile", "New milestone: $mile"))
                }
                "k" -> {
                    val title = cut(o.optString("title"), 60)
                    add(o, "erz", if (title.isNotEmpty()) t("$w hat ein Rezept geteilt: «$title»", "$w ka nda një recetë: «$title»", "$w shared a recipe: «$title»")
                        else t("$w hat ein Rezept geteilt", "$w ka nda një recetë", "$w shared a recipe"))
                }
                "d" -> {
                    val title = cut(o.optString("title"), 60)
                    add(o, "erz", if (title.isNotEmpty()) t("$w hat ein Dokument hinzugefügt: «$title»", "$w ka shtu një dokument: «$title»", "$w added a document: «$title»")
                        else t("$w hat ein Dokument hinzugefügt", "$w ka shtu një dokument", "$w added a document"))
                }
                "e" -> {
                    val title = cut(o.optString("title"), 60).ifEmpty { t("Familientreffen", "Takim familjar", "Family gathering") }
                    val d = o.optString("date")
                    val when_ = if (d.isNotEmpty()) ", " + date(d, en) else ""
                    add(o, "treff", t("Neues Familientreffen: «$title»$when_ (von $w)", "Takim i ri familjar: «$title»$when_ (nga $w)", "New family gathering: «$title»$when_ (by $w)"))
                }
                "c" -> {
                    val code = mine[o.optString("on")] ?: continue
                    val (_, acc, al) = target(code)
                    val tEn = targetEn(code)
                    val text = cut(o.optString("text"), 60)
                    add(o, "herz", t("$w hat $acc kommentiert: «$text»", "$w komentoi $al: «$text»", "$w commented on $tEn: «$text»"))
                }
                "x" -> {
                    if (o.optString("to") != meIn[o.optString("treeId")]) continue
                    add(o, "herz", t("$w hat dir einen Morgengruss geschickt 🌅", "$w të ka dërgu një përshëndetje 🌅", "$w sent you a good-morning greeting 🌅"))
                }
                "h" -> {
                    val code = mine[o.optString("on")] ?: continue
                    val (nom, acc, al) = target(code)
                    val tEn = targetEn(code)
                    val em = o.optString("em")
                    add(o, "herz", when {
                        em.isNotEmpty() && em != "❤️" -> t("$w hat auf $acc reagiert $em", "$w reagoi në $al $em", "$w reacted to $tEn $em")
                        code.endsWith("!") -> t("$w gefällt $nom", "$w e pëlqen $al", "$w likes $tEn")
                        else -> t("$w gefällt $nom", "$w pëlqen $al", "$w likes $tEn")
                    })
                }
            }
        }
        return out.sortedByDescending { it.at }
    }
}

package ch.familienstammbaum.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.os.SystemClock
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import androidx.core.content.FileProvider
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.exifinterface.media.ExifInterface
import androidx.webkit.WebViewAssetLoader
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * Zeigt die Stammbaum-Oberfläche. Sie steckt in der App selbst (nicht bei Google) und entschlüsselt die Daten
 * hier auf dem Handy. Google liefert nur verschlüsselte Daten.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON = "person"
        const val EXTRA_BACKUP = "backup"
        const val EXTRA_NEWS = "news"
        /** Ist die App gerade im Vordergrund? Dann braucht es keine Mitteilung über Neues. */
        @Volatile var inFront = false
        const val ACTION_SETUP = "ch.familienstammbaum.app.SETUP"
        /** Die App zeigt die Webversion an: immer derselbe Stand wie im Browser */
        private const val WEB_HOST = "cokakula.github.io"
        private const val START_URL = "https://$WEB_HOST/familienstammbaum-app/"
        /** Nur einmal nach dem Update: bisherigen Zugang aus der früher eingebauten Oberfläche übernehmen */
        private const val ASSET_HOST = "appassets.androidplatform.net"
        private const val MIGRATE_URL = "https://$ASSET_HOST/assets/migrate.html"
        private const val RELOCK_AFTER_MS = 60_000L
        private val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    private lateinit var web: WebView
    private lateinit var setup: View
    private lateinit var lockView: View
    private lateinit var offline: View
    @Volatile private var pageHost: String = ""
    private lateinit var linkInput: EditText
    private lateinit var linkError: TextView
    private lateinit var assets: WebViewAssetLoader
    private lateinit var prompt: BiometricPrompt

    @Volatile private var pendingPerson: String? = null
    @Volatile private var pendingAudio: String? = null
    @Volatile private var pendingBackup = false
    @Volatile private var pendingNews: String? = null
    private var pendingWebPerm: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    @Volatile private var pickedMeta: String = "[]"
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingPick: Intent? = null
    private var askedMediaLocation = false
    private var saveFile: File? = null
    private var saveOut: FileOutputStream? = null
    private var saveName = "Datei"
    private var saveMime = "application/octet-stream"
    private var internalNav = false
    private var unlocked = false
    private var backgroundAt = 0L
    /* Vorlesen mit der Android-Sprachausgabe */
    private var tts: TextToSpeech? = null
    @Volatile private var ttsReady = false
    @Volatile private var ttsId = ""
    @Volatile private var ttsLast = ""

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        if (callback == null) return@registerForActivityResult
        val data = result.data
        val uris: Array<Uri>? = if (result.resultCode == RESULT_OK && data != null) {
            val clip = data.clipData
            if (clip != null && clip.itemCount > 0) Array(clip.itemCount) { i -> clip.getItemAt(i).uri } else data.data?.let { arrayOf(it) }
        } else null
        if (uris == null) { callback.onReceiveValue(null); return@registerForActivityResult }
        Thread {
            pickedMeta = readPhotoMeta(uris)
            runOnUiThread { callback.onReceiveValue(uris) }
        }.start()
    }

    private val askMediaLocation = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        pendingPick?.let { launchPicker(it) }
        pendingPick = null
    }

    private val createDoc = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val target = result.data?.data
        val src = saveFile
        if (result.resultCode != RESULT_OK || target == null || src == null) { finishSave(false); return@registerForActivityResult }
        Thread {
            val ok = try {
                contentResolver.openOutputStream(target)?.use { out -> src.inputStream().use { it.copyTo(out) } } != null
            } catch (e: Exception) { false }
            runOnUiThread { finishSave(ok) }
        }.start()
    }

    /* Mikrofon für Sprachaufnahmen (Erinnerungen) */
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        val req = pendingWebPerm
        pendingWebPerm = null
        if (req != null) {
            if (granted) req.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE)) else { req.deny(); Toast.makeText(this, Lang.ctx(this).getString(R.string.mic_denied), Toast.LENGTH_LONG).show() }
        }
    }

    /* Standort, z.B. für den Ort eines Grabes */
    private val askLocation = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { res ->
        val g = pendingGeo
        pendingGeo = null
        if (g != null) g.second.invoke(g.first, res.values.any { it }, false)
    }

    private var notifyThenSettings = false
    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted && notifyThenSettings) notificationSettings()
        else if (!granted) Toast.makeText(this, Lang.ctx(this).getString(R.string.notifications_denied), Toast.LENGTH_LONG).show()
        notifyThenSettings = false
        web.evaluateJavascript("window.__fsNotify && window.__fsNotify($granted)", null)
    }

    override fun attachBaseContext(newBase: android.content.Context) { super.attachBaseContext(Lang.ctx(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.migrate(this)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        web = findViewById(R.id.web)
        setup = findViewById(R.id.setup)
        lockView = findViewById(R.id.lock)
        offline = findViewById(R.id.offline)
        findViewById<Button>(R.id.retryButton).setOnClickListener { offline.visibility = View.GONE; web.visibility = View.VISIBLE; web.reload() }
        linkInput = findViewById(R.id.linkInput)
        linkError = findViewById(R.id.linkError)
        assets = WebViewAssetLoader.Builder().setDomain(ASSET_HOST).addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                unlocked = true
                lockView.visibility = View.GONE
            }
        })

        setupWeb()
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteLink() }
        findViewById<Button>(R.id.openButton).setOnClickListener { saveLink(linkInput.text.toString()) }
        findViewById<Button>(R.id.unlockButton).setOnClickListener { authenticate() }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (setup.visibility == View.VISIBLE && Prefs.exec(this@MainActivity) != null) { showWeb(false); return }
                if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else finish()
            }
        })

        Birthdays.ensureChannel(this)
        Birthdays.schedule(this, update = false)
        News.schedule(this, update = false)
        FamilyWidget.refresh(this)
        handleIntent(intent, initial = true)
        initTts()
    }

    override fun onDestroy() {
        try { tts?.stop(); tts?.shutdown() } catch (e: Exception) { }
        tts = null
        super.onDestroy()
    }

    /* ---------- Vorlesen ---------- */
    private fun initTts() {
        try {
            tts = TextToSpeech(this) { status ->
                ttsReady = status == TextToSpeech.SUCCESS
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { if (utteranceId != null && utteranceId == ttsLast) ttsDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { ttsDone() }
                    override fun onError(utteranceId: String?, errorCode: Int) { ttsDone() }
                })
            }
        } catch (e: Exception) { tts = null }
    }

    private fun ttsDone() {
        val id = ttsId
        ttsId = ""
        runOnUiThread { web.evaluateJavascript("window.__ttsDone && window.__ttsDone(${JSONObject.quote(id)})", null) }
    }

    /** Langen Text an Satzenden in Stücke teilen, die die Sprachausgabe annimmt */
    private fun ttsChunks(text: String, max: Int): List<String> {
        val out = ArrayList<String>()
        var rest = text.trim()
        while (rest.length > max) {
            var cut = rest.lastIndexOfAny(charArrayOf('.', '!', '?', '\n'), max)
            if (cut < max / 3) cut = rest.lastIndexOf(' ', max)
            if (cut < max / 3) cut = max - 1
            out.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) out.add(rest)
        return out
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false)
    }

    override fun onResume() {
        super.onResume()
        inFront = true
        if (::web.isInitialized) web.evaluateJavascript("window.__fsResume && window.__fsResume()", null)
    }

    override fun onPause() {
        inFront = false
        super.onPause()
    }

    override fun onStart() {
        super.onStart()
        val away = backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt > RELOCK_AFTER_MS
        if (internalNav) { internalNav = false; if (!away) { lockView.visibility = View.GONE; return } }
        if (lockWanted() && (!unlocked || away)) {
            unlocked = false
            lockView.visibility = View.VISIBLE
            lockView.post { authenticate() }
        } else {
            lockView.visibility = View.GONE
        }
    }

    override fun onStop() {
        super.onStop()
        if (isChangingConfigurations) return
        backgroundAt = SystemClock.elapsedRealtime()
        if (lockWanted() && !internalNav) lockView.visibility = View.VISIBLE   // Vorschau in der App-Übersicht verdecken
    }

    /* ---------- App-Sperre ---------- */
    private fun lockWanted(): Boolean =
        Prefs.lockOn(this) && Prefs.exec(this) != null && BiometricManager.from(this).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!lockWanted()) { unlocked = true; lockView.visibility = View.GONE; return }
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(Lang.ctx(this).getString(R.string.lock_title))
            .setSubtitle(Lang.ctx(this).getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(AUTH)
            .build()
        try { prompt.authenticate(info) } catch (e: Exception) { /* Knopf «Entsperren» bleibt sichtbar */ }
    }

    /* ---------- Links und Einrichtung ---------- */
    private fun handleIntent(intent: Intent?, initial: Boolean) {
        when (intent?.action) {
            ACTION_SETUP -> { showSetup(); return }
            Intent.ACTION_SEND -> {
                if (isAudioShare(intent)) { takeSharedAudio(intent, initial); return }
                val link = Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                if (link != null) { if (!openPersonLink(link)) useLink(link); return }
                Toast.makeText(this, Lang.ctx(this).getString(R.string.no_link_in_text), Toast.LENGTH_LONG).show()
            }
            Intent.ACTION_VIEW -> {
                val link = intent.dataString?.let { Links.extract(it) }
                if (link != null) { if (!openPersonLink(link)) useLink(link); return }
                if (initial || web.visibility != View.VISIBLE) { if (Prefs.exec(this) != null) showWeb(initial) else showSetup() }
                return
            }
        }
        val person = intent?.getStringExtra(EXTRA_PERSON)
        if (person != null) { pendingPerson = person; showWeb(true); return }
        val news = intent?.getStringExtra(EXTRA_NEWS)
        if (news != null) { intent?.removeExtra(EXTRA_NEWS); pendingNews = news; showWeb(true); return }
        if (intent?.getBooleanExtra(EXTRA_BACKUP, false) == true) { intent?.removeExtra(EXTRA_BACKUP); pendingBackup = true; showWeb(true); return }
        if (initial || web.visibility != View.VISIBLE) {
            if (Prefs.exec(this) != null) showWeb(initial) else showSetup()
        }
    }

    private fun showWeb(reload: Boolean) {
        setup.visibility = View.GONE
        offline.visibility = View.GONE
        web.visibility = View.VISIBLE
        if (!reload && web.url != null) return
        if (!Prefs.webMigrated(this)) {
            web.loadUrl(MIGRATE_URL)
            web.postDelayed({ if (!Prefs.webMigrated(this)) { Prefs.setWebMigrated(this); web.loadUrl(START_URL) } }, 4000)
        } else web.loadUrl(START_URL)
    }

    private fun showSetup() {
        web.visibility = View.GONE
        offline.visibility = View.GONE
        setup.visibility = View.VISIBLE
        linkError.visibility = View.GONE
        linkInput.setText("")
    }

    /* Link zu einer Person: nur im eigenen Stammbaum öffnen, den Zugang nie wechseln */
    private fun openPersonLink(link: String): Boolean {
        val pid = Links.person(link) ?: return false
        val exec = Prefs.exec(this)
        if (exec == null) { Toast.makeText(this, Lang.ctx(this).getString(R.string.person_link_setup), Toast.LENGTH_LONG).show(); showSetup(); return true }
        val base = Links.base(link)
        if (base != null && base != exec) { Toast.makeText(this, Lang.ctx(this).getString(R.string.person_link_other), Toast.LENGTH_LONG).show(); if (web.visibility != View.VISIBLE) showWeb(true); return true }
        pendingPerson = pid; showWeb(true); return true
    }

    /* Sprachnachricht geteilt: Datei lesen und an die Webversion geben */
    private fun isAudioShare(intent: Intent): Boolean {
        val t = intent.type.orEmpty()
        if (t.startsWith("audio/") || t == "application/ogg") return true
        if (!t.startsWith("text/") && intent.hasExtra(Intent.EXTRA_STREAM)) return true
        return false
    }
    private fun takeSharedAudio(intent: Intent, initial: Boolean) {
        @Suppress("DEPRECATION")
        val uri: Uri? = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else intent.getParcelableExtra(Intent.EXTRA_STREAM)
        if (uri == null) { Toast.makeText(this, Lang.ctx(this).getString(R.string.audio_failed), Toast.LENGTH_LONG).show(); return }
        var name = "Sprachnachricht.opus"
        try { contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0)?.let { if (it.isNotBlank()) name = it } } } catch (e: Exception) {}
        val bytes = try {
            contentResolver.openInputStream(uri)?.use { inp ->
                val out = java.io.ByteArrayOutputStream(); val buf = ByteArray(65536); var total = 0
                while (true) { val n = inp.read(buf); if (n < 0) break; total += n; if (total > 16 * 1024 * 1024) return@use null; out.write(buf, 0, n) }
                out.toByteArray()
            }
        } catch (e: Exception) { null }
        if (bytes == null) { Toast.makeText(this, Lang.ctx(this).getString(R.string.audio_failed), Toast.LENGTH_LONG).show(); return }
        val mime = intent.type ?: contentResolver.getType(uri) ?: "audio/ogg"
        pendingAudio = JSONObject().put("name", name).put("mime", mime).put("b64", Base64.encodeToString(bytes, Base64.NO_WRAP)).toString()
        if (Prefs.exec(this) == null) { showSetup(); return }
        if (!initial && web.visibility == View.VISIBLE && web.url != null) {
            web.evaluateJavascript("window.__fsAudio && window.__fsAudio()", null)
        } else showWeb(true)
    }

    private fun useLink(link: String) {
        Prefs.useLink(this, link)
        unlocked = true
        showWeb(true)
        askForNotifications()
        Birthdays.schedule(this, update = true)
    }

    private fun saveLink(text: String) {
        val link = Links.extract(text)
        if (link == null) { linkError.visibility = View.VISIBLE; return }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(linkInput.windowToken, 0)
        useLink(link)
    }

    private fun pasteLink() {
        val clip = getSystemService(ClipboardManager::class.java)?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        linkInput.setText(text)
        if (Links.extract(text) != null) saveLink(text) else linkError.visibility = View.VISIBLE
    }

    /** Mitteilungen erlauben: zuerst die Frage von Android, sonst die Einstellungen der App */
    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) { notifyThenSettings = true; askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS); return }
        notificationSettings()
    }

    private fun notificationSettings() {
        val i = Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
        try { internalNav = true; startActivity(i) } catch (e: ActivityNotFoundException) { internalNav = false }
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && Prefs.remindersOn(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    /** Telefon, WhatsApp, E-Mail, Karten usw. öffnen die passende App */
    private fun openOutside(uri: Uri) {
        if (uri.scheme.equals("stammbaum-app", ignoreCase = true)) { openSettings(); return }
        val intent = if (uri.scheme.equals("tel", ignoreCase = true)) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_VIEW, uri)
        try { internalNav = true; startActivity(intent) } catch (e: ActivityNotFoundException) {
            internalNav = false
            Toast.makeText(this, Lang.ctx(this).getString(R.string.no_app_for_link), Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() { internalNav = true; startActivity(Intent(this, SettingsActivity::class.java)) }

    /* ---------- Fotos auswählen (mit Aufnahmeort) ---------- */
    private fun launchPicker(intent: Intent) {
        try { internalNav = true; pickFiles.launch(Intent.createChooser(intent, Lang.ctx(this).getString(R.string.choose_photos))) }
        catch (e: ActivityNotFoundException) { internalNav = false; fileCallback?.onReceiveValue(null); fileCallback = null }
    }

    /** Liest Name, Grösse, GPS-Standort und Aufnahmedatum. Der Standort geht nur verschlüsselt zu Google. */
    private fun readPhotoMeta(uris: Array<Uri>): String {
        val out = JSONArray()
        for (uri in uris) {
            val o = JSONObject()
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                    if (c.moveToFirst()) {
                        o.put("name", c.getString(0) ?: "")
                        if (!c.isNull(1)) o.put("size", c.getLong(1))
                    }
                }
                val exif = (originalUri(uri)?.let { u -> try { contentResolver.openInputStream(u) } catch (e: Exception) { null } } ?: contentResolver.openInputStream(uri))
                    ?.use { ExifInterface(it) }
                exif?.latLong?.let { if (it.size == 2 && (it[0] != 0.0 || it[1] != 0.0)) { o.put("lat", it[0]); o.put("lon", it[1]) } }
                val dt = exif?.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: exif?.getAttribute(ExifInterface.TAG_DATETIME)
                Regex("""^(\d{4}):(\d{2}):(\d{2})""").find(dt.orEmpty())?.let { m ->
                    if (m.groupValues[1] != "0000") o.put("date", "${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}")
                }
            } catch (e: Exception) { /* ohne Angaben weiter */ }
            out.put(o)
        }
        return out.toString()
    }

    /** Ohne diese Freigabe entfernt Android den Standort aus den Fotos */
    private fun originalUri(uri: Uri): Uri? {
        if (Build.VERSION.SDK_INT < 29) return null
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
        return try {
            var u = uri
            if (DocumentsContract.isDocumentUri(this, u)) u = MediaStore.getMediaUri(this, u) ?: return null
            if (u.authority != MediaStore.AUTHORITY) null else MediaStore.setRequireOriginal(u)
        } catch (e: Exception) { null }
    }

    /* ---------- Dateien speichern (Sicherung, GEDCOM) ---------- */
    private fun finishSave(ok: Boolean) {
        saveFile?.delete()
        saveFile = null
        web.evaluateJavascript("window.__fsSaved && window.__fsSaved($ok)", null)
    }

    private fun respond(id: Int, ok: Boolean, text: String) {
        runOnUiThread { web.evaluateJavascript("window.__fsHttp && window.__fsHttp($id, $ok, ${JSONObject.quote(text)})", null) }
    }

    /** Schnittstelle für die Oberfläche (nur für die eigene, in der App mitgelieferte Seite) */
    inner class Bridge {
        private fun ours() = pageHost == WEB_HOST

        /** Einmalig: Zugang aus der früher eingebauten Oberfläche (eigener Speicher) an die Webversion übergeben */
        @JavascriptInterface fun migrateSession(sess: String?) {
            if (pageHost != ASSET_HOST) return
            if (!sess.isNullOrBlank()) Prefs.setOldSession(this@MainActivity, sess)
            Prefs.setWebMigrated(this@MainActivity)
            runOnUiThread { web.loadUrl(START_URL) }
        }
        @JavascriptInterface fun sessionImported() { if (ours()) Prefs.clearOldSession(this@MainActivity) }
        @JavascriptInterface fun getConfig(): String {
            val ctx = this@MainActivity
            if (!ours()) return "{}"
            val o = JSONObject().put("exec", Prefs.exec(ctx) ?: "")
            Prefs.oldSession(ctx)?.let { o.put("sess", it) }
            Prefs.pending(ctx)?.let { o.put("link", it) }
            pendingPerson?.let { o.put("person", it) }
            pendingPerson = null
            if (pendingAudio != null) o.put("audio", true)
            if (pendingBackup) { o.put("backup", true); pendingBackup = false }
            pendingNews?.let { o.put("news", it) }
            pendingNews = null
            if (Prefs.reset(ctx)) { o.put("reset", true); Prefs.clearReset(ctx) }
            return o.toString()
        }
        @JavascriptInterface fun sharedAudio(): String { if (!ours()) return ""; val a = pendingAudio ?: return ""; pendingAudio = null; return a }
        @JavascriptInterface fun linkUsed() { if (!ours()) return; Prefs.clearPending(this@MainActivity) }
        @JavascriptInterface fun saveSession(json: String) { if (!ours()) return;
            Prefs.setSession(this@MainActivity, json)
            runOnUiThread { Birthdays.schedule(this@MainActivity, update = false) }
        }
        /* Mitteilungen bei Neuem */
        @JavascriptInterface fun newsCfg(): String = if (ours()) News.cfg(this@MainActivity).toString() else "{}"
        @JavascriptInterface fun setNewsCfg(json: String) { if (!ours()) return
            News.setCfg(this@MainActivity, json)
            runOnUiThread { News.schedule(this@MainActivity, update = true) }
        }
        @JavascriptInterface fun saveNewsCtx(json: String) { if (!ours()) return
            Prefs.setNewsCtx(this@MainActivity, json)
            News.seen(this@MainActivity)
            runOnUiThread { News.schedule(this@MainActivity, update = false) }
        }
        @JavascriptInterface fun notifyOk(): Boolean = ours() && Birthdays.canNotify(this@MainActivity)
        @JavascriptInterface fun openNotifySettings() { if (!ours()) return; runOnUiThread { allowNotifications() } }
        @JavascriptInterface fun clearSession() { if (!ours()) return; Prefs.clearSession(this@MainActivity) }
        @JavascriptInterface fun saveBirthdays(json: String) { if (!ours()) return; Prefs.setCache(this@MainActivity, json); FamilyWidget.refresh(this@MainActivity) }
        /* Vorlesen: "ok", "nolang" (keine Stimme für die Sprache) oder "no" */
        @JavascriptInterface fun speak(id: String, text: String, lang: String): String {
            if (!ours()) return "no"
            val t = tts ?: return "no"
            if (!ttsReady) return "no"
            val tries = if (lang == "sq") listOf(Locale("sq", "XK"), Locale("sq", "AL"), Locale("sq")) else if (lang == "en") listOf(Locale.UK, Locale.US, Locale.ENGLISH) else listOf(Locale("de", "CH"), Locale.GERMANY, Locale.GERMAN)
            val loc = tries.firstOrNull { t.isLanguageAvailable(it) >= TextToSpeech.LANG_AVAILABLE } ?: return "nolang"
            t.setLanguage(loc)
            t.setSpeechRate(0.92f)
            val parts = ttsChunks(text, TextToSpeech.getMaxSpeechInputLength() - 20)
            if (parts.isEmpty()) return "no"
            ttsId = id
            ttsLast = "$id#${parts.size - 1}"
            parts.forEachIndexed { i, p -> t.speak(p, if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, "$id#$i") }
            return "ok"
        }
        @JavascriptInterface fun stopSpeak() { if (!ours()) return; ttsId = ""; ttsLast = ""; try { tts?.stop() } catch (e: Exception) { } }
        @JavascriptInterface fun http(id: Int, url: String, body: String) {
            /* eigener Stammbaum, oder ein Familienlink zu einem anderen Stammbaum (nur Google-Apps-Script-Adressen) */
            if (!ours() || (url != Prefs.exec(this@MainActivity) && !Regex("^https://script\\.google\\.com/macros/s/[A-Za-z0-9_-]{20,120}/exec$").matches(url))) { respond(id, false, "Ungültige Adresse"); return }
            Thread { try { respond(id, true, Net.post(url, body)) } catch (e: Exception) { respond(id, false, e.message ?: "Fehler") } }.start()
        }
        @JavascriptInterface fun httpAny(): Boolean = true
        @JavascriptInterface fun shareText(text: String) { if (!ours()) return;
            runOnUiThread {
                internalNav = true
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }
        /* Startsymbol wechseln: Baum, Kosovo, Herz oder Tradita */
        @JavascriptInterface fun setIcon(name: String): Boolean {
            if (!ours()) return false
            val all = listOf("Baum", "Kosovo", "Herz", "Tradita")
            val pick = all.firstOrNull { it.equals(name, ignoreCase = true) } ?: return false
            return try {
                val pm = packageManager
                pm.setComponentEnabledSetting(android.content.ComponentName(this@MainActivity, "ch.familienstammbaum.app.Icon$pick"), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                all.filter { it != pick }.forEach { pm.setComponentEnabledSetting(android.content.ComponentName(this@MainActivity, "ch.familienstammbaum.app.Icon$it"), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP) }
                true
            } catch (e: Exception) { false }
        }
        /* Die App kennt die Auswahl der Geburtstags-Erinnerungen (Feld «bdn» in der Sitzung) */
        @JavascriptInterface fun bdnSupported(): Boolean = true
        /* Installierte Version, damit die Oberfläche auf ein Update hinweisen kann */
        @JavascriptInterface fun appVersion(): String = AppUpdate.installed(this@MainActivity)
        @JavascriptInterface fun iconName(): String {
            if (!ours()) return ""
            return try {
                val pm = packageManager
                listOf("Baum", "Kosovo", "Herz", "Tradita").firstOrNull {
                    val st = pm.getComponentEnabledSetting(android.content.ComponentName(this@MainActivity, "ch.familienstammbaum.app.Icon$it"))
                    st == PackageManager.COMPONENT_ENABLED_STATE_ENABLED || (st == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT && it == "Baum")
                } ?: "Baum"
            } catch (e: Exception) { "" }
        }
        @JavascriptInterface fun openSettings() { if (!ours()) return; runOnUiThread { this@MainActivity.openSettings() } }
        @JavascriptInterface fun openSetup() { if (!ours()) return; runOnUiThread { showSetup() } }
        @JavascriptInterface fun pickedMeta(): String = if (ours()) pickedMeta else "[]"
        @JavascriptInterface fun saveBegin(name: String, mime: String) { if (!ours()) return;
            try { saveOut?.close() } catch (e: Exception) { }
            val f = File(cacheDir, "speichern.tmp")
            saveFile = f
            saveOut = FileOutputStream(f)
            saveName = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "Datei" }
            saveMime = mime.ifBlank { "application/octet-stream" }
        }
        @JavascriptInterface fun saveChunk(b64: String) { if (!ours()) return; saveOut?.write(Base64.decode(b64, Base64.DEFAULT)) }
        @JavascriptInterface fun saveEnd() { if (!ours()) return;
            try { saveOut?.close() } catch (e: Exception) { }
            saveOut = null
            runOnUiThread {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(saveMime).putExtra(Intent.EXTRA_TITLE, saveName)
                try { internalNav = true; createDoc.launch(i) } catch (e: ActivityNotFoundException) { internalNav = false; finishSave(false) }
            }
        }
        /* Datei teilen, z.B. eine Glückwunschkarte per WhatsApp. Liegt nur kurz im App-Cache. */
        @JavascriptInterface fun shareEnd(text: String) { if (!ours()) return;
            try { saveOut?.close() } catch (e: Exception) { }
            saveOut = null
            val src = saveFile ?: return
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "teilen").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val f = File(dir, saveName)
                    src.renameTo(f)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.dateien", f)
                    val i = Intent(Intent.ACTION_SEND).setType(saveMime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    if (text.isNotBlank()) i.putExtra(Intent.EXTRA_TEXT, text)
                    i.clipData = ClipData.newRawUri(saveName, uri)
                    internalNav = true
                    startActivity(Intent.createChooser(i, null))
                } catch (e: Exception) {
                    internalNav = false
                    Toast.makeText(this@MainActivity, Lang.ctx(this@MainActivity).getString(R.string.no_viewer), Toast.LENGTH_LONG).show()
                }
            }
        }
        /* Datei mit einer passenden App ansehen (z.B. PDF-Betrachter). Liegt nur kurz im App-Cache. */
        @JavascriptInterface fun viewEnd() { if (!ours()) return;
            try { saveOut?.close() } catch (e: Exception) { }
            saveOut = null
            val src = saveFile ?: return
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "ansehen").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                    val f = File(dir, saveName)
                    src.renameTo(f)
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.dateien", f)
                    val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, saveMime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    internalNav = true
                    startActivity(Intent.createChooser(i, saveName))
                } catch (e: Exception) {
                    internalNav = false
                    Toast.makeText(this@MainActivity, Lang.ctx(this@MainActivity).getString(R.string.no_viewer), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.setGeolocationEnabled(true)
        s.mediaPlaybackRequiresUserGesture = false
        s.userAgentString = s.userAgentString + " FamilienstammbaumApp/2"
        web.addJavascriptInterface(Bridge(), "FSApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                if (request.url.host == ASSET_HOST) assets.shouldInterceptRequest(request.url) else null

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                pageHost = Uri.parse(url ?: "").host ?: ""
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                pageHost = Uri.parse(url ?: "").host ?: ""
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame && request.url.host == WEB_HOST) {
                    web.visibility = View.GONE
                    offline.visibility = View.VISIBLE
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (uri.host == WEB_HOST && uri.path.orEmpty().startsWith("/familienstammbaum-app")) return false
                if (uri.host == ASSET_HOST) return false
                if (scheme == "blob" || scheme == "data" || scheme == "about" || scheme == "javascript") return false
                openOutside(uri)
                return true
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val accept = fileChooserParams.acceptTypes.orEmpty().map { it.trim() }
                val mimes = accept.filter { it.contains('/') }
                val images = mimes.isNotEmpty() && mimes.all { it.startsWith("image/") }
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = when {
                        accept.any { it.contains("zip") || it.contains("ged") } -> "*/*"
                        mimes.size == 1 -> mimes[0]
                        images -> "image/*"
                        else -> "*/*"
                    }
                    if (mimes.size > 1 && !accept.any { it.contains("zip") || it.contains("ged") }) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                pickedMeta = "[]"
                if (images && Build.VERSION.SDK_INT >= 29 && !askedMediaLocation &&
                    ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED
                ) {
                    askedMediaLocation = true
                    pendingPick = pick
                    internalNav = true
                    askMediaLocation.launch(Manifest.permission.ACCESS_MEDIA_LOCATION)
                } else {
                    launchPicker(pick)
                }
                return true
            }

            /* Mikrofon nur für die eigene Webseite, nach Rückfrage bei Android */
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    val audio = PermissionRequest.RESOURCE_AUDIO_CAPTURE
                    if (request.origin.host != WEB_HOST || !request.resources.contains(audio)) { request.deny(); return@runOnUiThread }
                    if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        request.grant(arrayOf(audio))
                    } else {
                        pendingWebPerm?.deny()
                        pendingWebPerm = request
                        askMic.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            }

            override fun onPermissionRequestCanceled(request: PermissionRequest) {
                if (pendingWebPerm == request) pendingWebPerm = null
            }

            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) {
                if (Uri.parse(origin).host != WEB_HOST) { callback.invoke(origin, false, false); return }
                val fine = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                val coarse = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (fine || coarse) { callback.invoke(origin, true, false); return }
                pendingGeo?.let { it.second.invoke(it.first, false, false) }
                pendingGeo = origin to callback
                askLocation.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            }

            /* Links mit target="_blank" (Telefon, WhatsApp, E-Mail, Karte) gehen an andere Apps */
            override fun onCreateWindow(view: WebView, isDialog: Boolean, isUserGesture: Boolean, resultMsg: Message): Boolean {
                val hit = view.hitTestResult
                val href = hit.extra
                if (hit.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && href != null && href.contains(':')) {
                    openOutside(Uri.parse(href))
                    return false
                }
                val popup = WebView(this@MainActivity)
                popup.webViewClient = object : WebViewClient() {
                    private var done = false
                    private fun handOff(v: WebView, uri: Uri?) {
                        if (done || uri == null) return
                        done = true
                        openOutside(uri)
                        v.post { v.destroy() }
                    }
                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean { handOff(v, request.url); return true }
                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (url != null && url != "about:blank") { v.stopLoading(); handOff(v, Uri.parse(url)) }
                    }
                }
                (resultMsg.obj as WebView.WebViewTransport).webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}

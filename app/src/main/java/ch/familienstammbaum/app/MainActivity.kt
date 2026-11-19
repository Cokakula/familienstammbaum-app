package ch.familienstammbaum.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
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
import android.util.Base64
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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

/**
 * Zeigt die Stammbaum-Oberfläche. Sie steckt in der App selbst (nicht bei Google) und entschlüsselt die Daten
 * hier auf dem Handy. Google liefert nur verschlüsselte Daten.
 */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON = "person"
        const val ACTION_SETUP = "ch.familienstammbaum.app.SETUP"
        private const val HOST = "appassets.androidplatform.net"
        private const val START_URL = "https://$HOST/assets/www/index.html"
        private const val RELOCK_AFTER_MS = 60_000L
        private val AUTH = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    }

    private lateinit var web: WebView
    private lateinit var setup: View
    private lateinit var lockView: View
    private lateinit var linkInput: EditText
    private lateinit var linkError: TextView
    private lateinit var assets: WebViewAssetLoader
    private lateinit var prompt: BiometricPrompt

    @Volatile private var pendingPerson: String? = null
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

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
    }

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
        linkInput = findViewById(R.id.linkInput)
        linkError = findViewById(R.id.linkError)
        assets = WebViewAssetLoader.Builder().setDomain(HOST).addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this)).build()
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
        handleIntent(intent, initial = true)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false)
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
            .setTitle(getString(R.string.lock_title))
            .setSubtitle(getString(R.string.lock_subtitle))
            .setAllowedAuthenticators(AUTH)
            .build()
        try { prompt.authenticate(info) } catch (e: Exception) { /* Knopf «Entsperren» bleibt sichtbar */ }
    }

    /* ---------- Links und Einrichtung ---------- */
    private fun handleIntent(intent: Intent?, initial: Boolean) {
        when (intent?.action) {
            ACTION_SETUP -> { showSetup(); return }
            Intent.ACTION_SEND -> {
                val link = Links.extract(intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty())
                if (link != null) { useLink(link); return }
                Toast.makeText(this, R.string.no_link_in_text, Toast.LENGTH_LONG).show()
            }
            Intent.ACTION_VIEW -> {
                val link = intent.dataString?.let { Links.extract(it) }
                if (link != null) { useLink(link); return }
            }
        }
        val person = intent?.getStringExtra(EXTRA_PERSON)
        if (person != null) { pendingPerson = person; showWeb(true); return }
        if (initial || web.visibility != View.VISIBLE) {
            if (Prefs.exec(this) != null) showWeb(initial) else showSetup()
        }
    }

    private fun showWeb(reload: Boolean) {
        setup.visibility = View.GONE
        web.visibility = View.VISIBLE
        if (reload || web.url == null) web.loadUrl(START_URL)
    }

    private fun showSetup() {
        web.visibility = View.GONE
        setup.visibility = View.VISIBLE
        linkError.visibility = View.GONE
        linkInput.setText("")
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
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openSettings() { internalNav = true; startActivity(Intent(this, SettingsActivity::class.java)) }

    /* ---------- Fotos auswählen (mit Aufnahmeort) ---------- */
    private fun launchPicker(intent: Intent) {
        try { internalNav = true; pickFiles.launch(Intent.createChooser(intent, getString(R.string.choose_photos))) }
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
        @JavascriptInterface fun getConfig(): String {
            val ctx = this@MainActivity
            val o = JSONObject().put("exec", Prefs.exec(ctx) ?: "")
            Prefs.pending(ctx)?.let { o.put("link", it) }
            pendingPerson?.let { o.put("person", it) }
            pendingPerson = null
            if (Prefs.reset(ctx)) { o.put("reset", true); Prefs.clearReset(ctx) }
            return o.toString()
        }
        @JavascriptInterface fun linkUsed() { Prefs.clearPending(this@MainActivity) }
        @JavascriptInterface fun saveSession(json: String) {
            Prefs.setSession(this@MainActivity, json)
            runOnUiThread { Birthdays.schedule(this@MainActivity, update = false) }
        }
        @JavascriptInterface fun clearSession() { Prefs.clearSession(this@MainActivity) }
        @JavascriptInterface fun saveBirthdays(json: String) { Prefs.setCache(this@MainActivity, json) }
        @JavascriptInterface fun http(id: Int, url: String, body: String) {
            if (url != Prefs.exec(this@MainActivity)) { respond(id, false, "Ungültige Adresse"); return }
            Thread { try { respond(id, true, Net.post(url, body)) } catch (e: Exception) { respond(id, false, e.message ?: "Fehler") } }.start()
        }
        @JavascriptInterface fun shareText(text: String) {
            runOnUiThread {
                internalNav = true
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
        }
        @JavascriptInterface fun openSettings() { runOnUiThread { this@MainActivity.openSettings() } }
        @JavascriptInterface fun openSetup() { runOnUiThread { showSetup() } }
        @JavascriptInterface fun pickedMeta(): String = pickedMeta
        @JavascriptInterface fun saveBegin(name: String, mime: String) {
            try { saveOut?.close() } catch (e: Exception) { }
            val f = File(cacheDir, "speichern.tmp")
            saveFile = f
            saveOut = FileOutputStream(f)
            saveName = name.replace(Regex("""[\\/:*?"<>|]"""), "_").ifBlank { "Datei" }
            saveMime = mime.ifBlank { "application/octet-stream" }
        }
        @JavascriptInterface fun saveChunk(b64: String) { saveOut?.write(Base64.decode(b64, Base64.DEFAULT)) }
        @JavascriptInterface fun saveEnd() {
            try { saveOut?.close() } catch (e: Exception) { }
            saveOut = null
            runOnUiThread {
                val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(saveMime).putExtra(Intent.EXTRA_TITLE, saveName)
                try { internalNav = true; createDoc.launch(i) } catch (e: ActivityNotFoundException) { internalNav = false; finishSave(false) }
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
        s.userAgentString = s.userAgentString + " FamilienstammbaumApp/2"
        web.addJavascriptInterface(Bridge(), "FSApp")

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assets.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val scheme = uri.scheme?.lowercase().orEmpty()
                if (uri.host == HOST) return false
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
                        accept.any { it.contains("zip") } -> "*/*"
                        mimes.size == 1 -> mimes[0]
                        images -> "image/*"
                        else -> "*/*"
                    }
                    if (mimes.size > 1 && !accept.any { it.contains("zip") }) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
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

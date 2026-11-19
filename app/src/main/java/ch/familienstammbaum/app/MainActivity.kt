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
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Zeigt den Google-Stammbaum im Vollbild und kümmert sich um Fotos, Links und die Zurück-Geste */
class MainActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PERSON = "person"
        const val ACTION_SETUP = "ch.familienstammbaum.app.SETUP"
    }

    private lateinit var web: WebView
    private lateinit var setup: View
    private lateinit var offline: View
    private lateinit var progress: ProgressBar
    private lateinit var linkInput: EditText
    private lateinit var linkError: TextView
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    private val pickFiles = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val callback = fileCallback
        fileCallback = null
        if (callback != null) {
            val data = result.data
            var uris: Array<Uri>? = null
            if (result.resultCode == RESULT_OK && data != null) {
                val clip = data.clipData
                uris = if (clip != null && clip.itemCount > 0) {
                    Array(clip.itemCount) { i -> clip.getItemAt(i).uri }
                } else {
                    data.data?.let { arrayOf(it) }
                }
            }
            callback.onReceiveValue(uris)
        }
    }

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, R.string.notifications_denied, Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
        offline = findViewById(R.id.offline)
        progress = findViewById(R.id.progress)
        linkInput = findViewById(R.id.linkInput)
        linkError = findViewById(R.id.linkError)

        setupWeb()
        findViewById<Button>(R.id.pasteButton).setOnClickListener { pasteLink() }
        findViewById<Button>(R.id.openButton).setOnClickListener { saveLink(linkInput.text.toString()) }
        findViewById<Button>(R.id.retryButton).setOnClickListener {
            offline.visibility = View.GONE
            web.visibility = View.VISIBLE
            web.reload()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.visibility == View.VISIBLE && web.canGoBack()) web.goBack() else finish()
            }
        })

        Birthdays.ensureChannel(this)
        Birthdays.schedule(this, update = false)

        if (savedInstanceState != null && Prefs.link(this) != null) {
            showWeb()
            if (web.restoreState(savedInstanceState) == null) openApp(null)
        } else {
            handleIntent(intent, initial = true)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, initial = false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        web.saveState(outState)
    }

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
        if (person != null) { openApp(person); return }
        if (initial || web.visibility != View.VISIBLE) {
            if (Prefs.link(this) != null) openApp(null) else showSetup()
        }
    }

    private fun showWeb() {
        setup.visibility = View.GONE
        offline.visibility = View.GONE
        web.visibility = View.VISIBLE
    }

    private fun showSetup() {
        web.visibility = View.GONE
        offline.visibility = View.GONE
        setup.visibility = View.VISIBLE
        linkError.visibility = View.GONE
        linkInput.setText(Prefs.link(this).orEmpty())
    }

    private fun openApp(person: String?) {
        val link = Prefs.link(this)
        if (link == null) { showSetup(); return }
        showWeb()
        web.loadUrl(if (person != null) Links.withParam(link, "p", person) else link)
    }

    private fun useLink(link: String) {
        Prefs.setLink(this, link)
        Toast.makeText(this, R.string.link_saved, Toast.LENGTH_SHORT).show()
        openApp(null)
        askForNotifications()
        Birthdays.schedule(this, update = true)
    }

    private fun saveLink(text: String) {
        val link = Links.extract(text)
        if (link == null) {
            linkError.visibility = View.VISIBLE
            return
        }
        getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(linkInput.windowToken, 0)
        useLink(link)
    }

    private fun pasteLink() {
        val cm = getSystemService(ClipboardManager::class.java)
        val clip = cm?.primaryClip
        val text = if (clip != null && clip.itemCount > 0) clip.getItemAt(0).coerceToText(this).toString() else ""
        linkInput.setText(text)
        if (Links.extract(text) != null) saveLink(text) else linkError.visibility = View.VISIBLE
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && Prefs.remindersOn(this) &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    /** Links zu Telefon, WhatsApp, Google Maps usw. öffnen die passende App */
    private fun openOutside(uri: Uri) {
        if (uri.scheme.equals("stammbaum-app", ignoreCase = true)) { openSettings(); return }
        val intent = if (uri.scheme.equals("tel", ignoreCase = true)) Intent(Intent.ACTION_DIAL, uri) else Intent(Intent.ACTION_VIEW, uri)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.no_app_for_link, Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleUrl(uri: Uri, mainFrame: Boolean): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme == "stammbaum-app") { openSettings(); return true }
        if (scheme == "about" || scheme == "blob" || scheme == "data" || scheme == "javascript") return false
        if (scheme != "http" && scheme != "https") { openOutside(uri); return true }
        if (!mainFrame) return false
        val host = uri.host?.lowercase().orEmpty()
        val inApp = host == "script.google.com" || host.endsWith(".googleusercontent.com") || host == "accounts.google.com"
        if (inApp) return false
        openOutside(uri)
        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWeb() {
        val s = web.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        s.setSupportMultipleWindows(true)
        s.javaScriptCanOpenWindowsAutomatically = false
        s.allowFileAccess = false
        s.userAgentString = s.userAgentString + " FamilienstammbaumApp/1"
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true)

        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handleUrl(request.url, request.isForMainFrame)

            override fun onPageFinished(view: WebView, url: String?) {
                progress.visibility = View.GONE
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    web.visibility = View.GONE
                    offline.visibility = View.VISIBLE
                }
            }
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: WebChromeClient.FileChooserParams,
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = filePathCallback
                val mimes = fileChooserParams.acceptTypes.orEmpty().map { it.trim() }.filter { it.contains('/') }
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = when {
                        mimes.size == 1 -> mimes[0]
                        mimes.isNotEmpty() && mimes.all { it.startsWith("image/") } -> "image/*"
                        else -> "*/*"
                    }
                    if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
                    putExtra(Intent.EXTRA_ALLOW_MULTIPLE, fileChooserParams.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE)
                }
                return try {
                    pickFiles.launch(Intent.createChooser(pick, getString(R.string.choose_photos)))
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }

            /* Links mit target="_blank" (Telefon, WhatsApp, Karte, Google-Tabelle) gehen an andere Apps */
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

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        handOff(v, request.url)
                        return true
                    }

                    override fun onPageStarted(v: WebView, url: String?, favicon: Bitmap?) {
                        if (url != null && url != "about:blank") {
                            v.stopLoading()
                            handOff(v, Uri.parse(url))
                        }
                    }
                }
                val transport = resultMsg.obj as WebView.WebViewTransport
                transport.webView = popup
                resultMsg.sendToTarget()
                return true
            }
        }
    }
}

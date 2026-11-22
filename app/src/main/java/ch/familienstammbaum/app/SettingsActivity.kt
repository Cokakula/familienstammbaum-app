package ch.familienstammbaum.app

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.materialswitch.MaterialSwitch
import java.time.LocalDate
import java.util.Locale

/** Einstellungen für die Geburtstags-Erinnerungen und den Stammbaum-Link */
class SettingsActivity : AppCompatActivity() {

    private lateinit var reminders: MaterialSwitch
    private lateinit var dayBefore: MaterialSwitch
    private lateinit var timeRow: View
    private lateinit var timeValue: TextView
    private lateinit var linkValue: TextView
    private lateinit var permissionHint: View
    private lateinit var checkResult: TextView
    private lateinit var lockSwitch: MaterialSwitch
    private lateinit var lockHint: TextView

    private val askNotifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        // Hat Android den Dialog nicht mehr gezeigt, geht es nur noch über die System-Einstellungen
        if (!granted && Build.VERSION.SDK_INT >= 33 && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
            openNotificationSettings()
        }
        refresh()
    }

    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(Lang.ctx(newBase)) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_settings)
        val root = findViewById<View>(R.id.settingsRoot)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        findViewById<MaterialToolbar>(R.id.toolbar).setNavigationOnClickListener { finish() }

        reminders = findViewById(R.id.remindersSwitch)
        dayBefore = findViewById(R.id.dayBeforeSwitch)
        timeRow = findViewById(R.id.timeRow)
        timeValue = findViewById(R.id.timeValue)
        linkValue = findViewById(R.id.linkValue)
        permissionHint = findViewById(R.id.permissionHint)
        checkResult = findViewById(R.id.checkResult)
        lockSwitch = findViewById(R.id.lockSwitch)
        lockHint = findViewById(R.id.lockHint)
        lockSwitch.isChecked = Prefs.lockOn(this)
        lockSwitch.setOnCheckedChangeListener { _, on -> Prefs.setLockOn(this, on); refresh() }

        reminders.isChecked = Prefs.remindersOn(this)
        dayBefore.isChecked = Prefs.dayBefore(this)
        reminders.setOnCheckedChangeListener { _, on ->
            Prefs.setRemindersOn(this, on)
            if (on) askForNotifications()
            Birthdays.schedule(this, update = true)
            refresh()
        }
        dayBefore.setOnCheckedChangeListener { _, on -> Prefs.setDayBefore(this, on) }
        timeRow.setOnClickListener {
            TimePickerDialog(this, { _, hour, minute ->
                Prefs.setTime(this, hour, minute)
                Birthdays.schedule(this, update = true)
                refresh()
            }, Prefs.hour(this), Prefs.minute(this), true).show()
        }
        findViewById<Button>(R.id.permissionButton).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                openNotificationSettings()
            }
        }
        findViewById<Button>(R.id.checkButton).setOnClickListener { checkNow() }
        findViewById<Button>(R.id.changeLinkButton).setOnClickListener {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .setAction(MainActivity.ACTION_SETUP)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
            finish()
        }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun openNotificationSettings() {
        startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
    }

    private fun askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun refresh() {
        val on = Prefs.remindersOn(this)
        timeValue.text = if (Lang.en(this)) String.format(Locale.ENGLISH, "%02d:%02d", Prefs.hour(this), Prefs.minute(this)) else String.format(Locale.GERMAN, "%02d:%02d Uhr", Prefs.hour(this), Prefs.minute(this))
        linkValue.text = Prefs.exec(this)?.let { Links.masked(it) } ?: getString(R.string.no_link)
        val canLock = BiometricManager.from(this).canAuthenticate(
            BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
        ) == BiometricManager.BIOMETRIC_SUCCESS
        lockHint.setText(if (canLock) R.string.lock_hint else R.string.lock_hint_none)
        permissionHint.visibility = if (on && !Birthdays.canNotify(this)) View.VISIBLE else View.GONE
        timeRow.isEnabled = on
        timeRow.alpha = if (on) 1f else 0.5f
        dayBefore.isEnabled = on
    }

    private fun checkNow() {
        checkResult.visibility = View.VISIBLE
        checkResult.text = getString(R.string.checking)
        val appContext = applicationContext
        Thread {
            val fresh = Birthdays.fetch(appContext)
            val list = fresh ?: Birthdays.load(appContext)
            val today = LocalDate.now()
            val shown = Birthdays.notifyDue(appContext, list, today, force = true)
            val next = Birthdays.next(list, today)
            val lines = ArrayList<String>()
            if (fresh == null) lines.add(getString(R.string.check_offline))
            lines.add(getString(R.string.check_count, list.size))
            if (next != null) lines.add(getString(R.string.check_next, next.first.name, Birthdays.formatDate(next.second, Lang.en(this))))
            if (shown > 0) lines.add(getString(R.string.check_shown, shown))
            runOnUiThread { checkResult.text = lines.joinToString("\n") }
        }.start()
    }
}

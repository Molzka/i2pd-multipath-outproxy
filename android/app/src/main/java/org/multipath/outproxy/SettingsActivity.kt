package org.multipath.outproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var dns: EditText
    private lateinit var sam: EditText
    private lateinit var mtu: RadioGroup
    private lateinit var connectOnOpen: Switch
    private lateinit var recover: Switch
    private lateinit var metered: Switch
    private var excludedApps = emptySet<String>()
    private var appPicker: AppPickerDialog? = null
    private var confirmation: AlertDialog? = null
    private val listener: (Status) -> Unit = { renderLock() }
    private val active get() = StatusBus.status.phase !in setOf(Phase.STOPPED, Phase.ERROR)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = Settings(this)
        dns = findViewById(R.id.settings_dns)
        sam = findViewById(R.id.settings_sam)
        mtu = findViewById(R.id.settings_mtu)
        connectOnOpen = findViewById(R.id.settings_connect_on_open)
        recover = findViewById(R.id.settings_recover)
        metered = findViewById(R.id.settings_metered)
        loadForm()
        savedInstanceState?.getStringArrayList("excluded_apps")?.let { excludedApps = it.toSet() }
        showAppCount()
        applyInsets()
        findViewById<Button>(R.id.settings_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.settings_save).setOnClickListener { save() }
        findViewById<Button>(R.id.settings_apps).setOnClickListener { pickApps() }
        findViewById<Button>(R.id.settings_system_vpn).setOnClickListener { openSystemVpn() }
        findViewById<Button>(R.id.settings_copy_log).setOnClickListener { copyLog() }
        findViewById<Button>(R.id.settings_clear_log).setOnClickListener { confirmClearLog() }
        findViewById<Button>(R.id.settings_reset).setOnClickListener { confirmReset() }
        findViewById<TextView>(R.id.settings_version).text = getString(
            R.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.I2PD_VERSION,
            Build.VERSION.RELEASE, Build.SUPPORTED_ABIS.firstOrNull().orEmpty(),
        )
        if (Build.VERSION.SDK_INT < 29) {
            findViewById<TextView>(R.id.settings_metered_help).setText(R.string.settings_metered_unavailable)
        }
        renderLock()
        savedInstanceState?.getBundle("app_picker")?.let { state ->
            showAppPicker(state.getStringArrayList("selection")?.toSet() ?: excludedApps, state.getString("query").orEmpty())
        }
    }

    override fun onStart() {
        super.onStart()
        StatusBus.listen(listener)
    }

    override fun onStop() {
        StatusBus.unlisten(listener)
        super.onStop()
    }

    override fun onDestroy() {
        appPicker?.dismiss()
        confirmation?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putStringArrayList("excluded_apps", ArrayList(excludedApps))
        appPicker?.saveState()?.let { outState.putBundle("app_picker", it) }
        super.onSaveInstanceState(outState)
    }

    private fun loadForm() {
        dns.setText(settings.dnsServer)
        sam.setText(settings.samAddress)
        dns.error = null
        sam.error = null
        mtu.check(when (settings.mtu) {
            1280 -> R.id.settings_mtu_1280
            1400 -> R.id.settings_mtu_1400
            else -> R.id.settings_mtu_1500
        })
        connectOnOpen.isChecked = settings.connectOnOpen
        recover.isChecked = settings.autoReconnect
        metered.isChecked = settings.metered
        excludedApps = settings.excludedApps
        showAppCount()
    }

    private fun save() {
        if (active) return
        val dnsValue = dns.text.toString().trim()
        val samValue = sam.text.toString().trim()
        val dnsValid = Settings.isValidDns(dnsValue)
        val samValid = Settings.isValidSamAddress(samValue)
        dns.error = if (dnsValid) null else getString(R.string.settings_dns_error)
        sam.error = if (samValid) null else getString(R.string.settings_sam_error)
        if (!dnsValid || !samValid) {
            val invalid = if (!dnsValid) dns else sam
            invalid.requestFocus()
            invalid.post {
                invalid.requestRectangleOnScreen(Rect(0, 0, invalid.width, invalid.height), true)
            }
            return
        }
        settings.dnsServer = dnsValue
        settings.samAddress = samValue
        settings.mtu = when (mtu.checkedRadioButtonId) {
            R.id.settings_mtu_1280 -> 1280
            R.id.settings_mtu_1400 -> 1400
            else -> 1500
        }
        settings.connectOnOpen = connectOnOpen.isChecked
        settings.autoReconnect = recover.isChecked
        if (Build.VERSION.SDK_INT >= 29) settings.metered = metered.isChecked
        settings.excludedApps = excludedApps
        setResult(RESULT_OK)
        toast(R.string.settings_saved)
        finish()
    }

    private fun pickApps() {
        if (active) return
        showAppPicker(excludedApps)
    }

    private fun showAppPicker(selection: Set<String>, query: String = "") {
        appPicker = AppPickerDialog(this, selection, query, { !active }) { selected ->
            if (!active) {
                excludedApps = selected
                showAppCount()
            }
        }.also { it.show() }
    }

    private fun showAppCount() {
        findViewById<TextView>(R.id.settings_apps_summary).text = if (excludedApps.isEmpty())
            getString(R.string.settings_apps_none)
        else getString(R.string.settings_apps_count, excludedApps.size)
    }

    private fun renderLock() {
        val enabled = !active
        findViewById<View>(R.id.settings_busy).visibility = if (enabled) View.GONE else View.VISIBLE
        listOf<View>(dns, sam, connectOnOpen, recover).forEach { it.isEnabled = enabled }
        for (i in 0 until mtu.childCount) mtu.getChildAt(i).isEnabled = enabled
        metered.isEnabled = enabled && Build.VERSION.SDK_INT >= 29
        listOf(R.id.settings_apps, R.id.settings_save, R.id.settings_reset, R.id.settings_clear_log)
            .forEach { findViewById<View>(it).isEnabled = enabled }
        appPicker?.setEditable(enabled)
        confirmation?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled
    }

    private fun openSystemVpn() {
        try {
            startActivity(Intent(android.provider.Settings.ACTION_VPN_SETTINGS))
        } catch (_: Exception) {
            toast(R.string.settings_vpn_unavailable)
        }
    }

    private fun copyLog() {
        val log = StatusBus.sanitizedLogText()
        if (log.isBlank()) {
            toast(R.string.settings_log_empty)
            return
        }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), log))
        toast(R.string.log_copied)
    }

    private fun confirmClearLog() {
        if (active) return
        confirmation = AlertDialog.Builder(this)
            .setTitle(R.string.settings_clear_title)
            .setMessage(R.string.settings_clear_message)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_clear_log) { _, _ ->
                if (!active) {
                    StatusBus.clearLog()
                    toast(R.string.settings_log_cleared)
                }
            }.show()
    }

    private fun confirmReset() {
        if (active) return
        confirmation = AlertDialog.Builder(this)
            .setTitle(R.string.settings_reset_title)
            .setMessage(R.string.settings_reset_message)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_reset_confirm) { _, _ ->
                if (!active) {
                    settings.resetConnectionDefaults()
                    loadForm()
                    setResult(RESULT_OK)
                    toast(R.string.settings_reset_done)
                }
            }.show()
    }

    private fun applyInsets() {
        val root = findViewById<View>(R.id.settings_root)
        val content = findViewById<View>(R.id.settings_content)
        val actions = findViewById<View>(R.id.settings_actions)
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max)
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        fun updateWidth() {
            val width = root.width - root.paddingLeft - root.paddingRight
            if (width <= 0) return
            val side = maxOf(gutter, (width - maxWidth) / 2)
            content.setPadding(side, content.paddingTop, side, content.paddingBottom)
            actions.setPadding(side, actions.paddingTop, side, actions.paddingBottom)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = if (Build.VERSION.SDK_INT >= 30) {
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            } else {
                @Suppress("DEPRECATION")
                intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            view.setPadding(bars[0], bars[1], bars[2], bars[3])
            updateWidth()
            insets
        }
        root.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateWidth()
        }
        root.requestApplyInsets()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

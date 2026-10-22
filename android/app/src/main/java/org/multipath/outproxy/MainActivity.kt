package org.multipath.outproxy

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.net.Uri
import android.provider.DocumentsContract
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {
    private lateinit var settings: Settings
    private lateinit var bundle: BundleView
    private lateinit var phase: TextView
    private lateinit var detail: TextView
    private lateinit var toggle: Button
    private lateinit var address: EditText
    private lateinit var addressHelp: TextView
    private lateinit var links: RadioGroup
    private lateinit var hops: RadioGroup
    private lateinit var locked: TextView
    private lateinit var log: TextView
    private lateinit var logToggle: Button
    private lateinit var logCopy: Button
    private lateinit var statUp: TextView
    private lateinit var statDown: TextView
    private lateinit var statRtt: TextView
    private lateinit var statTime: TextView
    private lateinit var router: RadioGroup
    private lateinit var routerHelp: TextView
    private lateinit var externalBox: View
    private lateinit var samState: TextView
    private lateinit var samFix: Button
    private lateinit var openI2pd: Button

    private val handler = Handler(Looper.getMainLooper())
    private var status = Status()
    private var autoConnectAttempted = false
    private var samProbe = 0
    private val listener: (Status) -> Unit = { render(it) }
    private val ticker = object : Runnable {
        override fun run() {
            renderStats(status)
            if (log.visibility == View.VISIBLE) log.text = StatusBus.logText().ifEmpty { getString(R.string.log_empty) }
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        settings = Settings(this)
        autoConnectAttempted = savedInstanceState?.getBoolean("auto_connect_attempted") ?: false

        bundle = findViewById(R.id.bundle)
        phase = findViewById(R.id.phase)
        detail = findViewById(R.id.detail)
        toggle = findViewById(R.id.toggle)
        address = findViewById(R.id.address)
        addressHelp = findViewById(R.id.address_help)
        links = findViewById(R.id.links)
        hops = findViewById(R.id.hops)
        locked = findViewById(R.id.locked)
        log = findViewById(R.id.log)
        logToggle = findViewById(R.id.log_toggle)
        logCopy = findViewById(R.id.log_copy)
        statUp = findViewById(R.id.stat_up)
        statDown = findViewById(R.id.stat_down)
        statRtt = findViewById(R.id.stat_rtt)
        statTime = findViewById(R.id.stat_time)
        router = findViewById(R.id.router)
        routerHelp = findViewById(R.id.router_help)
        externalBox = findViewById(R.id.external_box)
        samState = findViewById(R.id.sam_state)
        samFix = findViewById(R.id.sam_fix)
        openI2pd = findViewById(R.id.open_i2pd)

        applyInsets()
        bindSettings()
        bindRouter()
        address.isSaveEnabled = false
        for (group in listOf(router, links, hops)) {
            group.isSaveEnabled = false
            for (i in 0 until group.childCount) group.getChildAt(i).isSaveEnabled = false
        }
        if (StatusBus.status.phase in setOf(Phase.STOPPED, Phase.ERROR)) {
            savedInstanceState?.getString("address_draft")?.let(address::setText)
        }

        findViewById<Button>(R.id.open_settings).setOnClickListener {
            startActivityForResult(Intent(this, SettingsActivity::class.java), REQ_SETTINGS)
        }

        toggle.setOnClickListener { onToggle() }
        logToggle.setOnClickListener {
            val show = log.visibility != View.VISIBLE
            log.visibility = if (show) View.VISIBLE else View.GONE
            logCopy.visibility = log.visibility
            logToggle.setText(if (show) R.string.log_hide else R.string.log_show)
            if (show) log.text = StatusBus.logText().ifEmpty { getString(R.string.log_empty) }
        }
        logCopy.setOnClickListener {
            getSystemService(ClipboardManager::class.java)
                .setPrimaryClip(ClipData.newPlainText("log", StatusBus.sanitizedLogText()))
            Toast.makeText(this, R.string.log_copied, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        StatusBus.listen(listener)
        handler.post(ticker)
        checkSam()
    }

    override fun onResume() {
        super.onResume()
        maybeAutoConnect()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_MAIN && intent.hasCategory(Intent.CATEGORY_LAUNCHER)) {
            autoConnectAttempted = false
        }
    }

    private fun maybeAutoConnect() {
        if (!autoConnectAttempted) {
            autoConnectAttempted = true
            if (settings.connectOnOpen && Settings.isValidAddress(settings.serverAddress) && !active) onToggle()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("auto_connect_attempted", autoConnectAttempted)
        if (!active && !Settings.isValidAddress(address.text.toString())) {
            outState.putString("address_draft", address.text.toString())
        }
        super.onSaveInstanceState(outState)
    }


    private fun installedRouter(): String? = Settings.ROUTER_PACKAGES.firstOrNull {
        try {
            packageManager.getPackageInfo(it, 0); true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun bindRouter() {
        router.check(if (settings.externalRouter) R.id.router_external else R.id.router_builtin)
        router.setOnCheckedChangeListener { _, id ->
            settings.externalRouter = id == R.id.router_external
            renderRouter()
            checkSam()
        }
        samFix.setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(
                    DocumentsContract.EXTRA_INITIAL_URI,
                    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:i2pd")
                )
            try {
                startActivityForResult(i, REQ_CONF)
            } catch (e: Exception) {
                Toast.makeText(this, getString(R.string.sam_fix_failed, e.message), Toast.LENGTH_LONG).show()
            }
        }
        openI2pd.setOnClickListener {
            val pkg = installedRouter() ?: return@setOnClickListener
            packageManager.getLaunchIntentForPackage(pkg)?.let { startActivity(it) }
        }
        renderRouter()
    }

    private fun renderRouter() {
        val ext = settings.externalRouter
        routerHelp.setText(if (ext) R.string.router_help_external else R.string.router_help_builtin)
        externalBox.visibility = if (ext) View.VISIBLE else View.GONE
        val installed = installedRouter()
        openI2pd.isEnabled = installed != null
        samFix.isEnabled = installed == "org.purplei2p.i2pd"
    }

    private var samVersion: String? = null
    private var samChecked = false

    private fun checkSam() {
        if (!settings.externalRouter) return
        samState.setText(R.string.sam_checking)
        samState.setTextColor(getColor(R.color.text_dim))
        val addr = settings.samAddress
        val probe = ++samProbe
        Thread {
            val v = I2pdConf.probeSam(addr)
            handler.post {
                if (isDestroyed || probe != samProbe || !settings.externalRouter || addr != settings.samAddress) return@post
                samVersion = v
                samChecked = true
                renderSamState()
            }
        }.start()
    }

    private fun renderSamState() {
        if (!samChecked) return
        val v = samVersion
        samFix.visibility = if (v != null) View.GONE else View.VISIBLE
        when {
            v != null -> {
                samState.text = getString(R.string.sam_ok, v)
                samState.setTextColor(getColor(R.color.ok))
            }
            settings.samAddress != Settings.DEFAULT_SAM -> {
                samState.text = getString(R.string.sam_unreachable_address, settings.samAddress)
                samState.setTextColor(getColor(R.color.error))
            }
            installedRouter() == null -> {
                samState.setText(R.string.sam_no_router)
                samState.setTextColor(getColor(R.color.error))
            }
            else -> {
                samState.setText(R.string.sam_off_installed)
                samState.setTextColor(getColor(R.color.accent))
            }
        }
    }

    private fun patchI2pdConf(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!I2pdConf.looksLikeConf(text)) {
                Toast.makeText(this, R.string.sam_wrong_file, Toast.LENGTH_LONG).show()
                return
            }
            val out = I2pdConf.enableSam(text)
            if (out == null) {
                Toast.makeText(this, R.string.sam_already, Toast.LENGTH_LONG).show()
                return
            }
            contentResolver.openOutputStream(uri, "wt")!!.use { it.write(out.toByteArray()) }
            StatusBus.log("app", "SAM enabled in i2pd.conf")
            Toast.makeText(this, R.string.sam_fixed, Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, getString(R.string.sam_fix_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    override fun onStop() {
        samProbe++
        StatusBus.unlisten(listener)
        handler.removeCallbacks(ticker)
        super.onStop()
    }

    private fun applyInsets() {
        val scroll = findViewById<ScrollView>(R.id.scroll)
        val content = findViewById<View>(R.id.content)
        val maxW = resources.getDimensionPixelSize(R.dimen.content_max)
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        val top0 = content.paddingTop
        val bottom0 = content.paddingBottom
        var bars = IntArray(4)
        fun updateWidth() {
            val width = scroll.width - scroll.paddingLeft - scroll.paddingRight - bars[0] - bars[2]
            if (width <= 0) return
            val side = maxOf(gutter, (width - maxW) / 2)
            content.setPadding(side + bars[0], top0 + bars[1], side + bars[2], bottom0 + bars[3])
        }
        scroll.setOnApplyWindowInsetsListener { _, insets ->
            bars = if (Build.VERSION.SDK_INT >= 30)
                insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                    .let { intArrayOf(it.left, it.top, it.right, it.bottom) }
            else @Suppress("DEPRECATION")
                intArrayOf(insets.systemWindowInsetLeft, insets.systemWindowInsetTop,
                    insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            updateWidth()
            insets
        }
        scroll.addOnLayoutChangeListener { _, left, _, right, _, oldLeft, _, oldRight, _ ->
            if (right - left != oldRight - oldLeft) updateWidth()
        }
        scroll.requestApplyInsets()
    }

    private fun bindSettings() {
        address.setRawInputType(
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        )
        address.setText(settings.serverAddress)
        address.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                val v = s.toString().trim().lowercase()
                if (Settings.isValidAddress(v)) settings.serverAddress = v
                showAddressError(false)
            }
        })
        address.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                showAddressError(!Settings.isValidAddress(address.text.toString()))
                address.clearFocus()
            }
            false
        }

        links.check(
            when (settings.links) { 8 -> R.id.links_8; 24 -> R.id.links_24; 32 -> R.id.links_32; else -> R.id.links_16 }
        )
        links.setOnCheckedChangeListener { _, id ->
            settings.links = when (id) { R.id.links_8 -> 8; R.id.links_24 -> 24; R.id.links_32 -> 32; else -> 16 }
            if (status.phase == Phase.STOPPED || status.phase == Phase.ERROR) render(status)
        }
        hops.check(when (settings.hops) { 1 -> R.id.hops_1; 2 -> R.id.hops_2; else -> R.id.hops_3 })
        hops.setOnCheckedChangeListener { _, id ->
            settings.hops = when (id) { R.id.hops_1 -> 1; R.id.hops_2 -> 2; else -> 3 }
        }
    }

    private fun showAddressError(error: Boolean) {
        addressHelp.setText(if (error) R.string.server_invalid else R.string.server_help)
        addressHelp.setTextColor(getColor(if (error) R.color.error else R.color.text_dim))
    }

    private val active get() = status.phase != Phase.STOPPED && status.phase != Phase.ERROR

    private fun onToggle() {
        if (active) {
            OutproxyVpnService.stop(this)
            return
        }
        val a = address.text.toString().trim().lowercase()
        if (!Settings.isValidAddress(a)) {
            showAddressError(true)
            address.requestFocus()
            return
        }
        settings.serverAddress = a
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
            return
        }
        requestVpn()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (code == REQ_NOTIF) requestVpn()
    }

    private fun requestVpn() {
        val intent = VpnService.prepare(this)
        if (intent != null) startActivityForResult(intent, REQ_VPN) else OutproxyVpnService.start(this)
    }

    @Deprecated("Activity result API is not available without AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_VPN ->
                if (resultCode == RESULT_OK) OutproxyVpnService.start(this)
                else Toast.makeText(this, R.string.err_permission, Toast.LENGTH_LONG).show()
            REQ_CONF -> if (resultCode == RESULT_OK) data?.data?.let(::patchI2pdConf)
            REQ_SETTINGS -> if (resultCode == RESULT_OK) {
                address.setText(settings.serverAddress)
                router.check(if (settings.externalRouter) R.id.router_external else R.id.router_builtin)
                links.check(when (settings.links) {
                    8 -> R.id.links_8
                    24 -> R.id.links_24
                    32 -> R.id.links_32
                    else -> R.id.links_16
                })
                hops.check(when (settings.hops) { 1 -> R.id.hops_1; 2 -> R.id.hops_2; else -> R.id.hops_3 })
                renderRouter()
                checkSam()
                render(StatusBus.status)
            }
        }
    }


    private fun render(s: Status) {
        status = s
        phase.text = Texts.headline(this, s)
        phase.setTextColor(getColor(if (s.phase == Phase.ERROR) R.color.error else R.color.text))
        detail.text = Texts.detail(this, s)

        val linksNow = if (active) s.links else settings.links
        bundle.set(
            linksNow,
            if (active) BundleView.litLinks(s) else 0,
            when (s.phase) {
                Phase.READY -> BundleView.ExitState.READY
                Phase.ERROR -> BundleView.ExitState.ERROR
                Phase.STOPPED, Phase.STOPPING -> BundleView.ExitState.IDLE
                else -> BundleView.ExitState.BUILDING
            },
        )

        if (active) {
            toggle.setText(R.string.action_disconnect)
            toggle.setBackgroundResource(R.drawable.btn_quiet)
            toggle.setTextColor(getColor(R.color.text))
        } else {
            toggle.setText(R.string.action_connect)
            toggle.setBackgroundResource(R.drawable.btn_primary)
            toggle.setTextColor(getColor(R.color.bg))
        }
        toggle.isEnabled = s.phase != Phase.STOPPING

        val editable = !active
        address.isEnabled = editable
        for (g in listOf(router, links, hops)) for (i in 0 until g.childCount) g.getChildAt(i).isEnabled = editable
        locked.visibility = if (editable) View.GONE else View.VISIBLE
        renderStats(s)
    }

    private fun renderStats(s: Status) {
        val on = active
        val dash = "-"
        statDown.text = if (on) Texts.bytes(s.down) else dash
        statUp.text = if (on) Texts.bytes(s.up) else dash
        statRtt.text = if (s.phase == Phase.READY) Texts.seconds(s.rttMs) + " s" else dash
        statTime.text = if (s.phase == Phase.READY && s.readySince > 0)
            Texts.duration(System.currentTimeMillis() - s.readySince) else dash
    }

    companion object {
        private const val REQ_VPN = 1
        private const val REQ_NOTIF = 2
        private const val REQ_CONF = 3
        private const val REQ_SETTINGS = 4

        fun pendingIntent(c: Context): PendingIntent = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
    }
}

package org.multipath.outproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputFilter
import android.view.View
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.integration.android.IntentIntegrator
import java.io.ByteArrayOutputStream

class ProfilesActivity : Activity() {
    private lateinit var profiles: Profiles
    private lateinit var list: LinearLayout
    private var dialog: AlertDialog? = null
    private var nameInput: EditText? = null
    private var renameId: String? = null
    private var pendingImport: Profile? = null
    private var pendingQr: Profile? = null
    private var exportText: String? = null
    private var readingUri: String? = null
    private val active get() = StatusBus.status.phase !in setOf(Phase.STOPPED, Phase.ERROR)
    private val listener: (Status) -> Unit = { updateLock() }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(R.layout.activity_profiles)
        profiles = Profiles(this)
        list = findViewById(R.id.profile_list)
        applyInsets()
        findViewById<Button>(R.id.profiles_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.profile_new).setOnClickListener { if (!active) editName(null) }
        findViewById<Button>(R.id.profile_import).setOnClickListener {
            if (!active) launchDocument(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), IMPORT)
        }
        findViewById<Button>(R.id.profile_scan).setOnClickListener {
            if (!active) IntentIntegrator(this).setDesiredBarcodeFormats(IntentIntegrator.QR_CODE)
                .setPrompt(getString(R.string.profile_scan_prompt)).setBeepEnabled(false)
                .setOrientationLocked(false).initiateScan()
        }
        render()
        exportText = state?.getString("export")
        when {
            state?.containsKey("draft_name") == true -> editName(state.getString("rename_id"), state.getString("draft_name"))
            state?.containsKey("import") == true -> preview(Profile.parse(state.getString("import")!!))
            state?.containsKey("qr") == true -> showQr(Profile.parse(state.getString("qr")!!))
            state?.containsKey("reading_uri") == true -> importFile(Uri.parse(state.getString("reading_uri")))
        }
        if (state == null && intent.action == Intent.ACTION_VIEW) intent.dataString?.let(::readProfile)
    }

    override fun onStart() {
        super.onStart()
        StatusBus.listen(listener)
        render()
    }

    override fun onStop() {
        StatusBus.unlisten(listener)
        super.onStop()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        super.onDestroy()
    }

    override fun onSaveInstanceState(out: Bundle) {
        nameInput?.let { out.putString("draft_name", it.text.toString()); out.putString("rename_id", renameId) }
        pendingImport?.let { out.putString("import", it.export()) }
        pendingQr?.let { out.putString("qr", it.export()) }
        out.putString("export", exportText)
        readingUri?.let { out.putString("reading_uri", it) }
        super.onSaveInstanceState(out)
    }

    private fun render() {
        list.removeAllViews()
        for (profile in profiles.list()) {
            val row = layoutInflater.inflate(R.layout.profile_row, list, false)
            row.findViewById<TextView>(R.id.profile_name).text = if (profile.id == profiles.selectedId)
                getString(R.string.profile_selected, profile.name) else profile.name
            row.findViewById<TextView>(R.id.profile_address).text = profile.server.ifBlank { getString(R.string.profile_no_address) }
            row.contentDescription = getString(R.string.profile_actions, profile.name)
            row.setOnClickListener { actions(profile.id) }
            list.addView(row)
        }
        updateLock()
    }

    private fun updateLock() {
        val busy = active || readingUri != null
        findViewById<TextView>(R.id.profiles_busy).apply {
            visibility = if (busy) View.VISIBLE else View.GONE
            setText(if (readingUri != null) R.string.profile_reading else R.string.profiles_locked)
        }
        listOf(R.id.profile_new, R.id.profile_import, R.id.profile_scan).forEach { findViewById<View>(it).isEnabled = !busy }
        if (nameInput != null || pendingImport != null) dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = !active
    }

    private fun actions(id: String) {
        val p = profiles.list().firstOrNull { it.id == id } ?: return
        val options = mutableListOf<Pair<Int, () -> Unit>>()
        if (!active) {
            options += R.string.profile_use to { profiles.select(id); setResult(RESULT_OK); finish() }
            options += R.string.profile_rename to { editName(id) }
        }
        if (Settings.isValidAddress(p.server)) {
            options += R.string.profile_export to { export(p) }
            options += R.string.profile_show_qr to { showQr(p) }
        }
        if (!active && profiles.list().size > 1) options += R.string.profile_delete to { delete(p) }
        dialog = AlertDialog.Builder(this).setTitle(p.name)
            .setItems(options.map { getString(it.first) }.toTypedArray()) { _, which ->
                val option = options[which]
                if (active && option.first !in setOf(R.string.profile_export, R.string.profile_show_qr)) return@setItems
                option.second()
            }.setNegativeButton(R.string.settings_cancel, null).show()
    }

    private fun editName(id: String?, draft: String? = null) {
        if (active) return
        renameId = id
        val input = EditText(this).apply {
            setSingleLine(true)
            filters = arrayOf(InputFilter.LengthFilter(Profile.MAX_NAME))
            setText(draft ?: profiles.list().firstOrNull { it.id == id }?.name.orEmpty())
            hint = getString(R.string.profile_name_hint)
            setSelectAllOnFocus(true)
        }
        nameInput = input
        val box = LinearLayout(this).apply {
            val pad = resources.getDimensionPixelSize(R.dimen.gutter)
            setPadding(pad, pad / 2, pad, 0)
            addView(input, LinearLayout.LayoutParams(-1, -2))
        }
        dialog = AlertDialog.Builder(this).setTitle(if (id == null) R.string.profile_new else R.string.profile_rename)
            .setView(box).setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_save, null).create().also { d ->
                d.setOnShowListener {
                    d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        if (active) return@setOnClickListener
                        val name = input.text.toString().trim()
                        if (!Profile.validName(name)) { input.error = getString(R.string.profile_name_error); return@setOnClickListener }
                        if (id == null && profiles.list().size >= Profile.MAX_PROFILES) { toast(R.string.profile_limit); return@setOnClickListener }
                        if (id == null) profiles.add(Profile("", name, "")) else profiles.rename(id, name)
                        setResult(RESULT_OK)
                        d.dismiss()
                        if (id == null) finish() else render()
                    }
                }
                d.setOnDismissListener { nameInput = null; renameId = null }
                d.show()
                input.requestFocus()
            }
    }

    private fun delete(p: Profile) {
        dialog = AlertDialog.Builder(this).setTitle(getString(R.string.profile_delete_title, p.name))
            .setMessage(R.string.profile_delete_help).setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.profile_delete) { _, _ ->
                if (!active && profiles.list().size > 1) { profiles.delete(p.id); setResult(RESULT_OK); render() }
            }.show()
    }

    private fun readProfile(text: String) {
        if (active) { toast(R.string.profiles_locked); return }
        try { preview(Profile.parse(text)) } catch (_: Exception) { toast(R.string.profile_invalid) }
    }

    private fun preview(p: Profile) {
        pendingImport = p
        dialog = AlertDialog.Builder(this).setTitle(getString(R.string.profile_import_title, p.name))
            .setMessage(getString(R.string.profile_preview, p.server, p.dns, p.links, p.hops, p.mtu,
                if (p.external) getString(R.string.profile_router_sam, p.sam) else getString(R.string.router_builtin)))
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.profile_add) { _, _ ->
                pendingImport = null
                if (!active) {
                    if (profiles.list().size >= Profile.MAX_PROFILES) toast(R.string.profile_limit)
                    else { profiles.add(p); setResult(RESULT_OK); render(); toast(R.string.profile_imported) }
                }
            }.create().also { d -> d.setOnDismissListener { pendingImport = null }; d.show() }
        updateLock()
    }

    private fun export(p: Profile) {
        exportText = p.export()
        launchDocument(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("application/json").putExtra(Intent.EXTRA_TITLE, "multipath-profile.json"), EXPORT)
    }

    private fun launchDocument(intent: Intent, code: Int) {
        try { startActivityForResult(intent, code) } catch (_: Exception) { toast(R.string.profile_file_error) }
    }

    private fun showQr(p: Profile) {
        try {
            val matrix = QRCodeWriter().encode(p.qr(), BarcodeFormat.QR_CODE, 640, 640, mapOf(EncodeHintType.MARGIN to 4))
            val pixels = IntArray(matrix.width * matrix.height) { i -> if (matrix[i % matrix.width, i / matrix.width]) 0xff000000.toInt() else 0xffffffff.toInt() }
            val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                val pad = resources.getDimensionPixelSize(R.dimen.gutter)
                setPadding(pad, 0, pad, pad)
                val qrSize = ((resources.configuration.screenHeightDp - 220).coerceIn(160, 480) * resources.displayMetrics.density).toInt()
                addView(ImageView(this@ProfilesActivity).apply {
                    setImageBitmap(bitmap); adjustViewBounds = true
                    maxWidth = qrSize; maxHeight = qrSize
                    contentDescription = getString(R.string.profile_qr_description, p.name)
                }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
                addView(TextView(this@ProfilesActivity).apply { setText(R.string.profile_share_help) })
            }
            pendingQr = p
            dialog = AlertDialog.Builder(this).setTitle(p.name).setView(box)
                .setPositiveButton(R.string.profile_close, null).create().also { d ->
                    d.setOnDismissListener { pendingQr = null }; d.show()
                }
        } catch (_: Exception) { toast(R.string.profile_qr_error) }
    }

    @Deprecated("Activity result API is used by the existing activities")
    override fun onActivityResult(code: Int, result: Int, data: Intent?) {
        super.onActivityResult(code, result, data)
        val scan = IntentIntegrator.parseActivityResult(code, result, data)
        if (scan != null) { scan.contents?.let(::readProfile); return }
        if (result != RESULT_OK) { if (code == EXPORT) exportText = null; return }
        val uri = data?.data ?: return
        if (code == IMPORT) {
            importFile(uri)
        } else if (code == EXPORT) {
            val text = exportText ?: return
            exportText = null
            Thread {
                val written = runCatching { contentResolver.openOutputStream(uri, "wt")!!.use { it.write(text.toByteArray(Charsets.UTF_8)) } }.isSuccess
                runOnUiThread { if (!isDestroyed && !isFinishing) toast(if (written) R.string.profile_exported else R.string.profile_file_error) }
            }.start()
        }
    }

    private fun importFile(uri: Uri) {
        readingUri = uri.toString()
        updateLock()
        Thread {
            val text = runCatching {
                contentResolver.openInputStream(uri)!!.use { input ->
                    val out = ByteArrayOutputStream()
                    val bytes = ByteArray(4096)
                    while (true) {
                        val n = input.read(bytes)
                        if (n < 0) break
                        require(out.size() + n <= Profile.MAX_BYTES)
                        out.write(bytes, 0, n)
                    }
                    out.toByteArray().toString(Charsets.UTF_8)
                }
            }
            runOnUiThread {
                if (!isDestroyed && !isFinishing) {
                    readingUri = null
                    updateLock()
                    text.fold(::readProfile) { toast(R.string.profile_file_error) }
                }
            }
        }.start()
    }

    private fun toast(message: Int) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    private fun applyInsets() {
        val root = findViewById<View>(R.id.profiles_root)
        val content = findViewById<View>(R.id.profiles_content)
        val gutter = resources.getDimensionPixelSize(R.dimen.gutter)
        val maxWidth = resources.getDimensionPixelSize(R.dimen.content_max)
        fun width() {
            val available = root.width - root.paddingLeft - root.paddingRight
            val side = maxOf(gutter, (available - maxWidth) / 2)
            content.setPadding(side, gutter, side, gutter)
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            } else {
                @Suppress("DEPRECATION")
                view.setPadding(insets.systemWindowInsetLeft, insets.systemWindowInsetTop, insets.systemWindowInsetRight, insets.systemWindowInsetBottom)
            }
            width(); insets
        }
        root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> width() }
        root.requestApplyInsets()
    }

    companion object {
        private const val IMPORT = 10
        private const val EXPORT = 11
    }
}

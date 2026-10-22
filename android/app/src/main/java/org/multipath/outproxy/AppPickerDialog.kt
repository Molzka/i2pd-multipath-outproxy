package org.multipath.outproxy

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.Locale

internal class AppPickerDialog(
    private val activity: Activity,
    initialSelection: Set<String>,
    private val initialQuery: String = "",
    private val canEdit: () -> Boolean,
    private val onSelected: (Set<String>) -> Unit,
) {
    private data class App(val label: String, val packageName: String)
    private val selected = initialSelection.filter {
        it != activity.packageName && it !in Settings.ROUTER_PACKAGES
    }.toMutableSet()
    private var apps = emptyList<App>()
    private var visibleApps = emptyList<App>()
    private var dialog: AlertDialog? = null
    private lateinit var list: ListView
    private lateinit var search: EditText
    private lateinit var empty: TextView
    private lateinit var adapter: ArrayAdapter<App>
    private var loaded = false

    fun show() {
        val gap = activity.resources.getDimensionPixelSize(R.dimen.s4)
        val gutter = activity.resources.getDimensionPixelSize(R.dimen.gutter)
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(gutter, gap, gutter, 0)
        }
        body.addView(TextView(activity).apply {
            setTextAppearance(R.style.Text_Help)
            setText(R.string.settings_picker_help)
        }, LinearLayout.LayoutParams(-1, -2))
        search = EditText(activity).apply {
            setTextAppearance(R.style.Text_Body)
            setBackgroundResource(R.drawable.field)
            hint = activity.getString(R.string.settings_picker_hint)
            contentDescription = activity.getString(R.string.settings_picker_search)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO
            setSingleLine(true)
            minHeight = activity.resources.getDimensionPixelSize(R.dimen.touch)
            setText(initialQuery)
        }
        body.addView(search, LinearLayout.LayoutParams(-1, -2).apply { topMargin = gap })
        empty = TextView(activity).apply {
            setTextAppearance(R.style.Text_Help)
            setText(R.string.settings_picker_loading)
            setPadding(0, gap, 0, gap)
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.addView(empty, LinearLayout.LayoutParams(-1, -2))
        list = ListView(activity).apply {
            choiceMode = ListView.CHOICE_MODE_MULTIPLE
            divider = null
            emptyView = empty
        }
        val height = minOf(
            activity.resources.displayMetrics.heightPixels / 3,
            activity.resources.getDimensionPixelSize(R.dimen.touch) * 6,
        )
        body.addView(list, LinearLayout.LayoutParams(-1, height))
        adapter = object : ArrayAdapter<App>(activity, android.R.layout.simple_list_item_multiple_choice) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = super.getView(position, convertView, parent) as CheckedTextView
                row.layoutParams = android.widget.AbsListView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
                val app = getItem(position)!!
                val text = SpannableString(app.label + "\n" + app.packageName)
                val start = app.label.length + 1
                text.setSpan(RelativeSizeSpan(0.8f), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                text.setSpan(ForegroundColorSpan(activity.getColor(R.color.text_dim)), start, text.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                row.text = text
                row.setSingleLine(false)
                row.maxLines = 3
                row.typeface = activity.resources.getFont(R.font.plex_sans_regular)
                row.setTextColor(activity.getColor(R.color.text))
                row.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, activity.resources.getDimension(R.dimen.t_body))
                row.setPadding(0, gap, gap, gap)
                row.minHeight = activity.resources.getDimensionPixelSize(R.dimen.touch)
                row.isEnabled = canEdit()
                row.isChecked = app.packageName in selected
                return row
            }
        }
        list.adapter = adapter
        list.setOnItemClickListener { _, _, position, _ ->
            if (canEdit()) {
                val pkg = visibleApps[position].packageName
                if (!selected.add(pkg)) selected.remove(pkg)
                list.setItemChecked(position, pkg in selected)
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { filter() }
            override fun afterTextChanged(s: Editable?) {}
        })
        val picker = AlertDialog.Builder(activity)
            .setTitle(R.string.settings_picker_title)
            .setView(body)
            .setNegativeButton(R.string.settings_cancel, null)
            .setPositiveButton(R.string.settings_picker_done) { _, _ ->
                if (canEdit() && loaded) onSelected(selected.toSet())
            }.create()
        dialog = picker
        picker.setOnShowListener { setEditable(canEdit()) }
        picker.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        picker.show()
        Thread {
            val result = runCatching {
                val manager = activity.packageManager
                val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                @Suppress("DEPRECATION")
                val resolved = manager.queryIntentActivities(intent, 0)
                val collator = Collator.getInstance(Locale.getDefault())
                resolved.map { App(it.loadLabel(manager).toString(), it.activityInfo.packageName) }
                    .distinctBy { it.packageName }
                    .filter { it.packageName != activity.packageName && it.packageName !in Settings.ROUTER_PACKAGES }
                    .sortedWith { left, right -> collator.compare(left.label, right.label) }
            }
            activity.runOnUiThread {
                if (activity.isDestroyed || dialog?.isShowing != true) return@runOnUiThread
                result.onSuccess {
                    apps = it
                    loaded = true
                    filter()
                }.onFailure { empty.setText(R.string.settings_picker_failed) }
                setEditable(canEdit())
            }
        }.start()
    }

    private fun filter() {
        if (!loaded) return
        val query = search.text.toString().trim()
        visibleApps = apps.filter { it.label.contains(query, true) || it.packageName.contains(query, true) }
        adapter.clear()
        adapter.addAll(visibleApps)
        list.clearChoices()
        visibleApps.forEachIndexed { index, app -> list.setItemChecked(index, app.packageName in selected) }
        empty.setText(R.string.settings_picker_empty)
    }

    fun setEditable(enabled: Boolean) {
        if (!::list.isInitialized) return
        list.isEnabled = enabled
        adapter.notifyDataSetChanged()
        dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = enabled && loaded
    }

    fun dismiss() {
        dialog?.dismiss()
    }

    fun saveState(): Bundle? {
        if (dialog?.isShowing != true) return null
        return Bundle().apply {
            putStringArrayList("selection", ArrayList(selected))
            putString("query", search.text.toString())
        }
    }
}

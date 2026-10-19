package dev.phosphor.launcher

import android.app.Dialog
import android.content.Context
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.ViewTreeObserver
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.CheckedTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

internal class AppPicker(private val host: MainActivity) {
    fun single(title: String, selected: AppEntry?, allowEmpty: Boolean, onChoose: (AppEntry?) -> Unit) {
        show(title, listOfNotNull(selected?.component?.flattenToString()), false, allowEmpty, onChoose, null)
    }

    fun multiple(title: String, selected: List<String>, onSave: (List<String>) -> Unit) {
        show(title, selected, true, false, null, onSave)
    }

    private fun show(
        title: String,
        selected: List<String>,
        multiple: Boolean,
        allowEmpty: Boolean,
        onChoose: ((AppEntry?) -> Unit)?,
        onSave: ((List<String>) -> Unit)?
    ) {
        val apps = host.device.apps
        // Opening a picker before the repository publishes must never clear saved choices.
        if (apps.isEmpty()) {
            host.toast(L10n.text(R.string.menus_apps_loading))
            return
        }
        val ink = Inks.get(host.prefs.getString("theme", "phosphor"))
        val chosen = LinkedHashSet(selected)
        fun dp(value: Int) = NativeDialogs.dp(host, value)
        val dialog = Dialog(host)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dialog.setTitle(title)
        val root = PickerLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(ink.bg)
            isFocusableInTouchMode = true
        }
        val heading = NativeDialogs.title(host, title, ink)
        root.addView(heading, LinearLayout.LayoutParams(-1, -2))
        val query = EditText(host).apply {
            hint = L10n.text(R.string.menus_search_apps)
            contentDescription = L10n.text(R.string.menus_search_apps_accessibility)
            isSingleLine = true
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(ink.fg)
            setHintTextColor(ink.dim)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            minimumHeight = dp(52)
            backgroundTintList = android.content.res.ColorStateList.valueOf(ink.accent)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, _, _ ->
                host.getSystemService(android.view.inputmethod.InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
                clearFocus()
                true
            }
        }
        root.addView(query, LinearLayout.LayoutParams(-1, -2).apply {
            leftMargin = dp(8); rightMargin = dp(8)
        })
        val summary = TextView(host).apply {
            textSize = 13f
            typeface = Typeface.MONOSPACE
            setTextColor(ink.fg)
            setPadding(dp(20), dp(8), dp(20), dp(8))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        root.addView(summary, LinearLayout.LayoutParams(-1, -2))
        val listArea = FrameLayout(host)
        val list = ListView(host).apply {
            divider = ColorDrawable(ink.dim)
            dividerHeight = dp(1)
            choiceMode = if (multiple) ListView.CHOICE_MODE_MULTIPLE else ListView.CHOICE_MODE_SINGLE
            selector = NativeDialogs.rowBackground(ink)
            isFastScrollEnabled = true
        }
        val empty = TextView(host).apply {
            text = L10n.text(R.string.menus_no_apps)
            textSize = 16f
            typeface = Typeface.MONOSPACE
            setTextColor(ink.fg)
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(20), dp(20), dp(20))
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        listArea.addView(list, FrameLayout.LayoutParams(-1, -1))
        listArea.addView(empty, FrameLayout.LayoutParams(-1, -1))
        list.emptyView = empty
        root.addView(listArea, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(View(host).apply { setBackgroundColor(ink.dim) }, LinearLayout.LayoutParams(-1, dp(1)))
        val buttons = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        fun button(label: String, accent: Boolean = false, action: () -> Unit) {
            buttons.addView(Button(host).apply {
                text = label
                isAllCaps = false
                textSize = 15f
                typeface = Typeface.create(Typeface.MONOSPACE, if (accent) Typeface.BOLD else Typeface.NORMAL)
                setTextColor(ink.fg)
                backgroundTintList = android.content.res.ColorStateList.valueOf(ink.faint)
                minimumHeight = dp(48)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(0, dp(48), 1f))
        }
        button(L10n.text(R.string.common_cancel)) { dialog.dismiss() }
        if (multiple) button(L10n.text(R.string.common_save), true) {
            dialog.dismiss()
            onSave?.invoke(chosen.toList())
            host.redraw()
        } else if (allowEmpty) button(L10n.text(R.string.menus_empty_slot), true) {
            dialog.dismiss()
            onChoose?.invoke(null)
            host.redraw()
        }
        root.addView(buttons, LinearLayout.LayoutParams(-1, -2))
        val adapter = AppsAdapter(apps, chosen, multiple, ink)
        list.adapter = adapter
        fun refresh() {
            list.clearChoices()
            adapter.visible.forEachIndexed { index, app ->
                list.setItemChecked(index, app.component.flattenToString() in chosen)
            }
            summary.text = if (multiple) L10n.text(R.string.menus_selected_apps,chosen.size,adapter.count) else L10n.text(R.string.menus_app_count,adapter.count)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val app = adapter.visible[position]
            val key = app.component.flattenToString()
            if (multiple) {
                if (!chosen.add(key)) chosen.remove(key)
                adapter.notifyDataSetChanged()
                refresh()
            } else {
                dialog.dismiss()
                onChoose?.invoke(app)
                host.redraw()
            }
        }
        query.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                adapter.filter(s?.toString().orEmpty())
                refresh()
                list.setSelection(0)
            }
            override fun afterTextChanged(s: Editable?) {}
        })
        refresh()
        root.onCompactChanged = { compact ->
            // Search replaces the decorative heading when the IME leaves little vertical space.
            heading.visibility = if (compact) View.GONE else View.VISIBLE
            summary.visibility = if (compact) View.GONE else View.VISIBLE
            query.minimumHeight = dp(if (compact) 48 else 52)
            val queryPadding = dp(if (compact) 4 else 12)
            query.setPadding(dp(16), queryPadding, dp(16), queryPadding)
            val buttonPadding = dp(if (compact) 0 else 4)
            buttons.setPadding(dp(8), buttonPadding, dp(8), buttonPadding)
            adapter.setCompact(compact)
        }
        if (android.os.Build.VERSION.SDK_INT >= 28) root.accessibilityPaneTitle = title
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(ink.bg))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE or WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        dialog.show()
        dialog.window?.apply {
            // Constrain the content itself as well: floating windows do not always resize for IME.
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            decorView.setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        root.requestFocus()
    }

    private class PickerLayout(context: Context) : LinearLayout(context), ViewTreeObserver.OnGlobalLayoutListener {
        var onCompactChanged: ((Boolean) -> Unit)? = null
        private val visibleFrame = Rect()
        private val location = IntArray(2)
        private var frameTop = -1
        private var frameBottom = -1
        private var compact = false

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            viewTreeObserver.addOnGlobalLayoutListener(this)
        }

        override fun onDetachedFromWindow() {
            viewTreeObserver.removeOnGlobalLayoutListener(this)
            super.onDetachedFromWindow()
        }

        override fun onGlobalLayout() {
            getWindowVisibleDisplayFrame(visibleFrame)
            if (frameTop != visibleFrame.top || frameBottom != visibleFrame.bottom) {
                frameTop = visibleFrame.top
                frameBottom = visibleFrame.bottom
                requestLayout()
            }
        }

        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            var height = MeasureSpec.getSize(heightMeasureSpec)
            if (isAttachedToWindow) {
                getWindowVisibleDisplayFrame(visibleFrame)
                getLocationOnScreen(location)
                if (!visibleFrame.isEmpty) {
                    val available = visibleFrame.bottom - maxOf(visibleFrame.top, location[1]) - rootView.paddingBottom
                    height = minOf(height, available.coerceAtLeast(0))
                }
            }
            val nextCompact = height < NativeDialogs.dp(context, 320)
            if (nextCompact != compact) {
                compact = nextCompact
                onCompactChanged?.invoke(compact)
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        }
    }

    private inner class AppsAdapter(
        private val apps: List<AppEntry>,
        private val selected: Set<String>,
        private val multiple: Boolean,
        private val ink: Ink
    ) : BaseAdapter() {
        var visible = apps
            private set
        // AppEntry already holds the repository's cached 64px bitmap; no second bitmap copy.
        private val icons = HashMap<String, BitmapDrawable>()
        private var compact = false

        fun setCompact(value: Boolean) {
            if (compact == value) return
            compact = value
            notifyDataSetChanged()
        }

        fun filter(query: String) {
            val term = query.trim()
            visible = if (term.isEmpty()) apps else apps.filter { it.label.contains(term, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? CheckedTextView ?: (LayoutInflater.from(host).inflate(
                if (multiple) android.R.layout.simple_list_item_multiple_choice else android.R.layout.simple_list_item_single_choice,
                parent, false
            ) as CheckedTextView).apply {
                typeface = Typeface.MONOSPACE
                textSize = 16f
                setTextColor(ink.fg)
                checkMarkTintList = android.content.res.ColorStateList.valueOf(ink.accent)
                compoundDrawablePadding = NativeDialogs.dp(host, 12)
                maxLines = 3
                ellipsize = android.text.TextUtils.TruncateAt.END
                background = NativeDialogs.rowBackground(ink)
            }
            val app = visible[position]
            val key = app.component.flattenToString()
            val icon = icons.getOrPut(key) {
                BitmapDrawable(host.resources, app.icon).apply {
                    val size = NativeDialogs.dp(host, 32)
                    setBounds(0, 0, size, size)
                }
            }
            row.text = app.label
            row.setCompoundDrawablesRelative(icon, null, null, null)
            val verticalPadding = NativeDialogs.dp(host, if (compact) 6 else 14)
            row.setPaddingRelative(NativeDialogs.dp(host, 16), verticalPadding, NativeDialogs.dp(host, 16), verticalPadding)
            row.minimumHeight = NativeDialogs.dp(host, if (compact) 48 else 64)
            row.isChecked = key in selected
            row.isActivated = row.isChecked
            row.contentDescription = L10n.text(if(row.isChecked) R.string.menus_app_selected else R.string.menus_app_not_selected,app.label)
            return row
        }
    }
}

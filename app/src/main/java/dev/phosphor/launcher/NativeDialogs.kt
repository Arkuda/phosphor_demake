package dev.phosphor.launcher

import android.app.AlertDialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.CheckedTextView
import android.widget.LinearLayout
import android.widget.TextView

internal object NativeDialogs {
    private const val TITLE_TAG = "phosphor.dialog.title"

    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density + .5f).toInt()

    fun title(context: Context, title: String, ink: Ink): View = LinearLayout(context).apply {
        tag = TITLE_TAG
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(ink.faint)
        addView(TextView(context).apply {
            text = title
            textSize = 19f
            setTextColor(ink.accent)
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
            setPadding(dp(context, 20), dp(context, 18), dp(context, 20), dp(context, 16))
        }, LinearLayout.LayoutParams(-1, -2))
        addView(View(context).apply { setBackgroundColor(ink.accent) }, LinearLayout.LayoutParams(-1, dp(context, 2)))
    }

    fun rowBackground(ink: Ink): RippleDrawable {
        val states = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_activated), ColorDrawable(ink.faint))
            addState(intArrayOf(android.R.attr.state_checked), ColorDrawable(ink.faint))
            addState(intArrayOf(android.R.attr.state_selected), ColorDrawable(ink.faint))
            addState(intArrayOf(), ColorDrawable(ink.bg))
        }
        return RippleDrawable(ColorStateList.valueOf((ink.accent and 0x00ffffff) or 0x55000000), states, null)
    }

    fun body(view: View, ink: Ink) {
        if (view.tag == TITLE_TAG) return
        if (view is TextView) {
            view.setTextColor(ink.fg)
            view.typeface = Typeface.MONOSPACE
            if (view is CheckedTextView) view.checkMarkTintList = ColorStateList.valueOf(ink.accent)
        }
        if (view is ViewGroup) for (i in 0 until view.childCount) body(view.getChildAt(i), ink)
    }

    fun style(dialog: AlertDialog, ink: Ink) {
        dialog.window?.setBackgroundDrawable(ColorDrawable(ink.bg))
        dialog.window?.decorView?.let { body(it, ink) }
        dialog.listView?.apply {
            divider = ColorDrawable(ink.dim)
            dividerHeight = dp(context, 1)
            selector = rowBackground(ink)
            fun styleRow(child: View) {
                body(child, ink)
                child.minimumHeight = dp(context, 56)
                child.setPaddingRelative(dp(context, 20), dp(context, 14), dp(context, 20), dp(context, 14))
                child.background = rowBackground(ink)
            }
            for (i in 0 until childCount) styleRow(getChildAt(i))
            setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
                override fun onChildViewAdded(parent: View?, child: View?) { child?.let { styleRow(it) } }
                override fun onChildViewRemoved(parent: View?, child: View?) {}
            })
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.setTextColor(ink.accent)
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(ink.fg)
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setTextColor(ink.accent)
    }
}

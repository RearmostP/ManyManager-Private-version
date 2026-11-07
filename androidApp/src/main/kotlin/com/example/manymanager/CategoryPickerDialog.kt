package com.example.simple.manymanager

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.example.simple.manymanager.domain.Category

/** Browsing is local to this window. Only explicit confirmation changes the expense draft. */
class CategoryPickerDialog(
    private val context: Context,
    private val categories: List<Category>,
    private val selectedId: String?,
    initialParentId: String? = null,
    private val onSelect: (String) -> Unit,
) {
    private var parentId = initialParentId
    private val inline = initialParentId != null
    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
    private val backButton = TextView(context, null, 0, R.style.Text).apply {
        id = R.id.categoryPickerBack
        setText(R.string.back)
        textSize = 14f
        minHeight = dp(48)
        gravity = android.view.Gravity.CENTER
        setPadding(dp(14), dp(10), dp(14), dp(10))
        setBackgroundResource(R.drawable.button_secondary)
        isClickable = true; isFocusable = true
        setOnClickListener { back() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            marginStart = dp(20); topMargin = dp(12)
        }
    }
    private val heading = TextView(context, null, 0, R.style.Text).apply {
        textSize = 22f
        setTypeface(null, android.graphics.Typeface.BOLD)
        isAccessibilityHeading = true
        setPadding(dp(20), dp(16), dp(20), dp(8))
    }
    private val rows = LinearLayout(context).apply {
        id = R.id.categoryPickerRows
        val inset = (20 * resources.displayMetrics.density).toInt()
        setPadding(inset, inset / 2, inset, inset / 2)
        orientation = LinearLayout.VERTICAL
        layoutDirection = resources.configuration.layoutDirection
    }
    val dialog: AlertDialog = AlertDialog.Builder(context)
        .setView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = resources.configuration.layoutDirection
                addView(backButton); addView(heading); addView(rows)
            })
        })
        .setNegativeButton(R.string.cancel, null)
        .setPositiveButton(R.string.select_main_category, null).create()

    private fun confirm(id: String) { dialog.dismiss(); onSelect(id) }
    private fun render() {
        val parent = categories.firstOrNull { it.id == parentId && it.parentId == null }
        heading.text = context.calculatorTitle(parent?.name ?: context.getString(R.string.expense_category))
        rows.removeAllViews()
        val options = categories.filter { it.parentId == parent?.id }
        if (options.isEmpty()) rows.addView(TextView(context).apply {
            setText(if (parent == null) R.string.category_management_required else R.string.no_subcategories)
            setPadding(24, 24, 24, 24)
        })
        options.forEach { category ->
            rows.addView(TextView(context, null, 0, R.style.Text).apply {
                text = category.name
                setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (selectedId == category.id) R.drawable.ic_check else 0, 0)
                tag = category.id
                textSize = 14f
                val inset = (16 * resources.displayMetrics.density).toInt()
                minHeight = (48 * resources.displayMetrics.density).toInt()
                setPadding(inset, inset, inset, inset)
                setBackgroundResource(if (selectedId == category.id) R.drawable.chip_active else R.drawable.chip)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = inset / 2 }
                isSelected = selectedId == category.id
                isClickable = true; isFocusable = true
                setOnClickListener {
                    if (category.parentId == null) { parentId = category.id; render() }
                    else confirm(category.id)
                }
            })
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            visibility = if (parent == null) View.GONE else View.VISIBLE
            setOnClickListener { parent?.let { confirm(it.id) } }
        }
        backButton.visibility = if (parent == null) View.GONE else View.VISIBLE
    }
    private fun back() {
        if (inline || parentId == null) dialog.dismiss()
        else { parentId = null; render() }
    }
    fun show() {
        dialog.showWithRoundedSurface()
        for (button in listOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE)) {
            dialog.getButton(button).apply {
                isAllCaps = false
                setSingleLine(false)
                minHeight = dp(48)
            }
        }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(context.getColor(R.color.muted))
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
            setBackgroundResource(R.drawable.primary)
            setTextColor(context.getColor(R.color.white))
            setPadding(dp(12), dp(10), dp(12), dp(10))
        }
        render()
        dialog.onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = back()
        })
    }
    fun dispose() = dialog.dismiss()
}

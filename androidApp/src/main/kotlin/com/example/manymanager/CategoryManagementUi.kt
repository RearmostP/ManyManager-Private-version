package com.example.simple.manymanager

import android.view.View
import android.widget.LinearLayout
import android.widget.ImageButton
import com.example.simple.manymanager.domain.Category
import android.widget.TextView

/** Displays prepared category state and submits actions; owns no mutable data list. */
class CategoryManagementUi(
    private val root: View,
    private val onBack: () -> Unit,
    private val onAdd: () -> Unit,
    private val onRetry: () -> Unit,
    private val onRename: (Category) -> Unit,
) {
    var parentId: String? = null
    private var state: CategoryListState = CategoryListState.Loading
    fun back(): Boolean {
        if (parentId == null) return false
        parentId = null; render(state); return true
    }
    init {
        root.findViewById<View>(R.id.categoryBack).setOnClickListener { if (!back()) onBack() }
        root.findViewById<View>(R.id.categoryAdd).setOnClickListener { onAdd() }
    }

    fun render(state: CategoryListState) {
        this.state = state
        val ready = (state as? CategoryListState.Ready)?.categories.orEmpty()
        val parent = ready.firstOrNull { it.id == parentId && it.parentId == null }
        root.findViewById<TextView>(R.id.categoryPageTitle).text = parent?.name ?: root.context.getString(R.string.expense_categories)
        root.findViewById<TextView>(R.id.categoryAdd).setText(if (parentId == null) R.string.add_category_action else R.string.add_subcategory)
        val status = root.findViewById<TextView>(R.id.categoriesEmpty)
        val message = if (state is CategoryListState.Ready && parentId != null && ready.none { it.parentId == parentId })
            R.string.no_subcategories else state.statusMessage()
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let { status.setText(it) }
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        status.setOnClickListener { onRetry() }
        status.isClickable = state is CategoryListState.Failed
        status.isFocusable = state is CategoryListState.Failed
        val rows = root.findViewById<LinearLayout>(R.id.categoryRows)
        rows.removeAllViews()
        if (state !is CategoryListState.Ready) return
        state.categories.filter { it.parentId == parentId }.forEach { category ->
            val density = root.resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val row = LinearLayout(root.context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.card)
                setPaddingRelative(dp(8), dp(4), dp(8), dp(4))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(16) }
            }
            row.addView(TextView(root.context, null, 0, R.style.Text).apply {
                tag = category.id
                if (parentId == null) {
                    isClickable = true; isFocusable = true
                    setOnClickListener { parentId = category.id; render(state) }
                    val ripple = android.util.TypedValue()
                    context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)
                    setBackgroundResource(ripple.resourceId)
                }
                text = category.name
                textSize = 16f
                minHeight = dp(48)
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPaddingRelative(dp(12), dp(16), dp(12), dp(16))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            row.addView(ImageButton(root.context).apply {
                tag = "rename:" + category.id
                setImageResource(R.drawable.ic_pencil)
                contentDescription = context.getString(
                    if (category.parentId == null) R.string.rename_category_label else R.string.rename_subcategory_label,
                    category.name)
                val ripple = android.util.TypedValue()
                context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)
                setBackgroundResource(ripple.resourceId)
                setPadding(dp(12), dp(12), dp(12), dp(12))
                layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
                setOnClickListener { onRename(category) }
            })
            rows.addView(row)
        }
    }
}

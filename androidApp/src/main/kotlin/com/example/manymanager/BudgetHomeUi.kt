package com.example.simple.manymanager

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.doOnPreDraw
import com.example.simple.manymanager.domain.BudgetSnapshot

/** Backward-compatible layout placeholder. */
class BudgetOverviewLayout @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : LinearLayout(context, attrs)

/** Inline budget breakdown anchored below Home's remaining budget row. */
class BudgetHomeUi(
    private val root: View,
    private val onToggleExpanded: () -> Unit,
    private val onExpandedChanged: (Boolean) -> Unit,
) {
    private val context = root.context
    private val area = root.findViewById<View>(R.id.homeBudgetArea)
    private val panel = root.findViewById<View>(R.id.budgetBreakdown)
    private val rows = root.findViewById<LinearLayout>(R.id.budgetBreakdownRows)
    private val chevron = root.findViewById<ImageView>(R.id.budgetChevron)
    private var isExpanded = false
    private var animator: ValueAnimator? = null
    private var pendingDraw: OneShotPreDrawListener? = null

    init {
        area.setOnClickListener { onToggleExpanded() }
    }

    private fun money(amount: Long): String = context.getString(
        R.string.ils_amount, BudgetAmounts.number(amount, context.resources.configuration.locales[0])
    )

    fun renderState(state: PersistentDataState, expanded: Boolean, onRetry: () -> Unit) {
        if (state is PersistentDataState.Ready) {
            area.setOnClickListener { onToggleExpanded() }
            render(state.snapshot, expanded)
            return
        }
        root.findViewById<TextView>(R.id.homeBudgetRemaining).setText(
            if (state is PersistentDataState.Failed) R.string.budget_load_error else R.string.budget_loading
        )
        root.findViewById<TextView>(R.id.homeBudgetTotal).text = "—"
        rows.removeAllViews()
        area.contentDescription = context.getString(
            if (state is PersistentDataState.Failed) R.string.budget_load_error else R.string.budget_loading
        )
        area.setOnClickListener { if (state is PersistentDataState.Failed) onRetry() }
        setExpanded(expanded, animate = false)
        area.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        ViewCompat.removeAccessibilityAction(area, AccessibilityNodeInfoCompat.ACTION_EXPAND)
        ViewCompat.removeAccessibilityAction(area, AccessibilityNodeInfoCompat.ACTION_COLLAPSE)
        ViewCompat.setStateDescription(area, null)
        area.isClickable = state is PersistentDataState.Failed
    }

    fun render(snapshot: BudgetSnapshot, expanded: Boolean) {
        val total = snapshot.totalAllocatedMinor
        val remaining = snapshot.remainingMinor
        val formattedRemaining = money(remaining)
        val formattedTotal = money(total)

        root.findViewById<TextView>(R.id.homeBudgetRemaining).text = formattedRemaining
        root.findViewById<TextView>(R.id.homeBudgetTotal).text = formattedTotal
        area.contentDescription = context.getString(
            R.string.budget_toggle_description, formattedRemaining, formattedTotal
        )

        rows.removeAllViews()
        if (snapshot.sources.isEmpty()) {
            rows.addView(TextView(context, null, 0, R.style.Text).apply {
                text = context.getString(R.string.no_budget_sources_yet)
                textSize = 14f
                setTextColor(context.getColor(R.color.muted))
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(12), 0, dp(6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
            })
        } else {
            snapshot.sources.forEach { sourceBudget ->
                rows.addView(HomeSummaryRow(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(8), 0, dp(8))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )

                    addView(TextView(context, null, 0, R.style.Text).apply {
                        text = sourceBudget.source.name
                        textSize = 14f
                        setTextColor(context.getColor(R.color.text))
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    addView(TextView(context, null, 0, R.style.Text).apply {
                        text = money(sourceBudget.spentMinor)
                        textSize = 14f
                        setTextColor(context.getColor(R.color.muted))
                        textDirection = View.TEXT_DIRECTION_LTR
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    })
                })
            }
        }
        setExpanded(expanded, animate = false)
    }

    fun setExpanded(expanded: Boolean, animate: Boolean = true) {
        animator?.removeAllListeners()
        animator?.cancel()
        animator = null
        pendingDraw?.removeListener()
        pendingDraw = null
        isExpanded = expanded

        chevron?.animate()?.cancel()
        val targetRotation = if (expanded) 180f else 0f
        if (animate && ValueAnimator.areAnimatorsEnabled()) {
            chevron?.animate()?.rotation(targetRotation)?.setDuration(200L)?.start()
        } else {
            chevron?.rotation = targetRotation
        }

        ViewCompat.setStateDescription(area, context.getString(
            if (expanded) R.string.budget_expanded else R.string.budget_collapsed
        ))
        val action = if (expanded) AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_COLLAPSE
            else AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_EXPAND
        ViewCompat.removeAccessibilityAction(area,
            if (expanded) AccessibilityNodeInfoCompat.ACTION_EXPAND else AccessibilityNodeInfoCompat.ACTION_COLLAPSE)
        ViewCompat.replaceAccessibilityAction(area, action, null) { _, _ ->
            onToggleExpanded()
            true
        }
        onExpandedChanged(expanded)

        if (!expanded && (!animate || !ValueAnimator.areAnimatorsEnabled())) {
            panel.visibility = View.GONE
            return
        }

        if ((panel.parent as View).width == 0) {
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) pendingDraw = root.doOnPreDraw {
                pendingDraw = null
                setExpanded(isExpanded, animate = false)
            }
            return
        }

        val start = if (panel.visibility == View.VISIBLE) panel.height else 0
        val parent = panel.parent as View
        val width = parent.width - parent.paddingLeft - parent.paddingRight
        panel.visibility = View.VISIBLE
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val target = if (expanded) panel.measuredHeight else 0
        if (!animate || !ValueAnimator.areAnimatorsEnabled()) {
            panel.layoutParams = panel.layoutParams.apply { height = target }
            panel.visibility = if (expanded) View.VISIBLE else View.GONE
            return
        }
        animator = ValueAnimator.ofInt(start, target).apply {
            duration = 180L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { panel.layoutParams = panel.layoutParams.apply { height = it.animatedValue as Int } }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!isExpanded) panel.visibility = View.GONE
                    animator = null
                }
            })
            start()
        }
    }

    fun dispose() {
        pendingDraw?.removeListener()
        pendingDraw = null
        animator?.removeAllListeners()
        animator?.removeAllUpdateListeners()
        animator?.cancel()
        animator = null
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
}

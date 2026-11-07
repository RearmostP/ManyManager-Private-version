package com.example.simple.manymanager

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Fill the available page on tall windows; keep natural content height when it must scroll. */
class HomePageLayout @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private var scroll: ScrollView? = null
    private val viewportChanged = OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateViewport() }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        var ancestor = parent
        while (ancestor != null && ancestor !is ScrollView) ancestor = ancestor.parent
        scroll = ancestor as? ScrollView
        scroll?.addOnLayoutChangeListener(viewportChanged)
        post { if (isAttachedToWindow) updateViewport() }
    }

    private fun updateViewport() {
        val viewport = scroll ?: return
        if (viewport.height == 0) return
        var insets = viewport.paddingTop + viewport.paddingBottom
        var ancestor = parent as? View
        while (ancestor != null && ancestor !== viewport) {
            insets += ancestor.paddingTop + ancestor.paddingBottom
            ancestor = ancestor.parent as? View
        }
        val available = (viewport.height - insets).coerceAtLeast(0)
        if (minimumHeight != available) minimumHeight = available
        val spacer = findViewById<View>(R.id.homeOrnamentSpace) ?: return
        val density = resources.displayMetrics.density
        val natural = (viewport.height * .30f).roundToInt().coerceIn((176 * density).roundToInt(), (280 * density).roundToInt())
        if (spacer.layoutParams.height != natural) spacer.layoutParams = spacer.layoutParams.apply { height = natural }
    }

    override fun onDetachedFromWindow() {
        scroll?.removeOnLayoutChangeListener(viewportChanged)
        scroll = null
        super.onDetachedFromWindow()
    }
}

/** Cap the ink at 320dp without stretching its 568:164 reference proportions. */
class HomeOrnamentView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    AppCompatImageView(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val cap = (320 * resources.displayMetrics.density).roundToInt()
        val available = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) cap
            else MeasureSpec.getSize(widthMeasureSpec)
        val inkWidth = min(available, cap)
        setMeasuredDimension(resolveSize(inkWidth, widthMeasureSpec),
            resolveSize((inkWidth * 164f / 568f).roundToInt(), heightMeasureSpec))
    }
}

/** Label + amount + optional indicator; wrap as a whole row before either amount is crowded out. */
class HomeSummaryRow @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    LinearLayout(context, attrs) {
    private val gap get() = (8 * resources.displayMetrics.density).roundToInt()
    private var stacked = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val available = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight).coerceAtLeast(0)
        val label = getChildAt(0)
        val amount = getChildAt(1)
        val indicator = if (childCount > 2) getChildAt(2) else null
        val freeHeight = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        indicator?.let { measureChild(it, widthMeasureSpec, freeHeight) }
        val indicatorWidth = indicator?.let { it.measuredWidth + gap } ?: 0
        amount.measure(MeasureSpec.makeMeasureSpec((available - indicatorWidth).coerceAtLeast(0), MeasureSpec.AT_MOST), freeHeight)
        label.measure(MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST), freeHeight)
        val labelMinimum = min(label.measuredWidth, (105 * resources.displayMetrics.density).roundToInt())
        val remaining = available - amount.measuredWidth - indicatorWidth - gap
        stacked = remaining < labelMinimum
        if (!stacked) label.measure(MeasureSpec.makeMeasureSpec(remaining.coerceAtLeast(0), MeasureSpec.EXACTLY), freeHeight)
        val valueHeight = max(amount.measuredHeight, indicator?.measuredHeight ?: 0)
        val contentHeight = if (stacked) label.measuredHeight + gap + valueHeight else max(label.measuredHeight, valueHeight)
        setMeasuredDimension(resolveSize(available + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(max(minimumHeight, contentHeight + paddingTop + paddingBottom), heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val label = getChildAt(0)
        val amount = getChildAt(1)
        val indicator = if (childCount > 2) getChildAt(2) else null
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        val valueHeight = max(amount.measuredHeight, indicator?.measuredHeight ?: 0)
        val contentHeight = height - paddingTop - paddingBottom
        val labelY = if (stacked) paddingTop else paddingTop + (contentHeight - label.measuredHeight) / 2
        val labelX = if (rtl) width - paddingRight - label.measuredWidth else paddingLeft
        label.layout(labelX, labelY, labelX + label.measuredWidth, labelY + label.measuredHeight)
        val valueY = if (stacked) labelY + label.measuredHeight + gap else paddingTop + (contentHeight - valueHeight) / 2
        val indicatorWidth = indicator?.let { it.measuredWidth + gap } ?: 0
        val amountX = if (stacked) {
            if (rtl) width - paddingRight - amount.measuredWidth else paddingLeft
        } else {
            if (rtl) paddingLeft + indicatorWidth else width - paddingRight - amount.measuredWidth - indicatorWidth
        }
        val amountY = valueY + (valueHeight - amount.measuredHeight) / 2
        amount.layout(amountX, amountY, amountX + amount.measuredWidth, amountY + amount.measuredHeight)
        indicator?.let {
            val x = if (rtl) amountX - gap - it.measuredWidth else amountX + amount.measuredWidth + gap
            val y = valueY + (valueHeight - it.measuredHeight) / 2
            it.layout(x, y, x + it.measuredWidth, y + it.measuredHeight)
        }
    }
}

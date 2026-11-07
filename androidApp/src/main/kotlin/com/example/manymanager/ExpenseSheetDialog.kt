package com.example.simple.manymanager

import android.animation.ValueAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.OneShotPreDrawListener
import androidx.core.view.doOnPreDraw
import androidx.core.widget.doAfterTextChanged
import com.example.simple.manymanager.domain.categoryPath
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.min

/** Slides only the expense sheet away, leaving the Activity beneath it in place. */
class ExpenseSheetDialog(
    activity: Activity, date: LocalDate,
    categories: CategoryListState, sources: SourceListState,
    draft: ExpenseDraft,
    private val onDraftChanged: (ExpenseDraft) -> Unit = {},
    private val onSubmit: () -> Unit = {},
    private val onRetryCategories: () -> Unit = {},
    private val onRetrySources: () -> Unit = {},
    onAddCategory: () -> Unit,
) : Dialog(activity) {
    var draft: ExpenseDraft = draft
        private set
    private var operation = ExpenseCreationState(draft)
    private var rendering = false
    private var datePicker: android.app.DatePickerDialog? = null
    private var categoryPicker: CategoryPickerDialog? = null
    private var categoryState: CategoryListState = CategoryListState.Loading
    private val resources get() = context.resources
    private val host: FrameLayout
    private var sheetView: View? = null
    private var openingDraw: OneShotPreDrawListener? = null
    var isDismissing = false
        private set

    init {
        val dialog = this
        val content = layoutInflater.inflate(R.layout.dialog_expense, null)
        content.findViewById<TextView>(R.id.expenseCalculatorLabel).text = activity.calculatorName()
        // The transparent window fills the screen; only the sheet wraps its content.
        // FrameLayout constrains long forms to the available height and anchors them below.
        host = FrameLayout(context).apply {
            layoutDirection = resources.configuration.layoutDirection
            addView(content, FrameLayout.LayoutParams(
                min(resources.displayMetrics.widthPixels, dp(440)),
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            ))
            setOnClickListener { dialog.dismiss() }
        }
        content.isClickable = true
        sheetView = content
        enableDrag(content)
        content.findViewById<View>(R.id.closeDialog).setOnClickListener { dialog.dismiss() }
        host.setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            val bottom = maxOf(safe.bottom, ime.bottom)
            val available = (activity.window.windowManager.currentWindowMetrics.bounds.height() -
                safe.top - bottom).coerceAtLeast(0)
            val topGap = (available * 0.08f).toInt()
            view.setPadding(safe.left, safe.top + topGap, safe.right, bottom)
            WindowInsets.CONSUMED
        }
        dialog.setContentView(host)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM)
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            attributes = attributes.apply {
                dimAmount = 0.38f
                windowAnimations = 0
            }
            setDecorFitsSystemWindows(false)
        }


        content.findViewById<EditText>(R.id.amount).apply {
            setText(draft.amount)
            doAfterTextChanged { if (!rendering) changeDraft(this@ExpenseSheetDialog.draft.copy(amount = it.toString())) }
        }
        content.findViewById<EditText>(R.id.expenseDate).apply {
            keyListener = null
            isFocusable = true
            setOnClickListener {
                if (operation.submitting || operation.interrupted) return@setOnClickListener
                val selected = this@ExpenseSheetDialog.draft.date ?: date
                datePicker = android.app.DatePickerDialog(context, { _, year, month, day ->
                    changeDraft(this@ExpenseSheetDialog.draft.copy(date = LocalDate.of(year, month + 1, day)))
                }, selected.year, selected.monthValue - 1, selected.dayOfMonth).also { it.show() }
            }
        }
        content.findViewById<EditText>(R.id.expenseNote).apply {
            setText(draft.note)
            doAfterTextChanged { if (!rendering) changeDraft(this@ExpenseSheetDialog.draft.copy(note = it.toString())) }
        }
        content.findViewById<View>(R.id.saveExpense).setOnClickListener { if (!isDismissing) onSubmit() }
        renderCategories(categories)
        renderSources(sources)
        content.findViewById<View>(R.id.expenseNewCategory).visibility = View.GONE
    }

    private fun changeDraft(value: ExpenseDraft) {
        draft = value
        onDraftChanged(value)
    }

    fun renderOperation(state: ExpenseCreationState, canSave: Boolean) {
        operation = state
        draft = state.draft
        val content = sheetView ?: return
        rendering = true
        content.findViewById<EditText>(R.id.amount).apply {
            if (text.toString() != draft.amount) setText(draft.amount)
        }
        content.findViewById<EditText>(R.id.expenseNote).apply {
            if (text.toString() != draft.note) setText(draft.note)
        }
        content.findViewById<EditText>(R.id.expenseDate).apply {
            setText(draft.date?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
                .withLocale(resources.configuration.locales[0])).orEmpty())
            contentDescription = context.getString(R.string.expense_date) + ": " + text
        }
        rendering = false
        renderCategories(categoryState)
        val parsed = BudgetAmounts.positive(draft.amount)
        val locked = state.submitting || state.interrupted
        listOf(R.id.amount, R.id.expenseNote, R.id.expenseDate, R.id.expenseNewCategory).forEach {
            content.findViewById<View>(it).isEnabled = !locked
        }
        for (id in listOf(R.id.expenseSourceChoices, R.id.expenseCategoryChoices)) {
            val choices = content.findViewById<LinearLayout>(id)
            for (index in 0 until choices.childCount) choices.getChildAt(index).isEnabled = !locked
        }
        content.findViewById<View>(R.id.closeDialog).isEnabled = !state.submitting
        setCancelable(!state.submitting)
        setCanceledOnTouchOutside(!state.submitting)
        content.findViewById<TextView>(R.id.saveExpense).apply {
            isEnabled = canSave
            alpha = if (canSave) 1f else 0.45f
            setText(if (state.submitting) R.string.expense_saving else R.string.save_expense)
        }
        content.findViewById<TextView>(R.id.expenseOperationStatus).apply {
            val message = when {
                state.interrupted -> R.string.expense_save_interrupted
                state.error != null -> expenseErrorMessage(state.error)
                draft.date == null -> R.string.expense_invalid_date
                draft.amount.isNotEmpty() && parsed is com.example.simple.manymanager.domain.DataResult.Failure ->
                    expenseErrorMessage(parsed.error)
                else -> null
            }
            visibility = if (message == null) View.GONE else View.VISIBLE
            message?.let { setText(it) }
        }
    }

    /** Refreshes only category choices, leaving every other draft field and its view intact. */
    fun renderCategories(state: CategoryListState) {
        categoryState = state
        val content = sheetView ?: return
        val status = content.findViewById<TextView>(R.id.expenseCategoriesEmpty)
        val message = if (state is CategoryListState.Ready && state.categories.none { it.parentId == null })
            R.string.category_management_required else state.statusMessage()
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let { status.setText(it) }
        status.setOnClickListener { onRetryCategories() }
        status.isClickable = state is CategoryListState.Failed
        status.isFocusable = state is CategoryListState.Failed
        status.accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        val container = content.findViewById<LinearLayout>(R.id.expenseCategoryChoices)
        container.removeAllViews()
        if (state is CategoryListState.Ready) {
            val selectedParent = state.categories.firstOrNull { it.id == draft.categoryId }?.let { it.parentId ?: it.id }
            state.categories.filter { it.parentId == null }.forEach { parent ->
                val selected = selectedParent == parent.id
                container.addView(TextView(context, null, 0, R.style.Text).apply {
                    text = if (selected) state.categories.categoryPath(draft.categoryId) else parent.name
                    setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, if (selected) R.drawable.ic_check else 0, 0)
                    compoundDrawablePadding = dp(10)
                    setTextColor(context.getColor(if (selected) R.color.blue else R.color.text))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = dp(8)
                    }
                    tag = parent.id
                    textSize = 14f; minHeight = dp(48)
                    setPadding(dp(14), dp(14), dp(14), dp(14))
                    setBackgroundResource(if (selected) R.drawable.chip_active else R.drawable.chip)
                    isSelected = selected; isClickable = true; isFocusable = true
                    setOnClickListener {
                        if (operation.submitting || operation.interrupted) return@setOnClickListener
                        categoryPicker?.dispose()
                        categoryPicker = CategoryPickerDialog(context, state.categories, draft.categoryId, parent.id) { id ->
                            changeDraft(draft.copy(categoryId = id))
                            renderCategories(categoryState)
                        }.also { it.show() }
                    }
                })
            }
        }
    }

    /** Refreshes only source choices, leaving every other draft field and its view intact. */
    fun renderSources(state: SourceListState) {
        val content = sheetView ?: return
        content.findViewById<TextView>(R.id.expensePaymentMethodsEmpty).apply {
            val message = state.statusMessage()
            visibility = if (message == null) View.GONE else View.VISIBLE
            message?.let { setText(it) }
            setOnClickListener { onRetrySources() }
            isClickable = state is SourceListState.Failed
            isFocusable = isClickable
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        // Loading/failure never clears the selected ID or any draft field.
        val container = content.findViewById<LinearLayout>(R.id.expenseSourceChoices)
        container.removeAllViews()
        if (state is SourceListState.Ready) {
            bindChoices(container, state.sources.map { it.id to it.name }, true)
        }
    }

    fun selectCategory(id: String, state: CategoryListState) {
        changeDraft(this@ExpenseSheetDialog.draft.copy(categoryId = id))
        // The creation result may arrive before the list emission. Keep the ID in that race.
        renderCategories(state)
    }

    private fun bindChoices(
        container: LinearLayout, options: List<Pair<String?, String>>, sourceChoices: Boolean
    ) {
        options.forEach { (id, name) ->
            container.addView(TextView(context, null, 0, R.style.Text).apply {
                text = name
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) }
                tag = id
                textSize = 14f
                minHeight = dp(48)
                setPadding(dp(14), dp(14), dp(14), dp(14))
                val active = (if (sourceChoices) draft.sourceId else draft.categoryId) == id
                setBackgroundResource(if (active) R.drawable.chip_active else R.drawable.chip)
                isSelected = active
                setTextColor(context.getColor(if (active) R.color.blue else R.color.text))
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (operation.submitting || operation.interrupted) return@setOnClickListener
                    changeDraft(if (sourceChoices) draft.copy(sourceId = id) else draft.copy(categoryId = id))
                    for (index in 0 until container.childCount) {
                        (container.getChildAt(index) as TextView).apply {
                            val selected = tag == id
                            isSelected = selected
                            setBackgroundResource(if (selected) R.drawable.chip_active else R.drawable.chip)
                            setTextColor(context.getColor(if (selected) R.color.blue else R.color.text))
                        }
                    }
                }
            })
        }
    }

    private fun enableDrag(content: View) {
        val handle = content.findViewById<View>(R.id.expenseDragHandle) ?: return
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var startY = 0f
        var dragging = false
        handle.setOnTouchListener { view, event ->
            if (isDismissing || operation.submitting) return@setOnTouchListener true
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    content.animate().cancel()
                    startY = event.rawY - content.translationY
                    dragging = false
                    view.parent.requestDisallowInterceptTouchEvent(true)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val distance = (event.rawY - startY).coerceAtLeast(0f)
                    if (distance > slop) dragging = true
                    if (dragging) content.translationY = distance
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.parent.requestDisallowInterceptTouchEvent(false)
                    if (event.actionMasked == MotionEvent.ACTION_UP &&
                        dragging && content.translationY >= dp(64)
                    ) {
                        dismiss()
                    } else {
                        content.animate().translationY(0f).setDuration(180).start()
                        if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) {
                            view.performClick()
                        }
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun show() {
        val alreadyShowing = isShowing
        super.show()
        window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        host.requestApplyInsets()
        val sheet = sheetView ?: return
        if (alreadyShowing || !ValueAnimator.areAnimatorsEnabled()) return
        // Start below the viewport, then animate only after layout and insets are ready.
        sheet.translationY = context.resources.displayMetrics.heightPixels.toFloat()
        openingDraw = sheet.doOnPreDraw {
            openingDraw = null
            if (isShowing && !isDismissing) {
                sheet.animate()
                    .translationY(0f)
                    .setDuration(320L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
        }
    }

    override fun dismiss() {
        if (operation.submitting) return
        if (isDismissing) return
        val sheet = sheetView
        if (!isShowing || sheet == null || !sheet.isAttachedToWindow ||
            !ValueAnimator.areAnimatorsEnabled()
        ) {
            dismissImmediately()
            return
        }
        isDismissing = true
        val parentHeight = (sheet.parent as? View)?.height ?: sheet.height
        // Include the bottom inset, so no part of the sheet remains in the window.
        val offscreen = (parentHeight - sheet.top).toFloat()
        sheet.animate().cancel()
        sheet.animate()
            .translationY(offscreen)
            .setDuration(220L)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction { dismissImmediately() }
            .start()
    }

    /** Used when the Activity replaces its UI or is destroyed; never leave a window behind. */
    fun dismissImmediately() {
        categoryPicker?.dispose(); categoryPicker = null
        datePicker?.dismiss()
        datePicker = null
        openingDraw?.removeListener()
        openingDraw = null
        sheetView?.animate()?.cancel()
        super.dismiss()
        sheetView = null
        isDismissing = false
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

package com.example.simple.manymanager

import android.app.DatePickerDialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.core.widget.doAfterTextChanged
import com.example.simple.manymanager.domain.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Saved details and in-place controls share one window. Never based on the Add Expense sheet. */
class ExpenseDetailsDialog(
    private val activity: android.content.Context,
    private val onEdit: () -> Unit,
    private val onDraft: (ExpenseDraft) -> Unit,
    private val onSave: () -> Unit,
    private val onCancelEdit: () -> Unit,
    private val onClose: () -> Unit,
    private val onRetry: () -> Unit,
    private val onDelete: () -> Unit,
    private val onConfirmDelete: () -> Unit,
    private val onCancelDelete: () -> Unit,
    private val onNewCategory: () -> Unit,
) {
    private fun dp(value: Int) = (value * activity.resources.displayMetrics.density).toInt()
    private var state: ExpenseCreationState? = null
    private var rendering = false
    private var datePicker: DatePickerDialog? = null
    private var confirmation: AlertDialog? = null
    private var categoryNames: Map<String, String> = emptyMap()
    private var categoryOptions: List<Category> = emptyList()
    private var categoryPicker: CategoryPickerDialog? = null
    private var sourceNames: Map<String, String> = emptyMap()

    private fun text(id: Int = View.NO_ID, size: Float = 16f) = TextView(activity).apply {
        this.id = id
        textSize = size
        setTextColor(activity.getColor(R.color.text))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    private fun action(label: Int, id: Int, primary: Boolean = false) = text(id, 14f).apply {
        setText(label); gravity = Gravity.CENTER; minHeight = dp(48)
        setPadding(dp(10), dp(10), dp(10), dp(10))
        isClickable = true; isFocusable = true
        setBackgroundResource(if (primary) R.drawable.primary else R.drawable.button_secondary)
        if (primary) setTextColor(activity.getColor(R.color.white))
    }
    private val root = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = activity.resources.configuration.layoutDirection
        setPadding(dp(20), dp(16), dp(20), dp(16))
    }
    private val header = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val close = action(R.string.close, R.id.closeExpenseDetails)
    private val title = text(R.id.expenseDetailsTitle, 22f).apply {
        text = activity.calculatorTitle(activity.getString(R.string.expense_details))
        setTypeface(null, android.graphics.Typeface.BOLD)
        isAccessibilityHeading = true
    }
    private val fields = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val body = object : ScrollView(activity) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val cap = (activity.resources.displayMetrics.heightPixels * .58f).toInt()
            val available = if (View.MeasureSpec.getMode(heightMeasureSpec) == View.MeasureSpec.UNSPECIFIED) cap
                else minOf(cap, View.MeasureSpec.getSize(heightMeasureSpec))
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(available, View.MeasureSpec.AT_MOST))
        }
    }.apply { isFillViewport = false; addView(fields) }
    private data class Field(val container: LinearLayout, val label: TextView, val value: TextView, val control: View)
    private fun field(label: Int, valueId: Int, control: View): Field {
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
        }
        val caption = text(size = 12f).apply { setText(label); setTextColor(activity.getColor(R.color.muted)); labelFor = control.id }
        val value = text(valueId).apply { setPadding(0, dp(4), 0, dp(4)); setTextIsSelectable(true) }
        container.addView(caption); container.addView(value); container.addView(control)
        fields.addView(container)
        return Field(container, caption, value, control)
    }
    private fun input(id: Int, numeric: Boolean = false) = EditText(activity).apply {
        this.id = id; textSize = 16f; minHeight = dp(48)
        setTextColor(activity.getColor(R.color.text)); setHintTextColor(activity.getColor(R.color.muted))
        inputType = if (numeric) android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL
            else android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
        if (numeric) { setSingleLine(); textDirection = View.TEXT_DIRECTION_LTR }
        setPadding(dp(10), dp(10), dp(10), dp(10))
        setBackgroundResource(R.drawable.chip)
    }
    private val amount = input(R.id.expenseDetailsAmount, true)
    private val date = action(R.string.expense_date, R.id.expenseDetailsDate)
    private val category = action(R.string.choose_category, R.id.expenseDetailsCategory).apply {
        setOnClickListener {
            if (canInteract()) {
                categoryPicker?.dispose()
                categoryPicker = CategoryPickerDialog(activity, categoryOptions, state?.draft?.categoryId) { id ->
                    state?.draft?.let { onDraft(it.copy(categoryId = id)) }
                }.also { it.show() }
            }
        }
    }
    private val source = Spinner(activity).apply { id = R.id.expenseDetailsSource; minimumHeight = dp(48) }
    private val note = input(R.id.expenseDetailsNote)
    private val amountField = field(R.string.expense_amount, R.id.expenseDetailsAmountValue, amount)
    private val dateField = field(R.string.expense_date, R.id.expenseDetailsDateValue, date)
    private val categoryField = field(R.string.expense_category, R.id.expenseDetailsCategoryValue, category)
    private val sourceField = field(R.string.payment_method, R.id.expenseDetailsSourceValue, source)
    private val newCategory = text(R.id.expenseDetailsNewCategory, 14f).apply {
        setText(R.string.new_category); setTextColor(activity.getColor(R.color.blue))
        minHeight = dp(48); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
        setOnClickListener { if (canInteract()) onNewCategory() }
    }
    private val noteField: Field
    private val status = text(R.id.expenseDetailsStatus, 13f).apply {
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        minHeight = dp(48)
        setOnClickListener { if (state?.submitting != true && state?.interrupted != true) onRetry() }
    }
    private val edit = action(R.string.edit, R.id.editExpenseDetails, true)
    private val save = action(R.string.save_expense_changes, R.id.saveExpenseDetails, true)
    private val cancel = action(R.string.cancel, R.id.cancelExpenseDetails)
    private val delete = action(R.string.delete_expense, R.id.deleteExpenseDetails).apply {
        background = null; setTextColor(activity.getColor(R.color.expense_danger))
    }
    private val editActions = LinearLayout(activity).apply {
        id = R.id.expenseDetailsActions
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        // Children follow locale direction once: primary group at start, destructive action at end.
        addView(LinearLayout(activity).apply {
            id = R.id.expenseDetailsPrimaryActions
            orientation = LinearLayout.HORIZONTAL
            addView(save, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(cancel, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(6) })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 2f))
        addView(Space(activity), LinearLayout.LayoutParams(dp(12), 1))
        addView(delete, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }
    val dialog: AlertDialog = AlertDialog.Builder(activity).create()

    init {
        // Category creation is available only in Category Management.
        noteField = field(R.string.optional_note, R.id.expenseDetailsNoteValue, note)
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(close, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(header); root.addView(body); root.addView(status); root.addView(edit); root.addView(editActions)
        dialog.setView(root)
        dialog.setCancelable(false)
        dialog.setCanceledOnTouchOutside(false)
        amount.doAfterTextChanged { if (!rendering && canInteract()) state?.draft?.let { draft -> onDraft(draft.copy(amount = it.toString())) } }
        note.doAfterTextChanged { if (!rendering && canInteract()) state?.draft?.let { draft -> onDraft(draft.copy(note = it.toString())) } }
        date.setOnClickListener {
            if (!canInteract()) return@setOnClickListener
            val selected = state?.draft?.date ?: return@setOnClickListener
            datePicker?.dismiss()
            datePicker = DatePickerDialog(activity, { _, y, m, d ->
                state?.draft?.let { onDraft(it.copy(date = LocalDate.of(y, m + 1, d))) }
            }, selected.year, selected.monthValue - 1, selected.dayOfMonth).also { it.show() }
        }
        edit.setOnClickListener { onEdit() }
        save.setOnClickListener { if (canInteract()) onSave() }
        cancel.setOnClickListener { if (state?.submitting != true) onCancelEdit() }
        delete.setOnClickListener { if (canInteract()) onDelete() }
        close.setOnClickListener { back() }
    }
    private fun canInteract() = state?.let { it.editing && !it.submitting && !it.loading && !it.interrupted && !it.confirmingDelete } == true
    private fun back() {
        val value = state ?: return
        if (value.submitting) return
        if (value.confirmingDelete) onCancelDelete()
        else if (value.editing && !value.interrupted) onCancelEdit() else onClose()
    }
    fun show() {
        dialog.showWithRoundedSurface()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog.onBackPressedDispatcher.addCallback(object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { back() }
        })
    }
    private data class Choice(val id: String?, val name: String) { override fun toString() = name }
    private fun bindChoices(spinner: Spinner, options: List<Choice>, selected: String?, select: (String?) -> Unit) {
        val items = if (options.any { it.id == selected }) options else options + Choice(selected, activity.getString(R.string.expense_missing_choice))
        val existing = (0 until (spinner.adapter?.count ?: 0)).map { spinner.adapter.getItem(it) }
        if (existing != items) spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_item, items).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        spinner.setSelection(items.indexOfFirst { it.id == selected }.coerceAtLeast(0), false)
        spinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) {}
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                if (!rendering && canInteract() && parent?.selectedItemPosition == position) {
                    val choice = parent.selectedItem as? Choice ?: return
                    if (choice.id != (state?.draft?.sourceId)) select(choice.id)
                }
            }
        }
    }
    fun render(value: ExpenseCreationState, categories: CategoryListState, sources: SourceListState, canSave: Boolean) {
        state = value
        if (categories is CategoryListState.Ready) {
            categoryOptions = categories.categories
            categoryNames = categories.categories.associate { it.id to (categoryOptions.categoryPath(it.id) ?: it.name) }
        }
        if (sources is SourceListState.Ready) sourceNames = sources.sources.associate { it.id to it.name }
        val saved = value.original
        val formatter = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(activity.resources.configuration.locales[0])
        amountField.value.text = saved?.let { activity.getString(R.string.ils_amount, BudgetAmounts.number(it.amountMinor, activity.resources.configuration.locales[0])) } ?: "—"
        dateField.value.text = saved?.date?.format(formatter) ?: "—"
        categoryField.value.text = if (saved?.categoryId == null) activity.getString(R.string.uncategorized)
            else categoryNames[saved.categoryId] ?: activity.getString(R.string.expense_missing_choice)
        sourceField.value.text = sourceNames[saved?.sourceId] ?: "—"
        noteField.value.text = saved?.note.orEmpty()
        val editing = value.editing && saved != null
        listOf(amountField, dateField, categoryField, sourceField, noteField).forEach {
            it.value.visibility = if (editing) View.GONE else View.VISIBLE
            it.control.visibility = if (editing) View.VISIBLE else View.GONE
            it.control.isEnabled = canInteract()
        }
        fields.visibility = if (saved == null) View.GONE else View.VISIBLE
        noteField.container.visibility = if (editing || !saved?.note.isNullOrBlank()) View.VISIBLE else View.GONE
        newCategory.visibility = View.GONE
        newCategory.isEnabled = canInteract()
        rendering = true
        if (amount.text.toString() != value.draft.amount) amount.setText(value.draft.amount)
        if (note.text.toString() != value.draft.note) note.setText(value.draft.note)
        date.text = value.draft.date?.format(formatter).orEmpty()
        category.text = categoryNames[value.draft.categoryId] ?: activity.getString(R.string.choose_category)
        bindChoices(source, sourceNames.map { Choice(it.key, it.value) }, value.draft.sourceId) { id -> state?.draft?.let { onDraft(it.copy(sourceId = id)) } }
        rendering = false
        edit.visibility = if (editing || saved == null) View.GONE else View.VISIBLE
        edit.isEnabled = !value.loading && !value.interrupted && value.error == null
        editActions.visibility = if (editing) View.VISIBLE else View.GONE
        save.isEnabled = canSave
        cancel.isEnabled = !value.submitting
        delete.isEnabled = canInteract()
        close.isEnabled = !value.submitting
        val parsed = BudgetAmounts.positive(value.draft.amount)
        val message = when {
            value.interrupted -> R.string.expense_save_interrupted
            value.loading -> R.string.expense_edit_loading
            value.error != null -> expenseErrorMessage(value.error)
            editing && categoryOptions.none { it.parentId == null } -> R.string.category_management_required
            editing && value.draft.categoryId == null -> R.string.choose_category
            editing && parsed is DataResult.Failure -> expenseErrorMessage(parsed.error)
            editing && (sources is SourceListState.Failed || categories is CategoryListState.Failed) -> R.string.expense_edit_retry
            else -> null
        }
        status.visibility = if (message == null) View.GONE else View.VISIBLE
        message?.let(status::setText)
        status.isClickable = !value.submitting && !value.interrupted && !value.loading
        status.isFocusable = status.isClickable
        renderConfirmation(value)
    }
    private fun renderConfirmation(value: ExpenseCreationState) {
        if (!value.confirmingDelete || value.original == null || value.interrupted) {
            confirmation?.setOnCancelListener(null); confirmation?.dismiss(); confirmation = null
            return
        }
        if (confirmation == null) {
            val saved = value.original
            val details = listOf(amountField.value.text.toString(), dateField.value.text.toString(),
                sourceNames[saved.sourceId].orEmpty(), saved.note.orEmpty()).filter(String::isNotBlank).joinToString(" · ")
            confirmation = AlertDialog.Builder(activity).setTitle(R.string.delete_expense_question)
                .setMessage(activity.getString(R.string.delete_expense_details) + "\n\n" + details)
                .setNegativeButton(R.string.cancel, null).setPositiveButton(R.string.delete_expense, null).create().also {
                    it.setOnCancelListener { onCancelDelete() }; it.showWithRoundedSurface()
                    it.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { onCancelDelete() }
                    it.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                        setTextColor(activity.getColor(R.color.expense_danger))
                        contentDescription = activity.getString(R.string.delete_expense)
                        setOnClickListener { onConfirmDelete() }
                    }
                }
        }
        confirmation?.apply {
            setCancelable(!value.submitting); setCanceledOnTouchOutside(!value.submitting)
            getButton(AlertDialog.BUTTON_NEGATIVE).isEnabled = !value.submitting
            getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = !value.submitting
        }
    }
    fun dispose() {
        categoryPicker?.dispose(); categoryPicker = null
        confirmation?.setOnCancelListener(null); confirmation?.dismiss(); confirmation = null
        datePicker?.dismiss(); datePicker = null
        dialog.dismiss()
    }
}

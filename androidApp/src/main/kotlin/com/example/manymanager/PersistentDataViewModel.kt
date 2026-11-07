package com.example.simple.manymanager

import android.os.Bundle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.simple.manymanager.data.ManyManagerRepository
import com.example.simple.manymanager.domain.*
import java.time.LocalDate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface PersistentDataState {
    data object Loading : PersistentDataState
    data class Ready(val snapshot: BudgetSnapshot) : PersistentDataState
    data class Failed(val error: DataError) : PersistentDataState
}

data class SourceCreationState(
    val period: BudgetPeriod,
    val name: String = "",
    val submitting: Boolean = false,
    val result: DataResult<BudgetSource>? = null,
    val interrupted: Boolean = false,
) {
    fun toBundle() = Bundle().apply {
        putLong("startEpochDay", period.startEpochDay)
        putLong("endExclusiveEpochDay", period.endExclusiveEpochDay)
        putString("name", name)
        putBoolean("interrupted", submitting || interrupted)
        (result as? DataResult.Success)?.value?.let { putString("committedId", it.id); putString("owner", it.calculatorId) }
    }
    companion object {
        fun fromBundle(bundle: Bundle): SourceCreationState? {
            if (!bundle.containsKey("startEpochDay")) return null
            val name = bundle.getString("name").orEmpty()
            return SourceCreationState(
                BudgetPeriod(LocalDate.ofEpochDay(bundle.getLong("startEpochDay")), LocalDate.ofEpochDay(bundle.getLong("endExclusiveEpochDay"))),
                name, result = bundle.getString("committedId")?.let { DataResult.Success(BudgetSource(bundle.getString("owner").orEmpty(), it, name)) },
                interrupted = bundle.getBoolean("interrupted") || bundle.getBoolean("submitting"))
        }
    }
}

data class SourceEditingState(
    val source: BudgetSource,
    val period: BudgetPeriod,
    val name: String = source.name,
    val submitting: Boolean = false,
    val submitResult: DataResult<BudgetSource>? = null,
    val interrupted: Boolean = false,
) {
    fun toBundle() = Bundle().apply {
        putString("sourceId", source.id); putString("sourceOwner", source.calculatorId)
        putString("sourceName", source.name); putString("name", name)
        putLong("startEpochDay", period.startEpochDay); putLong("endExclusiveEpochDay", period.endExclusiveEpochDay)
        putBoolean("committed", submitResult is DataResult.Success)
        putBoolean("interrupted", submitting || interrupted)
    }
    companion object {
        fun fromBundle(bundle: Bundle): SourceEditingState? {
            val id = bundle.getString("sourceId") ?: return null
            val source = BudgetSource(bundle.getString("sourceOwner").orEmpty(), id, bundle.getString("sourceName").orEmpty())
            val name = bundle.getString("name").orEmpty()
            return SourceEditingState(source,
                BudgetPeriod(LocalDate.ofEpochDay(bundle.getLong("startEpochDay")), LocalDate.ofEpochDay(bundle.getLong("endExclusiveEpochDay"))),
                name, submitResult = if (bundle.getBoolean("committed")) DataResult.Success(source.copy(name = name)) else null,
                interrupted = bundle.getBoolean("interrupted") || bundle.getBoolean("submitting"))
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PersistentDataViewModel(private val repository: ManyManagerRepository) : ViewModel() {
    private data class Selection(val period: BudgetPeriod? = null, val refresh: Long = 0)
    private val selection = MutableStateFlow(Selection())

    val state: StateFlow<PersistentDataState> = selection.flatMapLatest { selected ->
        repository.observeSnapshot(selected.period).map { result ->
            when (result) {
                is DataResult.Success -> PersistentDataState.Ready(result.value)
                is DataResult.Failure -> PersistentDataState.Failed(result.error)
            }
        }.onStart { emit(PersistentDataState.Loading) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersistentDataState.Loading)

    // Home always follows today; management may retain an explicit historical selection.
    private val activeRefresh = MutableStateFlow(0L)
    val activeBudget: StateFlow<PersistentDataState> = activeRefresh.flatMapLatest {
        repository.observeSnapshot().map { result ->
            when (result) {
                is DataResult.Success -> PersistentDataState.Ready(result.value)
                is DataResult.Failure -> PersistentDataState.Failed(result.error)
            }
        }.onStart { emit(PersistentDataState.Loading) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PersistentDataState.Loading)

    private val sourceRefresh = MutableStateFlow(0L)
    val sources: StateFlow<SourceListState> = sourceRefresh.flatMapLatest {
        repository.observeSources().map { result ->
            when (result) {
                is DataResult.Success -> SourceListState.Ready(result.value)
                is DataResult.Failure -> SourceListState.Failed(result.error)
            }
        }.onStart { emit(SourceListState.Loading) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SourceListState.Loading)

    fun retrySources() { sourceRefresh.update { it + 1 } }

    private val categoryRefresh = MutableStateFlow(0L)
    val categories: StateFlow<CategoryListState> = categoryRefresh.flatMapLatest {
        repository.observeCategories().map { result ->
            when (result) {
                is DataResult.Success -> CategoryListState.Ready(result.value)
                is DataResult.Failure -> CategoryListState.Failed(result.error)
            }
        }.onStart { emit(CategoryListState.Loading) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CategoryListState.Loading)

    private val creation = MutableStateFlow<CategoryCreationState?>(null)
    val categoryCreation: StateFlow<CategoryCreationState?> = creation

    private val sourceCreation = MutableStateFlow<SourceCreationState?>(null)
    val sourceCreationState: StateFlow<SourceCreationState?> = sourceCreation

    private val sourceEditing = MutableStateFlow<SourceEditingState?>(null)
    val sourceEditingState: StateFlow<SourceEditingState?> = sourceEditing

    fun retryCategories() { categoryRefresh.update { it + 1 } }

    fun beginCategoryCreation(fromExpense: Boolean, parentId: String? = null) {
        if (creation.value == null) creation.value = CategoryCreationState(fromExpense, parentId = parentId)
    }

    fun beginCategoryRename(category: com.example.simple.manymanager.domain.Category) {
        if (creation.value == null) creation.value = CategoryCreationState(
            fromExpense = false, parentId = category.parentId, name = category.name, categoryId = category.id)
    }

    fun restoreCategoryCreation(restored: CategoryCreationState) {
        if (creation.value == null) creation.value = restored
    }

    fun updateCategoryName(name: String) {
        creation.update { current ->
            if (current == null || current.submitting || current.result is DataResult.Success || current.name == name) current
            else current.copy(name = name, result = null)
        }
    }

    fun cancelCategoryCreation() {
        creation.update { current ->
            if (current?.submitting == true || current?.result is DataResult.Success) current else null
        }
    }

    fun acknowledgeCategoryCreation() {
        if (creation.value?.result is DataResult.Success) creation.value = null
    }

    fun submitCategoryCreation(): Job? {
        val current = creation.value ?: return null
        if (current.submitting || current.interrupted || current.result is DataResult.Success) return null
        creation.value = current.copy(submitting = true, result = null, interrupted = false)
        return viewModelScope.launch {
            val result = if (current.categoryId == null) repository.createCategory(current.name, current.parentId)
                else repository.renameCategory(current.categoryId, current.name)
            currentCoroutineContext().ensureActive()
            creation.value = current.copy(result = result, interrupted = false)
        }
    }

    fun beginSourceCreation(period: BudgetPeriod) {
        if (sourceCreation.value == null) {
            sourceCreation.value = SourceCreationState(period = period)
        }
    }

    fun restoreSourceCreation(restored: SourceCreationState) {
        if (sourceCreation.value == null) sourceCreation.value = restored
    }

    fun updateSourceCreationName(name: String) {
        sourceCreation.update { current ->
            if (current == null || current.submitting || current.interrupted || current.result is DataResult.Success || current.name == name) current
            else current.copy(name = name, result = null)
        }
    }

    fun cancelSourceCreation() {
        sourceCreation.value?.takeIf { it.interrupted }?.let {
            selectPeriod(it.period)
            refresh()
        }
        sourceCreation.update { current ->
            if (current?.submitting == true || current?.result is DataResult.Success) current else null
        }
    }

    fun acknowledgeSourceCreation() {
        if (sourceCreation.value?.result is DataResult.Success) sourceCreation.value = null
    }

    fun submitSourceCreation(): Job? {
        val current = sourceCreation.value ?: return null
        if (current.submitting || current.interrupted || current.result is DataResult.Success) return null
        sourceCreation.value = current.copy(submitting = true, result = null, interrupted = false)
        return viewModelScope.launch {
            val result = repository.createPaymentMethod(current.name)
            currentCoroutineContext().ensureActive()
            sourceCreation.value = current.copy(result = result, interrupted = false)
        }
    }

    fun beginSourceEditing(source: BudgetSource, period: BudgetPeriod) {
        if (sourceEditing.value == null) sourceEditing.value = SourceEditingState(source, period)
    }
    fun restoreSourceEditing(restored: SourceEditingState) {
        if (sourceEditing.value == null) sourceEditing.value = restored
    }

    fun updateSourceEditingName(name: String) {
        sourceEditing.update { current ->
            if (current == null || current.submitting || current.interrupted || current.submitResult is DataResult.Success || current.name == name) current
            else current.copy(name = name, submitResult = null)
        }
    }

    fun cancelSourceEditing() {
        sourceEditing.update { if (it?.submitting == true || it?.submitResult is DataResult.Success) it else null }
    }

    fun acknowledgeSourceEditing() {
        if (sourceEditing.value?.submitResult is DataResult.Success) sourceEditing.value = null
    }

    fun submitSourceEditing(): Job? {
        val current = sourceEditing.value ?: return null
        if (current.submitting || current.interrupted || current.submitResult is DataResult.Success) return null
        if (current.name.isBlank()) return null
        sourceEditing.value = current.copy(submitting = true, submitResult = null, interrupted = false)
        return viewModelScope.launch {
            val result = repository.renameSource(current.source.id, current.name)
            currentCoroutineContext().ensureActive()
            sourceEditing.value = current.copy(submitResult = result, interrupted = false)
        }
    }

    private val expenseCreation = MutableStateFlow<ExpenseCreationState?>(null)
    val expenseCreationState: StateFlow<ExpenseCreationState?> = expenseCreation

    private var expenseLoad: Job? = null

    fun openExpenseDetails(id: String) {
        if (expenseCreation.value != null) return
        expenseCreation.value = ExpenseCreationState(ExpenseDraft(), expenseId = id, loading = true)
        loadExpenseForEditing(id)
    }
    fun retryExpenseLoading() {
        val current = expenseCreation.value ?: return
        if (current.expenseId != null && !current.interrupted && !current.submitting)
            loadExpenseForEditing(current.expenseId)
    }
    private fun loadExpenseForEditing(id: String) {
        expenseLoad?.cancel()
        expenseCreation.update { it?.copy(loading = it.original == null, error = null) }
        expenseLoad = viewModelScope.launch {
            repository.observeExpense(id).collect { result ->
                currentCoroutineContext().ensureActive()
                expenseCreation.update { current ->
                    if (current?.expenseId != id || current.submitting || current.saved || current.interrupted) current
                    else when (result) {
                        is DataResult.Success -> current.copy(
                            draft = if (current.editing) current.draft else ExpenseDraft.fromExpense(result.value),
                            original = result.value, loading = false, error = if (current.editing) current.error else null)
                        is DataResult.Failure -> if (result.error is DataError.MissingEntity)
                            current.copy(original = null, loading = false, editing = false, confirmingDelete = false, error = result.error)
                        else current.copy(loading = false, error = result.error)
                    }
                }
            }
        }
    }

    fun beginExpenseCreation(date: LocalDate) {
        if (expenseCreation.value == null) expenseCreation.value = ExpenseCreationState(ExpenseDraft(date = date))
    }
    fun restoreExpenseCreation(state: ExpenseCreationState) {
        if (expenseCreation.value == null) {
            expenseCreation.value = state
            if (state.expenseId != null && !state.interrupted && !state.saved)
                loadExpenseForEditing(state.expenseId)
        }
    }
    fun updateExpenseDraft(draft: ExpenseDraft) {
        expenseCreation.update {
            if (it == null || it.submitting || it.saved || it.interrupted || it.loading || it.confirmingDelete ||
                (it.expenseId != null && !it.editing)) it
            else it.copy(draft = draft, error = null)
        }
    }
    fun editExpense() {
        expenseCreation.update {
            if (it?.original == null || it.submitting || it.loading || it.interrupted || it.error != null) it
            else it.copy(editing = true, draft = ExpenseDraft.fromExpense(it.original), error = null)
        }
    }
    fun cancelExpenseEdit() {
        val current = expenseCreation.value ?: return
        if (current.submitting) return
        if (current.interrupted) { cancelExpenseCreation(); return }
        expenseCreation.value = current.copy(editing = false, confirmingDelete = false, error = null,
            draft = current.original?.let(ExpenseDraft::fromExpense) ?: current.draft)
        current.expenseId?.let(::loadExpenseForEditing)
    }
    fun selectExpenseCategory(id: String) {
        expenseCreation.value?.let { updateExpenseDraft(it.draft.copy(categoryId = id)) }
    }
    fun cancelExpenseCreation() {
        if (expenseCreation.value?.submitting != true) expenseLoad?.cancel()
        expenseCreation.update { if (it?.submitting == true || it?.saved == true) it else null }
    }
    fun acknowledgeExpenseCreation() {
        if (expenseCreation.value?.saved == true) { expenseLoad?.cancel(); expenseCreation.value = null }
    }
    fun canSaveExpense(): Boolean {
        val state = expenseCreation.value ?: return false
        val draft = state.draft
        return !state.submitting && !state.saved && !state.interrupted && !state.loading && !state.confirmingDelete &&
            (state.expenseId == null || (state.original != null && state.editing)) && draft.date != null &&
            BudgetAmounts.positive(draft.amount) is DataResult.Success &&
            (sources.value as? SourceListState.Ready)?.sources?.any { it.id == draft.sourceId } == true &&
            draft.categoryId != null &&
            (categories.value as? CategoryListState.Ready)?.categories?.any { it.id == draft.categoryId } == true
    }
    fun submitExpenseCreation(): Job? {
        val current = expenseCreation.value ?: return null
        if (current.submitting || current.saved || current.interrupted || current.loading || current.confirmingDelete ||
            (current.expenseId != null && !current.editing)) return null
        val amount = BudgetAmounts.positive(current.draft.amount)
        val error = when {
            amount is DataResult.Failure -> amount.error
            current.draft.date == null -> DataError.InvalidDate
            current.draft.sourceId == null -> DataError.MissingEntity(EntityKind.SOURCE, "")
            current.draft.categoryId == null -> DataError.MissingEntity(EntityKind.CATEGORY, "")
            else -> null
        }
        if (error != null) { expenseCreation.value = current.copy(error = error); return null }
        if (!canSaveExpense()) return null
        val input = NewExpense((amount as DataResult.Success).value, current.draft.date!!,
            current.draft.sourceId!!, current.draft.categoryId, current.draft.note)
        expenseCreation.value = current.copy(submitting = true, error = null)
        return viewModelScope.launch {
            val result = if (current.expenseId == null) repository.createExpense(input)
                else repository.updateExpense(current.expenseId, input)
            currentCoroutineContext().ensureActive()
            if (current.expenseId != null && result is DataResult.Success) {
                expenseCreation.value = current.copy(original = result.value, draft = ExpenseDraft.fromExpense(result.value),
                    editing = false, submitting = false, error = null)
                loadExpenseForEditing(current.expenseId)
            } else {
                val error = (result as? DataResult.Failure)?.error
                expenseCreation.value = current.copy(saved = result is DataResult.Success, error = error,
                    original = if (error is DataError.MissingEntity && error.kind == EntityKind.EXPENSE) null else current.original,
                    editing = current.editing && !(error is DataError.MissingEntity && error.kind == EntityKind.EXPENSE))
            }
        }
    }

    fun requestExpenseDeletion() {
        expenseCreation.update {
            if (it == null || it.expenseId == null || it.original == null || !it.editing || it.loading ||
                it.submitting || it.saved || it.interrupted) it
            else it.copy(confirmingDelete = true, error = null)
        }
    }
    fun cancelExpenseDeletion() {
        expenseCreation.update { if (it?.submitting == true) it else it?.copy(confirmingDelete = false) }
    }
    fun confirmExpenseDeletion(): Job? {
        val current = expenseCreation.value ?: return null
        val id = current.expenseId ?: return null
        if (!current.confirmingDelete || current.submitting || current.saved || current.interrupted) return null
        expenseCreation.value = current.copy(submitting = true, error = null)
        return viewModelScope.launch {
            val result = repository.deleteExpense(id)
            currentCoroutineContext().ensureActive()
            val error = (result as? DataResult.Failure)?.error
            val missing = error is DataError.MissingEntity && error.kind == EntityKind.EXPENSE
            expenseCreation.value = current.copy(confirmingDelete = false, saved = result is DataResult.Success,
                error = error, original = if (missing) null else current.original, editing = current.editing && !missing)
        }
    }

    data class PeriodBudgetDraft(
        val period: BudgetPeriod, val text: String, val submitting: Boolean = false,
        val error: DataError? = null, val saved: Boolean = false, val interrupted: Boolean = false,
    ) {
        fun toBundle() = Bundle().apply {
            putLong("start", period.startEpochDay); putLong("end", period.endExclusiveEpochDay)
            putString("text", text); putBoolean("saved", saved)
            putBoolean("interrupted", submitting || interrupted)
        }
        companion object {
            fun fromBundle(bundle: Bundle) = PeriodBudgetDraft(
                BudgetPeriod(LocalDate.ofEpochDay(bundle.getLong("start")), LocalDate.ofEpochDay(bundle.getLong("end"))),
                bundle.getString("text").orEmpty(), saved = bundle.getBoolean("saved"),
                interrupted = bundle.getBoolean("interrupted"))
        }
    }
    private val budgetDraft = MutableStateFlow<PeriodBudgetDraft?>(null)
    val periodBudgetDraft: StateFlow<PeriodBudgetDraft?> = budgetDraft
    fun restorePeriodBudget(draft: PeriodBudgetDraft) {
        if (budgetDraft.value == null) budgetDraft.value = draft
    }
    fun beginPeriodBudget(period: BudgetPeriod, amount: Long) {
        if (budgetDraft.value == null) budgetDraft.value =
            PeriodBudgetDraft(period, java.math.BigDecimal.valueOf(amount, 2).toPlainString())
    }
    fun updatePeriodBudget(text: String) {
        budgetDraft.update { if (it?.submitting == true || it?.interrupted == true) it else it?.copy(text = text, error = null) }
    }
    fun closePeriodBudget() {
        if (budgetDraft.value?.submitting != true) budgetDraft.value = null
    }
    fun submitPeriodBudget() {
        val draft = budgetDraft.value ?: return
        if (draft.submitting || draft.saved || draft.interrupted) return
        val amount = BudgetAmounts.parse(draft.text)
        if (amount == null) {
            budgetDraft.value = draft.copy(error = DataError.InvalidAmount)
            return
        }
        budgetDraft.value = draft.copy(submitting = true)
        viewModelScope.launch {
            val result = repository.setPeriodBudget(draft.period, amount)
            currentCoroutineContext().ensureActive()
            budgetDraft.value = draft.copy(saved = result is DataResult.Success,
                error = (result as? DataResult.Failure)?.error)
        }
    }

    private var navigationRevision = 0L

    fun selectPreviousPeriod() {
        val current = (state.value as? PersistentDataState.Ready)?.snapshot?.period ?: return
        val revision = ++navigationRevision
        viewModelScope.launch {
            val prevResult = repository.periodContaining(current.start.minusDays(1))
            if (revision == navigationRevision && prevResult is DataResult.Success) {
                selectPeriod(prevResult.value)
            }
        }
    }

    fun selectNextPeriod() {
        val current = (state.value as? PersistentDataState.Ready)?.snapshot?.period ?: return
        val revision = ++navigationRevision
        viewModelScope.launch {
            val nextResult = repository.periodContaining(current.endExclusive)
            if (revision == navigationRevision && nextResult is DataResult.Success) {
                selectPeriod(nextResult.value)
            }
        }
    }

    /** Null follows today. Call refresh on resume/date changes when screens are migrated. */
    val selectedPeriod: BudgetPeriod? get() = selection.value.period
    fun selectPeriod(period: BudgetPeriod?) {
        ++navigationRevision
        selection.update { it.copy(period = period) }
    }
    fun refresh() {
        selection.update { it.copy(refresh = it.refresh + 1) }
        activeRefresh.update { it + 1 }
        retrySources()
    }
}

class PersistentDataViewModelFactory(
    private val repository: ManyManagerRepository
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass == PersistentDataViewModel::class.java) { "Unsupported ViewModel type" }
        return PersistentDataViewModel(repository) as T
    }
}

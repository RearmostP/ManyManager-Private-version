package com.example.simple.manymanager.data

import androidx.room.withTransaction
import com.example.simple.manymanager.data.local.*
import com.example.simple.manymanager.domain.*
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext

/**
 * Persistent pipeline scoped to the initialized local calculator, consumed through the ViewModel.
 * Each operation reloads saved settings; no temporary data or permanently cached active period.
 */
class ManyManagerRepository(
    private val database: ManyManagerDatabase,
    private val initialization: Deferred<Result<CalculatorEntity>>,
    private val clock: Clock,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val dao get() = database.dao()

    suspend fun currentPeriod(): DataResult<BudgetPeriod> =
        execute { resolvePeriod(it, null) }

    suspend fun periodContaining(date: LocalDate): DataResult<BudgetPeriod> =
        execute { configuredCalculator(it).periodContaining(date) }

    fun observeCategories(): Flow<DataResult<List<Category>>> =
        observe(arrayOf("calculators", "categories")) {
            execute { calculator -> dao.categories(calculator.id).map { it.domain() } }
        }

    fun observeSources(): Flow<DataResult<List<BudgetSource>>> =
        observe(arrayOf("calculators", "budget_sources")) {
            execute { calculator -> dao.sources(calculator.id).map { it.domain() } }
        }

    /**
     * Null selects the period containing today on each transactional read.
     * Callers restart/refresh on resume or civil-date changes; no background scheduler is installed.
     */
    fun observeSnapshot(period: BudgetPeriod? = null): Flow<DataResult<BudgetSnapshot>> =
        observe(arrayOf("calculators", "categories", "budget_sources", "period_budgets", "expenses")) {
            snapshot(period)
        }

    suspend fun snapshot(period: BudgetPeriod? = null): DataResult<BudgetSnapshot> =
        execute { calculator ->
            val selected = resolvePeriod(calculator, period)
            readSnapshot(calculator, selected)
        }

    private suspend fun readSnapshot(calculator: CalculatorEntity, selected: BudgetPeriod): BudgetSnapshot {
            val budget = dao.periodBudget(calculator.id, selected.startEpochDay)?.amountMinor ?: 0L
            if (budget < 0) reject(DataError.NegativeAllocatedBudget)
            return BudgetCalculations.snapshot(
                calculator.domain(), selected,
                dao.categories(calculator.id).map { it.domain() },
                dao.sources(calculator.id).map { it.domain() },
                budget,
                dao.expenses(calculator.id, selected.startEpochDay, selected.endExclusiveEpochDay)
                    .map { it.domain() },
            )
        }

    /** Date determines the period; validation of all exposed totals happens before commit. */
    suspend fun createExpense(input: NewExpense): DataResult<Expense> = execute { calculator ->
        val period = validateExpense(calculator, input)
        val expense = ExpenseEntity(calculator.id, UUID.randomUUID().toString(), input.amountMinor,
            input.date.toEpochDay(), input.categoryId, input.sourceId, input.note?.takeIf { it.isNotBlank() })
        dao.insertExpense(expense)
        readSnapshot(calculator, period) // Includes per-source/category sums and remaining overflow.
        expense.domain()
    }

    fun observeExpense(expenseId: String): Flow<DataResult<Expense>> =
        observe(arrayOf("calculators", "expenses")) { expense(expenseId) }

    suspend fun expense(expenseId: String): DataResult<Expense> = execute {
        requireExpense(it.id, expenseId).domain()
    }

    /** Validate and UPDATE the same owned row. Both periods must remain representable before commit. */
    suspend fun updateExpense(expenseId: String, input: NewExpense): DataResult<Expense> = execute { calculator ->
        val original = requireExpense(calculator.id, expenseId)
        val oldPeriod = expensePeriod(calculator, LocalDate.ofEpochDay(original.dateEpochDay))
        val newPeriod = validateExpense(calculator, input)
        val updated = original.copy(amountMinor = input.amountMinor, dateEpochDay = input.date.toEpochDay(),
            sourceId = input.sourceId, categoryId = input.categoryId, note = input.note?.takeIf { it.isNotBlank() })
        check(dao.updateExpense(updated.calculatorId, updated.id, updated.amountMinor, updated.dateEpochDay,
            updated.sourceId, updated.categoryId, updated.note) == 1)
        readSnapshot(calculator, oldPeriod)
        if (newPeriod != oldPeriod) readSnapshot(calculator, newPeriod)
        updated.domain()
    }

    suspend fun deleteExpense(expenseId: String): DataResult<Unit> = execute { calculator ->
        val original = requireExpense(calculator.id, expenseId)
        val period = expensePeriod(calculator, LocalDate.ofEpochDay(original.dateEpochDay))
        check(dao.deleteExpense(calculator.id, expenseId) == 1)
        readSnapshot(calculator, period)
        Unit
    }

    private suspend fun requireExpense(calculatorId: String, id: String): ExpenseEntity =
        dao.expense(calculatorId, id) ?: reject(DataError.MissingEntity(EntityKind.EXPENSE, id))

    private suspend fun validateExpense(calculator: CalculatorEntity, input: NewExpense): BudgetPeriod {
        if (input.amountMinor <= 0) reject(DataError.NonPositiveAmount)
        val period = expensePeriod(calculator, input.date)
        requireSource(calculator.id, input.sourceId)
        val id = input.categoryId ?: reject(DataError.MissingEntity(EntityKind.CATEGORY, ""))
        val categories = dao.categories(calculator.id)
        val category = categories.firstOrNull { it.id == id }
            ?: reject(DataError.MissingEntity(EntityKind.CATEGORY, id))
        category.parentId?.let { parentId ->
            if (categories.none { it.id == parentId && it.parentId == null })
                reject(DataError.MissingEntity(EntityKind.CATEGORY, parentId))
        }
        return period
    }

    private fun expensePeriod(calculator: CalculatorEntity, date: LocalDate): BudgetPeriod =
        try { configuredCalculator(calculator).periodContaining(date) }
        catch (_: DateTimeException) { reject(DataError.InvalidDate) }

    suspend fun createCategory(name: String, parentId: String? = null): DataResult<Category> = execute { calculator ->
        if (parentId != null && dao.categories(calculator.id).none { it.id == parentId && it.parentId == null })
            reject(DataError.MissingEntity(EntityKind.CATEGORY, parentId))
        val category = CategoryEntity(calculator.id, UUID.randomUUID().toString(), cleanName(name), parentId)
        dao.insertCategory(category)
        category.domain()
    }

    /** Changes only the owned category's name; IDs, hierarchy and expense references stay intact. */
    suspend fun renameCategory(categoryId: String, name: String): DataResult<Category> = execute { calculator ->
        val clean = cleanName(name)
        val category = dao.categories(calculator.id).firstOrNull { it.id == categoryId }
            ?: reject(DataError.MissingEntity(EntityKind.CATEGORY, categoryId))
        check(dao.renameCategory(calculator.id, categoryId, clean) == 1)
        category.copy(name = clean).domain()
    }

    suspend fun createPaymentMethod(name: String): DataResult<BudgetSource> = execute { calculator ->
        val source = BudgetSourceEntity(calculator.id, UUID.randomUUID().toString(), cleanName(name))
        dao.insertSource(source)
        source.domain()
    }

    suspend fun setPeriodBudget(period: BudgetPeriod, amountMinor: Long): DataResult<Unit> =
        execute { calculator ->
            val selected = resolvePeriod(calculator, period)
            if (amountMinor < 0) reject(DataError.NegativeAllocatedBudget)
            dao.putBudget(PeriodBudgetEntity(calculator.id, selected.startEpochDay, amountMinor))
            readSnapshot(calculator, selected)
            Unit
        }

    suspend fun renameSource(sourceId: String, name: String): DataResult<BudgetSource> =
        execute { calculator ->
            val clean = cleanName(name)
            val source = requireSource(calculator.id, sourceId)
            check(dao.renameSource(calculator.id, sourceId, clean) == 1)
            source.copy(name = clean).domain()
        }

    private suspend fun requireSource(calculatorId: String, sourceId: String): BudgetSourceEntity =
        dao.source(calculatorId, sourceId)
            ?: reject(DataError.MissingEntity(EntityKind.SOURCE, sourceId))

    private fun cleanName(name: String): String =
        name.trim().also { if (it.isEmpty()) reject(DataError.BlankName) }

    private fun configuredCalculator(calculator: CalculatorEntity): BudgetPeriodCalculator =
        try {
            BudgetPeriodCalculator(calculator.periodStartDay, clock)
        } catch (_: IllegalArgumentException) {
            reject(DataError.InvalidPeriod)
        }

    private fun resolvePeriod(calculator: CalculatorEntity, period: BudgetPeriod?): BudgetPeriod {
        val configured = configuredCalculator(calculator)
        val canonical = if (period == null) configured.currentPeriod()
            else configured.periodContaining(period.start)
        if (period != null && period != canonical) reject(DataError.InvalidPeriod)
        return canonical
    }

    private suspend fun initializedCalculatorId(): String {
        try {
            return initialization.await().getOrElse { cause ->
                if (cause is CancellationException) throw cause
                reject(DataError.InitializationFailure(cause))
            }.id
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (rejected: Rejected) {
            throw rejected
        } catch (failure: Exception) {
            reject(DataError.InitializationFailure(failure))
        }
    }

    private suspend fun <T> execute(block: suspend (CalculatorEntity) -> T): DataResult<T> =
        withContext(dispatcher) {
            try {
                val id = initializedCalculatorId()
                DataResult.Success(database.withTransaction {
                    val calculator = dao.calculator(id)
                        ?: reject(DataError.MissingEntity(EntityKind.CALCULATOR, id))
                    block(calculator)
                })
            } catch (failure: Exception) {
                DataResult.Failure(errorFor(failure))
            }
        }

    private fun <T> observe(
        tables: Array<String>, read: suspend () -> DataResult<T>
    ): Flow<DataResult<T>> = flow {
        initializedCalculatorId()
        // Register before the initial read. Every invalidation triggers a whole DB transaction;
        // no combine of independent queries can emit mixed revisions.
        database.invalidationTracker.createFlow(*tables, emitInitialState = true).collect {
            emit(read())
        }
    }.catch { failure -> emit(DataResult.Failure(errorFor(failure))) }
        .flowOn(dispatcher).distinctUntilChanged()

    private fun errorFor(failure: Throwable): DataError = when (failure) {
        is CancellationException -> throw failure
        is Rejected -> failure.error
        is ArithmeticException -> DataError.AmountOverflow
        is DateTimeException -> DataError.InvalidPeriod
        else -> DataError.StorageFailure(failure)
    }

    private class Rejected(val error: DataError) : RuntimeException()
    private fun reject(error: DataError): Nothing = throw Rejected(error)

    private fun CalculatorEntity.domain() = Calculator(id, periodStartDay, currencyCode)
    private fun CategoryEntity.domain() = Category(calculatorId, id, name, parentId)
    private fun BudgetSourceEntity.domain() = BudgetSource(calculatorId, id, name)
    private fun ExpenseEntity.domain() = Expense(
        calculatorId, id, amountMinor, LocalDate.ofEpochDay(dateEpochDay), categoryId, sourceId, note
    )
}

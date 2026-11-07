package com.example.simple.manymanager.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert

/** Storage primitives for the repository and isolated fixtures; never injected into screens. */
@Dao
abstract class ManyManagerDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    protected abstract suspend fun insertCalculatorIfAbsent(calculator: CalculatorEntity): Long

    @Query("SELECT * FROM calculators WHERE id = :id")
    abstract suspend fun calculator(id: String): CalculatorEntity?

    @Transaction
    open suspend fun initializeLocalCalculator(): CalculatorEntity {
        insertCalculatorIfAbsent(CalculatorEntity(LOCAL_CALCULATOR_ID))
        return checkNotNull(calculator(LOCAL_CALCULATOR_ID))
    }

    // ABORT is intentional: these are inserts, never silent replacement of existing facts.
    @Insert abstract suspend fun insertCategory(category: CategoryEntity)
    @Insert abstract suspend fun insertSource(source: BudgetSourceEntity)
    @Insert abstract suspend fun insertBudget(budget: PeriodBudgetEntity)
    @Insert abstract suspend fun insertExpense(expense: ExpenseEntity)

    @Query("SELECT * FROM expenses WHERE calculatorId = :calculatorId AND id = :id")
    abstract suspend fun expense(calculatorId: String, id: String): ExpenseEntity?

    @Query("SELECT * FROM period_budgets WHERE calculatorId = :calculatorId AND periodStartEpochDay = :periodStartEpochDay")
    abstract suspend fun periodBudget(calculatorId: String, periodStartEpochDay: Long): PeriodBudgetEntity?

    @Query("SELECT * FROM categories WHERE calculatorId = :calculatorId ORDER BY rowid")
    abstract suspend fun categories(calculatorId: String): List<CategoryEntity>

    @Query("SELECT * FROM budget_sources WHERE calculatorId = :calculatorId ORDER BY rowid")
    abstract suspend fun sources(calculatorId: String): List<BudgetSourceEntity>

    @Query("SELECT * FROM budget_sources WHERE calculatorId = :calculatorId AND id = :id")
    abstract suspend fun source(calculatorId: String, id: String): BudgetSourceEntity?


    @Query("""
        SELECT * FROM expenses WHERE calculatorId = :calculatorId
        AND dateEpochDay >= :startEpochDay AND dateEpochDay < :endExclusiveEpochDay
        ORDER BY dateEpochDay DESC, id
    """)
    abstract suspend fun expenses(
        calculatorId: String, startEpochDay: Long, endExclusiveEpochDay: Long
    ): List<ExpenseEntity>

    @Query("UPDATE categories SET name = :name WHERE calculatorId = :calculatorId AND id = :id")
    abstract suspend fun renameCategory(calculatorId: String, id: String, name: String): Int

    @Query("UPDATE budget_sources SET name = :name WHERE calculatorId = :calculatorId AND id = :id")
    abstract suspend fun renameSource(calculatorId: String, id: String, name: String): Int

    @Query("""UPDATE expenses SET amountMinor = :amountMinor, dateEpochDay = :dateEpochDay,
        sourceId = :sourceId, categoryId = :categoryId, note = :note
        WHERE calculatorId = :calculatorId AND id = :id""")
    abstract suspend fun updateExpense(calculatorId: String, id: String, amountMinor: Long,
        dateEpochDay: Long, sourceId: String, categoryId: String?, note: String?): Int

    @Query("DELETE FROM expenses WHERE calculatorId = :calculatorId AND id = :id")
    abstract suspend fun deleteExpense(calculatorId: String, id: String): Int

    @Upsert
    abstract suspend fun putBudget(budget: PeriodBudgetEntity)

    companion object {
        /** Internal identity of the initial local calculator; never derived from a translated label. */
        const val LOCAL_CALCULATOR_ID = "local-calculator"
    }
}

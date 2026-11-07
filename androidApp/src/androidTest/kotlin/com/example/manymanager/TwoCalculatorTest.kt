package com.example.simple.manymanager

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.simple.manymanager.data.ManyManagerRepository
import com.example.simple.manymanager.data.local.*
import com.example.simple.manymanager.domain.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwoCalculatorTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val clock = Clock.fixed(Instant.parse("2026-11-10T12:00:00Z"), ZoneOffset.UTC)
    private fun <T> saved(result: DataResult<T>) = (result as DataResult.Success).value
    private fun rejected(result: DataResult<*>) = assertTrue(result is DataResult.Failure)
    private fun open(name: String) = Room.databaseBuilder(context, ManyManagerDatabase::class.java, name)
        .addMigrations(ManyManagerDatabase.MIGRATION_1_2, ManyManagerDatabase.MIGRATION_2_3)
        .addCallback(ManyManagerDatabase.HIERARCHY_CALLBACK).build()

    @Test fun existingDataSurvivesConcurrentInitializationAndBothCalculatorsReopen(): Unit = runBlocking {
        val name = "two-calculators-${UUID.randomUUID()}.db"
        var db = open(name)
        try {
            // Simulate an existing installation with populated main calculator before new initialization.
            val original = db.dao().initializeLocalCalculator()
            val repo = ManyManagerRepository(db, CompletableDeferred(Result.success(original)), clock)
            val period = saved(repo.currentPeriod())
            val category = saved(repo.createCategory("Original food"))
            val method = saved(repo.createPaymentMethod("Original cash"))
            saved(repo.setPeriodBudget(period, 2000000))
            saved(repo.createExpense(NewExpense(10000, period.start, method.id, category.id, "Keep")))
            val before = saved(repo.snapshot())
            coroutineScope {
                (1..20).map { async(Dispatchers.IO) { db.dao().initializeCalculators() } }.awaitAll()
            }
            assertEquals(before, saved(repo.snapshot()))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM calculators").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
            val second = repo.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID)
            val empty = saved(second.snapshot())
            assertEquals(ManyManagerDao.SECOND_CALCULATOR_ID, empty.calculator.id)
            assertEquals(10, empty.calculator.periodStartDay)
            assertEquals("ILS", empty.calculator.currencyCode)
            assertEquals(0L, empty.totalAllocatedMinor)
            assertTrue(empty.categories.isEmpty() && empty.sources.isEmpty() && empty.expenses.isEmpty())
            val secondCategory = saved(second.createCategory("Second food"))
            val secondMethod = saved(second.createPaymentMethod("Second cash"))
            saved(second.setPeriodBudget(period, 50000))
            val expense = saved(second.createExpense(NewExpense(2500, period.start, secondMethod.id, secondCategory.id)))
            val secondBefore = saved(second.snapshot())
            db.close()
            db = open(name)
            val reopened = ManyManagerRepository(db, CompletableDeferred(Result.success(db.dao().initializeCalculators())), clock)
            assertEquals(before, saved(reopened.snapshot()))
            assertEquals(secondBefore, saved(reopened.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID).snapshot()))
            assertEquals(expense, saved(reopened.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID).expense(expense.id)))
            db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM calculators").use {
                assertTrue(it.moveToFirst()); assertEquals(2, it.getInt(0))
            }
        } finally { db.close(); context.deleteDatabase(name) }
    }

    @Test fun budgetsWritesRenamesOwnershipAndPeriodMovesStayIndependent(): Unit = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ManyManagerDatabase::class.java)
            .addCallback(ManyManagerDatabase.HIERARCHY_CALLBACK).build()
        try {
            val main = ManyManagerRepository(db, CompletableDeferred(Result.success(db.dao().initializeCalculators())), clock)
            val second = main.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID)
            val period = saved(main.currentPeriod())
            assertEquals(period, saved(second.currentPeriod()))
            val category = saved(main.createCategory("Food"))
            val child = saved(main.createCategory("Shop", category.id))
            val method = saved(main.createPaymentMethod("Cash"))
            val other = saved(second.createCategory("Food"))
            val otherChild = saved(second.createCategory("Shop", other.id))
            val otherMethod = saved(second.createPaymentMethod("Cash"))
            saved(main.setPeriodBudget(period, 2000000))
            saved(second.setPeriodBudget(period, 50000))
            val direct = saved(main.createExpense(NewExpense(10000, period.start, method.id, child.id)))
            val nested = saved(second.createExpense(NewExpense(2500, period.start, otherMethod.id, otherChild.id)))
            val mainBefore = saved(main.snapshot())
            rejected(main.createCategory("Foreign parent", other.id))
            rejected(second.createCategory("Foreign parent", category.id))
            rejected(main.createExpense(NewExpense(100, period.start, otherMethod.id, category.id)))
            rejected(main.createExpense(NewExpense(100, period.start, method.id, otherChild.id)))
            rejected(second.createExpense(NewExpense(100, period.start, method.id, other.id)))
            rejected(second.createExpense(NewExpense(100, period.start, otherMethod.id, child.id)))
            rejected(second.createExpense(NewExpense(100, period.start, otherMethod.id, null)))
            rejected(main.renameCategory(other.id, "Foreign"))
            rejected(second.renameSource(method.id, "Foreign"))
            rejected(main.updateExpense(nested.id, NewExpense(10, period.start, method.id, category.id)))
            rejected(second.deleteExpense(direct.id))
            saved(second.renameCategory(other.id, "Second parent"))
            saved(second.renameCategory(otherChild.id, "Second child"))
            saved(second.renameSource(otherMethod.id, "Second method"))
            assertEquals(other.id, saved(second.snapshot()).categories.first { it.id == otherChild.id }.parentId)
            assertEquals("Second parent / Second child", saved(second.snapshot()).categories.categoryPath(otherChild.id))
            assertEquals(mainBefore, saved(main.snapshot()))
            val previous = saved(second.periodContaining(period.start.minusDays(1)))
            assertEquals(0L, saved(second.snapshot(previous)).totalAllocatedMinor)
            saved(second.updateExpense(nested.id, NewExpense(3500, period.start.minusDays(1), otherMethod.id, other.id)))
            assertEquals(0L, saved(second.snapshot()).totalSpentMinor)
            assertEquals(-3500L, saved(second.snapshot(previous)).remainingMinor)
            assertEquals(mainBefore, saved(main.snapshot()))
            saved(second.setPeriodBudget(previous, 9000))
            assertEquals(5500L, saved(second.snapshot(previous)).remainingMinor)
            saved(second.deleteExpense(nested.id))
            assertEquals(0L, saved(second.snapshot(previous)).totalSpentMinor)
            assertEquals(50000L, saved(second.snapshot()).totalAllocatedMinor)
            assertEquals(mainBefore, saved(main.snapshot()))
            assertEquals(0L, saved(main.snapshot(previous)).totalAllocatedMinor)
            assertEquals(10000L, saved(main.observeSnapshot().first()).totalSpentMinor)
            assertEquals(0L, saved(second.observeSnapshot().first()).totalSpentMinor)
        } finally { db.close() }
    }
}

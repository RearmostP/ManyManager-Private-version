package com.example.simple.manymanager

import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.room.withTransaction
import com.example.simple.manymanager.data.ManyManagerRepository
import com.example.simple.manymanager.data.local.ManyManagerDatabase
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.simple.manymanager.domain.*
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Real MainActivity/dialog integration. Only this runner's in-memory database is fault-injected. */
@RunWith(AndroidJUnit4::class)
class BudgetLifecycleTest {
    private val app get() = ApplicationProvider.getApplicationContext<BudgetTestApplication>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before fun prepare() = runBlocking {
        app.databaseInitialization.await().getOrThrow()
        withContext(Dispatchers.IO) { app.database.clearAllTables() }
        app.database.dao().initializeLocalCalculator()
        app.clock.now = Instant.parse("2026-11-09T21:59:00Z")
        app.clock.currentZone = ZoneId.of("Asia/Jerusalem")
    }

    @After fun finish() {
        scenario?.close()
        scope.cancel()
    }

    private fun launch(): ActivityScenario<MainActivity> =
        ActivityScenario.launch(MainActivity::class.java).also { scenario = it }

    private fun model(screen: ActivityScenario<MainActivity>): PersistentDataViewModel {
        lateinit var result: PersistentDataViewModel
        screen.onActivity { result = ViewModelProvider(it)[PersistentDataViewModel::class.java] }
        return result
    }

    private fun awaitUi(screen: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var done = false
            screen.onActivity { done = condition(it) }
            if (done) return
            SystemClock.sleep(25)
        }
        fail("Timed out waiting for Activity/dialog state")
    }

    private fun rootWith(id: Int): View? = WindowInspector.getGlobalWindowViews()
        .lastOrNull { it.findViewById<View>(id)?.isShown == true }

    private fun openBudget(screen: ActivityScenario<MainActivity>) {
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav3) != null }
        screen.onActivity { rootWith(R.id.nav3)!!.findViewById<View>(R.id.nav3).performClick() }
        awaitUi(screen) {
            rootWith(R.id.nav3) == null && it.findViewById<View>(R.id.budgetPage).isShown &&
                it.findViewById<View>(R.id.budgetAddSource).isEnabled
        }
    }

    private fun addForm(screen: ActivityScenario<MainActivity>, name: String, amount: String) {
        screen.onActivity { it.findViewById<View>(R.id.budgetAddSource).performClick() }
        awaitUi(screen) { rootWith(R.id.addSourceName) != null }
        screen.onActivity {
            val root = rootWith(R.id.addSourceName)!!
            root.findViewById<EditText>(R.id.addSourceName).setText(name)
        }
    }

    private suspend fun gate(): Pair<CompletableDeferred<Unit>, Job> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val job = scope.launch {
            app.database.withTransaction { entered.complete(Unit); release.await() }
        }
        withTimeout(10_000) { entered.await() }
        return release to job
    }

    @Test fun fileBackedRecordsReopenAndDisplayInFreshActivity(): Unit = runBlocking {
        val name = "acceptance-ui-${java.util.UUID.randomUUID()}.db"
        fun openFile() = Room.databaseBuilder(
            app, ManyManagerDatabase::class.java, name
        ).build()
        var disk = openFile()
        try {
            suspend fun repository(): ManyManagerRepository {
                val calculator = disk.dao().initializeLocalCalculator()
                return ManyManagerRepository(
                    disk, CompletableDeferred(Result.success(calculator)), app.clock
                )
            }
            val writer = repository()
            val period = success(writer.currentPeriod())
            assertTrue(success(writer.snapshot()).expenses.isEmpty())
            val category = success(writer.createCategory("Reopened category"))
            val source = success(writer.seedMethodAndBudget("Reopened source", period, 10000))
            val zero = success(writer.seedMethodAndBudget("Zero source", period, 0))
            val categorized = success(writer.createExpense(NewExpense(1250, period.start, source.id, category.id, "Saved note")))
            disk.dao().insertExpense(com.example.simple.manymanager.data.local.ExpenseEntity(
                source.calculatorId, "legacy-reopen", 250, period.startEpochDay, null, zero.id))
            val uncategorized = success(writer.expense("legacy-reopen"))
            val saved = success(writer.snapshot())
            disk.close()
            disk = openFile()
            val reader = repository()
            assertEquals(saved, success(reader.snapshot()))
            app.fileTestRepository = reader

            // A new Activity/ViewModel reads the reopened file, not an in-memory copy.
            val screen = launch()
            val controller = model(screen)
            awaitUi(screen) {
                val ready = controller.activeBudget.value as? PersistentDataState.Ready
                ready?.snapshot == saved &&
                    it.findViewById<android.widget.LinearLayout>(R.id.expenseRows).childCount == 2 &&
                    it.findViewById<android.widget.LinearLayout>(R.id.sourceSummaryRows).childCount == 2
            }
            screen.onActivity { activity ->
                val snapshot = (controller.activeBudget.value as PersistentDataState.Ready).snapshot
                assertEquals(listOf(category), snapshot.categories)
                assertEquals(setOf(source.id, zero.id), snapshot.sources.map { it.source.id }.toSet())
                assertEquals(10000L, snapshot.totalAllocatedMinor)
                assertEquals(1500L, snapshot.totalSpentMinor)
                assertEquals(8500L, snapshot.remainingMinor)
                assertEquals(setOf(categorized, uncategorized), snapshot.expenses.toSet())
                assertEquals(2, snapshot.expenseCount)
                fun money(value: Long) = activity.getString(R.string.ils_amount,
                    BudgetAmounts.number(value, activity.resources.configuration.locales[0]))
                assertEquals(money(1500),
                    activity.findViewById<TextView>(R.id.homeTotal).text.toString())
                assertEquals(money(8500), activity.findViewById<TextView>(R.id.homeBudgetRemaining).text.toString())
                assertEquals(money(10000), activity.findViewById<TextView>(R.id.budgetAllocatedTotal).text.toString())
                fun rowText(container: Int, id: String?): String {
                    val rows = activity.findViewById<android.widget.LinearLayout>(container)
                    val row = (0 until rows.childCount).map(rows::getChildAt).single { it.tag == id }
                    return if (container == R.id.expenseRows) row.contentDescription.toString()
                        else row.findViewById<TextView>(R.id.breakdownAmount).text.toString()
                }
                assertEquals(money(1250), rowText(R.id.categorySummaryRows, category.id))
                assertEquals(money(250), rowText(R.id.categorySummaryRows, null))
                assertEquals(money(1250), rowText(R.id.sourceSummaryRows, source.id))
                assertEquals(money(250), rowText(R.id.sourceSummaryRows, zero.id))
                assertTrue(rowText(R.id.expenseRows, categorized.id).contains("Reopened category"))
                assertTrue(rowText(R.id.expenseRows, categorized.id).contains("Reopened source"))
                assertTrue(rowText(R.id.expenseRows, categorized.id).contains("Saved note"))
                assertTrue(rowText(R.id.expenseRows, uncategorized.id).contains("Zero source"))
            }
            screen.recreate()
            awaitUi(screen) { it.findViewById<TextView>(R.id.homeTotal).text.toString() ==
                it.getString(R.string.ils_amount, BudgetAmounts.number(1500, it.resources.configuration.locales[0])) }
        } finally {
            scenario?.close()
            scenario = null
            app.fileTestRepository = null
            disk.close()
            app.deleteDatabase(name) // Only this UUID fixture, never production storage.
        }
    }





    @Test fun backPreservesHistoryAndLateNavigationCannotOverwriteNewSelection() = runBlocking {
        val screen = launch()
        openBudget(screen)
        val controller = model(screen)
        val active = success(app.repository.currentPeriod())
        val (release, lock) = gate()
        try {
            screen.onActivity {
                controller.selectNextPeriod()
                controller.selectPeriod(active)
            }
            awaitUi(screen) { controller.state.value is PersistentDataState.Loading }
        } finally { release.complete(Unit); lock.join() }
        awaitUi(screen) { (controller.state.value as? PersistentDataState.Ready)?.snapshot?.period == active }
        assertEquals(active, controller.selectedPeriod)
        screen.onActivity { controller.selectPreviousPeriod() }
        awaitUi(screen) {
            (controller.state.value as? PersistentDataState.Ready)?.snapshot?.period?.let { it != active } == true
        }
        val historical = controller.selectedPeriod!!
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav2) != null }
        screen.onActivity { rootWith(R.id.nav2)!!.findViewById<View>(R.id.nav2).performClick() }
        awaitUi(screen) { rootWith(R.id.nav2) == null && it.findViewById<View>(R.id.settingsPage).isShown }
        screen.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        awaitUi(screen) { it.findViewById<View>(R.id.budgetPage).isShown }
        assertEquals(historical, controller.selectedPeriod)
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav2) != null }
        screen.onActivity { rootWith(R.id.nav2)!!.findViewById<View>(R.id.nav2).performClick() }
        awaitUi(screen) { rootWith(R.id.nav2) == null && it.findViewById<View>(R.id.settingsPage).isShown }
        openBudget(screen)
        awaitUi(screen) {
            controller.selectedPeriod == null &&
                (controller.state.value as? PersistentDataState.Ready)?.snapshot?.period == active
        }
    }


    @Test fun financialReadFailureOffersRetryAndKeepsSourceChoicesAndExpenseDraft() = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Available", period, 0))
        val screen = launch()
        val controller = model(screen)
        awaitUi(screen) { controller.activeBudget.value is PersistentDataState.Ready }
        screen.onActivity { it.findViewById<View>(R.id.addExpense).performClick() }
        awaitUi(screen) { rootWith(R.id.expenseNote) != null }
        screen.onActivity {
            val root = rootWith(R.id.expenseNote)!!
            root.findViewById<EditText>(R.id.amount).setText("7.89")
            root.findViewById<EditText>(R.id.expenseNote).setText("Keep note")
            root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).performClick()
        }
        app.database.dao().insertExpense(com.example.simple.manymanager.data.local.ExpenseEntity(
            source.calculatorId, "read-overflow-a", Long.MAX_VALUE, period.startEpochDay, null, source.id))
        app.database.dao().insertExpense(com.example.simple.manymanager.data.local.ExpenseEntity(
            source.calculatorId, "read-overflow-b", 1, period.startEpochDay, null, source.id))
        awaitUi(screen) { controller.activeBudget.value is PersistentDataState.Failed }
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.expenseNote) != null }
        screen.onActivity {
            val root = rootWith(R.id.expenseNote)!!
            assertEquals("7.89", root.findViewById<EditText>(R.id.amount).text.toString())
            assertEquals("Keep note", root.findViewById<EditText>(R.id.expenseNote).text.toString())
            assertTrue(root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).isSelected)
            root.findViewById<View>(R.id.closeDialog).performClick()
        }
        awaitUi(screen) { rootWith(R.id.expenseNote) == null }
        screen.onActivity {
            assertEquals(it.getString(R.string.budget_load_error),
                it.findViewById<TextView>(R.id.homeBudgetRemaining).text.toString())
            assertEquals("—", it.findViewById<TextView>(R.id.homeTotal).text.toString())
            assertTrue(it.findViewById<View>(R.id.homeBudgetArea).isClickable)
        }
        // Remove only explicitly isolated test fixtures, then exercise the visible retry action.
        app.database.openHelper.writableDatabase.execSQL("DELETE FROM expenses WHERE id IN ('read-overflow-a', 'read-overflow-b')")
        screen.onActivity { it.findViewById<View>(R.id.homeBudgetArea).performClick() }
        awaitUi(screen) {
            (controller.activeBudget.value as? PersistentDataState.Ready)?.snapshot?.totalSpentMinor == 0L
        }
    }

    @Test fun expenseSaveRecreationCategoryDraftAndAmountCorrection() = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Cash", period, 0))
        val category = success(app.repository.createCategory("Food"))
        val screen = launch()
        val controller = model(screen)
        awaitUi(screen) { controller.sources.value is SourceListState.Ready && controller.categories.value is CategoryListState.Ready }
        screen.onActivity { it.findViewById<View>(R.id.addExpense).performClick() }
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity {
            val root = rootWith(R.id.amount)!!
            root.findViewById<EditText>(R.id.amount).setText("12.34")
            root.findViewById<EditText>(R.id.expenseNote).setText("Keep note")
            root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).performClick()
            assertEquals(View.GONE, root.findViewById<View>(R.id.expenseNewCategory).visibility)
            root.findViewById<android.widget.LinearLayout>(R.id.expenseCategoryChoices).findViewWithTag<View>(category.id).performClick()
        }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        screen.onActivity { rootWith(R.id.categoryPickerRows)!!.findViewById<View>(android.R.id.button1).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.draft?.categoryId == category.id && rootWith(R.id.categoryPickerRows) == null }
        val draft = controller.expenseCreationState.value!!.draft
        assertEquals("12.34", draft.amount)
        assertEquals("Keep note", draft.note)
        assertEquals(source.id, draft.sourceId)
        assertNotNull(draft.date)
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.amount) != null }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        val (release, held) = gate()
        screen.onActivity {
            val save = rootWith(R.id.amount)!!.findViewById<View>(R.id.saveExpense)
            assertTrue(save.isEnabled)
            save.performClick(); save.performClick()
        }
        awaitUi(screen) { controller.expenseCreationState.value?.submitting == true }
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity {
            assertFalse(rootWith(R.id.amount)!!.findViewById<View>(R.id.saveExpense).isEnabled)
            assertNull(controller.submitExpenseCreation())
            controller.cancelExpenseCreation()
            assertTrue(controller.expenseCreationState.value!!.submitting)
        }
        screen.onActivity {
            val config = Configuration(it.resources.configuration)
            config.uiMode = config.uiMode xor Configuration.UI_MODE_NIGHT_MASK
            it.onConfigurationChanged(config)
        }
        awaitUi(screen) { rootWith(R.id.amount) != null }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        release.complete(Unit); held.join()
        awaitUi(screen) { controller.expenseCreationState.value == null && rootWith(R.id.amount) == null }
        // New draft follows the actual current civil date, not the injected accounting clock.
        val savedPeriod = success(app.repository.periodContaining(draft.date!!))
        val saved = success(app.repository.snapshot(savedPeriod)).expenses.single()
        assertEquals(1234L, saved.amountMinor)
        assertEquals(draft.categoryId, saved.categoryId)
        // Render the real active list using the same date through the test application's boundary clock.
        app.clock.now = draft.date.atTime(12, 0).atZone(app.clock.zone).toInstant()
        screen.onActivity { controller.refresh() }
        awaitUi(screen) { (controller.activeBudget.value as? PersistentDataState.Ready)?.snapshot?.expenseCount == 1 }
        screen.onActivity {
            it.findViewById<View>(R.id.menuButton).performClick()
        }
        awaitUi(screen) { rootWith(R.id.nav1) != null }
        screen.onActivity { rootWith(R.id.nav1)!!.findViewById<View>(R.id.nav1).performClick() }
        awaitUi(screen) { it.findViewById<View>(R.id.summaryPage).isShown }
        screen.onActivity { it.findViewById<View>(R.id.allTab).performClick() }
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.expenseRows).childCount == 1 }
        screen.onActivity { it.findViewById<android.widget.LinearLayout>(R.id.expenseRows).getChildAt(0).performClick() }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && controller.expenseCreationState.value?.original != null }
        val detailsWindow = rootWith(R.id.expenseDetailsTitle)
        screen.onActivity {
            assertFalse(controller.expenseCreationState.value!!.editing)
            assertEquals(View.GONE, rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.expenseDetailsAmount).visibility)
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.editExpenseDetails).performClick()
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<EditText>(R.id.expenseDetailsAmount).setText("9.87")
            detailsBack(it)
        }
        awaitUi(screen) { controller.expenseCreationState.value?.editing == false }
        assertTrue(detailsWindow === rootWith(R.id.expenseDetailsTitle))
        assertEquals(saved, success(app.repository.expense(saved.id)))
        screen.onActivity {
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.editExpenseDetails).performClick()
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<EditText>(R.id.expenseDetailsAmount).setText("9.87")
        }
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && controller.expenseCreationState.value?.editing == true }
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_correction BEFORE UPDATE ON expenses BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        screen.onActivity {
            assertEquals("9.87", rootWith(R.id.expenseDetailsTitle)!!.findViewById<EditText>(R.id.expenseDetailsAmount).text.toString())
            controller.submitExpenseCreation()
            assertNull(controller.submitExpenseCreation())
        }
        awaitUi(screen) { controller.expenseCreationState.value?.error is DataError.StorageFailure }
        assertEquals(saved, success(app.repository.expense(saved.id)))
        assertEquals("9.87", controller.expenseCreationState.value!!.draft.amount)
        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_correction")
        val (releaseCorrection, heldCorrection) = gate()
        try {
            screen.onActivity { controller.submitExpenseCreation() }
            screen.recreate()
            awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && controller.expenseCreationState.value?.submitting == true }
            screen.onActivity {
                assertFalse(rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.saveExpenseDetails).isEnabled)
                assertNull(controller.submitExpenseCreation())
                assertNull(controller.confirmExpenseDeletion())
            }
        } finally { releaseCorrection.complete(Unit); heldCorrection.join() }
        awaitUi(screen) { controller.expenseCreationState.value?.editing == false &&
            controller.expenseCreationState.value?.original?.amountMinor == 987L }
        assertNotNull(rootWith(R.id.expenseDetailsTitle))
        val corrected = saved.copy(amountMinor = 987)
        assertEquals(corrected, success(app.repository.expense(saved.id)))
        screen.onActivity {
            assertNull(controller.submitExpenseCreation())
            controller.cancelExpenseCreation()
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) == null }
        screen.onActivity {
            controller.restoreExpenseCreation(ExpenseCreationState.fromBundle(
                ExpenseCreationState(ExpenseDraft.fromExpense(corrected).copy(amount = "2"),
                    submitting = true, expenseId = corrected.id, original = corrected, editing = true).toBundle()))
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && controller.expenseCreationState.value?.interrupted == true }
        screen.onActivity {
            assertFalse(rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.saveExpenseDetails).isEnabled)
            assertNull(controller.submitExpenseCreation())
            assertNull(controller.confirmExpenseDeletion())
        }
        assertEquals(corrected, success(app.repository.expense(corrected.id)))
    }

    @Test fun failedExpenseWritePreservesInputAndUnknownRestoreCannotSubmit() = runBlocking {
        val period = success(app.repository.currentPeriod())
        success(app.repository.seedMethodAndBudget("Cash", period, 0))
        val category = success(app.repository.createCategory("Food"))
        val screen = launch()
        val controller = model(screen)
        awaitUi(screen) { controller.sources.value is SourceListState.Ready }
        screen.onActivity { it.findViewById<View>(R.id.addExpense).performClick() }
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity {
            val root = rootWith(R.id.amount)!!
            root.findViewById<EditText>(R.id.amount).setText("1.01")
            root.findViewById<EditText>(R.id.expenseNote).setText("retry")
            controller.selectExpenseCategory(category.id)
            root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).performClick()
        }
        val draft = controller.expenseCreationState.value!!.draft
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_expense BEFORE INSERT ON expenses BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        screen.onActivity { controller.submitExpenseCreation() }
        awaitUi(screen) { controller.expenseCreationState.value?.error is DataError.StorageFailure }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.amount) != null }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_expense")
        screen.onActivity { controller.submitExpenseCreation() }
        awaitUi(screen) { controller.expenseCreationState.value == null && rootWith(R.id.amount) == null }
        app.clock.now = draft.date!!.atTime(12, 0).atZone(app.clock.zone).toInstant()
        screen.onActivity { controller.refresh() }
        awaitUi(screen) { (controller.activeBudget.value as? PersistentDataState.Ready)?.snapshot?.expenseCount == 1 }
        screen.onActivity {
            val row = it.findViewById<android.widget.LinearLayout>(R.id.expenseRows).getChildAt(0)
            assertTrue(row.contentDescription.contains(category.name))
            assertEquals(it.getString(R.string.ils_amount, BudgetAmounts.number(101, it.resources.configuration.locales[0])),
                it.findViewById<TextView>(R.id.homeTotal).text.toString())
        }
        screen.onActivity {
            controller.restoreExpenseCreation(ExpenseCreationState.fromBundle(ExpenseCreationState(draft, submitting = true).toBundle()))
        }
        awaitUi(screen) { rootWith(R.id.expenseOperationStatus) != null }
        screen.onActivity {
            assertFalse(rootWith(R.id.amount)!!.findViewById<View>(R.id.saveExpense).isEnabled)
            assertNull(controller.submitExpenseCreation())
            assertTrue(rootWith(R.id.expenseOperationStatus)!!.findViewById<TextView>(R.id.expenseOperationStatus).text.isNotBlank())
        }
        assertEquals(1, success(app.repository.snapshot(success(app.repository.periodContaining(draft.date!!)))).expenseCount)
    }

    @Test fun summaryHomeManagementAndDraftConvergeOnSameCommittedFacts(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val a = success(app.repository.seedMethodAndBudget("Same", period, 500))
        val b = success(app.repository.seedMethodAndBudget("Same", period, 0))
        val catA = success(app.repository.createCategory("Same"))
        val catB = success(app.repository.createCategory("Same"))
        val first = success(app.repository.createExpense(NewExpense(100, period.start, a.id, catA.id)))
        success(app.repository.createExpense(NewExpense(200, period.start, b.id, catB.id)))
        success(app.repository.legacyExpense(300, period.start, a.id))
        val screen = launch()
        val controller = model(screen)
        fun rows(activity: MainActivity, id: Int) = activity.findViewById<android.widget.LinearLayout>(id)
        fun money(activity: MainActivity, value: Long) = activity.getString(R.string.ils_amount,
            BudgetAmounts.number(value, activity.resources.configuration.locales[0]))
        fun rowAmount(activity: MainActivity, container: Int, key: String?): String =
            (0 until rows(activity, container).childCount).map { rows(activity, container).getChildAt(it) }
                .single { it.tag == key }.findViewById<TextView>(R.id.breakdownAmount).text.toString()
        awaitUi(screen) { rows(it, R.id.categorySummaryRows).childCount == 3 && rows(it, R.id.sourceSummaryRows).childCount == 2 }
        screen.onActivity {
            assertEquals(money(it, 300), rowAmount(it, R.id.categorySummaryRows, null))
            assertEquals(money(it, 100), rowAmount(it, R.id.categorySummaryRows, catA.id))
            assertEquals(money(it, 200), rowAmount(it, R.id.categorySummaryRows, catB.id))
            assertEquals(money(it, 400), rowAmount(it, R.id.sourceSummaryRows, a.id))
            assertEquals(money(it, 200), rowAmount(it, R.id.sourceSummaryRows, b.id))
            assertEquals(3, rows(it, R.id.expenseRows).childCount)
            assertFalse(it.findViewById<View>(R.id.previousMonth).isEnabled)
            assertFalse(it.findViewById<View>(R.id.nextMonth).isEnabled)
        }
        success(app.repository.updateExpense(first.id, NewExpense(400, first.date, first.sourceId, first.categoryId, first.note)))
        awaitUi(screen) { rowAmount(it, R.id.categorySummaryRows, catA.id) == money(it, 400) }
        screen.onActivity {
            assertEquals(money(it, 900), it.findViewById<TextView>(R.id.homeTotal).text.toString())
            assertEquals(money(it, 700), rowAmount(it, R.id.sourceSummaryRows, a.id))
            assertEquals(money(it, -400), it.findViewById<TextView>(R.id.homeBudgetRemaining).text.toString())
            assertEquals(3, rows(it, R.id.expenseRows).childCount)
        }
        success(app.repository.renameSource(a.id, "Renamed"))
        success(app.repository.setPeriodBudget(period, 600))
        awaitUi(screen) {
            val group = rows(it, R.id.sourceSummaryRows).getChildAt(0)
            group.findViewById<TextView>(R.id.breakdownName).text.toString() == "Renamed" &&
                (controller.state.value as? PersistentDataState.Ready)?.snapshot?.totalAllocatedMinor == 600L
        }
        screen.onActivity {
            assertEquals(money(it, 700), rowAmount(it, R.id.sourceSummaryRows, a.id))
            assertEquals(money(it, -300), it.findViewById<TextView>(R.id.homeBudgetRemaining).text.toString())
            assertEquals(money(it, 600), it.findViewById<TextView>(R.id.budgetAllocatedTotal).text.toString())
            val homeRows = rows(it, R.id.budgetBreakdownRows)
            assertTrue((homeRows.getChildAt(0) as android.widget.LinearLayout).getChildAt(0).let { view -> (view as TextView).text.contains("Renamed") })
            assertEquals(money(it, 700), ((homeRows.getChildAt(0) as android.widget.LinearLayout).getChildAt(1) as TextView).text.toString())
            assertEquals(money(it, 200), ((homeRows.getChildAt(1) as android.widget.LinearLayout).getChildAt(1) as TextView).text.toString())
            val list = rows(it, R.id.expenseRows)
            val correctedRow = (0 until list.childCount).map(list::getChildAt).single { view -> view.tag == first.id }
            assertTrue(correctedRow.findViewById<TextView>(R.id.summaryExpenseAmount).text.contains(money(it, 400)))
            assertTrue(correctedRow.findViewById<TextView>(R.id.summaryExpenseSecondary).text.contains("Renamed"))
            it.findViewById<View>(R.id.addExpense).performClick()
        }
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity {
            val root = rootWith(R.id.amount)!!
            root.findViewById<EditText>(R.id.amount).setText("1.23")
            root.findViewById<EditText>(R.id.expenseNote).setText("Keep")
            root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).performClick()
        }
        val draft = controller.expenseCreationState.value!!.draft
        success(app.repository.renameSource(a.id, "Renamed again"))
        success(app.repository.createCategory("New choice"))
        screen.onActivity { controller.selectNextPeriod() }
        awaitUi(screen) {
            (controller.state.value as? PersistentDataState.Ready)?.snapshot?.period != period &&
                controller.state.value is PersistentDataState.Ready &&
                (controller.sources.value as? SourceListState.Ready)?.sources?.any { source -> source.name == "Renamed again" } == true
        }
        screen.onActivity {
            assertEquals(draft, controller.expenseCreationState.value!!.draft)
            assertEquals(money(it, 0), it.findViewById<TextView>(R.id.budgetAllocatedTotal).text.toString())
            assertEquals(money(it, 700), rowAmount(it, R.id.sourceSummaryRows, a.id))
            assertEquals(period, (controller.activeBudget.value as PersistentDataState.Ready).snapshot.period)
            val choices = rootWith(R.id.amount)!!.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices)
            assertTrue((0 until choices.childCount).any { index -> (choices.getChildAt(index) as TextView).text.toString() == "Renamed again" })
        }
    }

    @Test fun financialPanelsOwnLoadingEmptyErrorAndIgnoreChoiceListFailures(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Source", period, 0))
        val category = success(app.repository.createCategory("Category"))
        val screen = launch()
        val controller = model(screen)
        awaitUi(screen) { controller.activeBudget.value is PersistentDataState.Ready }
        val empty = success(app.repository.snapshot())
        success(app.repository.createExpense(NewExpense(29, period.start, source.id, category.id)))
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows).childCount == 1 }
        val populated = success(app.repository.snapshot())
        screen.onActivity {
            var retries = 0
            val renderer = SummaryBreakdownUi(it.findViewById(R.id.summaryPage), onRetry = { retries++ })
            renderer.render(PersistentDataState.Loading)
            assertEquals(it.getString(R.string.expenses_loading), it.findViewById<TextView>(R.id.categorySummaryEmpty).text)
            assertEquals(0, it.findViewById<android.widget.LinearLayout>(R.id.sourceSummaryRows).childCount)
            renderer.render(PersistentDataState.Failed(DataError.StorageFailure(IllegalStateException("fixture"))))
            assertEquals(it.getString(R.string.expenses_load_error), it.findViewById<TextView>(R.id.sourceSummaryEmpty).text)
            it.findViewById<View>(R.id.sourceSummaryEmpty).performClick()
            assertEquals(1, retries)
            renderer.render(PersistentDataState.Ready(empty))
            assertEquals(it.getString(R.string.no_expenses), it.findViewById<TextView>(R.id.categorySummaryEmpty).text)
            renderer.render(PersistentDataState.Ready(populated))
            val categoryCallback = MainActivity::class.java.getDeclaredMethod("renderCategoryState", CategoryListState::class.java).apply { isAccessible = true }
            categoryCallback.invoke(it, CategoryListState.Failed(DataError.StorageFailure(IllegalStateException("choice fixture"))))
            val sourceCallback = MainActivity::class.java.getDeclaredMethod("renderSources", SourceListState::class.java).apply { isAccessible = true }
            sourceCallback.invoke(it, SourceListState.Failed(DataError.StorageFailure(IllegalStateException("choice fixture"))))
            assertEquals(View.GONE, it.findViewById<View>(R.id.categorySummaryEmpty).visibility)
            assertEquals(View.GONE, it.findViewById<View>(R.id.sourceSummaryEmpty).visibility)
            assertEquals(1, it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows).childCount)
        }
    }

    @Test fun breakdownRowsWrapLongNamesAtPhoneWidthInAllLocalesAndThemes() = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Source", period, 0))
        val category = success(app.repository.createCategory("Category"))
        success(app.repository.createExpense(NewExpense(12345, period.start, source.id, category.id, "Long note · הערה ארוכה · Длинная заметка ".repeat(40))))
        val snapshot = success(app.repository.snapshot())
        val screen = launch()
        for (language in listOf("he", "ru", "en")) for (night in listOf(false, true)) {
            app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark", night).commit()
            screen.recreate()
            awaitUi(screen) { (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
            lateinit var dialog: androidx.appcompat.app.AlertDialog
            lateinit var content: android.widget.LinearLayout
            val longName = when (language) { "he" -> "שם ארוך במיוחד ".repeat(12); "ru" -> "Очень длинное название ".repeat(12); else -> "Very long name ".repeat(12) }
            screen.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply {
                    val locale = java.util.Locale.forLanguageTag(language)
                    setLocale(locale); setLayoutDirection(locale)
                }
                val themed = androidx.appcompat.view.ContextThemeWrapper(activity, R.style.AppTheme).apply { applyOverrideConfiguration(config) }
                content = android.widget.LinearLayout(themed).apply {
                    orientation = android.widget.LinearLayout.VERTICAL
                    addView(android.view.LayoutInflater.from(themed).inflate(R.layout.panel_categories, this, false))
                    addView(android.view.LayoutInflater.from(themed).inflate(R.layout.panel_sources, this, false))
                }
                dialog = androidx.appcompat.app.AlertDialog.Builder(themed).setView(android.widget.ScrollView(themed).apply { addView(content) }).create()
                dialog.showWithRoundedSurface()
                dialog.window!!.setLayout((320 * activity.resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                SummaryBreakdownUi(content, onRetry = {}).render(PersistentDataState.Ready(snapshot.copy(
                    categories = listOf(category.copy(name = longName)),
                    sources = snapshot.sources.map { it.copy(source = it.source.copy(name = longName)) })))
            }
            awaitUi(screen) { content.findViewById<TextView>(R.id.breakdownName)?.layout != null }
            screen.onActivity {
                for (container in listOf(R.id.categorySummaryRows, R.id.sourceSummaryRows)) {
                    val row = content.findViewById<android.widget.LinearLayout>(container).getChildAt(0)
                    val header = row.findViewById<View>(R.id.summaryGroupHeader)
                    assertFalse(row.isClickable)
                    assertTrue(header.isClickable)
                    assertTrue(header.isFocusable)
                    assertTrue(header.contentDescription.contains(longName))
                    for (id in listOf(R.id.breakdownName, R.id.breakdownAmount, R.id.breakdownCount)) {
                        val text = row.findViewById<TextView>(id)
                        assertTrue(text.layout.height <= text.height - text.compoundPaddingTop - text.compoundPaddingBottom)
                        for (line in 0 until text.lineCount) assertTrue(text.layout.getLineWidth(line) <= text.width - text.compoundPaddingLeft - text.compoundPaddingRight + 1)
                    }
                    assertEquals(if (language == "he") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, row.layoutDirection)
                    header.performClick()
                    val list = (row as android.widget.LinearLayout).getChildAt(1) as android.widget.LinearLayout
                    assertEquals(1, list.childCount)
                    assertEquals(row.context.getString(R.string.group_expanded), androidx.core.view.ViewCompat.getStateDescription(header))
                }
            }
            awaitUi(screen) { content.findViewById<TextView>(R.id.summaryExpenseNote)?.layout != null }
            screen.onActivity {
                for (container in listOf(R.id.categorySummaryRows, R.id.sourceSummaryRows)) {
                    val row = content.findViewById<android.widget.LinearLayout>(container).getChildAt(0)
                    val list = (row as android.widget.LinearLayout).getChildAt(1) as android.widget.LinearLayout
                    val expense = list.getChildAt(0)
                    assertTrue(list.parent === row)
                    assertNull(list.background)
                    assertNull(expense.background)
                    assertTrue(expense.isClickable && expense.isFocusable)
                    assertTrue(expense.height >= (48 * content.resources.displayMetrics.density).toInt())
                    val note = expense.findViewById<TextView>(R.id.summaryExpenseNote)
                    assertEquals(2, note.maxLines)
                    assertTrue(note.lineCount <= 2)
                    assertTrue(note.layout.getEllipsisCount(note.lineCount - 1) > 0)
                    val secondary = expense.findViewById<TextView>(R.id.summaryExpenseSecondary)
                    assertTrue(secondary.layout.height <= secondary.height)
                    assertTrue(expense.width <= row.width)
                }
                dialog.dismiss()
            }
        }
    }

    @Test fun unifiedCardsKeepSeparateActionsAndContextualExpenseRows(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Source", period, 0))
        val category = success(app.repository.createCategory("Category"))
        success(app.repository.createExpense(NewExpense(120, period.start, source.id, category.id, "Full note")))
        success(app.repository.legacyExpense(30, period.start, source.id))
        val saved = success(app.repository.snapshot())
        // A renderer-only fixture deliberately reuses an ID across the two grouping types.
        val snapshot = saved.copy(
            categories = saved.categories.map { it.copy(id = source.id) },
            expenses = saved.expenses.map { if (it.categoryId == category.id) it.copy(categoryId = source.id) else it },
            categoryBreakdown = saved.categoryBreakdown.map { if (it.categoryId == category.id) it.copy(categoryId = source.id) else it })
        val screen = launch()
        val controller = model(screen)
        awaitUi(screen) { controller.activeBudget.value is PersistentDataState.Ready }
        screen.onActivity { activity ->
            val root = activity.findViewById<View>(R.id.summaryPage)
            val expanded = mutableSetOf<String>()
            var selected: String? = null
            SummaryBreakdownUi(root, {}, { selected = it.id }, expanded).render(PersistentDataState.Ready(snapshot))
            val categoryCard = activity.findViewById<View>(R.id.categorySummaryRows).findViewWithTag<android.widget.LinearLayout>(source.id)
            val sourceCard = activity.findViewById<View>(R.id.sourceSummaryRows).findViewWithTag<android.widget.LinearLayout>(source.id)
            val categoryHeader = categoryCard.findViewById<View>(R.id.summaryGroupHeader)
            val sourceHeader = sourceCard.findViewById<View>(R.id.summaryGroupHeader)
            assertFalse(categoryCard.isClickable)
            categoryHeader.performClick()
            assertEquals(setOf("category:" + source.id), expanded)
            sourceHeader.performClick()
            assertEquals(setOf("category:" + source.id, "source:" + source.id), expanded)
            val categoryRows = inlineRows(activity, true, source.id)!!
            val sourceRows = inlineRows(activity, false, source.id)!!
            assertSame(categoryCard, categoryRows.parent)
            assertSame(sourceCard, sourceRows.parent)
            assertEquals(1, categoryRows.childCount)
            assertEquals(2, sourceRows.childCount)
            val row = categoryRows.getChildAt(0)
            val secondary = row.findViewById<TextView>(R.id.summaryExpenseSecondary)
            assertTrue(secondary.text.contains("Source"))
            assertFalse(secondary.text.contains("Category"))
            assertTrue(row.findViewById<TextView>(R.id.summaryExpenseAmount).textSize > secondary.textSize)
            assertEquals(View.VISIBLE, row.findViewById<View>(R.id.summaryExpenseDivider).visibility)
            row.performClick()
            assertEquals(snapshot.expenses.single { it.categoryId != null }.id, selected)
            assertEquals(2, expanded.size)
            val sourceLabels = (0 until sourceRows.childCount).map {
                sourceRows.getChildAt(it).findViewById<TextView>(R.id.summaryExpenseSecondary).text.toString()
            }
            assertTrue(sourceLabels.any { it.contains(activity.getString(R.string.uncategorized)) })
            assertTrue(sourceLabels.any { it.contains("Category") })
            assertTrue(sourceLabels.none { it.contains("Source") })
            AllExpensesUi(activity.findViewById(R.id.allPanel), {}, {}).render(PersistentDataState.Ready(snapshot))
            val all = activity.findViewById<android.widget.LinearLayout>(R.id.expenseRows)
            assertEquals(2, all.childCount)
            assertEquals(View.GONE, all.getChildAt(0).findViewById<View>(R.id.summaryExpenseDivider).visibility)
            assertEquals(View.VISIBLE, all.getChildAt(1).findViewById<View>(R.id.summaryExpenseDivider).visibility)
            val allCategory = (0 until all.childCount).map(all::getChildAt).single { it.tag == selected }
            assertTrue(allCategory.findViewById<TextView>(R.id.summaryExpenseSecondary).text.let { it.contains("Source") && it.contains("Category") })
            assertEquals("Full note", allCategory.findViewById<TextView>(R.id.summaryExpenseNote).text.toString())
            assertTrue(allCategory.contentDescription.contains("Full note"))
            sourceHeader.performClick()
            assertEquals(setOf("category:" + source.id), expanded)
            assertEquals(1, categoryRows.childCount)
        }
    }

    private fun inlineRows(activity: MainActivity, category: Boolean, id: String?): android.widget.LinearLayout? =
        activity.findViewById<View>(if (category) R.id.categorySummaryRows else R.id.sourceSummaryRows)
            .findViewWithTag((if (category) "category:" else "source:") + id.orEmpty())

    /** Protected system broadcasts cannot be sent by an app. Invoke the registered receiver's
     * callback with a simulated event; never change the user's device clock/zone. */
    @Test fun inlineGroupDetailsStayAboveSummaryAfterMoveAndRecreation(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Same", period, 0))
        val otherSource = success(app.repository.seedMethodAndBudget("Same", period, 0))
        val category = success(app.repository.createCategory("Same"))
        val otherCategory = success(app.repository.createCategory("Same"))
        val first = success(app.repository.createExpense(NewExpense(100, period.start, source.id, category.id)))
        success(app.repository.createExpense(NewExpense(200, period.start, otherSource.id, otherCategory.id)))
        success(app.repository.legacyExpense(50, period.start, source.id))
        val screen = launch()
        val controller = model(screen)
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav1) != null }
        screen.onActivity { rootWith(R.id.nav1)!!.findViewById<View>(R.id.nav1).performClick() }
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows).childCount == 3 }
        var originScroll = 0
        screen.onActivity {
            val scroll = it.findViewById<android.widget.ScrollView>(R.id.pageScroll)
            scroll.scrollTo(0, 70); originScroll = scroll.scrollY
            val rows = it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows)
            (0 until rows.childCount).map(rows::getChildAt).single { row -> row.tag == category.id }.findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, true, category.id)?.childCount == 1 }
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.summaryPage).isShown)
            val card = it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows)
                .findViewWithTag<View>(category.id).findViewById<View>(R.id.summaryGroupHeader)
            assertEquals(it.getString(R.string.group_expanded), androidx.core.view.ViewCompat.getStateDescription(card))
            card.performClick()
            assertNull(inlineRows(it, true, category.id))
            card.performClick()
        }
        awaitUi(screen) { inlineRows(it, true, category.id)?.childCount == 1 }
        screen.onActivity {
            val scroll = it.findViewById<android.widget.ScrollView>(R.id.pageScroll)
            scroll.scrollTo(0, 70); originScroll = scroll.scrollY
            inlineRows(it, true, category.id)!!.getChildAt(0).performClick()
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && controller.expenseCreationState.value?.original?.id == first.id }
        val window = rootWith(R.id.expenseDetailsTitle)
        screen.onActivity {
            val root = rootWith(R.id.expenseDetailsTitle)!!
            assertEquals(View.GONE, (root.findViewById<View>(R.id.expenseDetailsNoteValue).parent as View).visibility)
            assertFalse(controller.expenseCreationState.value!!.editing)
            root.findViewById<View>(R.id.editExpenseDetails).performClick()
            assertEquals("", root.findViewById<EditText>(R.id.expenseDetailsNote).text.toString())
            root.findViewById<EditText>(R.id.expenseDetailsAmount).setText("1.23")
            root.findViewById<EditText>(R.id.expenseDetailsNote).setText("Edited")
            controller.updateExpenseDraft(controller.expenseCreationState.value!!.draft.copy(
                sourceId = otherSource.id, date = period.endExclusive))
            root.findViewById<View>(R.id.expenseDetailsCategory).performClick()
        }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        val beforeCategory = controller.expenseCreationState.value!!.draft
        screen.onActivity {
            rootWith(R.id.categoryPickerRows)!!.findViewWithTag<View>(otherCategory.id).performClick()
            rootWith(R.id.categoryPickerRows)!!.findViewById<View>(android.R.id.button1).performClick()
        }
        awaitUi(screen) { controller.expenseCreationState.value?.draft?.categoryId == otherCategory.id }
        val selected = controller.expenseCreationState.value!!.draft
        assertEquals(beforeCategory.copy(categoryId = selected.categoryId), selected)
        screen.onActivity {
            controller.updateExpenseDraft(selected.copy(categoryId = null))
            assertFalse(controller.canSaveExpense())
            controller.updateExpenseDraft(selected)
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.deleteExpenseDetails).performClick()
        }
        awaitUi(screen) { rootWith(android.R.id.button1) != null && controller.expenseCreationState.value?.confirmingDelete == true }
        val draft = controller.expenseCreationState.value!!.draft
        screen.onActivity { rootWith(android.R.id.button2)!!.findViewById<View>(android.R.id.button2).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.confirmingDelete == false }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        assertTrue(window === rootWith(R.id.expenseDetailsTitle))
        // Saved-data invalidation updates the saved record, never this dirty draft.
        success(app.repository.updateExpense(first.id, NewExpense(110, first.date, first.sourceId, first.categoryId, "External")))
        awaitUi(screen) { controller.expenseCreationState.value?.original?.amountMinor == 110L }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && controller.expenseCreationState.value?.editing == true }
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.categoryTab).isSelected)
            assertEquals(1, inlineRows(it, true, category.id)!!.childCount)
            assertEquals(originScroll, it.findViewById<android.widget.ScrollView>(R.id.pageScroll).scrollY)
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.saveExpenseDetails).performClick()
        }
        awaitUi(screen) { controller.expenseCreationState.value?.editing == false &&
            controller.expenseCreationState.value?.original?.date == period.endExclusive }
        assertNotNull(rootWith(R.id.expenseDetailsTitle))
        assertEquals(first.copy(amountMinor = 123, date = period.endExclusive, sourceId = otherSource.id,
            categoryId = otherCategory.id, note = "Edited"), success(app.repository.expense(first.id)))
        awaitUi(screen) { inlineRows(it, true, category.id) == null }
        screen.onActivity {
            assertEquals(View.GONE, rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.expenseDetailsAmount).visibility)
            assertTrue(rootWith(R.id.expenseDetailsTitle)!!.findViewById<TextView>(R.id.expenseDetailsNoteValue).text.contains("Edited"))
        }
        app.clock.now = period.endExclusive.atTime(12, 0).atZone(app.clock.zone).toInstant()
        screen.onActivity { controller.refresh() }
        awaitUi(screen) { (controller.activeBudget.value as? PersistentDataState.Ready)?.snapshot?.period?.start == period.endExclusive }
        awaitUi(screen) { inlineRows(it, true, null)?.childCount == 1 ||
            it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows).childCount == 1 }
        screen.onActivity { detailsBack(it) }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) == null }
        awaitUi(screen) { it.findViewById<View>(R.id.summaryPage).isShown }
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.categoryTab).isSelected)
            val scroll = it.findViewById<android.widget.ScrollView>(R.id.pageScroll)
            val available = (scroll.getChildAt(0).height - scroll.height + scroll.paddingTop + scroll.paddingBottom).coerceAtLeast(0)
            assertEquals(minOf(originScroll, available), scroll.scrollY)
            assertFalse(it.findViewById<View>(R.id.previousMonth).isEnabled)
        }
    }

    @Test fun sourceAndNullGroupDetailsDeleteWithFailureRecreationAndMissingRecord(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Same", period, 100))
        val otherSource = success(app.repository.seedMethodAndBudget("Same", period, 0))
        val category = success(app.repository.createCategory("Food"))
        val first = success(app.repository.legacyExpense(30, period.start, source.id))
        val second = success(app.repository.createExpense(NewExpense(20, period.start, source.id, category.id)))
        val other = success(app.repository.legacyExpense(10, period.start, otherSource.id))
        val screen = launch()
        val controller = model(screen)
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav1) != null }
        screen.onActivity { rootWith(R.id.nav1)!!.findViewById<View>(R.id.nav1).performClick() }
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.sourceSummaryRows).childCount == 2 }
        screen.onActivity {
            it.findViewById<View>(R.id.sourceTab).performClick()
            val rows = it.findViewById<android.widget.LinearLayout>(R.id.sourceSummaryRows)
            (0 until rows.childCount).map(rows::getChildAt).single { row -> row.tag == source.id }.findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, false, source.id)?.childCount == 2 }
        screen.onActivity {
            val rows = inlineRows(it, false, source.id)!!
            (0 until rows.childCount).map(rows::getChildAt).single { row -> row.tag == first.id }.performClick()
        }
        awaitUi(screen) { controller.expenseCreationState.value?.original?.id == first.id }
        screen.onActivity {
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.editExpenseDetails).performClick()
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<EditText>(R.id.expenseDetailsNote).setText("Dirty")
            controller.requestExpenseDeletion()
        }
        val draft = controller.expenseCreationState.value!!.draft
        awaitUi(screen) { rootWith(android.R.id.button1) != null }
        app.database.openHelper.writableDatabase.execSQL("CREATE TRIGGER fail_delete_ui BEFORE DELETE ON expenses BEGIN SELECT RAISE(ABORT, 'fixture'); END")
        screen.onActivity { rootWith(android.R.id.button1)!!.findViewById<View>(android.R.id.button1).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.error is DataError.StorageFailure }
        assertTrue(controller.expenseCreationState.value!!.editing)
        assertEquals(draft, controller.expenseCreationState.value!!.draft)
        assertEquals(first, success(app.repository.expense(first.id)))
        app.database.openHelper.writableDatabase.execSQL("DROP TRIGGER fail_delete_ui")
        screen.onActivity { controller.requestExpenseDeletion() }
        val (release, held) = gate()
        try {
            screen.onActivity {
                controller.confirmExpenseDeletion()
                assertNull(controller.confirmExpenseDeletion()); assertNull(controller.submitExpenseCreation())
            }
            screen.recreate()
            awaitUi(screen) { rootWith(android.R.id.button1) != null && controller.expenseCreationState.value?.submitting == true }
            assertEquals(draft, controller.expenseCreationState.value!!.draft)
            screen.onActivity {
                assertFalse(rootWith(android.R.id.button1)!!.findViewById<View>(android.R.id.button1).isEnabled)
                assertNull(controller.confirmExpenseDeletion())
            }
        } finally { release.complete(Unit); held.join() }
        awaitUi(screen) { controller.expenseCreationState.value == null &&
            inlineRows(it, false, source.id)?.childCount == 1 }
        assertNull(rootWith(R.id.expenseDetailsTitle))
        assertEquals(setOf(second.id, other.id), success(app.repository.snapshot()).expenses.map { it.id }.toSet())
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.sourceTab).isSelected)
            it.findViewById<View>(R.id.categoryTab).performClick()
            val rows = it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows)
            (0 until rows.childCount).map(rows::getChildAt).single { row -> row.tag == null }.findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, true, null)?.childCount == 1 }
        screen.onActivity { inlineRows(it, true, null)!!.getChildAt(0).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.original?.id == other.id }
        assertFalse(controller.expenseCreationState.value!!.editing)
        success(app.repository.deleteExpense(other.id))
        awaitUi(screen) { controller.expenseCreationState.value?.error is DataError.MissingEntity }
        screen.onActivity {
            assertEquals(View.GONE, rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.editExpenseDetails).visibility)
            detailsBack(it)
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) == null &&
            inlineRows(it, true, null) == null }
        assertEquals(listOf(second), success(app.repository.snapshot()).expenses)
        assertEquals(100L, success(app.repository.snapshot()).totalAllocatedMinor)
    }

    @Test fun longInlineListKeepsSummaryScrollAndExpansionUnderDetails(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Source", period, 0))
        val category = success(app.repository.createCategory("Same"))
        val otherCategory = success(app.repository.createCategory("Same"))
        repeat(45) { success(app.repository.createExpense(NewExpense(10, period.start, source.id, category.id, "Row $it"))) }
        val single = success(app.repository.createExpense(NewExpense(20, period.start, source.id, otherCategory.id)))
        val screen = launch()
        val controller = model(screen)
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav1) != null }
        screen.onActivity { rootWith(R.id.nav1)!!.findViewById<View>(R.id.nav1).performClick() }
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows).childCount == 2 }
        screen.onActivity { it.findViewById<View>(R.id.categorySummaryRows).findViewWithTag<View>(category.id).findViewById<View>(R.id.summaryGroupHeader).performClick() }
        awaitUi(screen) { inlineRows(it, true, category.id)?.childCount == 45 }
        var top = 0
        screen.onActivity {
            val list = inlineRows(it, true, category.id)!!
            var ancestor: View? = list
            var scrollCount = 0
            while (ancestor != null) {
                if (ancestor is android.widget.ScrollView) scrollCount++
                ancestor = ancestor.parent as? View
            }
            assertEquals(1, scrollCount)
            val scroll = it.findViewById<android.widget.ScrollView>(R.id.pageScroll)
            scroll.scrollTo(0, 600); top = scroll.scrollY
            assertTrue(top > 0)
            list.getChildAt(12).performClick()
        }
        awaitUi(screen) { controller.expenseCreationState.value?.original != null }
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.summaryPage).isShown)
            assertEquals(top, it.findViewById<android.widget.ScrollView>(R.id.pageScroll).scrollY)
            assertTrue((rootWith(R.id.expenseDetailsTitle)!!.layoutParams as android.view.WindowManager.LayoutParams).flags and
                android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0)
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.editExpenseDetails).performClick()
            controller.updateExpenseDraft(controller.expenseCreationState.value!!.draft.copy(note = "Discard"))
            detailsBack(it)
        }
        awaitUi(screen) { controller.expenseCreationState.value?.editing == false }
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && inlineRows(it, true, category.id)?.childCount == 45 }
        screen.onActivity {
            val config = Configuration(it.resources.configuration)
            config.uiMode = config.uiMode xor Configuration.UI_MODE_NIGHT_MASK
            it.onConfigurationChanged(config)
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null && inlineRows(it, true, category.id)?.childCount == 45 }
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.categoryTab).isSelected)
            assertEquals(top, it.findViewById<android.widget.ScrollView>(R.id.pageScroll).scrollY)
            detailsBack(it)
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) == null }
        screen.onActivity {
            assertEquals(top, it.findViewById<android.widget.ScrollView>(R.id.pageScroll).scrollY)
            it.findViewById<View>(R.id.categorySummaryRows).findViewWithTag<View>(otherCategory.id).findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, true, otherCategory.id)?.childCount == 1 }
        screen.onActivity { inlineRows(it, true, otherCategory.id)!!.getChildAt(0).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.original?.id == single.id }
        screen.onActivity { controller.editExpense(); controller.requestExpenseDeletion(); controller.confirmExpenseDeletion() }
        awaitUi(screen) { controller.expenseCreationState.value == null && inlineRows(it, true, otherCategory.id) == null }
        screen.onActivity {
            assertTrue(it.findViewById<View>(R.id.categoryTab).isSelected)
            assertEquals(45, inlineRows(it, true, category.id)!!.childCount)
            it.findViewById<View>(R.id.sourceTab).performClick()
            it.findViewById<View>(R.id.sourceSummaryRows).findViewWithTag<View>(source.id).findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, false, source.id)?.childCount == 45 }
        val moved = success(app.repository.snapshot()).expenses.first()
        success(app.repository.updateExpense(moved.id, NewExpense(moved.amountMinor, moved.date, moved.sourceId, otherCategory.id, moved.note)))
        awaitUi(screen) { inlineRows(it, true, category.id)?.childCount == 44 && inlineRows(it, true, otherCategory.id)?.childCount == 1 }
        screen.onActivity {
            it.findViewById<View>(R.id.categoryTab).performClick()
            assertEquals(44, inlineRows(it, true, category.id)!!.childCount)
            assertEquals(1, inlineRows(it, true, otherCategory.id)!!.childCount)
        }
    }

    @Test fun detailsActionsFollowLocaleAndFitNarrowLightAndDarkWindows(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.seedMethodAndBudget("Long source name ".repeat(4), period, 0))
        val category = success(app.repository.createCategory("Long category name ".repeat(4)))
        val record = success(app.repository.createExpense(NewExpense(123, period.start, source.id, category.id)))
        val screen = launch()
        for (language in listOf("he", "en", "ru")) for (dark in listOf(false, true)) {
            app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark", dark).commit()
            screen.recreate()
            awaitUi(screen) { (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO }
            lateinit var window: ExpenseDetailsDialog
            screen.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply {
                    val locale = java.util.Locale.forLanguageTag(language); setLocale(locale); setLayoutDirection(locale)
                }
                val themed = androidx.appcompat.view.ContextThemeWrapper(activity, R.style.AppTheme).apply { applyOverrideConfiguration(config) }
                window = ExpenseDetailsDialog(themed, {}, {}, {}, {}, {}, {}, {}, {}, {}, {})
                window.show()
                val state = ExpenseCreationState(ExpenseDraft.fromExpense(record), expenseId = record.id, original = record)
                window.render(state, CategoryListState.Ready(listOf(category)), SourceListState.Ready(listOf(source)), false)
                val note = window.dialog.findViewById<TextView>(R.id.expenseDetailsNoteValue)!!
                assertEquals(View.GONE, (note.parent as View).visibility)
                assertEquals(View.GONE, window.dialog.findViewById<View>(R.id.expenseDetailsAmount)!!.visibility)
                window.render(state.copy(editing = true), CategoryListState.Ready(listOf(category)), SourceListState.Ready(listOf(source)), true)
                window.dialog.window!!.setLayout((320 * activity.resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                assertEquals("", window.dialog.findViewById<EditText>(R.id.expenseDetailsNote)!!.text.toString())
            }
            awaitUi(screen) { window.dialog.findViewById<View>(R.id.expenseDetailsActions)?.width != 0 }
            screen.onActivity { activity ->
                val primary = window.dialog.findViewById<View>(R.id.expenseDetailsPrimaryActions)!!
                val delete = window.dialog.findViewById<TextView>(R.id.deleteExpenseDetails)!!
                val primaryPosition = IntArray(2); val deletePosition = IntArray(2)
                primary.getLocationOnScreen(primaryPosition); delete.getLocationOnScreen(deletePosition)
                if (language == "he") assertTrue(primaryPosition[0] >= deletePosition[0] + delete.width)
                else assertTrue(primaryPosition[0] + primary.width <= deletePosition[0])
                for (id in listOf(R.id.saveExpenseDetails, R.id.cancelExpenseDetails, R.id.deleteExpenseDetails)) {
                    val button = window.dialog.findViewById<TextView>(id)!!
                    assertTrue(button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    assertTrue(button.width > 0)
                    assertTrue(button.layout.getLineBottom(button.layout.lineCount - 1) <=
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
                window.dispose()
            }
        }
    }

    private fun detailsBack(activity: MainActivity) {
        val field = MainActivity::class.java.getDeclaredField("expenseDetailsDialog").apply { isAccessible = true }
        (field.get(activity) as ExpenseDetailsDialog).dialog.onBackPressedDispatcher.onBackPressed()
    }

    private fun dispatchDate(screen: ActivityScenario<MainActivity>, action: String) {
        screen.onActivity {
            val field = MainActivity::class.java.getDeclaredField("dateChanges").apply { isAccessible = true }
            (field.get(it) as android.content.BroadcastReceiver).onReceive(it, Intent(action))
        }
    }

    /** Historical fixtures bypass new-write validation deliberately. */
    private suspend fun ManyManagerRepository.legacyExpense(amount: Long, date: java.time.LocalDate, source: String): DataResult<Expense> {
        val calculator = success(snapshot()).calculator.id
        val id = java.util.UUID.randomUUID().toString()
        val db = app.database
        db.dao().insertExpense(com.example.simple.manymanager.data.local.ExpenseEntity(
            calculator, id, amount, date.toEpochDay(), null, source))
        return expense(id)
    }

    private fun <T> success(result: DataResult<T>): T {
        assertTrue("Expected success, got $result", result is DataResult.Success)
        return (result as DataResult.Success).value
    }

    private suspend fun ManyManagerRepository.seedMethodAndBudget(name: String, period: BudgetPeriod, amount: Long): DataResult<BudgetSource> {
        success(setPeriodBudget(period, Math.addExact(success(snapshot(period)).totalAllocatedMinor, amount)))
        return createPaymentMethod(name)
    }

    @Test fun styledBudgetDialogKeepsDraftAndFitsLocalesAndThemes(): Unit = runBlocking {
        val screen = launch()
        openBudget(screen)
        val controller = model(screen)
        fun dialog(activity: MainActivity): androidx.appcompat.app.AlertDialog {
            val field = MainActivity::class.java.getDeclaredField("periodBudgetDialog").apply { isAccessible = true }
            return field.get(activity) as androidx.appcompat.app.AlertDialog
        }
        for (language in listOf("he", "en", "ru")) for (dark in listOf(false, true)) {
            app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE)
                .edit().putBoolean("dark", dark).commit()
            screen.onActivity {
                androidx.appcompat.app.AppCompatDelegate.setApplicationLocales(
                    androidx.core.os.LocaleListCompat.forLanguageTags(language))
            }
            screen.recreate()
            awaitUi(screen) {
                it.resources.configuration.locales[0].language == language &&
                    (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                    if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            awaitUi(screen) { it.findViewById<View>(R.id.budgetEditTotal).isEnabled }
            screen.onActivity { it.findViewById<View>(R.id.budgetEditTotal).performClick() }
            awaitUi(screen) { rootWith(R.id.periodBudgetSave) != null }
            screen.onActivity { activity ->
                dialog(activity).window!!.setLayout(
                    (288 * activity.resources.displayMetrics.density).toInt(),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                dialog(activity).findViewById<EditText>(R.id.periodBudgetAmount)!!.setText("20000")
            }
            awaitUi(screen) { rootWith(R.id.periodBudgetSave)?.findViewById<View>(R.id.periodBudgetSave)?.width != 0 }
            screen.onActivity { activity ->
                val window = dialog(activity)
                val input = window.findViewById<EditText>(R.id.periodBudgetAmount)!!
                assertNotNull(input.background)
                assertEquals(View.TEXT_DIRECTION_LTR, input.textDirection)
                assertTrue(window.findViewById<TextView>(R.id.periodBudgetTitle)!!.text.isNotBlank())
                val save = window.findViewById<TextView>(R.id.periodBudgetSave)!!
                val cancel = window.findViewById<TextView>(R.id.periodBudgetCancel)!!
                assertEquals(activity.getString(R.string.update_budget), save.text.toString())
                val savePosition = IntArray(2); val cancelPosition = IntArray(2)
                save.getLocationOnScreen(savePosition); cancel.getLocationOnScreen(cancelPosition)
                if (language == "he") assertTrue(savePosition[0] + save.width <= cancelPosition[0])
                else assertTrue(cancelPosition[0] + cancel.width <= savePosition[0])
                for (button in listOf(save, cancel)) {
                    assertTrue(button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    assertTrue(button.layout.getLineBottom(button.layout.lineCount - 1) <=
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
            }
            screen.onActivity { activity ->
                val view = dialog(activity).window!!.decorView
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                val path = java.io.File(checkNotNull(app.getExternalFilesDir(null)),
                    "budget-dialog-${language}-${dark}.png")
                path.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
            }
            screen.onActivity { dialog(it).findViewById<View>(R.id.periodBudgetCancel)!!.performClick() }
            awaitUi(screen) { controller.periodBudgetDraft.value == null }
            assertEquals(0L, success(app.repository.snapshot()).totalAllocatedMinor)
        }
        screen.onActivity {
            it.findViewById<View>(R.id.budgetEditTotal).performClick()
            dialog(it).findViewById<EditText>(R.id.periodBudgetAmount)!!.setText("123.45")
        }
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.periodBudgetSave) != null }
        screen.onActivity {
            assertEquals("123.45", dialog(it).findViewById<EditText>(R.id.periodBudgetAmount)!!.text.toString())
            dialog(it).findViewById<View>(R.id.periodBudgetSave)!!.performClick()
        }
        awaitUi(screen) { controller.periodBudgetDraft.value == null }
        assertEquals(12345L, success(app.repository.snapshot()).totalAllocatedMinor)
    }

    @Test fun categoryHierarchyBrowsingConfirmationManagementAndLegacyEditing(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.createPaymentMethod("Cash"))
        val screen = launch()
        val controller = model(screen)
        awaitUi(screen) { controller.categories.value is CategoryListState.Ready && controller.sources.value is SourceListState.Ready }
        screen.onActivity { it.findViewById<View>(R.id.addExpense).performClick() }
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity { activity ->
            val root = rootWith(R.id.amount)!!
            root.findViewById<EditText>(R.id.amount).setText("1.23")
            root.findViewById<EditText>(R.id.expenseNote).setText("Keep draft")
            root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).performClick()
            assertEquals(activity.getString(R.string.category_management_required), root.findViewById<TextView>(R.id.expenseCategoriesEmpty).text)
            assertFalse(root.findViewById<View>(R.id.saveExpense).isEnabled)
            assertNull(controller.submitExpenseCreation())
            root.findViewById<View>(R.id.closeDialog).performClick()
        }
        awaitUi(screen) { rootWith(R.id.amount) == null }
        // Create through Category Management, then open the main category and create a real child.
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav4) != null }
        screen.onActivity { rootWith(R.id.nav4)!!.findViewById<View>(R.id.nav4).performClick() }
        awaitUi(screen) { it.findViewById<View>(R.id.categoriesPage).isShown }
        screen.onActivity { it.findViewById<View>(R.id.categoryAdd).performClick() }
        awaitUi(screen) { rootWith(R.id.categoryNameInput) != null }
        screen.onActivity {
            rootWith(R.id.categoryNameInput)!!.findViewById<EditText>(R.id.categoryNameInput).setText("Food")
            rootWith(R.id.categoryNameInput)!!.findViewById<View>(android.R.id.button1).performClick()
        }
        awaitUi(screen) { controller.categoryCreation.value == null && it.findViewById<android.widget.LinearLayout>(R.id.categoryRows).childCount == 1 }
        val parent = success(app.repository.observeCategories().first()).single()
        assertNull(parent.parentId)
        screen.onActivity {
            it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>(parent.id).performClick()
            assertEquals(it.getString(R.string.add_subcategory), it.findViewById<TextView>(R.id.categoryAdd).text)
            it.findViewById<View>(R.id.categoryAdd).performClick()
        }
        awaitUi(screen) { rootWith(R.id.categoryNameInput) != null }
        screen.onActivity {
            rootWith(R.id.categoryNameInput)!!.findViewById<EditText>(R.id.categoryNameInput).setText("Food")
            rootWith(R.id.categoryNameInput)!!.findViewById<View>(android.R.id.button1).performClick()
        }
        awaitUi(screen) { controller.categoryCreation.value == null && (controller.categories.value as? CategoryListState.Ready)?.categories?.size == 2 }
        val child = success(app.repository.observeCategories().first()).single { it.parentId == parent.id }
        screen.recreate()
        awaitUi(screen) { it.findViewById<TextView>(R.id.categoryPageTitle).text.toString() == parent.name }
        screen.onActivity {
            assertEquals(1, it.findViewById<android.widget.LinearLayout>(R.id.categoryRows).childCount)
            it.findViewById<View>(R.id.categoryBack).performClick()
            it.findViewById<View>(R.id.categoryBack).performClick()
            it.findViewById<View>(R.id.addExpense).performClick()
        }
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity {
            val root = rootWith(R.id.amount)!!
            root.findViewById<EditText>(R.id.amount).setText("1.23")
            root.findViewById<EditText>(R.id.expenseNote).setText("Keep draft")
            root.findViewById<android.widget.LinearLayout>(R.id.expenseSourceChoices).getChildAt(0).performClick()
        }
        val initial = controller.expenseCreationState.value!!.draft
        fun openParent() = rootWith(R.id.amount)!!.findViewById<View>(R.id.expenseCategoryChoices).findViewWithTag<View>(parent.id).performClick()
        screen.onActivity { openParent() }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        screen.onActivity {
            val picker = rootWith(R.id.categoryPickerRows)!!
            assertEquals(1, picker.findViewById<android.widget.LinearLayout>(R.id.categoryPickerRows).childCount)
            assertEquals(initial, controller.expenseCreationState.value!!.draft)
            picker.findViewById<View>(android.R.id.button2).performClick()
        }
        assertEquals(initial, controller.expenseCreationState.value!!.draft)
        screen.onActivity { openParent() }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        screen.onActivity { rootWith(R.id.categoryPickerRows)!!.findViewById<View>(android.R.id.button1).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.draft?.categoryId == parent.id }
        val direct = controller.expenseCreationState.value!!.draft
        assertEquals(initial.copy(categoryId = parent.id), direct)
        screen.onActivity { openParent() }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        screen.onActivity { rootWith(R.id.categoryPickerRows)!!.findViewById<View>(R.id.categoryPickerBack).performClick() }
        assertEquals(direct, controller.expenseCreationState.value!!.draft)
        screen.onActivity { openParent() }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        screen.onActivity { rootWith(R.id.categoryPickerRows)!!.findViewWithTag<View>(child.id).performClick() }
        awaitUi(screen) { controller.expenseCreationState.value?.draft?.categoryId == child.id }
        assertEquals(initial.copy(categoryId = child.id), controller.expenseCreationState.value!!.draft)
        assertEquals(0, success(app.repository.snapshot()).expenseCount)
        screen.recreate()
        awaitUi(screen) { rootWith(R.id.amount) != null }
        screen.onActivity {
            val row = rootWith(R.id.amount)!!.findViewById<View>(R.id.expenseCategoryChoices).findViewWithTag<TextView>(parent.id)
            assertTrue(row.isSelected)
            assertTrue(row.text.toString().contains("Food / Food"))
            rootWith(R.id.amount)!!.findViewById<View>(R.id.saveExpense).performClick()
        }
        awaitUi(screen) { controller.expenseCreationState.value == null }
        val savedDate = initial.date!!
        val saved = success(app.repository.snapshot(success(app.repository.periodContaining(savedDate)))).expenses.single()
        assertEquals(child.id, saved.categoryId)
        val legacy = success(app.repository.legacyExpense(50, period.start, source.id))
        screen.onActivity { controller.openExpenseDetails(legacy.id) }
        awaitUi(screen) { controller.expenseCreationState.value?.original?.id == legacy.id }
        screen.onActivity {
            controller.editExpense()
            assertFalse(controller.canSaveExpense())
            assertNull(controller.submitExpenseCreation())
            rootWith(R.id.expenseDetailsTitle)!!.findViewById<View>(R.id.expenseDetailsCategory).performClick()
        }
        awaitUi(screen) { rootWith(R.id.categoryPickerRows) != null }
        screen.onActivity {
            rootWith(R.id.categoryPickerRows)!!.findViewWithTag<View>(parent.id).performClick()
            rootWith(R.id.categoryPickerRows)!!.findViewById<View>(android.R.id.button1).performClick()
        }
        awaitUi(screen) { controller.canSaveExpense() }
        screen.onActivity { controller.submitExpenseCreation() }
        awaitUi(screen) { controller.expenseCreationState.value?.editing == false }
        assertEquals(parent.id, success(app.repository.expense(legacy.id)).categoryId)
    }

    @Test fun categoryPickerWrapsLongNamesAndFooterInNarrowLocalesAndThemes(): Unit = runBlocking {
        val screen = launch()
        val categories = listOf(Category("test", "parent", "קטגוריה ארוכה · Long category · Длинная категория ".repeat(3)),
            Category("test", "child", "תת־קטגוריה · Subcategory · Подкатегория ".repeat(4), "parent"))
        for (language in listOf("he", "en", "ru")) for (dark in listOf(false, true)) {
            app.getSharedPreferences("appearance", android.content.Context.MODE_PRIVATE).edit().putBoolean("dark", dark).commit()
            screen.recreate()
            lateinit var picker: CategoryPickerDialog
            screen.onActivity { activity ->
                val config = Configuration(activity.resources.configuration).apply {
                    val locale = java.util.Locale.forLanguageTag(language); setLocale(locale); setLayoutDirection(locale)
                }
                val themed = androidx.appcompat.view.ContextThemeWrapper(activity, R.style.AppTheme).apply { applyOverrideConfiguration(config) }
                picker = CategoryPickerDialog(themed, categories, "child", "parent") { fail("Browsing must not confirm") }
                picker.show()
                picker.dialog.window!!.setLayout((320 * activity.resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            awaitUi(screen) { picker.dialog.findViewById<View>(android.R.id.button1)?.width != 0 }
            screen.onActivity { activity ->
                val confirm = picker.dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE)
                assertEquals(picker.dialog.context.getString(R.string.select_main_category), confirm.text.toString())
                for (id in listOf(android.R.id.button1, android.R.id.button2, R.id.categoryPickerBack)) {
                    val button = picker.dialog.findViewById<TextView>(id)!!
                    assertTrue(button.height >= (48 * activity.resources.displayMetrics.density).toInt())
                    assertTrue(button.layout.getLineBottom(button.layout.lineCount - 1) <=
                        button.height - button.compoundPaddingTop - button.compoundPaddingBottom)
                }
                val row = picker.dialog.findViewById<android.widget.LinearLayout>(R.id.categoryPickerRows)!!.getChildAt(0) as TextView
                assertTrue(row.layout.lineCount > 1)
                assertTrue(row.isSelected)
                val view = picker.dialog.window!!.decorView
                val image = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
                view.draw(android.graphics.Canvas(image))
                val path = java.io.File(checkNotNull(app.getExternalFilesDir(null)), "category-picker-${language}-${dark}.png")
                path.outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                image.recycle()
                picker.dispose()
            }
        }
    }

    @Test fun hierarchySummaryGroupsDirectAndChildOnceAndShowsSavedPaths(): Unit = runBlocking {
        val period = success(app.repository.currentPeriod())
        val source = success(app.repository.createPaymentMethod("Cash"))
        val parent = success(app.repository.createCategory("Food"))
        val child = success(app.repository.createCategory("Supermarket", parent.id))
        val direct = success(app.repository.createExpense(NewExpense(10000, period.start, source.id, parent.id)))
        val nested = success(app.repository.createExpense(NewExpense(25000, period.start, source.id, child.id)))
        success(app.repository.legacyExpense(500, period.start, source.id))
        val screen = launch()
        screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
        awaitUi(screen) { rootWith(R.id.nav1) != null }
        screen.onActivity { rootWith(R.id.nav1)!!.findViewById<View>(R.id.nav1).performClick() }
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.categorySummaryRows).childCount == 2 }
        screen.onActivity { activity ->
            val row = activity.findViewById<View>(R.id.categorySummaryRows).findViewWithTag<View>(parent.id)
            assertEquals(activity.getString(R.string.ils_amount, BudgetAmounts.number(35000, activity.resources.configuration.locales[0])),
                row.findViewById<TextView>(R.id.breakdownAmount).text)
            row.findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, true, parent.id)?.childCount == 4 }
        screen.onActivity { activity ->
            val sections = inlineRows(activity, true, parent.id)!!
            assertTrue((sections.getChildAt(0) as TextView).text.startsWith(activity.getString(R.string.category_general)))
            assertTrue((sections.getChildAt(2) as TextView).text.startsWith(child.name))
            val directRows = sections.getChildAt(1) as android.widget.LinearLayout
            val childRows = sections.getChildAt(3) as android.widget.LinearLayout
            assertEquals(1, directRows.childCount)
            assertEquals(1, childRows.childCount)
            assertEquals(direct.id, directRows.getChildAt(0).tag)
            assertEquals(nested.id, childRows.getChildAt(0).tag)
            childRows.getChildAt(0).performClick()
        }
        awaitUi(screen) { rootWith(R.id.expenseDetailsTitle) != null }
        screen.onActivity { activity ->
            assertEquals("Food / Supermarket", rootWith(R.id.expenseDetailsTitle)!!.findViewById<TextView>(R.id.expenseDetailsCategoryValue).text.toString())
            detailsBack(activity)
            activity.findViewById<View>(R.id.allTab).performClick()
        }
        awaitUi(screen) { it.findViewById<android.widget.LinearLayout>(R.id.expenseRows).childCount == 3 }
        screen.onActivity { activity ->
            val row = activity.findViewById<View>(R.id.expenseRows).findViewWithTag<View>(nested.id)
            assertTrue(row.findViewById<TextView>(R.id.summaryExpenseSecondary).text.contains("Food / Supermarket"))
            activity.findViewById<View>(R.id.sourceTab).performClick()
            activity.findViewById<View>(R.id.sourceSummaryRows).findViewWithTag<View>(source.id).findViewById<View>(R.id.summaryGroupHeader).performClick()
        }
        awaitUi(screen) { inlineRows(it, false, source.id)?.childCount == 3 }
        screen.onActivity { activity ->
            val row = inlineRows(activity, false, source.id)!!.findViewWithTag<View>(nested.id)
            assertTrue(row.findViewById<TextView>(R.id.summaryExpenseSecondary).text.contains("Food / Supermarket"))
        }
        assertEquals(2, success(app.repository.observeCategories().first()).size)
        assertEquals(35500L, success(app.repository.snapshot()).totalSpentMinor)
        assertEquals(3, success(app.repository.snapshot()).categoryBreakdown.sumOf { it.expenseCount })
    }

    @Test fun singleBudgetAndNameOnlyMethodsSurviveRecreation() = runBlocking {
        val screen = launch()
        openBudget(screen)
        val controller = model(screen)
        screen.onActivity { it.findViewById<View>(R.id.budgetEditTotal).performClick() }
        awaitUi(screen) { controller.periodBudgetDraft.value != null }
        screen.onActivity {
            controller.updatePeriodBudget("20000")
            controller.submitPeriodBudget()
        }
        awaitUi(screen) { controller.periodBudgetDraft.value == null }
        addForm(screen, "Cash", "")
        screen.onActivity { rootWith(R.id.addSourceName)!!.findViewById<View>(android.R.id.button1).performClick() }
        awaitUi(screen) { controller.sourceCreationState.value == null }
        val cash = success(app.repository.observeSources().first()).single()
        val card = success(app.repository.createPaymentMethod("Card"))
        val period = success(app.repository.currentPeriod())
        val category = success(app.repository.createCategory("Food"))
        success(app.repository.createExpense(NewExpense(10000, period.start, cash.id, category.id)))
        success(app.repository.createExpense(NewExpense(25000, period.start, card.id, category.id)))
        awaitUi(screen) {
            (controller.activeBudget.value as? PersistentDataState.Ready)?.snapshot?.remainingMinor == 1965000L
        }
        screen.recreate()
        awaitUi(screen) {
            it.findViewById<TextView>(R.id.homeBudgetRemaining).text.toString().contains(
                BudgetAmounts.number(1965000, it.resources.configuration.locales[0]))
        }
        screen.onActivity {
            it.findViewById<android.widget.LinearLayout>(R.id.budgetSourceRows).getChildAt(0).performClick()
        }
        awaitUi(screen) { rootWith(R.id.editSourceName) != null }
        screen.onActivity {
            rootWith(R.id.editSourceName)!!.findViewById<EditText>(R.id.editSourceName).setText("Wallet")
            rootWith(R.id.editSourceName)!!.findViewById<View>(android.R.id.button1).performClick()
        }
        awaitUi(screen) { controller.sourceEditingState.value == null }
        assertEquals(1965000L, success(app.repository.snapshot()).remainingMinor)
        assertEquals("Wallet", success(app.repository.observeSources().first()).first().name)
    }
}

package com.example.simple.manymanager

import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simple.manymanager.data.local.ManyManagerDao
import com.example.simple.manymanager.domain.*
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TwoCalculatorUiTest {
    private val app get() = ApplicationProvider.getApplicationContext<BudgetTestApplication>()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private fun <T> saved(result: DataResult<T>) = (result as DataResult.Success).value
    private fun root(id: Int): View? = WindowInspector.getGlobalWindowViews()
        .lastOrNull { it.findViewById<View>(id)?.isShown == true }
    private fun await(condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            instrumentation.runOnMainSync { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Calculator UI did not become ready")
    }
    private lateinit var mainCategory: Category
    private lateinit var secondCategory: Category
    private lateinit var mainMethod: BudgetSource
    private lateinit var secondMethod: BudgetSource

    @Before fun prepare(): Unit = runBlocking {
        app.databaseInitialization.await().getOrThrow()
        app.database.clearAllTables()
        app.database.dao().initializeCalculators()
        app.clock.now = Instant.parse("2026-11-10T12:00:00Z")
        app.clock.currentZone = ZoneOffset.UTC
        val main = app.repository
        val second = main.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID)
        mainCategory = saved(main.createCategory("MAIN food"))
        secondCategory = saved(second.createCategory("SECOND food"))
        mainMethod = saved(main.createPaymentMethod("MAIN cash"))
        secondMethod = saved(second.createPaymentMethod("SECOND cash"))
        val period = saved(main.currentPeriod())
        saved(main.setPeriodBudget(period, 2000000))
        saved(second.setPeriodBudget(period, 50000))
        saved(main.createExpense(NewExpense(10000, period.start, mainMethod.id, mainCategory.id)))
    }

    @Test fun homeButtonsOpenTheirOwnCalculatorAndMenuOrderIsCorrect(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            lateinit var activity: MainActivity
            lateinit var model: PersistentDataViewModel
            screen.onActivity { activity = it; model = ViewModelProvider(it)[PersistentDataViewModel::class.java] }
            await { model.categories.value is CategoryListState.Ready && model.sources.value is SourceListState.Ready }
            screen.onActivity {
                assertEquals(ManyManagerDao.LOCAL_CALCULATOR_ID, it.calculatorId)
                it.findViewById<View>(R.id.addExpense).performClick()
            }
            await { root(R.id.amount) != null }
            screen.onActivity {
                val form = root(R.id.amount)!!
                assertEquals(it.getString(R.string.main_calculator), form.findViewById<TextView>(R.id.expenseCalculatorLabel).text)
                assertEquals("MAIN cash", (form.findViewById<LinearLayout>(R.id.expenseSourceChoices).getChildAt(0) as TextView).text)
                assertNull(form.findViewById<View>(R.id.expenseCategoryChoices).findViewWithTag<View>(secondCategory.id))
                form.findViewById<View>(R.id.closeDialog).performClick()
            }
            await { root(R.id.amount) == null }
            val monitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            var secondActivity: MainActivity? = null
            try {
                screen.onActivity { it.findViewById<View>(R.id.addSecondExpense).performClick() }
                secondActivity = monitor.waitForActivityWithTimeout(15000) as? MainActivity
                assertNotNull(secondActivity)
                await {
                    root(R.id.amount)?.findViewById<TextView>(R.id.expenseCalculatorLabel)?.text == secondActivity!!.getString(R.string.second_calculator) &&
                        root(R.id.amount)?.findViewById<LinearLayout>(R.id.expenseSourceChoices)?.childCount == 1 &&
                        root(R.id.amount)?.findViewById<LinearLayout>(R.id.expenseCategoryChoices)?.childCount == 1
                }
                instrumentation.runOnMainSync {
                    assertEquals(ManyManagerDao.SECOND_CALCULATOR_ID, secondActivity!!.calculatorId)
                    val form = root(R.id.amount)!!
                    assertEquals("SECOND cash", (form.findViewById<LinearLayout>(R.id.expenseSourceChoices).getChildAt(0) as TextView).text)
                    assertNull(form.findViewById<View>(R.id.expenseCategoryChoices).findViewWithTag<View>(mainCategory.id))
                    form.findViewById<View>(R.id.closeDialog).performClick()
                }
                await { root(R.id.amount) == null }
            } finally {
                instrumentation.removeMonitor(monitor)
                secondActivity?.let { instrumentation.runOnMainSync { it.finish() } }
            }
            await { activity.hasWindowFocus() }
            screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
            await { root(R.id.nav5) != null }
            screen.onActivity {
                val menu = root(R.id.nav5)!!
                val settings = menu.findViewById<View>(R.id.nav2)
                val second = menu.findViewById<TextView>(R.id.nav5)
                assertEquals(it.getString(R.string.second_calculator_management), second.text)
                assertTrue(second.top >= settings.bottom)
                val parent = second.parent as ViewGroup
                assertEquals(settings, parent.getChildAt(parent.indexOfChild(second) - 2))
                menu.findViewById<View>(R.id.closeDialog).performClick()
            }
            await { root(R.id.nav5) == null }
            val managementMonitor = instrumentation.addMonitor(MainActivity::class.java.name, null, false)
            var management: MainActivity? = null
            try {
                screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
                await { root(R.id.nav5) != null }
                screen.onActivity { root(R.id.nav5)!!.findViewById<View>(R.id.nav5).performClick() }
                management = managementMonitor.waitForActivityWithTimeout(15000) as? MainActivity
                assertNotNull(management)
                await { management!!.findViewById<View>(R.id.secondCalculatorPage).isShown }
                instrumentation.runOnMainSync {
                    assertEquals(ManyManagerDao.SECOND_CALCULATOR_ID, management!!.calculatorId)
                    management!!.findViewById<View>(R.id.secondBudget).performClick()
                }
                await {
                    management!!.findViewById<View>(R.id.budgetPage).isShown &&
                        management!!.findViewById<TextView>(R.id.budgetSourceName)?.text == "SECOND cash"
                }
                instrumentation.runOnMainSync {
                    management!!.onBackPressedDispatcher.onBackPressed()
                }
                await { management!!.findViewById<View>(R.id.secondCalculatorPage).isShown }
                instrumentation.runOnMainSync { management!!.findViewById<View>(R.id.secondCategories).performClick() }
                await { management!!.findViewById<View>(R.id.categoryRows).findViewWithTag<View>(secondCategory.id)?.isShown == true }
                instrumentation.runOnMainSync {
                    assertNull(management!!.findViewById<View>(R.id.categoryRows).findViewWithTag<View>(mainCategory.id))
                }
            } finally {
                instrumentation.removeMonitor(managementMonitor)
                management?.let { instrumentation.runOnMainSync { it.finish() } }
            }
        }
    }

    @Test fun secondDraftSurvivesRecreationSavesOnlyToSecondAndRenamesStayScoped(): Unit = runBlocking {
        val intent = Intent(app, MainActivity::class.java)
            .putExtra("calculatorId", ManyManagerDao.SECOND_CALCULATOR_ID).putExtra("startPage", 5)
            .putExtra("openExpense", true)
        val secondRepo = app.repository.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID)
        val mainBefore = saved(app.repository.snapshot())
        val formDate = saved(secondRepo.currentPeriod()).start
        ActivityScenario.launch<MainActivity>(intent).use { screen ->
            lateinit var model: PersistentDataViewModel
            screen.onActivity { model = ViewModelProvider(it)[PersistentDataViewModel::class.java] }
            await { root(R.id.amount) != null && model.categories.value is CategoryListState.Ready && model.sources.value is SourceListState.Ready }
            screen.onActivity {
                val form = root(R.id.amount)!!
                form.findViewById<EditText>(R.id.amount).setText("25")
                form.findViewById<EditText>(R.id.expenseNote).setText("SECOND draft")
                form.findViewById<View>(R.id.expenseSourceChoices).findViewWithTag<View>(secondMethod.id).performClick()
                form.findViewById<View>(R.id.expenseCategoryChoices).findViewWithTag<View>(secondCategory.id).performClick()
            }
            await { root(R.id.categoryPickerRows) != null }
            screen.onActivity { root(R.id.categoryPickerRows)!!.findViewById<View>(android.R.id.button1).performClick() }
            await { model.expenseCreationState.value?.draft?.categoryId == secondCategory.id }
            screen.onActivity { model.updateExpenseDraft(model.expenseCreationState.value!!.draft.copy(date = formDate)) }
            screen.recreate()
            await { root(R.id.amount)?.findViewById<EditText>(R.id.expenseNote)?.text.toString() == "SECOND draft" }
            screen.onActivity {
                assertEquals(ManyManagerDao.SECOND_CALCULATOR_ID, it.calculatorId)
                assertEquals(secondCategory.id, model.expenseCreationState.value!!.draft.categoryId)
                assertEquals(secondMethod.id, model.expenseCreationState.value!!.draft.sourceId)
                assertTrue(root(R.id.amount)!!.findViewById<View>(R.id.saveExpense).isEnabled)
                root(R.id.amount)!!.findViewById<View>(R.id.saveExpense).performClick()
            }
            await { model.expenseCreationState.value == null && root(R.id.amount) == null }
            assertEquals(mainBefore, saved(app.repository.snapshot()))
            assertEquals(2500L, saved(secondRepo.snapshot()).totalSpentMinor)
            screen.onActivity {
                assertTrue(it.findViewById<View>(R.id.secondCalculatorPage).isShown)
                assertEquals(it.getString(R.string.second_calculator), it.findViewById<TextView>(R.id.activeCalculatorLabel).text)
                it.findViewById<View>(R.id.secondCategories).performClick()
                it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + secondCategory.id).performClick()
            }
            await { root(R.id.categoryNameInput) != null }
            screen.onActivity {
                root(R.id.categoryNameInput)!!.findViewById<EditText>(R.id.categoryNameInput).setText("SECOND renamed")
                root(R.id.categoryNameInput)!!.findViewById<View>(android.R.id.button1).performClick()
            }
            await { root(R.id.categoryNameInput) == null && (model.categories.value as? CategoryListState.Ready)?.categories?.any { it.name == "SECOND renamed" } == true }
            assertEquals(mainBefore, saved(app.repository.snapshot()))
            assertEquals("SECOND renamed", saved(secondRepo.snapshot()).categories.single().name)
        }
    }

    @Test fun homeRefreshesBothSnapshotsAndPeriodRollover(): Unit = runBlocking {
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            lateinit var activity: MainActivity
            screen.onActivity { activity = it }
            fun money(value: Long) = activity.getString(R.string.ils_amount,
                BudgetAmounts.number(value, activity.resources.configuration.locales[0]))
            await {
                activity.findViewById<View>(R.id.homeSummaryCard).findViewById<TextView>(R.id.homeTotal).text == money(10000) &&
                    activity.findViewById<View>(R.id.homeSecondSummaryCard).findViewById<TextView>(R.id.homeBudgetRemaining).text == money(50000)
            }
            val repo = app.repository.forCalculator(ManyManagerDao.SECOND_CALCULATOR_ID)
            val period = saved(repo.currentPeriod())
            saved(repo.createExpense(NewExpense(2500, period.start, secondMethod.id, secondCategory.id)))
            await { activity.findViewById<View>(R.id.homeSecondSummaryCard).findViewById<TextView>(R.id.homeTotal).text == money(2500) }
            screen.onActivity {
                assertEquals(money(10000), it.findViewById<View>(R.id.homeSummaryCard).findViewById<TextView>(R.id.homeTotal).text)
            }
            app.clock.now = Instant.parse("2026-12-10T12:00:00Z")
            screen.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            screen.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            await {
                activity.findViewById<View>(R.id.homeSummaryCard).findViewById<TextView>(R.id.homeTotal).text == money(0) &&
                    activity.findViewById<View>(R.id.homeSecondSummaryCard).findViewById<TextView>(R.id.homeBudgetRemaining).text == money(0)
            }
            screen.onActivity {
                assertEquals(money(0), it.findViewById<View>(R.id.homeSummaryCard).findViewById<TextView>(R.id.homeBudgetRemaining).text)
            }
        }
    }
}

package com.example.simple.manymanager

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.inspector.WindowInspector
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.simple.manymanager.data.local.CategoryEntity
import com.example.simple.manymanager.domain.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Runs only against BudgetTestApplication's in-memory database and prefixed preferences. */
@RunWith(AndroidJUnit4::class)
class CategoryRenamingTest {
    private val app get() = ApplicationProvider.getApplicationContext<BudgetTestApplication>()
    private fun <T> saved(result: DataResult<T>): T = (result as DataResult.Success).value
    private fun error(result: DataResult<*>): DataError = (result as DataResult.Failure).error

    @Before fun prepare(): Unit = runBlocking {
        app.databaseInitialization.await().getOrThrow()
        app.database.clearAllTables()
        app.database.dao().initializeLocalCalculator()
    }

    private fun root(): View? = WindowInspector.getGlobalWindowViews()
        .lastOrNull { it.findViewById<View>(R.id.categoryNameInput)?.isShown == true }

    private fun await(screen: ActivityScenario<MainActivity>, condition: (MainActivity) -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            screen.onActivity { ready = condition(it) }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Category rename UI did not become ready")
    }

    @Test fun renamePreservesHierarchyExpensesTotalsAndOwnership(): Unit = runBlocking {
        val repo = app.repository
        val parent = saved(repo.createCategory("Food"))
        val child = saved(repo.createCategory("Shop", parent.id))
        val other = saved(repo.createCategory("Health"))
        val source = saved(repo.createPaymentMethod("Cash"))
        val period = saved(repo.currentPeriod())
        saved(repo.setPeriodBudget(period, 2000000))
        val direct = saved(repo.createExpense(NewExpense(10000, period.start, source.id, parent.id, "Direct")))
        val nested = saved(repo.createExpense(NewExpense(25000, period.start, source.id, child.id, "Child")))
        val before = saved(repo.snapshot())
        assertEquals(DataError.BlankName, error(repo.renameCategory(parent.id, "   ")))
        assertEquals(DataError.BlankName, error(repo.renameCategory(child.id, "")))
        val renamed = saved(repo.renameCategory(parent.id, "  Household  "))
        assertEquals(parent.copy(name = "Household"), renamed)
        assertEquals(child.copy(name = "Supermarket"), saved(repo.renameCategory(child.id, "  Supermarket  ")))
        assertEquals("Household / Supermarket", saved(repo.snapshot()).categories.categoryPath(child.id))
        assertEquals(other.copy(name = "Household"), saved(repo.renameCategory(other.id, "Household")))
        val after = saved(repo.snapshot())
        assertEquals(before.expenses, after.expenses)
        assertEquals(before.categoryBreakdown, after.categoryBreakdown)
        assertEquals(before.sourceBreakdown, after.sourceBreakdown)
        assertEquals(before.totalSpentMinor, after.totalSpentMinor)
        assertEquals(before.remainingMinor, after.remainingMinor)
        assertEquals(direct, saved(repo.expense(direct.id)))
        assertEquals(nested, saved(repo.expense(nested.id)))
        assertEquals(parent.id, after.categories.first { it.id == child.id }.parentId)
        assertEquals(DataError.MissingEntity(EntityKind.CATEGORY, "missing"), error(repo.renameCategory("missing", "Name")))
        app.database.openHelper.writableDatabase.execSQL("INSERT INTO calculators VALUES ('other',10,'ILS')")
        app.database.dao().insertCategory(CategoryEntity("other", "foreign", "Foreign"))
        assertEquals(DataError.MissingEntity(EntityKind.CATEGORY, "foreign"), error(repo.renameCategory("foreign", "Name")))
        assertEquals("Foreign", app.database.dao().categories("other").single().name)
        val observed = repo.observeCategories().first { result ->
            result is DataResult.Success && result.value.any { it.id == child.id && it.name == "Supermarket" }
        }
        assertTrue(observed is DataResult.Success)
    }

    @Test fun managementPencilsValidateCancelRecreateAndSaveSameIds(): Unit = runBlocking {
        val parent = saved(app.repository.createCategory("Food"))
        val child = saved(app.repository.createCategory("Shop", parent.id))
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            lateinit var model: PersistentDataViewModel
            await(screen) {
                model = ViewModelProvider(it)[PersistentDataViewModel::class.java]
                model.categories.value is CategoryListState.Ready
            }
            screen.onActivity { it.findViewById<View>(R.id.menuButton).performClick() }
            await(screen) { WindowInspector.getGlobalWindowViews().any { root -> root.findViewById<View>(R.id.nav4)?.isShown == true } }
            screen.onActivity {
                WindowInspector.getGlobalWindowViews().last { root -> root.findViewById<View>(R.id.nav4)?.isShown == true }
                    .findViewById<View>(R.id.nav4).performClick()
            }
            await(screen) { it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + parent.id)?.isShown == true }
            screen.onActivity {
                val pencil = it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + parent.id)
                assertEquals(it.getString(R.string.rename_category_label, parent.name), pencil.contentDescription)
                assertTrue(pencil.width >= (48 * it.resources.displayMetrics.density).toInt())
                pencil.performClick()
            }
            await(screen) { root() != null }
            screen.onActivity {
                assertEquals(it.getString(R.string.expense_categories), it.findViewById<TextView>(R.id.categoryPageTitle).text)
                assertEquals(parent.id, model.categoryCreation.value!!.categoryId)
                assertEquals("Food", root()!!.findViewById<EditText>(R.id.categoryNameInput).text.toString())
                root()!!.findViewById<EditText>(R.id.categoryNameInput).setText("   ")
                root()!!.findViewById<View>(android.R.id.button1).performClick()
            }
            await(screen) { model.categoryCreation.value?.result is DataResult.Failure }
            screen.onActivity {
                assertEquals(it.getString(R.string.category_name_required), root()!!.findViewById<EditText>(R.id.categoryNameInput).error)
                root()!!.findViewById<EditText>(R.id.categoryNameInput).setText("Keep draft")
            }
            screen.recreate()
            await(screen) { root()?.findViewById<EditText>(R.id.categoryNameInput)?.text.toString() == "Keep draft" }
            screen.onActivity { root()!!.findViewById<View>(android.R.id.button2).performClick() }
            await(screen) { root() == null && model.categoryCreation.value == null }
            assertEquals("Food", saved(app.repository.observeCategories().first()).first { it.id == parent.id }.name)
            screen.onActivity { it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + parent.id).performClick() }
            await(screen) { root() != null }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            await(screen) { root() == null && model.categoryCreation.value == null }
            screen.onActivity { it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + parent.id).performClick() }
            await(screen) { root() != null }
            screen.onActivity {
                root()!!.findViewById<EditText>(R.id.categoryNameInput).setText("  Household  ")
                root()!!.findViewById<View>(android.R.id.button1).performClick()
            }
            await(screen) {
                root() == null && it.findViewById<View>(R.id.categoryRows).findViewWithTag<TextView>(parent.id).text.toString() == "Household"
            }
            screen.onActivity {
                it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>(parent.id).performClick()
                it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + child.id).performClick()
            }
            await(screen) { root() != null }
            screen.onActivity {
                assertEquals("Household", it.findViewById<TextView>(R.id.categoryPageTitle).text.toString())
                assertEquals(child.id, model.categoryCreation.value!!.categoryId)
                root()!!.findViewById<EditText>(R.id.categoryNameInput).setText("Discard")
            }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE)
            await(screen) { root() == null && model.categoryCreation.value == null }
            screen.onActivity { it.findViewById<View>(R.id.categoryRows).findViewWithTag<View>("rename:" + child.id).performClick() }
            await(screen) { root() != null }
            screen.onActivity {
                root()!!.findViewById<EditText>(R.id.categoryNameInput).setText("  Supermarket  ")
                root()!!.findViewById<View>(android.R.id.button1).performClick()
            }
            await(screen) { root() == null && (model.categories.value as? CategoryListState.Ready)?.categories?.any { it.id == child.id && it.name == "Supermarket" } == true }
            assertEquals(parent.id, saved(app.repository.observeCategories().first()).first { it.id == child.id }.parentId)
        }
    }

    @Test fun longNamesAndPencilsFitNarrowLocaleThemeLayouts(): Unit = runBlocking {
        val longName = "מזון לבית · Продукты для семьи · Household food ".repeat(4).trim()
        val parent = Category("layout", "parent", longName)
        val child = Category("layout", "child", longName + " child", parent.id)
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            for (language in listOf("he", "en", "ru")) for (night in listOf(false, true)) {
                lateinit var window: android.app.Dialog
                lateinit var page: View
                lateinit var ui: CategoryManagementUi
                lateinit var rename: CategoryCreationDialog
                var renameShown = false
                var selected: Category? = null
                screen.onActivity { activity ->
                    val configuration = android.content.res.Configuration(activity.resources.configuration).apply {
                        val locale = java.util.Locale.forLanguageTag(language)
                        setLocale(locale); setLayoutDirection(locale)
                        uiMode = (uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) android.content.res.Configuration.UI_MODE_NIGHT_YES else android.content.res.Configuration.UI_MODE_NIGHT_NO
                    }
                    val context = androidx.appcompat.view.ContextThemeWrapper(activity, R.style.AppTheme).apply {
                        applyOverrideConfiguration(configuration)
                    }
                    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                    page = android.view.LayoutInflater.from(context).inflate(R.layout.page_categories, null)
                    ui = CategoryManagementUi(page, {}, {}, {}, { selected = it })
                    ui.render(CategoryListState.Ready(listOf(parent, child)))
                    window = android.app.Dialog(context).apply {
                        setContentView(android.widget.ScrollView(context).apply { addView(page) })
                        show(); this.window!!.setLayout(dp(320), dp(720))
                    }
                }
                try {
                    await(screen) { !page.isLayoutRequested && page.width > 0 }
                    screen.onActivity {
                        val pencil = page.findViewWithTag<View>("rename:" + parent.id)
                        assertEquals(page.context.getString(R.string.rename_category_label, longName), pencil.contentDescription)
                        val label = page.findViewWithTag<TextView>(parent.id)
                        assertTrue(label.lineCount > 1)
                        assertTrue(label.right <= (label.parent as View).width)
                        assertTrue(pencil.width >= (48 * page.resources.displayMetrics.density).toInt())
                        pencil.performClick(); assertEquals(parent.id, selected!!.id)
                        assertEquals(page.context.getString(R.string.expense_categories), page.findViewById<TextView>(R.id.categoryPageTitle).text)
                        ui.parentId = parent.id; ui.render(CategoryListState.Ready(listOf(parent, child)))
                    }
                    await(screen) { !page.isLayoutRequested }
                    screen.onActivity { activity ->
                        val pencil = page.findViewWithTag<View>("rename:" + child.id)
                        assertEquals(page.context.getString(R.string.rename_subcategory_label, child.name), pencil.contentDescription)
                        pencil.performClick(); assertEquals(child.id, selected!!.id)
                        val label = page.findViewWithTag<TextView>(child.id)
                        assertTrue(label.lineCount > 1)
                        assertEquals(if (language == "he") View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR, page.layoutDirection)
                        rename = CategoryCreationDialog(page.context, {}, {}, {})
                        renameShown = true
                        rename.dialog.delegate.localNightMode = if (night) androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES
                            else androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO
                        rename.show()
                        rename.render(CategoryCreationState(false, parentId = parent.id, name = child.name, categoryId = child.id))
                        rename.dialog.window!!.setLayout((320 * page.resources.displayMetrics.density).toInt(), android.view.ViewGroup.LayoutParams.WRAP_CONTENT)
                        assertEquals(page.context.getString(R.string.save_expense_changes), rename.dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).text)
                        val input = rename.dialog.findViewById<EditText>(R.id.categoryNameInput)!!
                        assertEquals(child.name, input.text.toString())
                    }
                    await(screen) { rename.dialog.window!!.decorView.width > 0 && !rename.dialog.window!!.decorView.isLayoutRequested }
                    screen.onActivity { activity ->
                        val decor = rename.dialog.window!!.decorView
                        val bitmap = android.graphics.Bitmap.createBitmap(decor.width, decor.height, android.graphics.Bitmap.Config.ARGB_8888)
                        decor.draw(android.graphics.Canvas(bitmap))
                        java.io.File(activity.getExternalFilesDir(null), "rename-$language-$night.png").outputStream().use {
                            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                    }
                } finally { screen.onActivity { if (renameShown) rename.dispose(); window.dismiss() } }
            }
        }
    }

    @Test fun restoredRenameKeepsIdentityAndLocksUncertainSubmission() {
        val draft = CategoryCreationState(false, parentId = "parent", name = "Draft", categoryId = "child", submitting = true)
        val restored = CategoryCreationState.fromBundle(draft.toBundle())
        assertEquals("child", restored.categoryId)
        assertEquals("parent", restored.parentId)
        assertEquals("Draft", restored.name)
        assertTrue(restored.interrupted)
    }
}

package com.example.simple.manymanager

import android.app.Dialog
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.view.ContextThemeWrapper
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.simple.manymanager.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class HomeLayoutTest {
    private val app get() = ApplicationProvider.getApplicationContext<BudgetTestApplication>()
    private fun <T> success(value: DataResult<T>) = (value as DataResult.Success).value
    private fun await(screen: ActivityScenario<MainActivity>, condition: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (SystemClock.uptimeMillis() < deadline) {
            var ready = false
            screen.onActivity { ready = condition() }
            if (ready) return
            SystemClock.sleep(25)
        }
        fail("Home layout did not become ready")
    }

    @Test fun compactHomePreservesOrderThemesWrappingAndScrollableExpansion(): Unit = runBlocking {
        app.databaseInitialization.await().getOrThrow()
        val period = success(app.repository.currentPeriod())
        val snapshot = BudgetSnapshot(Calculator("layout", 10, "ILS"), period, emptyList(),
            listOf(SourceBudget(BudgetSource("layout", "cash", "מזומן · Cash · Наличные"), 10000, 1),
                SourceBudget(BudgetSource("layout", "card", "אשראי · Credit card · Карта"), 25000, 1)),
            emptyList(), 2000000, 35000, 1965000, 2, emptyList())
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            for (language in listOf("he", "en", "ru")) for (night in listOf(false, true)) for (heightDp in listOf(568, 720)) {
                lateinit var home: View
                lateinit var scroll: ScrollView
                lateinit var dialog: Dialog
                lateinit var ui: BudgetHomeUi
                var expanded = false
                screen.onActivity { activity ->
                    val configuration = Configuration(activity.resources.configuration).apply {
                        val locale = Locale.forLanguageTag(language)
                        setLocale(locale); setLayoutDirection(locale)
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val context = ContextThemeWrapper(activity, R.style.AppTheme).apply { applyOverrideConfiguration(configuration) }
                    fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()
                    home = LayoutInflater.from(context).inflate(R.layout.page_home, null)
                    val page = FrameLayout(context).apply {
                        setPadding(dp(25), dp(24), dp(25), dp(32))
                        addView(home, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    }
                    scroll = ScrollView(context).apply {
                        isFillViewport = true
                        isSmoothScrollingEnabled = false
                        setBackgroundColor(context.getColor(R.color.background))
                        addView(page)
                    }
                    ui = BudgetHomeUi(home, { expanded = !expanded; ui.setExpanded(expanded, animate = false) }, {})
                    ui.render(snapshot, false)
                    fun money(value: Long) = context.getString(R.string.ils_amount,
                        BudgetAmounts.number(value, context.resources.configuration.locales[0]))
                    home.findViewById<TextView>(R.id.homeTotal).text = money(snapshot.totalSpentMinor)
                    home.findViewById<TextView>(R.id.homeMonth).text = BudgetPeriodFormatter.format(context, period)
                    dialog = Dialog(context).apply {
                        setContentView(scroll); show()
                        window!!.setBackgroundDrawableResource(android.R.color.transparent)
                        window!!.setLayout(dp(320), dp(heightDp))
                    }
                }
                try {
                    await(screen) { home.height >= home.minimumHeight && home.minimumHeight > 0 && !home.isLayoutRequested &&
                        home.findViewById<View>(R.id.homeSummaryCard).width > 0 }
                    screen.onActivity { activity ->
                        val title = home.findViewById<View>(R.id.homeTitle)
                        val add = home.findViewById<View>(R.id.addExpense)
                        val buttonPixels = Bitmap.createBitmap(add.width, add.height, Bitmap.Config.ARGB_8888)
                        add.draw(Canvas(buttonPixels))
                        assertEquals("Purple add expense in both themes", 0xff7c3aed.toInt(),
                            buttonPixels.getPixel((8 * add.resources.displayMetrics.density).toInt(), add.height / 2))
                        buttonPixels.recycle()
                        val space = home.findViewById<View>(R.id.homeOrnamentSpace)
                        val card = home.findViewById<View>(R.id.homeSummaryCard)
                        val ornament = home.findViewById<ImageView>(R.id.homeOrnament)
                        assertTrue(title.bottom <= add.top && add.bottom <= space.top && space.bottom <= card.top)
                        assertTrue(card.bottom <= home.height)
                        assertFalse(ornament.isFocusable)
                        assertNull(ornament.contentDescription)
                        assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO, ornament.importantForAccessibility)
                        assertEquals(if (night) 0xffa6abb3.toInt() else 0xff747980.toInt(), ornament.imageTintList!!.defaultColor)
                        val spent = home.findViewById<TextView>(R.id.homeTotal)
                        val remaining = home.findViewById<TextView>(R.id.homeBudgetRemaining)
                        assertEquals(spent.textSize, remaining.textSize, .01f)
                        assertEquals(spent.typeface.style, remaining.typeface.style)
                        val bitmap = Bitmap.createBitmap(scroll.width, scroll.height, Bitmap.Config.ARGB_8888)
                        scroll.draw(Canvas(bitmap))
                        File(activity.getExternalFilesDir(null), "home-$language-$night-$heightDp.png").outputStream().use {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)
                        }
                        bitmap.recycle()
                        val huge = snapshot.copy(totalAllocatedMinor = 9000000000000000000L,
                            totalSpentMinor = 123456789012300L, remainingMinor = 8999876543210987700L)
                        ui.render(huge, false)
                        spent.text = home.context.getString(R.string.ils_amount,
                            BudgetAmounts.number(huge.totalSpentMinor, home.resources.configuration.locales[0]))
                        home.findViewById<TextView>(R.id.homeMonth).text =
                            BudgetPeriodFormatter.description(home.context, period).repeat(3)
                        home.findViewById<View>(R.id.homeBudgetArea).performClick()
                    }
                    await(screen) { home.findViewById<View>(R.id.budgetBreakdown).isShown &&
                        !home.isLayoutRequested && !scroll.isLayoutRequested &&
                        home.findViewById<TextView>(R.id.homeBudgetRemaining).layout != null }
                    screen.onActivity {
                        assertTrue(expanded)
                        assertEquals(180f, home.findViewById<View>(R.id.budgetChevron).rotation)
                        assertEquals(2, home.findViewById<android.widget.LinearLayout>(R.id.budgetBreakdownRows).childCount)
                        fun verify(view: View) {
                            if (view.visibility != View.VISIBLE) return
                            if (view is TextView) {
                                assertTrue("text outside parent", view.left >= 0 && view.right <= (view.parent as View).width)
                                assertTrue("text clipped vertically", view.layout.getLineBottom(view.layout.lineCount - 1) <=
                                    view.height - view.compoundPaddingTop - view.compoundPaddingBottom)
                                for (line in 0 until view.layout.lineCount) assertEquals(0, view.layout.getEllipsisCount(line))
                            }
                            if (view is ViewGroup) for (index in 0 until view.childCount) verify(view.getChildAt(index))
                        }
                        verify(home)
                        scroll.fullScroll(View.FOCUS_DOWN)
                    }
                    screen.onActivity {
                        val rows = home.findViewById<View>(R.id.budgetBreakdownRows)
                        val rowPosition = IntArray(2); val scrollPosition = IntArray(2)
                        rows.getLocationOnScreen(rowPosition); scroll.getLocationOnScreen(scrollPosition)
                        assertTrue("last breakdown row reachable", rowPosition[1] + rows.height <= scrollPosition[1] + scroll.height + 1)
                        home.findViewById<View>(R.id.homeBudgetArea).performClick()
                        assertEquals(View.GONE, home.findViewById<View>(R.id.budgetBreakdown).visibility)
                    }
                } finally {
                    screen.onActivity { ui.dispose(); dialog.dismiss() }
                }
            }
        }
    }

    @Test fun upperAddExpenseActionStillOpensTheExistingForm(): Unit = runBlocking {
        app.databaseInitialization.await().getOrThrow()
        ActivityScenario.launch(MainActivity::class.java).use { screen ->
            lateinit var model: PersistentDataViewModel
            screen.onActivity { model = ViewModelProvider(it)[PersistentDataViewModel::class.java] }
            await(screen) { model.activeBudget.value is PersistentDataState.Ready }
            screen.onActivity { activity ->
                val button = activity.findViewById<View>(R.id.addExpense)
                assertTrue(button.isShown && button.isEnabled)
                button.performClick()
            }
            await(screen) { model.expenseCreationState.value != null }
            screen.onActivity { assertNotNull(model.expenseCreationState.value!!.draft.date) }
        }
    }
}

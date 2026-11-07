package com.example.simple.manymanager

import android.app.Dialog
import android.content.res.Configuration
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import android.graphics.Color
import android.graphics.Rect
import android.os.Bundle
import android.os.Parcelable
import android.util.SparseArray
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.core.widget.doAfterTextChanged
import androidx.core.view.doOnAttach
import androidx.core.view.doOnPreDraw
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.simple.manymanager.domain.BudgetPeriod
import com.example.simple.manymanager.domain.BudgetSource
import com.example.simple.manymanager.domain.DataResult
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.min
import kotlinx.coroutines.launch

/** Coordinates the XML screens, navigation, and application appearance. */
class MainActivity : AppCompatActivity() {
    private data class PageVisit(val page: Int, val tab: Int, val scrollY: Int)
    private val pageHistory = mutableListOf<PageVisit>()
    private val pageBackCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() { navigateBack() }
    }
    private val budgetState by lazy { ViewModelProvider(this)[BudgetViewModel::class.java] }
    private val persistentViewModel by lazy {
        ViewModelProvider(this, PersistentDataViewModelFactory(
            (application as ManyManagerApplication).repository
        ))[PersistentDataViewModel::class.java]
    }
    private val expandedSummaryGroups = mutableSetOf<String>()
    private var summaryDialogScroll: Int? = null
    private var allExpensesUi: AllExpensesUi? = null
    private var summaryBreakdownUi: SummaryBreakdownUi? = null
    private var categoryDialog: CategoryCreationDialog? = null
    private var periodBudgetDialog: androidx.appcompat.app.AlertDialog? = null
    private var periodBudgetInput: android.widget.EditText? = null
    private var sourceCreationDialog: SourceCreationDialog? = null
    private var sourceEditingDialog: SourceEditingDialog? = null
    private var homeBudgetUi: BudgetHomeUi? = null
    private var budgetManagementUi: BudgetManagementUi? = null
    private var categoryManagementUi: CategoryManagementUi? = null
    private var page = 0
    private var tab = 0
    private var currentDate = LocalDate.now()
    private val preferences by lazy { AppearancePreferences(this) }
    private var dark = false
    private val appearanceTransition by lazy { AppearanceTransitionState(window) }
    private var contentReady = false
    private var renderedConfiguration: Configuration? = null
    private var activeDialog: Dialog? = null
    private var expenseDialog: ExpenseSheetDialog? = null
    private var expenseDetailsDialog: ExpenseDetailsDialog? = null
    private val expanded = mutableSetOf<Int>()

    override fun onCreate(savedInstanceState: Bundle?) {
        dark = preferences.dark
        delegate.localNightMode = if (dark) AppCompatDelegate.MODE_NIGHT_YES
            else AppCompatDelegate.MODE_NIGHT_NO
        super.onCreate(savedInstanceState)
        if (AppCompatDelegate.getApplicationLocales().isEmpty) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("he"))
        }
        onBackPressedDispatcher.addCallback(this, pageBackCallback)
        savedInstanceState?.getLongArray("selectedBudgetPeriod")?.let {
            persistentViewModel.selectPeriod(BudgetPeriod(LocalDate.ofEpochDay(it[0]), LocalDate.ofEpochDay(it[1])))
        }
        renderContent(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { persistentViewModel.state.collect(::renderPersistentDataState) }
                launch { persistentViewModel.activeBudget.collect(::renderActiveBudget) }
                launch { persistentViewModel.sources.collect(::renderSources) }
                launch { persistentViewModel.categories.collect(::renderCategoryState) }
                launch { persistentViewModel.categoryCreation.collect(::renderCategoryCreation) }
                launch { persistentViewModel.sourceCreationState.collect(::renderSourceCreation) }
                launch { persistentViewModel.sourceEditingState.collect(::renderSourceEditing) }
                launch { persistentViewModel.periodBudgetDraft.collect(::renderPeriodBudget) }
                launch { persistentViewModel.expenseCreationState.collect(::renderExpenseCreation) }
            }
        }
    }

    private val dateChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { refreshDate() }
    }

    override fun onStart() {
        super.onStart()
        ContextCompat.registerReceiver(this, dateChanges, IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStop() {
        unregisterReceiver(dateChanges)
        super.onStop()
    }

    private fun refreshDate() {
        currentDate = LocalDate.now()
        if (contentReady) persistentViewModel.refresh()
    }

    override fun onResume() {
        super.onResume()
        refreshDate()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        val previous = renderedConfiguration
        val appearanceChanged = previous == null ||
            previous.locales != newConfig.locales ||
            previous.layoutDirection != newConfig.layoutDirection ||
            (previous.uiMode and Configuration.UI_MODE_NIGHT_MASK) !=
            (newConfig.uiMode and Configuration.UI_MODE_NIGHT_MASK)
        if (!contentReady || !appearanceChanged) {
            super.onConfigurationChanged(newConfig)
            return
        }
        val state = Bundle().also { saveUiState(it) }
        if (!appearanceTransition.isChanging) {
            val languageChanged = previous != null && previous.locales != newConfig.locales
            prepareAppearanceTransition(if (languageChanged) 250L else 500L)
        }
        contentReady = false
        super.onConfigurationChanged(newConfig)
        renderContent(state)
    }

    private fun renderContent(savedInstanceState: Bundle?) {
        homeBudgetUi?.dispose()
        homeBudgetUi = null
        budgetManagementUi = null
        categoryManagementUi = null
        dismissActiveDialogImmediately()
        currentDate = savedInstanceState?.takeIf { it.containsKey("currentDate") }
            ?.getLong("currentDate")?.let(LocalDate::ofEpochDay) ?: currentDate
        enableEdgeToEdge(
            statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        setContentView(R.layout.activity_main)

        val container = findViewById<View>(R.id.appContainer)
        container.layoutParams = (container.layoutParams as FrameLayout.LayoutParams).apply {
            width = min(window.windowManager.currentWindowMetrics.bounds.width(), dp(440))
        }
        findViewById<View>(R.id.windowRoot).addOnLayoutChangeListener { root, _, _, _, _, _, _, _, _ ->
            val available = (root.width - root.paddingLeft - root.paddingRight).coerceAtLeast(0)
            val target = min(available, dp(440))
            if (target > 0 && container.layoutParams.width != target) {
                container.layoutParams = container.layoutParams.apply { width = target }
            }
        }
        findViewById<View>(R.id.windowRoot).setOnApplyWindowInsetsListener { view, insets ->
            val safe = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(safe.left, safe.top, safe.right, maxOf(safe.bottom, ime.bottom))
            insets
        }

        findViewById<View>(R.id.windowRoot).doOnAttach { it.requestApplyInsets() }

        savedInstanceState?.getIntArray("pageHistory")?.let { visits ->
            pageHistory.clear()
            visits.toList().chunked(3).filter { it.size == 3 }.forEach {
                pageHistory.add(PageVisit(it[0], it[1], it[2]))
            }
        }
        page = savedInstanceState?.getInt("page") ?: 0
        tab = savedInstanceState?.getInt("tab") ?: 0
        savedInstanceState?.getIntArray("expanded")?.forEach { expanded.add(it) }
        findViewById<View>(R.id.menuButton).setOnClickListener { showMenu() }
        findViewById<View>(R.id.addExpense).setOnClickListener { showExpense() }
        listOf(R.id.categoryTab, R.id.sourceTab, R.id.allTab).forEachIndexed { index, id ->
            findViewById<View>(id).setOnClickListener { selectTab(index) }
        }
        val toggles = listOf(
            R.id.languageToggle to R.id.languageDetails,
            R.id.themeToggle to R.id.themeDetails
        )
        val arrows = listOf(R.id.languageChevron, R.id.themeChevron)
        toggles.forEachIndexed { index, (toggle, details) ->
            fun update() {
                val open = details in expanded
                findViewById<View>(details).visibility = if (open) View.VISIBLE else View.GONE
                findViewById<View>(arrows[index]).rotation = if (open) {
                    if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) -90f else 90f
                } else 0f
                findViewById<View>(toggle).isSelected = open
            }
            update()
            findViewById<View>(toggle).setOnClickListener {
                if (!expanded.add(details)) expanded.remove(details)
                update()
            }
        }
        findViewById<View>(R.id.lightTheme).setOnClickListener { changeTheme(false) }
        findViewById<View>(R.id.darkTheme).setOnClickListener { changeTheme(true) }
        findViewById<TextView>(R.id.themeLabel).setText(
            if (dark) R.string.theme_dark else R.string.theme_light
        )
        listOf(R.id.lightTheme, R.id.darkTheme).forEachIndexed { index, id ->
            val selected = (index == 1) == dark
            findViewById<TextView>(id).apply {
                setBackgroundResource(if (selected) R.drawable.chip_active else R.drawable.chip)
                setTextColor(getColor(if (selected) R.color.blue else R.color.text))
                isSelected = selected
            }
        }

        bindLanguages()
        val locale = resources.configuration.locales[0]
        val month = currentDate.format(DateTimeFormatter.ofPattern("LLLL yyyy", locale))
        findViewById<TextView>(R.id.monthLabel).text = month
        findViewById<TextView>(R.id.homeMonth).text = month
        bindEmptyState()

        homeBudgetUi = BudgetHomeUi(findViewById(R.id.homePage), {
            homeBudgetUi?.setExpanded(!budgetState.breakdownExpanded)
        }) { expanded ->
            budgetState.breakdownExpanded = expanded
            pageBackCallback.isEnabled = page != 0 || budgetState.breakdownExpanded
        }
        budgetManagementUi = BudgetManagementUi(
            findViewById(R.id.budgetPage),
            persistentViewModel::selectPreviousPeriod,
            persistentViewModel::selectNextPeriod,
            ::showAddSource,
            ::showEditSource,
            { period, amount ->
                dismissActiveDialogImmediately()
                persistentViewModel.beginPeriodBudget(period, amount)
                renderPeriodBudget(persistentViewModel.periodBudgetDraft.value)
            }
        )
        categoryManagementUi = CategoryManagementUi(
            findViewById(R.id.categoriesPage), ::navigateBack, ::showAddCategory,
            persistentViewModel::retryCategories, ::showRenameCategory
        )
        categoryManagementUi?.parentId = savedInstanceState?.getString("categoryManagementParent")
        listOf(R.id.previousMonth, R.id.nextMonth).forEach { id ->
            findViewById<View>(id).apply {
                isEnabled = false
                alpha = 0.45f
            }
        }
        allExpensesUi = AllExpensesUi(findViewById(R.id.allPanel), ::showExpenseEditor, persistentViewModel::refresh)
        savedInstanceState?.getStringArrayList("expandedSummaryGroups")?.let {
            expandedSummaryGroups.clear(); expandedSummaryGroups.addAll(it)
        }
        summaryDialogScroll = savedInstanceState?.takeIf { it.containsKey("summaryDialogScroll") }?.getInt("summaryDialogScroll")
        summaryBreakdownUi = SummaryBreakdownUi(findViewById(R.id.summaryPage), persistentViewModel::refresh,
            ::showExpenseEditor, expandedSummaryGroups)
        selectPage(page)
        selectTab(tab)
        savedInstanceState?.getBundle("expenseCreation")?.let {
            persistentViewModel.restoreExpenseCreation(ExpenseCreationState.fromBundle(it))
        }
        if (savedInstanceState?.containsKey("pageScrollY") != true &&
            page == 2 && R.id.languageDetails in expanded
        ) {
            findViewById<View>(R.id.languageDetails).post {
                val section = findViewById<View>(R.id.languageDetails)
                section.requestRectangleOnScreen(Rect(0, 0, section.width, section.height), false)
            }
        }
        savedInstanceState?.getBundle("categoryCreation")?.let {
            persistentViewModel.restoreCategoryCreation(CategoryCreationState.fromBundle(it))
        }
        savedInstanceState?.getBundle("sourceCreation")?.let {
            SourceCreationState.fromBundle(it)?.let(persistentViewModel::restoreSourceCreation)
        }
        savedInstanceState?.getBundle("sourceEditing")?.let {
            SourceEditingState.fromBundle(it)?.let(persistentViewModel::restoreSourceEditing)
        }
        savedInstanceState?.getBundle("periodBudget")?.let {
            persistentViewModel.restorePeriodBudget(PersistentDataViewModel.PeriodBudgetDraft.fromBundle(it))
        }
        renderedConfiguration = Configuration(resources.configuration)
        contentReady = true
        renderPersistentDataState(persistentViewModel.state.value)
        renderActiveBudget(persistentViewModel.activeBudget.value)
        renderSources(persistentViewModel.sources.value)
        renderCategoryState(persistentViewModel.categories.value)
        renderExpenseCreation(persistentViewModel.expenseCreationState.value)
        renderCategoryCreation(persistentViewModel.categoryCreation.value)
        renderSourceCreation(persistentViewModel.sourceCreationState.value)
        renderSourceEditing(persistentViewModel.sourceEditingState.value)
        renderPeriodBudget(persistentViewModel.periodBudgetDraft.value)
        finishAppearanceTransition(savedInstanceState)
    }

    private fun prepareAppearanceTransition(durationMillis: Long = 500L) {
        appearanceTransition.prepare(findViewById(R.id.windowRoot), durationMillis)
    }

    private fun finishAppearanceTransition(savedInstanceState: Bundle?) {
        appearanceTransition.finish(findViewById(android.R.id.content)) {
            savedInstanceState?.getSparseParcelableArray<Parcelable>("viewHierarchy")?.let {
                findViewById<View>(R.id.windowRoot).restoreHierarchyState(it)
            }
            if (savedInstanceState?.containsKey("pageScrollY") == true) {
                findViewById<ScrollView>(R.id.pageScroll).scrollTo(
                    0, savedInstanceState.getInt("pageScrollY")
                )
            }
        }
    }

    private fun bindEmptyState() {
        renderActiveBudget(persistentViewModel.activeBudget.value)
    }

    private fun navigateBack() {
        if (page == 4 && categoryManagementUi?.back() == true) return
        if (page == 0 && budgetState.breakdownExpanded) {
            homeBudgetUi?.setExpanded(false)
            return
        }
        val previous = pageHistory.removeLastOrNull() ?: PageVisit(0, 0, 0)
        selectPage(previous.page, animate = true, scrollY = previous.scrollY)
        selectTab(previous.tab)
    }

    private fun navigateToPage(index: Int) {
        if (index == page) return
        if (index == 3) persistentViewModel.selectPeriod(null)
        if (index == 0) {
            pageHistory.clear()
        } else {
            pageHistory.add(PageVisit(page, tab, findViewById<ScrollView>(R.id.pageScroll).scrollY))
        }
        selectPage(index, animate = true)
    }

    private fun selectPage(index: Int, animate: Boolean = false, scrollY: Int = 0) {
        val scroll = findViewById<ScrollView>(R.id.pageScroll)
        appearanceTransition.changePage(
            scroll = scroll,
            animate = animate && page != index,
            durationMillis = if (page == 1) 200L else 180L,
            background = getColor(R.color.background),
            updatePage = {
                if (page == 0 && index != 0) homeBudgetUi?.setExpanded(false, animate = false)
                page = index
                pageBackCallback.isEnabled = index != 0 || budgetState.breakdownExpanded
                categoryManagementUi?.render(persistentViewModel.categories.value)
                listOf(R.id.homePage, R.id.summaryPage, R.id.settingsPage,
                    R.id.budgetPage, R.id.categoriesPage).forEachIndexed { i, id ->
                    findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
                }
            },
            restoreScroll = { scroll.scrollTo(0, scrollY) }
        )
    }

    private fun selectTab(index: Int) {
        tab = index
        listOf(R.id.categoryPanel, R.id.sourcePanel, R.id.allPanel).forEachIndexed { i, id ->
            findViewById<View>(id).visibility = if (i == index) View.VISIBLE else View.GONE
        }
        listOf(R.id.categoryTab, R.id.sourceTab, R.id.allTab).forEachIndexed { i, id ->
            findViewById<TextView>(id).apply {
                isSelected = i == index
                setTextColor(getColor(if (isSelected) R.color.blue else R.color.muted))
                setBackgroundResource(if (isSelected) R.drawable.tab_selected else R.drawable.tab_idle)
                setTypeface(null, if (isSelected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
            }
        }
    }

    private fun activeLanguage(): String =
        AppCompatDelegate.getApplicationLocales()[0]?.language?.let {
            if (it == "iw") "he" else it
        } ?: "he"

    private fun bindLanguages() {
        val choices = listOf(
            Triple(R.id.languageHe, "he", R.string.language_he),
            Triple(R.id.languageEn, "en", R.string.language_en),
            Triple(R.id.languageRu, "ru", R.string.language_ru)
        )
        val active = activeLanguage()
        choices.forEach { (id, tag, label) ->
            val name = getString(label)
            val selected = active == tag
            findViewById<TextView>(id).apply {
                text = if (selected) getString(R.string.language_selected, name) else name
                contentDescription = if (selected) {
                    getString(R.string.language_selected_description, name)
                } else name
                isSelected = selected
                setBackgroundResource(if (selected) R.drawable.chip_active else R.drawable.chip)
                setTextColor(getColor(if (selected) R.color.blue else R.color.text))
                setOnClickListener { selectLanguage(tag) }
            }
            if (selected) findViewById<TextView>(R.id.languageLabel).text = name
        }
    }

    private fun selectLanguage(tag: String) {
        if (activeLanguage() == tag || appearanceTransition.isChanging) return
        prepareAppearanceTransition(durationMillis = 250L)
        page = 2
        expanded.add(R.id.languageDetails)
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(tag))
    }

    private fun changeTheme(value: Boolean) {
        if (dark != value && !appearanceTransition.isChanging) {
            prepareAppearanceTransition()
            preferences.dark = value
            dark = value
            delegate.localNightMode = if (value) AppCompatDelegate.MODE_NIGHT_YES
                else AppCompatDelegate.MODE_NIGHT_NO
        }
    }

    private fun renderPersistentDataState(state: PersistentDataState) {
        if (!contentReady) return
        budgetManagementUi?.renderState(state, persistentViewModel::refresh)
    }

    private fun renderActiveBudget(state: PersistentDataState) {
        if (!contentReady) return
        homeBudgetUi?.renderState(state, budgetState.breakdownExpanded, persistentViewModel::refresh)
        val scroll = findViewById<ScrollView>(R.id.pageScroll)
        val top = if (page == 1) summaryDialogScroll ?: scroll.scrollY else scroll.scrollY
        allExpensesUi?.render(state)
        summaryBreakdownUi?.render(state)
        if (page == 1) scroll.doOnPreDraw { scroll.scrollTo(0, top) }
        val snapshot = (state as? PersistentDataState.Ready)?.snapshot
        val total = snapshot?.let { BudgetAmounts.number(it.totalSpentMinor, resources.configuration.locales[0]) } ?: "—"
        findViewById<TextView>(R.id.homeTotal).text = snapshot?.let { getString(R.string.ils_amount, total) } ?: "—"
        findViewById<TextView>(R.id.summaryTotal).text = total
        findViewById<TextView>(R.id.summaryCount).text = snapshot?.let {
            getString(R.string.monthly_expense_count,
                java.text.NumberFormat.getIntegerInstance(resources.configuration.locales[0]).format(it.expenseCount))
        } ?: "—"
        snapshot?.let {
            val label = BudgetPeriodFormatter.format(this, it.period)
            listOf(R.id.homeMonth, R.id.monthLabel).forEach { id ->
                findViewById<TextView>(id).apply {
                    text = label
                    contentDescription = BudgetPeriodFormatter.description(this@MainActivity, it.period)
                }
            }
        }
    }

    private fun renderSources(state: SourceListState) {
        if (!contentReady) return
        expenseDialog?.renderSources(state)
        renderExpenseCreation(persistentViewModel.expenseCreationState.value)

    }

    private fun renderPeriodBudget(state: PersistentDataViewModel.PeriodBudgetDraft?) {
        if (!contentReady || isFinishing || isDestroyed) return
        if (state == null || state.saved) {
            periodBudgetDialog?.dismiss()
            if (activeDialog === periodBudgetDialog) activeDialog = null
            periodBudgetDialog = null
            periodBudgetInput = null
            if (state?.saved == true) persistentViewModel.closePeriodBudget()
            return
        }
        if (periodBudgetDialog == null) {
            val form = layoutInflater.inflate(R.layout.dialog_period_budget, null)
            form.findViewById<TextView>(R.id.periodBudgetTitle).text =
                getString(R.string.budget_for_month, BudgetPeriodFormatter.format(this, state.period))
            val input = form.findViewById<android.widget.EditText>(R.id.periodBudgetAmount).apply {
                setText(state.text)
                doAfterTextChanged { persistentViewModel.updatePeriodBudget(it.toString()) }
            }
            periodBudgetInput = input
            val dialog = androidx.appcompat.app.AlertDialog.Builder(this).setView(form).create()
            dialog.setCanceledOnTouchOutside(false)
            periodBudgetDialog = dialog
            activeDialog = dialog
            dialog.setOnCancelListener { persistentViewModel.closePeriodBudget() }
            form.findViewById<View>(R.id.periodBudgetSave).setOnClickListener {
                persistentViewModel.submitPeriodBudget()
            }
            form.findViewById<View>(R.id.periodBudgetCancel).setOnClickListener {
                persistentViewModel.closePeriodBudget()
            }
            dialog.show()
            dialog.window?.apply {
                setBackgroundDrawableResource(R.drawable.budget_dialog_surface)
                decorView.clipToOutline = true
                addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
                setDimAmount(0.38f)
                setGravity(android.view.Gravity.CENTER)
                setLayout(
                    (min(windowManager.currentWindowMetrics.bounds.width(), dp(440)) - dp(32)).coerceAtLeast(1),
                    android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                )
            }
        }
        periodBudgetInput?.isEnabled = !state.submitting && !state.interrupted
        periodBudgetInput?.error = when {
            state.interrupted -> getString(R.string.source_save_interrupted)
            state.error is com.example.simple.manymanager.domain.DataError.StorageFailure -> getString(R.string.source_save_error)
            state.error != null -> getString(R.string.budget_invalid_amount)
            else -> null
        }
        periodBudgetDialog?.setCancelable(!state.submitting)
        periodBudgetDialog?.findViewById<View>(R.id.periodBudgetSave)?.apply {
            isEnabled = !state.submitting && !state.interrupted
            alpha = if (isEnabled) 1f else 0.45f
        }
        periodBudgetDialog?.findViewById<View>(R.id.periodBudgetCancel)?.isEnabled = !state.submitting
    }

    private fun showAddSource(period: BudgetPeriod) {
        dismissActiveDialogImmediately()
        persistentViewModel.beginSourceCreation(period)
        renderSourceCreation(persistentViewModel.sourceCreationState.value)
    }

    private fun showEditSource(source: BudgetSource, period: BudgetPeriod) {
        dismissActiveDialogImmediately()
        persistentViewModel.beginSourceEditing(source, period)
        renderSourceEditing(persistentViewModel.sourceEditingState.value)
    }

    private fun renderSourceCreation(state: SourceCreationState?) {
        if (!contentReady || isFinishing || isDestroyed) return
        if (state == null) {
            sourceCreationDialog?.dispose()
            if (activeDialog === sourceCreationDialog?.dialog) {
                activeDialog = expenseDialog?.takeIf { it.isShowing } ?: expenseDetailsDialog?.dialog?.takeIf { it.isShowing }
            }
            sourceCreationDialog = null
            return
        }
        val created = (state.result as? DataResult.Success)?.value
        if (created != null) {
            sourceCreationDialog?.dispose()
            sourceCreationDialog = null
            activeDialog = expenseDialog?.takeIf { it.isShowing } ?: expenseDetailsDialog?.dialog?.takeIf { it.isShowing }
            persistentViewModel.acknowledgeSourceCreation()
            return
        }
        if (sourceCreationDialog == null) {
            val window = SourceCreationDialog(this,
                persistentViewModel::updateSourceCreationName,
                { persistentViewModel.submitSourceCreation() },
                persistentViewModel::cancelSourceCreation
            )
            sourceCreationDialog = window
            activeDialog = window.dialog
            window.show()
        }
        sourceCreationDialog?.render(state)
    }

    private fun renderSourceEditing(state: SourceEditingState?) {
        if (!contentReady || isFinishing || isDestroyed) return
        if (state == null) {
            sourceEditingDialog?.dispose()
            if (activeDialog === sourceEditingDialog?.dialog) {
                activeDialog = expenseDialog?.takeIf { it.isShowing } ?: expenseDetailsDialog?.dialog?.takeIf { it.isShowing }
            }
            sourceEditingDialog = null
            return
        }
        val updated = (state.submitResult as? DataResult.Success)?.value
        if (updated != null) {
            sourceEditingDialog?.dispose()
            sourceEditingDialog = null
            activeDialog = expenseDialog?.takeIf { it.isShowing } ?: expenseDetailsDialog?.dialog?.takeIf { it.isShowing }
            persistentViewModel.acknowledgeSourceEditing()
            return
        }
        if (sourceEditingDialog == null) {
            val window = SourceEditingDialog(this,
                persistentViewModel::updateSourceEditingName,
                { persistentViewModel.submitSourceEditing() },
                persistentViewModel::cancelSourceEditing
            )
            sourceEditingDialog = window
            activeDialog = window.dialog
            window.show()
        }
        sourceEditingDialog?.render(state)
    }

    private fun showRenameCategory(category: com.example.simple.manymanager.domain.Category) {
        dismissActiveDialogImmediately()
        persistentViewModel.beginCategoryRename(category)
        renderCategoryCreation(persistentViewModel.categoryCreation.value)
    }

    private fun showAddCategory() = showAddCategory(null)

    private fun showAddCategory(fromExpense: ExpenseSheetDialog?) {
        if (fromExpense == null) dismissActiveDialogImmediately()
        persistentViewModel.beginCategoryCreation(false, categoryManagementUi?.parentId)
        renderCategoryCreation(persistentViewModel.categoryCreation.value)
    }

    private fun renderCategoryState(state: CategoryListState) {
        if (!contentReady) return
        categoryManagementUi?.render(state)
        expenseDialog?.renderCategories(state)
        renderExpenseCreation(persistentViewModel.expenseCreationState.value)
    }

    private fun renderCategoryCreation(state: CategoryCreationState?) {
        if (!contentReady || isFinishing || isDestroyed) return
        if (state == null) {
            categoryDialog?.dispose()
            if (activeDialog === categoryDialog?.dialog) {
                activeDialog = expenseDialog?.takeIf { it.isShowing } ?: expenseDetailsDialog?.dialog?.takeIf { it.isShowing }
            }
            categoryDialog = null
            return
        }
        val created = (state.result as? DataResult.Success)?.value
        if (created != null) {
            if (state.fromExpense) {
                persistentViewModel.selectExpenseCategory(created.id)
                expenseDialog?.selectCategory(created.id, persistentViewModel.categories.value)
            }
            categoryDialog?.dispose()
            categoryDialog = null
            activeDialog = expenseDialog?.takeIf { it.isShowing } ?: expenseDetailsDialog?.dialog?.takeIf { it.isShowing }
            persistentViewModel.acknowledgeCategoryCreation()
            return
        }
        if (categoryDialog == null) {
            val window = CategoryCreationDialog(this,
                persistentViewModel::updateCategoryName,
                { persistentViewModel.submitCategoryCreation() },
                persistentViewModel::cancelCategoryCreation)
            categoryDialog = window
            activeDialog = window.dialog
            window.show()
        }
        categoryDialog?.render(state)
    }

    private fun showMenu() {
        dismissActiveDialogImmediately()
        val dialog = SideMenuDialog(this, findViewById(R.id.menuButton), ::navigateToPage)
        activeDialog = dialog
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null }
        dialog.show()
    }

    private fun dismissActiveDialogImmediately() {
        expenseDialog?.setOnDismissListener(null)
        expenseDetailsDialog?.dispose()
        expenseDetailsDialog = null
        categoryDialog?.dispose()
        categoryDialog = null
        periodBudgetDialog?.setOnCancelListener(null)
        periodBudgetDialog?.dismiss()
        periodBudgetDialog = null
        periodBudgetInput = null
        sourceCreationDialog?.dispose()
        sourceCreationDialog = null
        sourceEditingDialog?.dispose()
        sourceEditingDialog = null
        val dialog = activeDialog
        when (dialog) {
            is ExpenseSheetDialog -> dialog.dismissImmediately()
            is SideMenuDialog -> dialog.dismissImmediately()
            else -> dialog?.dismiss()
        }
        expenseDialog?.takeIf { it !== dialog }?.dismissImmediately()
        expenseDialog = null
        activeDialog = null
    }

    private fun showExpense() {
        dismissActiveDialogImmediately()
        persistentViewModel.beginExpenseCreation(LocalDate.now())
        renderExpenseCreation(persistentViewModel.expenseCreationState.value)
    }

    private fun inspectExpenses() {
        persistentViewModel.refresh()
        navigateToPage(1)
        selectTab(2)
    }

    private fun renderExpenseCreation(state: ExpenseCreationState?) {
        if (!contentReady || isFinishing || isDestroyed) return
        if (state?.expenseId != null && !state.saved) {
            if (expenseDetailsDialog == null) {
                expenseDetailsDialog = ExpenseDetailsDialog(this,
                    persistentViewModel::editExpense,
                    persistentViewModel::updateExpenseDraft,
                    { persistentViewModel.submitExpenseCreation() },
                    persistentViewModel::cancelExpenseEdit,
                    {
                        val unknown = persistentViewModel.expenseCreationState.value?.interrupted == true
                        persistentViewModel.cancelExpenseCreation()
                        if (unknown) inspectExpenses()
                    },
                    {
                        persistentViewModel.retryExpenseLoading()
                        persistentViewModel.retryCategories()
                        persistentViewModel.retrySources()
                    },
                    persistentViewModel::requestExpenseDeletion,
                    { persistentViewModel.confirmExpenseDeletion() },
                    persistentViewModel::cancelExpenseDeletion,
                    {
                        persistentViewModel.beginCategoryCreation(true)
                        renderCategoryCreation(persistentViewModel.categoryCreation.value)
                    }
                ).also { activeDialog = it.dialog; it.show() }
            }
            expenseDetailsDialog?.render(state, persistentViewModel.categories.value,
                persistentViewModel.sources.value, persistentViewModel.canSaveExpense())
            return
        }
        expenseDetailsDialog?.dispose()
        if (activeDialog === expenseDetailsDialog?.dialog) activeDialog = null
        expenseDetailsDialog = null
        summaryDialogScroll?.let { top ->
            findViewById<ScrollView>(R.id.pageScroll).let { scroll -> scroll.doOnPreDraw { scroll.scrollTo(0, top) } }
        }
        summaryDialogScroll = null
        if (state == null || state.saved) {
            expenseDialog?.setOnDismissListener(null)
            expenseDialog?.dismissImmediately()
            if (activeDialog === expenseDialog) activeDialog = null
            expenseDialog = null
            if (state?.saved == true) persistentViewModel.acknowledgeExpenseCreation()
            return
        }
        if (expenseDialog == null) {
            lateinit var dialog: ExpenseSheetDialog
            dialog = ExpenseSheetDialog(this, currentDate,
                persistentViewModel.categories.value, persistentViewModel.sources.value, state.draft,
                onDraftChanged = persistentViewModel::updateExpenseDraft,
                onSubmit = { persistentViewModel.submitExpenseCreation() },
                onRetryCategories = persistentViewModel::retryCategories,
                onRetrySources = persistentViewModel::retrySources
            ) { showAddCategory(dialog) }
            expenseDialog = dialog
            activeDialog = dialog
            dialog.setOnDismissListener {
                val unknown = persistentViewModel.expenseCreationState.value?.interrupted == true
                if (activeDialog === dialog) activeDialog = null
                if (expenseDialog === dialog) expenseDialog = null
                persistentViewModel.cancelExpenseCreation()
                if (unknown) inspectExpenses()
            }
            dialog.show()
        }
        expenseDialog?.renderOperation(state, persistentViewModel.canSaveExpense())
    }

    private fun showExpenseEditor(expense: com.example.simple.manymanager.domain.Expense) {
        dismissActiveDialogImmediately()
        summaryDialogScroll = findViewById<ScrollView>(R.id.pageScroll).scrollY
        persistentViewModel.openExpenseDetails(expense.id)
        renderExpenseCreation(persistentViewModel.expenseCreationState.value)
    }

    private fun saveUiState(outState: Bundle) {
        val hierarchy = SparseArray<Parcelable>()
        findViewById<View>(R.id.windowRoot).saveHierarchyState(hierarchy)
        outState.putSparseParcelableArray("viewHierarchy", hierarchy)
        persistentViewModel.expenseCreationState.value?.let {
            outState.putBundle("expenseCreation", it.toBundle())
        }
        persistentViewModel.periodBudgetDraft.value?.let { outState.putBundle("periodBudget", it.toBundle()) }
        outState.putString("categoryManagementParent", categoryManagementUi?.parentId)
        outState.putStringArrayList("expandedSummaryGroups", ArrayList(expandedSummaryGroups))
        summaryDialogScroll?.let { outState.putInt("summaryDialogScroll", it) }
        persistentViewModel.categoryCreation.value?.let {
            outState.putBundle("categoryCreation", it.toBundle())
        }
        persistentViewModel.sourceCreationState.value?.let {
            outState.putBundle("sourceCreation", it.toBundle())
        }
        persistentViewModel.sourceEditingState.value?.let {
            outState.putBundle("sourceEditing", it.toBundle())
        }
        persistentViewModel.selectedPeriod?.let {
            outState.putLongArray("selectedBudgetPeriod", longArrayOf(it.startEpochDay, it.endExclusiveEpochDay))
        }
        outState.putInt("pageScrollY", findViewById<ScrollView>(R.id.pageScroll).scrollY)
        outState.putInt("page", page)
        outState.putIntArray("pageHistory", pageHistory.flatMap {
            listOf(it.page, it.tab, it.scrollY)
        }.toIntArray())
        outState.putInt("tab", tab)
        outState.putLong("currentDate", currentDate.toEpochDay())
        outState.putIntArray("expanded", expanded.toIntArray())
    }

    override fun onSaveInstanceState(outState: Bundle) {
        saveUiState(outState)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        homeBudgetUi?.dispose()
        homeBudgetUi = null
        budgetManagementUi = null
        categoryManagementUi = null
        appearanceTransition.clear()
        dismissActiveDialogImmediately()
        super.onDestroy()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
}

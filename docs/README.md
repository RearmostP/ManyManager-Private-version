# ManyManager Simple — current application

This document describes `main` in `RearmostP/ManyManager-Private-version`, migrated with the full history of `original` from `RearmostP/ManyManager`. The personal edition remains on `main` in the old `RearmostP/ManyManager` repository; do not infer its behavior here. ManyManager Simple uses native Android XML/Views and AppCompat. `:androidApp` owns production data; `:shared` retains a Compose sample.

## Two independent calculators

The app provides exactly two user-visible calculators: Main calculator (`local-calculator`) and Second calculator (`second-calculator`). Existing records and settings stay on Main. Transactional insert-if-absent initialization preserves Main and idempotently adds Second with ILS and period start day 10. Second starts with zero budget and no categories, subcategories, payment methods or expenses. No examples or copies are inserted in production.

All existing tables already use calculator-scoped keys and foreign keys; schema **3** and migrations 1→2→3 remain unchanged. No new schema migration is needed. `ManyManagerRepository.forCalculator` returns an immutable scope sharing the same database/clock. Reads, owned-reference validation, observations and all writes stay within that scope. Opening a second-calculator screen creates a separate MainActivity instance and ViewModel tied to that identity, rather than switching a repository underneath a pending operation. Activity saved state and its launch intent retain that identity across recreation. Budget/category/method/expense drafts, loading/errors and submissions therefore remain attached to their own calculator.

Home has two equally sized purple expense buttons with localized calculator names and a comfortable gap. Each opens its own calculator's entry form, whose visible destination and offered categories/methods match the captured calculator. Selecting a child still stores one category ID and derives its main category; a category is required, a child is optional. The ornament stays between the actions and the lower summary stack. Both calculators have separate period/spending/remaining cards with 17sp bold amounts and their own budget expansion. Home observes and refreshes both active periods on resume/date/time/zone changes.

The menu entry immediately below Settings is “ניהול המחשבון השני” / “Manage second calculator” / “Управление вторым калькулятором”. It opens a focused hub linking to the reused budget/payment-method screen, category/subcategory management, All Expenses and expense creation for Second. All existing menu entries continue to target Main. Calculator labels remain visible in management pages and forms/details/pickers. Back returns within the secondary flow, then to its caller; opening a main menu destination never reuses a secondary draft.

Each calculator independently follows the existing 10th–9th period rules, zero budgets for new periods, no carry-over, exact money arithmetic, stable IDs and mandatory owned choices. Editing a date can move an expense between its own periods, never between calculators. Renaming never moves category children, expense assignments or money. The HTML preview has the same essential interaction using two independent in-memory model scopes; reload intentionally resets both.

## Single budget per calculator and payment methods

- Each calculator and budget period has one directly stored non-negative budget in exact integer `Long` minor units (agorot). There is no hidden budget source.
- Remaining = period budget − every expense dated within that period. A ₪20,000 budget with ₪100 cash and ₪250 card spending leaves ₪19,650. The cash/card breakdown shows ₪100/₪250 and counts; methods have no allocations or balances.
- Payment methods are name-only records, available across periods. Creation and renaming never request or modify money. Stable UUID identities survive renaming; duplicate names remain separate.
- Each expense requires a method, a positive exact amount and a civil date; a main category or actual subcategory is required for new expenses and saved edits; note remains optional. Historical null categories remain localized Uncategorized and stay in totals. Every reference belongs to the same calculator. Zero budgets and negative remaining balances are allowed.
- The existing `BudgetPeriodCalculator` defines civil-date intervals `[start, endExclusive)`: by default the 10th through the 9th. Dates are epoch days, not UTC-midnight timestamps. Supported start days remain 1–28.
- New periods start at zero budget, with no copying or carry-over. Methods remain available. Moving an expense across a period boundary recalculates both periods.
- Main and Second initialize idempotently with stable IDs; new calculators use start day 10 and ILS and empty business data. No calculator creation, currency or period-start settings UI is provided.
- Decimal point/comma input accepts at most two fractional digits, with no floating-point money or silent rounding. Aggregations use `BigInteger`; final exposed totals must fit `Long`. Invalid totals roll back writes.

## Storage and migration

Room schema **3** stores calculators, categories, payment methods (legacy table name `budget_sources`), `period_budgets` and expenses in app-private `databases/many-manager.db`. `period_budgets` is keyed by calculator and period start and references the calculator directly. Legacy source class/field names remain internal identifiers for payment methods, including expense `sourceId`.

Registered `MIGRATION_1_2` sums each old calculator/period’s source allocations into one budget, using exact Kotlin aggregation rather than SQLite SUM. It preserves all source IDs, including unallocated sources, expenses, references, categories, civil dates, notes and calculator settings. Negative legacy allocations or totals outside `Long` abort migration transactionally; data is never reset. All exported schemas are retained. Future changes require a version increment and tested migration; destructive fallback is forbidden.

Registered `MIGRATION_2_3` adds nullable category parent IDs, a calculator-scoped self foreign key and its index. It stages expenses transactionally while rebuilding categories and restores every expense unchanged, including historical null assignments. Existing flat categories become roots. Hierarchy triggers installed for fresh/opened databases and migrations reject self-parenting, missing/foreign parents, cycles and deeper nesting. A stored expense has one category ID: a child derives its parent from the category record, so independently mismatched parent/child assignments cannot be represented. No synthetic children are stored.

`ManyManagerApplication` owns Room and the repository. `ManyManagerRepository` owns validation, transactions, observations and mutations. `setPeriodBudget` replaces a selected period’s amount; `createPaymentMethod` and `renameSource` only change method records. Expense create/update/delete validate resulting snapshots before commit, including both periods for moves. Budget, category and method spending are computed in `BudgetCalculations`; no mutable balance table exists.

`PersistentDataViewModel` owns active/selected-period streams and guarded form drafts. Success follows commit, known failures preserve input for retry, and pending writes cannot submit twice. Home/Summary consume the same transactional active snapshot. Management and choices observe independently and converge on saved facts.

## Screens

Budget Management retains period arrows and shows one total with an Update budget action. Its separate Payment methods section lists name-only rows with Add and Rename actions. The budget form captures the selected period; rollover does not redirect its save.

Home shows title/navigation, two side-by-side calculator-specific Add expense actions, a centered decorative ornament and separate compact summaries toward the bottom of a tall viewport. The summary contains the period, spending and remaining amounts in matching 17sp bold type. Short windows, long labels and large amounts remain in normal scrolling flow. The ornament uses a transparent crop of the original reference, retains its proportions, follows light/dark colors and is excluded from focus/accessibility. Expanding the remaining row retains its chevron and shows total budget and spending by payment method. Summary retains All Expenses, Categories and Payment Methods with spending, counts and expandable inline expense rows in unified rounded cards. Methods without expenses are omitted from spending summaries.

Expense creation immediately shows a compact inline list of main categories in the form's normal scroll. A selected row shows a checkmark and the confirmed parent or parent/child path. Opening a parent lists only real children; choosing a child or the footer's Select main category confirms only the draft. Back/Cancel preserves the prior selection and every other draft field. Editing uses a separate two-level picker with the same confirmation rules. No Uncategorized, synthetic General or creation shortcuts appear in expense selection. With no main categories, a localized Category Management explanation appears and saving is disabled.

Category Management lists main categories; opening one shows its children and Add subcategory. Main creation creates only a root, and child creation requires a name and the selected root. Exactly two levels are supported. Stable IDs preserve expense assignments through name changes. Both Android and the HTML preview offer separate 48dp/48px pencil buttons for main-category and subcategory renaming. The prefilled dialog trims names, rejects blanks with localized validation and permits duplicates. Cancel/Back/Escape discard the draft. Android retains the form and category identity across recreation, blocks uncertain restored submissions and updates only the owned category name through Room; no schema migration is needed. Names and paths refresh in choices, details and summaries without changing hierarchy, expense assignments, amounts or counts. Summary parent totals/counts combine direct and child expenses exactly once; expanded parents with children show nonempty General/child sections and subtotals. General is presentation only. Details, All Expenses and payment-method expense lists show category paths.

Read-only details, full in-place editing, confirmed deletion, civil-date picker and optional note behavior remain. Save edits the same ID and returns to saved details. Cancel/Back discards edits; deletion requires confirmation. Closing details preserves Summary tab, expansion and scroll.

Hebrew (default RTL), Russian and English (LTR), and light/dark appearance remain supported. Appearance preferences stay outside Room. Simple’s applicationId is `com.manymanager.privateversion`; its internal Android namespace remains `com.example.simple.manymanager`. Previous builds used applicationId `com.example.simple.manymanager`; the new package installs separately and does not automatically inherit their database or preferences. The personal edition retains `com.example.manymanager`. The packages install separately and do not automatically share or transfer app-private data. Version and signing are unchanged. Exported Room schemas are also retained under the renamed database class’s schema directory for migration tests.

## Lifecycle and limits

Home/Summary follow the active period independently of historical management selection. Entering management selects the active period. Resume and date/time/zone changes refresh data; no background rollover job exists.

ViewModel retains pending forms across ordinary recreation. Restored Bundle forms with uncertain submissions are locked against replay; close, inspect saved data and start a deliberate new operation. Bundle recovery is not durable draft recovery or exactly-once storage across arbitrary process death.

No refunds/nonpositive expenses, cloud sync, accounts, more than two calculator scopes in the UI, historical Summary navigation, export or database encryption. Keep `allowBackup=true` without database exclusions. Actual backup/device transfer, API 30 runtime, manual TalkBack/large-font/physical-keyboard coverage and arbitrary process death during commit remain unverified.

## Development and visual reference

Keep Android and [ui-reference](../ui-reference/README.md) visually aligned, including essential states. The approved home design is implemented in both native XML/Views and HTML: two calculator expense actions above a decorative theme-aware ornament and two compact lower summaries. Financial and expense-entry behavior is unchanged. Preview data stays in memory and resets on reload; do not reproduce production persistence/lifecycle. Fictional examples belong in `ui-reference/js/fixture.js`. Verify affected interactions.

With a compatible JDK, or Android Studio’s Gradle runner:

```powershell
.\gradlew.bat :androidApp:assembleDebug :androidApp:testDebugUnitTest :androidApp:lintDebug
.\gradlew.bat :androidApp:assembleDebugAndroidTest
.\gradlew.bat :androidApp:connectedDebugAndroidTest
```

Before instrumentation inspect `leaveApksInstalledAfterRun=true`, the isolated Application/runner, UUID test database names, test preferences and installed packages. Never clear/uninstall production data. Check real runner counts.

Focused checks cover the ₪20,000 example, zero budget, method rename, edits/deletes and boundary moves; schema migration tests populate multiple calculators/methods/periods, preserve historical references and reject overflow without reset. See the task report for actual run results.

## Verification of the single-budget change

Android Studio Gradle passed debug assembly, lint, 32 JVM tests and test APK assembly. The connected Android device run passed 66 instrumentation tests, including populated schema 1→2 migration, exact maximum/overflow rollback and real Activity/dialog integration.

Chrome headless passed HTML checks: single budget model 14, expense forms 32, summary 35, expense details 65, unified cards 37 and single-budget UI 11. A 320px Hebrew budget screen screenshot was inspected. These results do not establish pixel parity, manual accessibility, API 30 runtime or arbitrary process-death behavior.

## Verification of the category hierarchy change

Android Studio Gradle passed debug assembly, lint, 33 JVM tests and test APK assembly. The connected Samsung-device run passed 72 instrumentation tests using the isolated `com.example.simple.manymanager.uitest` target, test Application/preferences and UUID test databases; neither installed production edition was cleared or uninstalled. Device execution used a local Gradle init script to override only the test run's application IDs.

Coverage includes populated 1→3 and 2→3 migrations, retained direct/null assignments and calculator settings, database/repository depth and ownership rejection, mandatory category writes, legacy edits, management creation, same-name children, browsing/cancel/draft preservation, recreation, parent/child Summary sections and exact totals/counts, and category paths in details/lists. Picker layouts were checked at 320dp in all three languages and both themes; Hebrew light and Russian dark screenshots were visually inspected. These results do not establish pixel parity, manual accessibility, API 30 runtime or arbitrary process-death behavior.

## Verification of the compact home layout

Debug assembly, lint, 33 JVM tests and test APK assembly passed. On the connected Samsung device, all 72 existing instrumentation tests passed; after correcting a new test method's JUnit return type and waiting for scrolling/layout to settle, both new HomeLayoutTest cases passed in a separate focused run. The latter cover real Add expense wiring and native 320dp-wide home layouts at 568dp/720dp heights in Hebrew, English and Russian, light/dark colors, very large spending/remaining values, long period labels, expansion/collapse and reaching the final breakdown row by scrolling. Six locale/theme screenshots were visually inspected.

Instrumentation used a local ignored init script with the isolated target `com.example.simple.manymanager.hometest`, test Application, in-memory/UUID databases, prefixed preferences and leave-APKs-installed enabled. The normal `com.example.simple.manymanager` debug APK, versionCode 2/versionName 0.2.0, was then rebuilt, installed and launched successfully without clearing or uninstalling existing data. The personal edition and main branch were untouched. The wireless connection briefly dropped after installation and was restored; a final post-installation manual tap-through could not be completed because the device was locked.

These checks do not establish API 30 runtime, arbitrary process-death behavior, full manual screen-reader/large-font/keyboard coverage or exact HTML/native pixel parity. The device's configured font is retained.

## Verification of category renaming and the purple home action

On 2026-11-08, Android Studio Gradle passed debug assembly, lint, 33 JVM tests and test APK assembly. The Samsung device passed the full 77-test instrumentation suite, then six focused CategoryRenamingTest/HomeLayoutTest cases after adding narrow locale/theme and rendered purple-pixel checks. The narrow rename case passed again after explicitly setting each test dialog's night mode to match its requested configuration. All six locale/theme variants use 320dp layouts; Hebrew dark and Russian light rename screenshots and the Hebrew purple home action were visually inspected.

Instrumentation used the isolated target `com.manymanager.privateversion.renametest`, BudgetTestApplication's in-memory Room database, UUID file fixtures, test-prefixed preferences and leave-APKs-installed enabled. Tests cover name trimming, blank rejection, duplicate names, owned-record validation, preserved hierarchy/expenses/totals, real management pencils, Cancel/Back/Escape, draft recreation and uncertain restored submissions. Normal `com.manymanager.privateversion` was rebuilt, installed and launched successfully; its home screenshot confirms the purple action. Existing production packages were not cleared or uninstalled.

Fresh Chrome headless profiles passed nine HTML pages: home-layout 557, category-renaming 194, subcategories 93, stage8 32, stage9 35, expense-details 65, summary-cards-checks 37, single-budget-ui 11 and stage6 14 (1,038 assertions). These results do not establish full manual accessibility, cross-browser parity, API 30 runtime or arbitrary process-death behavior.

## Verification of two independent calculators

On 2026-11-08, debug assembly, lint, all 33 JVM tests and test APK assembly passed. The complete Samsung-device instrumentation suite passed **83 tests**. New tests seed a populated old single-calculator database, initialize concurrently 20 times, verify exactly two rows and unchanged Main, then close/reopen the file with fresh repositories and verify both calculators' categories, methods, budgets and expenses. Schema 3 and all exported schemas remain unchanged; existing supported migration tests still pass.

Repository checks reject foreign parent/category/method/expense IDs, require a category, retain ownership/hierarchy through renaming, isolate budgets and period moves/deletion, and observe independent snapshots. Real Activity tests exercise both home buttons, localized destination labels and owned choices; second-calculator draft recreation/save/cancellation; second category renaming; main snapshot preservation; separate Home refresh and rollover; menu order below Settings; and the actual second hub → budget/methods/category navigation. Narrow Home tests cover equal, separate 72dp action targets across Hebrew/English/Russian, both themes, 320dp widths and short/tall windows.

The first new Activity run exposed test assumptions about a fake clock versus the form's explicitly chosen civil date, and about choices being ready as soon as the dialog heading appears. Tests now choose the intended period date and wait for loaded choices. All five focused new tests and the final full 83-test suite passed.

Instrumentation used `com.manymanager.privateversion.renametest`, the isolated test Application's in-memory database, UUID-named file databases, test-prefixed preferences and leave-APKs-installed enabled. Production databases/preferences were not cleared or uninstalled. Normal `com.manymanager.privateversion` was rebuilt, installed and launched; its native Home was inspected. Package identity, signing, version and release publishing were not changed.

Fresh Chrome headless profiles passed **1,137 assertions** across ten pages: two-calculators 87, home-layout 569, category-renaming 194, subcategories 93, stage8 32, stage9 35, expense-details 65, summary-cards-checks 37, single-budget-ui 11 and stage6 14. The new checks include attempts to redirect a second draft by changing renderer scope, cross-calculator references, scoped management/edits/deletion/period moves and six 320px locale/theme combinations. Hebrew light and Russian dark Home screenshots were inspected.

File reopen and ordinary Activity recreation are verified; arbitrary process death during commit, actual OS backup/transfer, API 30 runtime, full manual TalkBack/keyboard/large-font coverage and Safari/Firefox compatibility remain unverified.

## Implementation map

- Storage: `data/local/Entities.kt`, `ManyManagerDao.kt`, `ManyManagerDatabase.kt`, exported schemas 1 and 2.
- Rules: `data/ManyManagerRepository.kt`, `domain/BudgetCalculations.kt`, `Models.kt`, `BudgetPeriodCalculator.kt`, `BudgetAmounts.kt`.
- UI/state: `PersistentDataViewModel.kt`, `MainActivity.kt`, `BudgetManagementUi.kt`, `BudgetSourceDialogs.kt`, `BudgetHomeUi.kt`, `SummaryBreakdownUi.kt`, expense dialogs.
- Tests: `ManyManagerRepositoryTest`, `ManyManagerDatabaseTest`, `SingleBudgetMigrationTest`, `BudgetLifecycleTest`, JVM domain tests, HTML test pages.

Use [release.md](release.md) for release tasks. Historical documents in `docs/archive/` remain unchanged and are not current requirements.

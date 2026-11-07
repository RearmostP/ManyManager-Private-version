# ManyManager Simple UI reference

Current behavior: [branch documentation](../docs/README.md). This preview follows `main` in `RearmostP/ManyManager-Private-version`, formerly `original` in `RearmostP/ManyManager`. The personal edition remains in the old repository.

This is a lightweight, in-memory design playground for the native Android XML/Views app. Reload resets all examples, drafts and appearance choices. It does not emulate Room, migrations or Android lifecycle guarantees.

## Run and examples

From the repository root, serve `ui-reference` over HTTP:

```sh
python -m http.server 8000 --directory ui-reference
```

Open http://localhost:8000/. This branch has no `preview.py` launcher. ES modules and locale JSON require HTTP.

The default starts empty. Fictional examples are in `js/fixture.js`; import it explicitly for visual experiments. It demonstrates ₪20,000 budget, ₪100 cash and ₪250 card expenses, leaving ₪19,650. The optional `tests/summary-cards.html` fixture demonstrates long names, notes and expanded groups.

## Approved home layout

The approved home-screen design is implemented in native Android XML/Views and this preview. Navigation/title come first, then the prominent Add expense action, a centered decorative ornament, and a compact period summary toward the bottom of taller windows. Spending and remaining both use the previous remaining amount's 17px/700 typography. Remaining retains its chevron, expansion and payment-method spending breakdown. Short windows and expanded/long content scroll naturally without fixed-position controls.

`assets/home-ornament.svg` embeds the supplied reference pixels in a cropped viewBox. A luminance-to-alpha filter removes white while retaining the original proportions and fine strokes. CSS uses the local SVG as a mask, tinted soft gray per theme immediately. The ornament is aria-hidden, unfocusable and has no surface, border, shadow or animation. Android uses a transparent 568×164 PNG made with the same crop/alpha transfer, a proportion-preserving image view and light/dark resource tints. Native HomeLayout.kt keeps the summary in normal scrolling flow and wraps amount rows when necessary.

`tests/home-layout.html` drives the real preview in 320/360px by 568/900px windows, in Hebrew/English/Russian and both themes. It checks ordering, matching amount type, original ornament ratio/transparent background, tall-window bottom placement, scrolling, expansion, large values/long labels and the existing Add expense form. Query options `lang=he|en|ru&theme=light|dark&width=320&height=900`, `expanded`, `large`, `long` and `bottom` leave a fixture state visible for review. Reloading the normal preview still starts with empty in-memory data.

Verified on 2026-11-07 in Chrome headless: home layout **533 passed**, existing expense-entry `stage8.html` **32 passed**, and `single-budget-ui.html` **11 passed**. Visually reviewed all six language/theme combinations, including short expanded layouts and very large spending/remaining amounts with an extended period label. The existing stage8 home-spending selector was updated to the compact row. Financial/model, period and expense-entry code is unchanged. The native follow-up passed assembly/lint, 33 JVM tests, 72 existing instrumentation tests and two focused home tests on the Samsung device. Native 320dp layouts in all six locale/theme combinations were visually inspected; large amounts, long labels, scrolling and the real Add expense entry point were checked. See docs/README.md for isolation and remaining gaps. These checks do not establish Safari/Firefox compatibility, full manual screen-reader coverage or exact native/browser pixel parity.

## Budget and payment methods

Budget Management retains period arrows, the date range and total card, and offers Update budget for the selected period. A separate Payment methods section has Add and Rename actions. Method forms accept a name alone; no initial allocation, adjustment controls or method balance exists. A budget form captures its selected period.

Each period has one budget in integer minor units. Remaining is that budget minus the expenses dated in the period. The 10th starts a new period; the 9th ends the previous one. New periods have zero budgets and keep methods. Spending with zero budget or negative remaining is allowed.

Home shows active-period spending and remaining; its expanded panel shows total budget and spending by payment method. Summary keeps category and payment-method spending/counts with expandable expense rows in unified rounded cards, plus All Expenses. Methods have no budgets or remaining balances.

Each new or edited expense requires a payment method, a positive amount, a civil date and a category (main category or one of its subcategories). The note is optional. Create categories and subcategories through Category Management before opening expense creation. Details open read-only; Edit changes the same ID in place, Cancel discards the draft, Delete requires confirmation. Edits and deletion immediately recalculate totals, including moves across period boundaries. Renaming a method preserves expense references and spending.

Hebrew RTL, Russian/English LTR and light/dark appearances remain supported. Native dialogs, fonts, IME and system bars can differ from HTML; pixel parity is not asserted.

## Optional subcategories

Expense creation immediately displays a compact inline list of main categories. The confirmed row is highlighted with a checkmark and shows the selected parent or parent/child path. Long lists use the form’s normal scrolling, with no nested category viewport. Every main category opens a view titled with its name and listing only actual user-created subcategories. The footer’s Select main category button (Hebrew: בחירה בקטגוריה הראשית) confirms the parent directly, including when it has no children. Selecting a child confirms its parent/child path. Both actions update only the draft; Save in the expense form saves the expense. In expense creation, Back returns to the inline main list; editing retains its separate picker. Cancel preserves the previously confirmed category and all other draft fields. Browsing never changes the draft selection. No Uncategorized or synthetic General row is selectable.

Category management opens a parent’s children and offers Add subcategory. Stable category IDs and optional parent IDs allow exactly two levels. Summary cards combine parent and child expenses once, with nonempty General/child sections when expanded. The fixture reuses the ₪100 and ₪250 records for direct Food and Supermarket expenses; Health has no children. Budget and payment methods are unchanged.

The former repeated parent row was a synthetic picker option, not a stored child. Creating a main category creates only that category. Genuine same-name children remain selectable by their own stable IDs. General remains only a summary section for direct-parent expenses. Existing uncategorized records remain visible and included in totals, but saving an edit requires an explicit category. New writes require a valid category in both the form and preview model. Expense creation has no Add category or Add subcategory shortcuts, including in its subcategory view. An empty list shows a localized instruction to create a category in Category Management and Save stays disabled. Category Management retains both creation actions. The native XML/Views implementation follows this selection and validation model with persistent Room hierarchy storage.

This is the approved category experience for ManyManager Simple. The preview stays in memory; Android persists the hierarchy and validates writes through its repository and database.

## Checks

Serve the preview and open:

- `tests/subcategories.html`: inline creation list without creation shortcuts, normal form scrolling, required category, actual-child lists, parent footer confirmation, legacy data, draft preservation, stable IDs, exact totals, editing/deletion and 320px locale/theme layouts.
- `tests/single-budget-ui.html`: real budget entry, name-only method forms, remaining balance and summaries.
- `tests/stage6.html`: single budget example, zero budget, required method, rename, edit/delete and 9th/10th boundary moves.
- `tests/stage8.html`: expense/category forms and real app events.
- `tests/stage9.html`: spending breakdown consistency and real app events.
- `tests/expense-details.html`: read-only/edit/delete flows and narrow locale/theme layouts.
- `tests/summary-cards-checks.html`: unified card/expansion/detail interactions.

For a narrow visual review, `tests/subcategories.html?visual=picker&lang=he&theme=light` leaves the picker open after the checks; `visual=creation` leaves the inline creation form open; `lang=ru` / `en` and `theme=dark` are also supported. The long mixed-language name intentionally stress-tests wrapping.

Chrome headless passed all seven HTML check pages (287 assertions, including 93 inline/category-picker checks). Coverage includes required-category rejection, parent confirmation with/without children, same-name children, legacy uncategorized records, totals and 320px Hebrew/Russian/English light/dark layouts. Real Samsung-device usability remains unverified.

Checks use only isolated memory. Historical result counts are not fresh verification; see the current task report for actual outcomes. Android additionally tests transactions, populated version-1 migration and overflow rollback. Keep this preview focused on visual behavior rather than reproducing production persistence or lifecycle.

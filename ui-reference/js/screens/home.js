import {state} from "../state.js";
import {snapshotFor} from "../preview-model.js";
import {icon} from "../icons.js";
import {tr,money,periodLabel,periodDescription,escapeValue} from "../render-helpers.js";

export function home(){
  const snapshot=snapshotFor(state.currentDate);
  const total=snapshot.allocated,remaining=snapshot.remaining;
  const rows=snapshot.sources.map(item=>`<div class="budget-line budget-source"><span>${escapeValue(item.source.name)}</span><span dir="ltr">${money(item.spent)}</span></div>`).join("");
  return `<section class="home"><h1 class="home-title">${tr("home_title")}</h1>
  <button class="add-expense primary" data-action="expense">${tr("add_expense")}${icon("plus","small")}</button>
  <div class="home-ornament-space" aria-hidden="true"><span class="home-ornament"></span></div>
  <div class="card home-card">
    <div class="home-month" aria-label="${escapeValue(periodDescription(state.language,state.currentDate))}">${periodLabel(state.language,state.currentDate)}</div>
    <div class="home-summary-row home-spending"><span class="spacer">${tr("monthly_total")}</span><span class="money">${money(snapshot.spent)}</span></div>
    <div class="divider"></div>
    <button class="home-summary-row budget-toggle ${state.budgetExpanded?"open":""}" data-action="budget-toggle" aria-expanded="${state.budgetExpanded}" aria-controls="home-budget-breakdown"><span class="spacer">${tr("remaining_budget")}</span><span class="home-summary-value"><span class="money">${money(remaining)}</span>${icon("down","small muted")}</span></button>
    <div id="home-budget-breakdown" class="budget-breakdown" ${state.budgetExpanded?"":"hidden"}><div class="budget-line"><span>${tr("total_budget_for_month")}</span><span dir="ltr">${money(total)}</span></div>${rows||`<div class="budget-empty">${tr("no_budget_sources_yet")}</div>`}</div>
  </div></section>`;
}

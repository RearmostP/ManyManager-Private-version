import {state} from "../state.js";
import {calculatorIds,calculatorModel,MAIN_CALCULATOR} from "../preview-model.js";
import {icon} from "../icons.js";
import {tr,money,periodLabel,periodDescription,escapeValue,calculatorName} from "../render-helpers.js";

export function home(){
 const cards=calculatorIds.map(id=>{
  const snapshot=calculatorModel(id).snapshotFor(state.currentDate);
  const expanded=id===MAIN_CALCULATOR?state.budgetExpanded:state.secondBudgetExpanded;
  const panelId=id===MAIN_CALCULATOR?"home-budget-breakdown":"second-budget-breakdown";
  const rows=snapshot.sources.map(item=>`<div class="budget-line budget-source"><span>${escapeValue(item.source.name)}</span><span dir="ltr">${money(item.spent)}</span></div>`).join("");
  return `<div class="card home-card" data-calculator="${id}">
   <p class="calculator-label">${calculatorName(id)}</p>
   <div class="home-month" aria-label="${escapeValue(periodDescription(state.language,state.currentDate))}">${periodLabel(state.language,state.currentDate)}</div>
   <div class="home-summary-row home-spending"><span class="spacer">${tr("monthly_total")}</span><span class="money">${money(snapshot.spent)}</span></div>
   <div class="divider"></div>
   <button class="home-summary-row budget-toggle ${expanded?"open":""}" data-action="budget-toggle" data-calculator="${id}" aria-expanded="${expanded}" aria-controls="${panelId}"><span class="spacer">${tr("remaining_budget")}</span><span class="home-summary-value"><span class="money">${money(snapshot.remaining)}</span>${icon("down","small muted")}</span></button>
   <div id="${panelId}" class="budget-breakdown" ${expanded?"":"hidden"}><div class="budget-line"><span>${tr("total_budget_for_month")}</span><span dir="ltr">${money(snapshot.allocated)}</span></div>${rows||`<div class="budget-empty">${tr("no_budget_sources_yet")}</div>`}</div>
  </div>`;
 }).join("");
 return `<section class="home"><h1 class="home-title">${tr("home_title")}</h1>
 <div class="home-expense-actions">${calculatorIds.map(id=>`<button class="add-expense primary" data-action="expense" data-calculator="${id}"><span>${tr("add_expense")}</span><span class="calculator-button-name">${calculatorName(id)}</span></button>`).join("")}</div>
 <div class="home-ornament-space" aria-hidden="true"><span class="home-ornament"></span></div>
 <div class="home-summary-stack">${cards}</div></section>`;
}

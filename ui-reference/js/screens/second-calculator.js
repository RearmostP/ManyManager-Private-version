import {tr} from "../render-helpers.js";
export function secondCalculator(){
 return `<section class="categories-page"><h1>${tr("second_calculator_management")}</h1>
 <button class="chip category-add" data-action="calculator-page" data-index="3">${tr("budget_management")}</button>
 <button class="chip category-add" data-action="calculator-page" data-index="4">${tr("expense_categories")}</button>
 <button class="chip category-add" data-action="calculator-page" data-index="1">${tr("all_expenses")}</button>
 <button class="primary category-add" data-action="expense" data-calculator="second-calculator">${tr("add_expense")}</button></section>`;
}

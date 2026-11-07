import {state} from "../state.js";
import {previewModel,parseAmount,categoryPath,isValidCategory,mainCategoryId} from "../preview-model.js";
import {parseCivilDate} from "../budget-period.js";

export function canSaveExpense(){
 const draft=state.expenseDraft;
 return (!draft.calculatorId||draft.calculatorId===previewModel.calculatorId)&&parseAmount(draft.amount)>0&&parseCivilDate(draft.date)!==null&&previewModel.sources.some(item=>item.id===draft.sourceId)&&isValidCategory(draft.categoryId);
}
import {icon} from "../icons.js";
import {tr,escapeValue} from "../render-helpers.js";

export function categoryField(id="expense-category"){
  return `<button id="${id}" class="sheet-field category-field" data-action="open-category-picker">${escapeValue(categoryPath(state.expenseDraft.categoryId)??tr("choose_category"))}</button>`;
}
export function inlineCategories(){
  const roots=previewModel.categories.filter(item=>!item.parentId);
  if(!roots.length)return `<p class="empty" data-category-empty>${tr("category_management_required")}</p>`;
  return `<div id="expense-category" class="expense-choices">${roots.map(category=>{
    const selected=mainCategoryId(state.expenseDraft.categoryId)===category.id;
    return `<button class="chip expense-main-category ${selected?"selected":""}" data-action="open-expense-category" data-id="${category.id}" aria-pressed="${selected}"><span class="category-choice-label">${escapeValue(selected?categoryPath(state.expenseDraft.categoryId):category.name)}</span>${selected?'<span aria-hidden="true">✓</span>':""}</button>`;
  }).join("")}</div>`;
}
export function expense(){

  const sources=previewModel.sources.map(source=>`<button class="chip ${state.expenseDraft.sourceId===source.id?"selected":""}" data-action="expense-source" data-id="${source.id}">${escapeValue(source.name)}</button>`).join("");
  return `<div class="scrim sheet-wrap" data-action="close-overlay"><div class="overlay-stage"><div class="sheet" data-stop-close><div class="drag-area" data-action="drag"><div class="drag-track"></div></div>
  <div class="sheet-head"><h2>${tr("new_expense")}</h2><button class="icon-button" data-action="close-overlay" aria-label="${tr("close")}">${icon("close")}</button></div>
  <label class="field-label" for="amount">${tr("expense_amount")}</label><input id="amount" class="amount-input" data-expense="amount" inputmode="decimal" placeholder="${tr("amount_zero")}" value="${escapeValue(state.expenseDraft.amount)}">
  <div class="section"><p class="field-label">${tr("expense_category")}</p>${inlineCategories()}</div>
  <div class="section" style="margin-top:15px"><p class="field-label">${tr("payment_method")}</p>${sources?`<div class="expense-choices">${sources}</div>`:`<p class="empty">${tr("no_payment_methods")}</p>`}</div>
  <div class="form-row"><div><label class="field-label" for="expense-date">${tr("expense_date")}</label><input id="expense-date" type="date" class="sheet-field" dir="ltr" data-expense="date" value="${escapeValue(state.expenseDraft.date)}"></div>
  <div><label class="field-label" for="expense-note">${tr("optional_note")}</label><textarea id="expense-note" class="sheet-field" data-expense="note" placeholder="${tr("expense_note_hint")}">${escapeValue(state.expenseDraft.note)}</textarea></div></div>
  <p id="expense-error" role="alert" hidden></p><button class="primary save" data-action="save-expense" ${canSaveExpense()?"":"disabled"}>${tr("save_expense")}</button></div></div></div>`;
}

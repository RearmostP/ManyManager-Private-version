import {adjacentPeriod,civilDate,periodFor} from "./budget-period.js";
import {state,changePage,goBack,closeOverlay,cancelExpenseEdit,savedExpenseDraft} from "./state.js";
import {loadLanguage,t,shortDate} from "./i18n.js";
import {icon} from "./icons.js";
import {previewModel,addSource,updateSource,setBudget,totalFor,parseAmount,createExpense,updateExpense,deleteExpense,observePreview} from "./preview-model.js";
import {home} from "./screens/home.js";
import {summary} from "./screens/summary.js";
import {budget} from "./screens/budget.js";
import {settings} from "./screens/settings.js";
import {categories} from "./screens/categories.js";
import {drawer} from "./components/drawer.js";
import {expense,canSaveExpense} from "./components/expense-sheet.js";
import {expenseDelete} from "./components/expense-delete.js";
import {expenseDetails,expenseDetailsBody} from "./components/expense-details.js";
import {categoryDialog,beginCategoryCreation,confirmCategoryCreation,beginCategoryRename,confirmCategoryRename} from "./components/category-dialog.js";
import {sourceDialog,canConfirmSourceEdit,budgetDialog} from "./components/source-dialog.js";

import {categoryPicker,openCategoryPicker,chooseCategory,selectCategory} from "./components/category-picker.js";

const app=document.getElementById("app");
const page=document.getElementById("page");
const scroll=document.getElementById("page-scroll");
const overlay=document.getElementById("overlay-root");
const menuButton=document.getElementById("menu-button");
const translate=key=>t(state.language,key);

function render(){
  state.currentDate=new Date();
  document.documentElement.lang=state.language;
  document.documentElement.dir=state.language==="he"?"rtl":"ltr";
  document.documentElement.dataset.theme=state.theme;
  document.title=translate("app_name");
  menuButton.setAttribute("aria-label",translate("menu_button_label"));
  menuButton.innerHTML=icon("menu");
  page.innerHTML=[home,summary,settings,budget,categories][state.page]();
  if(["expense-details","delete-expense"].includes(state.overlay)||(state.overlay==="category-picker"&&state.pickerReturnOverlay==="expense-details")){
    if(!overlay.querySelector("#expense-details"))overlay.innerHTML=expenseDetails();
    const panel=overlay.querySelector("#expense-details");
    const focused=document.activeElement,focusId=panel.contains(focused)?focused.id:null;
    panel.innerHTML=expenseDetailsBody();
    panel.inert=state.overlay!=="expense-details";
    if(panel.inert)panel.setAttribute("aria-hidden","true");else panel.removeAttribute("aria-hidden");
    overlay.querySelector("[data-expense-child-overlay]")?.remove();
    if(panel.inert){
      const confirmation=document.createElement("div");confirmation.dataset.expenseChildOverlay="";
      confirmation.innerHTML=state.overlay==="category-picker"?categoryPicker():expenseDelete();overlay.append(confirmation);
    }else if(focusId)panel.querySelector("#"+CSS.escape(focusId))?.focus({preventScroll:true});
  }else overlay.innerHTML=({
    menu:drawer,expense,"add-category":categoryDialog,"edit-category":categoryDialog,"category-picker":categoryPicker,
    "add-source":sourceDialog,"edit-source":sourceDialog,"edit-budget":budgetDialog
  })[state.overlay]?.()??"";
  app.inert=Boolean(state.overlay);
  scroll.style.overflow=state.overlay?"hidden":"auto";
}
function renderKeepScroll(){
  const top=scroll.scrollTop;
  render();
  scroll.scrollTop=top;
}
function showError(id,key){
  const element=document.getElementById(id);
  element.textContent=translate(key);
  element.hidden=false;
}
function clearError(id){
  const element=document.getElementById(id);
  if(element)element.hidden=true;
}
await loadLanguage(state.language);
render();
observePreview(()=>renderKeepScroll());

document.addEventListener("input",event=>{
  if(event.target.dataset.details && state.expenseEditing){
    const key=event.target.dataset.details;
    state.expenseDraft[key]=["categoryId","sourceId"].includes(key)?(event.target.value||null):event.target.value;
    state.expenseDetailsError=null;
    const save=document.querySelector('[data-action="save-expense-details"]');
    if(save)save.disabled=!canSaveExpense();
    clearError("expense-details-error");
  }
  if(event.target.dataset.expense){
    state.expenseDraft[event.target.dataset.expense]=event.target.value;
    document.querySelector('[data-action="save-expense"]').disabled=!canSaveExpense();
    clearError("expense-error");
  }
  if(event.target.hasAttribute("data-category-draft")){
    state.categoryDraft=event.target.value;
    clearError("category-error");
  }
  if(event.target.dataset.sourceDraft){
    state.sourceDraft[event.target.dataset.sourceDraft]=event.target.value;
    clearError("source-error");
    if(state.overlay==="edit-source"){
      document.querySelector('[data-action="confirm-edit-source"]').disabled=!canConfirmSourceEdit();
    }
  }
});
document.addEventListener("click",async event=>{
  const button=event.target.closest("[data-action]");
  if(!button||(app.inert&&app.contains(button)))return;
  if(button.classList.contains("scrim")&&event.target.closest("[data-stop-close]"))return;
  switch(button.dataset.action){
    case "close-overlay":
      closeOverlay();renderKeepScroll();break;
    case "navigate":
      changePage(Number(button.dataset.index),scroll);state.overlay=null;render();break;
    case "back":
      if(goBack(scroll))renderKeepScroll();break;
    case "edit-category":
      if(beginCategoryRename(button.dataset.id)){
        renderKeepScroll();document.getElementById("category-name")?.focus();
      }
      break;
    case "confirm-rename-category":
      if(!confirmCategoryRename()){showError("category-error","category_name_required");break}
      renderKeepScroll();break;
    case "add-category":
      beginCategoryCreation();renderKeepScroll();break;
    case "confirm-category":
      if(!confirmCategoryCreation()){showError("category-error","category_name_required");break}
      renderKeepScroll();break;
    case "add-source":
      state.dialogMonth=new Date(state.budgetMonth);
      state.sourceDraft={name:"",sourceId:null};
      state.overlay="add-source";renderKeepScroll();break;
    case "edit-source":{
      const source=previewModel.sources.find(item=>item.id===button.dataset.id);
      if(!source)break;
      state.dialogMonth=new Date(state.budgetMonth);
      state.sourceDraft={name:source.name,sourceId:source.id};
      state.overlay="edit-source";renderKeepScroll();break;
    }
    case "confirm-add-source":{
      if(!state.sourceDraft.name.trim()){showError("source-error","source_name_required");break}
      if(!addSource(state.sourceDraft.name)){
        showError("source-error","budget_invalid_amount");break;
      }
      closeOverlay();renderKeepScroll();break;
    }
    case "confirm-edit-source":{

      if(!state.sourceDraft.name.trim()){showError("source-error","source_name_required");break}
      if(!updateSource(state.sourceDraft.sourceId,state.sourceDraft.name)){
        showError("source-error","budget_invalid_amount");break;
      }
      closeOverlay();renderKeepScroll();break;
    }
    case "edit-budget":
      state.dialogMonth=new Date(state.budgetMonth);
      state.budgetDraft=String(totalFor(state.dialogMonth)/100);
      state.overlay="edit-budget";renderKeepScroll();break;
    case "confirm-budget":{
      const amount=parseAmount(document.getElementById("period-budget").value);
      if(amount===null||!setBudget(state.dialogMonth,amount)){
        showError("source-error","budget_invalid_amount");break;
      }
      closeOverlay();renderKeepScroll();break;
    }
    case "open-category":
      state.categoryManagementId=button.dataset.id;renderKeepScroll();break;
    case "open-expense-category":
      openCategoryPicker();chooseCategory(button.dataset.id);renderKeepScroll();break;
    case "open-category-picker":
      openCategoryPicker();renderKeepScroll();break;
    case "category-picker-back":
      if(state.pickerReturnOverlay==="expense")closeOverlay();else state.pickerParentId=null;
      renderKeepScroll();break;
    case "choose-category":
      chooseCategory(button.dataset.id??null);renderKeepScroll();break;
    case "select-category":
      selectCategory(button.dataset.id);renderKeepScroll();break;
    case "expense-source":
      state.expenseDraft.sourceId=button.dataset.id;renderKeepScroll();break;
    case "expense":
      state.expenseEditId=null;state.expenseOriginal=null;state.expenseEditing=false;state.expenseDetailsError=null;
      state.overlay="expense";
      state.expenseDraft={amount:"",date:civilDate(state.currentDate),note:"",categoryId:null,sourceId:null};
      renderKeepScroll();break;
    case "save-expense":{
      if(!canSaveExpense())break;
      const input={...state.expenseDraft,amountMinor:parseAmount(state.expenseDraft.amount)};
      const saved=createExpense(input);
      if(!saved){
        showError("expense-error",state.expenseEditId&&!previewModel.expenses.some(item=>item.id===state.expenseEditId)?"expense_missing_record":"expense_save_error");break;
      }
      closeOverlay();renderKeepScroll();break;
    }
    case "view-expense":{
      const item=previewModel.expenses.find(value=>value.id===button.dataset.id);
      state.expenseEditId=button.dataset.id;state.expenseOriginal=item?{...item}:null;
      state.expenseEditing=false;state.expenseDetailsError=null;
      state.expenseDraft=savedExpenseDraft(item);
      state.overlay="expense-details";renderKeepScroll();break;
    }
    case "begin-expense-edit":{
      const saved=previewModel.expenses.find(item=>item.id===state.expenseEditId);
      if(!saved){renderKeepScroll();break}
      state.expenseDraft=savedExpenseDraft(saved);state.expenseEditing=true;state.expenseDetailsError=null;
      renderKeepScroll();break;
    }
    case "cancel-expense-edit":
      cancelExpenseEdit();renderKeepScroll();break;
    case "save-expense-details":{
      if(state.overlay!=="expense-details"||!state.expenseEditing||!canSaveExpense())break;
      if(!updateExpense(state.expenseEditId,{...state.expenseDraft,amountMinor:parseAmount(state.expenseDraft.amount)})){
        state.expenseDetailsError=previewModel.expenses.some(item=>item.id===state.expenseEditId)?"expense_save_error":"expense_missing_record";
        renderKeepScroll();break;
      }
      cancelExpenseEdit();renderKeepScroll();break;
    }
    case "delete-expense":{
      const saved=previewModel.expenses.find(item=>item.id===state.expenseEditId);
      if(state.overlay!=="expense-details"||!state.expenseEditing)break;
      if(!saved){state.expenseDetailsError="expense_missing_record";renderKeepScroll();break}
      state.expenseOriginal={...saved};
      state.overlay="delete-expense";renderKeepScroll();break;
    }
    case "confirm-delete-expense":{
      if(state.overlay!=="delete-expense"||!state.expenseEditing)break;
      const deleted=deleteExpense(state.expenseEditId);
      state.overlay=deleted?null:"expense-details";
      if(!deleted)state.expenseDetailsError=previewModel.expenses.some(item=>item.id===state.expenseEditId)?"expense_save_error":"expense_missing_record";
      renderKeepScroll();
      break;
    }
    case "expense-group":{
      const key=button.dataset.kind+":"+(button.dataset.id||"");
      if(state.expandedSummaryGroups.has(key))state.expandedSummaryGroups.delete(key);
      else state.expandedSummaryGroups.add(key);
      renderKeepScroll();
      document.querySelector('[data-action="expense-group"][data-kind="'+button.dataset.kind+'"][data-id="'+(button.dataset.id||"")+'"]')?.focus({preventScroll:true});
      break;
    }
    case "budget-toggle":
      state.budgetExpanded=!state.budgetExpanded;renderKeepScroll();break;
    case "tab":
      state.tab=Number(button.dataset.index);renderKeepScroll();break;
    case "budget-prev":
    case "budget-next":
      state.budgetMonth=adjacentPeriod(state.budgetMonth,button.dataset.action==="budget-prev"?-1:1);
      renderKeepScroll();break;
    case "toggle-setting":{
      const key=button.dataset.key;
      state.expanded.has(key)?state.expanded.delete(key):state.expanded.add(key);
      renderKeepScroll();break;
    }
    case "language":
      if(state.language!==button.dataset.value){
        await loadLanguage(button.dataset.value);
        state.language=button.dataset.value;state.page=2;state.expanded.add("language");
        renderKeepScroll();
      }
      break;
    case "theme":
      state.theme=button.dataset.value;renderKeepScroll();break;
  }
});
function refreshToday(){
  if(new Date().toDateString()!==state.currentDate.toDateString())renderKeepScroll();
}
window.addEventListener("focus",refreshToday);
document.addEventListener("visibilitychange",()=>{if(!document.hidden)refreshToday()});
setInterval(()=>{if(!document.hidden)refreshToday()},60000);
menuButton.addEventListener("click",()=>{state.overlay="menu";renderKeepScroll()});
document.addEventListener("keydown",event=>{
  if(event.key==="Escape"||(event.key==="Backspace"&&event.target.tagName!=="INPUT"&&event.target.tagName!=="TEXTAREA"&&event.target.tagName!=="SELECT")){
    if(goBack(scroll)){event.preventDefault();renderKeepScroll()}
  }
});

let drag=null;
overlay.addEventListener("pointerdown",event=>{
  const handle=event.target.closest(".drag-area");
  if(!handle||state.overlay!=="expense"||drag)return;
  const sheet=handle.closest(".sheet");
  drag={pointerId:event.pointerId,startY:event.clientY,distance:0,handle,sheet};
  handle.setPointerCapture(event.pointerId);
});
overlay.addEventListener("pointermove",event=>{
  if(!drag||event.pointerId!==drag.pointerId)return;
  drag.distance=Math.max(0,event.clientY-drag.startY);
  drag.sheet.style.transform=`translateY(${drag.distance}px)`;
});
function finishDrag(event,cancelled){
  if(!drag||event.pointerId!==drag.pointerId)return;
  const {handle,sheet,distance,pointerId}=drag;
  drag=null;
  if(handle.hasPointerCapture(pointerId))handle.releasePointerCapture(pointerId);
  if(!cancelled&&distance>=64){
    state.overlay=null;renderKeepScroll();
  }else{
    sheet.style.transition="transform 180ms ease";
    sheet.style.transform="translateY(0)";
  }
}
overlay.addEventListener("pointerup",event=>finishDrag(event,false));
overlay.addEventListener("pointercancel",event=>finishDrag(event,true));

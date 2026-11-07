import {periodFor} from "./budget-period.js";
import {previewModel,MAIN_CALCULATOR,SECOND_CALCULATOR} from "./preview-model.js";

export const state={
  page:0,tab:0,language:"he",theme:"light",overlay:null,
  calculatorId:MAIN_CALCULATOR,operationCalculatorId:MAIN_CALCULATOR,secondBudgetExpanded:false,
  history:[],expanded:new Set(),budgetExpanded:false,
  currentDate:new Date(),budgetMonth:periodFor(new Date()).start,
  expenseDraft:{amount:"",date:"",note:"",categoryId:null,sourceId:null},
  expenseEditId:null,expenseOriginal:null,expandedSummaryGroups:new Set(),expenseEditing:false,expenseDetailsError:null,
  categoryDraft:"",categoryEditId:null,categoryReturnOverlay:null,categoryParentId:null,categoryManagementId:null,
  pickerParentId:null,pickerReturnOverlay:null,
  sourceDraft:{name:"",sourceId:null},budgetDraft:"",
  dialogMonth:null
};
export function changePage(index,scrollElement,calculatorId=state.calculatorId){
  if(index===state.page&&calculatorId===state.calculatorId)return;
  if(index===3)state.budgetMonth=periodFor(state.currentDate).start;
  if(index===0)state.history=[];
  else state.history.push({page:state.page,tab:state.tab,scroll:scrollElement.scrollTop,calculatorId:state.calculatorId,parentId:state.categoryManagementId});
  if(calculatorId!==state.calculatorId){state.categoryManagementId=null;state.expandedSummaryGroups.clear()}
  state.calculatorId=calculatorId;
  state.page=index;state.budgetExpanded=false;scrollElement.scrollTop=0;
}
export function savedExpenseDraft(item){
  return item?{calculatorId:item.calculatorId??state.operationCalculatorId,amount:`${Math.trunc(item.amountMinor/100)}.${String(item.amountMinor%100).padStart(2,"0")}`,
    date:item.date,categoryId:item.categoryId,sourceId:item.sourceId,note:item.note??""}:
    {amount:"",date:"",categoryId:null,sourceId:null,note:""};
}
export function cancelExpenseEdit(){
  state.expenseEditing=false;state.expenseDetailsError=null;
  state.expenseDraft=savedExpenseDraft(previewModel.expenses.find(item=>item.id===state.expenseEditId));
}
export function closeOverlay(){
  if(state.overlay==="category-picker"){
    state.overlay=state.pickerReturnOverlay;state.pickerParentId=null;
  }else if(state.overlay==="add-category"){
    state.overlay=state.categoryReturnOverlay;
    state.categoryReturnOverlay=null;
  }else if(state.overlay==="edit-category"){
    state.overlay=null;state.categoryEditId=null;state.categoryDraft="";
  }else if(state.overlay==="delete-expense")state.overlay="expense-details";
  else if(state.overlay==="expense-details"&&state.expenseEditing)cancelExpenseEdit();
  else state.overlay=null;
}
export function goBack(scrollElement){
  if(state.overlay){closeOverlay();return true}
  if(state.page===4&&state.categoryManagementId){state.categoryManagementId=null;return true}
  if(state.page===0&&state.budgetExpanded){state.budgetExpanded=false;return true}
  if(state.page===0&&state.secondBudgetExpanded){state.secondBudgetExpanded=false;return true}
  if(state.page===0)return false;
  const previous=state.history.pop()||{page:0,tab:0,scroll:0};
  state.page=previous.page;state.tab=previous.tab;state.calculatorId=previous.calculatorId??MAIN_CALCULATOR;state.categoryManagementId=previous.parentId??null;
  requestAnimationFrame(()=>scrollElement.scrollTop=previous.scroll);
  return true;
}

import {state} from "../state.js";
import {addCategory,previewModel,renameCategory} from "../preview-model.js";
import {tr,escapeValue} from "../render-helpers.js";

export function beginCategoryCreation(){
  state.categoryEditId=null;
  state.categoryDraft="";
  state.categoryParentId=state.overlay==="category-picker"?null:state.page===4?state.categoryManagementId:null;
  state.categoryReturnOverlay=["expense","expense-details","category-picker"].includes(state.overlay)?state.overlay:null;
  state.overlay="add-category";
}
export function confirmCategoryCreation(){
  const name=state.categoryDraft.trim();
  const created=addCategory(name,state.categoryParentId);
  if(!created)return false;
  if(["expense","expense-details","category-picker"].includes(state.categoryReturnOverlay))state.expenseDraft.categoryId=created.id;
  if(state.categoryReturnOverlay==="category-picker")state.categoryReturnOverlay=state.pickerReturnOverlay;
  state.overlay=state.categoryReturnOverlay;
  state.categoryReturnOverlay=null;
  return true;
}

export function beginCategoryRename(id){
  const category=previewModel.categories.find(item=>item.id===id);
  if(!category)return false;
  state.categoryEditId=id;state.categoryDraft=category.name;
  state.overlay="edit-category";return true;
}
export function confirmCategoryRename(){
  if(!renameCategory(state.categoryEditId,state.categoryDraft))return false;
  state.overlay=null;state.categoryEditId=null;state.categoryDraft="";return true;
}
export function categoryDialog(){
  const edit=state.overlay==="edit-category";
  return `<div class="scrim dialog-wrap" data-action="close-overlay"><div class="overlay-stage"><div class="dialog-panel" data-stop-close role="dialog" aria-modal="true" aria-labelledby="category-dialog-title">
  <h2 id="category-dialog-title">${tr(edit?"rename_category":state.categoryParentId?"add_subcategory":"add_category_action")}</h2>
  <input id="category-name" class="sheet-field" data-category-draft aria-describedby="category-error" placeholder="${tr("category_name")}" aria-label="${tr("category_name")}" value="${escapeValue(state.categoryDraft)}">
  <p id="category-error" class="form-error" role="alert" hidden></p>
  <div class="dialog-actions"><button data-action="close-overlay">${tr("cancel")}</button><button class="primary" data-action="${edit?"confirm-rename-category":"confirm-category"}">${tr(edit?"save_expense_changes":"add_category")}</button></div>
  </div></div></div>`;
}

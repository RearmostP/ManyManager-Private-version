import {previewModel} from "../preview-model.js";
import {icon} from "../icons.js";
import {state} from "../state.js";
import {tr,escapeValue} from "../render-helpers.js";

export function categories(){
  const parent=previewModel.categories.find(item=>item.id===state.categoryManagementId);
  const rows=previewModel.categories.filter(item=>parent?item.parentId===parent.id:!item.parentId).map(category=>
    `<div class="card category-row">${parent?
      `<span class="category-row-name">${escapeValue(category.name)}</span>`:
      `<button class="category-row-name" data-action="open-category" data-id="${category.id}">${escapeValue(category.name)}</button>`}
      <button class="icon-button category-edit" data-action="edit-category" data-id="${category.id}" aria-label="${escapeValue(tr(category.parentId?"rename_subcategory_label":"rename_category_label",category.name))}">${icon("pencil")}</button></div>`).join("");
  return `<section class="categories-page fade-in"><button class="chip" data-action="back">${tr("back")}</button>
  <h1>${escapeValue(parent?.name??tr("expense_categories"))}</h1>
  ${rows||`<p class="category-empty">${tr(parent?"no_subcategories":"no_categories")}</p>`}
  <button class="chip secondary-action category-add" data-action="add-category">${tr(parent?"add_subcategory":"add_category_action")}</button></section>`;
}

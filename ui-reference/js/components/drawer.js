import {state} from "../state.js";
import {icon} from "../icons.js";
import {tr} from "../render-helpers.js";

export function drawer(){
 return `<div class="scrim" data-action="close-overlay"><div class="overlay-stage"><nav class="drawer ${state.language==="he"?"from-right":"from-left"}" data-stop-close><div class="drawer-head"><h2>${tr("menu_title")}</h2><button class="icon-button" data-action="close-overlay" aria-label="${tr("close")}">${icon("close")}</button></div>
 ${[["home_screen",0],["expense_summary",1],["budget_management",3],["expense_categories",4],["settings",2],["second_calculator_management",5]].map(([key,i],n)=>`<button class="drawer-nav" data-action="navigate" data-index="${i}">${tr(key)}</button>${n<5?'<div class="divider"></div>':""}`).join("")}</nav></div></div>`;
}

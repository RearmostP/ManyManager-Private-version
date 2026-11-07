import {state} from "./state.js";
import {t} from "./i18n.js";
import {icon} from "./icons.js";
import {periodFor,lastDate} from "./budget-period.js";

export const tr=(key,...args)=>t(state.language,key,...args);
export const calculatorName=(id=state.calculatorId)=>tr(id==="second-calculator"?"second_calculator":"main_calculator");
export const money=(cents=0)=>tr("ils_amount",new Intl.NumberFormat(
  state.language==="he"?"he-IL":state.language==="ru"?"ru-RU":"en-US",
  {minimumFractionDigits:0,maximumFractionDigits:2}
).format(cents/100));
export const escapeValue=value=>String(value).replaceAll("&","&amp;").replaceAll('"',"&quot;").replaceAll("<","&lt;");

export function periodLabel(lang,date){
  const {start,endExclusive}=periodFor(date);
  const end=lastDate({start,endExclusive});
  const locale=lang==="he"?"he-IL":lang==="ru"?"ru-RU":"en-US";
  if(start.getMonth()===end.getMonth()&&start.getFullYear()===end.getFullYear()){
    return new Intl.DateTimeFormat(locale,{month:"long",year:"numeric"}).format(start);
  }
  const fmtShort=new Intl.DateTimeFormat(locale,{day:"numeric",month:"short"});
  const fmtLong=new Intl.DateTimeFormat(locale,{day:"numeric",month:"short",year:"numeric"});
  return `${(start.getFullYear()===end.getFullYear()?fmtShort:fmtLong).format(start)} – ${fmtLong.format(end)}`;
}

export function periodDescription(lang,date){
  const period=periodFor(date);
  const locale=lang==="he"?"he-IL":lang==="ru"?"ru-RU":"en-US";
  const format=new Intl.DateTimeFormat(locale,{dateStyle:"long"});
  return t(lang,"budget_period_description",format.format(period.start),format.format(lastDate(period)));
}

export const monthControls=(budget=false)=>`<div class="month-row ${budget?"":"summary-month"}">
<button class="icon-button" data-action="${budget?"budget-prev":"none"}" aria-label="${tr("previous_month")}" ${budget?"":"disabled style='opacity:.45'"}>${icon("right")}</button>
<span class="month-label" aria-label="${escapeValue(periodDescription(state.language,budget?state.budgetMonth:state.currentDate))}">${periodLabel(state.language,budget?state.budgetMonth:state.currentDate)}</span>
<button class="icon-button" data-action="${budget?"budget-next":"none"}" aria-label="${tr("next_month")}" ${budget?"":"disabled style='opacity:.45'"}>${icon("left")}</button></div>`;

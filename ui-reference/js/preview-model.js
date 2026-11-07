// Isolated in-memory companion. All preview data resets on reload.
export const previewModel={
  categories:[],
  sources:[],
  budgets:new Map(),
  expenses:[],
  nextExpenseId:1,
  nextCategoryId:1,
  nextSourceId:1
};

import {periodKey,parseCivilDate} from "./budget-period.js";

const observers=new Set();
export function observePreview(observer){observers.add(observer);return ()=>observers.delete(observer)}
function changed(){for(const observer of observers)queueMicrotask(observer)}

export function totalFor(date){return previewModel.budgets.get(periodKey(date))??0}
export function setBudget(date,amount){
  if(!Number.isSafeInteger(amount)||amount<0)return false;
  previewModel.budgets.set(periodKey(date),amount);changed();return true;
}

export function parseAmount(text){
  const value=text.trim().replace(",",".");
  if(!/^(?:\d+(?:\.\d{1,2})?|\.\d{1,2})$/.test(value))return null;
  const [whole,fraction=""]=value.split(".");
  const cents=BigInt(whole||"0")*100n+BigInt(fraction.padEnd(2,"0"));
  return cents<=BigInt(Number.MAX_SAFE_INTEGER)?Number(cents):null;
}

function safe(value){
  if(value>BigInt(Number.MAX_SAFE_INTEGER)||value<BigInt(Number.MIN_SAFE_INTEGER))throw new RangeError("overflow");
  return Number(value);
}
export function snapshotFor(date,expenses=previewModel.expenses){
  const key=periodKey(date);
  const selected=expenses.filter(item=>periodKey(parseCivilDate(item.date))===key)
    .sort((a,b)=>b.date.localeCompare(a.date)||a.id.localeCompare(b.id));
  const spent=safe(selected.reduce((sum,item)=>sum+BigInt(item.amountMinor),0n));
  const allocated=totalFor(date);
  const sources=previewModel.sources.map(source=>{
    const sourceSpent=safe(selected.filter(item=>item.sourceId===source.id).reduce((sum,item)=>sum+BigInt(item.amountMinor),0n));
    return {source,spent:sourceSpent,expenseCount:selected.filter(item=>item.sourceId===source.id).length};
  });
  const bySpending=(a,b)=>a.spent===b.spent?(a.id===b.id?0:a.id===null?-1:b.id===null?1:a.id<b.id?-1:1):a.spent>b.spent?-1:1;
  const categoryBreakdown=[...new Set(selected.map(item=>mainCategoryId(item.categoryId)))].map(id=>{
    const rows=selected.filter(item=>mainCategoryId(item.categoryId)===id);
    return {id,name:previewModel.categories.find(item=>item.id===id)?.name??null,
      spent:safe(rows.reduce((sum,item)=>sum+BigInt(item.amountMinor),0n)),expenseCount:rows.length};
  }).sort(bySpending);
  const sourceBreakdown=sources.filter(item=>item.expenseCount>0).map(item=>({
    id:item.source.id,name:item.source.name,spent:item.spent,expenseCount:item.expenseCount
  })).sort(bySpending);
  return {expenses:selected,spent,allocated,remaining:safe(BigInt(allocated)-BigInt(spent)),sources,categoryBreakdown,sourceBreakdown};
}
export function createExpense(input){
  const date=parseCivilDate(input.date);
  if(!Number.isSafeInteger(input.amountMinor)||input.amountMinor<=0||!date||
    !previewModel.sources.some(item=>item.id===input.sourceId)||
    !isValidCategory(input.categoryId))return null;
  const expense={id:`expense-${previewModel.nextExpenseId}`,amountMinor:input.amountMinor,
    date:input.date,sourceId:input.sourceId,categoryId:input.categoryId??null,note:input.note||null};
  try{snapshotFor(date,[...previewModel.expenses,expense])}catch{return null}
  previewModel.expenses.push(expense);previewModel.nextExpenseId++;changed();
  return expense;
}
export function updateExpense(id,input){
  const original=previewModel.expenses.find(item=>item.id===id);
  const date=parseCivilDate(input.date);
  if(!original||!date||!Number.isSafeInteger(input.amountMinor)||input.amountMinor<=0||
    !previewModel.sources.some(item=>item.id===input.sourceId)||
    !isValidCategory(input.categoryId))return false;
  const updated={id:original.id,amountMinor:input.amountMinor,date:input.date,
    sourceId:input.sourceId,categoryId:input.categoryId??null,note:input.note||null};
  const candidate=previewModel.expenses.map(item=>item.id===id?updated:item);
  try{
    snapshotFor(parseCivilDate(original.date),candidate);
    snapshotFor(date,candidate);
  }catch{return false}
  Object.assign(original,updated);changed();
  return true;
}
export function deleteExpense(id){
  const original=previewModel.expenses.find(item=>item.id===id);
  if(!original)return false;
  const candidate=previewModel.expenses.filter(item=>item.id!==id);
  try{snapshotFor(parseCivilDate(original.date),candidate)}catch{return false}
  previewModel.expenses=candidate;changed();
  return true;
}

export function isValidCategory(id){
  const category=previewModel.categories.find(item=>item.id===id);
  return Boolean(category&&(!category.parentId||previewModel.categories.some(parent=>parent.id===category.parentId&&!parent.parentId)));
}
export function mainCategoryId(id){return previewModel.categories.find(item=>item.id===id)?.parentId??id}
export function categoryPath(id){
  const category=previewModel.categories.find(item=>item.id===id);
  const parent=previewModel.categories.find(item=>item.id===category?.parentId);
  return category?(parent?`${parent.name} › ${category.name}`:category.name):null;
}
export function addCategory(name,parentId=null){
  const clean=name.trim();
  if(!clean||(parentId!==null&&!previewModel.categories.some(item=>item.id===parentId&&!item.parentId)))return null;
  const category={id:`category-${previewModel.nextCategoryId++}`,name:clean,parentId};
  previewModel.categories.push(category);changed();
  return category;
}

export function renameCategory(id,name){
  const category=previewModel.categories.find(item=>item.id===id),clean=name.trim();
  if(!category||!clean)return false;
  category.name=clean;changed();return true;
}

export function addSource(name){
  const clean=name.trim();if(!clean)return null;
  const source={id:`source-${previewModel.nextSourceId++}`,name:clean};
  previewModel.sources.push(source);changed();return source;
}
export function updateSource(sourceId,name){
  const source=previewModel.sources.find(item=>item.id===sourceId),clean=name.trim();
  if(!source||!clean)return false;
  source.name=clean;changed();return true;
}

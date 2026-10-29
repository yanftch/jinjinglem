"use strict";

const $ = id => document.getElementById(id);
const labels = {NEW:"新增",UPDATE:"更新",LINK:"关联现有探头",NEEDS_REVIEW:"待人工核对",INVALID:"数据无效",UNCHANGED:"无变化",SKIP_DELETED:"人工删除，跳过",PENDING:"未审核",CONFIRMED:"已确认",REJECTED:"已排除",APPROVED:"已入库",AWAITING_REVIEW:"等待人工审核",IMPORTING:"正在入库",SUCCEEDED:"已完成",PARTIAL_SUCCESS:"部分完成",FAILED:"失败",PREPARING_REVIEW:"正在生成预览"};
const types = {SIX_RING_IN:"六环内",SIX_RING_OUT:"六环外",TWO_RING_IN:"二环内",DISCARD:"已废弃"};
const state = {runId:null,run:null,offset:0,more:false,summary:null,mailConfigured:false};
const text = value => value == null || value === "" ? "—" : String(value);
const label = value => labels[value] || text(value);
const type = value => types[value] || "—";
function el(tag, className, value) {const node=document.createElement(tag);if(className)node.className=className;if(value!==undefined)node.textContent=text(value);return node;}
function notice(message, error=false) {const node=$("notice");node.textContent=message;node.hidden=!message;node.classList.toggle("error",error);}
async function api(path, options={}) {const response=await fetch(path,options);const data=await response.json().catch(()=>null);if(!response.ok)throw new Error(data?.error||`请求失败（${response.status}）`);return data;}
function count(name) {return (state.summary?.counts||[]).filter(item=>item.predicted_status===name).reduce((sum,item)=>sum+Number(item.total),0);}

async function loadRuns() {
  const rows=await api("/api/v1/admin/sync/runs?limit=100");
  const select=$("runSelect");select.replaceChildren();
  for(const run of rows) {const option=el("option","",`第 ${run.id} 次 · ${label(run.status)} · ${new Date(run.created_at).toLocaleString("zh-CN")}`);option.value=String(run.id);select.append(option);}
  if(!rows.length){state.runId=null;$("runMessage").textContent="暂无抓取批次。";return;}
  const requested=Number(new URLSearchParams(location.search).get("runId"));
  if(state.runId==null)state.runId=rows.some(row=>row.id===requested)?requested:rows[0].id;
  select.value=String(state.runId);
  await refresh();
}

async function refresh() {
  if(state.runId==null)return;
  const [run,summary,config]=await Promise.all([
    api(`/api/v1/admin/sync/runs/${state.runId}`),
    api(`/api/v1/admin/sync/runs/${state.runId}/review-summary`),
    api("/api/v1/admin/sync/config")
  ]);
  state.run=run;state.summary=summary;state.mailConfigured=config.mailConfigured;
  $("runMessage").textContent=`第 ${run.id} 次 · ${label(run.status)}；抓取 ${Number(run.fetch_done||0)} / ${Number(run.fetch_total||0)} 条，预览 ${Number(run.review_done||0)} 条。${run.phase_message||""}`;
  const cards=$("summaryCards");cards.replaceChildren();
  for(const [name,value] of [["新增",count("NEW")],["更新",count("UPDATE")],["关联",count("LINK")],["待人工核对",count("NEEDS_REVIEW")],["数据无效",count("INVALID")],["无变化",count("UNCHANGED")],["已排除",(summary.counts||[]).filter(item=>item.decision==="REJECTED").reduce((sum,item)=>sum+Number(item.total),0)],["待处理疑点",summary.unresolved]]) {
    const card=el("div","review-card");card.append(el("span","",name),el("strong","",Number(value).toLocaleString("zh-CN")));cards.append(card);
  }
  const mailLabel={SENT:"摘要邮件已发送",FAILED:"摘要邮件发送失败",UNCONFIGURED:"摘要邮件未配置",PENDING:"摘要邮件等待发送",SENDING:"摘要邮件发送中",NOT_APPLICABLE:"旧任务无需发送"};
  $("mailMessage").textContent=`邮件：${mailLabel[run.mail_status]||"—"}${run.mail_error?`；${run.mail_error}`:""}`;
  $("retryMail").hidden=!state.mailConfigured||!["FAILED","UNCONFIGURED"].includes(run.mail_status);
  $("downloadFile").hidden=!run.archive_path;
  $("downloadFile").href=`/api/v1/admin/sync/runs/${run.id}/file`;
  $("approveBatch").disabled=run.status!=="AWAITING_REVIEW"||summary.unresolved>0;
  $("approveBatch").title=summary.unresolved>0?`请先处理 ${summary.unresolved} 条疑点记录`:"";
  await loadRows();
}

async function loadRows() {
  if(state.runId==null)return;
  const params=new URLSearchParams({offset:String(state.offset),limit:"51"});
  if($("predictionFilter").value)params.set("prediction",$("predictionFilter").value);
  if($("decisionFilter").value)params.set("decision",$("decisionFilter").value);
  const rows=await api(`/api/v1/admin/sync/runs/${state.runId}/reviews?${params}`);
  state.more=rows.length>50;
  const container=$("reviewRows");container.replaceChildren();
  if(!rows.length)container.append(el("p","empty","当前筛选条件下没有记录。"));
  else {
    const table=el("table","data-table"),head=el("thead"),tr=el("tr"),body=el("tbody");
    ["序号","探头名称","预计变化","类型变化","新增评论 / 图片","说明","审核决定","操作"].forEach(name=>tr.append(el("th","",name)));
    head.append(tr);table.append(head,body);
    for(const row of rows) {
      const line=el("tr");
      [row.row_number,row.name,label(row.predicted_status),`${type(row.current_type)} → ${type(row.proposed_type)}`,`${row.new_comment_count} / ${row.new_image_count}`,row.review_note,label(row.decision)].forEach(value=>line.append(el("td","",value)));
      const actions=el("td");
      actions.append(action("查看差异",()=>showRow(row.row_number)));
      if(state.run?.status==="AWAITING_REVIEW") {
        if(row.decision==="PENDING") {
          if(row.predicted_status==="NEEDS_REVIEW")actions.append(action("确认此条",()=>decide(row.row_number,"confirm")));
          actions.append(action("排除此条",()=>reject(row.row_number)));
        } else if(["CONFIRMED","REJECTED"].includes(row.decision))actions.append(action("撤销决定",()=>decide(row.row_number,"reset")));
      }
      line.append(actions);body.append(line);
    }
    container.append(table);
  }
  $("previousPage").disabled=state.offset===0;$("nextPage").disabled=!state.more;
  $("pageNumber").textContent=`第 ${Math.floor(state.offset/50)+1} 页`;
}

function action(name,handler) {const button=el("button","",name);button.type="button";button.addEventListener("click",()=>Promise.resolve(handler()).catch(error=>notice(error.message,true)));return button;}
async function showRow(rowNumber) {
  const row=await api(`/api/v1/admin/sync/runs/${state.runId}/reviews/${rowNumber}`);
  $("detailTitle").textContent=`第 ${row.row_number} 条 · ${label(row.predicted_status)} · ${row.name||"未命名"}`;
  $("currentData").textContent=row.current_snapshot?JSON.stringify(JSON.parse(row.current_snapshot),null,2):"暂无对应探头";
  $("sourceData").textContent=JSON.stringify(JSON.parse(row.source_payload),null,2);
  $("detailDialog").showModal();
}
async function decide(rowNumber,kind,reason=null) {
  await api(`/api/v1/admin/sync/runs/${state.runId}/reviews/${rowNumber}/${kind}`,{method:"POST",headers:{"Content-Type":"application/json"},body:kind==="reject"?JSON.stringify({reason}):undefined});
  notice(kind==="reject"?"已排除该条记录。":kind==="confirm"?"已确认该条疑点记录。":"已撤销审核决定。");
  await refresh();
}
async function reject(rowNumber) {const reason=prompt("排除原因（可留空）","");if(reason===null)return;await decide(rowNumber,"reject",reason.trim());}

$("runSelect").addEventListener("change",()=>{state.runId=Number($("runSelect").value);state.offset=0;history.replaceState(null,"",`/review.html?runId=${state.runId}`);refresh().catch(error=>notice(error.message,true));});
$("predictionFilter").addEventListener("change",()=>{state.offset=0;loadRows().catch(error=>notice(error.message,true));});
$("decisionFilter").addEventListener("change",()=>{state.offset=0;loadRows().catch(error=>notice(error.message,true));});
$("previousPage").addEventListener("click",()=>{state.offset=Math.max(0,state.offset-50);loadRows().catch(error=>notice(error.message,true));});
$("nextPage").addEventListener("click",()=>{if(state.more){state.offset+=50;loadRows().catch(error=>notice(error.message,true));}});
$("refreshButton").addEventListener("click",()=>refresh().catch(error=>notice(error.message,true)));
$("closeDialog").addEventListener("click",()=>$("detailDialog").close());
$("approveBatch").addEventListener("click",async()=>{
  if(!confirm("确认将本批次未排除的探头数据写入统一数据库？"))return;
  $("approveBatch").disabled=true;
  try {await api(`/api/v1/admin/sync/runs/${state.runId}/approve`,{method:"POST"});notice("已确认，正在后台清洗并更新数据库。");await refresh();}
  catch(error){notice(error.message,true);await refresh();}
});
$("retryMail").addEventListener("click",async()=>{
  $("retryMail").disabled=true;
  try {const result=await api(`/api/v1/admin/sync/runs/${state.runId}/mail/retry`,{method:"POST"});notice(result.mailStatus==="SENT"?"摘要邮件已重新发送。":"邮件仍未发送，请查看下方错误。",result.mailStatus!=="SENT");await refresh();}
  catch(error){notice(error.message,true);await refresh();}
  finally{$("retryMail").disabled=false;}
});
loadRuns().catch(error=>notice(error.message,true));
setInterval(()=>{if(state.runId!=null&&["PREPARING_REVIEW","IMPORTING"].includes(state.run?.status))refresh().catch(()=>{});},5000);

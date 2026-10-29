"use strict";

const $ = id => document.getElementById(id);
const state = { runOffset: 0, runId: null, run: null, tab: "records", dataOffset: 0, runMore: false, dataMore: false, request: 0 };
const labels = {
  QUEUED: "等待执行", FETCHING: "正在抓取", ARCHIVING: "正在归档", IMPORTING: "正在入库",
  SUCCEEDED: "已完成", PARTIAL_SUCCESS: "部分完成", FAILED: "失败", INTERRUPTED: "已中断",
  INSERTED: "新增", UPDATED: "更新", UNCHANGED: "无变化", MANUAL: "手动", SCHEDULED: "定时",
  INSERT: "新增探头", UPDATE: "更新探头", DELETE: "删除探头", RESTORE: "恢复探头",
  COMMENT_ADD: "新增评论", IMAGE_ADD: "新增图片", SOURCE_LINK: "关联来源",
  COMMENT_MERGE: "合并评论", IMAGE_MERGE: "合并图片",
  MERGE_IN: "合并探头", MERGED_INTO: "并入探头", ARCHIVED: "已归档"
};
const active = new Set(["QUEUED", "FETCHING", "ARCHIVING", "IMPORTING"]);

function text(value) { return value == null || value === "" ? "—" : String(value); }
function label(value) { return labels[value] || text(value); }
function time(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"; }
function number(value) { return new Intl.NumberFormat("zh-CN").format(Number(value || 0)); }
function el(tag, className, content) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (content !== undefined) item.textContent = text(content);
  return item;
}
function notice(message, error = false) {
  const box = $("notice"); box.textContent = message; box.hidden = !message; box.classList.toggle("error", error);
}
async function api(path, options = {}) {
  const response = await fetch(path, options);
  const data = await response.json().catch(() => null);
  if (!response.ok) throw new Error(data?.error || `请求失败：${response.status}`);
  return data;
}

async function loadConfig() {
  const cfg = await api("/api/v1/admin/sync/config");
  $("credential").textContent = cfg.configured ? "已配置" : "未配置";
  $("credentialHint").textContent = cfg.configured ? "可执行抓取" : "请设置躲猫猫令牌后重启服务";
  $("schedule").textContent = cfg.scheduledEnabled ? "每天 03:00" : "未启用";
  $("nextRun").textContent = cfg.nextRunAt ? `下次：${time(cfg.nextRunAt)}` : "当前环境不自动执行，可手动抓取";
  $("startButton").disabled = !cfg.configured;
}

function pill(status) {
  const kind = active.has(status) ? "running" : status === "FAILED" || status === "INTERRUPTED" ? "bad" : status === "PARTIAL_SUCCESS" ? "warn" : "";
  return el("span", `pill ${kind}`, label(status));
}

async function loadRuns() {
  const rows = await api(`/api/v1/admin/sync/runs?offset=${state.runOffset}&limit=21`);
  state.runMore = rows.length > 20;
  const visible = rows.slice(0, 20);
  const list = $("runList"); list.replaceChildren();
  if (!visible.length) list.append(el("p", "empty", "还没有抓取记录。"));
  for (const item of visible) {
    const button = el("button", `run-item${state.runId === item.id ? " selected" : ""}`);
    button.type = "button";
    const title = el("strong", "", `第 ${item.id} 次 · ${label(item.trigger_type)}`);
    const meta = el("div", "meta"); meta.append(pill(item.status), el("small", "", time(item.created_at)));
    button.append(title, meta, el("small", "", item.phase_message || "等待执行"));
    button.addEventListener("click", () => selectRun(item.id));
    list.append(button);
  }
  $("runPrev").disabled = state.runOffset === 0;
  $("runNext").disabled = !state.runMore;
  $("runPage").textContent = `第 ${Math.floor(state.runOffset / 20) + 1} 页`;
  if (state.runOffset === 0 && visible.length) {
    $("latestStatus").textContent = label(visible[0].status);
    $("latestTime").textContent = `开始于 ${time(visible[0].created_at)}`;
    if (state.runId == null) await selectRun(visible[0].id);
  }
}

async function selectRun(id) {
  state.runId = id; state.dataOffset = 0;
  await loadRunDetail();
  await loadData();
  for (const button of $("runList").querySelectorAll(".run-item")) {
    button.classList.toggle("selected", button.textContent.startsWith(`第 ${id} 次`));
  }
}

async function loadRunDetail() {
  if (state.runId == null) return;
  const run = await api(`/api/v1/admin/sync/runs/${state.runId}`);
  if (state.runId !== run.id) return;
  state.run = run;
  $("runTitle").textContent = `第 ${run.id} 次 · ${label(run.status)}`;
  const download = $("download");
  download.hidden = run.archive_path == null;
  if (!download.hidden) download.href = `/api/v1/admin/sync/runs/${run.id}/file`;
  const view = $("runDetail"); view.replaceChildren();
  const stage = run.status === "IMPORTING" ? "导入进度" : "采集进度";
  const completed = run.status === "IMPORTING" ? run.imported_done : run.fetch_done;
  const total = run.status === "IMPORTING" ? run.total_count : run.fetch_total;
  const percent = total ? Math.min(100, Math.round(completed / total * 100)) : 0;
  const progressLabel = el("div", "", `${stage}：${number(completed)} / ${number(total)}（${percent}%）`);
  const progress = el("div", "progress"); const bar = el("i"); bar.style.width = `${percent}%`; progress.append(bar);
  view.append(progressLabel, progress, el("p", "", run.phase_message || "—"));
  const stats = el("div", "stats");
  for (const [name, count] of [["抓取总数", run.fetch_total], ["详情失败", run.detail_failures],
    ["新增", run.inserted_count], ["更新", run.updated_count], ["无变化", run.unchanged_count],
    ["入库失败", run.failed_count], ["导入进度", run.imported_done]]) {
    const item = el("div", "stat"); item.append(el("span", "", name), el("strong", "", number(count))); stats.append(item);
  }
  view.append(stats);
  const details = el("dl", "detail-grid");
  for (const [name, value] of [["触发方式", label(run.trigger_type)], ["创建时间", time(run.created_at)],
    ["开始时间", time(run.started_at)], ["完成时间", time(run.finished_at)],
    ["归档文件", run.archive_path], ["文件 SHA-256", run.archive_sha256],
    ["导入批次", run.import_batch_id], ["导入状态", label(run.import_status)],
    ["任务错误", run.error_message], ["导入错误", run.import_error]]) {
    details.append(el("dt", "", name), el("dd", "", value));
  }
  view.append(details);
}

function table(headers) {
  const table = el("table", "data-table"), head = el("thead"), tr = el("tr"), body = el("tbody");
  headers.forEach(name => tr.append(el("th", "", name)));
  head.append(tr); table.append(head, body); return [table, body];
}
function cell(row, value) { row.append(el("td", "", value)); }
function pretty(value) {
  if (!value) return "—";
  try { return JSON.stringify(JSON.parse(value), null, 2); } catch { return value; }
}
function auditDetails(row, item) {
  const td = el("td"), details = el("details"), summary = el("summary", "", "查看前后内容");
  details.append(summary, el("pre", "", `变更前：\n${pretty(item.before_value)}\n\n变更后：\n${pretty(item.after_value)}`));
  td.append(details); row.append(td);
}

async function loadData() {
  if (state.tab !== "audit" || !$("allAudit").checked) {
    if (state.runId == null) return;
  }
  const request = ++state.request;
  let path;
  if (state.tab === "records") {
    const params = new URLSearchParams({ offset: state.dataOffset, limit: 51,
      reviewOnly: $("reviewOnly").checked });
    if ($("recordStatus").value) params.set("status", $("recordStatus").value);
    path = `/api/v1/admin/sync/runs/${state.runId}/records?${params}`;
  } else if (state.tab === "events") {
    path = `/api/v1/admin/sync/runs/${state.runId}/events?offset=${state.dataOffset}&limit=51`;
  } else {
    path = $("allAudit").checked ? `/api/v1/admin/sync/audit?offset=${state.dataOffset}&limit=51`
      : `/api/v1/admin/sync/runs/${state.runId}/audit?offset=${state.dataOffset}&limit=51`;
  }
  const rows = await api(path);
  if (request !== state.request) return;
  state.dataMore = rows.length > 50;
  const visible = rows.slice(0, 50), container = $("dataRows"); container.replaceChildren();
  if (!visible.length) container.append(el("p", "empty", "暂无记录。"));
  else if (state.tab === "records") {
    const [t, body] = table(["序号", "来源编号", "统一探头", "结果", "说明", "原始数据"]);
    for (const item of visible) {
      const tr = el("tr");
      cell(tr, item.row_number); cell(tr, item.source_record_id); cell(tr, item.camera_id);
      const td = el("td"); td.append(pill(item.status)); tr.append(td);
      cell(tr, item.error_message || item.review_note);
      const action = el("td"), button = el("button", "", "查看");
      button.type = "button"; button.addEventListener("click", () => showRaw(item.source_record_id));
      action.append(button); tr.append(action); body.append(tr);
    }
    container.append(t);
  } else if (state.tab === "audit") {
    const [t, body] = table(["时间", "统一探头", "操作者", "操作", "变更内容"]);
    for (const item of visible) {
      const tr = el("tr"); cell(tr, time(item.created_at)); cell(tr, item.camera_id);
      cell(tr, item.actor === "admin" ? "管理员" : item.sync_run_id || state.tab === "audit" && !$("allAudit").checked ? "抓取同步" : "文件导入"); cell(tr, label(item.action));
      auditDetails(tr, item); body.append(tr);
    }
    container.append(t);
  } else {
    const [t, body] = table(["时间", "事件", "说明"]);
    for (const item of visible) {
      const tr = el("tr"); cell(tr, time(item.created_at)); cell(tr, label(item.event_type)); cell(tr, item.message); body.append(tr);
    }
    container.append(t);
  }
  $("dataPrev").disabled = state.dataOffset === 0;
  $("dataNext").disabled = !state.dataMore;
  $("dataPage").textContent = `第 ${Math.floor(state.dataOffset / 50) + 1} 页`;
}

async function showRaw(sourceId) {
  try {
    const data = await api(`/api/v1/admin/sync/runs/${state.runId}/records/${encodeURIComponent(sourceId)}`);
    $("rawTitle").textContent = `来源探头 ${sourceId} · 抓取原文`;
    $("rawContent").textContent = JSON.stringify(data, null, 2);
    $("rawDialog").showModal();
  } catch (error) { notice(error.message, true); }
}

async function refresh() {
  try {
    await loadRuns();
    if (state.runId != null) {
      await loadRunDetail();
      if (state.run && active.has(state.run.status)) await loadData();
    }
  } catch (error) { notice(error.message, true); }
}

$("startButton").addEventListener("click", async () => {
  $("startButton").disabled = true;
  try {
    const result = await api("/api/v1/admin/sync/runs", { method: "POST" });
    notice(`任务 ${result.runId} 已创建，正在后台执行。`);
    state.runOffset = 0; await loadRuns(); await selectRun(result.runId);
  } catch (error) { notice(error.message, true); }
  finally { await loadConfig().catch(() => {}); }
});
$("refreshButton").addEventListener("click", refresh);
$("runPrev").addEventListener("click", async () => { state.runOffset = Math.max(0, state.runOffset - 20); await loadRuns(); });
$("runNext").addEventListener("click", async () => { if (state.runMore) { state.runOffset += 20; await loadRuns(); } });
$("dataPrev").addEventListener("click", async () => { state.dataOffset = Math.max(0, state.dataOffset - 50); await loadData(); });
$("dataNext").addEventListener("click", async () => { if (state.dataMore) { state.dataOffset += 50; await loadData(); } });
$("recordStatus").addEventListener("change", () => { state.dataOffset = 0; loadData().catch(e => notice(e.message, true)); });
$("reviewOnly").addEventListener("change", () => { state.dataOffset = 0; loadData().catch(e => notice(e.message, true)); });
$("allAudit").addEventListener("change", () => { state.dataOffset = 0; loadData().catch(e => notice(e.message, true)); });
document.querySelectorAll("[data-tab]").forEach(button => button.addEventListener("click", () => {
  state.tab = button.dataset.tab; state.dataOffset = 0;
  document.querySelectorAll("[data-tab]").forEach(tab => tab.classList.toggle("active", tab === button));
  $("recordControls").hidden = state.tab !== "records";
  $("auditControls").hidden = state.tab !== "audit";
  loadData().catch(e => notice(e.message, true));
}));
$("closeDialog").addEventListener("click", () => $("rawDialog").close());
Promise.all([loadConfig(), loadRuns()]).catch(error => notice(error.message, true));
setInterval(refresh, 5000);

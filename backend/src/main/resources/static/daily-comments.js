const $ = id => document.getElementById(id);
const state = { offset: 0, pageSize: 50, startDate: "", endDate: "", busy: false };

function beijingDate(offsetDays = 0) {
  const now = new Date(Date.now() + 8 * 60 * 60 * 1000);
  return new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate() + offsetDays))
    .toISOString().slice(0, 10);
}

async function api(path, options = {}) {
  const response = await adminFetch(path, options);
  const value = await response.json().catch(() => null);
  if (!response.ok) throw new Error(value?.error || `请求失败（${response.status}）`);
  return value;
}

function error(message) {
  $("notice").hidden = !message;
  $("notice").textContent = message || "";
}

function renderRows(rows) {
  const body = $("commentRows");
  body.replaceChildren();
  if (!rows.length) {
    const row = document.createElement("tr");
    const cell = document.createElement("td");
    cell.colSpan = 3;
    cell.className = "empty";
    cell.textContent = "这一天没有新增用户评论。";
    row.append(cell);
    body.append(row);
    return;
  }
  for (const item of rows) {
    const row = document.createElement("tr");
    for (const value of [item.camera_name, item.content, item.comment_time]) {
      const cell = document.createElement("td");
      cell.textContent = value || "—";
      row.append(cell);
    }
    body.append(row);
  }
}

async function load() {
  try {
    error("");
    const startDate = $("startDate").value;
    const endDate = $("endDate").value;
    if (!startDate || !endDate) throw new Error("请选择开始日期和结束日期");
    if (startDate > endDate) throw new Error("开始日期不能晚于结束日期");
    state.startDate = startDate;
    state.endDate = endDate;
    const query = `startDate=${encodeURIComponent(startDate)}&endDate=${encodeURIComponent(endDate)}&`;
    const [summary, rows] = await Promise.all([
      api(`/api/v1/admin/comments/daily/summary/range?${query}`),
      api(`/api/v1/admin/comments/daily?${query}offset=${state.offset}&limit=${state.pageSize}`)
    ]);
    let mailSummary = null;
    if (startDate === endDate) {
      mailSummary = await api(`/api/v1/admin/comments/daily/summary?date=${encodeURIComponent(startDate)}`);
    }
    const mail = mailSummary
      ? ({ SENT: "日报已发送", FAILED: "日报发送失败", SENDING: "日报发送中", PENDING: "日报尚未发送" }[mailSummary.mailStatus] || "")
      : "";
    const mailDetail = mailSummary
      ? ` · ${mail}${mailSummary.mailError ? " · " + mailSummary.mailError : ""} · 收件邮箱：${mailSummary.recipient}`
      : "";
    $("summary").textContent = `${summary.startDate} 至 ${summary.endDate} 新增 ${summary.count} 条用户评论${mailDetail}`;
    $("retryMail").hidden = !mailSummary || mailSummary.mailStatus !== "FAILED" || startDate >= beijingDate();
    renderRows(rows);
    $("previous").disabled = state.offset === 0;
    $("next").disabled = state.offset + rows.length >= summary.count;
    $("pageNumber").textContent = `第 ${Math.floor(state.offset / state.pageSize) + 1} 页`;
  } catch (e) {
    $("retryMail").hidden = true;
    error(e.message);
  }
}

$("startDate").value = beijingDate(-6);
$("endDate").value = beijingDate();
$("startDate").addEventListener("change", () => { state.offset = 0; load(); });
$("endDate").addEventListener("change", () => { state.offset = 0; load(); });
$("refresh").addEventListener("click", () => { state.offset = 0; load(); });
$("previous").addEventListener("click", () => { state.offset = Math.max(0, state.offset - state.pageSize); load(); });
$("next").addEventListener("click", () => { state.offset += state.pageSize; load(); });
$("retryMail").addEventListener("click", async () => {
  if (state.busy) return;
  state.busy = true;
  $("retryMail").disabled = true;
  try {
    await api(`/api/v1/admin/comments/daily/retry?date=${encodeURIComponent(state.startDate)}`, { method: "POST" });
    await load();
  } catch (e) { error(e.message); }
  finally { state.busy = false; $("retryMail").disabled = false; }
});
load();

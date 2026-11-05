const $ = id => document.getElementById(id);
const state = { offset: 0, pageSize: 50, date: "", busy: false };

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
    const date = $("reportDate").value;
    const query = date ? `date=${encodeURIComponent(date)}&` : "";
    const [summary, rows] = await Promise.all([
      api(`/api/v1/admin/comments/daily/summary?${query}`),
      api(`/api/v1/admin/comments/daily?${query}offset=${state.offset}&limit=${state.pageSize}`)
    ]);
    state.date = summary.date;
    $("reportDate").value = summary.date;
    const mail = { SENT: "日报已发送", FAILED: "日报发送失败", SENDING: "日报发送中", PENDING: "日报尚未发送" }[summary.mailStatus] || "";
    $("summary").textContent = `${summary.date} 新增 ${summary.count} 条用户评论 · ${mail}${summary.mailError ? " · " + summary.mailError : ""} · 收件邮箱：${summary.recipient}`;
    $("retryMail").hidden = summary.mailStatus !== "FAILED";
    renderRows(rows);
    $("previous").disabled = state.offset === 0;
    $("next").disabled = state.offset + rows.length >= summary.count;
    $("pageNumber").textContent = `第 ${Math.floor(state.offset / state.pageSize) + 1} 页`;
  } catch (e) { error(e.message); }
}

$("reportDate").addEventListener("change", () => { state.offset = 0; load(); });
$("refresh").addEventListener("click", load);
$("previous").addEventListener("click", () => { state.offset = Math.max(0, state.offset - state.pageSize); load(); });
$("next").addEventListener("click", () => { state.offset += state.pageSize; load(); });
$("retryMail").addEventListener("click", async () => {
  if (state.busy) return;
  state.busy = true;
  $("retryMail").disabled = true;
  try {
    await api(`/api/v1/admin/comments/daily/retry?date=${encodeURIComponent(state.date)}`, { method: "POST" });
    await load();
  } catch (e) { error(e.message); }
  finally { state.busy = false; $("retryMail").disabled = false; }
});
load();

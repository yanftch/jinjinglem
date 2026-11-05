const $ = id => document.getElementById(id);
const state = { offset: 0, pageSize: 18, rows: [], selectedId: null, selected: null, busy: false };
const statusName = { PENDING: "待审核", APPROVED: "已入库", REJECTED: "已驳回" };

async function api(path, options = {}) {
  const response = await adminFetch(path, options);
  const result = await response.json().catch(() => null);
  if (!response.ok) throw new Error(result?.error || `请求失败（${response.status}）`);
  return result;
}

function notice(message, error = false) {
  $("notice").hidden = !message;
  $("notice").className = error ? "error" : "";
  $("notice").textContent = message || "";
}

function dateTime(value) {
  return value ? new Date(value).toLocaleString("zh-CN", { timeZone: "Asia/Shanghai", hour12: false }) : "—";
}

function setBusy(value) {
  state.busy = value;
  const canReview = state.selected?.status === "PENDING" && !value;
  $("approve").disabled = !canReview;
  $("reject").disabled = !canReview;
}

function clearSelection() {
  state.selectedId = null;
  state.selected = null;
  $("cameraForm").reset();
  $("reportId").textContent = "—";
  $("nickname").textContent = "—";
  $("content").textContent = "—";
  $("createdAt").textContent = "—";
  $("reviewResult").textContent = "—";
  $("reportStatus").textContent = "请选择记录";
  $("screenshot").hidden = true;
  $("screenshot").removeAttribute("src");
  $("screenshotHint").hidden = false;
  $("rejectReason").value = "";
  setBusy(false);
}

function listItem(row) {
  const button = document.createElement("button");
  button.type = "button";
  button.className = "report-item" + (row.id === state.selectedId ? " selected" : "");
  const meta = document.createElement("span");
  meta.className = "report-meta";
  const id = document.createElement("span");
  id.textContent = `#${row.id} · ${row.nickname}`;
  const status = document.createElement("span");
  status.textContent = statusName[row.status] || row.status;
  meta.append(id, status);
  const title = document.createElement("strong");
  title.textContent = row.content;
  const time = document.createElement("small");
  time.textContent = dateTime(row.created_at);
  button.append(meta, title, time);
  button.addEventListener("click", () => selectReport(row.id));
  return button;
}

async function loadList() {
  try {
    const status = $("statusFilter").value;
    state.rows = await api(`/api/v1/admin/camera-reports?status=${encodeURIComponent(status)}&offset=${state.offset}&limit=${state.pageSize}`);
    const list = $("reportList");
    list.replaceChildren();
    if (!state.rows.length) {
      clearSelection();
      const empty = document.createElement("p");
      empty.className = "empty";
      empty.textContent = "暂无符合条件的上报记录。";
      list.append(empty);
    } else state.rows.forEach(row => list.append(listItem(row)));
    $("pageNumber").textContent = `第 ${Math.floor(state.offset / state.pageSize) + 1} 页`;
    $("previous").disabled = state.offset === 0;
    $("next").disabled = state.rows.length < state.pageSize;
    if (!state.selectedId && state.rows.length) await selectReport(state.rows[0].id);
  } catch (error) { notice(error.message, true); }
}

async function selectReport(id) {
  if (state.busy) return;
  try {
    const report = await api(`/api/v1/admin/camera-reports/${id}`);
    if (state.selectedId !== id) $("cameraForm").reset();
    state.selectedId = id;
    state.selected = report;
    $("reportId").textContent = String(report.id);
    $("nickname").textContent = report.nickname;
    $("content").textContent = report.content;
    $("createdAt").textContent = dateTime(report.createdAt);
    $("reportStatus").textContent = statusName[report.status] || report.status;
    $("reviewResult").textContent = report.status === "PENDING" ? "尚未审核"
      : `${report.review.reviewedBy || "管理员"} · ${dateTime(report.review.reviewedAt)}${report.review.cameraId ? ` · 探头 #${report.review.cameraId}` : ""}${report.review.reason ? ` · ${report.review.reason}` : ""}`;
    $("screenshot").src = `/api/v1/admin/camera-reports/${id}/screenshot`;
    $("screenshot").hidden = false;
    $("screenshotHint").hidden = true;
    $("rejectReason").value = "";
    setBusy(false);
    document.querySelectorAll(".report-item").forEach(button => button.classList.toggle("selected", button.textContent.startsWith(`#${id} ·`)));
  } catch (error) { notice(error.message, true); }
}

function cameraType(name) {
  const compact = (name || "").replace(/\s/g, "");
  if (compact.includes("已废弃")) return "已废弃";
  if (compact.includes("六环外")) return "六环外";
  if (compact.includes("二环内")) return "二环内";
  return "六环内";
}

async function approve(event) {
  event.preventDefault();
  if (!state.selected || state.selected.status !== "PENDING" || state.busy) return;
  const name = $("cameraName").value.trim();
  const longitude = $("longitude").value;
  const latitude = $("latitude").value;
  if (!name || !longitude || !latitude || !$("coordinateConfirmed").checked) {
    notice("请填写探头名称、经纬度并确认坐标系。", true); return;
  }
  if (!confirm(`确认将上报 #${state.selectedId} 新增为探头“${name}”？`)) return;
  setBusy(true);
  try {
    const result = await api(`/api/v1/admin/camera-reports/${state.selectedId}/approve`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, longitude, latitude, typeCodeRaw: $("typeCodeRaw").value.trim(), coordinateConfirmed: true })
    });
    notice(`已审核入库，探头编号 #${result.cameraId}。`);
    clearSelection();
    await loadList();
  } catch (error) { notice(error.message, true); }
  finally { setBusy(false); }
}

async function reject() {
  if (!state.selected || state.selected.status !== "PENDING" || state.busy) return;
  const reason = $("rejectReason").value.trim();
  if (!reason) { notice("请先填写驳回原因。", true); return; }
  if (!confirm(`确认驳回上报 #${state.selectedId}？`)) return;
  setBusy(true);
  try {
    await api(`/api/v1/admin/camera-reports/${state.selectedId}/reject`, {
      method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ reason })
    });
    notice("上报已驳回并留痕。");
    clearSelection();
    await loadList();
  } catch (error) { notice(error.message, true); }
  finally { setBusy(false); }
}

$("refresh").addEventListener("click", loadList);
$("statusFilter").addEventListener("change", () => { state.offset = 0; clearSelection(); loadList(); });
$("previous").addEventListener("click", () => { state.offset = Math.max(0, state.offset - state.pageSize); clearSelection(); loadList(); });
$("next").addEventListener("click", () => { state.offset += state.pageSize; clearSelection(); loadList(); });
$("cameraName").addEventListener("input", () => { $("cameraType").value = `${cameraType($("cameraName").value)}（按名称自动判定）`; });
$("cameraForm").addEventListener("submit", approve);
$("reject").addEventListener("click", reject);
loadList();

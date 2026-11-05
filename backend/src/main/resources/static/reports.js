const $ = id => document.getElementById(id);
const state = { offset: 0, pageSize: 18, rows: [], selectedId: null, selected: null, busy: false,
  map: null, marker: null, boundaries: null };
const statusName = { PENDING: "待审核", APPROVED: "已入库", REJECTED: "已驳回" };
const typeName = { TWO_RING_IN: "二环内", SIX_RING_IN: "六环内", SIX_RING_OUT: "六环外", DISCARD: "已废弃" };

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
  $("publishScreenshot").disabled = !canReview;
}

function clearSelection() {
  state.selectedId = null;
  state.selected = null;
  $("cameraForm").reset();
  state.marker?.setMap(null);
  state.marker = null;
  $("cameraTypeHint").textContent = "选点后根据二环、六环边界自动推荐；可人工调整。";
  $("cameraTypeHint").classList.remove("warning");
  $("reportId").textContent = "—";
  $("nickname").textContent = "—";
  $("content").textContent = "—";
  $("createdAt").textContent = "—";
  $("reviewResult").textContent = "—";
  $("imagePublication").textContent = "—";
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
    if (state.selectedId !== id) {
      $("cameraForm").reset();
      state.marker?.setMap(null);
      state.marker = null;
      $("cameraTypeHint").textContent = "选点后根据二环、六环边界自动推荐；可人工调整。";
      $("cameraTypeHint").classList.remove("warning");
    }
    state.selectedId = id;
    state.selected = report;
    $("reportId").textContent = String(report.id);
    $("nickname").textContent = report.nickname;
    $("content").textContent = report.content;
    $("createdAt").textContent = dateTime(report.createdAt);
    $("reportStatus").textContent = statusName[report.status] || report.status;
    $("reviewResult").textContent = report.status === "PENDING" ? "尚未审核"
      : `${report.review.reviewedBy || "管理员"} · ${dateTime(report.review.reviewedAt)}${report.review.cameraId ? ` · 探头 #${report.review.cameraId}` : ""}${report.review.reason ? ` · ${report.review.reason}` : ""}`;
    $("imagePublication").textContent = report.status === "PENDING" ? "待决定" : report.publishScreenshot ? "已公开为探头图片" : "未公开";
    $("publishScreenshot").checked = report.status === "APPROVED" && report.publishScreenshot;
    $("screenshot").src = `/api/v1/admin/camera-reports/${id}/screenshot`;
    $("screenshot").hidden = false;
    $("screenshotHint").hidden = true;
    $("rejectReason").value = "";
    setBusy(false);
    document.querySelectorAll(".report-item").forEach(button => button.classList.toggle("selected", button.textContent.startsWith(`#${id} ·`)));
  } catch (error) { notice(error.message, true); }
}

function validCoordinates() {
  const longitude = Number($("longitude").value);
  const latitude = Number($("latitude").value);
  return $("longitude").value && $("latitude").value &&
    Number.isFinite(longitude) && Number.isFinite(latitude) &&
    longitude >= -180 && longitude <= 180 && latitude >= -90 && latitude <= 90
    ? [longitude, latitude] : null;
}

function updateTypeFromCoordinates() {
  const point = validCoordinates();
  if (!point) {
    $("cameraType").value = "";
    $("cameraTypeHint").textContent = "请输入有效的经纬度，或在地图上选点。";
    return;
  }
  if (!state.boundaries) {
    $("cameraTypeHint").textContent = "环路边界暂不可用，请人工选择探头类型。";
    return;
  }
  const result = RingLocation.classify(point[0], point[1], state.boundaries);
  $("cameraType").value = result.type;
  $("cameraTypeHint").textContent = `按地图位置建议：${typeName[result.type]}。${result.nearBoundary ? "距离环路边界约 " + result.boundaryDistanceMeters + " 米，请人工核实并可调整。" : "如有特殊情况可人工调整。"}`;
  $("cameraTypeHint").classList.toggle("warning", result.nearBoundary);
}

function placeMarker(point, recenter = false) {
  if (!state.map) return;
  if (!state.marker) {
    state.marker = new AMap.Marker({ position: point, draggable: true, cursor: "move" });
    state.marker.on("dragend", () => choosePoint(state.marker.getPosition()));
    state.marker.setMap(state.map);
  } else state.marker.setPosition(point);
  if (recenter) state.map.setCenter(point);
}

function choosePoint(lnglat) {
  if (state.selected?.status !== "PENDING") return;
  const point = [Number(lnglat.getLng()).toFixed(7), Number(lnglat.getLat()).toFixed(7)];
  $("longitude").value = point[0];
  $("latitude").value = point[1];
  $("coordinateConfirmed").checked = false;
  placeMarker(point.map(Number));
  updateTypeFromCoordinates();
  $("mapPickerHint").textContent = "选点已回填。请结合截图核实位置，确认坐标后再审核。";
}

function loadAmapScript(key, securityJsCode) {
  return new Promise((resolve, reject) => {
    window._AMapSecurityConfig = { securityJsCode };
    const script = document.createElement("script");
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.onload = () => window.AMap ? resolve() : reject(new Error("地图初始化失败"));
    script.onerror = () => reject(new Error("地图资源加载失败"));
    document.head.append(script);
  });
}

async function initializeMap() {
  try {
    const config = await api("/api/v1/site/map-config");
    if (!config.key || !config.securityJsCode || config.coordinateSystem !== "GCJ02") {
      $("mapPickerHint").textContent = "高德地图未配置或坐标系不匹配，可手动填写 GCJ-02 坐标。";
      return;
    }
    await loadAmapScript(config.key, config.securityJsCode);
    state.map = new AMap.Map("reviewMap", { zoom: 11, center: [116.4074, 39.9042], viewMode: "2D", mapStyle: "amap://styles/whitesmoke" });
    state.map.on("click", event => choosePoint(event.lnglat));
    $("mapPickerHint").textContent = "点击地图、拖动标记，或拖动地图后使用中心位置选点。";
    const current = validCoordinates();
    if (current) placeMarker(current, true);
    if (state.boundaries) drawRingBoundaries();
  } catch (error) {
    $("mapPickerHint").textContent = `地图不可用：${error.message}。可手动输入 GCJ-02 坐标。`;
  }
}

function drawRingBoundaries() {
  if (!state.map || !state.boundaries) return;
  for (const path of [state.boundaries.secondRing, state.boundaries.sixthRing]) {
    new AMap.Polyline({ path, strokeColor: "#bf5a52", strokeWeight: 2,
      strokeOpacity: 0.7, strokeStyle: "dashed", bubble: true, map: state.map });
  }
}

async function loadBoundaries() {
  try {
    const boundaries = await api("/ring-boundaries.json");
    if (boundaries.coordinateSystem !== "GCJ02" || !boundaries.secondRing?.length || !boundaries.sixthRing?.length) {
      throw new Error("边界数据无效");
    }
    state.boundaries = boundaries;
    drawRingBoundaries();
    if (validCoordinates()) updateTypeFromCoordinates();
  } catch (error) {
    $("cameraTypeHint").textContent = `环路边界加载失败：${error.message}；请人工选择类型。`;
  }
}

async function approve(event) {
  event.preventDefault();
  if (!state.selected || state.selected.status !== "PENDING" || state.busy) return;
  const name = $("cameraName").value.trim();
  const longitude = $("longitude").value;
  const latitude = $("latitude").value;
  const selectedType = $("cameraType").value;
  if (!name || !validCoordinates() || !selectedType || !$("coordinateConfirmed").checked) {
    notice("请填写探头名称、有效经纬度、探头类型并确认坐标系。", true); return;
  }
  const publishScreenshot = $("publishScreenshot").checked;
  if (!confirm(`确认将上报 #${state.selectedId} 新增为“${typeName[selectedType]}”探头“${name}”？${publishScreenshot ? "\n用户截图将作为探头图片公开。" : "\n用户截图仅供管理员审核，不公开。"}`)) return;
  setBusy(true);
  try {
    const result = await api(`/api/v1/admin/camera-reports/${state.selectedId}/approve`, {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ name, longitude, latitude, cameraType: selectedType,
        typeCodeRaw: $("typeCodeRaw").value.trim(), coordinateConfirmed: true, publishScreenshot })
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
for (const id of ["longitude", "latitude"]) {
  $(id).addEventListener("change", () => {
    const current = validCoordinates();
    if (current) placeMarker(current, true);
    $("coordinateConfirmed").checked = false;
    updateTypeFromCoordinates();
  });
}
$("cameraType").addEventListener("change", () => {
  $("cameraTypeHint").textContent = `已人工选择：${typeName[$("cameraType").value]}。`;
  $("cameraTypeHint").classList.remove("warning");
});
$("chooseMapCenter").addEventListener("click", () => {
  if (state.map) choosePoint(state.map.getCenter());
  else $("mapPickerHint").textContent = "地图尚不可用，请手动输入 GCJ-02 坐标。";
});
$("cameraForm").addEventListener("submit", approve);
$("reject").addEventListener("click", reject);
loadBoundaries();
initializeMap();
loadList();

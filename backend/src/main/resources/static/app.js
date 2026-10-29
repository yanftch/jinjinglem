"use strict";

const $ = (id) => document.getElementById(id);
const state = { offset: 0, limit: 20, total: 0, request: 0, detailRequest: 0, detail: null, commentOffset: 0, editorMode: "create" };
let toastTimer;

function node(tag, className, value) {
  const result = document.createElement(tag);
  if (className) result.className = className;
  if (value !== undefined && value !== null) result.textContent = String(value);
  return result;
}

function showToast(message, error = false) {
  const toast = $("toast");
  toast.textContent = message;
  toast.classList.toggle("error", error);
  toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toast.classList.remove("visible"), 4500);
}

async function api(path, options = {}) {
  const response = await fetch(path, { ...options, headers: { ...(options.body ? { "Content-Type": "application/json" } : {}), ...options.headers } });
  const body = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) {
    if (response.status === 409) throw new Error("数据已被其他操作更新，请刷新后重试。");
    if (response.status === 401) throw new Error("当前环境需要管理员账号密码。");
    throw new Error(body?.error || `请求失败（HTTP ${response.status}）`);
  }
  return body;
}

function filters() {
  const params = new URLSearchParams();
  const name = $("nameFilter").value.trim();
  const type = $("typeFilter").value.trim();
  const cameraType = $("cameraTypeFilter").value;
  if (name) params.set("name", name);
  if (type) params.set("type", type);
  if (cameraType) params.set("cameraType", cameraType);
  return params;
}

function formatNumber(value) {
  return new Intl.NumberFormat("zh-CN").format(Number(value || 0));
}

function displayCameraType(value) {
  return {
    DISCARD: "已废弃",
    SIX_RING_OUT: "六环外",
    TWO_RING_IN: "二环内",
    SIX_RING_IN: "六环内"
  }[value] || value;
}

async function refreshList() {
  const request = ++state.request;
  const params = filters();
  const hasFilter = params.size > 0;
  const listParams = new URLSearchParams(params);
  listParams.set("offset", String(state.offset));
  listParams.set("limit", String(state.limit));
  $("cameraRows").replaceChildren();
  const loading = node("tr");
  const cell = node("td", "empty-state", "正在加载探头…");
  cell.colSpan = 7;
  loading.append(cell);
  $("cameraRows").append(loading);
  try {
    const [items, filtered, all] = await Promise.all([
      api(`/api/v1/cameras?${listParams}`),
      api(`/api/v1/cameras/count?${params}`),
      hasFilter ? api("/api/v1/cameras/count") : Promise.resolve(null)
    ]);
    if (request !== state.request) return;
    state.total = filtered.total;
    $("allCount").textContent = formatNumber(all?.total ?? filtered.total);
    $("filteredCount").textContent = formatNumber(filtered.total);
    $("filterNote").textContent = hasFilter ? "符合当前查询条件" : "显示全部探头";
    renderRows(items);
    const page = Math.floor(state.offset / state.limit) + 1;
    const totalPages = Math.max(1, Math.ceil(state.total / state.limit));
    $("pageChip").textContent = `第 ${page} / ${totalPages} 页`;
    $("pageInfo").textContent = state.total ? `显示 ${state.offset + 1}–${state.offset + items.length} 条，共 ${formatNumber(state.total)} 条` : "没有符合条件的探头";
    $("prevPage").disabled = state.offset === 0;
    $("nextPage").disabled = state.offset + state.limit >= state.total;
  } catch (error) {
    if (request !== state.request) return;
    $("cameraRows").replaceChildren();
    const row = node("tr");
    const failed = node("td", "empty-state", `加载失败：${error.message}`);
    failed.colSpan = 7;
    row.append(failed);
    $("cameraRows").append(row);
    $("pageChip").textContent = "加载失败";
    showToast(error.message, true);
  }
}

function action(label, callback, danger = false) {
  const button = node("button", `text-button${danger ? " danger" : ""}`, label);
  button.type = "button";
  button.addEventListener("click", callback);
  return button;
}

function renderRows(items) {
  const body = $("cameraRows");
  body.replaceChildren();
  if (!items.length) {
    const row = node("tr");
    const empty = node("td", "empty-state", "没有找到探头。试试其他名称或类型编码。 ");
    empty.colSpan = 7;
    row.append(empty);
    body.append(row);
    return;
  }
  for (const item of items) {
    const row = node("tr");
    const nameCell = node("td", "name-cell");
    const nameButton = node("button", "camera-name", item.name);
    nameButton.type = "button";
    nameButton.addEventListener("click", () => openDetail(item.id));
    nameCell.append(nameButton, node("span", "camera-id", `编号 ${item.id}`));
    const categoryCell = node("td");
    categoryCell.append(node("span", "category-code", displayCameraType(item.camera_type)));
    const typeCell = node("td");
    typeCell.append(node("span", "type-pill", item.type_code_raw || "未填写"));
    const lonCell = node("td", "", item.longitude);
    const latCell = node("td", "", item.latitude);
    const statusCell = node("td");
    statusCell.append(node("span", `state-pill${item.manual_protected ? " manual" : ""}`, item.manual_protected ? "人工保护" : "来源同步"));
    const actionCell = node("td", "row-actions");
    actionCell.append(action("查看", () => openDetail(item.id)), action("编辑", () => openEditor(item.id)), action("删除", () => deleteCamera(item), true));
    row.append(nameCell, categoryCell, typeCell, lonCell, latCell, statusCell, actionCell);
    body.append(row);
  }
}

function closeDetail() {
  state.detailRequest++;
  $("detailDrawer").classList.remove("open");
  $("detailDrawer").setAttribute("aria-hidden", "true");
  $("drawerBackdrop").hidden = true;
  state.detail = null;
}

function detailValue(label, value) {
  const item = node("div");
  item.append(node("span", "detail-label", label), node("span", "detail-value", value ?? "—"));
  return item;
}

function detailSection(title) {
  const section = node("section", "detail-section");
  section.append(node("h3", "", title));
  return section;
}

async function openDetail(id) {
  const request = ++state.detailRequest;
  state.detail = null;
  $("drawerBackdrop").hidden = false;
  $("detailDrawer").classList.add("open");
  $("detailDrawer").setAttribute("aria-hidden", "false");
  $("detailContent").replaceChildren(node("p", "muted", "正在加载探头详情…"));
  const openedId = String(id);
  try {
    const [detail, images] = await Promise.all([
      api(`/api/v1/cameras/${encodeURIComponent(openedId)}`),
      api(`/api/v1/cameras/${encodeURIComponent(openedId)}/images`)
    ]);
    if (request !== state.detailRequest || $("detailDrawer").getAttribute("aria-hidden") === "true") return;
    state.detail = detail;
    state.commentOffset = 0;
    renderDetail(detail, images);
    await loadComments(true);
  } catch (error) {
    if (request !== state.detailRequest) return;
    $("detailContent").replaceChildren(node("p", "muted", error.message));
    showToast(error.message, true);
  }
}

function renderDetail(detail, images) {
  const content = $("detailContent");
  content.replaceChildren();
  content.append(node("p", "detail-name", detail.name));
  const tags = node("div", "detail-tags");
  tags.append(node("span", "category-code", displayCameraType(detail.cameraType)));
  tags.append(node("span", "type-pill", `原始编码 ${detail.typeCodeRaw || "未填写"}`));
  tags.append(node("span", `state-pill${detail.manualProtected ? " manual" : ""}`, detail.manualProtected ? "人工保护" : "来源同步"));
  content.append(tags);

  const location = detailSection("基础信息");
  const grid = node("div", "detail-grid");
  grid.append(detailValue("探头编号", detail.id), detailValue("版本号", detail.version), detailValue("经度", detail.longitude), detailValue("纬度", detail.latitude), detailValue("坐标系", detail.coordinateSystem === "GCJ02" ? "高德坐标系" : "未确认"), detailValue("探头类型", displayCameraType(detail.cameraType)), detailValue("原始类型编码", detail.typeCodeRaw || "未填写"));
  location.append(grid, node("p", "coordinate-note", detail.coordinateSystem === "GCJ02"
    ? "已采用高德坐标系；点位仅供查询参考，实际通行请以现场信息为准。"
    : "坐标系尚未确认，请勿直接将原始坐标当作准确地图位置。"));
  content.append(location);

  const photo = detailSection(`图片 · ${images.length}`);
  const safeImages = images.map((item) => {
    try { const url = new URL(item.url); return ["http:", "https:"].includes(url.protocol) ? url.href : null; }
    catch { return null; }
  }).filter(Boolean);
  if (!safeImages.length) photo.append(node("p", "muted", "该探头暂无图片。"));
  else {
    const gallery = node("div", "image-list");
    for (const url of safeImages) {
      const link = node("a");
      link.href = url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      const image = node("img");
      image.src = url;
      image.alt = "探头图片";
      image.loading = "lazy";
      gallery.append(link);
      link.append(image);
    }
    photo.append(gallery);
  }
  content.append(photo);
  const comments = detailSection("评论");
  const list = node("div");
  list.id = "commentList";
  const more = node("button", "button button-light more-comments", "加载更多评论");
  more.id = "moreComments";
  more.type = "button";
  more.hidden = true;
  more.addEventListener("click", () => loadComments(false));
  comments.append(list, more);
  content.append(comments);
}

async function loadComments(reset) {
  const detail = state.detail;
  if (!detail) return;
  const button = $("moreComments");
  button.disabled = true;
  const offset = reset ? 0 : state.commentOffset;
  try {
    const items = await api(`/api/v1/cameras/${encodeURIComponent(String(detail.id))}/comments?offset=${offset}&limit=20`);
    if (state.detail?.id !== detail.id) return;
    const list = $("commentList");
    if (reset) list.replaceChildren();
    if (reset && !items.length) list.append(node("p", "muted", "该探头暂无评论。"));
    for (const item of items) {
      const comment = node("article", "comment");
      const meta = node("div", "comment-meta");
      meta.append(node("strong", "", item.nickname || "匿名"), node("span", "", item.raw_time || item.parsed_time || "时间未知"));
      comment.append(meta, node("p", "", item.content || ""));
      list.append(comment);
    }
    state.commentOffset = offset + items.length;
    button.hidden = items.length < 20;
  } catch (error) { showToast(`评论加载失败：${error.message}`, true); }
  finally { button.disabled = false; }
}

async function openEditor(id) {
  const form = $("editorForm");
  form.reset();
  state.editorMode = id == null ? "create" : "edit";
  $("editorTitle").textContent = id == null ? "新增探头" : "编辑探头";
  $("saveButton").textContent = id == null ? "创建探头" : "保存修改";
  if (id != null) {
    try {
      const detail = await api(`/api/v1/cameras/${encodeURIComponent(String(id))}`);
      state.editingDetail = detail;
      $("cameraName").value = detail.name;
      $("cameraLongitude").value = detail.longitude;
      $("cameraLatitude").value = detail.latitude;
      $("cameraType").value = detail.typeCodeRaw || "";
    } catch (error) { showToast(error.message, true); return; }
  } else state.editingDetail = null;
  $("editorDialog").showModal();
  $("cameraName").focus();
}

async function saveEditor(event) {
  event.preventDefault();
  const form = $("editorForm");
  if (!form.reportValidity()) return;
  const payload = {
    name: $("cameraName").value.trim(),
    longitude: Number($("cameraLongitude").value),
    latitude: Number($("cameraLatitude").value),
    typeCodeRaw: $("cameraType").value
  };
  if (!payload.name) { showToast("请输入探头名称。", true); return; }
  const edit = state.editorMode === "edit";
  if (edit) payload.version = state.editingDetail.version;
  $("saveButton").disabled = true;
  try {
    const result = await api(edit ? `/api/v1/admin/cameras/${encodeURIComponent(String(state.editingDetail.id))}` : "/api/v1/admin/cameras", { method: edit ? "PATCH" : "POST", body: JSON.stringify(payload) });
    $("editorDialog").close();
    showToast(edit ? "探头修改已保存。" : "探头创建成功。");
    state.offset = 0;
    await refreshList();
    await openDetail(result.id);
  } catch (error) { showToast(error.message, true); }
  finally { $("saveButton").disabled = false; }
}

async function deleteCamera(item) {
  if (!window.confirm(`确定删除“${item.name}”吗？\n这会软删除该探头，后续同步不会自动恢复。`)) return;
  try {
    await api(`/api/v1/admin/cameras/${encodeURIComponent(String(item.id))}?version=${encodeURIComponent(String(item.version))}`, { method: "DELETE" });
    if (state.detail && String(state.detail.id) === String(item.id)) closeDetail();
    showToast("探头已删除。");
    if (state.offset > 0 && state.offset >= state.total - 1) state.offset = Math.max(0, state.offset - state.limit);
    await refreshList();
  } catch (error) { showToast(error.message, true); }
}

$("searchForm").addEventListener("submit", (event) => { event.preventDefault(); state.offset = 0; refreshList(); });
$("resetButton").addEventListener("click", () => { $("nameFilter").value = ""; $("typeFilter").value = ""; $("cameraTypeFilter").value = ""; state.offset = 0; refreshList(); });
$("prevPage").addEventListener("click", () => { if (state.offset > 0) { state.offset -= state.limit; refreshList(); } });
$("nextPage").addEventListener("click", () => { if (state.offset + state.limit < state.total) { state.offset += state.limit; refreshList(); } });
$("addButton").addEventListener("click", () => openEditor(null));
$("closeDetail").addEventListener("click", closeDetail);
$("drawerBackdrop").addEventListener("click", closeDetail);
$("editDetail").addEventListener("click", () => { if (state.detail) openEditor(state.detail.id); });
$("deleteDetail").addEventListener("click", () => { if (state.detail) deleteCamera({ id: state.detail.id, name: state.detail.name, version: state.detail.version }); });
$("editorForm").addEventListener("submit", saveEditor);
$("closeEditor").addEventListener("click", () => $("editorDialog").close());
$("cancelEditor").addEventListener("click", () => $("editorDialog").close());
refreshList();

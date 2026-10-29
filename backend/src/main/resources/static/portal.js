"use strict";

const $ = (id) => document.getElementById(id);
const typeNames = { SIX_RING_IN: "六环内", SIX_RING_OUT: "六环外", TWO_RING_IN: "二环内", DISCARD: "已废弃" };
const state = { name: "", type: "", offset: 0, limit: 20, total: 0, listRequest: 0, detailRequest: 0, mapRequest: 0, map: null, massMarks: null, infoWindow: null, userMarker: null, mapReady: false, selectedId: null, commentOffset: 0, currentDetail: null };
let mapRefreshTimer;
let locationStatusTimer;

function element(tag, className, value) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (value !== undefined && value !== null) item.textContent = String(value);
  return item;
}

function formatCount(value) { return new Intl.NumberFormat("zh-CN").format(value || 0); }
function typeName(value) { return typeNames[value] || "未分类"; }

async function request(path) {
  const response = await fetch(path);
  if (!response.ok) throw new Error(`数据请求失败（${response.status}）`);
  return response.json();
}

function queryParams() {
  const params = new URLSearchParams();
  if (state.name) params.set("name", state.name);
  if (state.type) params.set("cameraType", state.type);
  return params;
}

async function refreshList() {
  const seq = ++state.listRequest;
  const params = queryParams();
  const listParams = new URLSearchParams(params);
  listParams.set("offset", String(state.offset));
  listParams.set("limit", String(state.limit));
  $("cameraList").replaceChildren(element("p", "list-message", "正在加载探头…"));
  try {
    const [items, count] = await Promise.all([
      request(`/api/v1/cameras?${listParams}`),
      request(`/api/v1/cameras/count?${params}`)
    ]);
    if (seq !== state.listRequest) return;
    state.total = count.total;
    $("resultCount").textContent = `共 ${formatCount(count.total)} 条`;
    $("pageInfo").textContent = count.total ? `${state.offset + 1}–${state.offset + items.length} / ${formatCount(count.total)}` : "暂无结果";
    $("previousPage").disabled = state.offset === 0;
    $("nextPage").disabled = state.offset + state.limit >= count.total;
    renderList(items);
  } catch (error) {
    if (seq !== state.listRequest) return;
    $("resultCount").textContent = "查询失败";
    $("pageInfo").textContent = "—";
    $("cameraList").replaceChildren(element("p", "list-message", `${error.message}，请稍后重试。`));
  }
}

function renderList(items) {
  const list = $("cameraList");
  list.replaceChildren();
  if (!items.length) {
    list.append(element("p", "list-message", "没有找到符合条件的探头，试试其他位置或类型。"));
    return;
  }
  for (const item of items) {
    const card = element("button", `camera-card${state.selectedId === item.id ? " selected" : ""}`);
    card.type = "button";
    card.dataset.cameraId = String(item.id);
    const top = element("span", "card-top");
    top.append(element("span", "card-title", item.name), element("span", `type-badge ${item.camera_type}`, typeName(item.camera_type)));
    const bottom = element("span", "card-bottom");
    bottom.append(element("span", "", `探头编号 ${item.id}`), element("span", "detail-link", "查看详情 →"));
    card.append(top, bottom);
    card.addEventListener("click", () => openDetail(item.id, item));
    list.append(card);
  }
}

function closeDetail() {
  state.detailRequest++;
  state.currentDetail = null;
  state.selectedId = null;
  $("detailDrawer").classList.remove("open");
  $("detailDrawer").setAttribute("aria-hidden", "true");
  $("drawerBackdrop").hidden = true;
  for (const card of $("cameraList").children) card.classList.remove("selected");
}

function detailField(label, value) {
  const item = element("div");
  item.append(element("span", "detail-label", label), element("span", "detail-value", value ?? "—"));
  return item;
}

function detailSection(title) {
  const section = element("section", "detail-section");
  section.append(element("h3", "", title));
  return section;
}

async function openDetail(id, fromList) {
  state.infoWindow?.close();
  const seq = ++state.detailRequest;
  state.selectedId = id;
  $("drawerBackdrop").hidden = false;
  $("detailDrawer").classList.add("open");
  $("detailDrawer").setAttribute("aria-hidden", "false");
  $("detailContent").replaceChildren(element("p", "muted", "正在加载探头详情…"));
  for (const card of $("cameraList").children) card.classList.toggle("selected", card.dataset.cameraId === String(id));
  if (fromList && state.mapReady) {
    state.map.setZoomAndCenter(15, [Number(fromList.longitude), Number(fromList.latitude)]);
  }
  try {
    const [detail, images] = await Promise.all([
      request(`/api/v1/cameras/${encodeURIComponent(id)}`),
      request(`/api/v1/cameras/${encodeURIComponent(id)}/images`)
    ]);
    if (seq !== state.detailRequest) return;
    state.currentDetail = detail;
    state.commentOffset = 0;
    renderDetail(detail, images);
    await loadComments(true);
  } catch (error) {
    if (seq !== state.detailRequest) return;
    $("detailContent").replaceChildren(element("p", "muted", error.message));
  }
}

function renderDetail(detail, images) {
  const content = $("detailContent");
  content.replaceChildren();
  content.append(element("h3", "detail-name", detail.name));
  const meta = element("div", "detail-meta");
  meta.append(element("span", `type-badge ${detail.cameraType}`, typeName(detail.cameraType)));
  content.append(meta);

  const basic = detailSection("基础信息");
  const grid = element("div", "detail-grid");
  grid.append(
    detailField("探头编号", detail.id), detailField("探头类型", typeName(detail.cameraType)),
    detailField("原始经度", detail.longitude), detailField("原始纬度", detail.latitude)
  );
  const coordinateNote = detail.coordinateSystem === "GCJ02"
    ? "已采用高德坐标系。点位仅供查询参考，实际通行请以现场信息为准。"
    : "坐标系尚未确认，原始经纬度请勿直接用于导航。";
  basic.append(grid, element("p", "detail-note", coordinateNote));
  content.append(basic);

  const validImages = images.map((item) => {
    try { const url = new URL(item.url); return ["https:", "http:"].includes(url.protocol) ? url.href : null; }
    catch { return null; }
  }).filter(Boolean);
  const photo = detailSection(`图片 · ${validImages.length}`);
  if (!validImages.length) photo.append(element("p", "muted", "暂无图片。"));
  else {
    const gallery = element("div", "photo-list");
    for (const url of validImages) {
      const link = element("a");
      link.href = url;
      link.target = "_blank";
      link.rel = "noopener noreferrer";
      const image = element("img");
      image.src = url;
      image.alt = "探头图片";
      image.loading = "lazy";
      link.append(image);
      gallery.append(link);
    }
    photo.append(gallery);
  }
  content.append(photo);

  const comments = detailSection("评论");
  const list = element("div");
  list.id = "commentList";
  const more = element("button", "more-comments", "查看更多评论");
  more.id = "moreComments";
  more.type = "button";
  more.hidden = true;
  more.addEventListener("click", () => loadComments(false));
  comments.append(list, more);
  content.append(comments);
}

async function loadComments(reset) {
  const detail = state.currentDetail;
  if (!detail) return;
  const offset = reset ? 0 : state.commentOffset;
  const more = $("moreComments");
  more.disabled = true;
  try {
    const items = await request(`/api/v1/cameras/${encodeURIComponent(detail.id)}/comments?offset=${offset}&limit=20`);
    if (state.currentDetail !== detail) return;
    const list = $("commentList");
    if (reset) list.replaceChildren();
    if (reset && !items.length) list.append(element("p", "muted", "暂无评论。"));
    for (const item of items) {
      const comment = element("article", "comment");
      const heading = element("div");
      heading.append(element("strong", "", item.nickname || "匿名"), element("time", "", item.raw_time || item.parsed_time || "时间未知"));
      comment.append(heading, element("p", "", item.content || ""));
      list.append(comment);
    }
    state.commentOffset = offset + items.length;
    more.hidden = items.length < 20;
  } catch (error) {
    if (state.currentDetail === detail) $("commentList").append(element("p", "muted", `评论加载失败：${error.message}`));
  } finally { more.disabled = false; }
}

function showMapPending(message) {
  $("mapPendingText").textContent = message;
  $("mapPending").hidden = false;
  $("mapPreview").hidden = false;
  $("mapPointCount").textContent = "点位待接入";
}

function loadAmapScript(key, securityJsCode) {
  return new Promise((resolve, reject) => {
    window._AMapSecurityConfig = { securityJsCode };
    const script = document.createElement("script");
    script.src = `https://webapi.amap.com/maps?v=2.0&key=${encodeURIComponent(key)}`;
    script.onload = () => window.AMap ? resolve(window.AMap) : reject(new Error("地图资源未能初始化"));
    script.onerror = () => reject(new Error("地图资源加载失败"));
    document.head.append(script);
  });
}

function showMapPopup(camera) {
  if (!camera?.id) return;
  state.infoWindow?.close();
  const popup = element("div", "map-camera-popup");
  const title = element("strong", "map-popup-title", camera.name);
  const close = element("button", "map-popup-close", "×");
  close.type = "button";
  close.setAttribute("aria-label", "关闭探头提示");
  close.addEventListener("click", () => state.infoWindow?.close());
  const details = element("button", "map-popup-details", "详细信息");
  details.type = "button";
  details.addEventListener("click", () => openDetail(camera.id));
  popup.append(title, close, details);
  state.infoWindow = new window.AMap.InfoWindow({
    isCustom: true,
    content: popup,
    offset: new window.AMap.Pixel(0, -33),
    closeWhenClickMap: true,
    autoMove: true
  });
  state.infoWindow.open(state.map, camera.lnglat);
}

function showLocationStatus(message, autoHide = false) {
  clearTimeout(locationStatusTimer);
  $("locationStatus").textContent = message;
  $("locationStatus").hidden = false;
  if (autoHide) locationStatusTimer = setTimeout(() => { $("locationStatus").hidden = true; }, 4000);
}

async function locateUser() {
  if (!state.mapReady || $("locateMe").disabled) return;
  $("locateMe").disabled = true;
  showLocationStatus("正在定位…");
  try {
    const position = await new Promise((resolve, reject) => {
      const timeout = setTimeout(() => reject(new Error("定位超时")), 18000);
      window.AMap.plugin("AMap.Geolocation", () => {
        try {
          const geolocation = new window.AMap.Geolocation({ enableHighAccuracy: true, timeout: 10000, panToLocation: false, zoomToAccuracy: false });
          geolocation.getCurrentPosition((status, result) => {
            clearTimeout(timeout);
            if (status === "complete" && result?.position) resolve(result.position);
            else reject(new Error("定位失败"));
          });
        } catch (error) { clearTimeout(timeout); reject(error); }
      });
    });
    if (!state.userMarker) {
      state.userMarker = new window.AMap.Marker({
        position,
        content: element("span", "user-location-pin"),
        anchor: "center",
        zIndex: 20,
        title: "我的位置"
      });
      state.userMarker.setMap(state.map);
    } else state.userMarker.setPosition(position);
    state.map.setCenter(position);
    showLocationStatus("已定位到当前位置", true);
  } catch {
    showLocationStatus("定位失败，请检查浏览器定位权限或网络后重试。");
  } finally { $("locateMe").disabled = false; }
}

function scheduleMapRefresh() {
  clearTimeout(mapRefreshTimer);
  if (state.mapReady) mapRefreshTimer = setTimeout(refreshMapPoints, 250);
}

async function refreshMapPoints() {
  if (!state.mapReady) return;
  const seq = ++state.mapRequest;
  const bounds = state.map.getBounds();
  if (!bounds) return;
  const southwest = bounds.getSouthWest();
  const northeast = bounds.getNorthEast();
  const params = queryParams();
  params.set("west", String(southwest.lng));
  params.set("south", String(southwest.lat));
  params.set("east", String(northeast.lng));
  params.set("north", String(northeast.lat));
  params.set("limit", "10000");
  try {
    const result = await request(`/api/v1/cameras/map?${params}`);
    if (seq !== state.mapRequest) return;
    const styleIndex = { SIX_RING_IN: 0, SIX_RING_OUT: 1, TWO_RING_IN: 2, DISCARD: 3 };
    state.massMarks.setData(result.items.map((item) => ({
      lnglat: [Number(item.longitude), Number(item.latitude)],
      id: item.id,
      name: item.name,
      style: styleIndex[item.camera_type] ?? 0
    })));
    $("mapPointCount").textContent = `当前区域 ${formatCount(result.items.length)} 个点位`;
    $("mapNotice").hidden = !result.truncated;
    if (result.truncated) $("mapNotice").textContent = "当前区域点位过多，已显示前一万条。请放大地图查看完整点位。";
  } catch (error) {
    if (seq !== state.mapRequest) return;
    $("mapNotice").textContent = `点位加载失败：${error.message}`;
    $("mapNotice").hidden = false;
  }
}

async function initializeMap() {
  try {
    const config = await request("/api/v1/site/map-config");
    if (!config.key || !config.securityJsCode) {
      showMapPending("高德地图尚未配置。左侧已可查询全部探头，点击任一记录可查看详细信息。");
      return;
    }
    if (config.coordinateSystem !== "GCJ02") {
      showMapPending("高德地图配置已准备，但探头坐标系尚未确认。确认后才能准确显示点位；左侧查询可正常使用。");
      return;
    }
    const AMap = await loadAmapScript(config.key, config.securityJsCode);
    state.map = new AMap.Map("map", { zoom: 14, center: [116.4074, 39.9042], viewMode: "2D", mapStyle: "amap://styles/whitesmoke" });
    const icons = ["location_red", "location_blue", "location_purple", "location_yellow"];
    state.massMarks = new AMap.MassMarks([], {
      zIndex: 8,
      opacity: 1,
      cursor: "pointer",
      style: icons.map((name) => ({ url: `/icon/${name}.png`, anchor: new AMap.Pixel(11, 29), size: new AMap.Size(22, 29) }))
    });
    state.massMarks.on("click", (event) => showMapPopup(event.data));
    state.massMarks.setMap(state.map);
    $("mapPreview").hidden = true;
    $("mapPending").hidden = true;
    $("mapZoomControls").hidden = false;
    $("map").setAttribute("aria-hidden", "false");
    state.mapReady = true;
    state.map.on("moveend", scheduleMapRefresh);
    state.map.on("zoomend", scheduleMapRefresh);
    scheduleMapRefresh();
  } catch (error) {
    showMapPending(`地图暂时无法加载：${error.message}。左侧列表仍可正常查询。`);
  }
}

$("searchForm").addEventListener("submit", (event) => {
  event.preventDefault();
  state.name = $("searchInput").value.trim();
  state.offset = 0;
  refreshList();
  scheduleMapRefresh();
});
$("typeFilter").addEventListener("change", () => {
  state.type = $("typeFilter").value;
  state.offset = 0;
  refreshList();
  scheduleMapRefresh();
});
$("clearFilters").addEventListener("click", () => {
  $("searchInput").value = "";
  $("typeFilter").value = "";
  state.name = "";
  state.type = "";
  state.offset = 0;
  refreshList();
  scheduleMapRefresh();
});
$("previousPage").addEventListener("click", () => {
  if (state.offset > 0) { state.offset -= state.limit; refreshList(); $("cameraList").scrollTop = 0; }
});
$("nextPage").addEventListener("click", () => {
  if (state.offset + state.limit < state.total) { state.offset += state.limit; refreshList(); $("cameraList").scrollTop = 0; }
});
$("closeDetail").addEventListener("click", closeDetail);
$("locateMe").addEventListener("click", locateUser);
$("zoomIn").addEventListener("click", () => { if (state.mapReady) state.map.setZoom(state.map.getZoom() + 1); });
$("zoomOut").addEventListener("click", () => { if (state.mapReady) state.map.setZoom(state.map.getZoom() - 1); });
$("drawerBackdrop").addEventListener("click", closeDetail);
document.addEventListener("keydown", (event) => {
  if (event.key !== "Escape") return;
  if ($("detailDrawer").classList.contains("open")) closeDetail();
  else state.infoWindow?.close();
});

request("/api/v1/cameras/count").then((result) => { $("totalCount").textContent = formatCount(result.total); }).catch(() => { $("totalCount").textContent = "—"; });
refreshList();
initializeMap();

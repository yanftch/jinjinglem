var cameraApi = require("../../utils/jinjing-api");
var app = getApp();
var NEW_CAMERA_STORAGE_KEY = "jinjing_seen_camera_id";

function iconForType(type) {
  if (type === "DISCARD") return "/image/fq.png";
  if (type === "SIX_RING_OUT") return "/image/locationSixRing.png";
  if (type === "TWO_RING_IN") return "/image/locationTwoRing.png";
  return "/image/location.png";
}

function typeName(type) {
  if (type === "DISCARD") return "已废弃";
  if (type === "SIX_RING_OUT") return "六环外";
  if (type === "TWO_RING_IN") return "二环内";
  return "六环内";
}

function makeMarker(camera, selected) {
  return {
    id: camera.id,
    latitude: camera.position[1],
    longitude: camera.position[0],
    iconPath: iconForType(camera.cameraType),
    width: 24,
    height: 36,
    callout: {
      content: camera.name,
      color: "#202830",
      bgColor: "#ffffff",
      padding: 8,
      borderRadius: 6,
      display: selected ? "ALWAYS" : "BYCLICK"
    }
  };
}

Page({
  data: {
    latitude: 39.909088,
    longitude: 116.397643,
    scale: 16,
    markers: [],
    searchKeyword: "",
    searchResults: [],
    isSearchFocused: false,
    isSearching: false,
    selectedId: null,
    showNewCameras: false,
    newCameras: [],
    newCameraListHeight: 0,
    loaded: false
  },
  onLoad: function() {
    this.active = true;
    var page = this;
    wx.getLocation({
      type: "gcj02",
      success: function(location) {
        page.setData({ latitude: location.latitude, longitude: location.longitude });
      }
    });
    wx.showLoading({ title: "加载探头", mask: true });
    var latestRequest = cameraApi.latestCameras().catch(function() { return []; });
    cameraApi.loadAllCameras().then(function(cameras) {
      if (!page.active) return;
      app.globalData.cameraList = cameras;
      page.setData({ loaded: true });
      page.refreshVisibleMarkers();
      if (page.data.searchKeyword) page.onSearchInput({ detail: { value: page.data.searchKeyword } });
    }).catch(function(error) {
      wx.showToast({ title: error.message || "探头加载失败", icon: "none" });
    }).then(function() {
      wx.hideLoading();
      return latestRequest;
    }).then(function(cameras) {
      if (page.active) page.showRecentCameras(cameras);
    });
  },
  onUnload: function() {
    this.active = false;
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.regionTimer) clearTimeout(this.regionTimer);
  },
  showRecentCameras: function(cameras) {
    if (!Array.isArray(cameras) || !cameras.length) return;
    var latestId = Number(cameras[0].id);
    if (!Number.isFinite(latestId)) return;
    var seenId = 0;
    try { seenId = Number(wx.getStorageSync(NEW_CAMERA_STORAGE_KEY)) || 0; } catch (_) {}
    if (seenId === latestId) return;
    var candidates = seenId > 0 && seenId < latestId
      ? cameras.filter(function(camera) { return Number(camera.id) > seenId; })
      : cameras;
    var selected = candidates.slice(0, 5).map(function(camera, index) {
      return { id: Number(camera.id), name: camera.name,
        typeText: typeName(camera.camera_type), number: ("0" + (index + 1)).slice(-2) };
    });
    if (!selected.length) return;
    this.newestCameraId = latestId;
    this.setData({ showNewCameras: true, newCameras: selected,
      newCameraListHeight: selected.length * 112 });
  },
  closeNewCameras: function() {
    this.setData({ showNewCameras: false });
    if (this.newestCameraId) {
      try { wx.setStorageSync(NEW_CAMERA_STORAGE_KEY, this.newestCameraId); } catch (_) {}
    }
  },
  openNewCamera: function(event) {
    var id = Number(event.currentTarget.dataset.id);
    if (!id) return;
    this.closeNewCameras();
    wx.navigateTo({ url: "/pages/comment/comment?id=" + id });
  },
  stopNewCamerasTap: function() {},
  onRegionChange: function(event) {
    if (event.type !== "end" || !this.data.loaded) return;
    var region = event.detail && event.detail.region;
    if (region && region.northeast && region.southwest) {
      this.renderRegion(region);
    } else {
      this.refreshVisibleMarkers();
    }
  },
  refreshVisibleMarkers: function() {
    var page = this;
    var context = wx.createMapContext("myMap", this);
    context.getRegion({
      success: function(region) { page.renderRegion(region); },
      fail: function() {
        if (page.regionTimer) clearTimeout(page.regionTimer);
        page.regionTimer = setTimeout(function() { page.refreshVisibleMarkers(); }, 500);
      }
    });
  },
  renderRegion: function(region) {
    if (!region || !region.northeast || !region.southwest) return;
    var selectedId = this.data.selectedId;
    var cameras = app.globalData.cameraList || [];
    var selected = null;
    var visible = cameras.filter(function(camera) {
      if (camera.id === selectedId) selected = camera;
      return camera.position[1] >= region.southwest.latitude &&
        camera.position[1] <= region.northeast.latitude &&
        camera.position[0] >= region.southwest.longitude &&
        camera.position[0] <= region.northeast.longitude;
    });
    if (selected && !visible.some(function(camera) { return camera.id === selectedId; })) visible.push(selected);
    this.setData({ markers: visible.map(function(camera) { return makeMarker(camera, camera.id === selectedId); }) });
  },
  onMarkerTap: function(event) {
    var id = Number(event.markerId || event.detail && event.detail.markerId);
    if (!id) return;
    wx.navigateTo({ url: "/pages/comment/comment?id=" + id });
  },
  onMapTap: function() {
    if (this.data.searchResults.length) this.setData({ searchResults: [], isSearchFocused: false });
  },
  onLocateTap: function() {
    var page = this;
    wx.getLocation({
      type: "gcj02",
      success: function(location) {
        page.setData({ latitude: location.latitude, longitude: location.longitude, scale: 16 });
        wx.createMapContext("myMap", page).moveToLocation({ longitude: location.longitude, latitude: location.latitude });
      },
      fail: function() { wx.showToast({ title: "无法获取当前位置", icon: "none" }); }
    });
  },
  onSearchInput: function(event) {
    var keyword = String(event.detail.value || "").trim();
    this.setData({ searchKeyword: keyword, isSearching: !!keyword, searchResults: [] });
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (!keyword) return;
    var page = this;
    this.searchTimer = setTimeout(function() {
      var query = keyword.toLowerCase();
      var results = (app.globalData.cameraList || []).filter(function(camera) {
        return String(camera.name || "").toLowerCase().indexOf(query) !== -1;
      }).slice(0, 30).map(function(camera) {
        return {
          id: camera.id,
          name: camera.name,
          latitude: camera.position[1],
          longitude: camera.position[0],
          cameraType: camera.cameraType,
          typeText: typeName(camera.cameraType),
          iconPath: iconForType(camera.cameraType)
        };
      });
      if (page.data.searchKeyword === keyword) page.setData({ searchResults: results, isSearching: false });
    }, 250);
  },
  onSearchResultTap: function(event) {
    var item = event.currentTarget.dataset.item;
    if (!item) return;
    this.setData({
      latitude: item.latitude,
      longitude: item.longitude,
      scale: 17,
      selectedId: item.id,
      searchKeyword: "",
      searchResults: [],
      isSearchFocused: false
    });
    this.refreshVisibleMarkers();
  },
  onSearchFocus: function() { this.setData({ isSearchFocused: true }); },
  onSearchBlur: function() {},
  clearSearch: function() {
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.setData({ searchKeyword: "", searchResults: [], isSearching: false, selectedId: null });
    this.refreshVisibleMarkers();
  },
  cancelSearch: function() { this.clearSearch(); this.setData({ isSearchFocused: false }); },
  showUnavailable: function() { wx.showToast({ title: "功能暂不可用", icon: "none" }); },
  realTimeNavigation: function() { this.showUnavailable(); },
  routePlan: function() { wx.navigateTo({ url: "/pages/route/route" }); },
  membership: function() { this.showUnavailable(); },
  jjz: function() { this.showUnavailable(); },
  mineSetting: function() { this.showUnavailable(); },
  onShareAppMessage: function() { return { title: "进京了么 · 探头分布", path: "/pages/index/index" }; },
  onShareTimeline: function() { return { title: "进京了么 · 探头分布" }; }
});

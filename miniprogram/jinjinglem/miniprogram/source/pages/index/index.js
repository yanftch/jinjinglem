var cameraApi = require("../../utils/jinjing-api");
var app = getApp();

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

function makeArrivalMarker(position) {
  return {
    id: 0,
    latitude: Number(position.latitude),
    longitude: Number(position.longitude),
    iconPath: "/image/brand.png",
    width: 32,
    height: 32,
    callout: { content: "我的到达位置", color: "#202830", bgColor: "#ffffff",
      padding: 8, borderRadius: 6, display: "ALWAYS" }
  };
}

Page({
  data: {
    mode: "LOADING",
    latitude: 39.909088,
    longitude: 116.397643,
    scale: 16,
    markers: [],
    reviewMarkers: [],
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
    this.coldLaunch = !!app.globalData.recentPopupPending;
    app.globalData.recentPopupPending = false;
  },
  onShow: function() {
    var generation = app.globalData.foregroundGeneration || 1;
    if (this.modeGeneration === generation) return;
    this.modeGeneration = generation;
    var page = this;
    this.setData({ mode: "LOADING", reviewMarkers: [], showNewCameras: false });
    cameraApi.miniProgramMode().then(function(config) {
      if (!page.active || page.modeGeneration !== generation) return;
      if (config.mode === "LIVE") page.enterLiveMode(generation);
      else if (config.mode === "REVIEW") page.enterReviewMode(generation);
      else throw new Error("小程序模式配置无效");
    }).catch(function() {
      if (!page.active || page.modeGeneration !== generation) return;
      page.coldLaunch = false;
      page.setData({ mode: "UNAVAILABLE", showNewCameras: false });
      wx.showToast({ title: "地图配置加载失败，请重新进入", icon: "none" });
    });
  },
  enterLiveMode: function(generation) {
    var page = this;
    this.setData({ mode: "LIVE", reviewMarkers: [] });
    if (this.camerasLoaded) {
      this.refreshVisibleMarkers();
      this.coldLaunch = false;
      return;
    }
    var latestRequest = this.coldLaunch ? cameraApi.latestCameras().catch(function() { return []; }) : null;
    this.coldLaunch = false;
    wx.getLocation({
      type: "gcj02",
      success: function(location) {
        if (page.active && page.modeGeneration === generation && page.data.mode === "LIVE") {
          page.setData({ latitude: location.latitude, longitude: location.longitude });
        }
      }
    });
    wx.showLoading({ title: "加载探头", mask: true });
    cameraApi.loadAllCameras().then(function(cameras) {
      if (!page.active || page.modeGeneration !== generation || page.data.mode !== "LIVE") return;
      app.globalData.cameraList = cameras;
      page.camerasLoaded = true;
      page.setData({ loaded: true });
      page.refreshVisibleMarkers();
      if (page.data.searchKeyword) page.onSearchInput({ detail: { value: page.data.searchKeyword } });
    }).catch(function(error) {
      if (page.active && page.modeGeneration === generation && page.data.mode === "LIVE") {
        wx.showToast({ title: error.message || "探头加载失败", icon: "none" });
      }
    }).then(function() {
      wx.hideLoading();
      if (latestRequest) return latestRequest.then(function(cameras) {
        if (page.active && page.modeGeneration === generation && page.data.mode === "LIVE") {
          page.showRecentCameras(cameras);
        }
      });
    });
  },
  enterReviewMode: function(generation) {
    var page = this;
    this.arrivalVersion = this.arrivalVersion || 0;
    var arrivalVersion = this.arrivalVersion;
    this.coldLaunch = false;
    this.camerasLoaded = false;
    app.globalData.cameraList = [];
    this.setData({ mode: "REVIEW", markers: [], searchKeyword: "", searchResults: [],
      selectedId: null, loaded: false, showNewCameras: false });
    cameraApi.reviewArrivals().then(function(rows) {
      if (!page.active || page.modeGeneration !== generation || page.data.mode !== "REVIEW" ||
          page.arrivalVersion !== arrivalVersion || !rows.length) return;
      var latest = rows[0];
      page.setData({ latitude: Number(latest.latitude), longitude: Number(latest.longitude),
        reviewMarkers: [makeArrivalMarker(latest)] });
    }).catch(function() {});
  },
  onUnload: function() {
    this.active = false;
    if (this.searchTimer) clearTimeout(this.searchTimer);
    if (this.regionTimer) clearTimeout(this.regionTimer);
  },
  showRecentCameras: function(cameras) {
    var selected = (Array.isArray(cameras) ? cameras : []).slice(0, 5).map(function(camera, index) {
      return { id: Number(camera.id), name: camera.name,
        typeText: typeName(camera.camera_type), number: ("0" + (index + 1)).slice(-2),
        createdAtText: cameraApi.formatBeijingTime(camera.created_at) };
    });
    if (!selected.length) return;
    this.setData({ showNewCameras: true, newCameras: selected,
      newCameraListHeight: selected.length * 112 });
  },
  closeNewCameras: function() {
    this.setData({ showNewCameras: false });
  },
  openNewCamera: function(event) {
    if (this.data.mode !== "LIVE") return;
    var id = Number(event.currentTarget.dataset.id);
    if (!id) return;
    this.closeNewCameras();
    wx.navigateTo({ url: "/pages/comment/comment?id=" + id });
  },
  stopNewCamerasTap: function() {},
  onRegionChange: function(event) {
    if (event.type !== "end" || !this.data.loaded || this.data.mode !== "LIVE") return;
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
    if (this.data.mode !== "LIVE" || !region || !region.northeast || !region.southwest) return;
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
    if (this.data.mode !== "LIVE") return;
    var id = Number(event.markerId || event.detail && event.detail.markerId);
    if (!id) return;
    wx.navigateTo({ url: "/pages/comment/comment?id=" + id });
  },
  onMapTap: function() {
    if (this.data.searchResults.length) this.setData({ searchResults: [], isSearchFocused: false });
  },
  onLocateTap: function() {
    var page = this;
    if (this.data.mode === "REVIEW") {
      if (this.locating) return;
      this.locating = true;
      this.arrivalVersion = (this.arrivalVersion || 0) + 1;
      wx.getLocation({
        type: "gcj02",
        success: function(location) {
          var latitude = Number(location.latitude);
          var longitude = Number(location.longitude);
          if (!isFinite(latitude) || !isFinite(longitude)) {
            page.locating = false;
            wx.showToast({ title: "当前位置无效", icon: "none" });
            return;
          }
          page.setData({ latitude: latitude, longitude: longitude, scale: 16,
            reviewMarkers: [makeArrivalMarker({ latitude: latitude, longitude: longitude })] });
          wx.createMapContext("myMap", page).moveToLocation({ longitude: longitude, latitude: latitude });
          cameraApi.recordReviewArrival({ latitude: latitude, longitude: longitude,
            accuracy: isFinite(Number(location.accuracy)) ? Number(location.accuracy) : null })
            .then(function() { if (page.active) wx.showToast({ title: "已记录到达位置", icon: "success" }); })
            .catch(function(error) {
              if (page.active) wx.showToast({ title: error.message || "到达记录保存失败", icon: "none" });
            })
            .then(function() { page.locating = false; });
        },
        fail: function() {
          page.locating = false;
          wx.showToast({ title: "无法获取当前位置", icon: "none" });
        }
      });
      return;
    }
    if (this.data.mode !== "LIVE") return;
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
    if (this.data.mode !== "LIVE") return;
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
    if (this.data.mode !== "LIVE") return;
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
  routePlan: function() { if (this.data.mode === "LIVE") wx.navigateTo({ url: "/pages/route/route" }); },
  openProfile: function() { if (this.data.mode === "LIVE") wx.navigateTo({ url: "/pages/profile/profile" }); },
  onShareAppMessage: function() { return { title: "小汽车嗒嗒嗒 · 探头分布", path: "/pages/index/index" }; },
  onShareTimeline: function() { return { title: "小汽车嗒嗒嗒 · 探头分布" }; }
});

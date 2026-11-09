var api = require("../../utils/jinjing-api");

function markerFor(record, selected) {
  return {
    id: Number(record.id),
    latitude: Number(record.latitude),
    longitude: Number(record.longitude),
    iconPath: "/image/brand.png",
    width: selected ? 36 : 28,
    height: selected ? 36 : 28,
    callout: {
      content: "到达 · " + api.formatBeijingTime(record.created_at),
      color: "#303640",
      bgColor: "#ffffff",
      padding: 7,
      borderRadius: 8,
      display: selected ? "ALWAYS" : "BYCLICK"
    }
  };
}

function displayRecord(record) {
  var latitude = Number(record.latitude);
  var longitude = Number(record.longitude);
  return {
    id: Number(record.id),
    latitude: latitude,
    longitude: longitude,
    createdAt: record.created_at,
    timeText: api.formatBeijingTime(record.created_at),
    coordinateText: latitude.toFixed(5) + "，" + longitude.toFixed(5)
  };
}

Page({
  data: {
    latitude: 39.909088,
    longitude: 116.397643,
    scale: 14,
    markers: [],
    records: [],
    selectedId: null,
    loading: true,
    saving: false,
    error: "",
    modeReady: false
  },
  onLoad: function() {
    this.active = true;
  },
  onShow: function() {
    var page = this;
    this.setData({ modeReady: false, markers: [], loading: true, error: "" });
    api.miniProgramMode().then(function(config) {
      if (!page.active) return;
      if (config.mode === "LIVE") {
        wx.reLaunch({ url: "/pages/index/index" });
        return;
      }
      if (config.mode !== "REVIEW") throw new Error("模式配置无效");
      getApp().globalData.recentPopupPending = false;
      page.setData({ modeReady: true });
      page.loadRecords();
    }).catch(function(error) {
      if (page.active) page.setData({ loading: false, error: error.message || "页面暂不可用" });
    });
  },
  onUnload: function() {
    this.active = false;
  },
  loadRecords: function(selectedId) {
    var page = this;
    api.reviewArrivals().then(function(rows) {
      if (!page.active || !page.data.modeReady) return;
      var records = (Array.isArray(rows) ? rows : []).map(displayRecord).filter(function(item) {
        return isFinite(item.latitude) && isFinite(item.longitude);
      });
      var id = selectedId || page.data.selectedId || (records.length ? records[0].id : null);
      var selected = records.filter(function(item) { return item.id === id; })[0] || records[0];
      page.setData({
        records: records,
        markers: records.map(function(item) { return markerFor({
          id: item.id, latitude: item.latitude, longitude: item.longitude, created_at: item.createdAt
        }, selected && item.id === selected.id); }),
        selectedId: selected ? selected.id : null,
        latitude: selected ? selected.latitude : page.data.latitude,
        longitude: selected ? selected.longitude : page.data.longitude,
        loading: false,
        error: ""
      });
    }).catch(function(error) {
      if (page.active) page.setData({ loading: false, error: error.message || "足迹加载失败" });
    });
  },
  selectRecord: function(event) {
    var id = Number(event.currentTarget.dataset.id);
    var selected = this.data.records.filter(function(item) { return item.id === id; })[0];
    if (!selected) return;
    this.setData({
      selectedId: id,
      latitude: selected.latitude,
      longitude: selected.longitude,
      scale: 16,
      markers: this.data.records.map(function(item) { return markerFor({
        id: item.id, latitude: item.latitude, longitude: item.longitude, created_at: item.createdAt
      }, item.id === id); })
    });
  },
  onMarkerTap: function(event) {
    var id = Number(event.markerId || event.detail && event.detail.markerId);
    if (id) this.selectRecord({ currentTarget: { dataset: { id: id } } });
  },
  recordCurrentLocation: function() {
    if (!this.data.modeReady || this.data.saving) return;
    var page = this;
    this.setData({ saving: true, error: "" });
    wx.getLocation({
      type: "gcj02",
      success: function(location) {
        var latitude = Number(location.latitude);
        var longitude = Number(location.longitude);
        if (!isFinite(latitude) || !isFinite(longitude) || latitude < -90 || latitude > 90 ||
            longitude < -180 || longitude > 180) {
          page.setData({ saving: false, error: "当前位置无效，请重新定位" });
          return;
        }
        page.setData({ latitude: latitude, longitude: longitude, scale: 16,
          markers: [{ id: 0, latitude: latitude, longitude: longitude,
            iconPath: "/image/brand.png", width: 36, height: 36,
            callout: { content: "当前位置 · 保存中", display: "ALWAYS" } }] });
        var accuracy = Number(location.accuracy);
        api.recordReviewArrival({ latitude: latitude, longitude: longitude,
          accuracy: isFinite(accuracy) && accuracy >= 0 && accuracy <= 5000 ? accuracy : null })
          .then(function(record) {
            if (!page.active) return;
            page.loadRecords(Number(record.id));
            wx.showToast({ title: "到达位置已记录", icon: "success" });
          }).catch(function(error) {
            if (page.active) {
              page.setData({ error: error.message || "保存失败，请重试" });
              wx.showToast({ title: "到达记录保存失败", icon: "none" });
              page.loadRecords();
            }
          }).then(function() { if (page.active) page.setData({ saving: false }); });
      },
      fail: function() {
        if (page.active) page.setData({ saving: false, error: "定位失败，请检查位置权限" });
      }
    });
  }
});

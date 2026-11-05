var api = require("../../utils/jinjing-api");
var LICENCES = ["无任何证件", "六环外进京证", "六环内进京证"];
var PREFERENCES = ["速度最快", "距离最短", "不走高速"];
var POLL_LIMIT = 120;

function toPoint(location, fallback) {
  return {
    name: location.name || fallback,
    address: location.address || location.name || fallback,
    latitude: Number(location.latitude),
    longitude: Number(location.longitude)
  };
}

function marker(point, id, iconPath) {
  return { id: id, latitude: point.latitude, longitude: point.longitude,
    iconPath: iconPath, width: 28, height: 34 };
}

Page({
  data: {
    latitude: 39.909088,
    longitude: 116.397643,
    startPoint: null,
    endPoint: null,
    waypoints: [],
    licences: LICENCES,
    preferences: PREFERENCES,
    licenceIndex: 0,
    preferenceIndex: 0,
    markers: [],
    polylines: [],
    planning: false,
    statusText: "选择起点和终点后开始规划",
    result: null
  },
  onLoad: function() {
    this.active = true;
    var page = this;
    wx.getLocation({ type: "gcj02", success: function(location) {
      page.setData({ startPoint: toPoint(location, "当前位置"),
        latitude: location.latitude, longitude: location.longitude });
      page.refreshMarkers();
    } });
  },
  onUnload: function() {
    this.active = false;
    if (this.pollTimer) clearTimeout(this.pollTimer);
  },
  choosePoint: function(kind) {
    if (this.data.planning) return;
    var page = this;
    wx.chooseLocation({
      success: function(location) {
        var point = toPoint(location, kind === "startPoint" ? "起点" : "终点");
        var update = { result: null, polylines: [], statusText: "地点已更新，请重新规划" };
        update[kind] = point;
        update.latitude = point.latitude;
        update.longitude = point.longitude;
        page.setData(update);
        page.refreshMarkers();
      },
      fail: function(error) {
        if (!String(error.errMsg || "").includes("cancel"))
          wx.showToast({ title: "无法选择地点", icon: "none" });
      }
    });
  },
  chooseStart: function() { this.choosePoint("startPoint"); },
  chooseEnd: function() { this.choosePoint("endPoint"); },
  addWaypoint: function() {
    if (this.data.planning) return;
    if (this.data.waypoints.length >= 5) {
      wx.showToast({ title: "最多添加 5 个途经点", icon: "none" });
      return;
    }
    var page = this;
    wx.chooseLocation({ success: function(location) {
      page.setData({ waypoints: page.data.waypoints.concat([toPoint(location, "途经点")]),
        result: null, polylines: [], statusText: "途经点已更新，请重新规划" });
      page.refreshMarkers();
    } });
  },
  removeWaypoint: function(event) {
    if (this.data.planning) return;
    var index = Number(event.currentTarget.dataset.index);
    this.setData({ waypoints: this.data.waypoints.filter(function(_, i) { return i !== index; }),
      result: null, polylines: [], statusText: "途经点已更新，请重新规划" });
    this.refreshMarkers();
  },
  swapPoints: function() {
    if (this.data.planning) return;
    this.setData({ startPoint: this.data.endPoint, endPoint: this.data.startPoint,
      result: null, polylines: [], statusText: "起终点已交换，请重新规划" });
    this.refreshMarkers();
  },
  changeLicence: function(event) {
    if (this.data.planning) return;
    this.setData({ licenceIndex: Number(event.detail.value), result: null, polylines: [] });
  },
  changePreference: function(event) {
    if (this.data.planning) return;
    this.setData({ preferenceIndex: Number(event.detail.value), result: null, polylines: [] });
  },
  refreshMarkers: function() {
    var markers = [];
    if (this.data.startPoint) markers.push(marker(this.data.startPoint, 1, "/image/start.png"));
    this.data.waypoints.forEach(function(point, index) {
      markers.push(marker(point, index + 2, "/image/way.png"));
    });
    if (this.data.endPoint) markers.push(marker(this.data.endPoint, 9, "/image/end.png"));
    this.setData({ markers: markers });
  },
  plan: function() {
    if (this.data.planning) return;
    if (!this.data.startPoint || !this.data.endPoint) {
      wx.showToast({ title: "请先选择起点和终点", icon: "none" });
      return;
    }
    var page = this;
    this.routeSessionId = "jlm_" + Date.now() + "_" + Math.random().toString(36).slice(2);
    this.setData({ planning: true, result: null, polylines: [], statusText: "正在规划，请稍候…" });
    api.planRoute({
      openid: this.routeSessionId,
      licence: this.data.licenceIndex + 1,
      preference: this.data.preferenceIndex + 1,
      startPoint: this.data.startPoint,
      endPoint: this.data.endPoint,
      waypoints: this.data.waypoints
    }).then(function(result) {
      if (!page.active) return;
      if (result.status === "PENDING") {
        page.setData({ statusText: "上游仍在规划，正在查询结果…" });
        page.pollResult(0);
      } else {
        page.applyResult(result);
      }
    }).catch(function(error) {
      if (!page.active) return;
      page.setData({ planning: false, statusText: error.message || "规划失败，请重试" });
      wx.showToast({ title: error.message || "规划失败", icon: "none" });
    });
  },
  pollResult: function(attempt) {
    var page = this;
    if (!this.active) return;
    if (attempt >= POLL_LIMIT) {
      this.setData({ planning: false, statusText: "规划超时，请稍后重新尝试" });
      return;
    }
    this.pollTimer = setTimeout(function() {
      api.routeResult(page.routeSessionId).then(function(response) {
        if (!page.active) return;
        if (response.isSuccess && response.result) page.applyResult(response.result);
        else page.pollResult(attempt + 1);
      }).catch(function(error) {
        if (!page.active) return;
        page.setData({ planning: false, statusText: error.message || "查询规划结果失败" });
      });
    }, 5000);
  },
  applyResult: function(result) {
    var polylines = [];
    var allPoints = [];
    (result.routePlanResponse || []).forEach(function(segment) {
      var points = segment.map(function(point) {
        return { longitude: Number(point.longitude), latitude: Number(point.latitude) };
      }).filter(function(point) { return isFinite(point.longitude) && isFinite(point.latitude); });
      if (points.length > 1) {
        polylines.push({ points: points, color: result.isSuccessOnPlanRoute ? "#2563eb" : "#e07030", width: 6 });
        allPoints = allPoints.concat(points);
      }
    });
    this.setData({ result: result, polylines: polylines, planning: false,
      statusText: result.isSuccessOnPlanRoute ? "规划完成" : "当前证件条件下不可行；下方路线仅供参考" });
    if (allPoints.length) wx.createMapContext("routeMap", this).includePoints({ points: allPoints, padding: [40, 40, 40, 40] });
  }
});

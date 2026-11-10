var api = require("../../utils/jinjing-api");
var LICENCES = ["无任何证件", "六环外进京证", "六环内进京证"];
var PREFERENCES = ["速度最快", "距离最短", "不走高速"];
var PLAN_DEADLINE_MS = 180000;

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

function routePoints(result) {
  var lines = [];
  var all = [];
  (result.routePlanResponse || []).forEach(function(segment) {
    var points = (segment || []).map(function(point) {
      return { longitude: Number(point.longitude), latitude: Number(point.latitude) };
    }).filter(function(point) {
      return isFinite(point.longitude) && isFinite(point.latitude);
    });
    if (points.length > 1) {
      lines.push(points);
      all = all.concat(points);
    }
  });
  return { lines: lines, all: all };
}

function membershipErrorText(error) {
  if (error && [401, 403, 404].includes(error.statusCode))
    return "会员服务暂不可用，请确认后端已更新";
  return "会员状态获取失败，请检查网络后重试";
}

function routeErrorText(error, key) {
  if (error && (error.statusCode === 401 || error.statusCode === 403))
    return "会员身份已失效，请返回重试";
  if (key === "routeB") {
    if (error && error.code === "AMAP_NOT_CONFIGURED") return "路线B暂不可用，请稍后重试";
    if (error && error.code === "AMAP_BUSY") return "路线B服务繁忙，请稍后重试";
    if (error && error.code === "AMAP_UPSTREAM_ERROR") return "路线B服务暂不可用";
    if (error && error.code === "NO_CAMERA_FREE_ROUTE") return "未找到可避让已收录探头的路线";
  }
  return key === "routeA" ? "路线A规划失败，请稍后重试" : "路线B规划失败，请稍后重试";
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
    routeOptions: [],
    comparisonVisible: false,
    selectedRouteIndex: 0,
    memberActive: false,
    memberLoading: true,
    memberError: false,
    membershipText: "正在查询会员权益…"
  },
  onLoad: function() {
    this.active = true;
    var page = this;
    api.requireLivePage().then(function(allowed) {
      if (!allowed || !page.active) return;
      page.loadMembership(true);
      wx.getLocation({ type: "gcj02", success: function(location) {
        if (!page.active) return;
        page.setData({ startPoint: toPoint(location, "当前位置"),
          latitude: location.latitude, longitude: location.longitude });
        page.refreshMarkers();
      } });
    });
  },
  onUnload: function() {
    this.active = false;
    if (this.pollTimer) clearTimeout(this.pollTimer);
    if (this.planTimer) clearTimeout(this.planTimer);
    this.currentRunId = (this.currentRunId || 0) + 1;
  },
  onShow: function() {
    if (!this.trialInitialized) return;
    this.loadMembership(false);
  },
  loadMembership: function(startTrial) {
    var page = this;
    if (this.active) this.setData({ memberLoading: true, memberError: false,
      membershipText: "正在查询会员权益…" });
    this.memberReady = (startTrial ? api.startRouteTrial() : api.memberMe().then(function(info) {
      return info.member;
    })).then(function(status) {
      if (startTrial) page.trialInitialized = true;
      if (page.active) page.setData({ memberActive: !!status.active, memberLoading: false,
        memberError: false, membershipText: status.active
          ? (status.vipExpiresAt ? "会员有效期至 " : "两天免费试用至 ") + api.formatBeijingTime(status.expiresAt)
          : "试用已结束，开通会员后可规划路线" });
      return status;
    }, function(error) {
      var message = membershipErrorText(error);
      if (page.active) page.setData({ memberActive: false, memberLoading: false,
        memberError: true, membershipText: message });
      return { active: false, error: message };
    });
    return this.memberReady;
  },
  handleMemberBanner: function() {
    if (this.data.memberError) this.loadMembership(!this.trialInitialized);
    else this.openMembership();
  },
  clearResults: function() {
    this.routeBoundsPoints = [];
    this.setData({ routeOptions: [], comparisonVisible: false, polylines: [] });
  },
  choosePoint: function(kind) {
    if (this.data.planning) return;
    var page = this;
    var center = this.data[kind] || this.data.startPoint ||
      { latitude: this.data.latitude, longitude: this.data.longitude };
    wx.navigateTo({
      url: "/pages/place-picker/place-picker?kind=" + kind +
        "&latitude=" + encodeURIComponent(center.latitude) +
        "&longitude=" + encodeURIComponent(center.longitude),
      success: function(navigation) {
        navigation.eventChannel.on("placeSelected", function(point) {
          if (!page.active) return;
          page.applySelectedPoint(kind, toPoint(point, "地图选点"));
        });
      }
    });
  },
  applySelectedPoint: function(kind, point) {
    var update = { statusText: "地点已更新，请重新规划" };
    if (kind === "waypoint") {
      if (this.data.waypoints.length >= 5) return;
      update.waypoints = this.data.waypoints.concat([point]);
    } else {
      update[kind] = point;
      update.latitude = point.latitude;
      update.longitude = point.longitude;
    }
    this.setData(update);
    this.clearResults();
    this.refreshMarkers();
  },
  chooseStart: function() { this.choosePoint("startPoint"); },
  chooseEnd: function() { this.choosePoint("endPoint"); },
  addWaypoint: function() {
    if (this.data.planning) return;
    if (this.data.waypoints.length >= 5) {
      wx.showToast({ title: "最多添加 5 个途经点", icon: "none" });
      return;
    }
    this.choosePoint("waypoint");
  },
  removeWaypoint: function(event) {
    if (this.data.planning) return;
    var index = Number(event.currentTarget.dataset.index);
    this.setData({ waypoints: this.data.waypoints.filter(function(_, i) { return i !== index; }),
      statusText: "途经点已更新，请重新规划" });
    this.clearResults();
    this.refreshMarkers();
  },
  swapPoints: function() {
    if (this.data.planning) return;
    this.setData({ startPoint: this.data.endPoint, endPoint: this.data.startPoint,
      statusText: "起终点已交换，请重新规划" });
    this.clearResults();
    this.refreshMarkers();
  },
  changeLicence: function(event) {
    if (this.data.planning) return;
    this.setData({ licenceIndex: Number(event.detail.value) });
    this.clearResults();
  },
  changePreference: function(event) {
    if (this.data.planning) return;
    this.setData({ preferenceIndex: Number(event.detail.value) });
    this.clearResults();
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
    if (this.data.memberError) {
      this.loadMembership(!this.trialInitialized);
      return;
    }
    if (!this.data.startPoint || !this.data.endPoint) {
      wx.showToast({ title: "请先选择起点和终点", icon: "none" });
      return;
    }
    var page = this;
    Promise.resolve(this.memberReady).then(function(status) {
      if (status && status.error) { wx.showToast({ title: status.error, icon: "none" }); return; }
      if (!status || !status.active) { page.openMembership(); return; }
      if (page.active) page.startPlanning();
    }, function(error) { wx.showToast({ title: error.message || "请先登录", icon: "none" }); });
  },
  openMembership: function() { wx.navigateTo({ url: "/pages/membership/membership" }); },
  startPlanning: function() {
    var page = this;
    var runId = (this.currentRunId || 0) + 1;
    this.currentRunId = runId;
    var deadline = Date.now() + PLAN_DEADLINE_MS;
    var common = {
      preference: this.data.preferenceIndex + 1,
      startPoint: this.data.startPoint,
      endPoint: this.data.endPoint,
      waypoints: this.data.waypoints
    };
    var outcomes = { routeA: null, routeB: null };
    this.setData({ planning: true, routeOptions: [], comparisonVisible: false,
      polylines: [], statusText: "两条路线正在同时规划，请稍候…" });
    function settled(key, promise) {
      return promise.then(function(result) {
        outcomes[key] = { result: result };
      }, function(error) {
        outcomes[key] = { error: routeErrorText(error, key) };
      });
    }
    var routeA = api.planRoute({
      licence: this.data.licenceIndex + 1,
      preference: common.preference,
      startPoint: common.startPoint,
      endPoint: common.endPoint,
      waypoints: common.waypoints
    }).then(function(result) {
      return result.status === "PENDING" ? page.pollResult(result.requestId, deadline, runId) : result;
    });
    var both = Promise.all([
      settled("routeA", routeA),
      settled("routeB", api.planAmapRoute(common))
    ]);
    var timeout = new Promise(function(resolve) {
      page.planTimer = setTimeout(resolve, PLAN_DEADLINE_MS);
    });
    Promise.race([both, timeout]).then(function() {
      if (!page.active || page.currentRunId !== runId) return;
      clearTimeout(page.planTimer);
      if (page.pollTimer) clearTimeout(page.pollTimer);
      page.showComparison(outcomes);
    });
  },
  pollResult: function(sessionId, deadline, runId) {
    var page = this;
    return new Promise(function(resolve, reject) {
      function next() {
        if (!page.active || page.currentRunId !== runId || Date.now() >= deadline) {
          reject(new Error("规划超时"));
          return;
        }
        page.pollTimer = setTimeout(function() {
          api.routeResult(sessionId).then(function(response) {
            if (response.isSuccess && response.result) resolve(response.result);
            else next();
          }, reject);
        }, 5000);
      }
      next();
    });
  },
  showComparison: function(outcomes) {
    var sources = [
      { key: "routeA", title: "路线A", color: "#2563eb",
        note: "按所选进京证条件规划；未验证是否避开本项目探头。" },
      { key: "routeB", title: "路线B", color: "#16856a",
        note: "仅避让已收录探头，不判断进京证条件；路线仅供参考。" }
    ];
    var polylines = [];
    var allPoints = [];
    var options = sources.map(function(source) {
      var outcome = outcomes[source.key];
      if (!outcome) return { key: source.key, title: source.title, color: source.color,
        note: source.note, status: "规划超时", steps: [] };
      if (outcome.error) return { key: source.key, title: source.title, color: source.color,
        note: source.note, status: outcome.error, steps: [] };
      var result = outcome.result;
      var reference = source.key === "routeA" && !result.isSuccessOnPlanRoute;
      var shape = routePoints(result);
      var lineColor = reference ? "#9099a5" : source.color;
      shape.lines.forEach(function(points) {
        polylines.push({ points: points, color: lineColor, width: 6, dottedLine: reference });
        allPoints = allPoints.concat(points);
      });
      return { key: source.key, title: source.title, color: lineColor, note: source.note,
        status: reference ? "证件条件下不可行，线路仅供参考" :
          source.key === "routeB" ? "已避开已收录探头" : "规划完成",
        distance: reference ? "" : result.distance,
        useTime: reference ? "" : result.useTime,
        steps: (result.navigationSteps || []).map(function(step, index) {
          return { id: source.key + "-" + index, instruction: step.instruction || "继续行驶" };
        }), hasRoute: shape.lines.length > 0 };
    });
    this.routeBoundsPoints = allPoints;
    this.setData({ routeOptions: options, polylines: polylines, planning: false,
      selectedRouteIndex: 0, comparisonVisible: true,
      statusText: options.some(function(option) { return option.hasRoute; }) ?
        "规划结束，点击查看路线对比" : "两条路线均未规划成功" });
  },
  selectResult: function(event) {
    this.setData({ selectedRouteIndex: Number(event.currentTarget.dataset.index) });
  },
  openComparison: function() {
    if (this.data.routeOptions.length) this.setData({ comparisonVisible: true });
  },
  closeComparison: function() {
    var page = this;
    this.setData({ comparisonVisible: false }, function() {
      if (page.active && page.routeBoundsPoints && page.routeBoundsPoints.length) {
        wx.createMapContext("routeMap", page).includePoints({
          points: page.routeBoundsPoints, padding: [40, 40, 40, 40]
        });
      }
    });
  },
  noop: function() {
    return;
  }
});

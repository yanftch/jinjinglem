var ROUTE_MAP_PAYLOAD_KEY = "jinjing_route_map_payload";
var CAMERA_ROUTE_BUFFER_METERS = 200;
var api = require("../../utils/jinjing-api");

function iconForType(type) {
  if (type === "DISCARD") return "/image/fq.png";
  if (type === "SIX_RING_OUT") return "/image/locationSixRing.png";
  if (type === "TWO_RING_IN") return "/image/locationTwoRing.png";
  return "/image/location.png";
}

function distanceToSegmentMeters(point, start, end) {
  var latitude = (point.latitude + start.latitude + end.latitude) / 3 * Math.PI / 180;
  var metersPerDegreeX = 111320 * Math.cos(latitude);
  var metersPerDegreeY = 110540;
  var px = point.longitude * metersPerDegreeX;
  var py = point.latitude * metersPerDegreeY;
  var sx = start.longitude * metersPerDegreeX;
  var sy = start.latitude * metersPerDegreeY;
  var ex = end.longitude * metersPerDegreeX;
  var ey = end.latitude * metersPerDegreeY;
  var dx = ex - sx;
  var dy = ey - sy;
  var lengthSquared = dx * dx + dy * dy;
  var ratio = lengthSquared ? ((px - sx) * dx + (py - sy) * dy) / lengthSquared : 0;
  ratio = Math.max(0, Math.min(1, ratio));
  var nearestX = sx + ratio * dx;
  var nearestY = sy + ratio * dy;
  var offsetX = px - nearestX;
  var offsetY = py - nearestY;
  return Math.sqrt(offsetX * offsetX + offsetY * offsetY);
}

function cameraIsAlongRoute(camera, lines) {
  var point = { longitude: camera.position[0], latitude: camera.position[1] };
  return lines.some(function(line) {
    for (var i = 1; i < line.points.length; i++) {
      if (distanceToSegmentMeters(point, line.points[i - 1], line.points[i]) <= CAMERA_ROUTE_BUFFER_METERS) return true;
    }
    return false;
  });
}

Page({
  data: {
    latitude: 39.909088,
    longitude: 116.397643,
    markers: [],
    polylines: [],
    routeOptions: [],
    selectedRouteIndex: 0,
    cameraCount: 0
  },
  onLoad: function() {
    var payload = wx.getStorageSync(ROUTE_MAP_PAYLOAD_KEY);
    if (!payload || !Array.isArray(payload.polylines)) {
      wx.showToast({ title: "路线数据已失效，请重新规划", icon: "none" });
      return;
    }
    this.routePoints = payload.points || [];
    this.allPolylines = payload.polylines;
    this.baseMarkers = payload.markers || [];
    this.setData({ latitude: payload.latitude, longitude: payload.longitude,
      markers: this.baseMarkers, polylines: payload.polylines,
      routeOptions: payload.routeOptions || [] });
    this.loadRouteCameras();
  },
  loadRouteCameras: function() {
    var page = this;
    api.loadAllCameras().then(function(cameras) {
      page.allCameras = cameras;
      page.updateRouteCameraMarkers(page.data.polylines);
    }).catch(function() {
      wx.showToast({ title: "沿线探头加载失败，请检查网络", icon: "none" });
    });
  },
  updateRouteCameraMarkers: function(lines) {
    var baseMarkers = this.baseMarkers || [];
    var alongRoute = (this.allCameras || []).filter(function(camera) {
      return cameraIsAlongRoute(camera, lines || []);
    });
    var cameraMarkers = alongRoute.map(function(camera, index) {
      return { id: 1000000 + index, latitude: camera.position[1], longitude: camera.position[0],
        iconPath: iconForType(camera.cameraType), width: 20, height: 28,
        callout: { content: camera.name + " · 沿线探头", color: "#202830", bgColor: "#ffffff",
          padding: 6, borderRadius: 5, display: "BYCLICK" } };
    });
    this.setData({ markers: baseMarkers.concat(cameraMarkers), cameraCount: cameraMarkers.length });
  },
  onReady: function() {
    if (this.routePoints && this.routePoints.length) {
      wx.createMapContext("fullRouteMap", this).includePoints({
        points: this.routePoints, padding: [100, 50, 250, 50]
      });
    }
  },
  selectRoute: function(event) {
    var index = Number(event.currentTarget.dataset.index);
    var option = this.data.routeOptions[index];
    var color = option && option.color;
    var lines = (this.allPolylines || []).filter(function(line) { return line.color === color; });
    this.setData({ selectedRouteIndex: index, polylines: lines });
    this.updateRouteCameraMarkers(lines);
  },
  noop: function() {}
});

var STORAGE_KEY = "route_location_history_v1";
var MAX_COUNT = 10;

function valid(point) {
  return point && point.latitude !== "" && point.longitude !== "" &&
    isFinite(Number(point.latitude)) && isFinite(Number(point.longitude)) &&
    Math.abs(Number(point.latitude)) <= 90 && Math.abs(Number(point.longitude)) <= 180;
}

function normalize(point) {
  return {
    name: String(point.name || point.address || "地图选点"),
    address: String(point.address || point.name || ""),
    latitude: Number(point.latitude),
    longitude: Number(point.longitude)
  };
}

function sameLocation(left, right) {
  return left.latitude.toFixed(6) === right.latitude.toFixed(6) &&
    left.longitude.toFixed(6) === right.longitude.toFixed(6);
}

function read() {
  try {
    var saved = wx.getStorageSync(STORAGE_KEY);
    return Array.isArray(saved) ? saved.filter(valid).map(normalize).slice(0, MAX_COUNT) : [];
  } catch (_) {
    return [];
  }
}

function remember(point) {
  if (!valid(point)) return;
  var latest = normalize(point);
  var history = [latest].concat(read().filter(function(item) {
    return !sameLocation(item, latest);
  })).slice(0, MAX_COUNT);
  wx.setStorageSync(STORAGE_KEY, history);
}

module.exports = { read: read, remember: remember };

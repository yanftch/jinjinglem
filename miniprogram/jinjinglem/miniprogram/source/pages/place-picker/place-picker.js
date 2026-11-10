var api = require("../../utils/jinjing-api");
var history = require("../../utils/place-history");

function validCoordinate(latitude, longitude) {
  return isFinite(latitude) && isFinite(longitude) &&
    Math.abs(latitude) <= 90 && Math.abs(longitude) <= 180;
}

function distanceText(fromLatitude, fromLongitude, toLatitude, toLongitude) {
  if (!validCoordinate(fromLatitude, fromLongitude)) return "";
  var radians = Math.PI / 180;
  var deltaLatitude = (toLatitude - fromLatitude) * radians;
  var deltaLongitude = (toLongitude - fromLongitude) * radians;
  var a = Math.sin(deltaLatitude / 2) ** 2 +
    Math.cos(fromLatitude * radians) * Math.cos(toLatitude * radians) *
    Math.sin(deltaLongitude / 2) ** 2;
  var meters = 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return meters < 1000 ? Math.round(meters) + "米" : (meters / 1000).toFixed(1) + "公里";
}

function pointMarker(point) {
  return [{ id: 1, latitude: point.latitude, longitude: point.longitude,
    iconPath: "/image/way.png", width: 28, height: 34 }];
}

Page({
  data: {
    title: "选择地点",
    latitude: 39.909088,
    longitude: 116.397643,
    markers: [],
    keyword: "",
    searchMode: false,
    searching: false,
    searchError: "",
    results: [],
    recentPlaces: [],
    mapPicked: null
  },
  onLoad: function(options) {
    this.active = true;
    this.requestSerial = 0;
    var labels = { startPoint: "选择起点", endPoint: "选择终点", waypoint: "选择途经点" };
    var latitude = Number(options.latitude);
    var longitude = Number(options.longitude);
    var update = { title: labels[options.kind] || "选择地点", recentPlaces: history.read() };
    if (validCoordinate(latitude, longitude)) {
      update.latitude = latitude;
      update.longitude = longitude;
      this.originLatitude = latitude;
      this.originLongitude = longitude;
    }
    this.setData(update);
    wx.setNavigationBarTitle({ title: update.title });
  },
  onUnload: function() {
    this.active = false;
    this.requestSerial++;
    if (this.searchTimer) clearTimeout(this.searchTimer);
  },
  onSearchInput: function(event) {
    var keyword = String(event.detail.value || "");
    var query = keyword.trim();
    this.requestSerial++;
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.setData({ keyword: keyword, searchMode: !!query, searching: false,
      searchError: "", results: [], mapPicked: null, markers: [] });
    if (query.length < 2) return;
    var page = this;
    var serial = this.requestSerial;
    this.searchTimer = setTimeout(function() { page.search(query, serial); }, 350);
  },
  onSearchConfirm: function() {
    var query = this.data.keyword.trim();
    if (query.length < 2) return;
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.requestSerial++;
    this.search(query, this.requestSerial);
  },
  clearSearch: function() {
    this.requestSerial++;
    if (this.searchTimer) clearTimeout(this.searchTimer);
    this.setData({ keyword: "", searchMode: false, searching: false,
      searchError: "", results: [], recentPlaces: history.read(), mapPicked: null, markers: [] });
  },
  search: function(query, serial) {
    var page = this;
    this.setData({ searching: true, searchError: "" });
    api.searchPlaces(query).then(function(places) {
      if (!page.active || page.requestSerial !== serial) return;
      var results = (Array.isArray(places) ? places : []).map(function(place) {
        var latitude = Number(place.latitude);
        var longitude = Number(place.longitude);
        return {
          id: place.id,
          name: place.name,
          address: [place.district, place.address].filter(Boolean).join(" · "),
          latitude: latitude,
          longitude: longitude,
          distance: distanceText(page.originLatitude, page.originLongitude, latitude, longitude)
        };
      }).filter(function(place) {
        return validCoordinate(place.latitude, place.longitude);
      });
      var update = { results: results, searching: false };
      if (results.length) {
        update.latitude = results[0].latitude;
        update.longitude = results[0].longitude;
        update.markers = pointMarker(results[0]);
      }
      page.setData(update);
    }).catch(function(error) {
      if (!page.active || page.requestSerial !== serial) return;
      page.setData({ searching: false, results: [],
        searchError: error && error.message || "搜索失败，请重试" });
    });
  },
  selectResult: function(event) {
    var place = this.data.results[Number(event.currentTarget.dataset.index)];
    if (place) this.finish(place);
  },
  selectRecent: function(event) {
    var place = this.data.recentPlaces[Number(event.currentTarget.dataset.index)];
    if (place) this.finish(place);
  },
  onMapTap: function(event) {
    var latitude = Number(event.detail.latitude);
    var longitude = Number(event.detail.longitude);
    if (!validCoordinate(latitude, longitude)) return;
    var point = { name: "地图选点", address: latitude.toFixed(6) + ", " + longitude.toFixed(6),
      latitude: latitude, longitude: longitude };
    this.setData({ mapPicked: point, markers: pointMarker(point) });
  },
  selectMapPoint: function() {
    if (this.data.mapPicked) this.finish(this.data.mapPicked);
  },
  locate: function() {
    var page = this;
    wx.getLocation({ type: "gcj02", success: function(location) {
      if (!page.active) return;
      var point = { name: "当前位置", address: "当前位置",
        latitude: Number(location.latitude), longitude: Number(location.longitude) };
      page.setData({ latitude: point.latitude, longitude: point.longitude,
        mapPicked: point, markers: pointMarker(point) });
    }, fail: function() { wx.showToast({ title: "定位失败", icon: "none" }); } });
  },
  finish: function(place) {
    if (!this.active) return;
    this.active = false;
    var point = { name: place.name, address: place.address || place.name,
      latitude: Number(place.latitude), longitude: Number(place.longitude) };
    try { history.remember(point); } catch (_) {
      wx.showToast({ title: "历史地点保存失败", icon: "none" });
    }
    this.getOpenerEventChannel().emit("placeSelected", point);
    wx.navigateBack();
  }
});

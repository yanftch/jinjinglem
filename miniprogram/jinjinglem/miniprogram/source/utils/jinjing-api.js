// 进京了么公开接口。小程序后台需将此域名加入 request 合法域名。
var BASE_URL = "https://jinjinglem.yixiangshe.com";
var PAGE_SIZE = 500;

function request(path, options) {
  options = options || {};
  return new Promise(function(resolve, reject) {
    wx.request({
      url: BASE_URL + path,
      method: options.method || "GET",
      data: options.data,
      header: options.data ? { "content-type": "application/json" } : {},
      timeout: options.timeout || 20000,
      success: function(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data);
        } else {
          var error = new Error(response.data && response.data.error || "服务请求失败（" + response.statusCode + "）");
          error.statusCode = response.statusCode;
          reject(error);
        }
      },
      fail: function(error) {
        reject(new Error(error.errMsg || "网络连接失败"));
      }
    });
  });
}

function toMapCamera(camera) {
  var longitude = Number(camera.longitude);
  var latitude = Number(camera.latitude);
  if (camera.coordinate_system !== "GCJ02" || !isFinite(longitude) || !isFinite(latitude)) return null;
  return {
    id: Number(camera.id),
    name: camera.name,
    position: [longitude, latitude],
    cameraType: camera.camera_type
  };
}

function loadAllCameras() {
  return request("/api/v1/cameras/count").then(function(count) {
    var offsets = [];
    for (var offset = 0; offset < Number(count.total || 0); offset += PAGE_SIZE) offsets.push(offset);
    var rows = [];
    function loadBatch(index) {
      if (index >= offsets.length) return Promise.resolve(rows);
      return Promise.all(offsets.slice(index, index + 4).map(function(offset) {
        return request("/api/v1/cameras?offset=" + offset + "&limit=" + PAGE_SIZE);
      })).then(function(pages) {
        pages.forEach(function(page) { rows = rows.concat(page); });
        return loadBatch(index + 4);
      });
    }
    return loadBatch(0);
  }).then(function(rows) {
    var seen = {};
    return rows.map(toMapCamera).filter(function(camera) {
      if (!camera || seen[camera.id]) return false;
      seen[camera.id] = true;
      return true;
    });
  });
}

function cameraDetail(id) {
  return request("/api/v1/cameras/" + encodeURIComponent(String(id)));
}

function cameraComments(id) {
  var comments = [];
  function next(offset) {
    return request("/api/v1/cameras/" + encodeURIComponent(String(id)) + "/comments?offset=" + offset + "&limit=" + PAGE_SIZE)
      .then(function(page) {
        comments = comments.concat(page);
        return page.length === PAGE_SIZE ? next(offset + PAGE_SIZE) : comments;
      });
  }
  return next(0);
}

function cameraImages(id) {
  return request("/api/v1/cameras/" + encodeURIComponent(String(id)) + "/images");
}

function addComment(id, nickname, content) {
  return request("/api/v1/cameras/" + encodeURIComponent(String(id)) + "/comments", {
    method: "POST",
    data: { nickname: nickname, content: content }
  });
}

function planRoute(route) {
  return request("/api/v1/routes/plan", { method: "POST", data: route, timeout: 180000 });
}

function routeResult(openid) {
  return request("/api/v1/routes/result?openid=" + encodeURIComponent(openid), { timeout: 30000 });
}

module.exports = {
  loadAllCameras: loadAllCameras,
  cameraDetail: cameraDetail,
  cameraComments: cameraComments,
  cameraImages: cameraImages,
  addComment: addComment,
  planRoute: planRoute,
  routeResult: routeResult
};

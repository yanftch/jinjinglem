// 进京了公开接口。小程序后台需将此域名加入 request 合法域名。
var BASE_URL = "https://jinjingle.yixiangshe.com";
var PAGE_SIZE = 500;
var MEMBER_TOKEN_KEY = "jinjingle_member_token";
var loginPromise;

function wxCode() {
  return new Promise(function(resolve, reject) {
    wx.login({ success: function(result) { result.code ? resolve(result.code) : reject(new Error("微信登录失败")); },
      fail: function() { reject(new Error("微信登录失败")); } });
  });
}

function memberToken() {
  try { return wx.getStorageSync(MEMBER_TOKEN_KEY) || ""; } catch (_) { return ""; }
}

function formatBeijingTime(value) {
  if (!value) return "—";
  var time = new Date(value);
  if (!isFinite(time.getTime())) return "—";
  var local = new Date(time.getTime() + 8 * 60 * 60 * 1000);
  function two(number) { return number < 10 ? "0" + number : String(number); }
  return local.getUTCFullYear() + "-" + two(local.getUTCMonth() + 1) + "-" + two(local.getUTCDate()) +
    " " + two(local.getUTCHours()) + ":" + two(local.getUTCMinutes());
}

function memberRequest(path, options) {
  options = options || {};
  var token = memberToken();
  var headers = { "content-type": "application/json" };
  if (token) headers.Authorization = "Bearer " + token;
  return request(path, Object.assign({}, options, { header: headers }));
}

function memberLogin() {
  if (loginPromise) return loginPromise;
  loginPromise = wxCode().then(function(code) {
    return request("/api/v1/members/wechat-login", { method: "POST", data: { code: code } });
  }).then(function(result) {
    wx.setStorageSync(MEMBER_TOKEN_KEY, result.token);
    return result.member;
  });
  return loginPromise.then(function(value) { loginPromise = null; return value; },
    function(error) { loginPromise = null; throw error; });
}

function memberMe() {
  return memberToken() ? memberRequest("/api/v1/members/me").catch(function(error) {
    if (error.statusCode !== 401) throw error;
    return memberLogin().then(function() { return memberRequest("/api/v1/members/me"); });
  }) : memberLogin().then(function() { return memberRequest("/api/v1/members/me"); });
}

function startRouteTrial() {
  return memberMe().then(function() { return memberRequest("/api/v1/members/trial", { method: "POST" }); });
}

function buyMembership(plan) {
  return memberMe().then(wxCode).then(function(code) {
    return memberRequest("/api/v1/members/orders", { method: "POST", data: { plan: plan, code: code } });
  });
}

function appLoginCode() {
  return memberMe().then(function() { return memberRequest("/api/v1/members/app-code", { method: "POST" }); });
}

function request(path, options) {
  options = options || {};
  return new Promise(function(resolve, reject) {
    wx.request({
      url: BASE_URL + path,
      method: options.method || "GET",
      data: options.data,
      header: options.header || (options.data ? { "content-type": "application/json" } : {}),
      timeout: options.timeout || 20000,
      success: function(response) {
        if (response.statusCode >= 200 && response.statusCode < 300) {
          resolve(response.data);
        } else {
          var error = new Error(response.data && response.data.error || "服务请求失败（" + response.statusCode + "）");
          error.statusCode = response.statusCode;
          error.code = response.data && response.data.code;
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

function latestCameras() {
  return request("/api/v1/cameras?offset=0&limit=20");
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
  return request("/api/v1/cameras/" + encodeURIComponent(String(id)) + "/images").then(function(images) {
    return images.map(function(image) {
      if (image.url && image.url.charAt(0) === "/" && image.url.charAt(1) !== "/") {
        return Object.assign({}, image, { url: BASE_URL + image.url });
      }
      return image;
    });
  });
}

function addComment(id, nickname, content) {
  return request("/api/v1/cameras/" + encodeURIComponent(String(id)) + "/comments", {
    method: "POST",
    data: { nickname: nickname, content: content }
  });
}

function planRoute(route) {
  return memberRequest("/api/v1/routes/plan", { method: "POST", data: route, timeout: 180000 });
}

function planAmapRoute(route) {
  return memberRequest("/api/v1/routes/amap/plan", { method: "POST", data: route, timeout: 120000 });
}

function routeResult(openid) {
  return memberRequest("/api/v1/routes/result?openid=" + encodeURIComponent(openid), { timeout: 30000 });
}

function submitCameraReport(nickname, content, screenshotPath) {
  if (!screenshotPath) {
    return request("/api/v1/camera-reports", {
      method: "POST", data: { nickname: nickname, content: content },
      header: { "content-type": "application/x-www-form-urlencoded" }, timeout: 30000
    });
  }
  return new Promise(function(resolve, reject) {
    wx.uploadFile({
      url: BASE_URL + "/api/v1/camera-reports",
      filePath: screenshotPath,
      name: "screenshot",
      formData: { nickname: nickname, content: content },
      timeout: 30000,
      success: function(response) {
        var data;
        try { data = JSON.parse(response.data); } catch (_) { data = null; }
        if (response.statusCode >= 200 && response.statusCode < 300 && data) resolve(data);
        else reject(new Error(data && data.error || "上报失败（" + response.statusCode + "）"));
      },
      fail: function(error) { reject(new Error(error.errMsg || "截图上传失败")); }
    });
  });
}

module.exports = {
  loadAllCameras: loadAllCameras,
  latestCameras: latestCameras,
  cameraDetail: cameraDetail,
  cameraComments: cameraComments,
  cameraImages: cameraImages,
  addComment: addComment,
  planRoute: planRoute,
  planAmapRoute: planAmapRoute,
  routeResult: routeResult,
  memberLogin: memberLogin,
  memberMe: memberMe,
  startRouteTrial: startRouteTrial,
  buyMembership: buyMembership,
  appLoginCode: appLoginCode,
  formatBeijingTime: formatBeijingTime,
  submitCameraReport: submitCameraReport
};

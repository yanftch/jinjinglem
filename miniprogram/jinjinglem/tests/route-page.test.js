const assert = require("node:assert/strict");
const test = require("node:test");

function createPage(request, memberRequest) {
  global.Page = definition => { global.routePageDefinition = definition; };
  let included = [];
  const storage = {};
  const navigations = [];
  global.wx = {
    login(options) { options.success({ code: "wx-test-code" }); },
    getStorageSync(key) { return key === "jinjingle_member_token" ? "test-token" : storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    navigateTo(options) { navigations.push(options.url); },
    getLocation(options) { options.success({ latitude: 39.9, longitude: 116.4 }); },
    chooseLocation(options) { options.success({ name: "终点", address: "终点", latitude: 40, longitude: 116.5 }); },
    createMapContext() { return { includePoints(options) { included = options.points; } }; },
    showToast() {},
    request(options) {
      if (memberRequest && options.url.includes("/members/")) return memberRequest(options);
      if (options.url.endsWith("/members/me")) return options.success({ statusCode: 200,
        data: { member: { active: true, expiresAt: "2026-11-08T00:00:00" }, purchaseAvailable: false, orders: [] } });
      if (options.url.endsWith("/members/trial")) return options.success({ statusCode: 200,
        data: { active: true, expiresAt: "2026-11-08T00:00:00" } });
      request(options);
    }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/route/route")];
  require("../miniprogram/source/pages/route/route");
  const page = { ...global.routePageDefinition, data: { ...global.routePageDefinition.data },
    setData(values, done) { Object.assign(this.data, values); if (done) done(); } };
  page.onLoad();
  page.chooseEnd();
  return { page, included: () => included, storage, navigations };
}

test("shows a retry state instead of a purchase prompt when member login is unavailable", async () => {
  let available = false;
  let routeRequests = 0;
  const { page } = createPage(() => { routeRequests++; }, options => {
    if (!available) return options.success({ statusCode: 401, data: { error: "Unauthorized" } });
    if (options.url.endsWith("/members/me")) return options.success({ statusCode: 200,
      data: { member: { active: false }, purchaseAvailable: false, orders: [] } });
    options.success({ statusCode: 200, data: { active: true, expiresAt: "2026-11-08T00:00:00Z" } });
  });
  await page.memberReady;
  assert.equal(page.data.memberError, true);
  assert.equal(page.data.memberActive, false);
  assert.match(page.data.membershipText, /后端已更新/);
  assert.equal(page.trialInitialized, undefined);

  available = true;
  page.plan();
  await page.memberReady;
  assert.equal(page.data.memberError, false);
  assert.equal(page.data.memberActive, true);
  assert.equal(page.trialInitialized, true);
  assert.equal(routeRequests, 0);
  page.onUnload();
});

function line(latitude) {
  return [{ longitude: "116.4", latitude: String(latitude) },
    { longitude: "116.5", latitude: String(latitude) }];
}

test("route page requests both providers and compares two map lines", async () => {
  const urls = [];
  const { page, included } = createPage(options => {
    urls.push(options.url);
    assert.equal(options.data.startPoint.latitude, 39.9);
    if (options.url.endsWith("/routes/plan")) {
      assert.equal(options.data.licence, 1);
      assert.deepEqual(options.data.waypoints, []);
      options.success({ statusCode: 200, data: {
        isSuccessOnPlanRoute: true, distance: "2km", useTime: "3分钟",
        routePlanResponse: [line(39.9)], navigationSteps: [{ instruction: "直行" }]
      } });
    } else {
      assert.equal(options.url, "https://jinjingle.yixiangshe.com/api/v1/routes/amap/plan");
      assert.equal(options.data.licence, undefined);
      options.success({ statusCode: 200, data: {
        provider: "AMAP", avoidsKnownCameras: true, distance: "2.5 公里",
        useTime: "5 分钟", routePlanResponse: [line(39.91)],
        navigationSteps: [{ instruction: "绕行" }]
      } });
    }
  });
  await page.memberReady;
  page.plan();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(urls.length, 2);
  assert.equal(page.data.routeOptions[0].distance, "2km");
  assert.equal(page.data.routeOptions[1].distance, "2.5 公里");
  assert.equal(page.data.polylines.length, 2);
  assert.equal(page.data.comparisonVisible, true);
  page.closeComparison();
  assert.equal(included().length, 4);
  page.openComparison();
  assert.equal(page.data.comparisonVisible, true);
  page.onUnload();
});

test("route form defaults to no permit and temporarily hides waypoints and permit picker", () => {
  const fs = require("node:fs");
  const path = require("node:path");
  const markup = fs.readFileSync(path.join(__dirname, "../miniprogram/source/pages/route/route.wxml"), "utf8");
  const script = fs.readFileSync(path.join(__dirname, "../miniprogram/source/pages/route/route.js"), "utf8");
  assert.match(markup, /持有证件<\/text><text class="field-value">无任何证件/);
  assert.doesNotMatch(markup, /添加途经点|wx:for="\{\{waypoints\}\}"|<picker[^>]*licence/);
  assert.doesNotMatch(script, /addWaypoint|removeWaypoint|changeLicence/);
  assert.match(script, /licence: 1,/);
});

test("successful planning shows per-member map guidance at most ten times and opens full map", () => {
  const { page, storage, navigations } = createPage(() => {});
  page.data.memberNumber = "MEMBER123";
  const outcomes = {
    routeA: { result: { isSuccessOnPlanRoute: true, routePlanResponse: [line(39.9)], navigationSteps: [] } },
    routeB: { error: "路线B规划失败" }
  };
  for (let count = 0; count < 11; count++) {
    page.showComparison(outcomes);
    assert.equal(page.data.showMapGuide, count < 10);
    page.closeComparison();
    if (count < 10) page.openFullMap();
  }
  assert.equal(storage.jinjing_route_map_guide_count_MEMBER123, 10);
  assert.equal(navigations.length, 10);
  assert.ok(navigations.every(url => url === "/pages/route-map/route-map"));
  assert.ok(storage.jinjing_route_map_payload.polylines.length);
  page.onUnload();
});

test("full route map adds nearby cameras as markers and updates them with selected route", async () => {
  const payloadKey = "jinjing_route_map_payload";
  const segmentA = { points: [
    { longitude: 116.4, latitude: 39.9 },
    { longitude: 116.5, latitude: 39.9 }
  ], color: "#2563eb" };
  const segmentB = { points: [
    { longitude: 116.4, latitude: 39.91 },
    { longitude: 116.5, latitude: 39.91 }
  ], color: "#16856a" };
  const storage = { [payloadKey]: {
    latitude: 39.9, longitude: 116.4,
    markers: [{ id: 1, latitude: 39.9, longitude: 116.4 }],
    polylines: [segmentA, segmentB], points: segmentA.points.concat(segmentB.points),
    routeOptions: [{ key: "routeA", color: "#2563eb" }, { key: "routeB", color: "#16856a" }]
  } };
  global.Page = definition => { global.fullRouteMapDefinition = definition; };
  global.wx = {
    getStorageSync(key) { return storage[key]; },
    request(options) {
      if (options.url.endsWith("/cameras/count")) return options.success({ statusCode: 200, data: { total: 3 } });
      return options.success({ statusCode: 200, data: [
        { id: 41, name: "沿线探头", longitude: 116.45, latitude: 39.9004, coordinate_system: "GCJ02", camera_type: "SIX_RING_IN" },
        { id: 42, name: "非沿线探头", longitude: 117, latitude: 39.9, coordinate_system: "GCJ02", camera_type: "SIX_RING_IN" },
        { id: 43, name: "其他路线探头", longitude: 116.45, latitude: 39.9104, coordinate_system: "GCJ02", camera_type: "SIX_RING_OUT" }
      ] });
    },
    showToast() {},
    createMapContext() { return { includePoints() {} }; }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/route-map/route-map")];
  require("../miniprogram/source/pages/route-map/route-map");
  const page = { ...global.fullRouteMapDefinition, data: { ...global.fullRouteMapDefinition.data },
    setData(values) { Object.assign(this.data, values); } };
  page.onLoad();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(page.data.markers.map(marker => marker.id), [1, 1000000, 1000001]);
  assert.equal(page.data.cameraCount, 2);
  page.selectRoute({ currentTarget: { dataset: { index: 0 } } });
  assert.deepEqual(page.data.polylines, [segmentA]);
  assert.deepEqual(page.data.markers.map(marker => marker.id), [1, 1000000]);
});

test("shows available route when the other provider fails", async () => {
  const { page } = createPage(options => {
    if (options.url.endsWith("/routes/plan")) {
      options.success({ statusCode: 200, data: {
        isSuccessOnPlanRoute: false, routePlanResponse: [line(39.9)], navigationSteps: []
      } });
    } else {
      options.success({ statusCode: 503, data: { error: "未配置高德 Web 服务 Key" } });
    }
  });
  await page.memberReady;
  page.plan();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.routeOptions[0].status, "证件条件下不可行，线路仅供参考");
  assert.equal(page.data.routeOptions[1].status, "未配置高德 Web 服务 Key");
  assert.equal(page.data.polylines[0].dottedLine, true);
  page.onUnload();
});

test("waits for pending upstream result before opening comparison", async () => {
  const originalSetTimeout = global.setTimeout;
  global.setTimeout = (callback, delay) => delay === 5000 ? setImmediate(callback) : { deadline: callback };
  try {
    const { page } = createPage(options => {
      if (options.url.endsWith("/routes/plan")) {
        options.success({ statusCode: 202, data: { status: "PENDING", requestId: "r_owned" } });
      } else if (options.url.includes("/routes/result")) {
        assert.match(options.url, /openid=r_owned/);
        options.success({ statusCode: 200, data: { isSuccess: true, result: {
          isSuccessOnPlanRoute: true, routePlanResponse: [line(39.9)], navigationSteps: []
        } } });
      } else {
        options.success({ statusCode: 503, data: { error: "未配置" } });
      }
    });
    await page.memberReady;
    page.plan();
    await new Promise(resolve => setImmediate(() => setImmediate(resolve)));
    assert.equal(page.data.comparisonVisible, true);
    assert.equal(page.data.routeOptions[0].status, "规划完成");
    page.onUnload();
  } finally {
    global.setTimeout = originalSetTimeout;
  }
});

test("opens comparison at the three-minute deadline with partial result", async () => {
  const originalSetTimeout = global.setTimeout;
  let deadline;
  global.setTimeout = (callback, delay) => {
    if (delay === 180000) deadline = callback;
    return { callback };
  };
  try {
    const { page } = createPage(options => {
      if (options.url.endsWith("/routes/plan")) return;
      options.success({ statusCode: 200, data: {
        provider: "AMAP", avoidsKnownCameras: true, distance: "3 公里",
        useTime: "6 分钟", routePlanResponse: [line(39.91)], navigationSteps: []
      } });
    });
    await page.memberReady;
    page.plan();
    await new Promise(resolve => setImmediate(resolve));
    deadline();
    await new Promise(resolve => setImmediate(resolve));
    assert.equal(page.data.routeOptions[0].status, "规划超时");
    assert.equal(page.data.routeOptions[1].distance, "3 公里");
    assert.equal(page.data.comparisonVisible, true);
    page.onUnload();
  } finally {
    global.setTimeout = originalSetTimeout;
  }
});

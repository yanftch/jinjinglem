const assert = require("node:assert/strict");
const test = require("node:test");

function createPage(request) {
  global.Page = definition => { global.routePageDefinition = definition; };
  let included = [];
  global.wx = {
    getLocation(options) { options.success({ latitude: 39.9, longitude: 116.4 }); },
    chooseLocation(options) { options.success({ name: "终点", address: "终点", latitude: 40, longitude: 116.5 }); },
    createMapContext() { return { includePoints(options) { included = options.points; } }; },
    showToast() {},
    request
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/route/route")];
  require("../miniprogram/source/pages/route/route");
  const page = { ...global.routePageDefinition, data: { ...global.routePageDefinition.data },
    setData(values, done) { Object.assign(this.data, values); if (done) done(); } };
  page.onLoad();
  page.chooseEnd();
  return { page, included: () => included };
}

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
        options.success({ statusCode: 202, data: { status: "PENDING" } });
      } else if (options.url.includes("/routes/result")) {
        options.success({ statusCode: 200, data: { isSuccess: true, result: {
          isSuccessOnPlanRoute: true, routePlanResponse: [line(39.9)], navigationSteps: []
        } } });
      } else {
        options.success({ statusCode: 503, data: { error: "未配置" } });
      }
    });
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

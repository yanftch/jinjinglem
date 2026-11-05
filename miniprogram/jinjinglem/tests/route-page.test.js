const assert = require("node:assert/strict");
const test = require("node:test");

test("route page picks endpoints and draws the backend route", async () => {
  global.Page = definition => { global.routePageDefinition = definition; };
  let included = 0;
  global.wx = {
    getLocation(options) { options.success({ latitude: 39.9, longitude: 116.4 }); },
    chooseLocation(options) { options.success({ name: "终点", address: "终点", latitude: 40, longitude: 116.5 }); },
    createMapContext() { return { includePoints(options) { included = options.points.length; } }; },
    showToast() {},
    request(options) {
      assert.equal(options.url, "https://jinjingle.yixiangshe.com/api/v1/routes/plan");
      assert.equal(options.data.startPoint.latitude, 39.9);
      assert.equal(options.data.endPoint.longitude, 116.5);
      options.success({ statusCode: 200, data: {
        isSuccessOnPlanRoute: true, distance: "2km", useTime: "3分钟",
        routePlanResponse: [[
          { longitude: "116.4", latitude: "39.9" },
          { longitude: "116.5", latitude: "40.0" }
        ]], navigationSteps: []
      } });
    }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/route/route")];
  require("../miniprogram/source/pages/route/route");
  const page = { ...global.routePageDefinition, data: { ...global.routePageDefinition.data },
    setData(values) { Object.assign(this.data, values); } };
  page.onLoad();
  page.chooseEnd();
  page.plan();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.result.distance, "2km");
  assert.equal(page.data.polylines.length, 1);
  assert.equal(included, 2);
  page.onUnload();
});

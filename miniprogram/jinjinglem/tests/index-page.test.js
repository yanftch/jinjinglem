const assert = require("node:assert/strict");
const test = require("node:test");

test("map page loads own camera data and opens its own detail page", async () => {
  const app = { globalData: { cameraList: [] } };
  const calls = [];
  const requestUrls = [];
  const storage = {};
  global.getApp = () => app;
  global.Page = definition => { global.pageDefinition = definition; };
  global.wx = {
    request(options) {
      const url = new URL(options.url);
      assert.equal(url.host, "jinjinglem.yixiangshe.com");
      requestUrls.push(url.pathname + url.search);
      const data = url.pathname.endsWith("/count") ? { total: 1 } : [
        { id: 42, name: "顺义探头", longitude: 116.1, latitude: 40.1, coordinate_system: "GCJ02", camera_type: "SIX_RING_OUT" }
      ];
      options.success({ statusCode: 200, data });
    },
    getLocation(options) { options.success({ latitude: 40.1, longitude: 116.1 }); },
    showLoading() {},
    hideLoading() {},
    getStorageSync(key) { return storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    showToast(options) { calls.push(["toast", options.title]); },
    navigateTo(options) { calls.push(["navigate", options.url]); },
    createMapContext() {
      return { getRegion(options) { options.success({
        southwest: { latitude: 40, longitude: 116 },
        northeast: { latitude: 40.2, longitude: 116.2 }
      }); } };
    }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/index/index")];
  require("../miniprogram/source/pages/index/index");
  const page = { ...global.pageDefinition, data: { ...global.pageDefinition.data }, setData(values) { Object.assign(this.data, values); } };
  page.onLoad();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.markers.length, 1);
  assert.ok(requestUrls.includes("/api/v1/cameras?offset=0&limit=20"));
  assert.equal(page.data.markers[0].id, 42);
  assert.equal(page.data.showNewCameras, true);
  assert.equal(page.data.newCameras[0].typeText, "六环外");
  page.closeNewCameras();
  assert.equal(storage.jinjing_seen_camera_id, 42);
  page.showRecentCameras([{ id: 42, name: "顺义探头", camera_type: "SIX_RING_OUT" }]);
  assert.equal(page.data.showNewCameras, false);
  page.showRecentCameras([{ id: 43, name: "新探头", camera_type: "SIX_RING_IN" }]);
  assert.equal(page.data.showNewCameras, true);
  page.openNewCamera({ currentTarget: { dataset: { id: 43 } } });
  assert.equal(page.data.showNewCameras, false);
  assert.equal(storage.jinjing_seen_camera_id, 43);
  assert.deepEqual(calls[0], ["navigate", "/pages/comment/comment?id=43"]);
  page.onMarkerTap({ detail: { markerId: 42 } });
  assert.deepEqual(calls[1], ["navigate", "/pages/comment/comment?id=42"]);
  page.routePlan();
  assert.deepEqual(calls[2], ["navigate", "/pages/route/route"]);
  page.membership();
  assert.deepEqual(calls[3], ["toast", "功能暂不可用"]);
});

const assert = require("node:assert/strict");
const test = require("node:test");

test("review mode arrival records are stored locally without calling the arrival API", async () => {
  const storage = {};
  const requests = [];
  const toasts = [];
  global.Page = definition => { global.footprintPageDefinition = definition; };
  global.getApp = () => ({ globalData: {} });
  global.wx = {
    getStorageSync(key) { return storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    request(options) {
      requests.push(options.url);
      options.success({ statusCode: 200, data: { mode: "REVIEW" } });
    },
    getLocation(options) { options.success({ latitude: 39.9, longitude: 116.4, accuracy: 20 }); },
    showToast(options) { toasts.push(options.title); }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/footprint/footprint")];
  require("../miniprogram/source/pages/footprint/footprint");
  const page = { ...global.footprintPageDefinition,
    data: { ...global.footprintPageDefinition.data },
    setData(values) { Object.assign(this.data, values); } };

  page.onLoad();
  page.onShow();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(page.data.modeReady, true);
  assert.deepEqual(requests.map(url => new URL(url).pathname), ["/api/v1/site/miniprogram-mode"]);

  page.recordCurrentLocation();
  const saved = storage.jinjingle_review_arrivals;
  assert.equal(saved.length, 1);
  assert.equal(saved[0].latitude, 39.9);
  assert.equal(saved[0].longitude, 116.4);
  assert.equal(saved[0].accuracy_m, 20);
  assert.equal(page.data.records.length, 1);
  assert.equal(page.data.saving, false);
  assert.deepEqual(requests.map(url => new URL(url).pathname), ["/api/v1/site/miniprogram-mode"]);
  assert.deepEqual(toasts, ["到达位置已记录"]);
  page.onUnload();
});

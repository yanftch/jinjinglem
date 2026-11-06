const assert = require("node:assert/strict");
const test = require("node:test");

test("first mini program launch logs into the member service without blocking the map", async () => {
  const requests = [];
  let app;
  global.App = definition => { app = definition; };
  global.wx = {
    getStorageSync() { return ""; },
    setStorageSync() {},
    login(options) { options.success({ code: "first-launch-code" }); },
    request(options) {
      requests.push(options);
      if (options.url.endsWith("/members/wechat-login")) {
        assert.equal(options.data.code, "first-launch-code");
        return options.success({ statusCode: 200, data: { token: "new-token", member: { active: true } } });
      }
      assert.equal(options.header.Authorization, "Bearer new-token");
      options.success({ statusCode: 200, data: { member: { active: true } } });
    }
  };
  delete require.cache[require.resolve("../miniprogram/source/app")];
  require("../miniprogram/source/app");
  app.onLaunch();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(requests.length, 2);
  assert.deepEqual(app.globalData.cameraList, []);
});

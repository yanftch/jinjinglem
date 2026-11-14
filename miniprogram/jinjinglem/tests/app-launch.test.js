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

test("new mini program version prompts to restart and applies update when confirmed", () => {
  let app;
  let ready;
  let failed;
  let applied = 0;
  let modal;
  const updateManager = {
    onUpdateReady(callback) { ready = callback; },
    onUpdateFailed(callback) { failed = callback; },
    applyUpdate() { applied++; }
  };
  global.App = definition => { app = definition; };
  global.wx = {
    getUpdateManager() { return updateManager; },
    showModal(options) { modal = options; },
    getStorageSync() { return "existing-token"; },
    request(options) { options.success({ statusCode: 200, data: { member: { active: true } } }); }
  };
  delete require.cache[require.resolve("../miniprogram/source/app")];
  require("../miniprogram/source/app");
  app.onLaunch();

  ready();
  assert.equal(modal.title, "发现新版本");
  assert.match(modal.content, /重启小程序/);
  modal.success({ confirm: false });
  assert.equal(applied, 0);
  modal.success({ confirm: true });
  assert.equal(applied, 1);

  failed();
  assert.equal(modal.title, "更新失败");
});

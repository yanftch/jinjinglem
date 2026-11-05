const assert = require("node:assert/strict");
const test = require("node:test");

test("profile keeps a local nickname and only opens implemented features", () => {
  const storage = { jinjing_profile_nickname: "北京司机" };
  const calls = [];
  global.Page = definition => { global.profilePageDefinition = definition; };
  global.wx = {
    getStorageSync(key) { return storage[key]; },
    setStorageSync(key, value) { storage[key] = value; },
    showToast(options) { calls.push(["toast", options.title]); },
    showModal(options) { calls.push(["modal", options.title]); },
    navigateTo(options) { calls.push(["navigate", options.url]); },
    navigateBack(options) { calls.push(["back", options.delta]); }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/profile/profile")];
  require("../miniprogram/source/pages/profile/profile");
  const page = { ...global.profilePageDefinition,
    data: { ...global.profilePageDefinition.data },
    setData(values) { Object.assign(this.data, values); } };

  page.onLoad();
  assert.equal(page.data.nickname, "北京司机");
  page.editNickname();
  assert.equal(page.data.showNameEditor, true);
  page.onNameInput({ detail: { value: "  京京  " } });
  page.saveNickname();
  assert.equal(storage.jinjing_profile_nickname, "京京");
  assert.equal(page.data.showNameEditor, false);

  page.openRoute();
  page.openMap();
  page.openTrafficRules();
  page.openHelp();
  page.showUnavailable();
  assert.deepEqual(calls.slice(-5), [
    ["navigate", "/pages/route/route"], ["back", 1],
    ["navigate", "/pages/webview/webview?url=" + encodeURIComponent("https://jtgl.beijing.gov.cn/jgj/lszt/659722/659725/index.html") + "&title=" + encodeURIComponent("限行说明")],
    ["modal", "使用说明"], ["toast", "功能暂不可用"]
  ]);
  assert.deepEqual(page.onShareAppMessage().path, "/pages/index/index");
});

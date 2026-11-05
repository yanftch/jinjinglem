const assert = require("node:assert/strict");
const test = require("node:test");

test("H5 container loads an encoded HTTPS URL and rejects unsafe addresses", () => {
  const titles = [];
  global.Page = definition => { global.webviewPageDefinition = definition; };
  global.wx = { setNavigationBarTitle(options) { titles.push(options.title); } };
  delete require.cache[require.resolve("../miniprogram/source/pages/webview/webview")];
  require("../miniprogram/source/pages/webview/webview");
  const createPage = () => ({ ...global.webviewPageDefinition,
    data: { ...global.webviewPageDefinition.data },
    setData(values) { Object.assign(this.data, values); } });

  const page = createPage();
  const url = "https://jtgl.beijing.gov.cn/jgj/lszt/659722/659725/index.html";
  page.onLoad({ url: encodeURIComponent(url), title: encodeURIComponent("限行说明") });
  assert.equal(page.data.webUrl, url);
  assert.deepEqual(titles, ["限行说明"]);

  const invalid = createPage();
  invalid.onLoad({ url: "javascript:alert(1)" });
  assert.equal(invalid.data.webUrl, "");
  assert.match(invalid.data.error, /地址无效/);

  page.onWebViewError();
  assert.equal(page.data.webUrl, "");
  assert.match(page.data.error, /加载失败/);
});

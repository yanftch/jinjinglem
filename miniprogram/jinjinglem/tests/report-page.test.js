const assert = require("node:assert/strict");
const test = require("node:test");

test("report page submits nickname, screenshot and description without creating a camera locally", async () => {
  const calls = [];
  global.Page = definition => { global.reportPageDefinition = definition; };
  global.wx = {
    getStorageSync() { return "京京"; },
    setStorageSync(key, value) { calls.push(["storage", key, value]); },
    chooseImage(options) { options.success({ tempFiles: [{ path: "/tmp/report.png", size: 1000 }] }); },
    uploadFile(options) {
      calls.push(["upload", options.formData.nickname, options.formData.content]);
      options.success({ statusCode: 201, data: '{"id":23,"status":"PENDING"}' });
    },
    showLoading() {}, hideLoading() {},
    showModal(options) { calls.push(["modal", options.content]); },
    showToast(options) { calls.push(["toast", options.title]); }
  };
  delete require.cache[require.resolve("../miniprogram/source/pages/report/report")];
  require("../miniprogram/source/pages/report/report");
  const page = { ...global.reportPageDefinition, data: { ...global.reportPageDefinition.data },
    setData(values) { Object.assign(this.data, values); } };
  page.onLoad();
  assert.equal(page.data.nickname, "京京");
  page.chooseScreenshot();
  page.onContentInput({ detail: { value: "顺义区某路口南向北" } });
  page.submitReport();
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(calls[0], ["upload", "京京", "顺义区某路口南向北"]);
  assert.match(calls[2][1], /上报编号 #23/);
  assert.equal(page.data.screenshotPath, "");
  assert.equal(page.data.submitting, false);
});

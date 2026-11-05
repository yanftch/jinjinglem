const assert = require("node:assert/strict");
const test = require("node:test");
const api = require("../miniprogram/source/utils/jinjing-api");

test("loads every page, keeps only mapped GCJ-02 points and deduplicates ids", async () => {
  const requests = [];
  global.wx = {
    request(options) {
      requests.push(options.url);
      const url = new URL(options.url);
      let data;
      if (url.pathname.endsWith("/count")) data = { total: 1001 };
      else if (url.searchParams.get("offset") === "0") data = [
        { id: 1, name: "六环外", longitude: 116.1, latitude: 40.1, coordinate_system: "GCJ02", camera_type: "SIX_RING_OUT" },
        { id: 2, name: "未知坐标", longitude: 116.2, latitude: 40.2, coordinate_system: "UNKNOWN", camera_type: "SIX_RING_IN" }
      ];
      else if (url.searchParams.get("offset") === "500") data = [
        { id: 1, name: "重复", longitude: 116.1, latitude: 40.1, coordinate_system: "GCJ02", camera_type: "SIX_RING_OUT" }
      ];
      else data = [
        { id: 3, name: "二环内", longitude: 116.3, latitude: 39.9, coordinate_system: "GCJ02", camera_type: "TWO_RING_IN" }
      ];
      options.success({ statusCode: 200, data });
    }
  };
  const points = await api.loadAllCameras();
  assert.deepEqual(points.map(point => point.id), [1, 3]);
  assert.deepEqual(points[0].position, [116.1, 40.1]);
  assert.equal(points[0].cameraType, "SIX_RING_OUT");
  assert.equal(requests.length, 4);
});

test("posts a comment to the public camera API", async () => {
  global.wx = {
    request(options) {
      assert.equal(options.url, "https://jinjinglem.yixiangshe.com/api/v1/cameras/42/comments");
      assert.equal(options.method, "POST");
      assert.deepEqual(options.data, { nickname: "司机", content: "仍在使用" });
      options.success({ statusCode: 201, data: { id: 7 } });
    }
  };
  assert.deepEqual(await api.addComment(42, "司机", "仍在使用"), { id: 7 });
});

test("resolves approved report image URLs for mini program display", async () => {
  global.wx = {
    request(options) {
      options.success({ statusCode: 200, data: [
        { id: 1, url: "/api/v1/cameras/42/reported-image" },
        { id: 2, url: "https://example.com/camera.jpg" }
      ] });
    }
  };
  const images = await api.cameraImages(42);
  assert.equal(images[0].url, "https://jinjinglem.yixiangshe.com/api/v1/cameras/42/reported-image");
  assert.equal(images[1].url, "https://example.com/camera.jpg");
});

test("rejects failed HTTP responses instead of treating them as data", async () => {
  global.wx = {
    request(options) { options.success({ statusCode: 404, data: { error: "camera not found" } }); }
  };
  await assert.rejects(api.cameraDetail(42), /camera not found/);
});

test("sends route planning through the own backend and accepts pending response", async () => {
  global.wx = {
    request(options) {
      assert.equal(options.url, "https://jinjinglem.yixiangshe.com/api/v1/routes/plan");
      assert.equal(options.method, "POST");
      assert.equal(options.timeout, 180000);
      assert.equal(options.data.licence, 3);
      options.success({ statusCode: 202, data: { status: "PENDING" } });
    }
  };
  assert.deepEqual(await api.planRoute({ licence: 3 }), { status: "PENDING" });
});

test("uploads a camera report screenshot to the public review queue", async () => {
  global.wx = {
    uploadFile(options) {
      assert.equal(options.url, "https://jinjinglem.yixiangshe.com/api/v1/camera-reports");
      assert.equal(options.filePath, "/tmp/camera.png");
      assert.equal(options.name, "screenshot");
      assert.deepEqual(options.formData, { nickname: "司机", content: "路口南向北" });
      options.success({ statusCode: 201, data: '{"id":19,"status":"PENDING"}' });
    }
  };
  assert.deepEqual(await api.submitCameraReport("司机", "路口南向北", "/tmp/camera.png"),
    { id: 19, status: "PENDING" });
});

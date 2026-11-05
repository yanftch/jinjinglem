const test = require('node:test');
const assert = require('node:assert/strict');
const { candidates, zoomForLevel } = require('../../main/resources/static/road-search.js');

test('道路与路口比行政区使用更近的地图缩放', () => {
  assert.ok(zoomForLevel('道路') > zoomForLevel('区县'));
  assert.ok(zoomForLevel('交叉路口') > zoomForLevel('道路'));
});

test('候选位置只保留北京有效坐标并去重', () => {
  const road = { formattedAddress: '北京市顺义区香堤北街', adcode: '110113', level: '道路',
    location: { getLng: () => 116.7, getLat: () => 40.1 } };
  const result = candidates({ geocodes: [road, road,
    { formattedAddress: '天津市某路', adcode: '120100', location: { lng: 117.2, lat: 39.1 } },
    { formattedAddress: '北京市无坐标路', adcode: '110101' }] });
  assert.equal(result.length, 1);
  assert.deepEqual(result[0].point, [116.7, 40.1]);
});

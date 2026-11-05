const test = require('node:test');
const assert = require('node:assert/strict');
const { classify } = require('../../main/resources/static/ring-location.js');
const boundaries = require('../../main/resources/static/ring-boundaries.json');

test('北京二环内、二至六环间和六环外分别归类', () => {
  assert.equal(classify(116.4074, 39.9042, boundaries).type, 'TWO_RING_IN');
  assert.equal(classify(116.48, 39.95, boundaries).type, 'SIX_RING_IN');
  assert.equal(classify(116.8, 40, boundaries).type, 'SIX_RING_OUT');
});

test('环路附近提示复核，非法坐标不判断', () => {
  const onRing = boundaries.secondRing[0];
  assert.equal(classify(...onRing, boundaries).nearBoundary, true);
  assert.equal(classify(NaN, 39.9, boundaries), null);
});

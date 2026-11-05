/* Ring boundary coordinates are GCJ-02; the selected map point must use the same system. */
(function (root) {
  function inside(point, polygon) {
    let contained = false;
    for (let i = 0, j = polygon.length - 1; i < polygon.length; j = i++) {
      const a = polygon[i], b = polygon[j];
      if ((a[1] > point[1]) !== (b[1] > point[1]) &&
          point[0] < (b[0] - a[0]) * (point[1] - a[1]) / (b[1] - a[1]) + a[0]) {
        contained = !contained;
      }
    }
    return contained;
  }

  function boundaryDistance(point, polygon) {
    const scaleX = Math.cos(point[1] * Math.PI / 180) * 111320;
    const scaleY = 111320;
    let minimum = Infinity;
    for (let i = 0; i < polygon.length - 1; i++) {
      const a = polygon[i], b = polygon[i + 1];
      const dx = (b[0] - a[0]) * scaleX, dy = (b[1] - a[1]) * scaleY;
      const px = (point[0] - a[0]) * scaleX, py = (point[1] - a[1]) * scaleY;
      const factor = dx * dx + dy * dy ? Math.max(0, Math.min(1, (px * dx + py * dy) / (dx * dx + dy * dy))) : 0;
      minimum = Math.min(minimum, Math.hypot(px - factor * dx, py - factor * dy));
    }
    return minimum;
  }

  function classify(longitude, latitude, boundaries) {
    if (!Number.isFinite(longitude) || !Number.isFinite(latitude) ||
        longitude < -180 || longitude > 180 || latitude < -90 || latitude > 90) return null;
    const point = [longitude, latitude];
    const second = inside(point, boundaries.secondRing);
    const sixth = inside(point, boundaries.sixthRing);
    const distance = Math.min(boundaryDistance(point, boundaries.secondRing), boundaryDistance(point, boundaries.sixthRing));
    return { type: second ? "TWO_RING_IN" : sixth ? "SIX_RING_IN" : "SIX_RING_OUT",
      nearBoundary: distance <= 150, boundaryDistanceMeters: Math.round(distance) };
  }

  const api = { classify };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.RingLocation = api;
})(typeof window !== "undefined" ? window : globalThis);

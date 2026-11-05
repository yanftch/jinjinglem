(function (root) {
  function zoomForLevel(level) {
    const value = String(level || "");
    if (/门牌号|兴趣点|交叉路口|路口/.test(value)) return 17;
    if (/道路|街道/.test(value)) return 15;
    if (/乡镇|村庄/.test(value)) return 13;
    if (/区县/.test(value)) return 12;
    if (/城市|省/.test(value)) return 10;
    return 15;
  }

  function candidates(result) {
    const seen = new Set();
    return (result?.geocodes || []).map(item => {
      const location = item.location;
      const longitude = Number(location?.getLng?.() ?? location?.lng);
      const latitude = Number(location?.getLat?.() ?? location?.lat);
      return { name: item.formattedAddress || item.address || "匹配位置",
        district: item.district || "", level: item.level || "位置",
        adcode: String(item.adcode || ""), point: [longitude, latitude] };
    }).filter(item => Number.isFinite(item.point[0]) && Number.isFinite(item.point[1]) &&
      (item.adcode.startsWith("11") || item.name.includes("北京市")))
      .filter(item => {
        const key = `${item.name}|${item.point[0].toFixed(5)}|${item.point[1].toFixed(5)}`;
        if (seen.has(key)) return false;
        seen.add(key);
        return true;
      }).slice(0, 8);
  }

  const api = { zoomForLevel, candidates };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.RoadSearch = api;
})(typeof window !== "undefined" ? window : globalThis);

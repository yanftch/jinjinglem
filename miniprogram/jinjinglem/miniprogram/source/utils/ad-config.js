// 微信小程序流量主广告配置。
// 未达到开通条件或尚未创建广告位时保持关闭，不会影响小程序其他功能。
// 开通流量主并创建广告位后，只需填写对应广告单元 ID，再提交小程序新版本。
var config = {
  enabled: false,
  bannerUnitId: "",
  interstitialUnitId: "",
  rewardedVideoUnitId: ""
};

function unitEnabled(unitId) {
  return !!(config.enabled && unitId);
}

function bannerState() {
  return { enabled: unitEnabled(config.bannerUnitId), unitId: config.bannerUnitId };
}

var lastInterstitialAt = 0;
var INTERSTITIAL_INTERVAL_MS = 90000;

function showInterstitial() {
  if (!unitEnabled(config.interstitialUnitId) || typeof wx === "undefined" ||
      typeof wx.createInterstitialAd !== "function") {
    return Promise.resolve(false);
  }
  var now = Date.now();
  if (now - lastInterstitialAt < INTERSTITIAL_INTERVAL_MS) return Promise.resolve(false);
  lastInterstitialAt = now;
  try {
    var ad = wx.createInterstitialAd({ adUnitId: config.interstitialUnitId });
    if (!ad || typeof ad.show !== "function") return Promise.resolve(false);
    return Promise.resolve(ad.show()).then(function() { return true; }, function() { return false; });
  } catch (error) {
    return Promise.resolve(false);
  }
}

module.exports = {
  bannerState: bannerState,
  showInterstitial: showInterstitial
};

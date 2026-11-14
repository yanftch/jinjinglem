function decodeOption(value) {
  var text = String(value || "").trim();
  if (/^https:\/\//i.test(text)) return text;
  try { return decodeURIComponent(text); } catch (_) { return ""; }
}

function isValidWebUrl(url) {
  return /^https:\/\/[a-z0-9.-]+(?::[0-9]+)?(?:[/?#]|$)/i.test(url)
    && !/[\\\s]/.test(url);
}

Page({
  data: { webUrl: "", error: "" },
  onLoad: function(options) {
    var url = decodeOption(options && options.url);
    if (!isValidWebUrl(url)) {
      this.setData({ error: "网页地址无效，请使用 HTTPS 地址。" });
      return;
    }
    var title = decodeOption(options && options.title);
    if (title) wx.setNavigationBarTitle({ title: title.slice(0, 20) });
    this.setData({ webUrl: url, error: "" });
  },
  onWebViewError: function() {
    this.setData({ webUrl: "", error: "网页加载失败，请检查网络或小程序业务域名配置。" });
  }
});

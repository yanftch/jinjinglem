var TRAFFIC_RULES_URL = "https://jinjingle.yixiangshe.com/traffic-rules.html";
var memberApi = require("../../utils/jinjing-api");

Page({
  data: {
    showMembershipEntry: false,
    memberText: "正在查询会员状态"
  },
  onShow: function() {
    var page = this;
    memberApi.requireLivePage().then(function(allowed) {
      if (!allowed || !page.data.showMembershipEntry) return;
      memberApi.memberMe().then(function(info) {
        page.setData({ memberText: info.member.active ? "有效期至 " + memberApi.formatBeijingTime(info.member.expiresAt)
          : "查看试用、套餐和订单" });
      }, function() { page.setData({ memberText: "查看试用、套餐和订单" }); });
    });
  },
  openMembership: function() { wx.navigateTo({ url: "/pages/membership/membership" }); },
  openTrafficRules: function() {
    wx.navigateTo({
      url: "/pages/webview/webview?url=" + encodeURIComponent(TRAFFIC_RULES_URL)
        + "&title=" + encodeURIComponent("限行说明")
    });
  },
  openReport: function() {
    wx.navigateTo({ url: "/pages/report/report" });
  },
  openHelp: function() {
    wx.showModal({ title: "使用说明", showCancel: false,
      content: "在首页搜索道路或路口，点击地图上的探头可查看详情和发表评论。路线规划页可选择起点、终点及途经点。地图点位仅供参考，实际通行请以现场信息为准。" });
  },
  openAbout: function() {
    wx.showModal({ title: "关于小汽车嗒嗒嗒", showCancel: false,
      content: "小汽车嗒嗒嗒提供探头位置查询与路线规划。数据持续更新，详情请以现场信息为准。网站：jinjingle.yixiangshe.com" });
  },
  showUnavailable: function() {
    wx.showToast({ title: "功能暂不可用", icon: "none" });
  },
  onShareAppMessage: function() {
    return { title: "小汽车嗒嗒嗒 · 探头分布与路线规划", path: "/pages/index/index" };
  },
  onShareTimeline: function() {
    return { title: "小汽车嗒嗒嗒 · 探头分布与路线规划" };
  }
});

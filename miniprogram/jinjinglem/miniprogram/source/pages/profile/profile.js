var memberApi = require("../../utils/jinjing-api");
var adConfig = require("../../utils/ad-config");

Page({
  data: {
    showMembershipEntry: true,
    memberText: "正在查询会员状态",
    versionText: "",
    adBannerEnabled: false,
    adBannerUnitId: ""
  },
  onLoad: function() {
    var versionText = "当前版本号暂不可用";
    try {
      var miniProgram = wx.getAccountInfoSync().miniProgram;
      if (miniProgram.version) {
        versionText = "当前版本 v" + miniProgram.version;
      } else if (miniProgram.envVersion === "trial") {
        versionText = "当前版本：体验版";
      } else if (miniProgram.envVersion === "develop") {
        versionText = "当前版本：开发版";
      }
    } catch (error) {}
    var banner = adConfig.bannerState();
    this.setData({ versionText: versionText, adBannerEnabled: banner.enabled,
      adBannerUnitId: banner.unitId });
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
  openContact: function() { wx.navigateTo({ url: "/pages/contact/contact" }); },
  openTrafficRules: function() {
    wx.navigateTo({ url: "/pages/traffic-rules/traffic-rules" });
  },
  openReport: function() {
    wx.navigateTo({ url: "/pages/report/report" });
  },
  openHelp: function() {
    wx.navigateTo({ url: "/pages/usage-guide/usage-guide" });
  },
  openAbout: function() {
    wx.showModal({ title: "关于小汽车嗒嗒嗒", showCancel: false,
      content: "小汽车嗒嗒嗒提供探头位置查询与路线规划。数据持续更新，详情请以现场信息为准。网站：jinjingle.yixiangshe.com" });
  },
  showUnavailable: function() {
    wx.showToast({ title: "功能暂不可用", icon: "none" });
  },
  onAdError: function() {
    this.setData({ adBannerEnabled: false });
  },
  onShareAppMessage: function() {
    return { title: "小汽车嗒嗒嗒 · 探头分布与路线规划", path: "/pages/index/index" };
  },
  onShareTimeline: function() {
    return { title: "小汽车嗒嗒嗒 · 探头分布与路线规划" };
  }
});

var NICKNAME_STORAGE_KEY = "jinjing_profile_nickname";
var TRAFFIC_RULES_URL = "https://jtgl.beijing.gov.cn/jgj/lszt/659722/659725/index.html";
var memberApi = require("../../utils/jinjing-api");

Page({
  data: {
    nickname: "出行访客",
    nameDraft: "",
    showNameEditor: false,
    memberText: "正在查询会员状态"
  },
  onShow: function() {
    var page = this;
    memberApi.memberMe().then(function(info) {
      page.setData({ memberText: info.member.active ? "有效期至 " + memberApi.formatBeijingTime(info.member.expiresAt)
        : "查看试用、套餐和订单" });
    }, function() { page.setData({ memberText: "查看试用、套餐和订单" }); });
  },
  openMembership: function() { wx.navigateTo({ url: "/pages/membership/membership" }); },
  onLoad: function() {
    try {
      var saved = String(wx.getStorageSync(NICKNAME_STORAGE_KEY) || "").trim();
      if (saved) this.setData({ nickname: saved });
    } catch (_) {}
  },
  editNickname: function() {
    this.setData({ nameDraft: this.data.nickname, showNameEditor: true });
  },
  onNameInput: function(event) {
    this.setData({ nameDraft: event.detail.value });
  },
  closeNameEditor: function() {
    this.setData({ showNameEditor: false });
  },
  stopTap: function() {},
  saveNickname: function() {
    var name = String(this.data.nameDraft || "").trim();
    if (!name) {
      wx.showToast({ title: "请输入昵称", icon: "none" });
      return;
    }
    try {
      wx.setStorageSync(NICKNAME_STORAGE_KEY, name);
      this.setData({ nickname: name, showNameEditor: false });
      wx.showToast({ title: "昵称已保存", icon: "success" });
    } catch (_) {
      wx.showToast({ title: "保存失败，请重试", icon: "none" });
    }
  },
  openMap: function() {
    wx.navigateBack({ delta: 1, fail: function() {
      wx.redirectTo({ url: "/pages/index/index" });
    } });
  },
  openRoute: function() {
    wx.navigateTo({ url: "/pages/route/route" });
  },
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
    wx.showModal({ title: "关于进京了", showCancel: false,
      content: "进京了提供探头位置查询与路线规划。数据持续更新，详情请以现场信息为准。网站：jinjingle.yixiangshe.com" });
  },
  showUnavailable: function() {
    wx.showToast({ title: "功能暂不可用", icon: "none" });
  },
  onShareAppMessage: function() {
    return { title: "进京了 · 探头分布与路线规划", path: "/pages/index/index" };
  },
  onShareTimeline: function() {
    return { title: "进京了 · 探头分布与路线规划" };
  }
});

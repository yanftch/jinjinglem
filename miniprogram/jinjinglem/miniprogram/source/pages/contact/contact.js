var api = require("../../utils/jinjing-api");

Page({
  data: { memberId: "", memberError: false },
  onLoad: function() {
    var page = this;
    api.requireLivePage().then(function(allowed) {
      if (!allowed) return;
      api.memberMe().then(function(info) {
        page.setData({ memberId: String(info.member.id || ""), memberError: false });
      }, function() { page.setData({ memberError: true }); });
    });
  },
  copyMemberId: function() {
    var memberId = String(this.data.memberId || "").trim();
    if (!memberId) return;
    wx.setClipboardData({ data: memberId, success: function() {
      wx.showToast({ title: "会员编号已复制", icon: "success" });
    } });
  }
});

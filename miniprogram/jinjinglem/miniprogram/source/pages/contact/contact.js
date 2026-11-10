var api = require("../../utils/jinjing-api");

Page({
  data: { memberNumber: "", memberError: false },
  onLoad: function() {
    var page = this;
    api.requireLivePage().then(function(allowed) {
      if (!allowed) return;
      api.memberMe().then(function(info) {
        page.setData({ memberNumber: String(info.member.memberNo || ""), memberError: false });
      }, function() { page.setData({ memberError: true }); });
    });
  },
  copyMemberId: function() {
    var memberNumber = String(this.data.memberNumber || "").trim();
    if (!memberNumber) return;
    wx.setClipboardData({ data: memberNumber, success: function() {
      wx.showToast({ title: "会员编号已复制", icon: "success" });
    } });
  }
});

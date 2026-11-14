var api = require("../../utils/jinjing-api");
function date(value) { return api.formatBeijingTime(value); }
Page({
  data: { loading: true, active: false, memberNumber: "", expiresAt: "—", trialExpiresAt: "—",
    welcomeExpiresAt: "—", hasWelcome: false, hasTrial: false, purchaseAvailable: false,
    orders: [], error: "" },
  onShow: function() {
    var page = this;
    api.requireLivePage().then(function(allowed) { if (allowed) page.refresh(); });
  },
  refresh: function() {
    var page = this;
    api.memberMe().then(function(info) {
      page.setData({ loading: false, active: !!info.member.active, memberNumber: String(info.member.memberNo || ""), expiresAt: date(info.member.expiresAt),
        trialExpiresAt: date(info.member.trialExpiresAt), hasTrial: !!info.member.trialExpiresAt,
        welcomeExpiresAt: date(info.member.welcomeExpiresAt), hasWelcome: !!info.member.welcomeExpiresAt,
        purchaseAvailable: !!info.purchaseAvailable,
        orders: (info.orders || []).map(function(order) {
          return Object.assign({}, order, { planText: order.plan_code === "MONTH" ? "月会员" : "年会员",
            statusText: { PENDING: "待支付", PAID: "已开通", REFUNDED: "已退款", CLOSED: "已关闭" }[order.status] || order.status,
            createdText: date(order.created_at) });
        }), error: "" });
    }, function(error) { page.setData({ loading: false, error: error.message }); });
  },
  showPaymentUnavailable: function() {
    wx.showToast({ title: "暂不支持线上支付，请先联系客服开通", icon: "none" });
  },
  copyMemberId: function() {
    var memberNumber = String(this.data.memberNumber || "").trim();
    if (!memberNumber) return;
    wx.setClipboardData({ data: memberNumber, success: function() {
      wx.showToast({ title: "会员编号已复制", icon: "success" });
    } });
  },
});

var api = require("../../utils/jinjing-api");
function date(value) { return api.formatBeijingTime(value); }
Page({
  data: { loading: true, active: false, expiresAt: "—", trialExpiresAt: "—",
    welcomeExpiresAt: "—", hasWelcome: false, hasTrial: false, purchaseAvailable: false,
    orders: [], code: "", busy: false, error: "" },
  onShow: function() {
    var page = this;
    api.requireLivePage().then(function(allowed) { if (allowed) page.refresh(); });
  },
  refresh: function() {
    var page = this;
    api.memberMe().then(function(info) {
      page.setData({ loading: false, active: !!info.member.active, expiresAt: date(info.member.expiresAt),
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
  buyMonth: function() { this.buy("MONTH"); },
  buyYear: function() { this.buy("YEAR"); },
  buy: function(plan) {
    if (this.data.busy || !this.data.purchaseAvailable) return;
    if (typeof wx.requestVirtualPayment !== "function") {
      wx.showToast({ title: "当前微信版本暂不支持虚拟支付", icon: "none" });
      return;
    }
    var page = this;
    this.setData({ busy: true });
    api.buyMembership(plan).then(function(order) {
      wx.requestVirtualPayment(Object.assign({}, order.payParams, {
        success: function() { wx.showToast({ title: "支付已提交，正在核实", icon: "none" }); page.refresh(); },
        fail: function(error) {
          if (!String(error.errMsg || "").includes("cancel"))
            wx.showToast({ title: "支付未完成，请稍后重试", icon: "none" });
          page.refresh();
        },
        complete: function() { page.setData({ busy: false }); }
      }));
    }, function(error) { page.setData({ busy: false }); wx.showToast({ title: error.message, icon: "none" }); });
  },
  createCode: function() {
    var page=this;
    api.appLoginCode().then(function(result) { page.setData({ code: result.code }); },
      function(error) { wx.showToast({ title: error.message, icon: "none" }); });
  }
});

var memberApi = require("./utils/jinjing-api");

function setupUpdateManager() {
  if (typeof wx.getUpdateManager !== "function") return;
  var updateManager = wx.getUpdateManager();
  if (!updateManager) return;
  updateManager.onUpdateReady(function() {
    wx.showModal({
      title: "发现新版本",
      content: "新版本已准备好，需要重启小程序完成更新。",
      confirmText: "重启更新",
      cancelText: "稍后",
      success: function(result) {
        if (result.confirm) updateManager.applyUpdate();
      }
    });
  });
  updateManager.onUpdateFailed(function() {
    wx.showModal({
      title: "更新失败",
      content: "新版本下载失败，请检查网络后重新打开小程序。",
      showCancel: false
    });
  });
}

App({
  onLaunch: function() {
    setupUpdateManager();
    // 首页地图仍可匿名使用；登录成功后服务端会为新账号发放一次欢迎会员。
    memberApi.memberMe().catch(function() {});
  },
  onShow: function() {
    this.globalData.foregroundGeneration = (this.globalData.foregroundGeneration || 0) + 1;
  },
  globalData: {
    cameraList: [],
    recentPopupPending: true,
    foregroundGeneration: 0
  }
});

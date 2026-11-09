var memberApi = require("./utils/jinjing-api");

App({
  onLaunch: function() {
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

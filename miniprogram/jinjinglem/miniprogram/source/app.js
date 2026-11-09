var memberApi = require("./utils/jinjing-api");

App({
  onLaunch: function() {
    // 首页地图仍可匿名使用；登录成功后服务端会为新账号发放一次欢迎会员。
    memberApi.memberMe().catch(function() {});
  },
  globalData: {
    cameraList: [],
    recentPopupPending: true
  }
});

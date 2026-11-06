var api = require("../../utils/jinjing-api");
var NICKNAME_STORAGE_KEY = "jinjing_profile_nickname";
var MAX_IMAGE_BYTES = 1024 * 1024;

Page({
  data: { nickname: "", content: "", screenshotPath: "", screenshotBytes: 0, submitting: false },
  onLoad: function() {
    try {
      var nickname = String(wx.getStorageSync(NICKNAME_STORAGE_KEY) || "").trim();
      if (nickname) this.setData({ nickname: nickname });
    } catch (_) {}
  },
  onNicknameInput: function(event) { this.setData({ nickname: event.detail.value }); },
  onContentInput: function(event) { this.setData({ content: event.detail.value }); },
  chooseScreenshot: function() {
    var page = this;
    wx.chooseImage({
      count: 1, sizeType: ["compressed"], sourceType: ["album", "camera"],
      success: function(result) {
        var file = result.tempFiles && result.tempFiles[0];
        if (!file || !file.path) return;
        function acceptFile(size) {
          if (!size || size > MAX_IMAGE_BYTES) {
            wx.showToast({ title: size > MAX_IMAGE_BYTES ? "截图不能超过 1MB" : "无法读取截图大小，请重新选择", icon: "none" });
            return;
          }
          page.setData({ screenshotPath: file.path, screenshotBytes: size });
        }
        if (typeof file.size === "number") acceptFile(file.size);
        else wx.getFileInfo({ filePath: file.path, success: function(info) { acceptFile(info.size); },
          fail: function() { wx.showToast({ title: "无法读取截图大小，请重新选择", icon: "none" }); } });
      }
    });
  },
  previewScreenshot: function() {
    if (this.data.screenshotPath) wx.previewImage({ urls: [this.data.screenshotPath] });
  },
  removeScreenshot: function() { this.setData({ screenshotPath: "", screenshotBytes: 0 }); },
  submitReport: function() {
    if (this.data.submitting) return;
    var nickname = String(this.data.nickname || "").trim();
    var content = String(this.data.content || "").trim();
    if (!nickname || nickname.length > 30 || !content || content.length > 2000) {
      wx.showToast({ title: "请填写昵称和探头位置线索", icon: "none" });
      return;
    }
    if (this.data.screenshotPath && (!this.data.screenshotBytes || this.data.screenshotBytes > MAX_IMAGE_BYTES)) {
      wx.showToast({ title: "截图不能超过 1MB，请重新选择", icon: "none" });
      return;
    }
    var page = this;
    this.setData({ submitting: true });
    wx.showLoading({ title: "正在提交", mask: true });
    api.submitCameraReport(nickname, content, this.data.screenshotPath).then(function(result) {
      try { wx.setStorageSync(NICKNAME_STORAGE_KEY, nickname); } catch (_) {}
      page.setData({ content: "", screenshotPath: "", screenshotBytes: 0 });
      wx.showModal({ title: "上报成功", content: "上报编号 #" + result.id + "，信息已提交，审核通过后才会新增探头。", showCancel: false });
    }).catch(function(error) {
      wx.showToast({ title: error.message || "提交失败，请重试", icon: "none" });
    }).then(function() {
      wx.hideLoading();
      page.setData({ submitting: false });
    });
  }
});

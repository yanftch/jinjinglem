var cameraApi = require("../../utils/jinjing-api");

function iconForType(type) {
  if (type === "DISCARD") return "/image/fq.png";
  if (type === "SIX_RING_OUT") return "/image/locationSixRing.png";
  if (type === "TWO_RING_IN") return "/image/locationTwoRing.png";
  return "/image/location.png";
}

function showError(error) {
  wx.showToast({ title: error.message || "请求失败，请稍后重试", icon: "none" });
}

Page({
  data: {
    id: null,
    markName: "",
    markCreateTime: "",
    latitude: 39.909088,
    longitude: 116.397643,
    markers: [],
    comments: [],
    addComments: false,
    imageUrl: "",
    submitting: false
  },
  onShareAppMessage: function() {
    return { title: "小汽车嗒嗒嗒 · 探头详情", path: "/pages/comment/comment?id=" + this.data.id };
  },
  onShareTimeline: function() {
    return { title: "小汽车嗒嗒嗒 · 探头详情", query: "id=" + this.data.id };
  },
  onLoad: function(options) {
    var id = Number(options.id);
    if (!Number.isInteger(id) || id <= 0) {
      wx.showToast({ title: "无效的探头编号", icon: "none" });
      return;
    }
    this.setData({ id: id });
    var page = this;
    cameraApi.requireLivePage().then(function(allowed) { if (allowed) page.loadDetail(); });
  },
  loadDetail: function() {
    var page = this;
    wx.showLoading({ title: "加载探头详情", mask: true });
    return Promise.all([
      cameraApi.cameraDetail(this.data.id),
      cameraApi.cameraComments(this.data.id),
      cameraApi.cameraImages(this.data.id)
    ]).then(function(result) {
      var camera = result[0], comments = result[1], images = result[2];
      var latitude = Number(camera.latitude), longitude = Number(camera.longitude);
      page.setData({
        markName: camera.name,
        latitude: latitude,
        longitude: longitude,
        markers: [{ id: camera.id, latitude: latitude, longitude: longitude,
          width: 20, height: 30, iconPath: iconForType(camera.cameraType) }],
        comments: comments.map(function(comment) {
          return {
            id: comment.id,
            nickname: comment.nickname,
            content: comment.content,
            time: comment.raw_time || ""
          };
        }),
        imageUrl: images.length ? images[0].url : ""
      });
    }).catch(showError).then(function() { wx.hideLoading(); });
  },
  writeComment: function() {
    this.setData({ addComments: true });
  },
  handleClose: function() {
    this.setData({ addComments: false });
  },
  previewImage: function() {
    if (this.data.imageUrl) wx.previewImage({ urls: [this.data.imageUrl], current: this.data.imageUrl });
  },
  handleSubmit: function(event) {
    if (this.data.submitting) return;
    var nickname = String(event.detail.nickname || "").trim();
    var content = String(event.detail.comment || "").trim();
    if (!nickname || !content) {
      wx.showToast({ title: "请填写昵称和评论", icon: "none" });
      return;
    }
    var page = this;
    this.setData({ submitting: true });
    wx.showLoading({ title: "正在提交", mask: true });
    cameraApi.addComment(this.data.id, nickname, content).then(function() {
      page.setData({ addComments: false });
      var modal = page.selectComponent("#commentModal");
      if (modal) modal.clearComment();
      return page.loadDetail();
    }).then(function() {
      wx.showToast({ title: "评论已提交", icon: "success" });
    }).catch(showError).then(function() {
      page.setData({ submitting: false });
      wx.hideLoading();
    });
  }
});

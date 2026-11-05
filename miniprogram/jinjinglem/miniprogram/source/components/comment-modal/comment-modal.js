Component({
  properties: {
    isShow: Boolean
  },
  data: {
    nickname: "",
    comment: ""
  },
  lifetimes: {
    attached: function() {
      var t = wx.getStorageSync("nickname");
      t && this.setData({
        nickname: t
      })
    }
  },
  methods: {
    onNicknameInput: function(t) {
      this.setData({
        nickname: t.detail.value
      })
    },
    onCommentInput: function(t) {
      this.setData({
        comment: t.detail.value
      })
    },
    submitComment: function() {
      var t = this.data,
        n = String(t.nickname || "").trim(),
        e = String(t.comment || "").trim();
      n ? e ? (wx.setStorageSync("nickname", n), this.triggerEvent("submit", {
        nickname: n,
        comment: e
      })) : wx.showToast({
        title: "请填写评论",
        icon: "none"
      }) : wx.showToast({
        title: "请填写昵称",
        icon: "none"
      })
    },
    clearComment: function() {
      this.setData({ comment: "" });
      this.hideModal()
    },
    hideModal: function() {
      this.setData({
        isShow: !1
      }), this.triggerEvent("close")
    },
    stopPropagation: function() {}
  }
});

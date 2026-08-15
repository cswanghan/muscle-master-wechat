Page({
  soon() {
    wx.showToast({ title: '演示：礼卡稍后开通入账', icon: 'none' })
  },
  goHome() {
    wx.redirectTo({ url: '/pages/index/index' })
  },
  goMine() {
    wx.redirectTo({ url: '/pages/mine/mine' })
  },
})

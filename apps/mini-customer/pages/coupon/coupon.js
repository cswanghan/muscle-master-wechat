Page({
  data: {
    tab: 'unused',
    unused: [
      {
        id: 'c20a',
        amount: '¥20',
        cap: '无门槛',
        tone: 'jade',
        title: '新客体验券',
        rule: '周一至周五 · 10:00–17:00 · 全门店',
        date: '2026/08/13 – 2026/10/12',
        expire: '',
        solid: false,
      },
      {
        id: 'c20b',
        amount: '¥20',
        cap: '无门槛',
        tone: 'jade',
        title: '新客体验券',
        rule: '周一至周五 · 10:00–17:00 · 全门店',
        date: '2026/08/13 – 2026/10/12',
        expire: '',
        solid: false,
      },
      {
        id: 'c95',
        amount: '9.5折',
        cap: '限指定技师',
        tone: 'copper',
        title: '空档专享',
        rule: '仅今晚 14:00–17:00 · 本店',
        date: '',
        expire: '17 小时后过期',
        solid: true,
      },
    ],
  },
  switchTab(e) {
    this.setData({ tab: e.currentTarget.dataset.tab })
  },
  useCoupon() {
    wx.reLaunch({ url: '/pages/index/index' })
  },
  showRule() {
    wx.showModal({
      title: '使用规则',
      content: '同一订单可叠加 1 张券 + 时段折扣；储值赠金不与券叠加。',
      showCancel: false,
    })
  },
  enableNotify() {
    wx.showToast({ title: '演示：已模拟开启提醒', icon: 'none' })
  },
})

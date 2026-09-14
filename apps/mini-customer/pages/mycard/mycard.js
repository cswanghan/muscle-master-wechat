const { request, ensureLogin } = require('../../utils/api.js')

Page({
  data: {
    coreIssue: '',
    remaining: 0,
    done: 0,
    packages: [],
    plans: [],
    loading: true,
    error: '',
  },
  onShow() {
    this.load()
  },
  load() {
    this.setData({ loading: true, error: '' })
    ensureLogin()
      .then(() => request({ path: '/api/v1/c/membership', auth: true }))
      .then((d) => {
        this.setData({
          coreIssue: d.coreIssue || '',
          remaining: d.remainingSessions || 0,
          done: d.doneSessions || 0,
          packages: (d.packages || []).map((p) => ({
            ...p,
            pct: p.totalSessions > 0 ? Math.round((p.usedSessions * 100) / p.totalSessions) : 0,
          })),
          plans: (d.plans || []).map((p) => ({ ...p, pct: Math.round(p.progressX100 / 100) })),
          loading: false,
        })
      })
      .catch((err) => this.setData({ error: err.message || '加载失败', loading: false }))
  },
  // 续课在门店办：课包价格常随活动浮动，线上自助容易和店里的口径打架。
  onRenew() {
    wx.showModal({
      title: '续课',
      content: '续课请联系您的老师或到前台办理，办好后这里的课时会立即更新。',
      showCancel: false,
      confirmText: '知道了',
    })
  },
  goCheckin() {
    wx.navigateTo({ url: '/pages/checkin/checkin' })
  },
  goBack() {
    wx.navigateBack()
  },
})

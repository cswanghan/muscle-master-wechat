const api = require('../../../utils/staff-api.js')

const SCOPES = [
  { key: 'mine', label: '我的会员' },
  { key: 'store', label: '全店' },
]

// 余次少于这个数就标出来 —— 该提醒续课的人，老师一眼要看见。
const LOW_SESSIONS = 3

function paint(m) {
  return {
    ...m,
    low: m.remainingSessions <= LOW_SESSIONS,
    empty: m.remainingSessions <= 0,
    // 计划进度条按百分比给宽度；没计划时不占位。
    planPct: m.planTotal > 0 ? Math.round(m.planProgressX100 / 100) : 0,
    planLine: m.planTotal > 0
      ? m.planTitle + ' · ' + m.planDone + '/' + m.planTotal
      : '',
  }
}

Page({
  data: {
    scopes: SCOPES,
    scope: 'mine',
    items: [],
    total: 0,
    loading: true,
    error: '',
  },
  onShow() {
    this.load(this.data.scope)
  },
  pickScope(e) {
    const scope = e.currentTarget.dataset.key
    if (scope === this.data.scope) {
      return
    }
    this.setData({ scope })
    this.load(scope)
  },
  load(scope) {
    this.setData({ loading: true, error: '' })
    api.request({
      url: '/api/v1/t/members?scope=' + scope,
      token: getApp().globalData.token,
    })
      .then((data) => {
        const items = ((data && data.items) || []).map(paint)
        this.setData({
          items,
          total: (data && data.total) || 0,
          loading: false,
          error: items.length ? '' : '还没有会员，前台卖课后会自动建档',
        })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  openMember(e) {
    const id = e.currentTarget.dataset.id
    wx.navigateTo({ url: '/pages/staff/member/member?customerId=' + id })
  },
  goBack() {
    wx.navigateBack()
  },
})

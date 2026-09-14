const api = require('../../../utils/staff-api.js')

const KINDS = [
  { key: 'AFTER_CLASS', label: '课后' },
  { key: 'RETENTION', label: '续费' },
  { key: 'REVIVE', label: '唤回' },
  { key: 'TRIAL', label: '体验后' },
]

Page({
  data: {
    kinds: KINDS,
    items: [],
    pending: 0,
    overdue: 0,
    doneRate: 0,
    loading: true,
    error: '',
    // 记一笔
    open: false,
    draft: { customerId: '', kind: 'AFTER_CLASS', content: '', outcome: 'REACHED' },
    saving: false,
  },
  onShow() {
    this.load()
  },
  load() {
    this.setData({ loading: true, error: '' })
    api.request({ url: '/api/v1/t/follow-ups', token: getApp().globalData.token })
      .then((d) => {
        this.setData({
          items: (d.items || []).map((x) => ({
            ...x,
            tone: x.overdue ? 'overdue' : (x.pending ? 'pending' : 'done'),
          })),
          pending: d.pending || 0,
          overdue: d.overdue || 0,
          doneRate: Math.round((d.doneRateX100 || 0) / 100),
          loading: false,
        })
      })
      .catch((err) => this.setData({ error: err.message || '加载失败', loading: false }))
  },
  // 待办点一下就能标完成，不用先进详情——回访是个高频动作。
  markDone(e) {
    const id = e.currentTarget.dataset.id
    api.request({
      url: '/api/v1/t/follow-ups/' + id + '/done',
      method: 'POST',
      token: getApp().globalData.token,
      data: { content: '已联系', outcome: 'REACHED' },
    })
      .then(() => {
        wx.showToast({ title: '已记录', icon: 'none' })
        this.load()
      })
      .catch((err) => wx.showToast({ title: err.message || '失败', icon: 'none' }))
  },
  goBack() {
    wx.navigateBack()
  },
})

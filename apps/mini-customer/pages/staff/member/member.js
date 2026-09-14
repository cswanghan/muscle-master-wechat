const api = require('../../../utils/staff-api.js')

Page({
  data: {
    customerId: '',
    phoneMask: '',
    coreIssue: '',
    remark: '',
    remainingSessions: 0,
    doneSessions: 0,
    packages: [],
    plans: [],
    editing: false,
    draftIssue: '',
    draftRemark: '',
    saving: false,
    loading: true,
    error: '',
  },
  onLoad(query) {
    this.setData({ customerId: query.customerId || '' })
  },
  onShow() {
    if (this.data.customerId) {
      this.load()
    }
  },
  load() {
    this.setData({ loading: true, error: '' })
    api.request({
      url: '/api/v1/t/members/' + this.data.customerId,
      token: getApp().globalData.token,
    })
      .then((d) => this.apply(d))
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  apply(d) {
    this.setData({
      phoneMask: d.phoneMask || '',
      coreIssue: d.coreIssue || '',
      remark: d.remark || '',
      remainingSessions: d.remainingSessions || 0,
      doneSessions: d.doneSessions || 0,
      packages: (d.packages || []).map((p) => ({
        ...p,
        pct: p.totalSessions > 0
          ? Math.round((p.usedSessions * 100) / p.totalSessions)
          : 0,
      })),
      plans: (d.plans || []).map((p) => ({
        ...p,
        pct: Math.round(p.progressX100 / 100),
        running: p.statusLabel === '进行中',
      })),
      loading: false,
      editing: false,
      saving: false,
    })
  },
  startEdit() {
    this.setData({
      editing: true,
      draftIssue: this.data.coreIssue,
      draftRemark: this.data.remark,
    })
  },
  cancelEdit() {
    this.setData({ editing: false })
  },
  onIssue(e) {
    this.data.draftIssue = e.detail.value
  },
  onRemark(e) {
    this.data.draftRemark = e.detail.value
  },
  save() {
    if (this.data.saving) {
      return
    }
    this.setData({ saving: true })
    api.request({
      url: '/api/v1/t/members/' + this.data.customerId + '/profile',
      method: 'POST',
      token: getApp().globalData.token,
      data: {
        requestId: 'prof-' + Date.now(),
        coreIssue: this.data.draftIssue,
        remark: this.data.draftRemark,
      },
    })
      .then((d) => {
        this.apply(d)
        wx.showToast({ title: '已保存', icon: 'none' })
      })
      .catch((err) => {
        this.setData({ saving: false, error: err.message || '保存失败' })
      })
  },
  goBack() {
    wx.navigateBack()
  },
})

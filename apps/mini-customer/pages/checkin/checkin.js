const { request, ensureLogin } = require('../../utils/api.js')

function todayIso() {
  const d = new Date()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${d.getFullYear()}-${mm}-${dd}`
}

Page({
  data: {
    scope: 'store',
    items: [],
    content: '',
    saving: false,
    loading: true,
    error: '',
  },
  onShow() {
    this.load(this.data.scope)
  },
  pickScope(e) {
    const scope = e.currentTarget.dataset.key
    this.setData({ scope })
    this.load(scope)
  },
  load(scope) {
    this.setData({ loading: true, error: '' })
    ensureLogin()
      .then(() => request({ path: '/api/v1/c/checkins?scope=' + scope, auth: true }))
      .then((d) => {
        this.setData({
          items: (d.items || []),
          loading: false,
          error: (d.items || []).length ? '' : '还没有人打卡，你可以是第一个',
        })
      })
      .catch((err) => this.setData({ error: err.message || '加载失败', loading: false }))
  },
  onContent(e) {
    this.data.content = e.detail.value
  },
  submit() {
    const content = (this.data.content || '').trim()
    if (!content) {
      wx.showToast({ title: '写点什么再打卡吧', icon: 'none' })
      return
    }
    this.setData({ saving: true })
    request({
      path: '/api/v1/c/checkins',
      method: 'POST',
      auth: true,
      data: { checkDay: todayIso(), content, visibility: 'PUBLIC' },
    })
      .then(() => {
        // 一人一天一条：再次提交是改今天这条，所以文案说"已记录"而不是"已发布"。
        wx.showToast({ title: '已记录今日打卡', icon: 'none' })
        this.setData({ saving: false, content: '' })
        this.load(this.data.scope)
      })
      .catch((err) => {
        this.setData({ saving: false })
        wx.showToast({ title: err.message || '失败', icon: 'none' })
      })
  },
  like(e) {
    const id = e.currentTarget.dataset.id
    request({ path: '/api/v1/c/checkins/' + id + '/like', method: 'POST', auth: true })
      .then(() => this.load(this.data.scope))
      .catch((err) => wx.showToast({ title: err.message || '失败', icon: 'none' }))
  },
  goBack() {
    wx.navigateBack()
  },
})

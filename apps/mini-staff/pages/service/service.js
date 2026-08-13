const api = require('../../utils/api.js')

function rid(prefix) {
  return `${prefix}-${Date.now()}`
}

function statusLabel(status) {
  return ({
    BOOKED: '已预约',
    CHECKED_IN: '已到店',
    IN_SERVICE: '服务中',
    COMPLETED: '已完成',
  })[status] || status || '未知'
}

Page({
  data: {
    orderId: '',
    next: {},
    status: '',
    statusLabel: '',
    notes: [],
    draft: '',
    draftHint: '力度偏好、禁忌；提交后不可改',
    consented: false,
    timerText: '00:00',
    busy: false,
    error: '',
    startedAt: 0,
  },
  onLoad(query) {
    this.setData({ orderId: (query && query.orderId) || '' })
    this.ensureToken().then(() => this.refresh()).catch((err) => {
      this.setData({ error: err.message || '登录失败' })
    })
  },
  onUnload() {
    if (this._tick) {
      clearInterval(this._tick)
    }
  },
  ensureToken() {
    const app = getApp()
    if (app.globalData.token) {
      return Promise.resolve(app.globalData.token)
    }
    return api.loginTherapist().then((data) => {
      app.globalData.token = data.token
      app.globalData.staffName = data.name || '林晓'
      return data.token
    })
  },
  refresh() {
    const app = getApp()
    const token = app.globalData.token
    const orderId = this.data.orderId
    if (!orderId) {
      this.setData({ error: '缺少订单' })
      return Promise.resolve()
    }
    return api.request({ url: `/api/v1/t/orders/${orderId}`, token }).then((card) => {
      const status = (card && card.status) || this.data.status
      this.setData({
        next: card || {},
        status,
        statusLabel: statusLabel(status),
      })
      if (status === 'IN_SERVICE' && !this.data.startedAt) {
        this.beginTimer(Date.now())
      }
      return this.loadNotes(token)
    }).catch((err) => {
      this.setData({ error: err.message || '加载失败' })
    })
  },
  loadNotes(token) {
    if (!this.data.orderId) {
      return Promise.resolve()
    }
    return api.request({
      url: `/api/v1/t/orders/${this.data.orderId}/notes`,
      token,
    }).then((data) => {
      const items = (data && data.items) || []
      const last = items.length ? items[items.length - 1].content : ''
      this.setData({
        notes: items,
        consented: !!(data && data.consented),
        draftHint: last ? `参考上次：${last}` : '力度偏好、禁忌；提交后不可改',
      })
    }).catch(() => {
      this.setData({ notes: [] })
    })
  },
  giveConsent() {
    this.act(`/api/v1/t/orders/${this.data.orderId}/consent`, null, () => {
      this.setData({ consented: true })
    }, {})
  },
  beginTimer(startedAt) {
    this.setData({ startedAt })
    if (this._tick) {
      clearInterval(this._tick)
    }
    const tick = () => {
      const sec = Math.max(0, Math.floor((Date.now() - this.data.startedAt) / 1000))
      const mm = String(Math.floor(sec / 60)).padStart(2, '0')
      const ss = String(sec % 60).padStart(2, '0')
      this.setData({ timerText: `${mm}:${ss}` })
    }
    tick()
    this._tick = setInterval(tick, 1000)
  },
  startService() {
    this.act(`/api/v1/t/orders/${this.data.orderId}/start`, rid('start'), (data) => {
      this.setData({ status: data.status, statusLabel: statusLabel(data.status) })
      this.beginTimer(Date.now())
    })
  },
  completeService() {
    this.act(`/api/v1/t/orders/${this.data.orderId}/complete`, rid('complete'), (data) => {
      this.setData({ status: data.status, statusLabel: statusLabel(data.status) })
      if (this._tick) {
        clearInterval(this._tick)
      }
    })
  },
  onDraft(e) {
    this.setData({ draft: e.detail.value })
  },
  appendNote() {
    const content = (this.data.draft || '').trim()
    if (!content) {
      return
    }
    this.act(`/api/v1/t/orders/${this.data.orderId}/notes`, null, () => {
      this.setData({ draft: '' })
      this.loadNotes(getApp().globalData.token)
    }, { content })
  },
  act(url, requestId, onOk, extra) {
    const app = getApp()
    this.setData({ busy: true, error: '' })
    const data = extra || { requestId }
    return api.request({ url, method: 'POST', data, token: app.globalData.token })
      .then((res) => {
        this.setData({ busy: false })
        if (onOk) {
          onOk(res || {})
        }
      })
      .catch((err) => {
        this.setData({ busy: false, error: err.message || '操作失败' })
      })
  },
})

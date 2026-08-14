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

const METHODS = [
  { id: 'roll', name: '滚法', on: true },
  { id: 'pinch', name: '拿捏', on: true },
  { id: 'press', name: '点按', on: false },
  { id: 'stretch', name: '拔伸', on: false },
]

const FORCES = [
  { id: 'light', name: '轻', on: false },
  { id: 'mid', name: '中', on: true },
  { id: 'heavy', name: '重', on: false },
]

Page({
  data: {
    orderId: '',
    next: {},
    status: '',
    statusLabel: '',
    notes: [],
    chief: '',
    caution: '',
    methods: METHODS.map((m) => ({ ...m })),
    forces: FORCES.map((f) => ({ ...f })),
    consented: false,
    timerText: '00:00',
    busy: false,
    error: '',
    startedAt: 0,
    endAt: 0,
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
      app.globalData.staffName = data.name || '技师'
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
      this.syncEnd(card)
      if (status === 'IN_SERVICE' && !this.data.startedAt) {
        this.beginTimer(Date.now())
      } else if (status !== 'IN_SERVICE') {
        this.paintRemain()
      }
      return this.loadNotes(token)
    }).catch((err) => {
      this.setData({ error: err.message || '加载失败' })
    })
  },
  syncEnd(card) {
    if (!card || !card.end) {
      return
    }
    const parts = String(card.end).split(':')
    const end = new Date()
    end.setHours(Number(parts[0] || 0), Number(parts[1] || 0), 0, 0)
    this.setData({ endAt: end.getTime() })
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
      this.setData({
        notes: items,
        consented: !!(data && data.consented),
      })
    }).catch(() => {
      this.setData({ notes: [] })
    })
  },
  beginTimer(startedAt) {
    this.setData({ startedAt })
    if (this._tick) {
      clearInterval(this._tick)
    }
    const tick = () => this.paintRemain()
    tick()
    this._tick = setInterval(tick, 1000)
  },
  paintRemain() {
    const endAt = this.data.endAt
    let ms
    if (endAt) {
      ms = Math.max(0, endAt - Date.now())
    } else if (this.data.startedAt) {
      ms = Math.max(0, Date.now() - this.data.startedAt)
    } else {
      this.setData({ timerText: '60:00' })
      return
    }
    const sec = Math.floor(ms / 1000)
    const mm = String(Math.floor(sec / 60)).padStart(2, '0')
    const ss = String(sec % 60).padStart(2, '0')
    this.setData({ timerText: `${mm}:${ss}` })
  },
  startService() {
    if (this.data.busy || this.data.status === 'IN_SERVICE' || this.data.status === 'COMPLETED') {
      return
    }
    this.act(`/api/v1/t/orders/${this.data.orderId}/start`, rid('start'), (data) => {
      this.setData({ status: data.status, statusLabel: statusLabel(data.status) })
      this.beginTimer(Date.now())
    })
  },
  completeService() {
    if (this.data.busy || this.data.status !== 'IN_SERVICE') {
      return
    }
    this.act(`/api/v1/t/orders/${this.data.orderId}/complete`, rid('complete'), (data) => {
      this.setData({ status: data.status, statusLabel: statusLabel(data.status) })
      if (this._tick) {
        clearInterval(this._tick)
      }
    })
  },
  askAddon() {
    wx.showToast({ title: '请前台在收银页加钟', icon: 'none' })
  },
  onChief(e) {
    this.setData({ chief: e.detail.value })
  },
  onCaution(e) {
    this.setData({ caution: e.detail.value })
  },
  toggleMethod(e) {
    const id = e.currentTarget.dataset.id
    const methods = this.data.methods.map((m) => m.id === id ? { ...m, on: !m.on } : m)
    this.setData({ methods })
  },
  pickForce(e) {
    const id = e.currentTarget.dataset.id
    const forces = this.data.forces.map((f) => ({ ...f, on: f.id === id }))
    this.setData({ forces })
  },
  toggleConsent() {
    if (this.data.consented) {
      return
    }
    this.act(`/api/v1/t/orders/${this.data.orderId}/consent`, null, () => {
      this.setData({ consented: true })
    }, {})
  },
  composeNote() {
    const methods = this.data.methods.filter((m) => m.on).map((m) => m.name).join('、') || '未选'
    const force = (this.data.forces.find((f) => f.on) || {}).name || '中'
    const chief = (this.data.chief || '').trim() || '未填写'
    const caution = (this.data.caution || '').trim() || '无'
    return `主诉：${chief}\n手法：${methods}\n力度：${force}\n禁忌与提醒：${caution}\n已口头告知：是`
  },
  submitAndClose() {
    if (this.data.busy) {
      return
    }
    if (!this.data.consented) {
      wx.showToast({ title: '请先勾选口头告知', icon: 'none' })
      return
    }
    const content = this.composeNote()
    const finish = () => {
      if (this.data.status === 'IN_SERVICE') {
        this.completeService()
      } else if (this.data.status !== 'COMPLETED') {
        this.startService()
      } else {
        wx.showToast({ title: '已结单', icon: 'none' })
      }
    }
    this.act(`/api/v1/t/orders/${this.data.orderId}/notes`, null, () => {
      this.setData({ chief: '' })
      this.loadNotes(getApp().globalData.token)
      finish()
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

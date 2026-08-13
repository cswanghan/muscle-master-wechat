const STORE = '3100000000000000001'
const THERAPIST = '3100000000000000401'
const PROJECT = '3100000000000000501'

Page({
  data: {
    token: '',
    staffName: '未登录',
    keyword: '',
    hits: [],
    checkInText: '',
    phone: '18600001111',
    customerName: '王先生',
    date: '2026-08-14',
    startSlotNo: 64,
    alreadyInStore: true,
    payChannel: 'WECHAT',
    walkText: '',
    codeUrl: '',
    pollStatus: '',
    error: '',
  },

  onUnload() {
    this.stopPoll()
  },

  stopPoll() {
    if (this._poll) {
      clearInterval(this._poll)
      this._poll = null
    }
  },

  apiBase() {
    return (typeof getApp === 'function' && getApp().globalData && getApp().globalData.apiBase)
      || 'http://127.0.0.1:8080'
  },

  request(path, method, data) {
    const header = { 'Content-Type': 'application/json' }
    if (this.data.token) {
      header.Authorization = 'Bearer ' + this.data.token
    }
    return new Promise((resolve, reject) => {
      wx.request({
        url: this.apiBase() + path,
        method,
        header,
        data: data || {},
        success: (res) => {
          const body = res.data || {}
          if (res.statusCode >= 400 || body.code) {
            reject(new Error(body.message || 'HTTP ' + res.statusCode))
            return
          }
          resolve(body.data)
        },
        fail: (err) => reject(err),
      })
    })
  },

  async onLogin() {
    this.setData({ error: '' })
    try {
      const data = await this.request('/api/v1/staff/auth/wechat', 'POST', { code: 'dev-staff-front' })
      this.setData({ token: data.token, staffName: data.name || '前台' })
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },

  onKeyword(e) {
    this.setData({ keyword: e.detail.value })
  },

  onPhone(e) {
    this.setData({ phone: e.detail.value })
  },

  onName(e) {
    this.setData({ customerName: e.detail.value })
  },

  onToggleAlready() {
    this.setData({ alreadyInStore: !this.data.alreadyInStore })
  },

  onChannel(e) {
    this.setData({ payChannel: e.currentTarget.dataset.channel })
  },

  async onLookup() {
    this.setData({ error: '', checkInText: '' })
    try {
      const data = await this.request(
        '/api/v1/f/orders/lookup?keyword=' + encodeURIComponent(this.data.keyword),
        'GET')
      this.setData({ hits: data.items || [] })
    } catch (e) {
      this.setData({ hits: [], error: e.message || String(e) })
    }
  },

  async onCheckIn(e) {
    const orderId = e.currentTarget.dataset.id
    this.setData({ error: '' })
    try {
      const data = await this.request('/api/v1/f/orders/' + orderId + '/check-in', 'POST', {
        requestId: 'ci-' + Date.now(),
        keyword: this.data.keyword,
      })
      this.setData({
        checkInText: data.status + ' · ' + data.roomName + ' ' + data.bedName + ' · ' + data.customerMask,
      })
    } catch (err) {
      this.setData({ error: err.message || String(err) })
    }
  },

  async onWalkIn() {
    this.setData({ error: '', walkText: '', codeUrl: '', pollStatus: '' })
    this.stopPoll()
    try {
      const data = await this.request('/api/v1/f/walk-ins', 'POST', {
        requestId: 'wi-' + Date.now(),
        phone: this.data.phone,
        customerName: this.data.customerName,
        storeId: STORE,
        therapistId: THERAPIST,
        projectId: PROJECT,
        date: this.data.date,
        startSlotNo: this.data.startSlotNo,
        alreadyInStore: this.data.alreadyInStore,
        payChannel: this.data.payChannel,
      })
      this.setData({
        walkText: data.orderNo + ' · ' + data.payChannel + ' · ' + data.status,
        codeUrl: data.codeUrl || '',
      })
      if (data.payChannel === 'WECHAT' && data.paymentNo) {
        this.startPoll(data.paymentNo, data.orderId, data.alreadyInStore)
      }
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },

  startPoll(paymentNo, orderId, already) {
    this.setData({ pollStatus: 'PENDING' })
    this._poll = setInterval(async () => {
      try {
        const view = await this.request('/api/v1/f/payments/' + paymentNo, 'GET')
        this.setData({ pollStatus: view.status })
        if (view.status === 'SUCCESS' || view.status === 'CLOSED' || view.status === 'FAILED') {
          this.stopPoll()
          if (view.status === 'SUCCESS' && already) {
            await this.onCheckIn({ currentTarget: { dataset: { id: orderId } } })
          }
        }
      } catch (e) {
        this.setData({ pollStatus: e.message || String(e) })
      }
    }, 1500)
  },
})

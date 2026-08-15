const qrcode = require('../../../utils/qrcode.js')
const staffApi = require('../../../utils/staff-api.js')

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
    qrReady: false,
    error: '',
    action: 'addon',
    actionOrderId: '',
    addOnMinutes: 30,
    swapTherapistId: '3100000000000000402',
    rescheduleDate: '2026-08-14',
    rescheduleStart: 72,
    actionText: '',
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

  // Was a private wx.request against globalData.apiBase, which bypasses the
  // shared transport and therefore dies under transport:'container'.
  request(path, method, data) {
    return staffApi.request({ url: path, method, data: data || {}, token: this.data.token })
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

  async checkInOrder(orderId, verify, keyword) {
    this.setData({ error: '' })
    const data = await this.request('/api/v1/f/orders/' + orderId + '/check-in', 'POST', {
      requestId: 'ci-' + Date.now(),
      verify,
      keyword,
    })
    this.setData({
      checkInText: data.status + ' · ' + data.roomName + ' ' + data.bedName + ' · ' + data.customerMask,
    })
  },

  async onCheckIn(e) {
    const orderId = e.currentTarget.dataset.id
    const orderNo = e.currentTarget.dataset.orderno
    try {
      await this.checkInOrder(orderId, 'ORDER_NO', orderNo)
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
        qrReady: false,
      })
      if (data.codeUrl) {
        this.drawQr(data.codeUrl)
      }
      if (data.payChannel === 'WECHAT' && data.paymentNo) {
        this.startPoll(data.paymentNo, data.orderId, data.alreadyInStore)
      }
      if (data.orderId) {
        this.setData({ actionOrderId: data.orderId })
      }
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },

  onAction(e) {
    this.setData({ action: e.currentTarget.dataset.action, actionText: '' })
  },
  onActionOrder(e) {
    this.setData({ actionOrderId: e.detail.value })
  },
  onSwapTherapist(e) {
    this.setData({ swapTherapistId: e.detail.value })
  },
  onRsDate(e) {
    this.setData({ rescheduleDate: e.detail.value })
  },
  onRsStart(e) {
    this.setData({ rescheduleStart: Number(e.detail.value || 0) })
  },
  requireOrder() {
    const id = (this.data.actionOrderId || '').trim()
    if (!id) {
      this.setData({ error: '请填写订单 ID' })
      return ''
    }
    return id
  },
  async onAddOn() {
    const id = this.requireOrder()
    if (!id) return
    try {
      const data = await this.request('/api/v1/f/orders/' + id + '/add-on', 'POST', {
        requestId: 'ao-' + Date.now(),
        projectId: PROJECT,
        durationMinutes: this.data.addOnMinutes,
        payChannel: this.data.payChannel,
      })
      this.setData({ actionText: '加钟成功 · ' + (data.status || '') + ' · 至 slot ' + (data.endSlotNo || ''), error: '' })
      if (data.codeUrl) {
        this.setData({ codeUrl: data.codeUrl })
        this.drawQr(data.codeUrl)
      }
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },
  async onSwap() {
    const id = this.requireOrder()
    if (!id) return
    try {
      const data = await this.request('/api/v1/f/orders/' + id + '/swap-therapist', 'POST', {
        requestId: 'sw-' + Date.now(),
        newTherapistId: this.data.swapTherapistId,
        reason: '前台换师',
      })
      this.setData({ actionText: '已换师 · ' + (data.newTherapistId || ''), error: '' })
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },
  async onReschedule() {
    const id = this.requireOrder()
    if (!id) return
    try {
      const data = await this.request('/api/v1/f/orders/' + id + '/reschedule', 'POST', {
        requestId: 'rs-' + Date.now(),
        date: this.data.rescheduleDate,
        startSlotNo: this.data.rescheduleStart,
        therapistId: THERAPIST,
      })
      this.setData({ actionText: '已改约 · ' + (data.status || ''), error: '' })
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },
  async onRefund() {
    const id = this.requireOrder()
    if (!id) return
    try {
      const data = await this.request('/api/v1/f/orders/' + id + '/refund', 'POST', {
        requestId: 'rf-' + Date.now(),
        amountFen: 0,
        reason: '前台退款',
      })
      this.setData({ actionText: '退款已提交 · ' + (data.status || ''), error: '' })
    } catch (e) {
      this.setData({ error: e.message || String(e) })
    }
  },

  drawQr(text) {
    const modules = qrcode.modules(text)
    if (!modules || !modules.length) {
      this.setData({ qrReady: false })
      return
    }
    const size = 280
    const n = modules.length
    const cell = size / (n + 2)
    const ctx = wx.createCanvasContext('native-qr', this)
    ctx.setFillStyle('#ffffff')
    ctx.fillRect(0, 0, size, size)
    ctx.setFillStyle('#14352c')
    for (let y = 0; y < n; y++) {
      for (let x = 0; x < n; x++) {
        if (modules[y][x]) {
          ctx.fillRect((x + 1) * cell, (y + 1) * cell, cell, cell)
        }
      }
    }
    ctx.draw(false, () => this.setData({ qrReady: true }))
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
            await this.checkInOrder(orderId, 'PHONE', this.data.phone)
          }
        }
      } catch (e) {
        this.setData({ pollStatus: e.message || String(e) })
      }
    }, 1500)
  },
})

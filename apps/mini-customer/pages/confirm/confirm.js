const { request, ensureLogin, rid } = require('../../utils/api.js')
const { fenYuan, remainMs, mmss } = require('../../utils/format.js')

Page({
  data: {
    storeId: '',
    storeName: '',
    therapistId: '',
    therapistName: '',
    projectId: '',
    projectName: '',
    date: '',
    startSlotNo: 0,
    start: '',
    priceFen: 0,
    priceYuan: '0',
    durationMinutes: 60,
    bufferMinutes: 15,
    orderId: '',
    orderNo: '',
    status: '',
    lockExpireAt: '',
    remain: '15:00',
    expired: false,
    paying: false,
    paid: false,
    error: '',
    payParams: null,
  },
  timer: null,
  onLoad(query) {
    this.setData({
      storeId: query.storeId || '',
      storeName: query.storeName ? decodeURIComponent(query.storeName) : '',
      therapistId: query.therapistId || '',
      therapistName: query.therapistName ? decodeURIComponent(query.therapistName) : '',
      projectId: query.projectId || '',
      projectName: query.projectName ? decodeURIComponent(query.projectName) : '',
      date: query.date || '',
      startSlotNo: Number(query.startSlotNo || 0),
      start: query.start || '',
      priceFen: Number(query.priceFen || 0),
      priceYuan: fenYuan(query.priceFen || 0),
      durationMinutes: Number(query.durationMinutes || 60),
      bufferMinutes: Number(query.bufferMinutes || 15),
      orderId: query.orderId || '',
    })
    if (this.data.orderId) {
      this.setData({ status: 'PENDING_PAY' })
    }
  },
  onUnload() {
    if (this.timer) {
      clearInterval(this.timer)
    }
  },
  tick() {
    const ms = remainMs(this.data.lockExpireAt)
    this.setData({ remain: mmss(ms), expired: ms <= 0 })
    if (ms <= 0 && this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  },
  startTick(lockExpireAt) {
    this.setData({ lockExpireAt })
    this.tick()
    if (this.timer) {
      clearInterval(this.timer)
    }
    this.timer = setInterval(() => this.tick(), 1000)
  },
  lockOrder() {
    if (this.data.paying || this.data.paid || this.data.orderId) {
      return
    }
    this.setData({ paying: true, error: '' })
    ensureLogin()
      .then(() => this.ensureOrder())
      .then(() => this.setData({ paying: false }))
      .catch((err) => {
        this.setData({ paying: false, error: err.message || '下单失败' })
      })
  },
  lockAndPay() {
    if (this.data.paying || this.data.paid) {
      return
    }
    this.setData({ paying: true, error: '' })
    ensureLogin()
      .then(() => this.ensureOrder())
      .then((order) => this.payOrder(order))
      .catch((err) => {
        this.setData({ paying: false, error: err.message || '下单失败' })
      })
  },
  ensureOrder() {
    if (this.data.orderId && this.data.status === 'PENDING_PAY') {
      return Promise.resolve({
        orderId: this.data.orderId,
        orderNo: this.data.orderNo,
        status: this.data.status,
        lockExpireAt: this.data.lockExpireAt,
        payableFen: this.data.priceFen,
      })
    }
    return request({
      path: '/api/v1/c/bookings',
      method: 'POST',
      auth: true,
      data: {
        requestId: rid('book'),
        storeId: this.data.storeId,
        therapistId: this.data.therapistId,
        projectId: this.data.projectId,
        date: this.data.date,
        startSlotNo: this.data.startSlotNo,
      },
    }).then((data) => {
      this.setData({
        orderId: data.orderId,
        orderNo: data.orderNo,
        status: data.status,
        priceFen: data.payableFen,
        priceYuan: fenYuan(data.payableFen),
        payParams: data.payParams || null,
      })
      this.startTick(data.lockExpireAt)
      return data
    })
  },
  payOrder(order) {
    if (this.data.expired) {
      this.setData({ paying: false, error: '锁已过期' })
      return Promise.resolve()
    }
    return request({
      path: `/api/v1/c/bookings/${order.orderId}/pay`,
      method: 'POST',
      auth: true,
      data: { requestId: rid('pay') },
    }).then((pay) => this.mockNotify(pay))
  },
  mockNotify(pay) {
    const tryWxPay = typeof wx.requestPayment === 'function'
      ? new Promise((resolve) => {
        wx.requestPayment({
          timeStamp: pay.payParams && pay.payParams.timeStamp,
          nonceStr: pay.payParams && pay.payParams.nonceStr,
          package: pay.payParams && pay.payParams.package,
          signType: pay.payParams && pay.payParams.signType,
          paySign: pay.payParams && pay.payParams.paySign,
          success: () => resolve('wx'),
          fail: () => resolve('mock'),
        })
      })
      : Promise.resolve('mock')
    return tryWxPay.then((mode) => {
      if (mode === 'wx') {
        this.setData({ paying: false, paid: true, status: 'BOOKED' })
        return
      }
      return request({
        path: '/api/v1/pay/wechat/notify',
        method: 'POST',
        data: {
          out_trade_no: pay.paymentNo,
          transaction_id: 'wx_mock_' + pay.paymentNo,
          amount_fen: pay.amountFen,
        },
      }).then(() => {
        this.setData({ paying: false, paid: true, status: 'BOOKED' })
      })
    })
  },
  cancelOrder() {
    if (!this.data.orderId || this.data.paid) {
      return
    }
    this.setData({ error: '' })
    ensureLogin()
      .then(() => request({
        path: `/api/v1/c/bookings/${this.data.orderId}/cancel`,
        method: 'POST',
        auth: true,
        data: { requestId: rid('cancel'), reason: 'user' },
      }))
      .then((data) => {
        this.setData({ status: data.status || 'CLOSED' })
        wx.showToast({ title: '已取消', icon: 'none' })
      })
      .catch((err) => {
        this.setData({ error: err.message || '取消失败' })
      })
  },
  goMine() {
    wx.redirectTo({ url: '/pages/mine/mine' })
  },
})

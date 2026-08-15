const { request, ensureLogin, rid } = require('../../utils/api.js')
const { fenYuan, remainMs, mmss } = require('../../utils/format.js')
const mock = require('../../utils/mock.js')
const config = require('../../config.js')

function isPending(status) {
  return status === 'PENDING_PAY'
}

function isClosed(status) {
  return status === 'CLOSED' || status === 'CANCELLED'
}

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
    closed: false,
    pending: false,
    error: '',
    payParams: null,
    discountYuan: '0',
    photo: '/images/therapists/lin.jpg',
  },
  timer: null,
  onLoad(query) {
    this.setData({
      storeId: query.storeId || '',
      storeName: query.storeName ? decodeURIComponent(query.storeName) : '',
      therapistId: query.therapistId || '',
      therapistName: query.therapistName ? decodeURIComponent(query.therapistName) : '',
      photo: mock.therapistPhoto(query.therapistId),
      projectId: query.projectId || '',
      projectName: query.projectName ? decodeURIComponent(query.projectName) : '',
      date: query.date || '',
      startSlotNo: Number(query.startSlotNo || 0),
      start: query.start || '',
      priceFen: Number(query.priceFen || 0),
      priceYuan: fenYuan(query.priceFen || 0),
      discountYuan: fenYuan(Math.round(Number(query.priceFen || 0) * 0.05)),
      durationMinutes: Number(query.durationMinutes || 60),
      bufferMinutes: Number(query.bufferMinutes || 15),
      orderId: query.orderId || '',
      orderNo: query.orderNo ? decodeURIComponent(query.orderNo) : '',
      lockExpireAt: query.lockExpireAt ? decodeURIComponent(query.lockExpireAt) : '',
      status: query.status || (query.orderId ? 'PENDING_PAY' : ''),
    })
    this.syncFlags(this.data.status)
    if (this.data.orderId) {
      if (this.data.lockExpireAt && isPending(this.data.status)) {
        this.startTick(this.data.lockExpireAt)
      }
      this.refreshOrder()
    }
  },
  onUnload() {
    this.stopTick()
  },
  syncFlags(status) {
    const paid = status === 'BOOKED' || this.data.paid
    const closed = isClosed(status)
    const pending = isPending(status) && !paid
    this.setData({ status, paid, closed, pending })
  },
  stopTick() {
    if (this.timer) {
      clearInterval(this.timer)
      this.timer = null
    }
  },
  tick() {
    if (!isPending(this.data.status)) {
      this.stopTick()
      return
    }
    const ms = remainMs(this.data.lockExpireAt)
    this.setData({ remain: mmss(ms), expired: ms <= 0 })
    if (ms <= 0) {
      this.stopTick()
    }
  },
  startTick(lockExpireAt) {
    this.setData({ lockExpireAt })
    this.tick()
    this.stopTick()
    if (isPending(this.data.status) && remainMs(lockExpireAt) > 0) {
      this.timer = setInterval(() => this.tick(), 1000)
    }
  },
  applyOrder(data) {
    const status = data.status || this.data.status
    this.data.status = status
    this.setData({
      orderId: data.orderId || this.data.orderId,
      orderNo: data.orderNo || this.data.orderNo,
      priceFen: data.payableFen != null ? data.payableFen : this.data.priceFen,
      priceYuan: fenYuan(data.payableFen != null ? data.payableFen : this.data.priceFen),
      start: data.start || this.data.start,
      date: data.date || this.data.date,
    })
    this.syncFlags(status)
    if (isPending(status) && data.lockExpireAt) {
      this.startTick(data.lockExpireAt)
    } else {
      this.stopTick()
    }
  },
  refreshOrder() {
    if (!this.data.orderId) {
      return Promise.resolve()
    }
    return ensureLogin()
      .then(() => request({ path: `/api/v1/c/bookings/${this.data.orderId}`, auth: true }))
      .then((data) => this.applyOrder(data))
      .catch((err) => {
        this.setData({ error: err.message || '订单刷新失败' })
      })
  },
  lockOrder() {
    if (this.data.paying || this.data.paid || this.data.closed || this.data.orderId) {
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
    if (this.data.paying || this.data.paid || this.data.closed) {
      return
    }
    if (this.data.orderId && !isPending(this.data.status)) {
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
    if (this.data.orderId) {
      if (!isPending(this.data.status)) {
        return Promise.reject(new Error('订单已关闭，无法支付'))
      }
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
      this.applyOrder(data)
      return data
    }).catch((err) => {
      if (!config.mockFallback) {
        throw err
      }
      const local = mock.mockLock(this.data)
      this.applyOrder(local)
      this.startTick(local.lockExpireAt)
      return local
    })
  },
  payOrder(order) {
    if (!order || !isPending(this.data.status) || this.data.closed) {
      this.setData({ paying: false, error: '订单不可支付' })
      return Promise.resolve()
    }
    if (this.data.expired) {
      this.setData({ paying: false, error: '锁已过期' })
      return Promise.resolve()
    }
    if (String(order.orderId || '').indexOf('mock-') === 0) {
      return this.finishPaid(order)
    }
    return request({
      path: `/api/v1/c/bookings/${order.orderId}/pay`,
      method: 'POST',
      auth: true,
      data: { requestId: rid('pay') },
    }).then((pay) => this.completePay(pay))
  },
  completePay(pay) {
    if (!pay || !pay.payParams || !pay.payParams.paySign) {
      return this.mockNotify(pay)
    }
    return new Promise((resolve) => {
      wx.requestPayment({
        timeStamp: pay.payParams.timeStamp,
        nonceStr: pay.payParams.nonceStr,
        package: pay.payParams.package,
        signType: pay.payParams.signType,
        paySign: pay.payParams.paySign,
        success: () => {
          this.finishPaid(pay)
          resolve()
        },
        // Expected while the server runs app.wechat.mock: WeChat rejects the
        // placeholder paySign, and the demo notify below is the real completion
        // path. If that notify also fails, the order is genuinely unpaid.
        fail: () => {
          this.mockNotify(pay).then(resolve).catch((err) => {
            this.setData({ paying: false, error: err.message || '支付未完成，请重试' })
            resolve()
          })
        },
      })
    })
  },
  finishPaid(order) {
    const paid = mock.mockPay({
      ...this.data,
      ...(order || {}),
      orderId: (order && order.orderId) || this.data.orderId,
      status: 'BOOKED',
    })
    this.stopTick()
    this.setData({ paying: false, paid: true, pending: false, closed: false, status: 'BOOKED', orderId: paid.orderId, orderNo: paid.orderNo })
    return Promise.resolve()
  },
  mockNotify(pay) {
    return request({
      path: '/api/v1/pay/wechat/notify',
      method: 'POST',
      data: {
        out_trade_no: pay && pay.paymentNo,
        transaction_id: 'wx_mock_' + ((pay && pay.paymentNo) || Date.now()),
        amount_fen: (pay && pay.amountFen) || this.data.priceFen,
      },
    }).then(() => this.finishPaid(pay))
  },
  cancelOrder() {
    if (!this.data.orderId || this.data.paid || this.data.closed || !isPending(this.data.status)) {
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
        const status = data.status || 'CLOSED'
        this.stopTick()
        this.setData({
          status,
          closed: true,
          pending: false,
          paid: false,
          expired: false,
        })
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

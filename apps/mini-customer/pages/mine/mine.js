const { request, ensureLogin } = require('../../utils/api.js')
const { fenYuan, statusLabel, isOngoing, levelLabel, rating } = require('../../utils/format.js')
const { qs } = require('../../utils/query.js')
const config = require('../../config.js')

const TXN_SIGN = { TOPUP: '+', REFUND: '+' }

function toWallet(card) {
  if (!card) {
    return { principalYuan: '0.00', bonusYuan: '0.00', balanceYuan: '0.00', txns: [] }
  }
  return {
    principalYuan: fenYuan(card.principalFen),
    bonusYuan: fenYuan(card.bonusFen),
    balanceYuan: fenYuan(card.balanceFen),
    txns: (card.txns || []).map((t) => ({
      ...t,
      // 后端给的 deltaFen 消费为负；这里只取绝对值，正负交给符号表达。
      sign: TXN_SIGN[t.type] || '-',
      amountYuan: fenYuan(Math.abs(t.deltaFen || 0)),
      day: (t.createdAt || '').slice(0, 10),
    })),
  }
}

Page({
  data: {
    customerId: '',
    logged: false,
    ongoing: [],
    orders: [],
    therapists: {},
    stores: {},
    loading: true,
    error: '',
    version: config.version,
    transport: config.transport,
    staffEntry: false,
    wallet: { principalYuan: '0.00', bonusYuan: '0.00', balanceYuan: '0.00', txns: [] },
    remainingSessions: 0,
  },
  onShow() {
    this.reload()
  },
  reload() {
    this.setData({ loading: true, error: '' })
    ensureLogin()
      .then((auth) => {
        this.setData({ logged: true, customerId: auth.customerId || wx.getStorageSync('customerId') })
        return Promise.all([
          request({ path: '/api/v1/c/bookings', auth: true }),
          request({ path: '/api/v1/c/therapists' }),
          request({ path: '/api/v1/c/stores' }),
          // 钱包挂了不该把整页拖垮：余额看不到是小事，订单看不到是大事。
          request({ path: '/api/v1/c/card', auth: true }).catch(() => null),
          request({ path: '/api/v1/c/membership', auth: true }).catch(() => null),
        ])
      })
      .then(([page, tPage, sPage, card, mem]) => {
        const therapistNames = {}
        const therapistLabels = {}
        ;((tPage && tPage.items) || []).forEach((t) => {
          therapistNames[t.therapistId] = t.name
          therapistLabels[t.therapistId] = t.name + ' · ' + levelLabel(t.level) + ' · ' + rating(t.ratingX100)
        })
        const stores = {}
        ;((sPage && sPage.items) || []).forEach((s) => {
          stores[s.storeId] = s.name
        })
        const orders = ((page && page.items) || []).map((o) => ({
          ...o,
          priceYuan: fenYuan(o.payableFen),
          statusLabel: statusLabel(o.status),
          therapistName: therapistNames[o.therapistId] || o.therapistId,
          therapistLabel: therapistLabels[o.therapistId] || o.therapistId,
          storeLabel: stores[o.storeId] || o.storeId,
          // 只有 COMPLETED / REVIEWED 两态出口子；其余状态不放按钮，免得点进去只能看报错。
          reviewCta: o.status === 'COMPLETED' ? '去评价' : (o.status === 'REVIEWED' ? '查看评价' : ''),
          reviewDone: o.status === 'REVIEWED',
        }))
        this.setData({
          orders,
          ongoing: orders.filter((o) => isOngoing(o.status)),
          therapists: therapistNames,
          stores,
          wallet: toWallet(card),
          remainingSessions: (mem && mem.remainingSessions) || 0,
          loading: false,
        })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  openOrder(e) {
    const o = e.currentTarget.dataset.item
    if (o.status === 'PENDING_PAY') {
      wx.navigateTo({
        url: `/pages/confirm/confirm?orderId=${o.orderId}&orderNo=${encodeURIComponent(o.orderNo || '')}&status=${o.status}&lockExpireAt=${encodeURIComponent(o.lockExpireAt || '')}&storeId=${o.storeId}&storeName=${encodeURIComponent(o.storeLabel || '')}&therapistId=${o.therapistId}&therapistName=${encodeURIComponent(o.therapistName || '')}&date=${o.date}&startSlotNo=${o.startSlotNo}&start=${o.start}&priceFen=${o.payableFen}`,
      })
    }
  },
  openReview(e) {
    const o = e.currentTarget.dataset.item
    wx.navigateTo({
      url: '/pages/review/review?' + qs({
        orderId: o.orderId,
        orderNo: o.orderNo || '',
        therapistName: o.therapistName || '',
        date: o.date || '',
        start: o.start || '',
        mode: o.status === 'REVIEWED' ? 'view' : 'edit',
      }),
    })
  },
  // Staff screens live in this same mini program (one AppID, so they get
  // callContainer too). Five taps on the build line opens the door; it is not a
  // tab, so ordinary customers never see it.
  tapBuild() {
    this._taps = (this._taps || 0) + 1
    if (this._taps >= 5 && !this.data.staffEntry) {
      this.setData({ staffEntry: true })
      wx.showToast({ title: '员工入口已开启', icon: 'none' })
    }
  },
  // 储值收钱在前台（现金 / 扫码），小程序里没有充值入口。写清楚比放一个点不动的按钮强。
  onRecharge() {
    wx.showModal({
      title: '储值充值',
      content: '目前储值在门店前台办理，充完立刻可用。下单时余额会自动抵扣，不够的部分走微信支付。',
      showCancel: false,
      confirmText: '知道了',
    })
  },
  goMyCard() {
    wx.navigateTo({ url: '/pages/mycard/mycard' })
  },
  goCheckin() {
    wx.navigateTo({ url: '/pages/checkin/checkin' })
  },
  goStaff() {
    wx.navigateTo({ url: '/pages/staff/home/home' })
  },
  goHome() {
    wx.redirectTo({ url: '/pages/index/index' })
  },
})

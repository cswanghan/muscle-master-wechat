const { request, ensureLogin } = require('../../utils/api.js')
const { fenYuan, statusLabel, isOngoing, levelLabel, rating } = require('../../utils/format.js')

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
        ])
      })
      .then(([page, tPage, sPage]) => {
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
        }))
        this.setData({
          orders,
          ongoing: orders.filter((o) => isOngoing(o.status)),
          therapists: therapistNames,
          stores,
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
  goHome() {
    wx.redirectTo({ url: '/pages/index/index' })
  },
})

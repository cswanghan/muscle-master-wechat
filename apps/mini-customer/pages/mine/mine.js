const { request, ensureLogin, rid } = require('../../utils/api.js')
const { fenYuan, statusLabel, isOngoing, levelLabel, todayIso } = require('../../utils/format.js')
const mock = require('../../utils/mock.js')
const config = require('../../config.js')

function maskPhone(customerId) {
  const tail = String(customerId || '0000').replace(/\D/g, '').slice(-4) || '0000'
  return '186****' + tail
}

function whenLabel(order) {
  const date = order.date || ''
  const start = order.start || ''
  const today = todayIso()
  const day = date === today ? '今天' : date.slice(5).replace('-', '-')
  return (day + ' ' + start).trim()
}

function projectLabel(order) {
  return order.projectName || '到店调理'
}

Page({
  data: {
    customerId: '',
    logged: false,
    phoneMask: '未登录',
    visitCount: 0,
    favTherapist: '暂无',
    next: null,
    orders: [],
    loading: true,
    error: '',
    version: config.version,
    transport: config.transport,
    staffEntry: false,
  },
  onShow() {
    this.reload()
  },
  reload() {
    this.setData({ loading: true, error: '' })
    ensureLogin()
      .then((auth) => {
        const customerId = auth.customerId || wx.getStorageSync('customerId')
        this.setData({
          logged: true,
          customerId,
          phoneMask: maskPhone(customerId),
        })
        return Promise.all([
          request({ path: '/api/v1/c/bookings', auth: true }),
          request({ path: '/api/v1/c/therapists' }),
          request({ path: '/api/v1/c/stores' }),
          request({ path: '/api/v1/c/projects' }).catch(() => ({ items: [] })),
        ])
      })
      .then(([page, tPage, sPage, pPage]) => {
        const therapistNames = {}
        ;((tPage && tPage.items) || []).forEach((t) => {
          therapistNames[t.therapistId] = t.name
        })
        const stores = {}
        ;((sPage && sPage.items) || []).forEach((s) => {
          stores[s.storeId] = s.name
        })
        const projects = {}
        ;((pPage && pPage.items) || []).forEach((p) => {
          projects[p.projectId] = p.name
        })
        const freq = {}
        const remote = ((page && page.items) || [])
        // Locally faked orders would sit here looking exactly like real ones.
        const locals = config.mockFallback
          ? mock.readOrders().filter((o) => !remote.some((r) => String(r.orderId) === String(o.orderId)))
          : []
        const orders = remote.concat(locals).map((o) => {
          const therapistName = therapistNames[o.therapistId] || o.therapistId
          freq[therapistName] = (freq[therapistName] || 0) + 1
          return {
            ...o,
            priceYuan: fenYuan(o.payableFen),
            statusLabel: statusLabel(o.status),
            therapistName: o.therapistName || therapistName,
            therapistLabel: (o.therapistName || therapistName) + (o.level ? ' · ' + levelLabel(o.level) : ''),
            storeLabel: o.storeName || stores[o.storeId] || o.storeId,
            projectLabel: o.projectName || projects[o.projectId] || projectLabel(o),
            whenLabel: whenLabel(o),
          }
        })
        let favTherapist = '暂无'
        let max = 0
        Object.keys(freq).forEach((name) => {
          if (freq[name] > max) {
            max = freq[name]
            favTherapist = name
          }
        })
        const next = orders.find((o) => o.status === 'BOOKED' || o.status === 'CHECKED_IN' || o.status === 'IN_SERVICE')
          || orders.find((o) => isOngoing(o.status))
          || null
        const visitCount = orders.filter((o) => o.status === 'COMPLETED' || o.status === 'IN_SERVICE' || o.status === 'BOOKED').length
        this.setData({
          orders,
          next,
          favTherapist,
          visitCount,
          loading: false,
        })
      })
      .catch((err) => {
        if (!config.mockFallback) {
          // Was: fall back to local orders AND clear `error`, so an outage
          // rendered as a normal booking list.
          this.setData({
            orders: [],
            next: null,
            visitCount: 0,
            loading: false,
            error: err.message || '加载失败，请检查网络',
          })
          return
        }
        const orders = mock.readOrders().map((o) => ({
          ...o,
          priceYuan: fenYuan(o.payableFen),
          statusLabel: statusLabel(o.status),
          therapistName: o.therapistName || '林晓',
          storeLabel: o.storeName || mock.stores[0].name,
          projectLabel: o.projectName || '到店调理',
          whenLabel: whenLabel(o),
        }))
        this.setData({
          orders,
          next: orders.find((o) => o.status === 'BOOKED') || orders[0] || null,
          favTherapist: (orders[0] && orders[0].therapistName) || '林晓',
          visitCount: orders.length,
          loading: false,
          error: '',
        })
      })
  },
  // Staff screens live in this same mini program now (one AppID, so they get
  // callContainer too). Keep the door shut for ordinary customers: five taps
  // on the build line opens it, and it is not a tab.
  tapBuild() {
    this._taps = (this._taps || 0) + 1
    if (this._taps >= 5 && !this.data.staffEntry) {
      this.setData({ staffEntry: true })
      wx.showToast({ title: '员工入口已开启', icon: 'none' })
    }
  },
  goStaff() {
    wx.navigateTo({ url: '/pages/staff/home/home' })
  },
  findOrder(id) {
    return (this.data.orders || []).find((x) => String(x.orderId) === String(id))
      || (this.data.next && String(this.data.next.orderId) === String(id) ? this.data.next : null)
  },
  openOrder(e) {
    const o = this.findOrder(e.currentTarget.dataset.id)
    if (!o) {
      return
    }
    if (o.status === 'PENDING_PAY') {
      wx.navigateTo({
        url: `/pages/confirm/confirm?orderId=${o.orderId}&orderNo=${encodeURIComponent(o.orderNo || '')}&status=${o.status}&lockExpireAt=${encodeURIComponent(o.lockExpireAt || '')}&storeId=${o.storeId}&storeName=${encodeURIComponent(o.storeLabel || '')}&therapistId=${o.therapistId}&therapistName=${encodeURIComponent(o.therapistName || '')}&date=${o.date}&startSlotNo=${o.startSlotNo}&start=${o.start}&priceFen=${o.payableFen}`,
      })
    }
  },
  onCheckin(e) {
    const o = this.findOrder(e.currentTarget.dataset.id) || this.data.next
    wx.showModal({
      title: '核销码',
      content: '到店出示订单号\n' + (o && o.orderNo ? o.orderNo : '暂无'),
      showCancel: false,
    })
  },
  onReschedule() {
    wx.showToast({ title: '请联系前台改约', icon: 'none' })
  },
  onCancel(e) {
    const o = this.findOrder(e.currentTarget.dataset.id) || this.data.next
    if (!o || !o.orderId) {
      return
    }
    wx.showModal({
      title: '取消预约',
      content: '取消后时段将释放，确定取消？',
      success: (res) => {
        if (!res.confirm) {
          return
        }
        request({
          path: `/api/v1/c/bookings/${o.orderId}/cancel`,
          method: 'POST',
          auth: true,
          data: { requestId: rid('c6-cancel'), reason: '用户取消' },
        })
          .then(() => {
            wx.showToast({ title: '已取消', icon: 'none' })
            this.reload()
          })
          .catch((err) => {
            wx.showToast({ title: err.message || '取消失败', icon: 'none' })
          })
      },
    })
  },
  onRecharge() {
    wx.showToast({ title: 'P0 未开通储值', icon: 'none' })
  },
  openArchive() {
    const records = (this.data.orders || []).filter((o) => o.status === 'COMPLETED' || o.status === 'IN_SERVICE')
    if (!records.length) {
      wx.showToast({ title: '暂无理疗记录', icon: 'none' })
      return
    }
    const lines = records.slice(0, 8).map((o) => (o.whenLabel || '') + ' · ' + (o.projectLabel || '到店调理'))
    wx.showModal({
      title: '理疗档案 · ' + records.length + ' 次',
      content: lines.join('\n'),
      showCancel: false,
    })
  },
  soon() {
    wx.showToast({ title: '该入口尚未开通', icon: 'none' })
  },
  goCoupon() {
    wx.navigateTo({ url: '/pages/coupon/coupon' })
  },
  goMall() {
    wx.redirectTo({ url: '/pages/mall/mall' })
  },
  onAbout() {
    wx.showModal({
      title: '关于肌松大师',
      content: '对症推拿连锁 · 满班率北极星\n客户 / 技师 / 前台 / 店长 四端\nP0 演示包 v1.0',
      showCancel: false,
    })
  },
  onService() {
    wx.showModal({
      title: '联系客服',
      content: '前台微信 / 门店电话，P0 不接入在线客服',
      showCancel: false,
    })
  },
  scrollOrders() {
    wx.pageScrollTo({ selector: '#order-list', duration: 240 })
  },
  goHome() {
    wx.redirectTo({ url: '/pages/index/index' })
  },
})

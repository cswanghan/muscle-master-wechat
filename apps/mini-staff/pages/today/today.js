const api = require('../../utils/api.js')

function slotLabel(slotNo) {
  const hour = Math.floor(slotNo / 4)
  const minute = (slotNo % 4) * 15
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function etaText(minutes) {
  if (minutes == null) {
    return ''
  }
  if (minutes > 0) {
    return `${minutes} 分钟后开始`
  }
  if (minutes === 0) {
    return '现在开始'
  }
  return `已迟到 ${-minutes} 分钟`
}

Page({
  data: {
    staffName: '',
    loading: true,
    error: '',
    next: null,
    etaText: '',
    chips: [],
  },
  onShow() {
    this.ensureToken().then(() => this.loadToday()).catch((err) => {
      this.setData({ loading: false, error: err.message || '登录失败' })
    })
  },
  ensureToken() {
    const app = getApp()
    if (app.globalData.token) {
      this.setData({ staffName: app.globalData.staffName || '林晓' })
      return Promise.resolve(app.globalData.token)
    }
    return api.loginTherapist().then((data) => {
      app.globalData.token = data.token
      app.globalData.staffName = data.name || '林晓'
      this.setData({ staffName: app.globalData.staffName })
      return data.token
    })
  },
  loadToday() {
    const app = getApp()
    this.setData({ loading: true, error: '' })
    return api.request({ url: '/api/v1/t/today', token: app.globalData.token }).then((data) => {
      const next = data && data.next
      const timeline = (data && data.timeline) || []
      const chips = timeline
        .filter((slot) => slot.state !== 'FREE' || (next && String(slot.orderId) === String(next.orderId)))
        .slice(0, 16)
        .map((slot) => ({
          slotNo: slot.slotNo,
          label: slotLabel(slot.slotNo),
          state: slot.state,
          tone: slot.state === 'BOOKED' || slot.state === 'LOCKED' ? 'booked'
            : slot.state === 'BUFFER' || slot.state === 'REST' ? 'busy' : '',
        }))
      this.setData({
        next,
        etaText: next ? etaText(next.minutesToStart) : '',
        chips,
        loading: false,
      })
    }).catch((err) => {
      this.setData({ loading: false, error: err.message || '加载失败' })
    })
  },
  openService() {
    const next = this.data.next
    if (!next) {
      return
    }
    wx.navigateTo({
      url: `/pages/service/service?orderId=${next.orderId}`,
    })
  },
})

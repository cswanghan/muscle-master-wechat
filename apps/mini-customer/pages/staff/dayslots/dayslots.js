const api = require('../../../utils/staff-api.js')

function iso(dt) {
  const mm = String(dt.getMonth() + 1).padStart(2, '0')
  const dd = String(dt.getDate()).padStart(2, '0')
  return `${dt.getFullYear()}-${mm}-${dd}`
}

function addDays(day, n) {
  const [y, m, d] = day.split('-').map(Number)
  return iso(new Date(y, m - 1, d + n))
}

function weekday(day) {
  const [y, m, d] = day.split('-').map(Number)
  return '日一二三四五六'[new Date(y, m - 1, d).getDay()]
}

// 时段颜色只表达一件事：这一格能不能约。忙的原因用状态文字说。
function tone(s) {
  if (s.bookable) {
    return 'free'
  }
  if (s.orderId) {
    return 'booked'
  }
  return s.state === 'REST' ? 'rest' : 'busy'
}

Page({
  data: {
    date: '',
    dates: [],
    slots: [],
    freeCount: 0,
    bookedCount: 0,
    therapistName: '',
    loading: true,
    error: '',
  },
  onLoad() {
    const today = iso(new Date())
    const dates = []
    for (let i = 0; i < 7; i += 1) {
      const day = addDays(today, i)
      dates.push({
        iso: day,
        day: day.slice(8),
        label: i === 0 ? '今天' : (i === 1 ? '明天' : '周' + weekday(day)),
        on: i === 0,
      })
    }
    this.setData({ date: today, dates })
  },
  onShow() {
    if (this.data.date) {
      this.load(this.data.date)
    }
  },
  pickDate(e) {
    const day = e.currentTarget.dataset.iso
    this.setData({
      date: day,
      dates: this.data.dates.map((d) => ({ ...d, on: d.iso === day })),
    })
    this.load(day)
  },
  load(day) {
    this.setData({ loading: true, error: '' })
    api.request({
      url: '/api/v1/t/day-slots?date=' + day,
      token: getApp().globalData.token,
    })
      .then((data) => {
        const slots = ((data && data.slots) || []).map((s) => ({ ...s, tone: tone(s) }))
        this.setData({
          slots,
          freeCount: (data && data.freeCount) || 0,
          bookedCount: (data && data.bookedCount) || 0,
          therapistName: (data && data.therapistName) || '',
          loading: false,
          error: slots.length ? '' : '这天没有排班',
        })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  goBack() {
    wx.navigateBack()
  },
})

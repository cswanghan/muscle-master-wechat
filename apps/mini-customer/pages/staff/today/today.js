const api = require('../../../utils/staff-api.js')

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
    return `${minutes} 分钟后`
  }
  if (minutes === 0) {
    return '现在开始'
  }
  return `已迟到 ${-minutes} 分钟`
}

function nowSlot() {
  const d = new Date()
  return d.getHours() * 4 + Math.floor(d.getMinutes() / 15)
}

function shortProject(name) {
  if (!name) {
    return '到店项目'
  }
  return String(name).replace(/调理/g, '').trim() || name
}

function groupTimeline(timeline, next) {
  const rows = []
  let i = 0
  const current = nowSlot()
  while (i < timeline.length) {
    const cur = timeline[i]
    let j = i + 1
    while (
      j < timeline.length
      && timeline[j].state === cur.state
      && String(timeline[j].orderId || '') === String(cur.orderId || '')
    ) {
      j += 1
    }
    const slots = j - i
    const minutes = slots * 15
    const isNext = next && cur.orderId && String(cur.orderId) === String(next.orderId)
    const past = cur.slotNo + slots <= current
    let kind = 'idle'
    let title = '空档 ' + minutes + ' 分钟'
    let status = ''
    let action = ''
    if (cur.state === 'BOOKED' || cur.state === 'LOCKED' || cur.state === 'BUFFER') {
      kind = past && !isNext ? 'done' : isNext ? 'next' : 'booked'
      title = isNext
        ? `${next.customerName} · ${shortProject(next.projectName)}`
        : (cur.state === 'LOCKED' ? '锁定中' : '已预约')
      status = past && !isNext
        ? '已完成'
        : (cur.state === 'LOCKED' ? '锁定中' : (isNext ? '待服务' : '已预约'))
    } else if (cur.state === 'REST') {
      kind = 'rest'
      title = '休息 / 请假'
      status = '休息'
    } else if (cur.state === 'FREE' && minutes >= 60 && !past) {
      kind = 'gap'
      title = '空档 ' + minutes + ' 分钟'
      action = '填满它'
    } else {
      i = j
      continue
    }
    rows.push({
      key: `${cur.slotNo}-${cur.state}-${cur.orderId || ''}`,
      time: slotLabel(cur.slotNo),
      kind,
      title,
      status,
      action,
      orderId: cur.orderId || '',
    })
    i = j
  }
  return rows
}

Page({
  data: {
    staffName: '',
    levelLabel: '技师',
    storeLabel: '本店',
    onDuty: true,
    loading: true,
    error: '',
    next: null,
    etaText: '',
    rows: [],
    doneCount: 0,
    incomeText: '—',
    rateText: '—',
    dateLabel: '',
    pendingCount: 0,
  },
  onShow() {
    const d = new Date()
    this.setData({ dateLabel: `${d.getMonth() + 1} 月 ${d.getDate()} 日` })
    this.ensureToken().then(() => this.loadToday()).catch((err) => {
      this.setData({ loading: false, error: err.message || '登录失败' })
    })
  },
  ensureToken() {
    const app = getApp()
    if (app.globalData.token) {
      this.setData({ staffName: app.globalData.staffName || '技师' })
      return Promise.resolve(app.globalData.token)
    }
    return api.loginTherapist().then((data) => {
      app.globalData.token = data.token
      app.globalData.staffName = data.name || '技师'
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
      const work = timeline.filter((s) => s.state !== 'REST')
      const busy = work.filter((s) => s.state === 'BOOKED' || s.state === 'BUFFER' || s.state === 'LOCKED')
      const rate = work.length ? Math.round((busy.length / work.length) * 100) : 0
      const current = nowSlot()
      const doneIds = {}
      timeline.forEach((s) => {
        if (s.orderId && s.slotNo < current && (s.state === 'BOOKED' || s.state === 'BUFFER')) {
          if (!next || String(s.orderId) !== String(next.orderId)) {
            doneIds[String(s.orderId)] = true
          }
        }
      })
      const pending = timeline.filter((s) => s.state === 'LOCKED').length > 0 ? 1 : 0
      this.setData({
        next,
        etaText: next ? etaText(next.minutesToStart) : '',
        rows: groupTimeline(timeline, next),
        doneCount: Object.keys(doneIds).length,
        rateText: work.length ? `${rate}%` : '—',
        pendingCount: pending,
        loading: false,
      })
    }).catch((err) => {
      this.setData({ loading: false, error: err.message || '加载失败' })
    })
  },
  toggleDuty() {
    const next = !this.data.onDuty
    this.setData({ onDuty: next })
    wx.showToast({ title: next ? '已设为在岗' : '已设为离岗', icon: 'none' })
  },
  openService() {
    const next = this.data.next
    if (!next) {
      return
    }
    wx.navigateTo({
      url: `/pages/staff/service/service?orderId=${next.orderId}`,
    })
  },
  openArchive() {
    wx.showToast({ title: '档案在服务页查看', icon: 'none' })
    this.openService()
  },
  fillGap() {
    wx.navigateTo({ url: '/pages/staff/promotions/promotions' })
  },
  openRow(e) {
    const id = e.currentTarget.dataset.id
    if (!id) {
      return
    }
    wx.navigateTo({ url: `/pages/staff/service/service?orderId=${id}` })
  },
  onPending() {
    this.loadToday()
  },
  soon() {
    wx.navigateTo({ url: '/pages/staff/performance/performance' })
  },
  goPerf() {
    wx.navigateTo({ url: '/pages/staff/performance/performance' })
  },
  goSchedule() {
    wx.navigateTo({ url: '/pages/staff/schedule/schedule' })
  },
  goMine() {
    wx.navigateTo({ url: '/pages/staff/home/home' })
  },
})

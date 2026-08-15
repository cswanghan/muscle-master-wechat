const api = require('../../utils/api.js')

const LEVEL = { SENIOR: '资深', MIDDLE: '中级', JUNIOR: '初级' }

function slotLabel(slotNo) {
  const hour = Math.floor(slotNo / 4)
  const minute = (slotNo % 4) * 15
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function groupBlocks(blocks) {
  const chips = []
  let i = 0
  const list = blocks || []
  while (i < list.length) {
    const cur = list[i]
    let j = i + 1
    while (j < list.length && list[j].state === cur.state) {
      j += 1
    }
    const slots = j - i
    const minutes = slots * 15
    let tone = 'rest'
    let label = cur.state
    if (cur.state === 'BOOKED' || cur.state === 'BUFFER') {
      tone = 'booked'
      label = `已约 ${minutes}'`
    } else if (cur.state === 'LOCKED') {
      tone = 'locked'
      label = '锁定中'
    } else if (cur.state === 'REST') {
      tone = 'rest'
      label = '休息'
    } else if (cur.state === 'FREE' && minutes >= 60) {
      tone = 'gap'
      label = `空档 ${minutes}'`
    } else {
      i = j
      continue
    }
    chips.push({ key: `${cur.slotNo}-${cur.state}`, time: slotLabel(cur.slotNo), tone, label })
    i = j
  }
  return chips
}

Page({
  data: {
    date: '',
    loading: true,
    error: '',
    rows: [],
  },
  onShow() {
    const d = new Date()
    const date = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
    this.setData({ date })
    this.load(date)
  },
  load(date) {
    this.setData({ loading: true, error: '' })
    Promise.all([
      api.request({ url: '/api/v1/c/stores' }).catch(() => ({ items: [] })),
      api.request({ url: '/api/v1/c/projects' }).catch(() => ({ items: [] })),
    ]).then(([stores, projects]) => {
      const storeId = (stores.items && stores.items[0] && stores.items[0].storeId) || '3100000000000000001'
      const projectId = (projects.items && projects.items[0] && projects.items[0].projectId) || '3100000000000000501'
      return api.request({
        url: `/api/v1/c/availability?storeId=${storeId}&date=${date}&projectId=${projectId}&includeBusy=1`,
      })
    }).then((avail) => {
      const rows = ((avail && avail.therapists) || []).map((t) => ({
        therapistId: t.therapistId,
        name: t.name,
        level: LEVEL[t.level] || t.level || '技师',
        chips: groupBlocks(t.blocks),
      }))
      this.setData({ rows, loading: false })
    }).catch((err) => {
      this.setData({ loading: false, error: err.message || '加载失败' })
    })
  },
  goToday() {
    wx.redirectTo({ url: '/pages/today/today' })
  },
  goManager() {
    wx.redirectTo({ url: '/pages/manager/manager' })
  },
  goMine() {
    wx.navigateTo({ url: '/pages/index/index' })
  },
})

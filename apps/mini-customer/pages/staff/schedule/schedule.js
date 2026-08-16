const api = require('../../../utils/staff-api.js')
const { todayIso } = require('../../../utils/format.js')

const LEVEL = { SENIOR: '资深', MIDDLE: '中级', JUNIOR: '初级' }
const STORE_ID = '3100000000000000001'
/** Business day is slot 40 (10:00) to slot 88 (22:00); 4 slots per hour. */
const OPEN = 40
const CLOSE = 88
/** Evening starts at 19:00 for the "今晚空档" list. */
const EVENING = 76

function slotLabel(slotNo) {
  const hour = Math.floor(slotNo / 4)
  const minute = (slotNo % 4) * 15
  return `${String(hour).padStart(2, '0')}:${String(minute).padStart(2, '0')}`
}

function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number)
  const dt = new Date(y, m - 1, d + n)
  const p = (x) => String(x).padStart(2, '0')
  return `${dt.getFullYear()}-${p(dt.getMonth() + 1)}-${p(dt.getDate())}`
}

function weekdayLabel(iso, offset) {
  if (offset === 0) return '今天'
  if (offset === 1) return '明天'
  const [y, m, d] = iso.split('-').map(Number)
  return '周' + '日一二三四五六'[new Date(y, m - 1, d).getDay()]
}

function tone(state) {
  if (state === 'BOOKED' || state === 'BUFFER') return 'booked'
  if (state === 'LOCKED') return 'locked'
  if (state === 'REST') return 'rest'
  return 'free'
}

/**
 * Collapse consecutive same-state slots into spans, so the row renders as a few
 * wide bars rather than 48 cells. Width is proportional to slot count, which is
 * what makes the row read as a timeline.
 */
function spansOf(blocks) {
  const list = (blocks || []).filter((b) => b.slotNo >= OPEN && b.slotNo < CLOSE)
  const spans = []
  let i = 0
  while (i < list.length) {
    const cur = list[i]
    let j = i + 1
    while (j < list.length && tone(list[j].state) === tone(cur.state)) j += 1
    const slots = j - i
    spans.push({
      key: `${cur.slotNo}-${cur.state}`,
      tone: tone(cur.state),
      slots,
      width: slots * 28,
      start: slotLabel(cur.slotNo),
      label: slots >= 4 ? `${slotLabel(cur.slotNo)} · ${(slots * 15) / 60}h` : '',
    })
    i = j
  }
  return spans
}

/** Booked hours, the number on the right of each row in the design. */
function bookedHours(blocks) {
  const n = (blocks || []).filter((b) => b.state === 'BOOKED' || b.state === 'BUFFER').length
  return (n * 15 / 60).toFixed(1)
}

/** Free runs of at least an hour after 19:00. */
function eveningGaps(name, level, blocks) {
  const list = (blocks || []).filter((b) => b.slotNo >= EVENING && b.slotNo < CLOSE)
  const out = []
  let i = 0
  while (i < list.length) {
    if (list[i].state !== 'FREE') { i += 1; continue }
    let j = i + 1
    while (j < list.length && list[j].state === 'FREE') j += 1
    if ((j - i) >= 4) {
      out.push({
        key: `${name}-${list[i].slotNo}`,
        name,
        level,
        range: `${slotLabel(list[i].slotNo)}–${slotLabel(list[i].slotNo + (j - i))}`,
      })
    }
    i = j
  }
  return out
}

Page({
  data: {
    storeName: '门店',
    date: '',
    dates: [],
    rows: [],
    onDuty: 0,
    gaps: [],
    gapSlots: 0,
    hours: [],
    loading: true,
    error: '',
  },
  onShow() {
    const base = todayIso()
    const dates = [0, 1, 2].map((i) => {
      const iso = addDays(base, i)
      return { iso, label: weekdayLabel(iso, i), on: i === 0 }
    })
    // Hour ruler across the top of the timeline.
    const hours = []
    for (let s = OPEN; s < CLOSE; s += 4) hours.push({ slot: s, label: slotLabel(s) })
    this.setData({ dates, hours, date: this.data.date || base }, () => this.load(this.data.date))
  },
  pickDate(e) {
    const iso = e.currentTarget.dataset.iso
    this.setData({
      date: iso,
      dates: this.data.dates.map((d) => ({ ...d, on: d.iso === iso })),
    })
    this.load(iso)
  },
  load(date) {
    this.setData({ loading: true, error: '' })
    Promise.all([
      api.request({ url: '/api/v1/c/stores' }).catch(() => ({ items: [] })),
      api.request({ url: '/api/v1/c/projects' }).catch(() => ({ items: [] })),
    ]).then(([stores, projects]) => {
      const store = (stores.items || []).find((s) => String(s.storeId) === STORE_ID) || (stores.items || [])[0]
      const projectId = (projects.items && projects.items[0] && projects.items[0].projectId)
        || '3100000000000000501'
      this.setData({ storeName: (store && store.name) || '门店' })
      return api.request({
        url: `/api/v1/c/availability?storeId=${STORE_ID}&date=${date}&projectId=${projectId}&includeBusy=1`,
      })
    }).then((avail) => {
      const therapists = (avail && avail.therapists) || []
      const rows = therapists.map((t) => ({
        therapistId: t.therapistId,
        name: t.name,
        level: LEVEL[t.level] || t.level || '技师',
        spans: spansOf(t.blocks),
        hours: bookedHours(t.blocks),
      }))
      const gaps = therapists.flatMap((t) => eveningGaps(t.name, LEVEL[t.level] || '技师', t.blocks))
      const gapSlots = therapists.reduce(
        (n, t) => n + (t.blocks || []).filter(
          (b) => b.slotNo >= EVENING && b.slotNo < CLOSE && b.state === 'FREE').length,
        0)
      this.setData({
        rows,
        onDuty: rows.length,
        gaps,
        gapSlots,
        loading: false,
      })
    }).catch((err) => {
      this.setData({ loading: false, error: err.message || '加载失败' })
    })
  },
  notifyTherapist(e) {
    // Read-and-approve only on the mini program; sending the actual campaign
    // is a PC-side action.
    wx.showToast({ title: '已通知 ' + (e.currentTarget.dataset.name || '技师'), icon: 'none' })
  },
  goToday() {
    wx.redirectTo({ url: '/pages/staff/today/today' })
  },
  goManager() {
    wx.redirectTo({ url: '/pages/staff/manager/manager' })
  },
  goPerf() {
    wx.navigateTo({ url: '/pages/staff/performance/performance' })
  },
  goMine() {
    wx.navigateTo({ url: '/pages/staff/home/home' })
  },
})

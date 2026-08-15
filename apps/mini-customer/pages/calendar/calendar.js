const { request } = require('../../utils/api.js')
const { fenYuan, rating, levelLabel, slotToTime } = require('../../utils/format.js')
const { demoDate } = require('../../config.js')

function addDays(iso, n) {
  const [y, m, d] = iso.split('-').map(Number)
  const dt = new Date(y, m - 1, d + n)
  const mm = String(dt.getMonth() + 1).padStart(2, '0')
  const dd = String(dt.getDate()).padStart(2, '0')
  return `${dt.getFullYear()}-${mm}-${dd}`
}

function weekday(iso) {
  const [y, m, d] = iso.split('-').map(Number)
  return '日一二三四五六'[new Date(y, m - 1, d).getDay()]
}

function qs(obj) {
  return Object.keys(obj)
    .filter((k) => obj[k] !== undefined && obj[k] !== '')
    .map((k) => `${k}=${encodeURIComponent(obj[k])}`)
    .join('&')
}

function paintTherapist(t, selected) {
  const startSet = {}
  const startPrice = {}
  ;(t.starts || []).forEach((s) => {
    startSet[s.slotNo] = true
    startPrice[s.slotNo] = s.priceFen
  })
  const slots = (t.blocks || []).map((b) => {
    let kind = 'busy'
    if (selected && selected.therapistId === t.therapistId && selected.slotNo === b.slotNo) {
      kind = 'sel'
    } else if (startSet[b.slotNo]) {
      kind = 'free'
    } else if (b.state === 'LOCKED') {
      kind = 'locked'
    } else if (b.state === 'BOOKED') {
      kind = 'booked'
    }
    return {
      slotNo: b.slotNo,
      start: b.start,
      state: b.state,
      kind,
      bookable: !!startSet[b.slotNo],
      priceFen: startPrice[b.slotNo],
    }
  })
  return {
    ...t,
    rating: rating(t.ratingX100),
    levelLabel: levelLabel(t.level),
    slots,
  }
}

Page({
  data: {
    storeId: '',
    storeName: '',
    projectId: '',
    projectName: '',
    therapistId: '',
    therapistName: '',
    priceFen: 0,
    priceYuan: '0',
    durationMinutes: 60,
    bufferMinutes: 15,
    date: demoDate,
    dates: [],
    therapists: [],
    selected: null,
    loading: true,
    error: '',
  },
  onLoad(query) {
    const dates = []
    for (let i = 0; i < 5; i += 1) {
      const iso = addDays(demoDate, i)
      const label = i === 0 ? '今天' : i === 1 ? '明天' : '周' + weekday(iso)
      dates.push({ iso, day: iso.slice(8), week: weekday(iso), label, left: '—', on: i === 0 })
    }
    this.setData({
      storeId: query.storeId || '',
      storeName: query.storeName ? decodeURIComponent(query.storeName) : '',
      projectId: query.projectId || '',
      projectName: query.projectName ? decodeURIComponent(query.projectName) : '',
      therapistId: query.therapistId || '',
      therapistName: query.therapistName ? decodeURIComponent(query.therapistName) : '',
      priceFen: Number(query.priceFen || 0),
      priceYuan: fenYuan(query.priceFen || 0),
      durationMinutes: Number(query.durationMinutes || 60),
      bufferMinutes: Number(query.bufferMinutes || 15),
      dates,
    }, () => {
      this._ready = true
      this.loadAvailability()
    })
  },
  onShow() {
    if (this._ready) {
      this.loadAvailability()
    }
  },
  pickDate(e) {
    const iso = e.currentTarget.dataset.iso
    this.setData({
      date: iso,
      selected: null,
      dates: this.data.dates.map((d) => ({ ...d, on: d.iso === iso })),
    })
    this.loadAvailability()
  },
  loadAvailability() {
    const { storeId, date, projectId, therapistId } = this.data
    if (!storeId || !projectId) {
      this.setData({ error: '缺少门店或项目', loading: false })
      return
    }
    this.setData({ loading: true, error: '' })
    let path = `/api/v1/c/availability?storeId=${storeId}&date=${date}&projectId=${projectId}&includeBusy=1`
    if (therapistId) {
      path += `&therapistId=${therapistId}`
    }
    request({ path })
      .then((data) => {
        const selected = this.data.selected
        const therapists = ((data && data.therapists) || []).map((t) => paintTherapist(t, selected))
        const left = therapists.reduce((n, t) => n + ((t.starts && t.starts.length) || (t.slots || []).filter((s) => s.bookable).length), 0)
        const dates = this.data.dates.map((d) => (d.iso === this.data.date ? { ...d, left } : d))
        this.setData({ therapists, dates, loading: false })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickSlot(e) {
    const { tid, tname, slot, start, price, bookable } = e.currentTarget.dataset
    if (bookable !== 1 && bookable !== '1' && bookable !== true) {
      return
    }
    const selected = {
      therapistId: tid,
      therapistName: tname,
      slotNo: Number(slot),
      start,
      priceFen: Number(price),
    }
    this.setData({
      selected,
      priceFen: selected.priceFen,
      priceYuan: fenYuan(selected.priceFen),
      therapists: this.data.therapists.map((t) => paintTherapist(t, selected)),
    })
  },
  goConfirm() {
    const s = this.data.selected
    if (!s) {
      return
    }
    wx.navigateTo({
      url: '/pages/confirm/confirm?' + qs({
        storeId: this.data.storeId,
        storeName: this.data.storeName,
        therapistId: s.therapistId,
        therapistName: s.therapistName,
        projectId: this.data.projectId,
        projectName: this.data.projectName,
        date: this.data.date,
        startSlotNo: s.slotNo,
        start: s.start || slotToTime(s.slotNo),
        priceFen: s.priceFen,
        durationMinutes: this.data.durationMinutes,
        bufferMinutes: this.data.bufferMinutes,
      }),
    })
  },
})

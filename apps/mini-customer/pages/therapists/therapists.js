const { request } = require('../../utils/api.js')
const { fenYuan } = require('../../utils/format.js')
const mock = require('../../utils/mock.js')

function qs(obj) {
  return Object.keys(obj)
    .filter((k) => obj[k] !== undefined && obj[k] !== '')
    .map((k) => `${k}=${encodeURIComponent(obj[k])}`)
    .join('&')
}

Page({
  data: {
    therapists: mock.therapists,
    projects: [],
    stores: mock.stores,
    picked: null,
    storeId: mock.STORE_ID,
    storeName: mock.stores[0].name,
    projectId: '',
    projectName: '',
    priceFen: '',
    durationMinutes: '',
    bufferMinutes: '',
    loading: false,
    error: '',
  },
  onLoad(query) {
    this.setData({
      storeId: query.storeId || mock.STORE_ID,
      storeName: query.storeName ? decodeURIComponent(query.storeName) : mock.stores[0].name,
      projectId: query.projectId || '',
      projectName: query.projectName ? decodeURIComponent(query.projectName) : '',
      priceFen: query.priceFen || '',
      durationMinutes: query.durationMinutes || '',
      bufferMinutes: query.bufferMinutes || '',
    })
    this.bootstrap(query.therapistId || '')
  },
  bootstrap(wantId) {
    this.setData({ loading: true, error: '' })
    const path = this.data.storeId
      ? `/api/v1/c/therapists?storeId=${this.data.storeId}`
      : '/api/v1/c/therapists'
    request({ path })
      .then((tPage) => {
        const therapists = mock.first(
          ((tPage && tPage.items) || []).map((t) => mock.decorateTherapist(t)),
          mock.therapists,
        )
        this.setData({ therapists, loading: false })
        const want = wantId || (this.options && this.options.therapistId)
        if (want) {
          const hit = therapists.find((x) => String(x.therapistId) === String(want)) || therapists[0]
          if (hit) {
            this.pickTherapist({ currentTarget: { dataset: { id: hit.therapistId } } })
          }
        }
      })
      .catch(() => {
        this.setData({ therapists: mock.therapists, loading: false })
        const want = wantId || (this.options && this.options.therapistId)
        const hit = mock.therapists.find((x) => String(x.therapistId) === String(want)) || mock.therapists[0]
        this.pickTherapist({ currentTarget: { dataset: { id: hit.therapistId } } })
      })
  },
  goCalendar(t, p) {
    wx.navigateTo({
      url: '/pages/calendar/calendar?' + qs({
        storeId: this.data.storeId || t.homeStoreId || mock.STORE_ID,
        storeName: this.data.storeName || mock.stores[0].name,
        therapistId: t.therapistId,
        therapistName: t.name,
        projectId: p.projectId,
        projectName: p.name,
        priceFen: p.priceFen,
        durationMinutes: p.durationMinutes,
        bufferMinutes: p.bufferMinutes,
      }),
    })
  },
  pickTherapist(e) {
    const id = e.currentTarget.dataset.id
    const t = (this.data.therapists || []).find((x) => String(x.therapistId) === String(id))
      || mock.therapists.find((x) => String(x.therapistId) === String(id))
    if (!t) {
      return
    }
    if (this.data.projectId) {
      this.goCalendar(t, {
        projectId: this.data.projectId,
        name: this.data.projectName || '到店调理',
        priceFen: this.data.priceFen || 19800,
        durationMinutes: this.data.durationMinutes || 60,
        bufferMinutes: this.data.bufferMinutes || 15,
      })
      return
    }
    this.setData({ picked: t, projects: mock.projects, loading: true })
    const storeId = this.data.storeId || t.homeStoreId || mock.STORE_ID
    request({ path: `/api/v1/c/projects?storeId=${storeId}` })
      .then((page) => {
        const items = mock.first(
          ((page && page.items) || []).map((p) => ({ ...p, priceYuan: fenYuan(p.priceFen) })),
          mock.projects,
        )
        this.setData({ projects: items, loading: false, storeId })
      })
      .catch(() => {
        this.setData({ projects: mock.projects, loading: false })
      })
  },
  pickProject(e) {
    const id = e.currentTarget.dataset.id
    const p = (this.data.projects || []).find((x) => String(x.projectId) === String(id))
    const t = this.data.picked
    if (!p || !t) {
      return
    }
    this.goCalendar(t, p)
  },
})

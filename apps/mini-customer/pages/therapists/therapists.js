const { request } = require('../../utils/api.js')
const { fenYuan, rating, levelLabel } = require('../../utils/format.js')

function qs(obj) {
  return Object.keys(obj)
    .filter((k) => obj[k] !== undefined && obj[k] !== '')
    .map((k) => `${k}=${encodeURIComponent(obj[k])}`)
    .join('&')
}

Page({
  data: {
    therapists: [],
    projects: [],
    stores: [],
    picked: null,
    storeId: '',
    storeName: '',
    loading: true,
    error: '',
  },
  onLoad(query) {
    this.setData({
      storeId: query.storeId || '',
      storeName: query.storeName ? decodeURIComponent(query.storeName) : '',
    })
    this.bootstrap()
  },
  bootstrap() {
    this.setData({ loading: true, error: '' })
    const path = this.data.storeId
      ? `/api/v1/c/therapists?storeId=${this.data.storeId}`
      : '/api/v1/c/therapists'
    Promise.all([
      request({ path }),
      this.data.storeId ? Promise.resolve({ items: [] }) : request({ path: '/api/v1/c/stores' }),
    ])
      .then(([tPage, sPage]) => {
        const therapists = ((tPage && tPage.items) || []).map((t) => ({
          ...t,
          rating: rating(t.ratingX100),
          levelLabel: levelLabel(t.level),
        }))
        const stores = (sPage && sPage.items) || []
        const storeName = this.data.storeName
          || (stores[0] && stores[0].name)
          || ''
        const storeId = this.data.storeId
          || (stores[0] && stores[0].storeId)
          || ''
        this.setData({ therapists, stores, storeId, storeName, loading: false })
        const want = this.options && this.options.therapistId
        if (want) {
          const hit = therapists.find((x) => String(x.therapistId) === String(want))
          if (hit) {
            this.pickTherapist({ currentTarget: { dataset: { id: hit.therapistId } } })
          }
        }
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickTherapist(e) {
    const id = e.currentTarget.dataset.id
    const t = (this.data.therapists || []).find((x) => String(x.therapistId) === String(id))
    if (!t) {
      return
    }
    this.setData({ picked: t, projects: [], loading: true })
    const storeId = this.data.storeId || t.homeStoreId
    request({ path: `/api/v1/c/projects?storeId=${storeId}` })
      .then((page) => {
        const items = ((page && page.items) || []).map((p) => ({
          ...p,
          priceYuan: fenYuan(p.priceFen),
        }))
        this.setData({ projects: items, loading: false, storeId })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickProject(e) {
    const id = e.currentTarget.dataset.id
    const p = (this.data.projects || []).find((x) => String(x.projectId) === String(id))
    const t = this.data.picked
    if (!p || !t) {
      return
    }
    wx.navigateTo({
      url: '/pages/calendar/calendar?' + qs({
        storeId: this.data.storeId || t.homeStoreId,
        storeName: this.data.storeName,
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
})

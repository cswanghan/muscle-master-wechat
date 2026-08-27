const { request } = require('../../utils/api.js')
const {
  fenYuan, rating, levelLabel, levelClass, positiveRate, repeatLine, reviewLine,
} = require('../../utils/format.js')
const { qs, decodeQuery } = require('../../utils/query.js')

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
  onLoad(rawQuery) {
    const query = decodeQuery(rawQuery)
    this.setData({
      storeId: query.storeId || '',
      storeName: query.storeName || '',
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
        const therapists = ((tPage && tPage.items) || []).map((t) => {
          const stats = t.stats || {}
          return {
            ...t,
            rating: rating(t.ratingX100),
            levelLabel: levelLabel(t.level),
            levelClass: levelClass(t.level),
            // 样本不足时后端不下发 positiveRateX100，这里跟着留空，由 wx:else 走「N 条评价」那支。
            rateText: positiveRate(stats.positiveRateX100),
            reviewText: reviewLine(stats),
            repeatText: repeatLine(stats),
            newcomer: !!stats.newcomer,
          }
        })
        const stores = (sPage && sPage.items) || []
        const storeName = this.data.storeName
          || (stores[0] && stores[0].name)
          || ''
        const storeId = this.data.storeId
          || (stores[0] && stores[0].storeId)
          || ''
        this.setData({ therapists, stores, storeId, storeName, loading: false })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickTherapist(e) {
    const t = e.currentTarget.dataset.item
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
    const p = e.currentTarget.dataset.item
    const t = this.data.picked
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

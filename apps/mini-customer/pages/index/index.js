const { request } = require('../../utils/api.js')
const { fenYuan, rating, levelLabel } = require('../../utils/format.js')

Page({
  data: {
    deal: null,
    stores: [],
    therapists: [],
    nearStore: '',
    error: '',
  },
  onShow() {
    this.load()
  },
  load() {
    Promise.all([
      request({ path: '/api/v1/c/stores' }).catch(() => ({ items: [] })),
      request({ path: '/api/v1/c/therapists' }).catch(() => ({ items: [] })),
      request({ path: '/api/v1/c/projects' }).catch(() => ({ items: [] })),
    ]).then(([stores, therapists, projects]) => {
      const storeItems = (stores.items || []).slice(0, 2)
      const thItems = (therapists.items || []).slice(0, 2).map((t) => ({
        ...t,
        rating: rating(t.ratingX100),
        levelLabel: levelLabel(t.level),
        tags: (t.symptomNames || t.tags || ['头颈肩痛', '睡眠调理']).slice(0, 2),
      }))
      const p = (projects.items || [])[0]
      const deal = p
        ? {
            name: (p.name || '对症调理') + ' ' + (p.durationMinutes || 60) + ' 分钟',
            priceYuan: fenYuan(Math.round(p.priceFen * 0.95)),
            strikeYuan: fenYuan(p.priceFen),
            therapistName: (thItems[0] && thItems[0].name) || '推荐技师',
            projectId: p.projectId,
            priceFen: p.priceFen,
            durationMinutes: p.durationMinutes,
            bufferMinutes: p.bufferMinutes,
          }
        : null
      this.setData({
        stores: storeItems,
        therapists: thItems,
        deal,
        nearStore: storeItems[0] ? `${storeItems[0].name}` : '',
        error: storeItems.length || deal ? '' : '列表为空，点下方入口仍可进入',
      })
    }).catch((err) => {
      this.setData({ error: (err && err.message) || '网络连不上本机接口，请确认同一 Wi-Fi 且已关代理' })
    })
  },
  goSymptom() {
    wx.navigateTo({ url: '/pages/symptom/symptom' })
  },
  goStores() {
    wx.navigateTo({ url: '/pages/stores/stores' })
  },
  goTherapists() {
    wx.navigateTo({ url: '/pages/therapists/therapists' })
  },
  goMine() {
    wx.redirectTo({ url: '/pages/mine/mine' })
  },
  goMall() {
    wx.redirectTo({ url: '/pages/mall/mall' })
  },
  goDeal() {
    const d = this.data.deal
    const s = this.data.stores[0]
    if (!d || !s) {
      this.goStores()
      return
    }
    wx.navigateTo({
      url: `/pages/calendar/calendar?storeId=${s.storeId}&storeName=${encodeURIComponent(s.name)}&projectId=${d.projectId}&projectName=${encodeURIComponent(d.name)}&priceFen=${d.priceFen}&durationMinutes=${d.durationMinutes}&bufferMinutes=${d.bufferMinutes}`,
    })
  },
  goStore(e) {
    const id = e.currentTarget.dataset.id
    const store = (this.data.stores || []).find((s) => String(s.storeId) === String(id))
    const d = this.data.deal
    if (store && d) {
      wx.navigateTo({
        url: `/pages/calendar/calendar?storeId=${store.storeId}&storeName=${encodeURIComponent(store.name)}&projectId=${d.projectId}&projectName=${encodeURIComponent(d.name)}&priceFen=${d.priceFen}&durationMinutes=${d.durationMinutes}&bufferMinutes=${d.bufferMinutes}`,
      })
      return
    }
    this.goStores()
  },
  goTherapist(e) {
    const id = e.currentTarget.dataset.id
    const t = (this.data.therapists || []).find((x) => String(x.therapistId) === String(id))
    if (!t) {
      this.goTherapists()
      return
    }
    wx.navigateTo({
      url: `/pages/therapists/therapists?therapistId=${t.therapistId}`,
    })
  },
})

const { request } = require('../../utils/api.js')
const { fenYuan, rating, levelLabel } = require('../../utils/format.js')

Page({
  data: {
    deal: null,
    stores: [],
    therapists: [],
    nearStore: '',
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
            name: p.name,
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
      })
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
    const store = e.currentTarget.dataset.item
    const d = this.data.deal
    if (d) {
      wx.navigateTo({
        url: `/pages/calendar/calendar?storeId=${store.storeId}&storeName=${encodeURIComponent(store.name)}&projectId=${d.projectId}&projectName=${encodeURIComponent(d.name)}&priceFen=${d.priceFen}&durationMinutes=${d.durationMinutes}&bufferMinutes=${d.bufferMinutes}`,
      })
      return
    }
    this.goStores()
  },
  goTherapist(e) {
    const t = e.currentTarget.dataset.item
    wx.navigateTo({
      url: `/pages/therapists/therapists?therapistId=${t.therapistId}`,
    })
  },
})

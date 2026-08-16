const { request } = require('../../utils/api.js')
const { fenYuan } = require('../../utils/format.js')
const mock = require('../../utils/mock.js')
const config = require('../../config.js')

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
      // Swallowing these turned an outage into a page that merely looked
      // sparse; let them reject so the catch below can say what happened.
      request({ path: '/api/v1/c/stores' }),
      request({ path: '/api/v1/c/therapists' }),
      request({ path: '/api/v1/c/projects' }),
    ]).then(([stores, therapists, projects]) => {
      const realStores = (stores.items || []).slice(0, 2).map((s) => mock.decorateStore(s))
      const realTherapists = (therapists.items || []).slice(0, 3).map((t) => mock.decorateTherapist(t))
      // mock.stores carries a 城西银泰店 the server does not have, so silently
      // substituting it made a thin response look like a full one.
      const storeItems = config.mockFallback ? mock.first(realStores, mock.stores) : realStores
      const thItems = config.mockFallback ? mock.first(realTherapists, mock.therapists) : realTherapists
      const p = (projects.items || [])[0] || mock.projects[0]
      const deal = p
        ? {
            name: (p.name || '对症调理') + ' ' + (p.durationMinutes || 60) + ' 分钟',
            priceYuan: fenYuan(Math.round(p.priceFen * 0.95)),
            strikeYuan: fenYuan(p.priceFen),
            therapistName: (thItems[0] && thItems[0].name) || '推荐技师',
            photo: (thItems[0] && thItems[0].photo) || mock.therapistPhoto(),
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
      if (!config.mockFallback) {
        this.setData({
          stores: [],
          therapists: [],
          deal: null,
          nearStore: '',
          error: err.message || '门店和技师加载失败，请检查网络',
        })
        return
      }
      const p = mock.projects[0]
      this.setData({
        stores: mock.stores,
        therapists: mock.therapists,
        deal: {
          name: p.name + ' ' + p.durationMinutes + ' 分钟',
          priceYuan: fenYuan(Math.round(p.priceFen * 0.95)),
          strikeYuan: fenYuan(p.priceFen),
          therapistName: mock.therapists[0].name,
          photo: mock.therapists[0].photo,
          projectId: p.projectId,
          priceFen: p.priceFen,
          durationMinutes: p.durationMinutes,
          bufferMinutes: p.bufferMinutes,
        },
        nearStore: mock.stores[0].name,
        error: '',
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
    const d = this.data.deal
    const s = this.data.stores[0] || mock.stores[0]
    const q = [
      `therapistId=${t.therapistId}`,
      `storeId=${s.storeId}`,
      `storeName=${encodeURIComponent(s.name)}`,
    ]
    if (d) {
      q.push(
        `projectId=${d.projectId}`,
        `projectName=${encodeURIComponent(d.name)}`,
        `priceFen=${d.priceFen}`,
        `durationMinutes=${d.durationMinutes}`,
        `bufferMinutes=${d.bufferMinutes}`,
      )
    }
    wx.navigateTo({ url: '/pages/therapists/therapists?' + q.join('&') })
  },
})

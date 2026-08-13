const { request } = require('../../utils/api.js')
const { fenYuan } = require('../../utils/format.js')

function qs(obj) {
  return Object.keys(obj)
    .filter((k) => obj[k] !== undefined && obj[k] !== '')
    .map((k) => `${k}=${encodeURIComponent(obj[k])}`)
    .join('&')
}

Page({
  data: {
    stores: [],
    projects: [],
    pickedStore: null,
    projectId: '',
    projectName: '',
    priceFen: '',
    durationMinutes: '',
    bufferMinutes: '',
    loading: true,
    error: '',
  },
  onLoad(query) {
    this.setData({
      projectId: query.projectId || '',
      projectName: query.projectName ? decodeURIComponent(query.projectName) : '',
      priceFen: query.priceFen || '',
      durationMinutes: query.durationMinutes || '',
      bufferMinutes: query.bufferMinutes || '',
    })
    this.loadStores()
  },
  loadStores() {
    this.setData({ loading: true, error: '' })
    request({ path: '/api/v1/c/stores' })
      .then((page) => {
        this.setData({ stores: (page && page.items) || [], loading: false })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickStore(e) {
    const store = e.currentTarget.dataset.item
    if (this.data.projectId) {
      this.goCalendar(store, {
        projectId: this.data.projectId,
        projectName: this.data.projectName,
        priceFen: this.data.priceFen,
        durationMinutes: this.data.durationMinutes,
        bufferMinutes: this.data.bufferMinutes,
      })
      return
    }
    this.setData({ pickedStore: store, projects: [], loading: true })
    request({ path: `/api/v1/c/projects?storeId=${store.storeId}` })
      .then((page) => {
        const items = ((page && page.items) || []).map((p) => ({
          ...p,
          priceYuan: fenYuan(p.priceFen),
        }))
        this.setData({ projects: items, loading: false })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickProject(e) {
    const p = e.currentTarget.dataset.item
    this.goCalendar(this.data.pickedStore, p)
  },
  goCalendar(store, project) {
    wx.navigateTo({
      url: '/pages/calendar/calendar?' + qs({
        storeId: store.storeId,
        storeName: store.name,
        projectId: project.projectId,
        projectName: project.projectName || project.name,
        priceFen: project.priceFen,
        durationMinutes: project.durationMinutes,
        bufferMinutes: project.bufferMinutes,
      }),
    })
  },
})

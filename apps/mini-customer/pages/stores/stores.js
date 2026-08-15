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
        this.setData({
          stores: mock.first(
            ((page && page.items) || []).map((s) => mock.decorateStore(s)),
            mock.stores,
          ),
          loading: false,
        })
      })
      .catch(() => {
        this.setData({ stores: mock.stores, loading: false })
      })
  },
  pickStore(e) {
    const id = e.currentTarget.dataset.id
    const store = (this.data.stores || []).find((s) => String(s.storeId) === String(id))
    if (!store) {
      return
    }
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
        this.setData({ projects: mock.first(items, mock.projects), loading: false })
      })
      .catch(() => {
        this.setData({ projects: mock.projects, loading: false })
      })
  },
  pickProject(e) {
    const id = e.currentTarget.dataset.id
    const p = (this.data.projects || []).find((x) => String(x.projectId) === String(id))
    if (!p || !this.data.pickedStore) {
      return
    }
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

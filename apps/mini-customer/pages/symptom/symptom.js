const { request } = require('../../utils/api.js')
const { fenYuan } = require('../../utils/format.js')

Page({
  data: {
    parts: [],
    discomforts: [],
    selectedId: '',
    selectedName: '',
    projects: [],
    hint: '',
    loading: true,
    error: '',
  },
  onLoad() {
    this.loadSymptoms()
  },
  loadSymptoms() {
    this.setData({ loading: true, error: '' })
    request({ path: '/api/v1/c/symptoms' })
      .then((page) => {
        const items = (page && page.items) || []
        this.setData({
          parts: items.filter((s) => s.type === 'BODY_PART'),
          discomforts: items.filter((s) => s.type === 'DISCOMFORT'),
          loading: false,
        })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickSymptom(e) {
    const { id, name } = e.currentTarget.dataset
    this.setData({ selectedId: id, selectedName: name, projects: [], hint: '', loading: true, error: '' })
    request({ path: `/api/v1/c/symptoms/${id}/projects` })
      .then((data) => {
        const items = ((data && data.items) || []).map((p) => ({
          ...p,
          priceYuan: fenYuan(p.priceFen),
        }))
        this.setData({
          projects: items,
          hint: items.length ? '' : (data && data.hint) || '面诊后调整',
          loading: false,
        })
      })
      .catch((err) => {
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },
  pickProject(e) {
    const p = e.currentTarget.dataset.item
    const q = [
      `projectId=${p.projectId}`,
      `projectName=${encodeURIComponent(p.name)}`,
      `priceFen=${p.priceFen}`,
      `durationMinutes=${p.durationMinutes}`,
      `bufferMinutes=${p.bufferMinutes}`,
    ].join('&')
    wx.navigateTo({ url: `/pages/stores/stores?${q}` })
  },
})

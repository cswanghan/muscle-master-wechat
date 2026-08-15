const { request } = require('../../utils/api.js')
const { fenYuan } = require('../../utils/format.js')
const mock = require('../../utils/mock.js')

const FALLBACK_PARTS = [
  { id: '3100000000000000601', name: '头颈肩', type: 'BODY_PART', photo: '/images/parts/neck.jpg' },
  { id: '3100000000000000602', name: '腰背', type: 'BODY_PART', photo: '/images/parts/back.jpg' },
  { id: '3100000000000000604', name: '手臂', type: 'BODY_PART', photo: '/images/parts/arm.jpg' },
  { id: '3100000000000000605', name: '腿足', type: 'BODY_PART', photo: '/images/parts/leg.jpg' },
]

const FALLBACK_DISCOMFORTS = [
  { id: '3100000000000000611', name: '久坐僵硬', type: 'DISCOMFORT' },
  { id: '3100000000000000612', name: '睡不好', type: 'DISCOMFORT' },
  { id: '3100000000000000613', name: '腰酸背痛', type: 'DISCOMFORT' },
  { id: '3100000000000000614', name: '肩周僵硬', type: 'DISCOMFORT' },
  { id: '3100000000000000615', name: '头痛头晕', type: 'DISCOMFORT' },
  { id: '3100000000000000616', name: '产后调理', type: 'DISCOMFORT' },
  { id: '3100000000000000617', name: '足底疲劳', type: 'DISCOMFORT' },
]

const DISPLAY = {
  肩颈: '头颈肩',
  腰骶: '腰背',
}

function labelOf(s) {
  return DISPLAY[s.name] || s.name
}

Page({
  data: {
    parts: FALLBACK_PARTS,
    discomforts: FALLBACK_DISCOMFORTS,
    selectedId: '',
    selectedName: '',
    projects: [],
    hint: '',
    loading: false,
    error: '',
  },
  onLoad() {
    this.loadSymptoms()
  },
  applyItems(items) {
    const list = items || []
    const parts = list.filter((s) => s.type === 'BODY_PART').map((s) => ({
      ...s,
      name: labelOf(s),
      photo: mock.partPhoto(s.name, s.id),
    }))
    const discomforts = list.filter((s) => s.type === 'DISCOMFORT' && s.name !== '其他').map((s) => ({ ...s, name: labelOf(s) }))
    this.setData({
      parts: parts.length ? parts : FALLBACK_PARTS,
      discomforts: discomforts.length ? discomforts : FALLBACK_DISCOMFORTS,
      loading: false,
    })
  },
  loadSymptoms() {
    this.setData({ loading: true, error: '' })
    request({ path: '/api/v1/c/symptoms' })
      .then((page) => {
        this.applyItems((page && page.items) || [])
      })
      .catch((err) => {
        this.setData({
          error: err.message || '接口暂不可用，已显示演示症状',
          loading: false,
          parts: FALLBACK_PARTS,
          discomforts: FALLBACK_DISCOMFORTS,
        })
      })
  },
  fallbackProjects() {
    return request({ path: '/api/v1/c/projects' })
      .then((page) => ((page && page.items) || []).map((p) => ({
        ...p,
        priceYuan: fenYuan(p.priceFen),
      })))
      .catch(() => mock.projects)
  },
  pickSymptom(e) {
    const { id, name } = e.currentTarget.dataset
    if (!id) {
      return
    }
    this.setData({ selectedId: id, selectedName: name, projects: [], hint: '', loading: true, error: '' })
    request({ path: `/api/v1/c/symptoms/${id}/projects` })
      .then((data) => {
        const items = ((data && data.items) || []).map((p) => ({
          ...p,
          priceYuan: fenYuan(p.priceFen),
        }))
        if (items.length) {
          this.setData({ projects: items, hint: '', loading: false })
          return
        }
        return this.fallbackProjects().then((alts) => {
          this.setData({
            projects: alts,
            hint: alts.length ? '' : (data && data.hint) || '面诊后调整',
            loading: false,
          })
        })
      })
      .catch(() => {
        this.fallbackProjects().then((alts) => {
          this.setData({
            projects: alts,
            hint: alts.length ? '' : '面诊后调整',
            loading: false,
            error: alts.length ? '' : '暂无推荐项目',
          })
        })
      })
  },
  pickProject(e) {
    const id = e.currentTarget.dataset.id
    const p = (this.data.projects || []).find((x) => String(x.projectId) === String(id))
    if (!p) {
      return
    }
    const q = [
      `projectId=${p.projectId}`,
      `projectName=${encodeURIComponent(p.name)}`,
      `priceFen=${p.priceFen}`,
      `durationMinutes=${p.durationMinutes}`,
      `bufferMinutes=${p.bufferMinutes}`,
    ].join('&')
    wx.navigateTo({ url: `/pages/therapists/therapists?${q}` })
  },
})

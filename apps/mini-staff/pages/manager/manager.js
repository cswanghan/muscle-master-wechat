const api = require('../../utils/api.js')

/** 待办分组：语义化 key，不用数字编号。顺序即页面从上到下的优先级。 */
const GROUPS = [
  { key: 'leave', label: '请假审批', types: ['LEAVE_APPROVE'], action: 'approve' },
  { key: 'refund', label: '退款审批 ≥¥500', types: ['REFUND_APPROVE'], action: 'approve' },
  { key: 'abnormal', label: '异常单', types: ['ORDER_ABNORMAL'], action: 'resolve' },
  { key: 'queue', label: '人工队列', types: [], action: 'none' },
]

function rateText(rateX10000) {
  if (rateX10000 == null) {
    return '—'
  }
  return (rateX10000 / 100).toFixed(1) + '%'
}

/** 满班率 → 柱高（12–100，太矮点不出来）与色调。 */
function bar(rateX10000) {
  if (rateX10000 == null) {
    return { height: 12, tone: 'rest' }
  }
  const pct = rateX10000 / 100
  return {
    height: Math.max(12, Math.round(pct)),
    tone: pct >= 85 ? 'full' : pct >= 50 ? 'busy' : 'idle',
  }
}

function groupOf(taskType) {
  for (const g of GROUPS) {
    if (g.types.indexOf(taskType) >= 0) {
      return g
    }
  }
  return GROUPS[GROUPS.length - 1]
}

Page({
  data: {
    staffName: '',
    date: '',
    loading: true,
    error: '',
    notice: '',
    rateText: '—',
    rateWidth: 0,
    remainText: '',
    revenueText: '—',
    hours: [],
    stores: [],
    groups: [],
    openCount: 0,
    busyTaskId: '',
  },

  onShow() {
    this.ensureToken()
      .then(() => this.reload())
      .catch((err) => this.setData({ loading: false, error: err.message || '登录失败' }))
  },

  onPullDownRefresh() {
    this.reload().then(() => wx.stopPullDownRefresh())
  },

  ensureToken() {
    const app = getApp()
    if (app.globalData.managerToken) {
      this.setData({ staffName: app.globalData.managerName || '店长' })
      return Promise.resolve(app.globalData.managerToken)
    }
    return api.loginManager().then((data) => {
      app.globalData.managerToken = data.token
      app.globalData.managerName = data.name || '店长'
      this.setData({ staffName: app.globalData.managerName })
      return data.token
    })
  },

  token() {
    return getApp().globalData.managerToken
  },

  reload() {
    this.setData({ loading: true, error: '' })
    return Promise.all([this.loadUtilization(), this.loadTasks()])
      .then(() => this.setData({ loading: false }))
      .catch((err) => this.setData({ loading: false, error: err.message || '加载失败' }))
  },

  loadUtilization() {
    return api.request({ url: '/api/v1/f/metrics/utilization', token: this.token() }).then((data) => {
      const hours = (data.byHour || []).map((h) => {
        const shape = bar(h.rateX10000)
        return {
          hour: h.hour,
          label: String(h.hour).padStart(2, '0'),
          text: rateText(h.rateX10000),
          height: shape.height,
          tone: shape.tone,
        }
      })
      const pct = data.rateX10000 == null ? 0 : data.rateX10000 / 100
      const after19 = (data.byHour || []).filter((h) => h.hour >= 19)
      const idleAfter19 = after19.filter((h) => (h.rateX10000 || 0) < 5000).length
      const remainSlots = Math.max(0, Math.round((100 - pct) * 4))
      const storeTone = pct >= 85 ? 'full' : pct >= 70 ? 'ok' : pct >= 50 ? 'idle' : 'alert'
      this.setData({
        date: data.date,
        rateText: rateText(data.rateX10000),
        rateWidth: Math.max(4, Math.min(100, Math.round(pct))),
        remainText: `剩余约 ${remainSlots} 个可售 slot，19:00 后低谷 ${idleAfter19} 小时`,
        hours,
        stores: [{
          name: '本店',
          text: rateText(data.rateX10000),
          width: Math.max(8, Math.min(100, Math.round(pct))),
          tone: storeTone,
        }],
      })
    })
  },

  loadTasks() {
    return api.request({ url: '/api/v1/f/human-tasks?status=OPEN', token: this.token() }).then((data) => {
      const items = data.items || []
      const bucket = {}
      GROUPS.forEach((g) => {
        bucket[g.key] = []
      })
      items.forEach((t) => {
        const g = groupOf(t.taskType)
        bucket[g.key].push({
          id: t.id,
          taskType: t.taskType,
          title: t.title,
          orderId: t.orderId,
          bizKey: t.bizKey,
        })
      })
      const groups = GROUPS.map((g) => ({
        key: g.key,
        label: g.label,
        action: g.action,
        items: bucket[g.key],
      })).filter((g) => g.items.length > 0)
      this.setData({ groups, openCount: items.length })
    })
  },

  onApprove(e) {
    this.act(e, '/approve', { requestId: 'm1-ap-' + Date.now() }, '已通过')
  },

  onDeny(e) {
    this.act(e, '/deny', { requestId: 'm1-dn-' + Date.now(), reason: '店长驳回' }, '已驳回')
  },

  onDetail(e) {
    const id = e.currentTarget.dataset.id
    const all = []
    this.data.groups.forEach((g) => g.items.forEach((t) => all.push(t)))
    const task = all.find((t) => t.id === id)
    wx.showModal({
      title: task ? task.title : '详情',
      content: task ? `${task.taskType}\n${task.bizKey || ''}` : '',
      showCancel: false,
    })
  },

  soon() {
    wx.showToast({ title: 'P0 未开通', icon: 'none' })
  },

  goMine() {
    wx.navigateTo({ url: '/pages/index/index' })
  },

  scrollTasks() {
    wx.pageScrollTo({ scrollTop: 280, duration: 240 })
  },

  onResolve(e) {
    const action = e.currentTarget.dataset.action
    const label = action === 'RESOLVE_COMPLETE' ? '已按完成结单'
      : action === 'RESOLVE_CANCEL' ? '已按取消结单' : '已忽略'
    this.act(e, '/resolve', { requestId: 'm1-rs-' + Date.now(), action }, label)
  },

  act(e, suffix, body, okText) {
    const id = e.currentTarget.dataset.id
    if (!id || this.data.busyTaskId) {
      return
    }
    this.setData({ busyTaskId: id, error: '', notice: '' })
    api.request({
      url: '/api/v1/f/human-tasks/' + id + suffix,
      method: 'POST',
      data: body,
      token: this.token(),
    }).then(() => {
      this.setData({ busyTaskId: '', notice: okText })
      return this.reload()
    }).catch((err) => {
      this.setData({ busyTaskId: '', error: err.message || '操作失败' })
    })
  },
})

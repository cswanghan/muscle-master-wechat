const { request, ensureLogin } = require('../../utils/api.js')
const { decodeQuery } = require('../../utils/query.js')

// 标签白名单与后端 ReviewPolicy 一一对应。分档整组替换而不是共用一套：
// 低分标签才有归因价值，高分场景摆出「力度太重」只会诱导用户串档。
const GOOD_TAGS = [
  { code: 'TECHNIQUE_GOOD', label: '手法专业' },
  { code: 'FORCE_RIGHT', label: '力度合适' },
  { code: 'ATTENTIVE', label: '细致耐心' },
  { code: 'ON_TIME', label: '准时开始' },
  { code: 'CLEAN', label: '环境整洁' },
  { code: 'GOOD_COMMUNICATION', label: '沟通舒服' },
  { code: 'EFFECTIVE', label: '效果明显' },
]

const BAD_TAGS = [
  { code: 'FORCE_TOO_LIGHT', label: '力度偏轻' },
  { code: 'FORCE_TOO_HEAVY', label: '力度偏重' },
  { code: 'LATE', label: '开始晚了' },
  { code: 'SHORT_TIME', label: '时长不足' },
  { code: 'NOT_ATTENTIVE', label: '不够专注' },
  { code: 'ENV_POOR', label: '环境一般' },
  { code: 'NO_EFFECT', label: '没有效果' },
]

const MAX_TAGS = 5
const MAX_CONTENT = 500
const SCORE_HINTS = ['', '很不满意', '不太满意', '一般', '满意', '非常满意']

// WXML 里没有 indexOf，选中态只能在 JS 侧摊平成每项一个 on 标志。
function options(good, picked) {
  return (good ? GOOD_TAGS : BAD_TAGS).map((t) => ({
    code: t.code,
    label: t.label,
    on: picked.indexOf(t.code) >= 0,
  }))
}

function labelOf(code) {
  const hit = GOOD_TAGS.concat(BAD_TAGS).filter((t) => t.code === code)[0]
  return hit ? hit.label : code
}

Page({
  data: {
    orderId: '',
    orderNo: '',
    therapistName: '',
    date: '',
    start: '',
    view: false,
    loading: true,
    submitting: false,
    error: '',
    score: 0,
    stars: [1, 2, 3, 4, 5],
    scoreHint: '',
    tagOptions: [],
    picked: [],
    pickedLabels: [],
    content: '',
    contentLeft: MAX_CONTENT,
    anonymous: false,
    createdAt: '',
  },

  onLoad(rawQuery) {
    const query = decodeQuery(rawQuery)
    this.setData({
      orderId: query.orderId || '',
      orderNo: query.orderNo || '',
      therapistName: query.therapistName || '',
      date: query.date || '',
      start: query.start || '',
      view: query.mode === 'view',
    })
    this.load()
  },

  // 只读与可写共用这一页：进来先探一次，已有评价就直接切只读，
  // 免得「已评价」的单子又给出一个能点的提交按钮。
  load() {
    if (!this.data.orderId) {
      this.setData({ error: '缺少订单号', loading: false })
      return
    }
    ensureLogin()
      .then(() => request({
        path: `/api/v1/c/bookings/${this.data.orderId}/review`,
        auth: true,
      }))
      .then((r) => this.paintExisting(r))
      .catch((err) => {
        if (err.code === 40401) {
          this.setData({ view: false, loading: false, tagOptions: [] })
          return
        }
        this.setData({ error: err.message || '加载失败', loading: false })
      })
  },

  paintExisting(r) {
    const picked = r.tags || []
    this.setData({
      view: true,
      loading: false,
      score: r.score || 0,
      scoreHint: SCORE_HINTS[r.score] || '',
      picked,
      pickedLabels: picked.map(labelOf),
      content: r.content || '',
      anonymous: !!r.anonymous,
      createdAt: r.createdAt || '',
    })
  },

  // 换档要清空已选：高低分是两组互斥标签，留着旧的提交必被后端 40001 打回。
  pickScore(e) {
    if (this.data.view) {
      return
    }
    const score = Number(e.currentTarget.dataset.score)
    const wasGood = this.data.score >= 4
    const isGood = score >= 4
    const keep = this.data.score > 0 && wasGood === isGood
    const picked = keep ? this.data.picked : []
    this.setData({
      score,
      scoreHint: SCORE_HINTS[score] || '',
      tagOptions: options(isGood, picked),
      picked,
      error: '',
    })
  },

  toggleTag(e) {
    if (this.data.view) {
      return
    }
    const code = e.currentTarget.dataset.code
    const picked = this.data.picked.slice()
    const at = picked.indexOf(code)
    if (at >= 0) {
      picked.splice(at, 1)
    } else {
      if (picked.length >= MAX_TAGS) {
        this.setData({ error: `最多选择 ${MAX_TAGS} 个标签` })
        return
      }
      picked.push(code)
    }
    this.setData({ picked, tagOptions: options(this.data.score >= 4, picked), error: '' })
  },

  onContent(e) {
    const content = e.detail.value || ''
    this.setData({ content, contentLeft: MAX_CONTENT - content.length })
  },

  onAnonymous(e) {
    this.setData({ anonymous: !!e.detail.value })
  },

  submit() {
    if (this.data.view || this.data.submitting) {
      return
    }
    if (!this.data.score) {
      this.setData({ error: '请先打分' })
      return
    }
    this.setData({ submitting: true, error: '' })
    request({
      path: `/api/v1/c/bookings/${this.data.orderId}/review`,
      method: 'POST',
      auth: true,
      data: {
        score: this.data.score,
        tags: this.data.picked,
        content: this.data.content.trim() || null,
        anonymous: this.data.anonymous,
      },
    })
      .then((r) => {
        this.setData({ submitting: false })
        // 后端对重复提交返回已有那条，所以这里画的永远是服务端的真值，不是刚才填的表单。
        this.paintExisting(r)
        wx.showToast({ title: '评价已提交', icon: 'success' })
        setTimeout(() => wx.navigateBack(), 900)
      })
      .catch((err) => {
        this.setData({ submitting: false, error: err.message || '提交失败' })
      })
  },
})

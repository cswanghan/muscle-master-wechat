const api = require('../../../utils/staff-api.js')

const RANGES = [
  { key: 'day', label: '今日' },
  { key: 'week', label: '本周' },
  { key: 'month', label: '本月' },
]

const KIND_TONE = { SERVICE: '', ADD_ON: '', REFUND: 'minus' }

function yuan(fen) {
  const n = Number(fen || 0)
  const sign = n < 0 ? '-' : ''
  const abs = Math.abs(n)
  return sign + (abs / 100).toFixed(abs % 100 === 0 ? 0 : 2)
}

// 千分位只加在整数部分，小数位保持原样。
function grouped(fen) {
  const s = yuan(fen)
  const neg = s.startsWith('-')
  const body = neg ? s.slice(1) : s
  const [int, dec] = body.split('.')
  const withSep = int.replace(/\B(?=(\d{3})+(?!\d))/g, ',')
  return (neg ? '-' : '') + withSep + (dec ? '.' + dec : '')
}

Page({
  data: {
    ranges: RANGES,
    range: 'month',
    rangeLabel: '本月',
    loading: true,
    error: '',
    total: '0',
    clocks: 0,
    level: '',
    rateText: '',
    from: '',
    to: '',
    rows: [],
    entries: [],
  },
  onShow() {
    this.load(this.data.range)
  },
  pick(e) {
    const range = e.currentTarget.dataset.v
    if (range === this.data.range) {
      return
    }
    this.setData({ range, rangeLabel: (RANGES.find((r) => r.key === range) || {}).label || '' })
    this.load(range)
  },
  load(range) {
    this.setData({ loading: true, error: '' })
    api.request({ url: '/api/v1/t/performance?range=' + range, token: getApp().globalData.token })
      .then((d) => {
        const b = (d && d.breakdown) || {}
        // 只列非零项：一屏里摆一串 ¥0 会把真正有钱的那几行淹掉。
        const rows = [
          { key: 'service', label: '钟数提成', value: b.serviceFen },
          { key: 'addOn', label: '加钟提成', value: b.addOnFen },
          { key: 'card', label: '卡销提成', value: b.cardFen },
          { key: 'designated', label: '指定加成', value: b.designatedFen },
          { key: 'refund', label: '退款回滚', value: -(b.refundFen || 0) },
        ].filter((r) => r.value).map((r) => ({
          ...r,
          text: (r.value < 0 ? '-¥' : '¥') + grouped(Math.abs(r.value)),
          tone: r.value < 0 ? 'minus' : '',
        }))
        const entries = ((d && d.entries) || []).map((e) => ({
          ...e,
          tone: KIND_TONE[e.kind] || '',
          amountText: (e.commissionFen < 0 ? '-¥' : '+¥') + grouped(Math.abs(e.commissionFen)),
          meta: `${e.date.slice(5)} ${e.start} · ${e.orderNo}`,
        }))
        this.setData({
          loading: false,
          total: grouped(d && d.totalCommissionFen),
          clocks: (d && d.clockCount) || 0,
          level: (d && d.level) || '',
          rateText: d && d.rateX100 ? (d.rateX100 / 100).toFixed(0) + '%' : '',
          from: (d && d.from) || '',
          to: (d && d.to) || '',
          rows,
          entries,
        })
      })
      .catch((err) => {
        this.setData({ loading: false, error: err.message || '加载失败', rows: [], entries: [] })
      })
  },
  openOrder(e) {
    const id = e.currentTarget.dataset.id
    if (!id) {
      return
    }
    wx.navigateTo({ url: '/pages/staff/service/service?orderId=' + id })
  },
  appeal() {
    wx.showModal({
      title: '提成申诉',
      content: '提成 T+1 生成，昨日 04:00 前完成。演示包记录申诉，不入账。',
      showCancel: false,
    })
  },
  goToday() {
    wx.redirectTo({ url: '/pages/staff/today/today' })
  },
  goSchedule() {
    wx.redirectTo({ url: '/pages/staff/schedule/schedule' })
  },
  goMine() {
    wx.navigateTo({ url: '/pages/staff/home/home' })
  },
})

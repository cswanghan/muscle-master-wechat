function fenYuan(fen) {
  const n = Number(fen || 0)
  return (n / 100).toFixed(n % 100 === 0 ? 0 : 2)
}

function slotToTime(slotNo) {
  const n = Number(slotNo)
  const h = Math.floor(n / 4)
  const m = (n % 4) * 15
  return String(h).padStart(2, '0') + ':' + String(m).padStart(2, '0')
}

function rating(x100) {
  return ((Number(x100) || 0) / 100).toFixed(1)
}

function levelLabel(level) {
  const map = { SENIOR: '资深技师', MIDDLE: '中级技师', JUNIOR: '初级技师' }
  return map[level] || level || ''
}

function statusLabel(status) {
  const map = {
    PENDING_PAY: '待支付',
    BOOKED: '已预约',
    CHECKED_IN: '已到店',
    IN_SERVICE: '服务中',
    COMPLETED: '已完成',
    CLOSED: '已关闭',
    CANCELLED: '已取消',
    NO_SHOW: '未到店',
    ABNORMAL: '异常',
  }
  return map[status] || status || ''
}

function isOngoing(status) {
  return status === 'PENDING_PAY' || status === 'BOOKED'
    || status === 'CHECKED_IN' || status === 'IN_SERVICE'
}

function remainMs(lockExpireAt) {
  if (!lockExpireAt) {
    return 0
  }
  const t = Date.parse(lockExpireAt)
  if (Number.isNaN(t)) {
    return 0
  }
  return Math.max(0, t - Date.now())
}

function mmss(ms) {
  const s = Math.max(0, Math.floor(ms / 1000))
  const m = Math.floor(s / 60)
  const r = s % 60
  return String(m).padStart(2, '0') + ':' + String(r).padStart(2, '0')
}

/**
 * Local calendar date, not UTC. toISOString() is UTC, so between 00:00 and
 * 08:00 Beijing time it reports yesterday and "today" comparisons flip.
 */
function todayIso() {
  const d = new Date()
  const pad = (n) => (n < 10 ? '0' : '') + n
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate())
}

// 好评率样本不足时后端整个字段不下发，这里也必须返回空串而不是「0%」——
// 把缺省渲染成 0，新技师看起来就是差评缠身。
function positiveRate(x100) {
  if (x100 === null || x100 === undefined) {
    return ''
  }
  return Math.floor(Number(x100) / 100) + '%'
}

// 「28 次回头 · 19 位老客」：次数说粘性强度，人数说粘性宽度。
// 只报次数会被一个高频客人撑起来，只报人数看不出复购深度，两个一起才读得准。
function repeatLine(stats) {
  if (!stats || !stats.servedCount) {
    return ''
  }
  if (!stats.repeatCount) {
    return '30 天服务 ' + stats.servedCount + ' 次'
  }
  return '30 天回头 ' + stats.repeatCount + ' 次 · ' + stats.repeatCustomerCount + ' 位老客'
}

function reviewLine(stats) {
  if (!stats || !stats.reviewCount) {
    return '暂无评价'
  }
  return stats.reviewCount + ' 条评价'
}

module.exports = {
  todayIso,
  fenYuan,
  slotToTime,
  rating,
  levelLabel,
  positiveRate,
  repeatLine,
  reviewLine,
  statusLabel,
  isOngoing,
  remainMs,
  mmss,
}

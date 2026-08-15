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

module.exports = {
  todayIso,
  fenYuan,
  slotToTime,
  rating,
  levelLabel,
  statusLabel,
  isOngoing,
  remainMs,
  mmss,
}

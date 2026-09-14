const api = require('../../../utils/staff-api.js')

function pct(x100) {
  return Math.round((x100 || 0) / 100) + '%'
}

Page({
  data: {
    name: '',
    month: '',
    lessons: 0,
    salesYuan: '0',
    attendanceDays: 0,
    ranks: [],
    rates: [],
    encouragement: '',
    clockedIn: false,
    clockInAt: '',
    clockOutAt: '',
    loading: true,
    error: '',
  },
  onShow() {
    this.load()
  },
  load() {
    this.setData({ loading: true, error: '' })
    const token = getApp().globalData.token
    Promise.all([
      api.request({ url: '/api/v1/t/scoreboard', token }),
      api.request({ url: '/api/v1/t/attendance', token }).catch(() => null),
    ])
      .then(([b, a]) => {
        this.setData({
          name: b.therapistName || '',
          month: b.month || '',
          lessons: b.lessonCount || 0,
          salesYuan: b.salesYuan || '0',
          attendanceDays: b.attendanceDays || 0,
          // 五项分别排名，不合成一个综合分——合起来就看不出该改什么了。
          ranks: (b.ranks || []).map((r) => ({ ...r, top: r.rank <= 3 })),
          rates: [
            { label: '新客好评率', value: pct(b.positiveRateX100) },
            { label: '续费率', value: pct(b.renewRateX100) },
            { label: '转成交率', value: pct(b.convertRateX100) },
            { label: '回访完成度', value: pct(b.followUpRateX100) },
            { label: '档案完成度', value: pct(b.profileRateX100) },
            { label: '计划配合度', value: pct(b.complianceX100) },
          ],
          encouragement: b.encouragement || '',
          clockedIn: a ? !!a.onDuty : false,
          clockInAt: a ? (a.clockInAt || '') : '',
          clockOutAt: a ? (a.clockOutAt || '') : '',
          loading: false,
        })
      })
      .catch((err) => this.setData({ error: err.message || '加载失败', loading: false }))
  },
  clockIn() {
    this.clock('clock-in')
  },
  clockOut() {
    this.clock('clock-out')
  },
  clock(path) {
    api.request({
      url: '/api/v1/t/attendance/' + path,
      method: 'POST',
      token: getApp().globalData.token,
    })
      .then(() => {
        wx.showToast({ title: path === 'clock-in' ? '已打上班卡' : '已打下班卡', icon: 'none' })
        this.load()
      })
      .catch((err) => wx.showToast({ title: err.message || '失败', icon: 'none' }))
  },
  goBack() {
    wx.navigateBack()
  },
})

const api = require('../../../utils/staff-api.js')

const STORE_ID = '3100000000000000001'

Page({
  data: {
    title: '肌松大师 · 员工端',
    roles: ['技师', '前台', '店长'],
    therapists: [],
    error: '',
  },
  onShow() {
    // Pulled rather than hardcoded: the demo roster grew from 3 to 10, and a
    // fixed list would silently omit whoever the customer actually booked.
    api.request({ url: '/api/v1/c/therapists?storeId=' + STORE_ID })
      .then((data) => {
        const items = (data && data.items) || []
        this.setData({
          therapists: items.map((t) => ({
            name: t.name,
            levelLabel: { SENIOR: '资深', MIDDLE: '中级', JUNIOR: '初级' }[t.level] || '技师',
            // demo.t1..t10 line up with employeeNo T001..T010.
            who: 't' + parseInt(String(t.employeeNo || '').replace(/\D/g, ''), 10),
          })).filter((t) => t.who !== 'tNaN'),
          error: '',
        })
      })
      .catch((err) => this.setData({ error: err.message || '技师列表加载失败' }))
  },
})

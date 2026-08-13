const { apiBase } = require('../../config.js')

Page({
  data: {
    title: '肌松大师',
    apiBase,
    stores: [],
    error: '',
    loading: true,
  },
  onLoad() {
    this.loadStores()
  },
  loadStores() {
    this.setData({ loading: true, error: '' })
    wx.request({
      url: `${this.data.apiBase}/api/v1/c/stores`,
      method: 'GET',
      success: (res) => {
        const body = res.data || {}
        if (res.statusCode === 200 && body.code === 0) {
          this.setData({ stores: (body.data && body.data.items) || [], loading: false })
        } else {
          this.setData({ error: body.message || `HTTP ${res.statusCode}`, loading: false })
        }
      },
      fail: (err) => {
        this.setData({ error: err.errMsg || '网络错误', loading: false })
      },
    })
  },
})

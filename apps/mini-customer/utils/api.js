const { apiBase } = require('../config.js')

function request({ path, method, data, auth }) {
  const header = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = wx.getStorageSync('token')
    if (token) {
      header.Authorization = 'Bearer ' + token
    }
  }
  return new Promise((resolve, reject) => {
    wx.request({
      url: apiBase + path,
      method: method || 'GET',
      data,
      header,
      success(res) {
        const body = res.data || {}
        if (res.statusCode >= 200 && res.statusCode < 300 && (body.code === 0 || body.code === 'SUCCESS')) {
          resolve(body.data !== undefined ? body.data : body)
          return
        }
        const err = new Error(body.message || `HTTP ${res.statusCode}`)
        err.code = body.code
        err.statusCode = res.statusCode
        err.body = body
        reject(err)
      },
      fail(err) {
        reject(new Error((err && err.errMsg) || '网络错误'))
      },
    })
  })
}

function ensureLogin() {
  const token = wx.getStorageSync('token')
  if (token) {
    return Promise.resolve({ token, customerId: wx.getStorageSync('customerId') })
  }
  return request({
    path: '/api/v1/c/auth/wechat',
    method: 'POST',
    data: { code: 'dev' },
  }).then((data) => {
    wx.setStorageSync('token', data.token)
    wx.setStorageSync('customerId', data.customerId || '')
    return data
  })
}

function rid(prefix) {
  return (prefix || 'req') + '-' + Date.now() + '-' + Math.random().toString(16).slice(2, 10)
}

module.exports = {
  apiBase,
  request,
  ensureLogin,
  rid,
}

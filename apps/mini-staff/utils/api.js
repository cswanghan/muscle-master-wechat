const { apiBase } = require('../config.js')

function request({ url, method = 'GET', data, token }) {
  return new Promise((resolve, reject) => {
    wx.request({
      url: `${apiBase}${url}`,
      method,
      data,
      header: token
        ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
        : { 'Content-Type': 'application/json' },
      success: (res) => {
        const body = res.data || {}
        if (res.statusCode >= 200 && res.statusCode < 300 && body.code === 0) {
          resolve(body.data)
        } else {
          reject(new Error(body.message || `HTTP ${res.statusCode}`))
        }
      },
      fail: (err) => reject(new Error(err.errMsg || '网络错误')),
    })
  })
}

function loginTherapist() {
  return request({
    url: '/api/v1/staff/auth/wechat',
    method: 'POST',
    data: { code: 'dev-staff-t1' },
  })
}

module.exports = {
  apiBase,
  request,
  loginTherapist,
}

const config = require('../config.js')

const { apiBase, mockFallback, transport, cloud } = config

/** Both transports answer with {statusCode, data}; unwrap the envelope once. */
function settle(res, resolve, reject) {
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
}

/**
 * WeChat routes this through its own gateway to the CloudBase service, so the
 * host never appears in a wx.request domain check. X-WX-SERVICE picks the
 * service inside the env; without it the gateway cannot route.
 */
function viaContainer({ path, method, data, header }, resolve, reject) {
  if (!wx.cloud || typeof wx.cloud.callContainer !== 'function') {
    reject(new Error('当前基础库不支持云托管调用，请把 config.transport 改为 request'))
    return
  }
  wx.cloud.callContainer({
    config: { env: cloud.env },
    path,
    method: method || 'GET',
    data,
    header: Object.assign({}, header, { 'X-WX-SERVICE': cloud.service }),
    success(res) {
      settle(res, resolve, reject)
    },
    fail(err) {
      reject(new Error((err && err.errMsg) || '云托管调用失败'))
    },
  })
}

function viaRequest({ path, method, data, header }, resolve, reject) {
  wx.request({
    url: apiBase + path,
    method: method || 'GET',
    data,
    header,
    success(res) {
      settle(res, resolve, reject)
    },
    fail(err) {
      reject(new Error((err && err.errMsg) || '网络错误'))
    },
  })
}

function request({ path, method, data, auth }) {
  const header = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = wx.getStorageSync('token')
    if (token) {
      header.Authorization = 'Bearer ' + token
    }
  }
  const call = { path, method, data, header }
  return new Promise((resolve, reject) => {
    if (transport === 'container') {
      viaContainer(call, resolve, reject)
    } else {
      viaRequest(call, resolve, reject)
    }
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
  }).catch((err) => {
    if (!mockFallback) {
      // Handing back a fake token here turns "cannot reach the API" into a
      // confusing 401 three calls later. Fail where the failure happened.
      throw err
    }
    const fallback = { token: 'mock-token', customerId: 'mock-customer' }
    wx.setStorageSync('token', fallback.token)
    wx.setStorageSync('customerId', fallback.customerId)
    return fallback
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

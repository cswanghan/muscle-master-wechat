const { mockFallback } = require('../config.js')
const { send: transportSend } = require('./transport.js')

function send({ path, method, data, auth }) {
  const header = { 'Content-Type': 'application/json' }
  if (auth) {
    const token = wx.getStorageSync('token')
    if (token) {
      header.Authorization = 'Bearer ' + token
    }
  }
  return transportSend({ path, method, data, header })
}

function isUnauthorized(err) {
  return err && (err.statusCode === 401 || err.code === 40101)
}

/**
 * A token in storage was trusted on sight, so a stale one — an expired JWT, or
 * the literal 'mock-token' an older build wrote when the API was unreachable —
 * kept being replayed and every authed call answered 未登录. Re-login once on
 * 401 and retry. The login call itself is unauthed, so this cannot recurse.
 */
function request(opts) {
  return send(opts).catch((err) => {
    if (!opts.auth || opts.retried || !isUnauthorized(err)) {
      throw err
    }
    wx.removeStorageSync('token')
    wx.removeStorageSync('customerId')
    return ensureLogin().then(() => send(Object.assign({}, opts, { retried: true })))
  })
}

function ensureLogin() {
  const token = wx.getStorageSync('token')
  // 'mock-token' is what builds before 0.4.0 stored when the API was
  // unreachable; it survives an upgrade and the server rejects it.
  if (token && token !== 'mock-token') {
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
  apiBase: require('../config.js').apiBase,
  request,
  ensureLogin,
  rid,
}

const { apiBase } = require('../config.js')
const { send } = require('./transport.js')

/**
 * Staff pages call this as request({url, token}) — kept as-is so merging them
 * into the customer app did not touch their bodies. The customer layer uses
 * {path, auth} and reads its token from storage; staff tokens live in
 * app.globalData and are passed in explicitly, so the two never collide.
 *
 * Going through the shared transport is the point of the merge: under one
 * AppID the staff screens now reach the service via callContainer too, so they
 * need no request 合法域名 of their own.
 */
function request({ url, method = 'GET', data, token }) {
  return send({
    path: url,
    method,
    data,
    header: token
      ? { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }
      : { 'Content-Type': 'application/json' },
  })
}

function loginTherapist() {
  return request({
    url: '/api/v1/staff/auth/wechat',
    method: 'POST',
    data: { code: 'dev-staff-t1' },
  })
}

/** 店长台（M1）：满班率 + 待办都要 /f/** 的门店数据域。 */
function loginManager() {
  return request({
    url: '/api/v1/staff/auth/wechat',
    method: 'POST',
    data: { code: 'dev-staff-manager' },
  })
}

module.exports = {
  apiBase,
  request,
  loginTherapist,
  loginManager,
}

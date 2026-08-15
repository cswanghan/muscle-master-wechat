const config = require('./config.js')

App({
  onLaunch() {
    // callContainer needs the cloud SDK initialised first. Harmless when the
    // transport is 'request', so it is not worth branching on.
    if (wx.cloud && typeof wx.cloud.init === 'function') {
      try {
        wx.cloud.init({ traceUser: false })
      } catch (e) {
        console.warn('wx.cloud.init failed:', e && e.message)
      }
    }
  },
  globalData: {
    brandColor: '#1E5C4A',
    transport: config.transport,
    version: config.version,
    apiBase: config.apiBase,
    // Staff screens keep their token here rather than in storage, so it stays
    // separate from the customer token and dies with the session.
    token: '',
  },
})

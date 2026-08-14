const { apiBase } = require('./config.js')

App({
  onLaunch() {},
  globalData: {
    brandColor: '#1E5C4A',
    apiBase,
    token: '',
    staffName: '',
    managerToken: '',
    managerName: '',
  },
})

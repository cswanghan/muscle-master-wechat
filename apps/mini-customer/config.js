module.exports = {
  // Keep in step with the version typed into DevTools on upload, so the number
  // shown on 我的 tells you which build a tester is actually running.
  version: '0.13.0',

  // 'container': wx.cloud.callContainer — reaches the CloudBase service over
  //   the WeChat gateway, so NO request 合法域名 entry is needed and changing
  //   the host does not cost one of the 5 monthly domain edits. Requires the
  //   mini program and the service to sit under the same AppID.
  // 'request': plain wx.request against apiBase — needs the host registered
  //   as a request 合法域名, or every call dies with "url not in domain list".
  transport: 'container',
  cloud: {
    env: 'prod-d3gvc0fd30c1c760d',
    service: 'muscle-api',
  },

  apiBase: 'https://muscle-api-297565-11-1469372614.sh.run.tcloudbase.com',
  // Empty = start the calendar on the real today. A pinned date silently rots:
  // once it is in the past the picker offers dead slots, and 前台/技师端 only
  // show today's orders, so a booking made against it is invisible to staff.
  demoDate: '',
  // Off: a failed booking/login surfaces the error. On: it silently becomes a
  // local fake order, which looks identical to success and hides an outage —
  // that is how a demo produced an M-prefixed code the server had never seen.
  // Only turn on to demo with no reachable API at all.
  mockFallback: false,
}

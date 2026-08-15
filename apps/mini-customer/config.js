module.exports = {
  // Keep in step with the version typed into DevTools on upload, so the number
  // shown on 我的 tells you which build a tester is actually running.
  version: '0.4.0',
  apiBase: 'https://muscle-api-297565-11-1469372614.sh.run.tcloudbase.com',
  demoDate: '2026-08-15',
  // Off: a failed booking/login surfaces the error. On: it silently becomes a
  // local fake order, which looks identical to success and hides an outage —
  // that is how a demo produced an M-prefixed code the server had never seen.
  // Only turn on to demo with no reachable API at all.
  mockFallback: false,
}

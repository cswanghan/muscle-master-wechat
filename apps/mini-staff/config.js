module.exports = {
  // Keep in step with the version typed into DevTools on upload.
  version: '0.1.0',

  // No callContainer here: it requires the mini program and the CloudBase
  // service to share an AppID, and the service lives under the customer app
  // (wxf848c067f5807a75). So this host MUST be registered as a request 合法域名
  // in the staff app's MP console, or every call fails with
  // "url not in domain list".
  apiBase: 'https://muscle-api-297565-11-1469372614.sh.run.tcloudbase.com',
}

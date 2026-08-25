// apiBase 用局域网 IP 而不是 127.0.0.1：真机预览时手机解析不到 Mac 的 loopback。
// 同一个值在开发者工具里也照样通，所以不需要两套配置。
// 换了 Wi-Fi / IP 变了就跑 `scripts/preview.sh`，它会自动重写这一行。
module.exports = {
  apiBase: 'http://10.0.192.122:8080',
  // Empty = follow the device's today, which is what the server seeds the demo
  // calendar on. Set an ISO date only to pin a specific day for a repro.
  demoDate: '',
}

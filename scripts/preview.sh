#!/usr/bin/env bash
# 真机预览：把 apiBase 对到当前局域网 IP，然后让开发者工具出二维码。
#
#   scripts/preview.sh                  # 预览 mini-customer
#   （员工端已并入 mini-customer，无需单独预览）
#   scripts/preview.sh mini-customer wxAAAA...   # 顺手写入真实 AppID
#
# 前置：开发者工具 设置 → 安全设置 → 服务端口 已开启，且账号已登录。
set -euo pipefail

APP="${1:-mini-customer}"
APPID_ARG="${2:-}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PROJ="$ROOT/apps/$APP"
CLI="/Applications/wechatwebdevtools.app/Contents/MacOS/cli"
OUT="$ROOT/docs/preview-$APP.png"

[ -d "$PROJ" ] || { echo "找不到小程序目录：$PROJ" >&2; exit 1; }
[ -x "$CLI" ] || { echo "找不到开发者工具 CLI：$CLI" >&2; exit 1; }

# 1) 局域网 IP —— 手机和 Mac 必须在同一个网段，loopback 手机解析不到
IP="$(ipconfig getifaddr en0 || ipconfig getifaddr en1)"
[ -n "$IP" ] || { echo "拿不到局域网 IP，先确认 Wi-Fi 连着" >&2; exit 1; }
/usr/bin/sed -i '' -E "s|apiBase: 'http://[^:]+:8080'|apiBase: 'http://$IP:8080'|" "$PROJ/config.js"
echo "apiBase -> http://$IP:8080"

# 2) AppID —— touristappid 服务端直接拒，preview / 真机调试 / 上传全都走不了
if [ -n "$APPID_ARG" ]; then
  /usr/bin/sed -i '' -E "s|\"appid\": *\"[^\"]*\"|\"appid\": \"$APPID_ARG\"|" "$PROJ/project.config.json"
fi
APPID="$(/usr/bin/grep -o '"appid": *"[^"]*"' "$PROJ/project.config.json" | /usr/bin/sed -E 's|.*"appid": *"([^"]*)".*|\1|')"
if [ "$APPID" = "touristappid" ]; then
  cat >&2 <<'MSG'
✖ 当前是游客模式（touristappid），微信服务端会以「不存在此 AppID (code 10)」拒掉预览。
  真机预览必须有已注册的小程序 AppID：
    1. mp.weixin.qq.com 注册小程序，拿到 wx 开头的 AppID
    2. 开发 → 开发管理 → 开发设置，把本机加进「开发者」名单
    3. 重跑：scripts/preview.sh <app> <AppID>
MSG
  exit 2
fi

# 3) 后端必须监听 0.0.0.0 —— 只绑 loopback 的话二维码扫开是白屏
curl -sf -m 5 -o /dev/null "http://$IP:8080/api/v1/c/therapists" \
  || echo "⚠ http://$IP:8080 不通，先起后端：cd server && mvn spring-boot:run -Dspring-boot.run.profiles=dev"

"$CLI" preview --project "$PROJ" --qr-format image --qr-output "$OUT"
echo "二维码：$OUT"
echo "注意：http 请求要在手机上打开右上角「开启调试」才放行，否则被域名白名单拦掉。"

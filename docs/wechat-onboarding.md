# 微信开通检查单

支付 PR（PR8）合并前必须全部勾选。审核日历单独排，不要假设某一周一定过审。

## 小程序主体与类目

- [ ] 主体已认证（与商户号主体一致）
- [ ] C 端小程序类目已通过（生活服务 / 到店服务等相关类目）
- [ ] 员工端小程序类目已通过（可与 C 端同主体）

## AppID

| 端 | AppID | 备注 | 到位日 |
| --- | --- | --- | --- |
| C 端 `apps/mini-customer` | `wxf848c067f5807a75` | 正式号；Secret 只放本机 `.env.local` | 2026-08-15 |
| 员工端 `apps/mini-staff`（技师 / 前台 / 店长） | | 独立 AppID，不可与 C 端共用 | |

- [ ] C 端 AppID 已写入构建配置
- [ ] 员工端 AppID 已写入构建配置

生产 `wechat.mock=false` 必须设置 `WX_C_APP_ID` / `WX_C_APP_SECRET`（缺省则进程拒绝启动）。绑手机走 `getuserphonenumber`（`access_token` + `phone_code`）。Compose 演示默认 `WECHAT_MOCK=true`。

## 直连商户号（D17）

- [ ] 直连商户号（`mchid`）已开通，非服务商子商户
- [ ] 商户号主体 = 小程序主体
- [ ] JSAPI 支付产品已开通
- [ ] Native 收款码（前台散客）产品已开通
- [ ] `store.wx_mchid` 可空：空则走平台默认商户号

商户号：`________________`

## APIv3 证书与平台证书

- [ ] APIv3 密钥（32 位）已生成并放入密钥管理，**禁止入库**
- [ ] 商户 API 证书（`apiclient_key.pem` / 序列号）已下载
- [ ] 微信支付平台证书已下载或可通过接口拉取
- [ ] 回调验签公钥 / 平台证书轮换流程已登记

## 支付目录 / JSAPI 授权目录

C 端支付目录（须与下单 referer 一致）：

- [ ] `https://<C 端合法域名>/`
- [ ] 开发版 / 体验版 / 正式版目录均已配置

JSAPI 授权目录：

- [ ] 已在商户平台配置
- [ ] 与 C 端 `requestPayment` 页面路径一致

员工端收款码（Native）无需 JSAPI 目录，但仍需：

- [ ] Native 下单权限
- [ ] 回调 URL 已在商户平台登记（`/api/v1/pay/wechat/notify`）

## 微信云托管（远程体验）

本机已安装 `@wxcloud/cli`（`wxcloud`）。

正式号 `wxf848c067f5807a75` 已开通环境：

- 环境 ID：`prod-d3gvc0fd30c1c760d`
- 微信自带模板 `springboot-tm6p` 已于 2026-08-15 部署成功（计数器 Demo，不是本仓库）
- 模板公网：`https://springboot-tm6p-297565-11-1469372614.sh.run.tcloudbase.com`（可留着对照，不要写进小程序 `apiBase`）

### 已部署服务（2026-08-15）

| 服务 | 公网地址 | 说明 |
| --- | --- | --- |
| `muscle-api` | `https://muscle-api-297565-11-1469372614.sh.run.tcloudbase.com` | 本仓库 `server/`，端口 8080，`dev` profile + H2 |
| `muscle-admin` | `https://muscle-admin-297565-11-1469372614.sh.run.tcloudbase.com` | 本仓库 `apps/admin-web/`，nginx:80，`/api/` 反代到 `muscle-api` |
| `springboot-tm6p` | — | 微信自带计数器模板，可随时删除 |

已验证：`GET /`（探活）、`GET /actuator/health`、`GET /api/v1/c/stores`、`GET /api/v1/c/therapists` 均 200，演示种子数据在。

重新部署（CLI 的 `--envParams` 报 `Conf.OperationMode` 不识别，别加这个参数）：

```
# API：跳过 target/，否则代码包超限
cd server && wxcloud run:deploy . -e prod-d3gvc0fd30c1c760d -s muscle-api \
  --containerPort 8080 --targetDir . --dockerfile Dockerfile --noConfirm --remark p0-demo

# 管理后台：上传前先排除 node_modules/ 和 dist/（174M，CLI 不完全遵守 .dockerignore）
cd apps/admin-web && wxcloud run:deploy . -e prod-d3gvc0fd30c1c760d -s muscle-admin \
  --containerPort 80 --targetDir . --dockerfile Dockerfile --noConfirm --remark p0-admin-web
```

反代目标写死在 `apps/admin-web/Dockerfile` 的 `API_UPSTREAM` / `API_HOST`（envsubst 注入 nginx 模板），
本地 `deploy/docker-compose.yml` 用 `environment` 覆盖回 `http://server:8080`。换 API 域名时改 Dockerfile。

**H2 是内存库**：容器重启或扩容后订单数据清空、多实例之间不一致。仅够单次演示。
微信会把云数据库账号发到管理员微信，切正式 MySQL 时用它填 `SPRING_DATASOURCE_*` 并把 profile 从 `dev` 换掉。

## 服务器域名（小程序后台）

两个小程序的 `config.js` 已指向 `https://muscle-api-297565-11-1469372614.sh.run.tcloudbase.com`。
真机体验前必须在 MP 后台「开发管理 → 开发设置 → 服务器域名」把该域名加进 request 合法域名；
开发者工具里可临时勾「不校验合法域名」跳过。

| 类型 | C 端 | 员工端 |
| --- | --- | --- |
| request 合法域名 | [ ] `muscle-api-297565-11-1469372614.sh.run.tcloudbase.com` | [ ] 同左 |
| uploadFile | P0 可不配 | [ ] 头像/封面如走 OSS |
| downloadFile | [ ] | [ ] |

员工端 `apps/mini-staff/project.config.json` 仍是 `touristappid`，只能在工具里跑，无法真机预览/上传，需要单独的小程序 AppID。

## 验收签字

| 角色 | 姓名 | 日期 |
| --- | --- | --- |
| 后端 | | |
| 前端 | | |
| 运营 / 主体管理员 | | |

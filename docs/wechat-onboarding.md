# 微信开通检查单

支付 PR（PR8）合并前必须全部勾选。审核日历单独排，不要假设某一周一定过审。

## 小程序主体与类目

- [ ] 主体已认证（与商户号主体一致）
- [ ] C 端小程序类目已通过（生活服务 / 到店服务等相关类目）
- [ ] 员工端小程序类目已通过（可与 C 端同主体）

## AppID

| 端 | AppID | 备注 | 到位日 |
| --- | --- | --- | --- |
| C 端 `apps/mini-customer` | `wx212c1b4e136ef4e0` | 已写入 `project.config.json`；Secret 只放本机 `.env.local` | 2026-08-15 |
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

## 服务器域名（小程序后台）

| 类型 | C 端 | 员工端 |
| --- | --- | --- |
| request 合法域名 | [ ] | [ ] |
| uploadFile | P0 可不配 | [ ] 头像/封面如走 OSS |
| downloadFile | [ ] | [ ] |

## 验收签字

| 角色 | 姓名 | 日期 |
| --- | --- | --- |
| 后端 | | |
| 前端 | | |
| 运营 / 主体管理员 | | |

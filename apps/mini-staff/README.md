# 员工端（已并入顾客端，暂不维护）

2026-08-16 起，员工端的页面已经合并进 `apps/mini-customer`：

| 原路径 | 现路径 |
| --- | --- |
| `pages/index/index` | `pages/staff/home/home` |
| `pages/today/today` 等 7 页 | `pages/staff/<name>/<name>` |
| `utils/api.js` | `utils/staff-api.js`（调用签名不变，改走共享 transport） |
| `utils/qrcode.js` | `utils/qrcode.js` |

## 为什么合并

独立的员工端需要自己的 AppID（这里一直是 `touristappid`，上传被微信拒：
`AppID 不合法 code 10`），而换了 AppID 就用不了 `wx.cloud.callContainer`
——那个机制要求小程序和云托管服务同属一个 AppID。合并到顾客端之后，员工页面
也免配 request 合法域名。

入口在「我的」页：连点底部版本号 5 次显示，不进 tabBar，普通顾客看不到。

## 这个目录还留着做什么

只作为将来真要拆分时的起点。**改动请改 `apps/mini-customer/pages/staff/`**，
这里的代码不再跟进，也不会被上传。

真要拆分的前提：员工端拿到自己的 AppID，并接受它必须配 request 合法域名；
若还想让它免配域名，得在那个 AppID 下另开云托管环境，而两个环境各一份 H2
内存库数据不通——那要先切到共享 MySQL 才谈得上。

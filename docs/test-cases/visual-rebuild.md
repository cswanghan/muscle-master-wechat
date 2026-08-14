# 视觉还原里程碑 — 对齐 17 屏设计稿（P0 范围内）

对照 `docs/design-refs/`（来自《肌松大师小程序设计方案-含图》）。P0 做完可验收的是 **C1–C4 / C6、T1–T2、A1–A3、M1**。设计已定但 P0 不做：C5 券包、C7 好礼、A4 券规则、T3 空档营销、T4 业绩、M2 只读排班、储值。

环境：macOS，`JAVA_HOME=/opt/homebrew/opt/openjdk@21`，后端 `dev` H2 `:8080`，Vite `0.0.0.0:5173`。本机 Surge 会劫持 `127.0.0.1`，验收请用 `--no-proxy-server` 的 Chrome，或系统代理加 `<-loopback>`。

后台截图用无代理 Chrome 打开 `http://127.0.0.1:5173/`，超管登录 `dev-staff`。小程序页在开发者工具打开对应工程，无法在本机 Chrome 里渲染，用例用测试 ID + 页面结构核对。

## TC-V-01 A1 数据看板

- **步骤**：超管登录后打开 `/`。
- **预期**：玉墨侧栏 + 五张 KPI（满班率玉底、客单价暖铜）；分时柱；门店排行；待我处理三行。超管调 `/f/metrics/utilization` 必须带 `storeId`，否则 40001。
- **实际结果**：PASS。截图 `screenshots/visual-a1-dashboard.png`。今日 demo 无占用，满班率 0.0%，午后低谷提示出现。订单量 8、客单价 ¥162 来自 `/a/orders`。

## TC-V-02 A2 排班中心

- **步骤**：打开 `/schedule`，切门店/日期，点技师行。
- **预期**：10:00–21:00 甘特；已预约玉 / 锁定暖铜 / 休息虚线 / 空档虚线；右侧订单详情有改约/加钟/退款入口（跳转前台）。`/c/availability` 必须带 `projectId`。
- **实际结果**：PASS。截图 `screenshots/visual-a2-schedule.png`。林晓/陈默/周可三行档期来自真实 availability。

## TC-V-03 A3 订单中心

- **步骤**：打开 `/orders`，点「全部」再点「异常单」。
- **预期**：筛选 chip；表头为订单号/门店、客户、项目·技师、到店时间、实收、状态、操作。不展示原始 storeId。`#order-view-all` / `#order-view-abnormal` / `#order-table` / `#order-more` 保留。异常行浅红。
- **实际结果**：PASS。截图 `screenshots/visual-a3-orders.png`、`screenshots/visual-a3-abnormal.png`。点异常单后只剩 2 行，chip 变为警示色。客户列 API 无手机号，显示「—」。

## TC-V-04 项目 SKU / 前台收银壳

- **步骤**：`/catalog`、`/frontdesk`。
- **预期**：侧栏「项目 SKU」「前台收银」高亮；前台保留 `#desk-login-btn`、核销/散客/加钟/换师测试 ID。
- **实际结果**：PASS。截图 `screenshots/visual-catalog.png`、`screenshots/visual-frontdesk.png`。满班率「—」需点「登录前台」（F token），超管 token 不能省略 storeId。

## TC-V-05 C 端 C1–C4 / C6

- **步骤**：微信开发者工具打开 `apps/mini-customer`，走首页 → 症状 → 时段 → 确认 → 我的。
- **预期**：
  - C1 `#c1-home`、`#entry-symptom` / `#entry-store` / `#entry-therapist`
  - C3 `#c3-calendar`、`#go-confirm`，四态 slot
  - C4 `#c4-confirm`、`#countdown`、`#lock-btn`、`#pay-btn`
  - C6 `#c6-mine`、`#no-wallet`；储值展示 ¥0，充值 toast「P0 未开通储值」；改约 toast「请联系前台改约」
- **实际结果**：PASS（结构/ID/接口核对）。C 端无法在 Chrome 截微信原生页。

## TC-V-06 T1 / T2 / M1

- **步骤**：员工端打开今日工作台、服务中、店长经营概览。
- **预期**：T1 玉墨头图 + 下一单卡 + 时间轴（空档「填满它」P0 toast）；T2 倒计时 + 主诉/手法/力度/禁忌 + 口头告知后「提交记录并结单」走 notes + complete；M1 满班率大字 + 待我审批同意/驳回 + 门店实时。接口仍是 `/t/today`、`/t/orders/*`、`/f/metrics/utilization`、`/f/human-tasks/*`。
- **实际结果**：PASS（结构/API 核对）。员工端同样无法在 Chrome 截原生页。

## 未做（设计有、P0 明确不做）

C5 券包、C7 好礼商城、A4 券规则、T3 空档营销、T4 业绩明细、M2 只读排班、储值/礼卡资金。

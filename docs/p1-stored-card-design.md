# P1 技术设计：储值卡与混合支付

> 状态：**已实现**（server + admin-web + 小程序）
> 前置：`docs/p0-technical-design.md` §3.5 支付；Flyway `V9__stored_card.sql`

## 1. 需求

顾客在门店充值，下单时余额自动抵扣，不够的部分走微信。技师卖卡拿提成。

## 2. 核心决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 余额怎么存 | **本金 / 赠送两笔分开记**，不合成一个数 | 两者退法不同：退卡只退本金，赠送作废。合成一个数就再也拆不回来，退卡时只能靠猜 |
| 扣款顺序 | **先扣赠送再扣本金** | 反过来会把可退的本金花光、只留下不可退的赠送额，顾客退卡只能退到零 |
| 卡扣怎么落账 | payment 表加一条 `channel=CARD` 的 SUCCESS 行 | 一个订单本来就能挂多笔支付，混合支付因此不需要新表；退款也按笔各退各的 |
| 卡销提成口径 | **只认本金**，4%（演示值） | 赠送额是营销成本不是收入，给提成等于倒贴 |
| 余额算不算营收 | **不算**，是负债 | 充值那一刻钱进了账但服务还没发生 |
| 充值走不走微信支付 | **不走**，前台线下收钱后记账 | 把充值也接进微信支付是另一件事，先不混在一起 |

## 3. 支付流程

```
POST /api/v1/c/bookings（下单那一步就会试着付一次）
  └─ applyCard: want = payable - 已付
       ├─ 卡 >= want  → 扣卡 + CARD 收款行 + fire(PAY_SUCCESS)  ⇒ 没有微信这条腿
       ├─ 0 < 卡 < want → 扣卡 + CARD 收款行，余额交给微信      ⇒ 混合支付
       └─ 卡 = 0       → 原路，与改造前无差别
  └─ 微信 jsapiPrepay(差额)  →  WECHAT 收款行 PENDING
       └─ 回调 SUCCESS → fire(PAY_SUCCESS)
```

**先扣卡再问微信要钱**，因为微信预支付的金额必须是差额，顺序不能反。

代价是有个窗口：卡扣了、微信还没付。这段时间订单可能超时或被取消，
所以 `PENDING_PAY --PAY_TIMEOUT/USER_CANCEL--> CLOSED` 两条转移都挂了新的
`OrderSide.RETURN_CARD`，由 `payment/CardCloseSide` 把钱退回卡并冲掉那笔 CARD 收款。
只退余额不冲收款的话，`remainingFen` 会一直认为这单还有钱能退，对账对不上。

**重复调 pay 不会扣第二次**：已经有 CARD 收款的订单直接跳过扣卡这步。
超时重试、用户连点都必须落在同一个拆分上。

## 4. 退款

`settleChannelRefunds` 按 payment 的 channel 分流：

| channel | 退法 |
|---|---|
| `WECHAT` | 调微信退款接口 |
| `CASH` | 标记成功，钱由前台现场退 |
| `CARD` | `refundToCard` 原路退回卡，**不走微信** —— 那笔钱从来没到过微信 |

回卡时按当初扣款的本金 / 赠送比例还回去：全退成本金等于把赠送额洗成可提现的钱，
全退成赠送又亏了顾客。

混合支付的单退款时，卡那一半回卡、微信那一半回微信。全退回卡等于把顾客的现金变成储值。

## 5. API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/c/card` | 顾客 | 钱包：本金 / 赠送 / 合计 + 最近 20 条流水。没开卡返回全 0 不是 404 |
| POST | `/api/v1/f/cards/topup` | `frontdesk:order:*` | 按手机号充值。顾客必须已在小程序登录过 |
| POST | `/api/v1/f/cards/lookup?phone=` | `frontdesk:order:*` | 查余额 |

幂等键是 `card_transaction.request_id` 上的唯一索引。前台网络抖一下重发不会白送一笔。

## 6. 顺带修掉的

- **`toPayResponse` 无条件 resign**：卡付 / 现金的 payment 没有 prepayId，
  mock 渠道照样会吐一组 `prepay_id=null` 的参数，客户端拿去 `requestPayment`
  只会得到一个看不懂的失败。现在只有 WECHAT 那条腿才签。
- **下单响应的 status 是快照**：`locked.status()` 取在支付之前，储值够付时
  会返回一个"待支付"的已付单，用户再点一次支付就是 409。现在如实报 BOOKED。
- **`CardCloseSide` 的行锁**：payment store 有自己一套 ThreadLocal 行锁，
  跑在状态机事务里也必须自己 `beginWork/commitWork` 配对，否则锁留在线程上，
  下一个 `lockByRefundNo` 撞上同一行就永远等下去（第一版就这么挂住了整个测试套件）。

## 7. 不做

- **不做退卡**：本金可退是账务口径，真要退还涉及原路退回哪一笔、赠送怎么作废，
  单独做。当前只有消费和订单退款两个出口。
- **不做卡的有效期 / 门店限用**：`store_id` 只记发卡门店，不限制在哪家店消费。
- **不做多张卡**：一个顾客一张卡（`uk` 在 `customer_id + status`），
  多卡要先想清楚扣款优先级。
- **提成比例仍是演示值**：`CardPolicy.SALE_COMMISSION_RATE_X100 = 400`，
  和 `CommissionPolicy` 一样等运营确认，没有做成配置表。

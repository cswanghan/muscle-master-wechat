# P1 技术设计：评价体系 / 技师统计展示 / 等级与价格带

> 状态：**待确认**（未动代码）
> 前置：`docs/p0-technical-design.md`（line 81/86/868 把评价体系明确划在 P0 之外、`REVIEWED` 枚举预留但无 API）

## 1. 需求

客户端展示技师「30 天回头数 + 好评情况」，方便客户在技师之间选择；每单服务完成后可打分评价；技师按累积评价晋升等级，不同等级对应不同价格带。

三块是**链式依赖**，不能并行：

```
评价系统（数据源）→ 30 天统计展示 → 等级晋升（消费统计）→ 价格带（消费等级）
```

## 2. 现状：能复用的地基

当初按 P1 预留过，不是从零开始。

| 已有 | 位置 | 状态 |
|---|---|---|
| `therapist.level`（`JUNIOR/MIDDLE/SENIOR/CHIEF`） | `V1__init.sql:1487` | 表里有，admin 手填，**不参与定价** |
| `therapist.rating_x100` / `service_count` | `V1__init.sql` | 有字段，默认 500 / 0，**无数据来源** |
| `OrderStatus.REVIEWED` + `OrderEvent.REVIEW` | `OrderStatus.java` | 枚举齐全 |
| `COMPLETED --REVIEW--> REVIEWED` | `OrderStateMachine.java:365` | 转移表已连，`OrderSide.NONE` |
| 闸门 `ctx.reviewAllowed()` | `OrderStateMachine.java:219` | 现返回 `"review-p1"` 拒绝；`FireContext.withReviewAllowed()` 已备好 |
| `service_record(order_id, therapist_id, customer_id, store_id, started_at, ended_at)` | `V1__init.sql` | **真的在写**（`ServiceRecordSide` 与 CAS 同事务），已有 `idx_svc_therapist(therapist_id, started_at)` / `idx_svc_customer` |
| Side 扩展位 | `OrderSide` 枚举 + `OrderStateMachine:254` switch | `ServiceRecordSide` 是 `@Autowired(required=false)`，新增 Side 照抄即可 |
| 技师级价格通路 | `AvailabilityService.bookableStarts` | **已经是按技师循环算价**，`AvailabilityDtos.Start.priceFen` 天然支持技师间不同价 |

缺的只有三样：`review` 表（一张都没有）、晋升流水、等级→价格的通路。

## 3. 已定口径（2026-08-25 确认）

| 决策 | 结论 |
|---|---|
| 回头口径 | **回头次数 + 老客人数**，两个数一起给（「30 天 128 次，其中回头 76 次 / 41 位老客」） |
| 好评口径 | **好评率 + 评价条数**（「好评率 96%（52 条）」），`score >= 4` 计好评 |
| 价格带落法 | **等级系数表**（`therapist_level_config`），不做门店×项目×等级价表 |
| 晋升生效 | **系统算达标，admin 确认才生效**；不自动改 `level` |

## 4. 数据模型（Flyway V6）

下一个版本号是 **V6**（现有 V1–V5）。

### 4.1 `order_review` — 评价主表

```sql
CREATE TABLE order_review (
  id            BIGINT       NOT NULL PRIMARY KEY,
  order_id      BIGINT       NOT NULL,
  customer_id   BIGINT       NOT NULL,
  therapist_id  BIGINT       NOT NULL,
  store_id      BIGINT       NOT NULL,
  score         TINYINT      NOT NULL COMMENT '1-5 星',
  positive      TINYINT      NOT NULL COMMENT '1=好评；落库时按当时口径定格，日后改口径不追溯历史',
  tags          VARCHAR(255)          COMMENT '标签码，逗号分隔',
  content       VARCHAR(500),
  anonymous     TINYINT      NOT NULL DEFAULT 0,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=下架',
  created_at    DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_review_order (order_id),
  KEY idx_review_therapist (therapist_id, created_at),
  KEY idx_review_customer  (customer_id, created_at)
) COMMENT '一单一评';
```

两个设计点：

- `uk_review_order` 让「一单一评」由数据库兜底，重复提交天然幂等，不依赖应用层判重。
- `positive` **落库定格**而不是每次按 `score >= 4` 现算。以后若把门槛改成 5 星，历史技师的好评率不会一夜之间集体跳水。

### 4.2 `therapist_stat_30d` — 统计物化表

```sql
CREATE TABLE therapist_stat_30d (
  therapist_id          BIGINT      NOT NULL PRIMARY KEY,
  served_count          INT         NOT NULL DEFAULT 0 COMMENT '30 天完成服务次数',
  repeat_count          INT         NOT NULL DEFAULT 0 COMMENT '其中老客到访次数',
  repeat_customer_count INT         NOT NULL DEFAULT 0 COMMENT '去重老客人数',
  review_count          INT         NOT NULL DEFAULT 0,
  positive_count        INT         NOT NULL DEFAULT 0,
  positive_rate_x100    INT         NOT NULL DEFAULT 0,
  avg_score_x100        INT         NOT NULL DEFAULT 0,
  window_start          DATE        NOT NULL,
  window_end            DATE        NOT NULL,
  calc_at               DATETIME(3) NOT NULL
) COMMENT '30 天滚动统计，日更 + 评价写入增量';
```

**为什么必须物化**：`GET /c/availability` 一次返回整店技师，每人一次聚合查询会把这个已经在热路径上的接口拖垮。统计走独立表，读侧是一次主键批量 `IN` 查询。

**回头判定的坑**：`ROW_NUMBER()` 必须在**全历史**上算，再按窗口过滤；反过来先截窗口再排序，会把窗口外首访的老客误判成首访，回头数系统性偏低。

```sql
SELECT therapist_id,
       COUNT(*)                                        AS served_count,
       SUM(rn > 1)                                     AS repeat_count,
       COUNT(DISTINCT IF(rn > 1, customer_id, NULL))   AS repeat_customer_count
FROM (
  SELECT therapist_id, customer_id, started_at,
         ROW_NUMBER() OVER (PARTITION BY therapist_id, customer_id ORDER BY started_at) AS rn
  FROM service_record
  WHERE ended_at IS NOT NULL          -- 只认真正做完的
) t
WHERE started_at >= :windowStart      -- 窗口过滤在排序之后
GROUP BY therapist_id;
```

### 4.3 `therapist_level_config` / `therapist_level_log`（P1/P2）

```sql
CREATE TABLE therapist_level_config (
  level                  VARCHAR(16) NOT NULL PRIMARY KEY,
  sort_no                INT         NOT NULL,
  display_name           VARCHAR(32) NOT NULL,
  price_delta_fen        INT         NOT NULL DEFAULT 0 COMMENT '相对基价的加价（分）',
  min_review_count       INT         NOT NULL COMMENT '晋升门槛：累计评价数',
  min_positive_rate_x100 INT         NOT NULL COMMENT '晋升门槛：累计好评率',
  status                 TINYINT     NOT NULL DEFAULT 1
);

CREATE TABLE therapist_level_log (
  id            BIGINT       NOT NULL PRIMARY KEY,
  therapist_id  BIGINT       NOT NULL,
  from_level    VARCHAR(16),
  to_level      VARCHAR(16)  NOT NULL,
  reason        VARCHAR(64)  NOT NULL COMMENT 'AUTO_ELIGIBLE/ADMIN_CONFIRM/ADMIN_REJECT/ADMIN_MANUAL',
  operator_id   BIGINT,
  snapshot_json VARCHAR(512)          COMMENT '达标时的统计快照，事后可复核',
  status        TINYINT      NOT NULL COMMENT '0=待确认 1=已生效 2=已驳回',
  created_at    DATETIME(3)  NOT NULL,
  decided_at    DATETIME(3),
  KEY idx_level_log_therapist (therapist_id, created_at),
  KEY idx_level_log_pending   (status, created_at)
);
```

**加价额而非百分比系数**：`price_delta_fen` 用绝对值（「高级 +30 元」），不用 `ratio_x100`。三个理由——结果永远是整分，不产生四舍五入争议；运营口头传达无歧义；百分比会在高价项目上被放大成离谱的溢价。

**晋升门槛用累计而非 30 天**：30 天窗口会让技师休一次长假就掉出门槛。展示用 30 天（反映近况），晋升用累计（反映资历）。

## 5. 分期

### P0：评价系统 + 统计展示

跑通「做完 → 评价 → 客户看到」这条闭环。不碰钱。

服务端：

1. V6 migration：`order_review` + `therapist_stat_30d`
2. `OrderSide` 新增 `REVIEW_RECORD`，新增 `ReviewSide` 接口（照抄 `ServiceRecordSide` 的 `@Autowired(required=false)` 写法），转移表 `COMPLETED --REVIEW--> REVIEWED` 的 side 从 `NONE` 改为 `REVIEW_RECORD`，评价行与 CAS 同事务落库
3. `POST /api/v1/c/bookings/{id}/review` — 校验通过后 `ctx.withReviewAllowed()` 放行。校验四条：状态为 `COMPLETED`；调用方是该单的 customer；`uk_review_order` 未占用；距 `service_ended_at` 未超 15 天
4. `GET /api/v1/c/bookings/{id}/review` — 回显已提交的评价
5. 统计维护：日更 job 全量重算 + 评价写入时增量刷该技师一行（新评价要立刻可见，不能等到第二天）
6. `CatalogDtos.TherapistItem` 与 `AvailabilityDtos.Therapist` 增补统计字段，读侧一次主键批量查

小程序（`apps/mini-customer`）：

7. 新增 `pages/review`（星级 + 标签 + 文字 + 匿名开关）
8. `pages/mine` 订单卡：`COMPLETED` 显示「去评价」，`REVIEWED` 显示「已评价」
9. `pages/therapists` 与 `pages/calendar` 的技师卡展示两行统计
10. 页面路由 key 用语义化英文（`review`，不是编号）

**新技师冷启动**：`review_count < 5` 时不显示好评率，显示「新技师」。避免 1 条 5 星刷出「好评率 100%」压过 52 条 96% 的老技师。

验收：一单完整走 `BOOKED → CHECKED_IN → IN_SERVICE → COMPLETED → REVIEWED`；重复提交返回同一条不报错；技师卡数字与 SQL 直查一致。

### P1：等级晋升

11. V7 migration：`therapist_level_config` + `therapist_level_log`，配置表塞入 4 档初值
12. 日更 job：算达标，写 `therapist_level_log(status=0)`，**不改 `therapist.level`**
13. `GET /api/v1/a/therapists/level-pending` + `POST .../level-confirm`（admin 确认/驳回）
14. `apps/admin-web` 增「等级待确认」页
15. 确认生效时改 `therapist.level` 并写回 log

此期 `level` 仍只影响展示，价格不动。

### P2：价格带

单独一期，因为这是唯一会改到**已跑通下单链路**的部分。

16. `Pricing.priceFen` 增 `levelDeltaFen` 入参
17. 四个调用点全部带上等级：`CatalogService:256`、`AvailabilityService:137`、`SlotOccupyService:427`（lockNew，权威价）、`SlotOccupyService:1415`（改约重算）
18. `SlotOccupyStore.TherapistRef(id, homeStoreId)` 扩 `level` 字段
19. 单店灰度开关

**等级加价与 slot override 的关系**：有 `slot.price_override_fen` 时直接用，**不叠加等级**——override 的语义是「这个时段就卖这个价」，是最终裁定。否则 `base = coalesce(store_project, project)`，最终 `= base + levelDelta`。

## 6. 已知冲突（P2 必须同时解决）

`SlotOccupyService:1420` 有一条硬校验：

```java
if (newPrice != order.payableFen()) {
    throw new ApiException(ErrorCodes.ILLEGAL_TRANSITION, "改约须同价");
}
```

等级加价一旦生效，**改约换到不同等级的技师会被这条直接拒掉**，报错文案还是「改约须同价」——运营和客户都看不懂发生了什么。`SWAP_THERAPIST` 同理。

P2 必须明确：改约/换师限定在同等级内（文案改为「改约需选择同等级技师」），跨等级走取消重下。这条不解决就不能放开价格带。

另外两条较轻：

- `AvailabilityCache` 是 store+date 维度，等级变更后价格有最多 30s 延迟。可接受，不专门做失效。
- `booking_order.origin_price_fen` 已经是下单时的价格快照，晋升**不会**回改历史订单。这点是现成的，不需要额外处理。

## 7. 不做

- 评价回复（技师/门店回复客户）——等有真实评价量再说
- 评价配图 / 视频——要接对象存储，不在这轮
- 评价激励（评价返券）——涉及资金，`docs/p0-technical-design.md:86` 已明确资金账户表不建
- `therapist.rating_x100` 保留但不再手填，P0 起由统计表接管展示；字段本身暂不删，避免动 V1 schema

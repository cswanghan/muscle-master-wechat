# 坏锁回滚

症状：超卖、大面积 `LOCKED` 不释放、床被错误占用。设计见 `docs/p0-technical-design.md` §坏锁回滚。

灰度开关属性名：`app.booking.lock.enabled`（环境变量 `BOOKING_LOCK_ENABLED`，默认 `true`）。

## 不要做

- **不要回滚 Flyway。** 迁移只加不改列；回滚镜像即可，schema 向前兼容。
- **不要关掉 5 min 扫描。** `JobRunner.scanExpiredLocksEvery5Min` cron `0 */5 * * * *` / `Asia/Shanghai` 在旧版本必须仍存在。扫描是权威释放路径；延时任务最多丢 5 分钟。

## 步骤

### 1. 紧急停锁

```
app.booking.lock.enabled=false
# 或 BOOKING_LOCK_ENABLED=false
```

C 端停售，前台停散客。目录浏览仍可用。不要在这个窗口开第二家灰度店。

### 2. ForceReleaseJob 清 PENDING_PAY 残留

对仍 `PENDING_PAY` 且格子 `LOCKED` 的 hold 跑 `ForceReleaseJob`（与扫描共用 `forceFreeByHold`）：

- 超时未支付单：`SlotScanJob` / `RELEASE_LOCK` 只 `fire(PAY_TIMEOUT)`，由状态机调 `ReleaseLock`（Law A）。
- 扫描后仍 `LOCKED` 的 PENDING_PAY leftover：`ForceReleaseJob.run(holdId)` → 格 `FREE`，occupancy 删除。

列出仍 `LOCKED` 且无 `booking_order` 的孤儿 hold。

### 3. 超管 force-release 孤儿

内部端点（不在 `/api/v1/c|t|f|a`）：

```
POST /internal/force-release?holdId={holdId}
X-Internal-Token: <token>
```

开启条件：

- `app.internal.force-release.enabled=true`
- 配置 `app.internal.force-release.token`
- **仅本机 loopback**

只释放 `LOCKED` occupancy / 行。已 `BOOKED` 的占用不会被 `forceFreeByHold` 删掉。写审计 `FORCE_RELEASE`。

### 4. 回滚 server 镜像

回滚到上一稳定版本。**不要回滚 Flyway**（V1…Vn 只加不改）。5 min 扫描在旧版本必须仍存在。

### 5. 修复后恢复

1. 先在灰度店开 `app.booking.lock.enabled=true`。
2. 跑并发测试套件（lockNew / ReleaseLock / ForceReleaseJob drill）。
3. `inventory.drift=0` 后再开第二家。

## 演练

`LockRollbackDrillTest`：种 stuck `LOCKED` → `ForceReleaseJob` / `forceFreeByHold` → 断言 `FREE` + occupancy 空。

`GraySliceE2eTest`：未支付取消 + 过期 `PAY_TIMEOUT` 走 `ReleaseLock`。

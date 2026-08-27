# P1 技术设计：员工入离职

> 状态：**已实现**（server + admin-web）
> 前置：`docs/p0-technical-design.md` 的 RBAC 章节；`V2__rbac_seed.sql` 的角色/权限表

## 1. 需求

管理后台要能办入职、离职、复职，并留下谁在什么时候做了什么的记录。

## 2. 核心决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 离职怎么落 | `staff_user.status = 0`，**不删行、不写 `deleted_at`** | 历史订单、业绩、评价、`service_record` 全部外键指向这个账号；删了就断线索，业绩报表会出现"无名技师" |
| 过程放哪 | 新表 `employment_log`，一次动作一行 | `status` 只记得住"现在是什么"，记不住"什么时候离的、为什么"。复职就是再加一行，不改历史行 |
| 名下还有单怎么办 | **默认阻断**，返回冲突单清单；`force=true` 才放行 | 直接停用会让顾客到店找不到人，而系统这边看起来一切正常 |
| 入职建不建技师档案 | 角色含 `THERAPIST` 时**一并建** `catalog.therapist` | 光有登录账号排不了班：排班、可选技师、业绩都挂在 therapist 行上 |
| 能发什么角色 | 只有 `THERAPIST / FRONTDESK / STORE_MANAGER / OPS` | 否则拿着 `staff:manage` 的店长可以给自己开超管账号，一步绕开整套权限。超管/财务走 DBA |
| 权限码 | 新增 `staff:manage`，不搭 `catalog:therapist` 的便车 | 建账号、改账号状态比改目录重 |

阻断的口径是"离职后仍会发生的服务"：`PENDING_PAY / BOOKED / CHECKED_IN / IN_SERVICE`，
从今天起往后看 60 天。已完成、已取消的单不算冲突。

## 3. 数据模型（Flyway V10）

```sql
CREATE TABLE employment_log (
  id           BIGINT       NOT NULL COMMENT '雪花 ID',
  staff_id     BIGINT       NOT NULL,
  action       VARCHAR(16)  NOT NULL COMMENT 'ONBOARD / OFFBOARD / REHIRE',
  effective_on DATE         NOT NULL COMMENT '生效日',
  reason       VARCHAR(128)     NULL,
  operator_id  BIGINT           NULL,
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_employment_staff (staff_id, created_at)
);
```

同一毫秒内连着入职→离职→复职会让 `created_at` 打平，排序一律带 `id` 兜底
（雪花 ID 单调递增）。内存实现和 JDBC 实现都要带，不然 dev 和 prod 的列表顺序会不一样。

## 4. API

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| GET | `/api/v1/a/employment/staff?status=` | `staff:manage` | 花名册；`status` 空=全部，行内带 `therapistId` / `pendingOrders` |
| GET | `/api/v1/a/employment/logs?staffId=` | `staff:manage` | 异动流水；不带 `staffId` 返回最近 50 条 |
| POST | `/api/v1/a/employment/onboard` | `staff:manage` | 建账号（+技师档案），返回 `staffId` / `therapistId` |
| POST | `/api/v1/a/employment/{id}/offboard` | `staff:manage` | 离职；被挡时 **HTTP 200 + `status` 不变 + `blockedOrders`** |
| POST | `/api/v1/a/employment/{id}/rehire` | `staff:manage` | 复职 |

离职被挡走的是正常返回而不是报错：前端要把冲突单摆出来给人看，再由人决定是改约还是强制离职。
用错误码的话前端就得从 message 里抠单号。

## 5. 顺带修掉的

`JdbcStaffUserRepository.insert()` 原来只写 `staff_user` 一行，**不写 `staff_role` / `data_scope`**
——dev 用内存实现看不出来，prod 里新建的员工会是零角色零数据域，登录进来什么都点不动。
现在 insert/update 都会同步这两张关联表。

角色为空一律当"这次不改角色"处理，不当清空：登录链路的 `update(staff)` 传的是完整对象，
但没 hydrate 过的 `StaffUser` 的 `roleCodes` 恰好是空列表。真要停权限走离职，不是把角色删光。

## 6. 不做

- **离职日期不做定时生效**：填了未来的 `effective_on` 也是立即停用，那个日期只是给 HR 记账用的。
  真要做延后生效得有个 job 扫，而且中间那段时间"还能不能接单"是业务问题不是技术问题，先不猜。
- **不做交接**：离职时不自动把名下的单转给别人。谁接手是人的决定。
- **不做离职回收**：账号停用即可，不删 openid 绑定——同一个人复职还是同一个微信。

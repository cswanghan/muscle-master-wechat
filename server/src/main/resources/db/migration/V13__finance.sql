-- 财务：支出、工资口径、月度快照。
--
-- 收入侧此前已经齐了（耗课流水、支付、退款），缺的一直是**支出**——
-- 系统里一分钱的成本都没记过，所以利润表无从算起。这一批补的就是那一半。

-- ── 支出 ──────────────────────────────────────────────────────
-- 物料补充要上传凭证并审批，与成本表挂钩：审批通过的才计入当月成本。
CREATE TABLE expense_record (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  store_id      BIGINT       NOT NULL,
  category      VARCHAR(24)  NOT NULL COMMENT 'MATERIAL 物料 / RENT 租金 / PROPERTY 物业 / UTILITY 水电 / LABOR 人工 / PROMOTION 推广 / OTHER',
  amount_fen    BIGINT       NOT NULL,
  happened_on   DATE         NOT NULL COMMENT '费用归属日，决定算进哪个月',
  vendor        VARCHAR(64)      NULL,
  remark        VARCHAR(255)     NULL,
  proof_url     VARCHAR(500)     NULL COMMENT '支出凭证',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  submitted_by  BIGINT       NOT NULL,
  reviewed_by   BIGINT           NULL,
  reviewed_at   DATETIME(3)      NULL,
  reject_reason VARCHAR(255)     NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_exp_store_month (store_id, happened_on, status),
  KEY idx_exp_status (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='门店支出；仅 APPROVED 计入成本';

-- ── 工资口径 ──────────────────────────────────────────────────
-- 课时费和提点是每位老师可以不同的，所以按人存；没有单独配的走 policy 默认值。
CREATE TABLE payroll_config (
  id                    BIGINT      NOT NULL COMMENT '雪花 ID',
  therapist_id          BIGINT      NOT NULL,
  store_id              BIGINT      NOT NULL,
  base_salary_fen       BIGINT      NOT NULL DEFAULT 0 COMMENT '底薪',
  lesson_fee_fen        BIGINT      NOT NULL DEFAULT 0 COMMENT '每节课的课时费',
  sale_rate_x100        INT         NOT NULL DEFAULT 0 COMMENT '销售提点，万分比；0=用默认',
  effective_on          DATE        NOT NULL,
  created_at            DATETIME(3) NOT NULL,
  updated_at            DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_payroll_therapist (therapist_id),
  KEY idx_payroll_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='老师薪资口径';

-- ── 退费原因 ──────────────────────────────────────────────────
-- 退费报表要「责任老师 / 退费原因」两个维度，refund 表原本只有 reason 自由文本。
ALTER TABLE refund
  ADD COLUMN reason_code VARCHAR(24) NULL COMMENT 'EFFECT 效果不满意 / SERVICE 服务问题 / SCHEDULE 时间冲突 / RELOCATE 搬家 / HEALTH 身体原因 / OTHER' AFTER reason,
  ADD COLUMN liable_therapist_id BIGINT NULL COMMENT '责任老师；无责可空' AFTER reason_code;

CREATE INDEX idx_refund_liable ON refund (liable_therapist_id, created_at);

-- ── 权限 ──────────────────────────────────────────────────────
INSERT INTO permission (id, code, name) VALUES
  (2000000000000000123, 'expense:write',   '财务-支出提报'),
  (2000000000000000124, 'expense:approve', '财务-支出审批'),
  (2000000000000000125, 'finance:report',  '财务-报表查看'),
  (2000000000000000126, 'payroll:config',  '财务-薪资口径');

-- 提报：前台(6) 店长(5) 及以上
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000123),
  (2000000000000000003, 2000000000000000123),
  (2000000000000000004, 2000000000000000123),
  (2000000000000000005, 2000000000000000123),
  (2000000000000000006, 2000000000000000123);

-- 审批：店长(5) 区域(4) 财务(2) 超管(1)。提报人不能自批，由服务层校验。
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000124),
  (2000000000000000002, 2000000000000000124),
  (2000000000000000004, 2000000000000000124),
  (2000000000000000005, 2000000000000000124);

-- 报表：财务(2) 店长(5) 区域(4) 运营(3) 超管(1)
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000125),
  (2000000000000000002, 2000000000000000125),
  (2000000000000000003, 2000000000000000125),
  (2000000000000000004, 2000000000000000125),
  (2000000000000000005, 2000000000000000125);

-- 薪资口径：财务(2) 区域(4) 超管(1)。店长不给 —— 自己定自己店老师的工资口径不合适。
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000126),
  (2000000000000000002, 2000000000000000126),
  (2000000000000000004, 2000000000000000126);

-- ── 订阅消息额度与课前提醒 ──────────────────────────────────
-- 微信的硬规则：用户点一次「允许」，才能推一条。所以额度要单独记账，
-- 推完即销 —— 做不到"买了 12 次课就自动提醒 12 次"。
CREATE TABLE subscribe_grant (
  id           BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id  BIGINT       NOT NULL,
  template_id  VARCHAR(64)  NOT NULL,
  order_id     BIGINT           NULL COMMENT '换额度时对应的那一单',
  status       VARCHAR(16)  NOT NULL DEFAULT 'GRANTED' COMMENT 'GRANTED / USED',
  granted_at   DATETIME(3)  NOT NULL,
  sent_at      DATETIME(3)      NULL,
  fail_reason  VARCHAR(255)     NULL,
  PRIMARY KEY (id),
  KEY idx_grant_customer (customer_id, status, granted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='订阅消息额度；一次授权一条';

CREATE TABLE class_reminder (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id   BIGINT       NOT NULL,
  order_id      BIGINT       NOT NULL,
  store_id      BIGINT       NOT NULL,
  kind          VARCHAR(24)  NOT NULL DEFAULT 'BEFORE_CLASS',
  service_date  DATE         NOT NULL,
  fire_at       DATETIME(3)  NOT NULL COMMENT '到点推送时间',
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / SKIPPED / FAILED',
  sent_at       DATETIME(3)      NULL,
  fail_reason   VARCHAR(255)     NULL,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_reminder_order (order_id),
  KEY idx_reminder_due (status, fire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课前提醒；无额度记 SKIPPED 而非 FAILED';

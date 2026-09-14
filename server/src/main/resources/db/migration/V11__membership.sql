-- 会员制课包。与 stored_card 并存而不是合并：
-- 储值卡存的是**钱**（按金额抵扣、按余额退），课包存的是**次数**（按次扣、按剩余次数×单价退），
-- 而且课包还有有效期和课程类型。塞进同一张表的话，这三件事都没地方放。

CREATE TABLE member_profile (
  id                  BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id         BIGINT       NOT NULL,
  store_id            BIGINT       NOT NULL COMMENT '主门店',
  owner_therapist_id  BIGINT           NULL COMMENT '归属老师；老师端"我的会员"按这个过滤',
  core_issue          VARCHAR(255)     NULL COMMENT '核心问题，如"腰椎间盘突出 / 圆肩驼背"',
  remark              VARCHAR(500)     NULL,
  created_at          DATETIME(3)  NOT NULL,
  updated_at          DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_profile_customer (customer_id),
  KEY idx_profile_owner (owner_therapist_id, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员档案；一人一行，可编辑';

-- 会员持有的课包。课程类型直接复用 project（"私教60分钟"已经有时长/缓冲/价格），
-- 不再单开一张课包 SKU 表 —— 课包 = project × 次数 × 有效期。
CREATE TABLE member_package (
  id                   BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id          BIGINT       NOT NULL,
  store_id             BIGINT       NOT NULL,
  project_id           BIGINT       NOT NULL COMMENT '课程类型，指向 project',
  title                VARCHAR(64)  NOT NULL COMMENT '如"私教60分钟 10 次卡"',
  total_sessions       INT          NOT NULL COMMENT '购买总次数',
  used_sessions        INT          NOT NULL DEFAULT 0 COMMENT '已耗课次数',
  price_fen            BIGINT       NOT NULL COMMENT '实收金额',
  unit_price_fen       BIGINT       NOT NULL COMMENT '单次价，退课按它折算',
  seller_therapist_id  BIGINT           NULL COMMENT '卖课提成归谁；前台自己卖的可空',
  effective_on         DATE         NOT NULL,
  expire_on            DATE             NULL COMMENT '空=不过期',
  status               TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用 2=已用完 3=已过期',
  created_at           DATETIME(3)  NOT NULL,
  updated_at           DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_pkg_customer (customer_id, status),
  KEY idx_pkg_seller (seller_therapist_id, created_at),
  KEY idx_pkg_store (store_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会员课包；余次是负债，耗课才是收入';

-- 每一次课时变动都留痕。卖课和耗课分别记一笔，是因为这两个数的业务含义不同：
-- 卖课收的钱是预收（负债），耗课才是实现的收入。老师的两项业绩也分别从这里统计。
CREATE TABLE package_transaction (
  id                 BIGINT       NOT NULL COMMENT '雪花 ID',
  member_package_id  BIGINT       NOT NULL,
  customer_id        BIGINT       NOT NULL,
  store_id           BIGINT       NOT NULL,
  type               VARCHAR(16)  NOT NULL COMMENT 'PURCHASE / CONSUME / REFUND / ADJUST',
  delta_sessions     INT          NOT NULL COMMENT '耗课为负',
  order_id           BIGINT           NULL COMMENT '耗课关联的订单',
  therapist_id       BIGINT           NULL COMMENT '上这节课的老师，耗课业绩归他',
  request_id         VARCHAR(64)      NULL COMMENT '幂等键',
  remark             VARCHAR(255)     NULL,
  created_at         DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_pkg_txn_request (request_id),
  KEY idx_pkg_txn_pkg (member_package_id, created_at),
  KEY idx_pkg_txn_therapist (therapist_id, type, created_at),
  KEY idx_pkg_txn_store (store_id, type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课时流水';

-- 训练计划。done_sessions 由耗课累加，不手填 —— 手填的进度迟早跟实际上课对不上。
CREATE TABLE training_plan (
  id                BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id       BIGINT       NOT NULL,
  store_id          BIGINT       NOT NULL,
  therapist_id      BIGINT           NULL COMMENT '制定计划的老师',
  title             VARCHAR(64)  NOT NULL,
  goal              VARCHAR(500)     NULL COMMENT '训练目标',
  total_sessions    INT          NOT NULL COMMENT '计划总次数',
  done_sessions     INT          NOT NULL DEFAULT 0 COMMENT '已完成，由耗课累加',
  weekly_frequency  INT          NOT NULL DEFAULT 0 COMMENT '每周几次；0=不定。P2 自动排课用',
  start_on          DATE         NOT NULL,
  end_on            DATE             NULL,
  status            TINYINT      NOT NULL DEFAULT 1 COMMENT '1=进行中 2=已完成 0=已终止',
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_plan_customer (customer_id, status),
  KEY idx_plan_therapist (therapist_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='训练计划';

-- 订单用了哪张课包。为空=按金额付的普通单，两条路并存。
ALTER TABLE booking_order
  ADD COLUMN member_package_id BIGINT NULL COMMENT '用课包抵扣的课包 ID' AFTER designated;

CREATE INDEX idx_order_member_pkg ON booking_order (member_package_id, status);

-- 会员管理权限。老师看自己的会员走 staff:self，
-- 卖课、改档案、改计划是另一回事，单独一个码。
INSERT INTO permission (id, code, name) VALUES
  (2000000000000000118, 'member:manage', '会员-档案与课包');

-- SUPER_ADMIN(1) / OPS(3) / REGION_MANAGER(4) / STORE_MANAGER(5) / FRONTDESK(6)
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000118),
  (2000000000000000003, 2000000000000000118),
  (2000000000000000004, 2000000000000000118),
  (2000000000000000005, 2000000000000000118),
  (2000000000000000006, 2000000000000000118);

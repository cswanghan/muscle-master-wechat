-- 获客与经营地基：渠道、体验课、回访、考勤、课后打卡、阶段评估、双向打分。
-- 这一批是后面绝大多数报表与排名的前置 —— 渠道和体验课不先落库，
-- 转成交率、体验课报表、新客好评率都算不出来，而且补不回历史数据。

-- ── 渠道与转化 ────────────────────────────────────────────────
-- source（MINI_C / WALK_IN）说的是**下单入口**，这里说的是**人从哪来的**，两个维度。
ALTER TABLE member_profile
  ADD COLUMN channel VARCHAR(32) NULL COMMENT '获客渠道 XIAOHONGSHU/DOUYIN/DIANPING/REFERRAL/WALK_IN/FRIEND/OTHER' AFTER store_id,
  ADD COLUMN referrer_customer_id BIGINT NULL COMMENT '转介绍人；channel=REFERRAL 时有值' AFTER channel,
  ADD COLUMN wx_nickname VARCHAR(64) NULL COMMENT '微信名，回访时对得上人' AFTER referrer_customer_id,
  ADD COLUMN first_visit_on DATE NULL COMMENT '首次到访' AFTER wx_nickname,
  ADD COLUMN converted_on DATE NULL COMMENT '体验转正课之日；空=还没转化' AFTER first_visit_on;

CREATE INDEX idx_profile_channel ON member_profile (store_id, channel, first_visit_on);

-- 体验课与正课的唯一区别是这个标记。首单低价、目的是转化，
-- 好评认领、转成交率、体验课报表全靠它分流。
ALTER TABLE booking_order
  ADD COLUMN trial TINYINT NOT NULL DEFAULT 0 COMMENT '1=体验课' AFTER member_package_id;

CREATE INDEX idx_order_trial ON booking_order (store_id, trial, service_date);

-- ── 回访 ──────────────────────────────────────────────────────
-- 成交路径里的关键环节：店长回访带成交诉求，老师回访送课后叮嘱。
CREATE TABLE follow_up (
  id             BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id    BIGINT       NOT NULL,
  store_id       BIGINT       NOT NULL,
  staff_id       BIGINT       NOT NULL COMMENT '回访人',
  role_kind      VARCHAR(16)  NOT NULL COMMENT 'THERAPIST / MANAGER',
  kind           VARCHAR(16)  NOT NULL COMMENT 'AFTER_CLASS 课后 / RETENTION 续费 / REVIVE 唤回 / TRIAL 体验后',
  channel        VARCHAR(16)  NOT NULL DEFAULT 'WECOM' COMMENT 'WECOM 企业微信 / PHONE / WECHAT / OFFLINE',
  content        VARCHAR(500)     NULL COMMENT '回访留痕',
  outcome        VARCHAR(16)      NULL COMMENT 'REACHED 已接通 / NO_ANSWER / DEAL 成交 / REFUSED',
  due_on         DATE             NULL COMMENT '应回访日；完成度按它算',
  done_at        DATETIME(3)      NULL COMMENT '空=待办',
  created_at     DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_fu_staff (staff_id, due_on, done_at),
  KEY idx_fu_customer (customer_id, created_at),
  KEY idx_fu_store (store_id, kind, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='回访留痕；due_on 未到 done_at 即为待办';

-- ── 考勤 ──────────────────────────────────────────────────────
CREATE TABLE staff_attendance (
  id          BIGINT       NOT NULL COMMENT '雪花 ID',
  staff_id    BIGINT       NOT NULL,
  store_id    BIGINT       NOT NULL,
  work_day    DATE         NOT NULL,
  clock_in_at  DATETIME(3)     NULL,
  clock_out_at DATETIME(3)     NULL,
  created_at  DATETIME(3)  NOT NULL,
  updated_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_att_staff_day (staff_id, work_day),
  KEY idx_att_store (store_id, work_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='上下班打卡；出勤天数=有 clock_in 的天数';

-- ── 课后作业打卡 ──────────────────────────────────────────────
-- 会员完成课后处方后打卡，可选公开成"类朋友圈"。
-- 计划配合度就是按"应打卡天数里实际打了几天"算的。
CREATE TABLE homework_checkin (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id   BIGINT       NOT NULL,
  store_id      BIGINT       NOT NULL,
  plan_id       BIGINT           NULL COMMENT '关联训练计划',
  check_day     DATE         NOT NULL,
  content       VARCHAR(500)     NULL,
  image_urls    VARCHAR(1000)    NULL COMMENT '逗号分隔',
  visibility    VARCHAR(16)  NOT NULL DEFAULT 'PUBLIC' COMMENT 'PUBLIC 可被别人看到 / PRIVATE 只有老师看',
  like_count    INT          NOT NULL DEFAULT 0,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_hw_customer_day (customer_id, check_day),
  KEY idx_hw_store_day (store_id, visibility, check_day),
  KEY idx_hw_plan (plan_id, check_day)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='课后作业打卡；一人一天一条';

CREATE TABLE homework_like (
  checkin_id   BIGINT      NOT NULL,
  customer_id  BIGINT      NOT NULL,
  created_at   DATETIME(3) NOT NULL,
  PRIMARY KEY (checkin_id, customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='打卡点赞；主键即防重复';

-- ── 阶段评估 ──────────────────────────────────────────────────
-- 评估结果是建立信服感的工具：初次评估建立基线，阶段末再测一次做比对。
CREATE TABLE member_assessment (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id   BIGINT       NOT NULL,
  store_id      BIGINT       NOT NULL,
  therapist_id  BIGINT           NULL,
  plan_id       BIGINT           NULL,
  phase         VARCHAR(16)  NOT NULL COMMENT 'BASELINE 初次 / MID 阶段中 / FINAL 阶段末',
  assessed_on   DATE         NOT NULL,
  summary       VARCHAR(500)     NULL COMMENT '总体结论',
  items_json    JSON             NULL COMMENT '[{name,value,unit,note}] 如 前屈/体前屈距离/cm',
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_asm_customer (customer_id, assessed_on),
  KEY idx_asm_plan (plan_id, phase)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='测试评估记录；BASELINE 与 FINAL 比对即"上课后结果比对"';

-- ── 双向打分 ──────────────────────────────────────────────────
-- 一单一条的 order_review 装不下"按阶段"的评价，所以单开一张。
-- 两个方向分开存：客户给老师打的分决定老师口碑，系统给客户打的配合度决定续费话术。
CREATE TABLE stage_review (
  id              BIGINT       NOT NULL COMMENT '雪花 ID',
  customer_id     BIGINT       NOT NULL,
  store_id        BIGINT       NOT NULL,
  therapist_id    BIGINT           NULL,
  plan_id         BIGINT           NULL,
  direction       VARCHAR(16)  NOT NULL COMMENT 'C2T 客户评老师 / SYS2C 系统评客户配合度',
  score           INT          NOT NULL COMMENT '1-5；SYS2C 由打卡率换算',
  comment_text    VARCHAR(500)     NULL,
  created_at      DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_sr_therapist (therapist_id, direction, created_at),
  KEY idx_sr_customer (customer_id, direction, created_at),
  KEY idx_sr_plan (plan_id, direction)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='阶段性双向打分';

-- ── 外部平台好评认领 ──────────────────────────────────────────
-- 点评/小红书/抖音的好评截图由老师提交，店长审核后计入好评率。
-- 平台没有开放接口能自动拉，只能人工认领 + 审核。
CREATE TABLE external_review_claim (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  store_id      BIGINT       NOT NULL,
  therapist_id  BIGINT       NOT NULL,
  customer_id   BIGINT           NULL COMMENT '对应哪位体验客，可空',
  platform      VARCHAR(16)  NOT NULL COMMENT 'DIANPING / XIAOHONGSHU / DOUYIN / MEITUAN',
  rating        INT          NOT NULL COMMENT '1-5',
  proof_url     VARCHAR(500)     NULL COMMENT '截图',
  claimed_on    DATE         NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / APPROVED / REJECTED',
  reviewed_by   BIGINT           NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_erc_therapist (therapist_id, status, claimed_on),
  KEY idx_erc_store (store_id, status, claimed_on)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='外部平台好评认领；需店长审核';

-- ── 权限 ──────────────────────────────────────────────────────
INSERT INTO permission (id, code, name) VALUES
  (2000000000000000119, 'followup:write',  '回访-记录'),
  (2000000000000000120, 'attendance:self', '考勤-本人打卡'),
  (2000000000000000121, 'ranking:national','排名-跨店查看'),
  (2000000000000000122, 'review:claim',    '好评-认领与审核');

-- 回访：技师(7) 前台(6) 店长(5) 区域(4) 运营(3) 超管(1)
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000119),
  (2000000000000000003, 2000000000000000119),
  (2000000000000000004, 2000000000000000119),
  (2000000000000000005, 2000000000000000119),
  (2000000000000000006, 2000000000000000119),
  (2000000000000000007, 2000000000000000119);

-- 打卡：所有员工
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000120),
  (2000000000000000003, 2000000000000000120),
  (2000000000000000004, 2000000000000000120),
  (2000000000000000005, 2000000000000000120),
  (2000000000000000006, 2000000000000000120),
  (2000000000000000007, 2000000000000000120);

-- 跨店排名：技师要看自己的全国名次，所以技师也给。
-- 它只返回名次与本人指标，不泄露别店的明细，与门店数据域不冲突。
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000121),
  (2000000000000000003, 2000000000000000121),
  (2000000000000000004, 2000000000000000121),
  (2000000000000000005, 2000000000000000121),
  (2000000000000000007, 2000000000000000121);

-- 好评认领：技师提交，店长及以上审核
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000122),
  (2000000000000000003, 2000000000000000122),
  (2000000000000000004, 2000000000000000122),
  (2000000000000000005, 2000000000000000122),
  (2000000000000000007, 2000000000000000122);

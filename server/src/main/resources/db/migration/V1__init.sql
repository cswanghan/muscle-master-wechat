-- ============================================================
-- V1__init.sql  P0 full schema (design Data Model Changes)
-- Money: BIGINT fen. Time: DATETIME(3) Beijing. Engine: InnoDB utf8mb4.
-- Slot tables are NOT partitioned in V1 (D24). Same later migration
-- must RANGE-partition therapist_slot and bed_slot together.
-- No customer.balance_fen. No wallet / ledger tables (D14).
-- ============================================================

CREATE TABLE store (
  id              BIGINT       NOT NULL PRIMARY KEY,
  code            VARCHAR(32)  NOT NULL COMMENT '门店编码，删除后不复用',
  name            VARCHAR(64)  NOT NULL,
  phone_cipher    VARBINARY(256) NULL,
  address_cipher  VARBINARY(512) NULL,
  lng             DECIMAL(10,7) NULL,
  lat             DECIMAL(10,7) NULL,
  business_start  TIME         NOT NULL DEFAULT '10:00:00',
  business_end    TIME         NOT NULL DEFAULT '22:00:00',
  timezone        VARCHAR(32)  NOT NULL DEFAULT 'Asia/Shanghai',
  wx_mchid        VARCHAR(32)  NULL COMMENT '空则用平台默认商户号',
  status          TINYINT      NOT NULL DEFAULT 1 COMMENT '1营业 0停业',
  created_at      DATETIME(3)  NOT NULL,
  updated_at      DATETIME(3)  NOT NULL,
  deleted_at      DATETIME(3)  NULL,
  UNIQUE KEY uk_store_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店';

CREATE TABLE room (
  id          BIGINT      NOT NULL PRIMARY KEY,
  store_id    BIGINT      NOT NULL,
  name        VARCHAR(32) NOT NULL,
  sort_no     INT         NOT NULL DEFAULT 0,
  status      TINYINT     NOT NULL DEFAULT 1,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  deleted_at  DATETIME(3) NULL,
  KEY idx_room_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='房间';

CREATE TABLE bed (
  id          BIGINT      NOT NULL PRIMARY KEY,
  store_id    BIGINT      NOT NULL,
  room_id     BIGINT      NOT NULL,
  name        VARCHAR(32) NOT NULL,
  sort_no     INT         NOT NULL DEFAULT 0,
  status      TINYINT     NOT NULL DEFAULT 1,
  created_at  DATETIME(3) NOT NULL,
  updated_at  DATETIME(3) NOT NULL,
  deleted_at  DATETIME(3) NULL,
  KEY idx_bed_store (store_id),
  KEY idx_bed_room (room_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='床位';

CREATE TABLE therapist (
  id              BIGINT      NOT NULL PRIMARY KEY,
  staff_user_id   BIGINT      NULL,
  employee_no     VARCHAR(32) NOT NULL,
  name            VARCHAR(32) NOT NULL,
  home_store_id   BIGINT      NOT NULL COMMENT '人事归属店，P1 提成用',
  level           VARCHAR(16) NOT NULL COMMENT 'JUNIOR/MIDDLE/SENIOR/CHIEF',
  gender          TINYINT     NULL,
  avatar_url      VARCHAR(512) NULL,
  intro           VARCHAR(512) NULL,
  rating_x100     INT         NOT NULL DEFAULT 500,
  service_count   INT         NOT NULL DEFAULT 0,
  status          TINYINT     NOT NULL DEFAULT 1 COMMENT '1在职 0停用',
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  deleted_at      DATETIME(3) NULL,
  UNIQUE KEY uk_therapist_emp (employee_no),
  KEY idx_therapist_home_store (home_store_id),
  KEY idx_therapist_staff (staff_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技师';

CREATE TABLE project (
  id                BIGINT       NOT NULL PRIMARY KEY,
  code              VARCHAR(32)  NOT NULL,
  name              VARCHAR(64)  NOT NULL,
  duration_minutes  SMALLINT     NOT NULL,
  buffer_minutes    SMALLINT     NOT NULL DEFAULT 15 COMMENT 'P0 必须 1–15，对应 buffer_slots=1',
  price_fen         BIGINT       NOT NULL,
  add_on_price_fen  BIGINT       NULL COMMENT '加钟每单位价，空则按时长比例',
  description       TEXT         NULL,
  cover_url         VARCHAR(512) NULL,
  status            TINYINT      NOT NULL DEFAULT 1,
  created_at        DATETIME(3)  NOT NULL,
  updated_at        DATETIME(3)  NOT NULL,
  deleted_at        DATETIME(3)  NULL,
  UNIQUE KEY uk_project_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='项目 SKU';

CREATE TABLE store_project (
  id          BIGINT      NOT NULL PRIMARY KEY,
  store_id    BIGINT      NOT NULL,
  project_id  BIGINT      NOT NULL,
  price_fen   BIGINT      NULL COMMENT '门店覆盖价',
  status      TINYINT     NOT NULL DEFAULT 1,
  UNIQUE KEY uk_store_project (store_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='门店上架项目';

CREATE TABLE symptom (
  id          BIGINT      NOT NULL PRIMARY KEY,
  parent_id   BIGINT      NULL,
  type        VARCHAR(16) NOT NULL COMMENT 'BODY_PART / DISCOMFORT',
  name        VARCHAR(32) NOT NULL,
  sort_no     INT         NOT NULL DEFAULT 0,
  status      TINYINT     NOT NULL DEFAULT 1,
  KEY idx_symptom_parent (parent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='症状';

CREATE TABLE symptom_project (
  symptom_id  BIGINT NOT NULL,
  project_id  BIGINT NOT NULL,
  PRIMARY KEY (symptom_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='症状-项目';

CREATE TABLE therapist_project (
  therapist_id  BIGINT NOT NULL,
  project_id    BIGINT NOT NULL,
  PRIMARY KEY (therapist_id, project_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技师可接项目';

CREATE TABLE therapist_symptom (
  therapist_id  BIGINT NOT NULL,
  symptom_id    BIGINT NOT NULL,
  PRIMARY KEY (therapist_id, symptom_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技师擅长症状';

CREATE TABLE schedule_template (
  id              BIGINT      NOT NULL PRIMARY KEY,
  therapist_id    BIGINT      NOT NULL,
  store_id        BIGINT      NOT NULL COMMENT '当班门店，可≠归属店',
  weekday         TINYINT     NOT NULL COMMENT '1=周一 … 7=周日',
  start_time      TIME        NOT NULL,
  end_time        TIME        NOT NULL,
  effective_from  DATE        NOT NULL,
  effective_to    DATE        NULL,
  status          TINYINT     NOT NULL DEFAULT 1,
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  KEY idx_tpl_therapist (therapist_id),
  KEY idx_tpl_store (store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='周排班模板';

CREATE TABLE schedule_exception (
  id           BIGINT       NOT NULL PRIMARY KEY,
  therapist_id BIGINT       NOT NULL,
  store_id     BIGINT       NULL COMMENT 'SUPPORT 时的当班店',
  except_date  DATE         NOT NULL,
  type         VARCHAR(16)  NOT NULL COMMENT 'LEAVE / ADJUST / SUPPORT',
  start_time   TIME         NULL,
  end_time     TIME         NULL,
  reason       VARCHAR(255) NULL,
  status       VARCHAR(16)  NOT NULL COMMENT 'PENDING / APPROVED / REJECTED',
  created_by   BIGINT       NULL,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  KEY idx_ex_therapist_date (therapist_id, except_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='请假/调班/跨店支援';

-- V1 不分区。上量后同一 Flyway 给 therapist_slot / bed_slot 加按月 RANGE，禁止只改一张。
CREATE TABLE therapist_slot (
  id              BIGINT      NOT NULL,
  therapist_id    BIGINT      NOT NULL,
  store_id        BIGINT      NOT NULL COMMENT '格子所属门店，报表用',
  slot_date       DATE        NOT NULL,
  slot_no         SMALLINT    NOT NULL COMMENT '0=00:00，40=10:00，78=19:30',
  status          VARCHAR(16) NOT NULL COMMENT 'FREE/LOCKED/BOOKED/BUFFER/REST',
  order_id        BIGINT      NULL,
  hold_id         BIGINT      NULL,
  lock_expire_at  DATETIME(3) NULL,
  price_override_fen BIGINT   NULL COMMENT 'P2 特惠预留',
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_therapist_slot (therapist_id, slot_date, slot_no),
  KEY idx_ts_store_date (store_id, slot_date, status),
  KEY idx_ts_order (order_id),
  KEY idx_ts_hold (hold_id),
  KEY idx_ts_lock (status, lock_expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='技师日历格；V1 不分区';

CREATE TABLE bed_slot (
  id              BIGINT      NOT NULL,
  bed_id          BIGINT      NOT NULL,
  store_id        BIGINT      NOT NULL,
  slot_date       DATE        NOT NULL,
  slot_no         SMALLINT    NOT NULL,
  status          VARCHAR(16) NOT NULL,
  order_id        BIGINT      NULL,
  hold_id         BIGINT      NULL,
  lock_expire_at  DATETIME(3) NULL,
  created_at      DATETIME(3) NOT NULL,
  updated_at      DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_bed_slot (bed_id, slot_date, slot_no),
  KEY idx_bs_store_date (store_id, slot_date, status),
  KEY idx_bs_order (order_id),
  KEY idx_bs_hold (hold_id),
  KEY idx_bs_lock (status, lock_expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='床位日历格；V1 不分区';

CREATE TABLE slot_occupancy (
  id             BIGINT      NOT NULL PRIMARY KEY,
  resource_type  VARCHAR(16) NOT NULL COMMENT 'THERAPIST / BED',
  resource_id    BIGINT      NOT NULL,
  slot_date      DATE        NOT NULL,
  slot_no        SMALLINT    NOT NULL,
  order_id       BIGINT      NOT NULL,
  hold_id        BIGINT      NOT NULL COMMENT '首锁 / 加钟尾 / 改约新持有',
  created_at     DATETIME(3) NOT NULL,
  UNIQUE KEY uk_occ (resource_type, resource_id, slot_date, slot_no),
  KEY idx_occ_order (order_id),
  KEY idx_occ_hold (hold_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='占用账本，INSERT 唯一键防超卖';

CREATE TABLE customer (
  id                   BIGINT        NOT NULL PRIMARY KEY,
  wx_openid            VARCHAR(64)   NULL COMMENT '散客为空；C 端登录按 phone_hash 合并',
  wx_unionid           VARCHAR(64)   NULL,
  phone_cipher         VARBINARY(256) NULL,
  phone_hash           CHAR(64)      NULL COMMENT 'HMAC-SHA256(phone, pepper)',
  nickname             VARCHAR(64)   NULL,
  avatar_url           VARCHAR(512)  NULL,
  no_show_count        INT           NOT NULL DEFAULT 0,
  treatment_consent_at DATETIME(3)   NULL COMMENT '理疗记录知情同意时间',
  created_at           DATETIME(3)   NOT NULL,
  updated_at           DATETIME(3)   NOT NULL,
  deleted_at           DATETIME(3)   NULL,
  UNIQUE KEY uk_customer_openid (wx_openid),
  UNIQUE KEY uk_customer_phone_hash (phone_hash),
  KEY idx_customer_unionid (wx_unionid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='C 端客户';

CREATE TABLE auth_session (
  id           BIGINT      NOT NULL PRIMARY KEY,
  subject_type VARCHAR(16) NOT NULL COMMENT 'CUSTOMER / STAFF',
  subject_id   BIGINT      NOT NULL,
  token_hash   CHAR(64)    NOT NULL,
  expire_at    DATETIME(3) NOT NULL,
  created_at   DATETIME(3) NOT NULL,
  UNIQUE KEY uk_sess_token (token_hash),
  KEY idx_sess_subject (subject_type, subject_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='会话';

CREATE TABLE booking_order (
  id                       BIGINT       NOT NULL PRIMARY KEY,
  order_no                 VARCHAR(32)  NOT NULL,
  request_id               VARCHAR(64)  NULL COMMENT 'C 端下单幂等键',
  hold_id                  BIGINT       NOT NULL COMMENT '当前主持有',
  add_on_hold_id           BIGINT       NULL COMMENT '未支付加钟尾',
  customer_id              BIGINT       NOT NULL,
  store_id                 BIGINT       NOT NULL COMMENT '履约门店=slot 门店',
  therapist_id             BIGINT       NOT NULL,
  therapist_home_store_id  BIGINT       NOT NULL COMMENT '下单时技师归属店快照',
  bed_id                   BIGINT       NOT NULL,
  room_id                  BIGINT       NOT NULL,
  status                   VARCHAR(32)  NOT NULL,
  source                   VARCHAR(16)  NOT NULL COMMENT 'MINI_C / WALK_IN / FRONTDESK',
  service_date             DATE         NOT NULL,
  start_slot_no            SMALLINT     NOT NULL,
  end_slot_no              SMALLINT     NOT NULL COMMENT '左闭右开，含 buffer',
  buffer_slots             SMALLINT     NOT NULL DEFAULT 1,
  origin_price_fen         BIGINT       NOT NULL,
  payable_fen              BIGINT       NOT NULL,
  paid_fen                 BIGINT       NOT NULL DEFAULT 0,
  lock_expire_at           DATETIME(3)  NULL,
  paid_at                  DATETIME(3)  NULL,
  checked_in_at            DATETIME(3)  NULL,
  service_started_at       DATETIME(3)  NULL,
  service_ended_at         DATETIME(3)  NULL,
  cancel_reason            VARCHAR(255) NULL,
  version                  INT          NOT NULL DEFAULT 0,
  remark                   VARCHAR(255) NULL,
  created_at               DATETIME(3)  NOT NULL,
  updated_at               DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_order_no (order_no),
  UNIQUE KEY uk_order_request (request_id),
  KEY idx_order_hold (hold_id),
  KEY idx_order_addon_hold (add_on_hold_id),
  KEY idx_order_customer (customer_id, created_at),
  KEY idx_order_store_status (store_id, status, created_at),
  KEY idx_order_therapist_date (therapist_id, service_date),
  KEY idx_order_date (service_date, store_id),
  KEY idx_order_pending (status, lock_expire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='预约单';

CREATE TABLE order_item (
  id                BIGINT      NOT NULL PRIMARY KEY,
  order_id          BIGINT      NOT NULL,
  item_type         VARCHAR(16) NOT NULL COMMENT 'PROJECT / ADD_ON',
  project_id        BIGINT      NOT NULL,
  project_name      VARCHAR(64) NOT NULL,
  duration_minutes  SMALLINT    NOT NULL,
  buffer_minutes    SMALLINT    NOT NULL,
  quantity          SMALLINT    NOT NULL DEFAULT 1,
  unit_price_fen    BIGINT      NOT NULL,
  amount_fen        BIGINT      NOT NULL,
  start_slot_no     SMALLINT    NOT NULL,
  end_slot_no       SMALLINT    NOT NULL,
  created_at        DATETIME(3) NOT NULL,
  KEY idx_item_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='订单行';

CREATE TABLE payment (
  id                 BIGINT       NOT NULL PRIMARY KEY,
  payment_no         VARCHAR(64)  NOT NULL,
  order_id           BIGINT       NOT NULL,
  channel            VARCHAR(16)  NOT NULL COMMENT 'WECHAT / CASH',
  amount_fen         BIGINT       NOT NULL,
  status             VARCHAR(16)  NOT NULL COMMENT 'PENDING / SUCCESS / FAILED / CLOSED',
  wx_prepay_id       VARCHAR(64)  NULL,
  wx_transaction_id  VARCHAR(64)  NULL,
  paid_at            DATETIME(3)  NULL,
  notify_raw         JSON         NULL,
  created_at         DATETIME(3)  NOT NULL,
  updated_at         DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_payment_no (payment_no),
  UNIQUE KEY uk_wx_txn (wx_transaction_id),
  KEY idx_pay_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='支付单；回调按 payment_no 幂等';

CREATE TABLE refund (
  id            BIGINT       NOT NULL PRIMARY KEY,
  refund_no     VARCHAR(64)  NOT NULL,
  payment_id    BIGINT       NOT NULL,
  order_id      BIGINT       NOT NULL,
  amount_fen    BIGINT       NOT NULL,
  reason        VARCHAR(255) NULL,
  status        VARCHAR(16)  NOT NULL COMMENT 'PENDING / SUCCESS / FAILED / MANUAL / WAIT_APPROVAL',
  wx_refund_id  VARCHAR(64)  NULL,
  operator_id   BIGINT       NULL,
  created_at    DATETIME(3)  NOT NULL,
  updated_at    DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_refund_no (refund_no),
  KEY idx_refund_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='退款单';

CREATE TABLE service_record (
  id            BIGINT      NOT NULL PRIMARY KEY,
  order_id      BIGINT      NOT NULL,
  therapist_id  BIGINT      NOT NULL,
  customer_id   BIGINT      NOT NULL,
  store_id      BIGINT      NOT NULL,
  started_at    DATETIME(3) NULL,
  ended_at      DATETIME(3) NULL,
  created_at    DATETIME(3) NOT NULL,
  KEY idx_svc_order (order_id),
  KEY idx_svc_therapist (therapist_id, started_at),
  KEY idx_svc_customer (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='服务段；一单可多行（换师）';

CREATE TABLE treatment_note (
  id                 BIGINT      NOT NULL PRIMARY KEY,
  service_record_id  BIGINT      NOT NULL,
  order_id           BIGINT      NOT NULL,
  author_staff_id    BIGINT      NOT NULL,
  content            TEXT        NOT NULL,
  created_at         DATETIME(3) NOT NULL,
  KEY idx_note_svc (service_record_id),
  KEY idx_note_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='理疗记录，只增不改不删；保留 3 年';

CREATE TABLE staff_user (
  id             BIGINT        NOT NULL PRIMARY KEY,
  username       VARCHAR(32)   NOT NULL,
  password_hash  VARCHAR(128)  NULL COMMENT 'PC 登录；小程序可空',
  name           VARCHAR(32)   NOT NULL,
  phone_cipher   VARBINARY(256) NULL,
  phone_hash     CHAR(64)      NULL,
  wx_openid      VARCHAR(64)   NULL,
  status         TINYINT       NOT NULL DEFAULT 1,
  created_at     DATETIME(3)   NOT NULL,
  updated_at     DATETIME(3)   NOT NULL,
  deleted_at     DATETIME(3)   NULL,
  UNIQUE KEY uk_staff_username (username),
  UNIQUE KEY uk_staff_phone_hash (phone_hash),
  UNIQUE KEY uk_staff_openid (wx_openid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工账号';

CREATE TABLE role (
  id    BIGINT      NOT NULL PRIMARY KEY,
  code  VARCHAR(32) NOT NULL,
  name  VARCHAR(32) NOT NULL,
  UNIQUE KEY uk_role_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色';

CREATE TABLE permission (
  id    BIGINT      NOT NULL PRIMARY KEY,
  code  VARCHAR(64) NOT NULL,
  name  VARCHAR(64) NOT NULL,
  UNIQUE KEY uk_perm_code (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='功能权限';

CREATE TABLE role_permission (
  role_id        BIGINT NOT NULL,
  permission_id  BIGINT NOT NULL,
  PRIMARY KEY (role_id, permission_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='角色权限';

CREATE TABLE staff_role (
  staff_user_id  BIGINT NOT NULL,
  role_id        BIGINT NOT NULL,
  PRIMARY KEY (staff_user_id, role_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='员工角色';

CREATE TABLE data_scope (
  id             BIGINT      NOT NULL PRIMARY KEY,
  staff_user_id  BIGINT      NOT NULL,
  scope_type     VARCHAR(16) NOT NULL COMMENT 'ALL / STORE / SELF',
  store_id       BIGINT      NULL,
  KEY idx_scope_staff (staff_user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='数据域；P0 不做 REGION';

CREATE TABLE audit_log (
  id             BIGINT       NOT NULL PRIMARY KEY,
  actor_id       BIGINT       NULL,
  actor_type     VARCHAR(16)  NOT NULL COMMENT 'STAFF / CUSTOMER / SYSTEM',
  action         VARCHAR(64)  NOT NULL,
  resource_type  VARCHAR(32)  NOT NULL,
  resource_id    BIGINT       NULL,
  store_id       BIGINT       NULL,
  ip             VARCHAR(64)  NULL,
  user_agent     VARCHAR(255) NULL,
  request_id     VARCHAR(64)  NULL,
  before_json    JSON         NULL,
  after_json     JSON         NULL,
  created_at     DATETIME(3)  NOT NULL,
  KEY idx_audit_actor (actor_id, created_at),
  KEY idx_audit_resource (resource_type, resource_id),
  KEY idx_audit_store (store_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='审计，不可删，保留 3 年';

CREATE TABLE idempotency_record (
  id             BIGINT      NOT NULL PRIMARY KEY,
  scope          VARCHAR(32) NOT NULL,
  request_id     VARCHAR(64) NOT NULL,
  status         VARCHAR(16) NOT NULL COMMENT 'PROCESSING / DONE',
  version        INT         NOT NULL DEFAULT 0,
  locked_by      VARCHAR(64) NULL,
  response_code  INT         NULL,
  response_body  JSON        NULL,
  created_at     DATETIME(3) NOT NULL,
  updated_at     DATETIME(3) NOT NULL,
  expire_at      DATETIME(3) NOT NULL COMMENT '仅 PROCESSING 接管窗',
  UNIQUE KEY uk_idem (scope, request_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='写接口幂等；唯一键保留 24h';

CREATE TABLE delayed_job (
  id           BIGINT       NOT NULL PRIMARY KEY,
  job_type     VARCHAR(32)  NOT NULL COMMENT 'RELEASE_LOCK / RELEASE_ADDON',
  biz_key      VARCHAR(64)  NOT NULL COMMENT 'hold:{holdId}',
  payload      JSON         NOT NULL,
  run_at       DATETIME(3)  NOT NULL,
  status       VARCHAR(16)  NOT NULL COMMENT 'PENDING / RUNNING / DONE / FAILED',
  locked_by    VARCHAR(64)  NULL,
  locked_at    DATETIME(3)  NULL,
  lease_until  DATETIME(3)  NULL,
  retry_count  INT          NOT NULL DEFAULT 0,
  last_error   VARCHAR(512) NULL,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_job_biz (job_type, biz_key),
  KEY idx_job_due (status, run_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='延时任务；SKIP LOCKED 领取';

CREATE TABLE outbox_event (
  id           BIGINT      NOT NULL PRIMARY KEY,
  topic        VARCHAR(64) NOT NULL,
  payload      JSON        NOT NULL,
  status       VARCHAR(16) NOT NULL COMMENT 'NEW / SENT / FAILED',
  retry_count  INT         NOT NULL DEFAULT 0,
  created_at   DATETIME(3) NOT NULL,
  sent_at      DATETIME(3) NULL,
  KEY idx_outbox_status (status, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='本地消息落库；P0 不发送订阅消息';

CREATE TABLE workflow_instance (
  id             BIGINT      NOT NULL PRIMARY KEY,
  workflow_type  VARCHAR(32) NOT NULL COMMENT 'RESCHEDULE / ADD_ON / SWAP_THERAPIST / REFUND',
  order_id       BIGINT      NOT NULL,
  status         VARCHAR(16) NOT NULL COMMENT 'RUNNING / SUCCESS / COMPENSATING / FAILED / MANUAL / WAIT_APPROVAL',
  context_json   JSON        NOT NULL,
  created_by     BIGINT      NULL,
  created_at     DATETIME(3) NOT NULL,
  updated_at     DATETIME(3) NOT NULL,
  KEY idx_wf_order (order_id),
  KEY idx_wf_status (status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='脏流程实例';

CREATE TABLE workflow_step (
  id           BIGINT       NOT NULL PRIMARY KEY,
  instance_id  BIGINT       NOT NULL,
  step_no      SMALLINT     NOT NULL,
  step_name    VARCHAR(64)  NOT NULL,
  status       VARCHAR(16)  NOT NULL COMMENT 'PENDING / DONE / COMPENSATED / FAILED',
  request_json JSON         NULL,
  result_json  JSON         NULL,
  error_msg    VARCHAR(512) NULL,
  created_at   DATETIME(3)  NOT NULL,
  updated_at   DATETIME(3)  NOT NULL,
  KEY idx_wfs_inst (instance_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='脏流程步骤';

CREATE TABLE human_task (
  id                    BIGINT       NOT NULL PRIMARY KEY,
  workflow_instance_id  BIGINT       NULL,
  order_id              BIGINT       NULL,
  task_type             VARCHAR(32)  NOT NULL,
  biz_key               VARCHAR(64)  NULL COMMENT 'leave:{exId} / unknown_pay:{no} 幂等',
  title                 VARCHAR(128) NOT NULL,
  detail                JSON         NULL,
  status                VARCHAR(16)  NOT NULL COMMENT 'OPEN / DONE / IGNORED',
  assignee_role         VARCHAR(32)  NULL,
  store_id              BIGINT       NULL,
  created_at            DATETIME(3)  NOT NULL,
  resolved_at           DATETIME(3)  NULL,
  resolved_by           BIGINT       NULL,
  UNIQUE KEY uk_ht_biz (biz_key),
  KEY idx_ht_status (status, store_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='人工介入队列';

CREATE TABLE order_change_log (
  id           BIGINT      NOT NULL PRIMARY KEY,
  order_id     BIGINT      NOT NULL,
  change_type  VARCHAR(32) NOT NULL,
  before_json  JSON        NULL,
  after_json   JSON        NULL,
  operator_id  BIGINT      NULL,
  created_at   DATETIME(3) NOT NULL,
  KEY idx_ocl_order (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='改约/换人/加钟痕迹';

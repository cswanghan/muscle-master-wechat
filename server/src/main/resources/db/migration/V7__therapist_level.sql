-- 等级配置与晋升流水。P1 只走「系统算达标 → admin 确认」，不自动改 therapist.level；
-- price_delta_fen 先存不用，P2 接入定价时才读。
CREATE TABLE therapist_level_config (
  level                  VARCHAR(16) NOT NULL COMMENT '等级码',
  sort_no                INT         NOT NULL COMMENT '由低到高',
  display_name           VARCHAR(32) NOT NULL COMMENT '展示名',
  price_delta_fen        INT         NOT NULL DEFAULT 0 COMMENT '相对基价的加价（分），P2 生效',
  min_review_count       INT         NOT NULL COMMENT '晋升门槛：累计评价数',
  min_positive_rate_x100 INT         NOT NULL COMMENT '晋升门槛：累计好评率 x100',
  status                 TINYINT     NOT NULL DEFAULT 1,
  PRIMARY KEY (level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技师等级配置';

-- 门槛用累计而非 30 天：30 天窗口会让技师休一次长假就掉出门槛。
-- 展示用 30 天（近况），晋升用累计（资历）。
INSERT INTO therapist_level_config
  (level, sort_no, display_name, price_delta_fen, min_review_count, min_positive_rate_x100, status) VALUES
  ('JUNIOR', 1, '初级',    0, 0,   0,    1),
  ('MIDDLE', 2, '中级', 2000, 30,  8500, 1),
  ('SENIOR', 3, '资深', 4000, 80,  9000, 1),
  ('CHIEF',  4, '首席', 8000, 200, 9500, 1);

CREATE TABLE therapist_level_log (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  therapist_id  BIGINT       NOT NULL,
  from_level    VARCHAR(16)      NULL,
  to_level      VARCHAR(16)  NOT NULL,
  reason        VARCHAR(64)  NOT NULL COMMENT 'AUTO_ELIGIBLE/ADMIN_CONFIRM/ADMIN_REJECT/ADMIN_MANUAL',
  operator_id   BIGINT           NULL,
  snapshot_json VARCHAR(512)     NULL COMMENT '达标时的统计快照，事后可复核',
  status        TINYINT      NOT NULL COMMENT '0=待确认 1=已生效 2=已驳回',
  created_at    DATETIME(3)  NOT NULL,
  decided_at    DATETIME(3)      NULL,
  PRIMARY KEY (id),
  KEY idx_level_log_therapist (therapist_id, created_at),
  KEY idx_level_log_pending   (status, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='技师等级晋升流水';

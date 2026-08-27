-- P0 评价体系：一单一评 + 30 天滚动统计物化表。
-- 设计见 docs/p1-review-level-design.md §4。等级/价格带在 V7（P1）与 P2，本版不涉及资金与定价。

CREATE TABLE order_review (
  id            BIGINT       NOT NULL PRIMARY KEY,
  order_id      BIGINT       NOT NULL,
  customer_id   BIGINT       NOT NULL,
  therapist_id  BIGINT       NOT NULL,
  store_id      BIGINT       NOT NULL,
  score         TINYINT      NOT NULL COMMENT '1-5 星',
  positive      TINYINT      NOT NULL COMMENT '1=好评；落库时按当时口径定格，改口径不追溯历史',
  tags          VARCHAR(255) NULL     COMMENT '标签码，逗号分隔，白名单见 ReviewTags',
  content       VARCHAR(500) NULL,
  anonymous     TINYINT      NOT NULL DEFAULT 0,
  status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1=正常 0=下架',
  created_at    DATETIME(3)  NOT NULL,
  UNIQUE KEY uk_review_order (order_id),
  KEY idx_review_therapist (therapist_id, created_at),
  KEY idx_review_customer (customer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='一单一评；uk_review_order 让重复提交天然幂等';

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci COMMENT='30 天滚动统计；GET /c/availability 一次返回整店技师，读侧必须是主键批量 IN 而不是每人一次聚合';

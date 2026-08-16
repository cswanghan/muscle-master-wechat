-- 顾客评价：一单一评，评分 1–5。
CREATE TABLE review (
  id            BIGINT       NOT NULL COMMENT '雪花 ID',
  order_id      BIGINT       NOT NULL COMMENT '订单 ID',
  customer_id   BIGINT       NOT NULL COMMENT '顾客 ID',
  therapist_id  BIGINT       NOT NULL COMMENT '技师 ID',
  store_id      BIGINT       NOT NULL COMMENT '门店 ID',
  score         TINYINT      NOT NULL COMMENT '评分 1-5',
  tags          VARCHAR(255)     NULL COMMENT '逗号分隔标签',
  content       VARCHAR(500)     NULL COMMENT '评价正文',
  created_at    DATETIME(3)  NOT NULL COMMENT '创建时间',
  deleted_at    DATETIME(3)      NULL COMMENT '逻辑删除',
  PRIMARY KEY (id),
  UNIQUE KEY uk_review_order (order_id),
  KEY idx_review_therapist (therapist_id, created_at),
  KEY idx_review_customer (customer_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='顾客评价';

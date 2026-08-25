-- 技师 30 天滚动统计物化表。评价本体在 V6 的 review 表，这里只放它的派生量。
-- 设计见 docs/p1-review-level-design.md §4。等级/价格带是后续版本，本版不涉及资金与定价。

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
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='30 天滚动统计；GET /c/availability 一次返回整店技师，读侧必须是主键批量 IN 而不是每人一次聚合';

-- 日更重算按时间窗扫全表评价（ReviewRepository#listSince）。V6 那两个索引都以 therapist_id /
-- customer_id 打头，跨技师的窗口查询用不上，缺这条就是全表扫。
ALTER TABLE review ADD KEY idx_review_created (created_at);

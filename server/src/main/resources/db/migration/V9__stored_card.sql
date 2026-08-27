-- 储值卡。余额拆本金 / 赠送两笔，因为两者的退法不同：
-- 退卡只退本金，赠送作废；订单退款则按当初的扣款比例原路退回，账才平得上。
CREATE TABLE stored_card (
  id             BIGINT      NOT NULL COMMENT '雪花 ID',
  card_no        VARCHAR(32) NOT NULL COMMENT '卡号',
  customer_id    BIGINT      NOT NULL,
  store_id       BIGINT      NOT NULL COMMENT '发卡门店',
  principal_fen  BIGINT      NOT NULL DEFAULT 0 COMMENT '本金余额，可退',
  bonus_fen      BIGINT      NOT NULL DEFAULT 0 COMMENT '赠送余额，不可退',
  status         TINYINT     NOT NULL DEFAULT 1 COMMENT '1=正常 0=停用',
  created_at     DATETIME(3) NOT NULL,
  updated_at     DATETIME(3) NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_no (card_no),
  KEY idx_card_customer (customer_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储值卡；余额是负债，不计营收';

-- 每一次余额变动都留痕，且本金 / 赠送分开记：
-- 退款要按当初扣的比例还回去，只记总额就还不准了。
CREATE TABLE card_transaction (
  id                  BIGINT       NOT NULL COMMENT '雪花 ID',
  card_id             BIGINT       NOT NULL,
  customer_id         BIGINT       NOT NULL,
  type                VARCHAR(16)  NOT NULL COMMENT 'TOPUP / CONSUME / REFUND',
  principal_delta_fen BIGINT       NOT NULL COMMENT '本金变动，消费为负',
  bonus_delta_fen     BIGINT       NOT NULL COMMENT '赠送变动，消费为负',
  order_id            BIGINT           NULL COMMENT '消费 / 退款关联订单',
  payment_id          BIGINT           NULL COMMENT '充值对应的收款单',
  seller_therapist_id BIGINT           NULL COMMENT '卡销归属技师，提成用',
  request_id          VARCHAR(64)      NULL COMMENT '幂等键',
  created_at          DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_card_txn_request (request_id),
  KEY idx_card_txn_card (card_id, created_at),
  KEY idx_card_txn_seller (seller_therapist_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='储值卡流水';

-- 卡扣走 payment 表的一条 CARD 记录：一个订单本来就能挂多笔支付，
-- 混合支付因此不需要新表 —— 一笔 CARD + 一笔 WECHAT，退款也按笔各退各的。
ALTER TABLE payment
  MODIFY COLUMN channel VARCHAR(16) NOT NULL COMMENT 'WECHAT / CASH / CARD';

-- 顾客是否点名这位技师。与 source（下单渠道）是正交的两个维度：
-- 渠道说"从哪下的单"，designated 说"是不是冲着这个人来的"，指定加成只认后者。
ALTER TABLE booking_order
  ADD COLUMN designated TINYINT NOT NULL DEFAULT 0 COMMENT '1=顾客指定技师' AFTER source;

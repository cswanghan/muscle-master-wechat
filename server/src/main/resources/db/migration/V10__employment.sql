-- 入离职流水。staff_user.status 是当前态，这张表是过程：
-- 谁在什么时候入的、离的、为什么，离职后复职也只是再加一行。
CREATE TABLE employment_log (
  id           BIGINT       NOT NULL COMMENT '雪花 ID',
  staff_id     BIGINT       NOT NULL,
  action       VARCHAR(16)  NOT NULL COMMENT 'ONBOARD / OFFBOARD / REHIRE',
  effective_on DATE         NOT NULL COMMENT '生效日',
  reason       VARCHAR(128)     NULL,
  operator_id  BIGINT           NULL,
  created_at   DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_employment_staff (staff_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='员工入离职流水';

-- 入离职是建账号、改账号状态，比改目录重。单独一个权限，不搭 catalog:therapist 的便车。
INSERT INTO permission (id, code, name) VALUES
  (2000000000000000117, 'staff:manage', '员工-入离职');

-- SUPER_ADMIN(1) / OPS(3) / REGION_MANAGER(4) / STORE_MANAGER(5)
INSERT INTO role_permission (role_id, permission_id) VALUES
  (2000000000000000001, 2000000000000000117),
  (2000000000000000003, 2000000000000000117),
  (2000000000000000004, 2000000000000000117),
  (2000000000000000005, 2000000000000000117);

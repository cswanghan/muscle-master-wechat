CREATE TABLE human_task (
  id                    BIGINT       NOT NULL PRIMARY KEY,
  workflow_instance_id  BIGINT       NULL,
  order_id              BIGINT       NULL,
  task_type             VARCHAR(32)  NOT NULL,
  biz_key               VARCHAR(64)  NULL,
  title                 VARCHAR(128) NOT NULL,
  detail                VARCHAR(512) NULL,
  status                VARCHAR(16)  NOT NULL,
  assignee_role         VARCHAR(32)  NULL,
  store_id              BIGINT       NULL,
  created_at            TIMESTAMP    NOT NULL,
  resolved_at           TIMESTAMP    NULL,
  resolved_by           BIGINT       NULL,
  UNIQUE (biz_key)
);

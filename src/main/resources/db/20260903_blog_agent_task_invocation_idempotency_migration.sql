-- Adds the Agent invocation-backed idempotency key for durable blog task creation.
--
-- 幂等守卫：新装基线 blog_agent_schema.sql 里已经有这一列与唯一键，所以本迁移在新库上
-- 必须是 no-op。原来的裸 ALTER 在全新库上会以 ERROR 1060 Duplicate column name 直接失败，
-- 而它与同形状的 20260914_agent_message_invocation_id 迁移不一致（那个有守卫）。
-- 列与索引分别判存在性：老库可能只缺其中一个（例如列已加、索引建失败后重跑）。
SET @client_request_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'blog_agent_task'
    AND COLUMN_NAME = 'client_request_id'
);
SET @client_request_column_sql := IF(
  @client_request_column_exists = 0,
  'ALTER TABLE `blog_agent_task` ADD COLUMN `client_request_id` VARCHAR(64) NULL COMMENT ''创建命令幂等标识，在同一用户内唯一'' AFTER `user_id`',
  'SELECT 1'
);
PREPARE client_request_column_statement FROM @client_request_column_sql;
EXECUTE client_request_column_statement;
DEALLOCATE PREPARE client_request_column_statement;

SET @client_request_index_exists := (
  SELECT COUNT(*) FROM information_schema.STATISTICS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'blog_agent_task'
    AND INDEX_NAME = 'uk_blog_agent_task_user_request'
);
SET @client_request_index_sql := IF(
  @client_request_index_exists = 0,
  'ALTER TABLE `blog_agent_task` ADD UNIQUE KEY `uk_blog_agent_task_user_request` (`user_id`, `client_request_id`)',
  'SELECT 1'
);
PREPARE client_request_index_statement FROM @client_request_index_sql;
EXECUTE client_request_index_statement;
DEALLOCATE PREPARE client_request_index_statement;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260903_blog_agent_task_invocation_idempotency');

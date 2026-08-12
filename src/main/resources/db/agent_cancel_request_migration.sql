-- Agent conversation cancel-request column.
-- Apply after agent_conversation_migration.sql. Safe for both upgraded
-- databases and fresh installs whose baseline already contains the column.
CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移应用时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

SET @sql_agent_cancel_request = IF(
  NOT EXISTS (
    SELECT 1
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_conversation'
      AND COLUMN_NAME = 'cancel_requested'
  ),
  'ALTER TABLE `agent_conversation`
     ADD COLUMN `cancel_requested` TINYINT NOT NULL DEFAULT 0
       COMMENT ''用户取消请求标记：0=未请求，1=执行中轮询到后立即停止；新一轮执行开始时复位''
       AFTER `error_message`',
  'SELECT 1'
);
PREPARE stmt_agent_cancel_request FROM @sql_agent_cancel_request;
EXECUTE stmt_agent_cancel_request;
DEALLOCATE PREPARE stmt_agent_cancel_request;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_agent_cancel_request');

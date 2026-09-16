-- Agent conversation execution-generation isolation.
-- Apply after agent_conversation_migration.sql and agent_cancel_request_migration.sql.
CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移应用时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

SET @sql_agent_run_version = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_conversation'
      AND COLUMN_NAME = 'run_version'
  ),
  'ALTER TABLE `agent_conversation`
     ADD COLUMN `run_version` BIGINT NOT NULL DEFAULT 0
       COMMENT ''当前执行代次；每次成功领取新一轮后递增，用于拒绝旧工作线程迟到写入''
       AFTER `error_message`',
  'SELECT 1'
);
PREPARE stmt_agent_run_version FROM @sql_agent_run_version;
EXECUTE stmt_agent_run_version;
DEALLOCATE PREPARE stmt_agent_run_version;

SET @sql_agent_event_run_version = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_event'
      AND COLUMN_NAME = 'run_version'
  ),
  'ALTER TABLE `agent_event`
     ADD COLUMN `run_version` BIGINT NOT NULL DEFAULT 0
       COMMENT ''事件所属执行代次；断线续传固定在该代次内回放''
       AFTER `conversation_id`',
  'SELECT 1'
);
PREPARE stmt_agent_event_run_version FROM @sql_agent_event_run_version;
EXECUTE stmt_agent_event_run_version;
DEALLOCATE PREPARE stmt_agent_event_run_version;

SET @sql_agent_event_run_index = IF(
  NOT EXISTS (
    SELECT 1 FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_event'
      AND INDEX_NAME = 'idx_agent_event_conversation_run_id'
  ),
  'ALTER TABLE `agent_event`
     ADD INDEX `idx_agent_event_conversation_run_id` (`conversation_id`, `run_version`, `id`)',
  'SELECT 1'
);
PREPARE stmt_agent_event_run_index FROM @sql_agent_event_run_index;
EXECUTE stmt_agent_event_run_index;
DEALLOCATE PREPARE stmt_agent_event_run_index;

SET @sql_agent_event_type_comment = IF(
  EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_event'
      AND COLUMN_NAME = 'event_type'
  ),
  'ALTER TABLE `agent_event`
     MODIFY COLUMN `event_type` VARCHAR(24) NOT NULL
       COMMENT ''事件类型：thought/tool_start/tool_progress/tool_delta/tool_end/tool_error/answer_delta/answer/complete/error''',
  'SELECT 1'
);
PREPARE stmt_agent_event_type_comment FROM @sql_agent_event_type_comment;
EXECUTE stmt_agent_event_type_comment;
DEALLOCATE PREPARE stmt_agent_event_type_comment;

-- 旧版本工作线程无法参与执行代次围栏；升级时关闭遗留执行，允许用户安全重试。
SET @sql_agent_close_legacy_run = IF(
  EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'agent_conversation'
      AND COLUMN_NAME = 'status'
  ),
  'UPDATE `agent_conversation`
   SET `status` = ''failed'',
       `error_message` = ''系统升级期间上一轮执行中断，可重新发送消息重试'',
       `updated_at` = CURRENT_TIMESTAMP
   WHERE `status` = ''running''
     AND `run_version` = 0',
  'SELECT 1'
);
PREPARE stmt_agent_close_legacy_run FROM @sql_agent_close_legacy_run;
EXECUTE stmt_agent_close_legacy_run;
DEALLOCATE PREPARE stmt_agent_close_legacy_run;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260812_agent_run_version');

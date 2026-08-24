-- 智能体消息附件快照：支持图片视觉输入和文档输入。
SET @attachment_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_message'
    AND COLUMN_NAME = 'attachments_json'
);
SET @attachment_column_sql := IF(
  @attachment_column_exists = 0,
  'ALTER TABLE `agent_message` ADD COLUMN `attachments_json` LONGTEXT NULL COMMENT ''用户消息附件快照JSON，包含URL、原始文件名、MIME类型和字节数'' AFTER `content`',
  'SELECT 1'
);
PREPARE attachment_column_statement FROM @attachment_column_sql;
EXECUTE attachment_column_statement;
DEALLOCATE PREPARE attachment_column_statement;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260824_agent_message_attachment');

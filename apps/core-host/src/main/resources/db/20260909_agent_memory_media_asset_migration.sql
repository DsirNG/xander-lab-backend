-- 为长期记忆增加用户图片素材引用，支持跨会话原图复用。
SET @agent_memory_media_asset_column_exists := (
  SELECT COUNT(*) FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA = DATABASE()
    AND TABLE_NAME = 'agent_memory'
    AND COLUMN_NAME = 'media_asset_id'
);
SET @agent_memory_media_asset_column_sql := IF(
  @agent_memory_media_asset_column_exists = 0,
  'ALTER TABLE `agent_memory` ADD COLUMN `media_asset_id` BIGINT NULL COMMENT ''关联用户私有图片素材ID，记忆图片时使用'' AFTER `content`',
  'SELECT 1'
);
PREPARE agent_memory_media_asset_column_statement FROM @agent_memory_media_asset_column_sql;
EXECUTE agent_memory_media_asset_column_statement;
DEALLOCATE PREPARE agent_memory_media_asset_column_statement;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260909_agent_memory_media_asset');

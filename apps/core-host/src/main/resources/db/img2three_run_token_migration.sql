-- 为现有图片转 3D 任务增加执行代次令牌，防止超时后的旧工作线程覆盖新重试。
-- Apply manually. Safe to re-run via migration marker.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL COMMENT '迁移标识键',
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '迁移应用时间',
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='数据库迁移执行标记';

START TRANSACTION;

SET @img2three_run_token_migrated = EXISTS(
  SELECT 1
  FROM `xander_schema_migration`
  WHERE `migration_key` = '20260804_img2three_run_token'
);

SET @sql_img2three_run_token = IF(
  @img2three_run_token_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'img2three_task'
      AND COLUMN_NAME = 'run_token'
  ),
  'ALTER TABLE `img2three_task`
     ADD COLUMN `run_token` VARCHAR(36) NULL COMMENT ''当前执行代次令牌，用于隔离超时重试与旧工作线程'' AFTER `stage`',
  'SELECT 1'
);
PREPARE stmt_img2three_run_token FROM @sql_img2three_run_token;
EXECUTE stmt_img2three_run_token;
DEALLOCATE PREPARE stmt_img2three_run_token;

SET @sql_img2three_run_token_comment = IF(
  EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'img2three_task'
      AND COLUMN_NAME = 'run_token'
  ),
  'ALTER TABLE `img2three_task`
     MODIFY COLUMN `run_token` VARCHAR(36) NULL COMMENT ''当前执行代次令牌，用于隔离超时重试与旧工作线程''',
  'SELECT 1'
);
PREPARE stmt_img2three_run_token_comment FROM @sql_img2three_run_token_comment;
EXECUTE stmt_img2three_run_token_comment;
DEALLOCATE PREPARE stmt_img2three_run_token_comment;

SET @sql_img2three_task_comment = IF(
  EXISTS (
    SELECT 1 FROM information_schema.TABLES
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'img2three_task'
  ),
  'ALTER TABLE `img2three_task` COMMENT = ''图片转过程式 Three.js 持久化任务''',
  'SELECT 1'
);
PREPARE stmt_img2three_task_comment FROM @sql_img2three_task_comment;
EXECUTE stmt_img2three_task_comment;
DEALLOCATE PREPARE stmt_img2three_task_comment;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260804_img2three_run_token');

COMMIT;

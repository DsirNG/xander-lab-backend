-- Add content type (PLAIN/HTML) and layout template for scheduled emails.
-- Apply manually. Safe to re-run via migration marker.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL,
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;

SET @email_reminder_content_template_migrated = EXISTS(
  SELECT 1
  FROM `xander_schema_migration`
  WHERE `migration_key` = '20260725_email_reminder_content_template'
);

SET @sql_content = IF(
  @email_reminder_content_template_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'content_type'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `content_type` VARCHAR(16) NOT NULL DEFAULT ''PLAIN'' COMMENT ''内容格式：PLAIN/HTML'' AFTER `message`,
     ADD COLUMN `template_id` VARCHAR(32) NOT NULL DEFAULT ''none'' COMMENT ''邮件版式模板：none/classic/minimal/card/notice'' AFTER `content_type`',
  'SELECT 1'
);
PREPARE stmt_content FROM @sql_content;
EXECUTE stmt_content;
DEALLOCATE PREPARE stmt_content;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260725_email_reminder_content_template');

COMMIT;

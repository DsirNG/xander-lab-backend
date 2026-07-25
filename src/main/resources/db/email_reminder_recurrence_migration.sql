-- Add recurrence + timezone support for scheduled emails.
-- Apply manually. Safe to re-run via migration marker.

CREATE TABLE IF NOT EXISTS `xander_schema_migration` (
  `migration_key` VARCHAR(128) NOT NULL,
  `applied_at` TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`migration_key`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

START TRANSACTION;

SET @email_reminder_recurrence_migrated = EXISTS(
  SELECT 1
  FROM `xander_schema_migration`
  WHERE `migration_key` = '20260725_email_reminder_recurrence'
);

-- frequency: ONCE | DAILY | WEEKLY | MONTHLY | CUSTOM
SET @sql_freq = IF(
  @email_reminder_recurrence_migrated = 0
  AND NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'email_reminder_task'
      AND COLUMN_NAME = 'frequency'
  ),
  'ALTER TABLE `email_reminder_task`
     ADD COLUMN `frequency` VARCHAR(16) NOT NULL DEFAULT ''ONCE'' AFTER `message`,
     ADD COLUMN `timezone` VARCHAR(64) NOT NULL DEFAULT ''Asia/Shanghai'' AFTER `frequency`,
     ADD COLUMN `send_time` VARCHAR(5) NULL AFTER `timezone`,
     ADD COLUMN `recurrence_day` INT NULL AFTER `send_time`,
     ADD COLUMN `interval_days` INT NULL AFTER `recurrence_day`',
  'SELECT 1'
);
PREPARE stmt_freq FROM @sql_freq;
EXECUTE stmt_freq;
DEALLOCATE PREPARE stmt_freq;

INSERT IGNORE INTO `xander_schema_migration` (`migration_key`)
VALUES ('20260725_email_reminder_recurrence');

COMMIT;
